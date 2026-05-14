// ═══════════════════════════════════════════════════════════════════════
// scripts/usage-report.groovy
// ScriptRunner RRD Usage PoC — Single Node
// Run from: Script Console  |  Output: HTML
//
// PURPOSE: Show that ScriptRunner records execution data for every script
// it runs, and that data is accessible programmatically. This reads one
// script's RRD file and renders a simple metrics table — including the
// script's name, feature type (looked up automatically), and which
// projects the script is configured to apply to.
//
// MULTI-NODE CLUSTER? Use usage-report-multi-node.groovy instead.
//
// Run discover-ids.groovy first to find the correct SCRIPT_ID for Script
// Fields, Post-Functions, and REST Endpoints.
// See docs/field-guide.md for full details on every feature type.
// ═══════════════════════════════════════════════════════════════════════

// ── ⚙ CONFIGURE THESE TWO VALUES ────────────────────────────────────────
//
// SCRIPT_ID — the RRD key (filename without .rrd4j).
//   Run discover-ids.groovy to find the correct ID for Script Fields,
//   Post-Functions, and REST Endpoints. For Jobs, Escalation Services,
//   and Listeners use the UUID from the SR admin URL (?id=...).
//
// NODE_ID — the node directory name under $JIRA_HOME/scriptrunner/rrd/
//   Not sure? Run this in the Script Console:
//   new File(ComponentAccessor.getComponent(JiraHome).home, "scriptrunner/rrd")
//     .listFiles()?.each { println it.name }

String SCRIPT_ID = "xxxxxxxx-xxxx-xxxx-xxx-xxxxxxxx"  // ← find via discover-ids.groovy
String NODE_ID   = "dc-saunders-0"                          // ← your node dir

// ── Imports ──────────────────────────────────────────────────────────────

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome
import com.atlassian.jira.workflow.WorkflowManager
import com.atlassian.jira.workflow.WorkflowManager
import com.atlassian.jira.workflow.WorkflowSchemeManager
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

// ── Time windows (RRD works in epoch seconds, not milliseconds) ───────────

long nowSec = System.currentTimeMillis().intdiv(1000) as long
long sec30d = 30L * 86_400L
long sec60d = 60L * 86_400L
long sec90d = 90L * 86_400L

// ── Locate the RRD file ───────────────────────────────────────────────────

JiraHome jiraHome = ComponentAccessor.getComponent(JiraHome)
File rrdFile = new File(
    jiraHome.home, "scriptrunner/rrd/${NODE_ID}/${SCRIPT_ID}.rrd4j"
)

if (!rrdFile.exists()) {
    return "<p style='color:red'>RRD file not found: ${rrdFile.absolutePath}<br>" +
           "Check the SCRIPT_ID and NODE_ID values at the top of this script.<br>" +
           "Run discover-ids.groovy to find the correct values.</p>"
}

// ── Auto-identify the script — name and feature type ─────────────────────
// Detect what this SCRIPT_ID belongs to from its format, then look up the
// human-readable name. The detected type is shown in the output for
// confirmation; FEATURE_TYPE (above) drives project association separately.
//
//   METHOD-name  → REST Endpoint
//   numeric      → Script Field (fieldConfigurationSchemeId)
//   UUID         → Job, Escalation Service, Post-Function, or Listener
//   anything else → JQL Function

String detectedType = "Unknown"
String scriptName   = SCRIPT_ID   // fallback — overwritten below if found
String scriptExtra  = ""          // optional second line of context

boolean isRestPattern = SCRIPT_ID.matches(/(?i)(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)-.+/)
boolean isNumeric     = SCRIPT_ID.matches(/\d+/)
boolean isUuid        = SCRIPT_ID.matches(
    /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/)

