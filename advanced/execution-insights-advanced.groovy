// ScriptRunner for Jira Data Center — Execution Insights Report
// Run from: ScriptRunner Script Console
// Output:   HTML report rendered in the Script Console result panel
//
// This script surfaces execution data for every ScriptRunner feature type
// configured on this Jira instance. It reads from the same data sources
// the ScriptRunner admin UI uses — no external dependencies, no changes
// made to any data.
//
// HOW IT WORKS — big picture:
//   1. Open one database connection and load all SR configuration from the DB.
//   2. Walk the RRD files on disk to get execution counts and durations.
//   3. For each feature type (jobs, listeners, fields, etc.), build one Row.
//   4. Render all rows as an HTML table and return it to the Script Console.
//
// ─────────────────────────────────────────────────────────────────────────
// CONFIGURATION
// ─────────────────────────────────────────────────────────────────────────
//
// Script Listeners cannot be auto-discovered — add each one manually below.
// Find the UUID by editing a listener in SR admin and copying it from the URL.
//
Map<String, String> listenerIds = [
    "e2c59022-d52f-48ae-bf23-ec04dc5238dc" : "Auto-Set Priority for Bugs",
    "dae8a1ee-f9fa-4300-af7a-f836597c9c2f" : "Welcome Comment on Issue Creation"
] as Map<String, String>

// Set to true if this is a multi-node Data Center cluster.
// When false, only the first node directory is read (safe for single-node).
boolean MULTI_NODE = false

// ─────────────────────────────────────────────────────────────────────────

// ── Imports ───────────────────────────────────────────────────────────────
// Groovy requires all external classes to be declared at the top.
// These cover: Jira core APIs, ScriptRunner APIs, workflow APIs,
// database access, JSON parsing, HTML building, and the RRD library.
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
// We need "now" and three look-back boundaries (30 / 60 / 90 days ago).
// These are computed once here and reused throughout the script.
//
// Two sets of units are needed because different APIs speak different languages:
//   - The database stores timestamps in MILLISECONDS (standard Java time).
//   - The RRD library stores timestamps in SECONDS (Unix epoch convention).
//
// 86_400_000 = the number of milliseconds in one day  (1000 ms × 60 s × 60 min × 24 h)
// 86_400     = the number of seconds in one day
//
// The underscores in the numbers are just a readability aid — Groovy ignores them.
final long NOW     = System.currentTimeMillis()   // current time in milliseconds
final long D30     = 30L * 86_400_000L            // 30 days expressed in milliseconds
final long D60     = 60L * 86_400_000L            // 60 days expressed in milliseconds
final long D90     = 90L * 86_400_000L            // 90 days expressed in milliseconds
final long NOW_SEC = (NOW / 1000L) as long        // current time in seconds (for RRD)
final long D30_SEC = 30L * 86_400L               // 30 days expressed in seconds (for RRD)
final long D60_SEC = 60L * 86_400L               // 60 days expressed in seconds (for RRD)
final long D90_SEC = 90L * 86_400L               // 90 days expressed in seconds (for RRD)

// Collects non-fatal problems encountered during data loading.
// These are shown as a warning banner in the HTML output rather than
// crashing the whole script — so the report still renders even if one
// data source is unavailable.
List<String> loadWarnings = []

// ── Row — one row in the output table ────────────────────────────────────
// Every ScriptRunner feature (job, listener, field, etc.) becomes one Row.
// Fields default to "n/a" or "—" so the table always has something to show
// even when no execution data is available for a particular script.
class Row {
    String type           // feature type label, e.g. "Scheduled Job"
    String name           // human-readable name of the script
    String script         // file path or "(inline script)"
    String enabled        // "true", "false", or "⚠ broken"
    String owner          // the user who owns / last modified the script
    String c30 = "n/a"   // execution count — last 30 days
    String c60 = "n/a"   // execution count — last 60 days
    String c90 = "n/a"   // execution count — last 90 days
    String lastRun     = "—"   // date of most recent execution (yyyy-MM-dd)
    String avgDuration = "—"   // mean execution time across the 90-day window
}

// The list that accumulates every Row before we render the HTML table.
List<Row> rows = []

