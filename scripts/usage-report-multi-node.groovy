// ═══════════════════════════════════════════════════════════════════════
// scripts/03-usage-report-multi-node.groovy
// ScriptRunner RRD Usage PoC — Multi-Node Variant
// Run from: Script Console  |  Output: HTML
//
// Use this instead of 02-usage-report.groovy if your Jira instance runs
// on multiple nodes (Data Center cluster). It discovers all node
// directories automatically and sums execution counts across every node.
//
// On a single-node instance this produces the same result as
// 02-usage-report.groovy — it is safe to use either way.
//
// Run 01-discover-ids.groovy first to find the correct SCRIPT_ID.
// ═══════════════════════════════════════════════════════════════════════


// ── ⚙ ONE VALUE TO CONFIGURE ─────────────────────────────────────────────

String SCRIPT_ID = "paste-your-script-id-here"   // ← from 01-discover-ids


// ── Imports ──────────────────────────────────────────────────────────────

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome
import com.atlassian.jira.workflow.WorkflowManager
import com.onresolve.scriptrunner.scheduled.ScheduledScriptJobManager
import com.onresolve.scriptrunner.scheduled.model.AbstractScheduledJobCommand
import com.opensymphony.workflow.loader.FunctionDescriptor
import groovy.json.JsonSlurper
import groovy.sql.Sql
import java.sql.Connection
import org.ofbiz.core.entity.ConnectionFactory
import org.ofbiz.core.entity.DelegatorInterface
import org.rrd4j.ConsolFun
import org.rrd4j.core.RrdDb


// ── Time windows ──────────────────────────────────────────────────────────

long nowSec  = (long)(System.currentTimeMillis() / 1000)
long sec30d  = 30L * 86_400L
long sec60d  = 60L * 86_400L
long sec90d  = 90L * 86_400L


// ── Identify the script — name and feature type ───────────────────────────
// Same logic as 02-usage-report.groovy. We check the ID format first,
// then look up the name from the appropriate source.

String featureType = "Unknown"
String scriptName  = SCRIPT_ID
String scriptExtra = ""

boolean isRestPattern = SCRIPT_ID.matches(/(?i)(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)-.+/)
boolean isNumeric     = SCRIPT_ID.matches(/\d+/)
boolean isUuid        = SCRIPT_ID.matches(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/)

if (isRestPattern) {
    featureType = "REST Endpoint"
    String method = SCRIPT_ID.split('-')[0].toUpperCase()
    String name   = SCRIPT_ID.replaceFirst(/(?i)^[A-Z]+-/, '')
    scriptName  = name
    scriptExtra = "HTTP Method: ${method}"

} else if (isNumeric) {
    featureType = "Script Field"
    try {
        DelegatorInterface del = ComponentAccessor.getComponent(DelegatorInterface)
        Connection conn = ConnectionFactory.getConnection(del.getGroupHelperName("default"))
        try {
            Sql sql = new Sql(conn)
            sql.eachRow(
                "SELECT pt.\"propertyvalue\" FROM \"propertytext\" pt" +
                " JOIN \"propertyentry\" pe ON pe.\"id\" = pt.\"id\"" +
                " WHERE pe.\"property_key\" = 'com.onresolve.jira.groovy.groovyrunner:customfields'"
            ) { r ->
                String v = r.getAt('propertyvalue') as String
                if (v) {
                    (new JsonSlurper().parseText(v) as List<Map>).each { Map cfg ->
                        if (cfg['fieldConfigurationSchemeId']?.toString() == SCRIPT_ID) {
                            scriptName  = (cfg['name'] as String) ?: SCRIPT_ID
                            scriptExtra = "Custom Field ID: customfield_${cfg['customFieldId']}"
                        }
                    }
                }
            }
        } finally {
            try { conn?.close() } catch (ignored) {}
        }
    } catch (ignored) {}

} else if (isUuid) {
    // Check Scheduled Jobs and Escalation Services
    try {
        ScheduledScriptJobManager jobMgr =
            ComponentAccessor.getOSGiComponentInstanceOfType(ScheduledScriptJobManager)
        AbstractScheduledJobCommand match = jobMgr?.load()
            ?.find { it.id == SCRIPT_ID } as AbstractScheduledJobCommand
        if (match) {
            boolean isEscalation = match.class.simpleName == 'EscalationServiceCommand'
            featureType = isEscalation ? "Escalation Service" : "Scheduled Job"
            scriptName  = match.notes ?: match.id ?: SCRIPT_ID
            scriptExtra = "Owner: ${match.ownedBy ?: match.userId ?: '—'}"
        }
    } catch (ignored) {}

    // Check Workflow Post-Functions
    if (featureType == "Unknown") {
        try {
            WorkflowManager wfm = ComponentAccessor.getWorkflowManager()
            wfm?.getWorkflows()?.each { wf ->
                if (featureType != "Unknown") return
                wf.allActions?.each { action ->
                    if (featureType != "Unknown") return
                    List<FunctionDescriptor> fns = []
                    try {
                        fns = action.unconditionalResult?.postFunctions
                            as List<FunctionDescriptor> ?: []
                    } catch (ignored2) {}
                    fns.each { FunctionDescriptor fd ->
                        if (fd.args['FIELD_FUNCTION_ID'] as String == SCRIPT_ID) {
                            featureType = "Workflow Post-Function"
                            scriptName  = "${wf.name} → ${action.name}"
                            scriptExtra = "Workflow active: ${wf.isActive()}"
                        }
                    }
                }
            }
        } catch (ignored) {}
    }

    // Not found anywhere — most likely a Script Listener
    if (featureType == "Unknown") {
        featureType = "Script Listener (unconfirmed)"
        scriptName  = "Unknown — UUID not found in Jobs, Escalation Services, or Post-Functions"
        scriptExtra = "If this is a listener, the UUID is correct. " +
                      "SR does not expose listener names via a public API."
    }

} else {
    featureType = "JQL Function"
    scriptName  = SCRIPT_ID
    scriptExtra = "JQL usage: issueFunction in ${SCRIPT_ID}(...)"
}