if (isRestPattern) {
    // ── REST Endpoint ─────────────────────────────────────────────────────
    detectedType = "REST Endpoint"
    String method = SCRIPT_ID.split('-')[0].toUpperCase()
    String name   = SCRIPT_ID.replaceFirst(/(?i)^[A-Z]+-/, '')
    scriptName  = name
    scriptExtra = "HTTP Method: ${method}"

} else if (isNumeric) {
    // ── Script Field ──────────────────────────────────────────────────────
    // The RRD key is the fieldConfigurationSchemeId, not the Jira custom
    // field ID. Look up the display name from the customfields property.
    detectedType = "Script Field"
    try {
        DelegatorInterface del = ComponentAccessor.getComponent(DelegatorInterface)
        Connection conn = ConnectionFactory.getConnection(del.getGroupHelperName("default"))
        try {
            Sql sql = new Sql(conn)
            sql.eachRow(
                "SELECT pt.\"propertyvalue\" FROM \"propertytext\" pt" +
                " JOIN \"propertyentry\" pe ON pe.\"id\" = pt.\"id\"" +
                " WHERE pe.\"property_key\" = " +
                "'com.onresolve.jira.groovy.groovyrunner:customfields'"
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
    // ── UUID — check Jobs and Escalation Services first ───────────────────
    try {
        ScheduledScriptJobManager jobMgr =
            ComponentAccessor.getOSGiComponentInstanceOfType(ScheduledScriptJobManager)
        AbstractScheduledJobCommand match = jobMgr?.load()
            ?.find { it.id == SCRIPT_ID } as AbstractScheduledJobCommand
        if (match) {
            boolean isEscalation = match.class.simpleName == 'EscalationServiceCommand'
            detectedType = isEscalation ? "Escalation Service" : "Scheduled Job"
            scriptName   = match.notes ?: match.id ?: SCRIPT_ID
            scriptExtra  = "Owner: ${match.ownedBy ?: match.userId ?: '—'}"
        }
    } catch (ignored) {}

    // ── UUID — check Workflow Post-Functions (only if not found above) ────
    if (detectedType == "Unknown") {
        try {
            WorkflowManager wfm = ComponentAccessor.getWorkflowManager()
            wfm?.getWorkflows()?.each { wf ->
                if (detectedType != "Unknown") return
                wf.allActions?.each { action ->
                    if (detectedType != "Unknown") return
                    List<FunctionDescriptor> fns = []
                    try {
                        fns = action.unconditionalResult?.postFunctions
                            as List<FunctionDescriptor> ?: []
                    } catch (ignored2) {}
                    fns.each { FunctionDescriptor fd ->
                        if (fd.args['FIELD_FUNCTION_ID'] as String == SCRIPT_ID) {
                            detectedType = "Workflow Post-Function"
                            scriptName   = "${wf.name} → ${action.name}"
                            scriptExtra  = "Workflow active: ${wf.isActive()}"
                        }
                    }
                }
            }
        } catch (ignored) {}
    }

    // ── UUID — not found anywhere: most likely a Script Listener ──────────
    if (detectedType == "Unknown") {
        detectedType = "Script Listener (unconfirmed)"
        scriptName   = "UUID not found in Jobs, Escalation Services, or Post-Functions"
        scriptExtra  = "If this is a listener the UUID is correct. " +
                       "SR does not expose listener names via a public API."
    }

} else {
    // ── JQL Function ──────────────────────────────────────────────────────
    detectedType = "JQL Function"
    scriptName   = SCRIPT_ID
    scriptExtra  = "JQL usage: issueFunction in ${SCRIPT_ID}(...)"
}

// ── Read the RRD file ─────────────────────────────────────────────────────
// ConsolFun.AVERAGE reads the daily consolidated archive — the same data
// source the SR admin UI Performance tab graphs use.

RrdDb db = RrdDb.getBuilder()
    .setPath(rrdFile.absolutePath)
    .readOnly()
    .build()

def fd = db.createFetchRequest(ConsolFun.AVERAGE, nowSec - sec90d, nowSec)
    .fetchData()
db.close()

// ── Aggregate the fetched rows ────────────────────────────────────────────

long[]   timestamps = fd.timestamps
double[] counts     = fd.getValues('count')
double[] durations  = fd.getValues('duration')

double sum30 = 0, sum60 = 0, sum90 = 0
double durSum = 0
int    durCnt = 0
long   lastTs = 0L

(0..<fd.rowCount).each { int i ->
    double c = counts[i]
    double d = durations[i]
    if (!Double.isNaN(c)) {
        sum90 += c
        if (timestamps[i] >= nowSec - sec60d) sum60 += c
        if (timestamps[i] >= nowSec - sec30d) sum30 += c
        if (c > 0 && timestamps[i] > lastTs)  lastTs = timestamps[i]
    }
    if (!Double.isNaN(d)) { durSum += d; durCnt++ }
}

String fmt30  = "~${(long)(sum30 + 0.5)}"
String fmt60  = "~${(long)(sum60 + 0.5)}"
String fmt90  = "~${(long)(sum90 + 0.5)}"
String avgDur = durCnt > 0 ? "${(long)(durSum / durCnt)} ms" : "—"
String lastRun = lastTs > 0
    ? new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date(lastTs * 1000L))
    : "— (no data in last 90 days)"

// ── Resolve project association ───────────────────────────────────────────
// Driven by detectedType — no manual config needed. Projects shows where
// the script is configured to apply — not which projects generated each
// execution. RRD stores aggregate counts only; per-project execution
// breakdown is not available.

String projectsHtml

switch (detectedType) {

    case "Workflow Post-Function":
        // The auto-ID block already walked the workflows to find scriptName,
        // so we know the owner workflow. Build the reverse map and look it up.
        WorkflowSchemeManager wfSchemeManager =
            ComponentAccessor.getComponent(WorkflowSchemeManager)
        Map<String, List<String>> wfToProjects = [:]
        ComponentAccessor.projectManager.getProjects().each { project ->
            wfSchemeManager.getWorkflowMap(project)
                .values().unique()
                .each { String wfName ->
                    if (!wfToProjects.containsKey(wfName)) wfToProjects[wfName] = []
                    wfToProjects[wfName] << project.key
                }
        }
        // scriptName is "WorkflowName → TransitionName" — extract workflow name
        String ownerWorkflow = scriptName.contains(' → ')
            ? scriptName.split(' → ')[0]
            : null
        if (!ownerWorkflow) {
            projectsHtml = "<span style='color:#97A0AF'>Could not determine " +
                           "owner workflow</span>"
        } else {
            List<String> projs = (wfToProjects[ownerWorkflow] ?: []).sort()
            projectsHtml = projs
                ? projs.collect { "<span class='proj-tag'>${it}</span>" }.join(' ')
                : "<span style='color:#97A0AF'>No projects use workflow: " +
                  "${ownerWorkflow}</span>"
        }
        break

    case "Script Field":
        // Find the FieldConfigScheme whose ID matches SCRIPT_ID
        def matchedScheme = ComponentAccessor.customFieldManager
            .getCustomFieldObjects()
            .findAll { cf ->
                String typeKey = cf.customFieldType?.key ?: ''
                typeKey.contains('onresolve') || typeKey.contains('scriptrunner')
            }
            .collectMany { cf -> cf.getConfigurationSchemes() }
            .find { scheme -> scheme.id.toString() == SCRIPT_ID }

        if (!matchedScheme) {
            projectsHtml = "<span style='color:#97A0AF'>Scheme not found " +
                           "— check SCRIPT_ID</span>"
        } else if (matchedScheme.isGlobal()) {
            projectsHtml = "<span style='color:#00875A;font-style:italic'>" +
                           "All projects (global context)</span>"
        } else {
            List<String> projs = matchedScheme.getAssociatedProjectObjects()
                .collect { it.key }.sort()
            projectsHtml = projs
                ? projs.collect { "<span class='proj-tag'>${it}</span>" }.join(' ')
                : "<span style='color:#97A0AF'>No projects assigned to this context</span>"
        }
        break

    case "Scheduled Job":
    case "Escalation Service":
        projectsHtml = "<span style='color:#97A0AF;font-style:italic'>" +
                       "Not project-scoped — runs on a schedule</span>"
        break

    case "Script Listener (unconfirmed)":
        projectsHtml = "<span style='color:#97A0AF;font-style:italic'>" +
                       "Not accessible — project scope is held in ScriptRegistry</span>"
        break

    case "REST Endpoint":
    case "JQL Function":
        projectsHtml = "<span style='color:#97A0AF;font-style:italic'>" +
                       "Not project-scoped — instance-wide</span>"
        break

    default:
        projectsHtml = "<span style='color:#97A0AF;font-style:italic'>" +
                       "Could not determine feature type</span>"
}

// ── Render HTML ───────────────────────────────────────────────────────────

return """
<html>
<head><style>
  body  { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
          font-size: 13px; color: #172B4D; margin: 16px; }
  h2    { font-size: 15px; color: #0052CC; margin: 0 0 4px; }
  p.sub { font-size: 11px; color: #6B778C; margin: 0 0 12px; }
  table { border-collapse: collapse; min-width: 480px; }
  th    { background: #0052CC; color: #fff; padding: 8px 12px;
          text-align: left; font-size: 12px; }
  td    { padding: 7px 12px; border-bottom: 1px solid #DFE1E6;
          font-size: 12px; vertical-align: top; }
  tr:nth-child(even) td { background: #F4F5F7; }
  .proj-tag { display: inline-block; background: #EAE6FF; color: #403294;
              border-radius: 3px; padding: 1px 6px; margin: 1px 3px 1px 0;
              font-size: 11px; font-weight: bold; }
  .note { font-size: 11px; color: #6B778C; margin-top: 10px; }
  .info-table { border-collapse: collapse; margin-bottom: 16px; }
  .info-table td { padding: 3px 16px 3px 0; font-size: 12px;
                   border: none; vertical-align: top; }
  .info-table td:first-child { color: #6B778C; white-space: nowrap; }
</style></head>
<body>

  <h2>ScriptRunner — RRD Usage Report</h2>
  <p class="sub">
    Generated: ${new Date().format('yyyy-MM-dd HH:mm:ss z')} &nbsp;|&nbsp;
    Node: <code>${NODE_ID}</code> &nbsp;|&nbsp;
    Detected type: <code>${detectedType}</code>
  </p>

  <table class="info-table">
    <tr>
      <td>Feature Type</td>
      <td><strong>${detectedType}</strong></td>
    </tr>
    <tr>
      <td>Name</td>
      <td><strong>${scriptName}</strong></td>
    </tr>
    ${scriptExtra ? "<tr><td>Details</td><td>${scriptExtra}</td></tr>" : ""}
    <tr>
      <td>Script ID</td>
      <td><code>${SCRIPT_ID}</code></td>
    </tr>
    <tr>
      <td>RRD file</td>
      <td><code>${rrdFile.absolutePath}</code></td>
    </tr>
  </table>

  <table>
    <thead><tr><th>Metric</th><th>Value</th></tr></thead>
    <tbody>
      <tr><td>Projects</td><td>${projectsHtml}</td></tr>
      <tr><td>Executions — last 30 days</td><td>${fmt30}</td></tr>
      <tr><td>Executions — last 60 days</td><td>${fmt60}</td></tr>
      <tr><td>Executions — last 90 days</td><td>${fmt90}</td></tr>
      <tr><td>Avg duration (over 90 days)</td><td>${avgDur}</td></tr>
      <tr><td>Last execution (date only)</td><td>${lastRun}</td></tr>
    </tbody>
  </table>

  <p class="note">
    ⏱ Counts come from the RRD daily archive and may lag by up to one
    consolidation cycle. Counts prefixed with ~ are approximate (RRD stores
    averages per 5-minute window). Use the SR admin Performance tab for
    real-time data.<br>
    ℹ️ <em>Projects</em> shows where this script is configured to apply —
    not which projects generated each execution. Per-project execution
    breakdown is not available from RRD data.
  </p>

</body></html>
"""
