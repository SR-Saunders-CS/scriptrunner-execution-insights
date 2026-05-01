// ScriptRunner for Jira Data Center — Execution Insights Report
// Run from: ScriptRunner Script Console
// Output:   HTML report rendered in the Script Console result panel
//
// This script surfaces execution data for every ScriptRunner feature type
// configured on this Jira instance. It reads from the same data sources
// the ScriptRunner admin UI uses — no external dependencies, no changes
// made to any data.
//
// ─────────────────────────────────────────────────────────────────────────
// CONFIGURATION
// ─────────────────────────────────────────────────────────────────────────
//
// Script Listeners cannot be auto-discovered — add each one manually below.
// Find the UUID by editing a listener in SR admin and copying it from the URL.
//
Map<String, String> listenerIds = [
    // "paste-uuid-here" : "Listener display name",
] as Map<String, String>

// Set to true if this is a multi-node Data Center cluster.
boolean MULTI_NODE = false

// ─────────────────────────────────────────────────────────────────────────

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome
import com.atlassian.jira.workflow.JiraWorkflow
import com.atlassian.jira.workflow.WorkflowManager
import com.atlassian.scheduler.SchedulerService
import com.atlassian.scheduler.status.JobDetails
import com.onresolve.scriptrunner.runner.diag.DiagnosticsManager
import com.onresolve.scriptrunner.runner.diag.ScriptRunResult
import com.onresolve.scriptrunner.scheduled.ScheduledScriptJobManager
import com.onresolve.scriptrunner.scheduled.model.AbstractScheduledJobCommand
import com.onresolve.scriptrunner.scheduled.model.AbstractCustomScheduledJobCommand
import com.onresolve.jira.behaviours.BehaviourManager
import com.onresolve.jira.behaviours.types.BehaviourEditState
import com.opensymphony.workflow.loader.ActionDescriptor
import com.opensymphony.workflow.loader.FunctionDescriptor
import groovy.json.JsonSlurper
import groovy.sql.Sql
import groovy.xml.MarkupBuilder
import java.sql.Connection
import org.ofbiz.core.entity.ConnectionFactory
import org.ofbiz.core.entity.DelegatorInterface
import org.rrd4j.ConsolFun
import org.rrd4j.core.FetchData
import org.rrd4j.core.RrdDb

// ── Time windows ──────────────────────────────────────────────────────────
final long NOW     = System.currentTimeMillis()
final long D30     = 30L * 86_400_000L
final long D60     = 60L * 86_400_000L
final long D90     = 90L * 86_400_000L
final long NOW_SEC = (NOW / 1000L) as long
final long D30_SEC = 30L * 86_400L
final long D60_SEC = 60L * 86_400L
final long D90_SEC = 90L * 86_400L

List<String> loadWarnings = []

class Row {
    String type
    String name
    String script
    String enabled
    String owner
    String c30         = "n/a"
    String c60         = "n/a"
    String c90         = "n/a"
    String lastRun     = "—"
    String avgDuration = "—"
}

List<Row> rows = []

def fmt = { long epochMs ->
    new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date(epochMs))
}

Closure<String> q

def scriptRef = { Map<String, Object> cfg ->
    def raw = cfg['FIELD_JOB_CODE']
           ?: cfg['FIELD_SCRIPT_FILE_OR_SCRIPT']
           ?: cfg['FIELD_SCRIPT']
           ?: cfg['FIELD_LINK_CONDITION']
    if (!raw) return "(no script)"
    if (raw instanceof Map) {
        Map<String, Object> m = raw as Map<String, Object>
        return (m['scriptPath'] as String)
            ?: (m['scriptFile'] as String)
            ?: (m['script'] ? "(inline script)" : "(no script)")
    }
    String s = raw.toString().trim()
    return s.startsWith("import") || s.startsWith("//") ? "(inline script)" : s.take(100)
}

def decodeWfScript = { Map args ->
    String encoded = args['FIELD_SCRIPT_FILE_OR_SCRIPT'] as String
    if (!encoded) return "(no script)"
    try {
        Map parsed = new JsonSlurper().parseText(
            new String(encoded.decodeBase64())) as Map
        return (parsed['scriptPath'] as String)
            ?: (parsed['scriptFile'] as String)
            ?: (parsed['script'] ? "(inline script)" : "(no script)")
    } catch (ignored) {
        return "(inline script)"
    }
}

// ── Database load ─────────────────────────────────────────────────────────
Map<String, Map<String, Object>> hist = [:]
Map<String, List<Map<String, Object>>> stash = [:]
List<Map<String, Object>> restEndpoints = []
Map<String, String> cfIdToRrdKey = [:]
Map<String, String> cfIdToScript  = [:]
Map<String, String> cfIdToOwner   = [:]