// ── fmt — date formatter ──────────────────────────────────────────────────
// Converts a millisecond timestamp (the standard Java format) into a
// human-readable date string like "2024-11-15".
// We show date only — no time — because RRD's daily archive has day-level
// resolution and showing a time would imply false precision.
def fmt = { long epochMs ->
    new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date(epochMs))
}

// ── q — SQL identifier quoting ────────────────────────────────────────────
// Different databases use different characters to quote column and table names:
//   PostgreSQL / Oracle  →  "double quotes"
//   MySQL / MariaDB      →  `backticks`
//   SQL Server           →  [square brackets]
//
// We detect the database type once (inside the DB connection block below)
// and assign this closure so every SQL query can call q('column_name')
// instead of hard-coding a quoting style. Declared here as null so it is
// in scope throughout the script; assigned inside the try block below.
Closure<String> q

// ── scriptRef — extract a script reference from a config map ──────────────
// ScriptRunner stores script configuration as JSON blobs in the database.
// The field name that holds the script path or inline code varies by feature
// type, so we check several known field names in priority order.
// Returns a file path string, "(inline script)", or "(no script)".
def scriptRef = { Map<String, Object> cfg ->
    def raw = cfg['FIELD_JOB_CODE']
           ?: cfg['FIELD_SCRIPT_FILE_OR_SCRIPT']
           ?: cfg['FIELD_SCRIPT']
           ?: cfg['FIELD_LINK_CONDITION']
    if (!raw) return "(no script)"
    // If the value is itself a nested map, the script path is inside it.
    if (raw instanceof Map) {
        Map<String, Object> m = raw as Map<String, Object>
        return (m['scriptPath'] as String)
            ?: (m['scriptFile'] as String)
            ?: (m['script'] ? "(inline script)" : "(no script)")
    }
    // If the value is a plain string that looks like Groovy code, it is inline.
    String s = raw.toString().trim()
    return s.startsWith("import") || s.startsWith("//") ? "(inline script)" : s.take(100)
}

// ── decodeWfScript — decode a workflow post-function script reference ──────
// Workflow post-function configuration is stored differently from other
// feature types: the script config is Base64-encoded JSON inside the
// FIELD_SCRIPT_FILE_OR_SCRIPT argument. We decode and parse it here.
// Returns a file path, "(inline script)", or "(no script)".
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
        // If decoding fails the value is probably already plain text.
        return "(inline script)"
    }
}

// ── Database load ─────────────────────────────────────────────────────────
// We open one JDBC connection and run all database queries inside a single
// try/finally block so the connection is always closed, even if a query fails.
//
// Five things are loaded from the database:
//   stash          — ScriptRunner's own config table (jobs, fragments, etc.)
//   restEndpoints  — REST endpoint configuration
//   cfIdToRrdKey   — maps a Jira custom field ID to its RRD file key
//   cfIdToScript   — maps a custom field ID to its script reference
//   cfIdToOwner    — maps a custom field ID to its owner
//   hist           — aggregated execution counts from the SR history table
//                    (used as a fallback when no RRD file exists)

Map<String, Map<String, Object>> hist = [:]           // execution history keyed by script ID
Map<String, List<Map<String, Object>>> stash = [:]    // SR config blobs keyed by feature type
List<Map<String, Object>> restEndpoints = []          // REST endpoint config entries
Map<String, String> cfIdToRrdKey = [:]                // customFieldId → RRD file key
Map<String, String> cfIdToScript  = [:]               // customFieldId → script reference
Map<String, String> cfIdToOwner   = [:]               // customFieldId → owner username