// ── Locate all node directories ───────────────────────────────────────────

JiraHome jiraHome = ComponentAccessor.getComponent(JiraHome)
File rrdBaseDirectory = new File(jiraHome.home, "scriptrunner/rrd")

List<File> nodeDirectories = rrdBaseDirectory.listFiles()
    ?.findAll { it.isDirectory() }
    ?.sort { it.name }
    ?: []

if (!rrdBaseDirectory.exists() || nodeDirectories.isEmpty()) {
    return "<p style='color:red'>No RRD node directories found under: " +
           "${rrdBaseDirectory.absolutePath}</p>"
}


// ── Read and accumulate metrics across every node ─────────────────────────

double count30 = 0, count60 = 0, count90 = 0
double durSum  = 0
int    durCnt  = 0
long   lastTs  = 0L
List<String> nodesWithData    = []
List<String> nodesWithoutData = []

for (File nodeDirectory : nodeDirectories) {
    File rrdFile = new File(nodeDirectory, "${SCRIPT_ID}.rrd4j")

    if (!rrdFile.exists()) {
        nodesWithoutData << nodeDirectory.name
        continue
    }

    RrdDb rrdDatabase = RrdDb.getBuilder()
        .setPath(rrdFile.absolutePath)
        .readOnly()
        .build()

    def fetchedData = rrdDatabase
        .createFetchRequest(ConsolFun.AVERAGE, nowSec - sec90d, nowSec)
        .fetchData()

    rrdDatabase.close()

    long[]   timestamps      = fetchedData.timestamps
    double[] bucketCounts    = fetchedData.getValues('count')
    double[] bucketDurations = fetchedData.getValues('duration')

    for (int i = 0; i < fetchedData.rowCount; i++) {
        long   ts = timestamps[i]
        double c  = bucketCounts[i]
        double d  = bucketDurations[i]

        if (!Double.isNaN(c)) {
            count90 += c
            if (ts >= nowSec - sec60d) count60 += c
            if (ts >= nowSec - sec30d) count30 += c
            if (c > 0 && ts > lastTs)  lastTs = ts
        }
        if (!Double.isNaN(d)) { durSum += d; durCnt++ }
    }

    nodesWithData << nodeDirectory.name
}

if (nodesWithData.isEmpty()) {
    return "<p style='color:red'>RRD file not found on any node for: " +
           "<code>${SCRIPT_ID}</code><br>" +
           "Check the SCRIPT_ID value, or run 01-discover-ids.groovy to confirm it.</p>"
}


// ── Format for display ────────────────────────────────────────────────────

def fmtCount = { double v -> "~${(long)(v + 0.5)}" }

String avgDur  = durCnt > 0 ? "${(long)(durSum / durCnt)} ms" : "—"
String lastRun = lastTs > 0
    ? new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date(lastTs * 1000L))
    : "— (no data in last 90 days)"


// ── Render HTML ───────────────────────────────────────────────────────────

return """
<html><body style="font-family:sans-serif;font-size:13px;color:#172B4D;margin:16px">

  <h2 style="color:#0052CC">ScriptRunner — RRD Usage Report (Multi-Node)</h2>

  <table border="0" cellpadding="4" cellspacing="0" style="margin-bottom:16px">
    <tr>
      <td style="color:#6B778C;padding-right:16px">Feature Type</td>
      <td><strong>${featureType}</strong></td>
    </tr>
    <tr>
      <td style="color:#6B778C;padding-right:16px">Name</td>
      <td><strong>${scriptName}</strong></td>
    </tr>
    ${scriptExtra ? "<tr><td style='color:#6B778C;padding-right:16px'>Details</td><td>${scriptExtra}</td></tr>" : ""}
    <tr>
      <td style="color:#6B778C;padding-right:16px">Script ID</td>
      <td><code>${SCRIPT_ID}</code></td>
    </tr>
    <tr>
      <td style="color:#6B778C;padding-right:16px">Nodes with data</td>
      <td>${nodesWithData.join(', ')}</td>
    </tr>
    ${nodesWithoutData ? "<tr><td style='color:#6B778C;padding-right:16px'>Nodes with no data</td><td>${nodesWithoutData.join(', ')} (script has not run on these nodes)</td></tr>" : ""}
  </table>

  <table border="1" cellpadding="8" cellspacing="0"
         style="border-collapse:collapse;min-width:400px">
    <thead>
      <tr style="background:#0052CC;color:#fff">
        <th>Metric</th><th>Value</th>
      </tr>
    </thead>
    <tbody>
      <tr><td>Executions — last 30 days</td><td>${fmtCount(count30)}</td></tr>
      <tr><td>Executions — last 60 days</td><td>${fmtCount(count60)}</td></tr>
      <tr><td>Executions — last 90 days</td><td>${fmtCount(count90)}</td></tr>
      <tr><td>Avg duration (over 90 days)</td><td>${avgDur}</td></tr>
      <tr><td>Last execution (date only)</td><td>${lastRun}</td></tr>
    </tbody>
  </table>

  <p style="color:#6B778C;font-size:11px;margin-top:12px">
    ⏱ Counts are summed across ${nodesWithData.size()} node(s) and come from the
    RRD daily archive — they may lag by up to one consolidation cycle.
    Counts prefixed with ~ are approximate (RRD stores averages per window).
    Use the SR admin Performance tab for real-time data.
  </p>

</body></html>
"""