Connection conn = null
try {
    DelegatorInterface del = ComponentAccessor.getComponent(DelegatorInterface)
    conn = ConnectionFactory.getConnection(del.getGroupHelperName("default"))

    String dbProduct = conn.metaData.databaseProductName?.toLowerCase() ?: ''
    boolean isMysql  = dbProduct.contains('mysql')
    boolean isMssql  = dbProduct.contains('microsoft') || dbProduct.contains('sql server')

    q = { String name ->
        if (isMssql) return '[' + name + ']'
        if (isMysql) return '`' + name + '`'
        return '"' + name + '"'
    }

    String dbName      = conn.catalog ?: ''
    String schemaFilter = isMysql ? "AND table_schema = '" + dbName + "' " : ''
    Sql sql            = new Sql(conn)

    // Scheduled jobs and fragments are stored in AO_4B00E6_STASH_SETTINGS
    try {
        sql.eachRow(
            'SELECT ' + q('KEY') + ', ' + q('SETTING') +
            ' FROM ' + q('AO_4B00E6_STASH_SETTINGS')
        ) { r ->
            String k = r.getAt('KEY') as String
            String v = r.getAt('SETTING') as String
            if (k && v) {
                try { stash[k] = new JsonSlurper().parseText(v) as List<Map<String, Object>> }
                catch (ignored) {}
            }
        }
    } catch (ex) {
        String msg = "STASH_SETTINGS load failed: ${ex.message}"
        log.warn(msg); loadWarnings << msg
    }

    // REST endpoint configuration is stored in the propertytext table
    try {
        sql.eachRow(
            "SELECT pt." + q('propertyvalue') +
            " FROM " + q('propertytext') + " pt" +
            " JOIN " + q('propertyentry') + " pe ON pe." + q('id') + " = pt." + q('id') +
            " WHERE pe." + q('property_key') +
            " = 'com.onresolve.jira.groovy.groovyrunner:rest-endpoints'"
        ) { r ->
            String v = r.getAt('propertyvalue') as String
            if (v) {
                try { restEndpoints = new JsonSlurper().parseText(v) as List<Map<String, Object>> }
                catch (ignored) {}
            }
        }
    } catch (ex) {
        String msg = "REST endpoints load failed: ${ex.message}"
        log.warn(msg); loadWarnings << msg
    }

    // Script field configuration — critically, this contains the fieldConfigurationSchemeId
    // which is the key SR uses for RRD files, not the Jira customFieldId
    try {
        sql.eachRow(
            "SELECT pt." + q('propertyvalue') +
            " FROM " + q('propertytext') + " pt" +
            " JOIN " + q('propertyentry') + " pe ON pe." + q('id') + " = pt." + q('id') +
            " WHERE pe." + q('property_key') +
            " = 'com.onresolve.jira.groovy.groovyrunner:customfields'"
        ) { r ->
            String v = r.getAt('propertyvalue') as String
            if (v) {
                try {
                    (new JsonSlurper().parseText(v) as List<Map<String, Object>>).each { cfg ->
                        String cfId   = cfg['customFieldId']?.toString()
                        String rrdKey = cfg['fieldConfigurationSchemeId']?.toString()
                        if (!cfId) return
                        if (rrdKey) cfIdToRrdKey[cfId] = rrdKey
                        def scriptCfg = cfg['FIELD_SCRIPT_FILE_OR_SCRIPT']
                        if (scriptCfg instanceof Map) {
                            Map<String, Object> sm = scriptCfg as Map<String, Object>
                            cfIdToScript[cfId] = (sm['scriptPath'] as String)
                                ?: (sm['script'] ? "(inline script)" : "(no script)")
                        } else {
                            String canned = cfg['canned-script'] as String
                            if (canned) {
                                String label = canned.tokenize('.').last()
                                    .replace('CannedScriptField', '')
                                    .replace('ScriptField', '')
                                    .replace('Config', '')
                                cfIdToScript[cfId] = "(built-in: ${label})" as String
                            }
                        }
                        cfIdToOwner[cfId] = (cfg['ownedBy'] as String) ?: "—"
                    }
                } catch (ex2) {
                    String msg = "Script fields property parse failed: ${ex2.message}"
                    log.warn(msg); loadWarnings << msg
                }
            }
        }
    } catch (ex) {
        String msg = "Script fields property load failed: ${ex.message}"
        log.warn(msg); loadWarnings << msg
    }

    // Execution history from the SR database table — used as fallback when no RRD file exists
    String tbl = null
    for (String pat : ['AO_%_SCRIPT_RUN_RESULT', 'AO_%_SCRIPT_EXECUTION']) {
        if (tbl) break
        try {
            sql.eachRow(
                "SELECT table_name FROM information_schema.tables " +
                "WHERE UPPER(table_name) LIKE '" + pat + "' " + schemaFilter
            ) { r -> if (!tbl) tbl = r.getAt('table_name') as String }
        } catch (ignored) {}
    }

    if (tbl) {
        List<String> cols = []
        sql.eachRow(
            "SELECT column_name FROM information_schema.columns " +
            "WHERE table_name = '" + tbl + "' " + schemaFilter +
            "ORDER BY ordinal_position"
        ) { r -> cols << (r.getAt('column_name') as String) }

        String keyCol  = cols.find { it.equalsIgnoreCase('KEY') }    ?: cols.find { it.equalsIgnoreCase('SCRIPT_ID') }
        String timeCol = cols.find { it.equalsIgnoreCase('CREATED') } ?: cols.find { it.equalsIgnoreCase('START_TIME') }

        if (keyCol && timeCol) {
            sql.eachRow(
                "SELECT " + q(keyCol) + " AS sid, COUNT(*) AS c90, " +
                "SUM(CASE WHEN " + q(timeCol) + " >= " + (NOW - D60) + " THEN 1 ELSE 0 END) AS c60, " +
                "SUM(CASE WHEN " + q(timeCol) + " >= " + (NOW - D30) + " THEN 1 ELSE 0 END) AS c30, " +
                "MAX(" + q(timeCol) + ") AS last_t " +
                "FROM " + q(tbl) + " WHERE " + q(timeCol) + " >= " + (NOW - D90) + " GROUP BY " + q(keyCol)
            ) { r ->
                String sid = r.getAt('sid') as String
                if (sid) hist[sid] = ([
                    c30: (r.getAt('c30') as Long) ?: 0L,
                    c60: (r.getAt('c60') as Long) ?: 0L,
                    c90: (r.getAt('c90') as Long) ?: 0L,
                    t  : r.getAt('last_t') as Long
                ] as Map<String, Object>)
            }
        } else {
            String msg = "Could not identify KEY/CREATED columns in ${tbl}"
            log.warn(msg); loadWarnings << msg
        }
    } else {
        String msg = "SCRIPT_RUN_RESULT table not found — DB execution counts unavailable"
        log.warn(msg); loadWarnings << msg
    }

} catch (ex) {
    String msg = "DB load failed: ${ex.message}"
    log.warn(msg); loadWarnings << msg
} finally {
    try { conn?.close() } catch (ignored) {}
}