Connection conn = null
try {
    // ComponentAccessor is the Jira service locator — it gives us access to
    // any Jira or ScriptRunner component without needing dependency injection.
    DelegatorInterface del = ComponentAccessor.getComponent(DelegatorInterface)
    // ConnectionFactory gives us a raw JDBC connection to the Jira database.
    conn = ConnectionFactory.getConnection(del.getGroupHelperName("default"))

    // Detect which database engine we are talking to so we can quote
    // identifiers correctly in every SQL query below.
    String dbProduct = conn.metaData.databaseProductName?.toLowerCase() ?: ''
    boolean isMysql  = dbProduct.contains('mysql')
    boolean isMssql  = dbProduct.contains('microsoft') || dbProduct.contains('sql server')

    // Now that we know the DB type, assign the quoting closure declared above.
    q = { String name ->
        if (isMssql) return '[' + name + ']'
        if (isMysql) return '`' + name + '`'
        return '"' + name + '"'   // ANSI standard — works for PostgreSQL and Oracle
    }

    // schemaFilter is appended to information_schema queries on MySQL/MariaDB
    // to restrict results to the current database. Other databases do not need it.
    String dbName      = conn.catalog ?: ''
    String schemaFilter = isMysql ? "AND table_schema = '" + dbName + "' " : ''
    Sql sql            = new Sql(conn)

    // ── Load STASH_SETTINGS ───────────────────────────────────────────────
    // AO_4B00E6_STASH_SETTINGS is ScriptRunner's own configuration table.
    // Each row has a KEY (feature type, e.g. "scheduled_jobs") and a SETTING
    // (a JSON array of config objects for all scripts of that type).
    // We parse each JSON blob and store it in the `stash` map.
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

    // ── Load REST endpoint configuration ──────────────────────────────────
    // REST endpoint config is stored as a single JSON blob in Jira's
    // propertytext table, identified by a well-known property key.
    // We join propertytext (the value) with propertyentry (the key name)
    // to find the right row.
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

    // ── Load script field configuration ───────────────────────────────────
    // Script field config is also stored in propertytext, under a different key.
    //
    // IMPORTANT: Jira identifies custom fields by their customFieldId (e.g. 10045).
    // ScriptRunner identifies script fields in RRD by their fieldConfigurationSchemeId
    // (a different number). We build lookup maps here so the Script Fields section
    // below can find the right RRD file for each custom field.
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
                        if (!cfId) return   // skip entries with no field ID
                        if (rrdKey) cfIdToRrdKey[cfId] = rrdKey
                        // The script reference can be stored as a nested map (custom script)
                        // or as a plain string class name (built-in "canned" field type).
                        def scriptCfg = cfg['FIELD_SCRIPT_FILE_OR_SCRIPT']
                        if (scriptCfg instanceof Map) {
                            Map<String, Object> sm = scriptCfg as Map<String, Object>
                            cfIdToScript[cfId] = (sm['scriptPath'] as String)
                                ?: (sm['script'] ? "(inline script)" : "(no script)")
                        } else {
                            // "canned-script" is a fully-qualified class name like
                            // "com.example.CannedScriptFieldConfig". We strip the package
                            // and known suffixes to produce a short readable label.
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

    // ── Load execution history from the SR run-result table ───────────────
    // ScriptRunner writes a row to a database table every time a script runs.
    // The table name varies between SR versions, so we try two known patterns
    // and use whichever one exists.
    //
    // We run a single GROUP BY query that counts executions in each time window
    // in one pass, rather than three separate queries. The result is stored in
    // `hist`, keyed by script ID, and used as a fallback when no RRD file exists.
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
        // Column names also vary between SR versions, so we inspect the schema
        // to find the right column for the script ID and the timestamp.
        List<String> cols = []
        sql.eachRow(
            "SELECT column_name FROM information_schema.columns " +
            "WHERE table_name = '" + tbl + "' " + schemaFilter +
            "ORDER BY ordinal_position"
        ) { r -> cols << (r.getAt('column_name') as String) }

        String keyCol  = cols.find { it.equalsIgnoreCase('KEY') }    ?: cols.find { it.equalsIgnoreCase('SCRIPT_ID') }
        String timeCol = cols.find { it.equalsIgnoreCase('CREATED') } ?: cols.find { it.equalsIgnoreCase('START_TIME') }

        if (keyCol && timeCol) {
            // One SQL query counts executions in all three windows simultaneously
            // using conditional SUM (CASE WHEN timestamp >= cutoff THEN 1 ELSE 0 END).
            // This is more efficient than running three separate queries.
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
                    t  : r.getAt('last_t') as Long   // most recent execution timestamp (ms)
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
    // Always close the connection, whether the queries succeeded or failed.
    // The ?. operator means "call close() only if conn is not null".
    try { conn?.close() } catch (ignored) {}
}

// If the DB connection failed entirely, q was never assigned above.
// Set it to the ANSI standard (double-quotes) so the rest of the script
// can still call q() safely without null-checking everywhere.
if (!q) q = { String name -> '"' + name + '"' }

// ── RRD load ──────────────────────────────────────────────────────────────
// RRD (Round Robin Database) is the primary source for execution counts.
// ScriptRunner writes one .rrd4j file per script the first time it runs,
// under $JIRA_HOME/scriptrunner/rrd/{nodeId}/{scriptId}.rrd4j.
//
// On a multi-node cluster each node has its own subdirectory. We read all
// of them and sum the counts so the report shows instance-wide totals.
//
// The result is stored in `rrdData`, a map from script ID → metrics map.
Map<String, Map<String, Object>> rrdData = [:]

try {
    JiraHome jiraHome = ComponentAccessor.getComponent(JiraHome)
    File rrdBase = new File(jiraHome.home, "scriptrunner/rrd")

    if (rrdBase.exists()) {
        // Each subdirectory of rrdBase corresponds to one cluster node.
        List<File> nodeDirs = (rrdBase.listFiles()
            ?.findAll { it.isDirectory() }?.sort { it.name } ?: []) as List<File>

        // On a single-node instance, only read the first directory.
        // This avoids accidentally reading stale data from a decommissioned node.
        if (!MULTI_NODE) nodeDirs = nodeDirs ? [nodeDirs.first()] : []

        // readRrd — reads one .rrd4j file and returns a metrics map.
        // Returns null if the file cannot be read (e.g. locked or corrupt).
        //
        // RRD stores data as a circular buffer of time-bucketed averages.
        // We request the AVERAGE consolidation function over the last 90 days,
        // which gives us one data point per day. Each point has:
        //   count    — average executions per 5-minute window in that day
        //   duration — average execution time (ms) in that day
        // NaN means no data was recorded for that bucket — we skip those.
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
                long lastTs = 0L   // tracks the most recent bucket that had data

                (0..<rowCount).each { int i ->
                    double c = counts[i]; double d = durations[i]; long ts = timestamps[i]
                    if (!Double.isNaN(c)) {
                        sum90 += c
                        if (ts >= NOW_SEC - D60_SEC) sum60 += c
                        if (ts >= NOW_SEC - D30_SEC) sum30 += c
                        if (ts > lastTs) lastTs = ts   // keep the latest timestamp seen
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

        // Walk every node directory and read every .rrd4j file inside it.
        // The filename (without the extension) is the script's RRD key.
        nodeDirs.each { File nodeDir ->
            nodeDir.listFiles()?.findAll { it.name.endsWith('.rrd4j') }?.each { File f ->
                String key = f.name.replace('.rrd4j', '')
                Map result = readRrd(f)
                if (result == null) return   // skip unreadable files
                if (!rrdData.containsKey(key)) {
                    // First time we have seen this key — store the result directly.
                    rrdData[key] = result as Map<String, Object>
                } else {
                    // We have already seen this key from a previous node.
                    // Sum the counts and average the durations across nodes.
                    Map<String, Object> ex = rrdData[key] as Map<String, Object>
                    long ed = (ex.avgDuration as Long) ?: -1L   // existing average duration
                    long nd = (result.avgDuration as Long) ?: -1L   // new node's average duration
                    rrdData[key] = ([
                        sum30: (ex.sum30 as double) + (result.sum30 as double),
                        sum60: (ex.sum60 as double) + (result.sum60 as double),
                        sum90: (ex.sum90 as double) + (result.sum90 as double),
                        lastTs: Math.max((ex.lastTs as Long) ?: 0L, (result.lastTs as Long) ?: 0L),
                        // Average the two durations if both are valid; otherwise keep whichever is valid.
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
// The DiagnosticsManager is a ScriptRunner component that keeps the last
// ~15 execution results for each script in JVM memory (not persisted to disk).
// It is used as a last-resort fallback for feature types that do not write
// to the database history table — specifically escalation services and listeners.
// Because it only holds ~15 records, counts from this source are approximate
// and are prefixed with "~" in the output.
DiagnosticsManager dm = null
try {
    dm = ComponentAccessor.getOSGiComponentInstanceOfType(DiagnosticsManager)
} catch (ex) {
    String msg = "DiagnosticsManager lookup failed: ${ex.message}"
    log.warn(msg); loadWarnings << msg
}

// ── Helper closures ───────────────────────────────────────────────────────
// The closures below are small reusable functions that populate a Row's
// execution counts from one of the three data sources (RRD, database, memory).
// They are defined here, after all data is loaded, because they close over
// (reference) the rrdData, hist, and dm variables populated above.

// attributedKeys — tracks every script ID that has been matched to a known
// feature. Any ID in `hist` that is NOT in this set at the end of the script
// belongs to a deleted script and is reported in the "orphaned records" section.
Set<String> attributedKeys = [] as Set<String>

// fmtCount — formats a raw RRD count for display.
// RRD stores averages per 5-minute window, so summing them gives a fractional
// result for high-frequency scripts. We round to the nearest integer and add
// a "~" prefix when the value is not a whole number, to signal approximation.
def fmtCount = { double sum ->
    if (sum <= 0.0) return "0"
    long r = Math.round(sum)
    return Math.abs(sum - r) > 0.1 ? "~${r}" : "${r}"
}

// attachFromRrd — populates a Row's counts from the rrdData map.
// Returns true if data was found for the given key, false if not.
// The caller uses the return value to decide whether to try a fallback source.
def attachFromRrd = { Row row, String key ->
    if (!key) return false
    Map<String, Object> d = rrdData[key] as Map<String, Object>
    if (!d) return false
    row.c30 = fmtCount(d.sum30 as double)
    row.c60 = fmtCount(d.sum60 as double)
    row.c90 = fmtCount(d.sum90 as double)
    long ts = (d.lastTs as Long) ?: 0L
    // lastTs is in seconds (RRD convention) — multiply by 1000 to get milliseconds for fmt().
    row.lastRun = ts > 0L ? fmt(ts * 1000L) : "—"
    long dur = (d.avgDuration as Long) ?: -1L
    row.avgDuration = dur >= 0L ? "${dur}ms" : "—"
    return true
}

// attachFromDb — populates a Row's counts from the database history map.
// Used when no RRD file exists for a script (e.g. the file was deleted,
// or the script has only run on a different node).
def attachFromDb = { Row row, String sid ->
    if (!sid) return
    Map<String, Object> h = hist[sid] as Map<String, Object>
    if (!h) { row.c30 = "0"; row.c60 = "0"; row.c90 = "0"; return }
    row.c30 = (h.c30 as Long).toString(); row.c60 = (h.c60 as Long).toString()
    row.c90 = (h.c90 as Long).toString()
    row.lastRun = h.t ? fmt(h.t as long) : "—"
}

// attachFromMemory — populates a Row's counts from the DiagnosticsManager.
// Used as a last resort for escalation services and listeners, which do not
// write to the database history table. Because the DiagnosticsManager only
// retains ~15 records, counts are capped and prefixed with "~".
def attachFromMemory = { Row row, String fid ->
    if (!dm || !fid) { row.c30 = "0"; row.c60 = "0"; row.c90 = "0"; return }
    List<ScriptRunResult> results = dm.getResultsForFunction(fid) ?: []
    // If the list is at capacity (15 records), the true count is higher than
    // what we can see — prefix with "~" to communicate this uncertainty.
    boolean atCap = results.size() >= 15
    String pfx = atCap ? "~" : ""
    row.c30 = pfx + results.findAll { it.created != null && it.created >= NOW - D30 }.size()
    row.c60 = pfx + results.findAll { it.created != null && it.created >= NOW - D60 }.size()
    row.c90 = pfx + results.findAll { it.created != null && it.created >= NOW - D90 }.size()
    List<Long> ts = results.findAll { it.created != null }.collect { it.created }
    row.lastRun = ts ? fmt(ts.max() as long) : "—"
}

// attachWithRrdOrDb — tries RRD first, falls back to the database.
// Also registers the script ID in attributedKeys so it is not reported
// as an orphaned record at the end of the script.
def attachWithRrdOrDb = { Row row, String sid ->
    if (sid) attributedKeys << sid
    if (!attachFromRrd(row, sid)) attachFromDb(row, sid)
}

// attachWithRrdOrMemory — tries RRD first, falls back to DiagnosticsManager.
// Used for escalation services and listeners, which have no DB history table.
def attachWithRrdOrMemory = { Row row, String fid ->
    if (!attachFromRrd(row, fid)) attachFromMemory(row, fid)
}

// addStashRow — builds a Row from a stash config map and appends it to `rows`.
// Used for feature types whose configuration lives in STASH_SETTINGS
// (currently: script fragments). The `hasHistory` flag controls whether
// we look for execution counts in the database (true) or RRD (false).
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

// ── Quartz next-run map ───────────────────────────────────────────────────
// Quartz is the job scheduling library that Jira uses internally.
// ScriptRunner registers each scheduled job and escalation service with
// Quartz so they fire at the right time.
//
// A "broken" job is one that is enabled in ScriptRunner but has no Quartz
// trigger — meaning it will never fire. This can happen after a Jira restart
// or a failed job registration. We detect this by checking whether Quartz
// knows about the job and whether it has a next run time.
//
// quartzNextRun maps each SR script ID → the Date of its next scheduled run
// (or null if Quartz has the job registered but with no trigger).
Map<String, Date> quartzNextRun = [:]
try {
    SchedulerService svc = ComponentAccessor.getComponent(SchedulerService)
    svc?.getJobRunnerKeysForAllScheduledJobs()?.each { runnerKey ->
        String rk = runnerKey.toString()
        // Only inspect jobs registered by ScriptRunner — skip Jira's own jobs.
        if (!rk.toLowerCase().contains('onresolve') && !rk.toLowerCase().contains('scriptrunner')) return
        svc.getJobsByJobRunnerKey(runnerKey).each { JobDetails jd ->
            Map<String, java.io.Serializable> params = jd.parameters ?: [:]
            // The SR script ID may be stored under different parameter names
            // depending on the SR version and job type.
            String srId = params['id'] as String ?: params['scriptId'] as String ?: params['jobId'] as String
            if (!srId) {
                // Last resort: extract a UUID from the Quartz job ID string itself.
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
// Both feature types are managed by the same SR job manager and stored in
// the same STASH_SETTINGS key ("scheduled_jobs"). We tell them apart by
// checking the class name of the job command object:
//   EscalationServiceCommand → Escalation Service
//   anything else            → Scheduled Job
//
// We also build a lookup map from the stash data so we can retrieve the
// script reference for jobs that do not expose it via the command object.
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
        // Custom jobs expose their script config directly on the command object.
        AbstractCustomScheduledJobCommand cmd = j as AbstractCustomScheduledJobCommand
        scriptStr = cmd.scriptConfig?.scriptPath
            ?: (cmd.scriptConfig?.script ? "(inline script)" : (isEscalation ? "(not accessible)" : "(no script)"))
    } else {
        // Other job types (e.g. canned jobs) do not expose the script directly —
        // fall back to the stash config we loaded from the database.
        Map<String, Object> sc = stashJobsById[j.id] as Map<String, Object>
        scriptStr = sc ? scriptRef(sc) : "—"
    }
    // A job is "broken" if it is enabled in SR but Quartz has no next run time for it.
    boolean broken = !j.disabled && quartzNextRun.containsKey(j.id) && quartzNextRun[j.id] == null
    Row r = new Row(
        type   : isEscalation ? "Escalation Service" : "Scheduled Job",
        name   : j.notes ?: j.id ?: "—",
        script : scriptStr,
        enabled: j.disabled ? "false" : broken ? "⚠ broken" : "true",
        owner  : j.ownedBy ?: j.userId ?: "—"
    )
    // Escalation services fall back to memory (DiagnosticsManager) because they
    // do not write to the database history table. Scheduled jobs fall back to DB.
    if (isEscalation) attachWithRrdOrMemory(r, j.id) else attachWithRrdOrDb(r, j.id)
    rows << r
}

// ── Script Fragments ──────────────────────────────────────────────────────
// Script Fragments are UI customisations (e.g. extra panels on issue screens).
// ScriptRunner does not record execution history for them, so counts will
// always be "n/a". They are listed here for inventory purposes only.
// The `false` argument tells addStashRow to look in RRD (not the DB),
// which will find nothing — resulting in the "n/a" default values.
(stash['ui_fragments'] ?: stash['fragments'] ?: []).each {
    addStashRow("Script Fragment", it, false)
}

// ── REST Endpoints ────────────────────────────────────────────────────────
// REST endpoint config comes from the property table (loaded above).
// Execution counts come from RRD — SR creates the RRD file on first call,
// so endpoints that have never been called will show "n/a" (not "0").
//
// We match each endpoint config entry to its RRD key by normalising both
// the display name and the RRD key name (stripping the HTTP method prefix,
// lowercasing, and removing all non-alphanumeric characters) before comparing.
// This handles camelCase RRD keys like "validationTest" matching display
// names like "Validation Test Endpoint" that would otherwise fail a simple
// substring check.
List<String> restRrdKeys = rrdData.keySet()
    .findAll { it.matches(/(?i)(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)-.+/) }
    .sort() as List<String>

restEndpoints.each { Map<String, Object> cfg ->
    String displayName = (cfg['FIELD_NOTES'] ?: '') as String
    String rrdKey = restRrdKeys.find { String k ->
        String kName            = k.replaceFirst(/(?i)^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)-/, '')
        String normalizedDisplay = displayName.toLowerCase().replaceAll(/[^a-z0-9]/, '')
        String normalizedRrdName = kName.toLowerCase().replaceAll(/[^a-z0-9]/, '')
        normalizedDisplay.contains(normalizedRrdName) || normalizedRrdName.contains(normalizedDisplay)
    } ?: ''
    Row r = new Row(
        type   : "REST Endpoint",
        name   : (cfg['FIELD_NOTES'] ?: cfg['name'] ?: cfg.id ?: "—") as String,
        script : scriptRef(cfg),
        enabled: cfg.disabled ? "false" : "true",
        owner  : (cfg['ownedBy'] ?: "—") as String
    )
    // If no RRD key was matched, the endpoint has never been called — show "n/a".
    if (!attachFromRrd(r, rrdKey)) { r.c30 = "n/a"; r.c60 = "n/a"; r.c90 = "n/a" }
    rows << r
}

// ── Script Listeners ──────────────────────────────────────────────────────
// Listeners react to Jira events (e.g. issue created, comment added).
// ScriptRunner does not expose an API to list listener UUIDs, so the user
// must add them manually to the listenerIds map at the top of this script.
// We warn if the map is empty so the user knows data may be missing.
if (listenerIds.isEmpty()) {
    loadWarnings << "No listener UUIDs configured — add them to the listenerIds map at the top of the script."
}
listenerIds.each { String fid, String name ->
    Row r = new Row(type: "Script Listener", name: name ?: fid, script: "—", enabled: "—", owner: "—")
    attachWithRrdOrMemory(r, fid)
    rows << r
}

// ── Workflow Post-Functions ───────────────────────────────────────────────
// Post-functions run automatically after a workflow transition (e.g. after
// an issue is moved to "Done"). We walk every workflow → every transition →
// every post-function, keeping only the ones that belong to ScriptRunner.
//
// The same post-function can appear in multiple workflow versions (active and
// draft). We use seenFunctionIds to deduplicate so each function appears once.
Set<String> seenFunctionIds = [] as Set<String>
ComponentAccessor.getWorkflowManager()?.getWorkflows()?.each { JiraWorkflow wf ->
    wf.allActions?.each { ActionDescriptor action ->
        List<FunctionDescriptor> pfs = []
        try { pfs = action.unconditionalResult?.postFunctions as List<FunctionDescriptor> ?: [] }
        catch (ignored) {}
        pfs.each { FunctionDescriptor fd ->
            Map args = fd.args ?: [:]
            String cls = args['class.name'] as String ?: ''
            // Skip post-functions that do not belong to ScriptRunner.
            if (!cls.contains('onresolve') && !cls.contains('scriptrunner')) return
            String fid = args['FIELD_FUNCTION_ID'] as String
            // Skip if we have already added a row for this function ID.
            if (fid && seenFunctionIds.contains(fid)) return
            if (fid) seenFunctionIds << fid
            Row r = new Row(
                type   : "Workflow Post-Function",
                // Name shows "WorkflowName → TransitionName" for easy identification.
                name   : "${wf.name} → ${action.name}",
                script : decodeWfScript(args),
                // "enabled" reflects whether the parent workflow is active —
                // an inactive workflow means the post-function will never run.
                enabled: wf.active ? "true" : "false",
                owner  : "—"
            )
            attachWithRrdOrDb(r, fid)
            rows << r
        }
    }
}

// ── Script Fields ─────────────────────────────────────────────────────────
// Script Fields are custom fields whose value is computed by a Groovy script.
// They run every time an issue is displayed, so they can have very high counts.
//
// IMPORTANT: Jira identifies custom fields by customFieldId (e.g. 10045).
// ScriptRunner uses fieldConfigurationSchemeId as the RRD key — a different
// number. The cfIdToRrdKey map (built from the DB load above) translates
// between the two. Without this mapping, we would look for the wrong RRD file.
ComponentAccessor.getCustomFieldManager()?.getCustomFieldObjects()
    ?.findAll { cf ->
        String tk = cf.customFieldType?.key ?: ''
        // Keep only custom fields whose type key belongs to ScriptRunner.
        // Exclude JQL function fields, which are handled separately below.
        (tk.contains('onresolve') || tk.contains('scriptrunner')) && !tk.contains('jqlFunctions')
    }
    ?.each { cf ->
        String cfId   = cf.idAsLong.toString()
        String rrdKey = cfIdToRrdKey[cfId] ?: cfId   // use scheme ID if known, else fall back to field ID
        // Register both IDs as attributed so neither appears as an orphan.
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
// JQL Functions let users write custom JQL clauses (e.g. issueFunction in myFunc(...)).
// SR creates an RRD file for each function the first time it is used in a search.
// Functions that have never been called will not have an RRD file and will not
// appear in this report at all.
//
// We identify JQL function keys by elimination: any RRD key that is not a UUID,
// not a plain number (script field scheme ID), and not an HTTP-method-prefixed
// string (REST endpoint) must be a JQL function name.
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
// Behaviours control how issue create/edit screens behave (e.g. hiding fields,
// setting defaults). ScriptRunner does not record execution history for them,
// so counts will always be "n/a". They are listed here for inventory only.
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
// Any script ID in the execution history (`hist`) that was never added to
// `attributedKeys` belongs to a script that no longer exists in ScriptRunner
// (e.g. it was deleted). We surface these in the HTML report so the user
// knows the historical data exists even though the script is gone.
List<String> orphanedNotes = []
hist.each { String key, Map<String, Object> h ->
    if (attributedKeys.contains(key)) return   // already accounted for — skip
    String label = key.matches(/\d+/) ? "deleted script field id=${key}" : "deleted script ${key.take(8)}…"
    String lastRun = h.t ? fmt(h.t as long) : '—'
    orphanedNotes << (label + ": " + h.c90 + " execution(s) in last 90d, last run " + lastRun)
}

// ── Safety net ────────────────────────────────────────────────────────────
// If a feature type has no configured scripts at all, we add a placeholder
// row so the table always shows every type. This makes it obvious when a
// type is genuinely empty rather than missing due to a data load failure.
["Scheduled Job", "Script Listener", "Script Fragment", "REST Endpoint",
 "Escalation Service", "Workflow Post-Function", "Script Field", "Behaviour"].each { String type ->
    if (rows.every { it.type != type }) {
        rows << new Row(type: type, name: "(none configured)", script: "—", enabled: "—", owner: "—")
    }
}

// ── SR admin links ────────────────────────────────────────────────────────
// Maps each feature type to its SR admin page URL. Used in the HTML table
// to make each feature type badge a clickable link to the right admin page.
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
// Everything above has populated the `rows` list. We now render it as HTML.
//
// MarkupBuilder is a Groovy class that lets you write HTML as nested method
// calls. Each method name becomes an HTML tag. For example:
//   html { body { h1("Hello") } }
// produces: <html><body><h1>Hello</h1></body></html>
//
// The output is written into a StringWriter (sw) and returned at the end.
// The Script Console detects that the return value is HTML and renders it.
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
        // Shown only if one or more data sources failed to load.
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

        // ── Main data table ───────────────────────────────────────────────
        // One row per ScriptRunner feature. The feature type badge is a
        // clickable link to the corresponding SR admin page (where available).
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
                            // Map the enabled value to a CSS class that colours it
                            // green (on), red (off), orange (broken), or grey (unknown).
                            String cls = r.enabled == "true"     ? "on"
                                       : r.enabled == "false"    ? "off"
                                       : r.enabled == "⚠ broken" ? "broken" : "na"
                            span(class: cls, r.enabled)
                        }
                        td(r.owner)
                        // Render each count cell. "n/a" gets a grey italic style.
                        // Values starting with "~" are approximate — shown with a
                        // dashed underline and a tooltip explaining why.
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