if (!q) q = { String name -> '"' + name + '"' }

// ── RRD load ──────────────────────────────────────────────────────────────
// ScriptRunner writes a .rrd4j file for each script it tracks. These files
// are the primary source for execution counts and duration data.
Map<String, Map<String, Object>> rrdData = [:]

try {
    JiraHome jiraHome = ComponentAccessor.getComponent(JiraHome)
    File rrdBase = new File(jiraHome.home, "scriptrunner/rrd")

    if (rrdBase.exists()) {
        List<File> nodeDirs = (rrdBase.listFiles()
            ?.findAll { it.isDirectory() }?.sort { it.name } ?: []) as List<File>

        if (!MULTI_NODE) nodeDirs = nodeDirs ? [nodeDirs.first()] : []

        def readRrd = { File rrdFile ->
            RrdDb db = null
            try {
                db = RrdDb.getBuilder().setPath(rrdFile.absolutePath).readOnly().build()
                FetchData fd = db.createFetchRequest(
                    ConsolFun.AVERAGE, NOW_SEC - D90_SEC, NOW_SEC).fetchData()

                long[]   timestamps = fd.timestamps
                double[] counts     = fd.getValues('count')
                double[] durations  = fd.getValues('duration')
                int      rowCount   = fd.rowCount

                double sum30 = 0, sum60 = 0, sum90 = 0, durSum = 0
                int durCnt = 0
                long lastTs = 0L

                (0..<rowCount).each { int i ->
                    double c = counts[i]; double d = durations[i]; long ts = timestamps[i]
                    if (!Double.isNaN(c)) {
                        sum90 += c
                        if (ts >= NOW_SEC - D60_SEC) sum60 += c
                        if (ts >= NOW_SEC - D30_SEC) sum30 += c
                        if (ts > lastTs) lastTs = ts
                    }
                    if (!Double.isNaN(d)) { durSum += d; durCnt++ }
                }
                return [sum30: sum30, sum60: sum60, sum90: sum90, lastTs: lastTs,
                        avgDuration: durCnt > 0 ? (long)(durSum / durCnt) : -1L]
            } catch (ex) {
                log.warn("RRD read failed for ${rrdFile.name}: ${ex.message}"); return null
            } finally {
                try { db?.close() } catch (ignored) {}
            }
        }

        nodeDirs.each { File nodeDir ->
            nodeDir.listFiles()?.findAll { it.name.endsWith('.rrd4j') }?.each { File f ->
                String key = f.name.replace('.rrd4j', '')
                Map result = readRrd(f)
                if (result == null) return
                if (!rrdData.containsKey(key)) {
                    rrdData[key] = result as Map<String, Object>
                } else {
                    Map<String, Object> ex = rrdData[key] as Map<String, Object>
                    long ed = (ex.avgDuration as Long) ?: -1L
                    long nd = (result.avgDuration as Long) ?: -1L
                    rrdData[key] = ([
                        sum30: (ex.sum30 as double) + (result.sum30 as double),
                        sum60: (ex.sum60 as double) + (result.sum60 as double),
                        sum90: (ex.sum90 as double) + (result.sum90 as double),
                        lastTs: Math.max((ex.lastTs as Long) ?: 0L, (result.lastTs as Long) ?: 0L),
                        avgDuration: (ed >= 0 && nd >= 0) ? ((ed + nd) / 2L) as long
                                   : (ed >= 0 ? ed : nd)
                    ] as Map<String, Object>)
                }
            }
        }
    } else {
        String msg = "RRD directory not found — falling back to DB/memory counts"
        log.warn(msg); loadWarnings << msg
    }
} catch (ex) {
    String msg = "RRD load failed: ${ex.message}"
    log.warn(msg); loadWarnings << msg
}

// ── DiagnosticsManager — in-memory fallback ───────────────────────────────
DiagnosticsManager dm = null
try {
    dm = ComponentAccessor.getOSGiComponentInstanceOfType(DiagnosticsManager)
} catch (ex) {
    String msg = "DiagnosticsManager lookup failed: ${ex.message}"
    log.warn(msg); loadWarnings << msg
}

// ── Helpers ───────────────────────────────────────────────────────────────
Set<String> attributedKeys = [] as Set<String>

def fmtCount = { double sum ->
    if (sum <= 0.0) return "0"
    long r = Math.round(sum)
    return Math.abs(sum - r) > 0.1 ? "~${r}" : "${r}"
}

def attachFromRrd = { Row row, String key ->
    if (!key) return false
    Map<String, Object> d = rrdData[key] as Map<String, Object>
    if (!d) return false
    row.c30 = fmtCount(d.sum30 as double)
    row.c60 = fmtCount(d.sum60 as double)
    row.c90 = fmtCount(d.sum90 as double)
    long ts = (d.lastTs as Long) ?: 0L
    row.lastRun = ts > 0L ? fmt(ts * 1000L) : "—"
    long dur = (d.avgDuration as Long) ?: -1L
    row.avgDuration = dur >= 0L ? "${dur}ms" : "—"
    return true
}

def attachFromDb = { Row row, String sid ->
    if (!sid) return
    Map<String, Object> h = hist[sid] as Map<String, Object>
    if (!h) { row.c30 = "0"; row.c60 = "0"; row.c90 = "0"; return }
    row.c30 = (h.c30 as Long).toString(); row.c60 = (h.c60 as Long).toString()
    row.c90 = (h.c90 as Long).toString()
    row.lastRun = h.t ? fmt(h.t as long) : "—"
}

def attachFromMemory = { Row row, String fid ->
    if (!dm || !fid) { row.c30 = "0"; row.c60 = "0"; row.c90 = "0"; return }
    List<ScriptRunResult> results = dm.getResultsForFunction(fid) ?: []
    boolean atCap = results.size() >= 15
    String pfx = atCap ? "~" : ""
    row.c30 = pfx + results.findAll { it.created != null && it.created >= NOW - D30 }.size()
    row.c60 = pfx + results.findAll { it.created != null && it.created >= NOW - D60 }.size()
    row.c90 = pfx + results.findAll { it.created != null && it.created >= NOW - D90 }.size()
    List<Long> ts = results.findAll { it.created != null }.collect { it.created }
    row.lastRun = ts ? fmt(ts.max() as long) : "—"
}

def attachWithRrdOrDb = { Row row, String sid ->
    if (sid) attributedKeys << sid
    if (!attachFromRrd(row, sid)) attachFromDb(row, sid)
}

def attachWithRrdOrMemory = { Row row, String fid ->
    if (!attachFromRrd(row, fid)) attachFromMemory(row, fid)
}

def addStashRow = { String type, Map<String, Object> cfg, boolean hasHistory ->
    Row r = new Row(
        type   : type,
        name   : (cfg['FIELD_NOTES'] ?: cfg['name'] ?: cfg.id ?: "—") as String,
        script : scriptRef(cfg),
        enabled: cfg.disabled ? "false" : "true",
        owner  : (cfg.ownedBy ?: cfg['modifiedByUserKey'] ?: cfg['FIELD_USER_ID'] ?: "—") as String
    )
    if (cfg.id) attributedKeys << (cfg.id as String)
    if (hasHistory) attachFromDb(r, cfg.id as String) else attachFromRrd(r, cfg.id as String)
    rows << r
}

// ── Quartz next-run map — used to detect broken triggers ──────────────────
Map<String, Date> quartzNextRun = [:]
try {
    SchedulerService svc = ComponentAccessor.getComponent(SchedulerService)
    svc?.getJobRunnerKeysForAllScheduledJobs()?.each { runnerKey ->
        String rk = runnerKey.toString()
        if (!rk.toLowerCase().contains('onresolve') && !rk.toLowerCase().contains('scriptrunner')) return
        svc.getJobsByJobRunnerKey(runnerKey).each { JobDetails jd ->
            Map<String, java.io.Serializable> params = jd.parameters ?: [:]
            String srId = params['id'] as String ?: params['scriptId'] as String ?: params['jobId'] as String
            if (!srId) {
                java.util.regex.Matcher m = jd.jobId.toString() =~
                    /([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/
                if (m.find()) srId = m.group(1)
            }
            if (srId) quartzNextRun[srId] = jd.nextRunTime
        }
    }
} catch (ex) {
    String msg = "SchedulerService lookup failed: ${ex.message}"
    log.warn(msg); loadWarnings << msg
}

// ── Scheduled Jobs and Escalation Services ────────────────────────────────
Map<String, Map<String, Object>> stashJobsById = [:]
(stash['scheduled_jobs'] ?: []).each { Map<String, Object> cfg ->
    if (cfg.id) stashJobsById[cfg.id as String] = cfg
}

ScheduledScriptJobManager jobMgr = null
try {
    jobMgr = ComponentAccessor.getOSGiComponentInstanceOfType(ScheduledScriptJobManager)
} catch (ex) {
    String msg = "ScheduledScriptJobManager lookup failed: ${ex.message}"
    log.warn(msg); loadWarnings << msg
}

jobMgr?.load()?.each { AbstractScheduledJobCommand j ->
    boolean isEscalation = j.class.simpleName == 'EscalationServiceCommand'
    String scriptStr = "—"
    if (j instanceof AbstractCustomScheduledJobCommand) {
        AbstractCustomScheduledJobCommand cmd = j as AbstractCustomScheduledJobCommand
        scriptStr = cmd.scriptConfig?.scriptPath
            ?: (cmd.scriptConfig?.script ? "(inline script)" : (isEscalation ? "(not accessible)" : "(no script)"))
    } else {
        Map<String, Object> sc = stashJobsById[j.id] as Map<String, Object>
        scriptStr = sc ? scriptRef(sc) : "—"
    }
    boolean broken = !j.disabled && quartzNextRun.containsKey(j.id) && quartzNextRun[j.id] == null
    Row r = new Row(
        type   : isEscalation ? "Escalation Service" : "Scheduled Job",
        name   : j.notes ?: j.id ?: "—",
        script : scriptStr,
        enabled: j.disabled ? "false" : broken ? "⚠ broken" : "true",
        owner  : j.ownedBy ?: j.userId ?: "—"
    )
    if (isEscalation) attachWithRrdOrMemory(r, j.id) else attachWithRrdOrDb(r, j.id)
    rows << r
}

// ── Script Fragments ──────────────────────────────────────────────────────
(stash['ui_fragments'] ?: stash['fragments'] ?: []).each {
    addStashRow("Script Fragment", it, false)
}

// ── REST Endpoints ────────────────────────────────────────────────────────
List<String> restRrdKeys = rrdData.keySet()
    .findAll { it.matches(/(?i)(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)-.+/) }
    .sort() as List<String>

restEndpoints.each { Map<String, Object> cfg ->
    String name = (cfg['FIELD_NOTES'] ?: '') as String
    String rrdKey = restRrdKeys.find { String k ->
        name.toLowerCase().contains(k.replaceFirst(/(?i)^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)-/, '').toLowerCase())
    } ?: ''
    Row r = new Row(
        type   : "REST Endpoint",
        name   : (cfg['FIELD_NOTES'] ?: cfg['name'] ?: cfg.id ?: "—") as String,
        script : scriptRef(cfg),
        enabled: cfg.disabled ? "false" : "true",
        owner  : (cfg['ownedBy'] ?: "—") as String
    )
    if (!attachFromRrd(r, rrdKey)) { r.c30 = "n/a"; r.c60 = "n/a"; r.c90 = "n/a" }
    rows << r
}

// ── Script Listeners ──────────────────────────────────────────────────────
if (listenerIds.isEmpty()) {
    loadWarnings << "No listener UUIDs configured — add them to the listenerIds map at the top of the script."
}
listenerIds.each { String fid, String name ->
    Row r = new Row(type: "Script Listener", name: name ?: fid, script: "—", enabled: "—", owner: "—")
    attachWithRrdOrMemory(r, fid)
    rows << r
}

// ── Workflow Post-Functions ───────────────────────────────────────────────
Set<String> seenFunctionIds = [] as Set<String>
ComponentAccessor.getWorkflowManager()?.getWorkflows()?.each { JiraWorkflow wf ->
    wf.allActions?.each { ActionDescriptor action ->
        List<FunctionDescriptor> pfs = []
        try { pfs = action.unconditionalResult?.postFunctions as List<FunctionDescriptor> ?: [] }
        catch (ignored) {}
        pfs.each { FunctionDescriptor fd ->
            Map args = fd.args ?: [:]
            String cls = args['class.name'] as String ?: ''
            if (!cls.contains('onresolve') && !cls.contains('scriptrunner')) return
            String fid = args['FIELD_FUNCTION_ID'] as String
            if (fid && seenFunctionIds.contains(fid)) return
            if (fid) seenFunctionIds << fid
            Row r = new Row(
                type   : "Workflow Post-Function",
                name   : "${wf.name} → ${action.name}",
                script : decodeWfScript(args),
                enabled: wf.active ? "true" : "false",
                owner  : "—"
            )
            attachWithRrdOrDb(r, fid)
            rows << r
        }
    }
}

// ── Script Fields ─────────────────────────────────────────────────────────
// SR uses fieldConfigurationSchemeId as the RRD key, not the Jira customFieldId.
// The mapping is loaded from the customfields property above.
ComponentAccessor.getCustomFieldManager()?.getCustomFieldObjects()
    ?.findAll { cf ->
        String tk = cf.customFieldType?.key ?: ''
        (tk.contains('onresolve') || tk.contains('scriptrunner')) && !tk.contains('jqlFunctions')
    }
    ?.each { cf ->
        String cfId   = cf.idAsLong.toString()
        String rrdKey = cfIdToRrdKey[cfId] ?: cfId
        attributedKeys << cfId
        if (rrdKey != cfId) attributedKeys << rrdKey
        Row r = new Row(
            type   : "Script Field",
            name   : cf.name ?: cf.id ?: "—",
            script : cfIdToScript[cfId] ?: "—",
            enabled: "true",
            owner  : cfIdToOwner[cfId] ?: "—"
        )
        if (!attachFromRrd(r, rrdKey)) attachFromDb(r, cfId)
        rows << r
    }

// ── JQL Functions ─────────────────────────────────────────────────────────
// SR creates an RRD file for each JQL function on first use.
// Functions that have never been called will not appear here.
rrdData.keySet()
    .findAll { String k ->
        !k.matches(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/) &&
        !k.matches(/\d+/) &&
        !k.matches(/(?i)(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)-.+/)
    }
    .sort()
    .each { String fn ->
        Row r = new Row(type: "JQL Function", name: fn, script: "—", enabled: "—", owner: "—")
        attachFromRrd(r, fn)
        rows << r
    }

// ── Behaviours ────────────────────────────────────────────────────────────
BehaviourManager behaviourMgr = null
try {
    behaviourMgr = ComponentAccessor.getOSGiComponentInstanceOfType(BehaviourManager)
} catch (ex) {
    String msg = "BehaviourManager lookup failed: ${ex.message}"
    log.warn(msg); loadWarnings << msg
}
behaviourMgr?.getAllConfigs()?.each { String id, BehaviourEditState state ->
    rows << new Row(
        type: "Behaviour", name: state.name ?: id, script: "—",
        enabled: state.disabled ? "false" : "true", owner: "—"
    )
}

// ── Orphaned DB records ───────────────────────────────────────────────────
List<String> orphanedNotes = []
hist.each { String key, Map<String, Object> h ->
    if (attributedKeys.contains(key)) return
    String label = key.matches(/\d+/) ? "deleted script field id=${key}" : "deleted script ${key.take(8)}…"
    String lastRun = h.t ? fmt(h.t as long) : '—'
    orphanedNotes << (label + ": " + h.c90 + " execution(s) in last 90d, last run " + lastRun)
}

// ── Safety net — ensure every feature type has at least one row ───────────
["Scheduled Job", "Script Listener", "Script Fragment", "REST Endpoint",
 "Escalation Service", "Workflow Post-Function", "Script Field", "Behaviour"].each { String type ->
    if (rows.every { it.type != type }) {
        rows << new Row(type: type, name: "(none configured)", script: "—", enabled: "—", owner: "—")
    }
}

// ── SR admin links ────────────────────────────────────────────────────────
Map<String, String> adminUrls = [
    "Scheduled Job"         : "/plugins/servlet/scriptrunner/admin/jobs",
    "Escalation Service"    : "/plugins/servlet/scriptrunner/admin/jobs",
    "Script Fragment"       : "/plugins/servlet/scriptrunner/admin/uifragments",
    "REST Endpoint"         : "/plugins/servlet/scriptrunner/admin/restendpoints",
    "Script Listener"       : "/plugins/servlet/scriptrunner/admin/listeners",
    "Workflow Post-Function": "/secure/admin/workflows/ListWorkflows.jspa",
    "Script Field"          : "/plugins/servlet/scriptrunner/admin/scriptfields",
    "Behaviour"             : "/plugins/servlet/scriptrunner/admin/behaviours",
    "JQL Function"          : "/plugins/servlet/scriptrunner/admin/jqlfunctions"
]

// ── HTML output ───────────────────────────────────────────────────────────
StringWriter sw = new StringWriter()
new MarkupBuilder(sw).html {
    head {
        style("""
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
       font-size: 13px; color: #172B4D; margin: 16px; }
h1   { font-size: 18px; color: #0052CC; }
p    { font-size: 12px; color: #6B778C; margin: 4px 0 16px; }
table { border-collapse: collapse; width: 100%; margin-bottom: 16px; }
th { background: #0052CC; color: #fff; padding: 8px 10px;
     text-align: left; font-size: 12px; white-space: nowrap; }
td { padding: 6px 10px; border-bottom: 1px solid #DFE1E6;
     font-size: 12px; vertical-align: top; }
tr:nth-child(even) td { background: #F4F5F7; }
tr:hover td { background: #DEEBFF; }
.t   { background: #DEEBFF; color: #0052CC; font-weight: bold;
       padding: 2px 6px; border-radius: 3px; font-size: 11px; }
.on  { color: #00875A; font-weight: bold; }
.off { color: #DE350B; font-weight: bold; }
.na  { color: #97A0AF; font-style: italic; }
.broken { color: #FF8B00; font-weight: bold; }
.warn  { background: #FFFAE6; border: 1px solid #FFE380; border-radius: 4px;
         padding: 10px 14px; margin-bottom: 12px; font-size: 12px; }
.error { background: #FFEBE6; border: 1px solid #FF8F73; border-radius: 4px;
         padding: 10px 14px; margin-bottom: 12px; font-size: 12px; color: #BF2600; }
.approx { color: #FF8B00; font-weight: bold; cursor: help;
          border-bottom: 2px dashed #FF8B00; }
.dur { color: #42526E; font-size: 11px; }
.poc-banner { background: #FFEBE6; border: 2px solid #FF5630; border-radius: 4px;
              padding: 12px 16px; margin-bottom: 12px; font-size: 13px; color: #BF2600; }
.track-table th { background: #42526E; font-size: 11px; }
.track-table td { font-size: 11px; }
.yes  { color: #00875A; font-weight: bold; }
.no   { color: #97A0AF; }
""")
    }
    body {
        h1("ScriptRunner — Execution Insights")
        p("Generated: ${new Date().format('yyyy-MM-dd HH:mm:ss z')} | " +
          "Scripts inventoried: ${rows.size()} | " +
          "RRD files loaded: ${rrdData.size()}")

        // ── Consolidation delay notice ─────────────────────────────────────
        div(class: "warn") {
            mkp.yieldUnescaped("""
<strong>⏱ Counts may not reflect recent executions — this is normal.</strong><br>
Execution counts come from RRD's <strong>daily archive</strong>, which consolidates data
from the 5-minute archive periodically. The exact rollup schedule is managed internally
by ScriptRunner and may vary. This means:<br>
&nbsp;&nbsp;• Scripts that ran recently may still show 0 until the next rollup completes.<br>
&nbsp;&nbsp;• The <strong>Last Execution date</strong> is reliable once consolidated
(daily resolution only — no time component).<br>
&nbsp;&nbsp;• To confirm a script ran recently, use the SR admin UI
<strong>Performance tab</strong> — it reads the 5-minute archive directly and updates immediately.<br>
This is not a bug. Executions are recorded the moment they happen; they just need to roll
up into the daily archive before this report can see them.
""")
        }

        // ── PoC banner ────────────────────────────────────────────────────
        div(class: "poc-banner") {
            mkp.yieldUnescaped("""
<strong>⚠ PROOF OF CONCEPT — NOT FOR PRODUCTION USE</strong><br>
This report is a technical PoC demonstrating what ScriptRunner usage data can be surfaced
programmatically. Data is read directly from the same sources the SR admin UI uses.<br><br>
<strong>✔ Read-only and safe to run.</strong> This script makes no changes to any data.
Click any feature type badge to open the corresponding SR admin page.
""")
        }

        // ── Load warnings ─────────────────────────────────────────────────
        if (loadWarnings) {
            div(class: "error") {
                mkp.yieldUnescaped(
                    "<strong>⚠ Data load warnings — some data may be incomplete:</strong><br>" +
                    loadWarnings.collect { "• " + it }.join("<br>"))
            }
        }

        // ── Listener action-required banner ───────────────────────────────
        div(class: "warn") {
            mkp.yieldUnescaped("""
<strong>📋 Action required — Script Listeners need manual configuration</strong><br><br>
ScriptRunner does not provide an API to auto-discover listeners, so each listener
must be registered in this script manually by UUID. This is a one-time task per listener.<br><br>
<strong>This report is currently tracking ${listenerIds.size()} listener(s).</strong>
If that number does not match the count in your
<a href="/plugins/servlet/scriptrunner/admin/listeners" target="_blank">SR admin → Listeners</a>
page, some are missing.<br><br>
<strong>How to add a missing listener — 4 steps:</strong><br>
&nbsp;&nbsp;1. Go to <a href="/plugins/servlet/scriptrunner/admin/listeners" target="_blank">SR admin → Listeners</a><br>
&nbsp;&nbsp;2. Click <strong>Edit</strong> next to the listener<br>
&nbsp;&nbsp;3. Copy the UUID from the browser URL — it ends with <code>?id=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx</code><br>
&nbsp;&nbsp;4. Open this script, find the <code>listenerIds</code> map at the <strong>very top</strong>,
and add: <code>"uuid-here" : "Listener name",</code><br><br>
<strong>Good news:</strong> Once added, the report immediately shows the
<strong>full execution history</strong> for that listener — up to 2 years.
The data was always recorded by RRD; you are just telling the report where to look.
""")
        }

        // ── Execution tracking reference table ────────────────────────────
        div(class: "warn") {
            mkp.yieldUnescaped("""
<strong>Execution tracking — data sources and accuracy</strong><br>
Execution counts are sourced from ScriptRunner's RRD (Round Robin Database) performance
files, the same data that powers the SR admin UI Performance tab graphs.
RRD data persists across Jira restarts and covers up to 2 years.
""")
            mkp.yieldUnescaped("""
<table class="track-table" style="margin-top:8px">
<thead><tr>
  <th>Feature Type</th><th>Execution counts?</th><th>Primary source</th>
  <th>Fallback</th><th>Important notes</th>
</tr></thead>
<tbody>
<tr><td>Scheduled Job</td>
    <td class="yes">✔ Yes</td><td>RRD (rrd4j file)</td>
    <td>Database (SCRIPT_RUN_RESULT)</td>
    <td>RRD records every execution regardless of log output.
        <strong>⚠ broken</strong> = enabled but no Quartz trigger scheduled.</td></tr>
<tr><td>Escalation Service</td>
    <td class="yes">✔ Yes</td><td>RRD (rrd4j file)</td>
    <td>JVM memory (DiagnosticsManager)</td>
    <td>RRD replaces the previous 15-record memory cap.
        <strong>⚠ broken</strong> = enabled but no Quartz trigger scheduled.</td></tr>
<tr><td>Script Listener</td>
    <td class="yes">✔ Yes</td><td>RRD (rrd4j file)</td>
    <td>JVM memory (DiagnosticsManager)</td>
    <td>Must be registered manually by UUID — see banner above.</td></tr>
<tr><td>Workflow Post-Function</td>
    <td class="yes">✔ Yes</td><td>RRD (rrd4j file)</td>
    <td>Database (SCRIPT_RUN_RESULT)</td>
    <td>Enabled reflects parent workflow active state.</td></tr>
<tr><td>Script Field</td>
    <td class="yes">✔ Yes (approx)</td><td>RRD (rrd4j file)</td>
    <td>Database (SCRIPT_RUN_RESULT)</td>
    <td>RRD key is <code>fieldConfigurationSchemeId</code> (loaded from the
        <code>customfields</code> property), not the custom field ID.
        Counts are approximate (~ prefix) — fields run on every issue view,
        board load, and search result page.</td></tr>
<tr><td>REST Endpoint</td>
    <td class="yes">✔ Yes</td><td>RRD (rrd4j file)</td>
    <td>n/a — endpoint never called</td>
    <td><strong>n/a = endpoint has never been called.</strong>
        0/0/0 = called before but not in the last 90 days
        (or called today but not yet consolidated).</td></tr>
<tr><td>JQL Function</td>
    <td class="yes">✔ Yes</td><td>RRD (rrd4j file)</td><td>n/a</td>
    <td>Only appears if called at least once — RRD file is created on first use.</td></tr>
<tr><td>Script Fragment</td>
    <td class="no">✘ Not tracked</td><td>—</td><td>—</td>
    <td>SR does not record execution history for fragments.</td></tr>
<tr><td>Behaviour</td>
    <td class="no">✘ Not tracked</td><td>—</td><td>—</td>
    <td>SR does not record execution history for behaviours.</td></tr>
</tbody></table>
""")
        }

        // ── Known limitations ─────────────────────────────────────────────
        div(class: "warn") {
            mkp.yieldUnescaped("""
<strong>Known limitations</strong><br><br>
<strong>1. Execution counts cover the last 90 days only.</strong>
A count of 0 does not mean a script has never run — only that it has not run in the past 90 days.
RRD retains up to 2 years of data; the 90-day window is a report design choice.<br><br>
<strong>2. Script Field counts are approximate (~ prefix).</strong>
Script fields can execute hundreds of times per 5-minute window (every issue view, board load,
search result page). RRD stores the <em>average</em> executions per 5-minute window; the
displayed total is the sum of those averages. It is a reliable indicator of relative activity
but not an exact total.<br><br>
<strong>3. Script Listeners require manual UUID entry.</strong>
SR's internal ScriptRegistry is not accessible on this version, so listeners cannot be
auto-enumerated. See the action-required banner above for step-by-step instructions.<br><br>
<strong>4. Script column is blank for some feature types.</strong><br>
&nbsp;&nbsp;• <strong>Script Fields</strong> — script content shown (inline or file path)
for custom script fields. Built-in canned field types show the template name.<br>
&nbsp;&nbsp;• <strong>Script Listeners, Behaviours, JQL Functions</strong> — held in ScriptRegistry, which is not accessible.<br>
&nbsp;&nbsp;• <strong>Escalation Services</strong> — script is in "Additional Issue Actions", not in the standard config structure.<br>
&nbsp;&nbsp;• <strong>REST Endpoints</strong> — show "(inline script)" correctly.<br><br>
<strong>5. Owner column is blank for several feature types.</strong>
Available for Scheduled Jobs, Escalation Services, Script Fragments, and Script Fields
(where <code>ownedBy</code> is set). All other types require ScriptRegistry access.<br><br>
<strong>6. Enabled column is blank or indirect for several feature types.</strong><br>
&nbsp;&nbsp;• <strong>Script Listeners</strong> — not accessible (ScriptRegistry).<br>
&nbsp;&nbsp;• <strong>JQL Functions</strong> — no enable/disable concept in SR; <code>—</code> is intentional.<br>
&nbsp;&nbsp;• <strong>Workflow Post-Functions</strong> — reflects parent workflow active state, not the function itself.<br><br>
<strong>7. Last Execution shows date only — no time.</strong>
RRD's daily archive has day-level resolution only. There is no intra-day timestamp —
the report shows the date only to avoid implying false precision.<br><br>
<strong>8. A count of 0 with no Last Execution date (—) is ambiguous.</strong>
It can mean: (a) never run, (b) ran but RRD file not yet written, or (c) data not yet
consolidated. A count of 0 <em>with</em> a date means it ran more than 90 days ago.<br><br>
<strong>9. REST Endpoint n/a = endpoint has never been called.</strong>
SR only creates the RRD file on the first invocation — n/a is not a data gap, it means
the endpoint has genuinely never been used.<br><br>
<strong>10. JQL Functions only appear if called at least once.</strong>
A function that exists but has never been used in a search will not appear here at all.<br><br>
<strong>11. Avg Duration is the mean over all non-NaN 5-minute windows in 90 days.</strong>
May not reflect recent performance for scripts with variable execution times.
Use the SR admin UI Performance tab for a time-series view.<br><br>
<strong>12. ⚠ broken trigger = enabled in SR but no Quartz trigger scheduled.</strong>
The job will not run until repaired — disable then re-enable it, or restart Jira.<br><br>
${orphanedNotes ? '<strong>13. Deleted scripts with remaining execution history.</strong><br>' +
  'These no longer exist in SR but their database records are retained:<br>' +
  orphanedNotes.collect { '&nbsp;&nbsp;• ' + it }.join('<br>') + '<br><br>' : ''}
""")
        }

        table {
            thead { tr {
                th("Feature Type"); th("Name"); th("Script")
                th("Enabled"); th("Owner")
                th("Exec 30d"); th("Exec 60d"); th("Exec 90d")
                th("Avg Duration"); th("Last Execution (date only)")
            }}
            tbody {
                rows.each { Row r ->
                    tr {
                        td {
                            String url = adminUrls[r.type] ?: ''
                            if (url) {
                                a(href: url, target: "_blank", style: "text-decoration:none") {
                                    span(class: "t", r.type)
                                }
                            } else {
                                span(class: "t", r.type)
                            }
                        }
                        td(r.name)
                        td(r.script)
                        td {
                            String cls = r.enabled == "true"     ? "on"
                                       : r.enabled == "false"    ? "off"
                                       : r.enabled == "⚠ broken" ? "broken" : "na"
                            span(class: cls, r.enabled)
                        }
                        td(r.owner)
                        [r.c30, r.c60, r.c90].each { String val ->
                            if (val == "n/a") {
                                td(class: "na", "n/a")
                            } else if (val.startsWith("~")) {
                                td { span(class: "approx",
                                     title: "Approximate — RRD stores averages per 5-minute window.",
                                     "≈${val.substring(1)}") }
                            } else {
                                td(val)
                            }
                        }
                        td(class: r.avgDuration == "—" ? "na dur" : "dur", r.avgDuration)
                        td(class: r.lastRun == "—" ? "na" : "", r.lastRun)
                    }
                }
            }
        }
    }
}

return sw.toString()
