// ═══════════════════════════════════════════════════════════════════════
// scripts/02-usage-report.groovy
// ScriptRunner RRD Usage PoC — Single Node
// Run from: Script Console  |  Output: HTML
//
// PURPOSE: Show that ScriptRunner records execution data for every script
// it runs, and that data is accessible programmatically. This reads one
// script's RRD file and renders a simple metrics table — including the
// script's name and feature type, looked up automatically from the ID.
//
// MULTI-NODE CLUSTER? Use 03-usage-report-multi-node.groovy instead.
//
// Run 01-discover-ids.groovy first to find the correct SCRIPT_ID.
// ═══════════════════════════════════════════════════════════════════════


// ── ⚙ CONFIGURE THESE TWO VALUES ────────────────────────────────────────

String SCRIPT_ID = "e2c59022-d52f-48ae-bf23-ec04dc5238dc"  // ←  find this from discover-ids script
String NODE_ID   = "dc-saunders-0"                          // ← your node dir


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
import org.rrd4j.core.FetchData
import org.rrd4j.core.RrdDb


// ── Time windows ──────────────────────────────────────────────────────────

long nowSec   = (long)(System.currentTimeMillis() / 1000)
long sec30d   = 30L * 86_400L
long sec60d   = 60L * 86_400L
long sec90d   = 90L * 86_400L


// ── Locate the RRD file ───────────────────────────────────────────────────

JiraHome jiraHome = ComponentAccessor.getComponent(JiraHome)
File rrdFile = new File(jiraHome.home, "scriptrunner/rrd/${NODE_ID}/${SCRIPT_ID}.rrd4j")

if (!rrdFile.exists()) {
    return "<p style='color:red'>RRD file not found: ${rrdFile.absolutePath}<br>" +
           "Check the SCRIPT_ID and NODE_ID values at the top of this script.<br>" +
           "Run 01-discover-ids.groovy to find the correct values.</p>"
}


// ── Identify the script — name and feature type ───────────────────────────
// We work out what this SCRIPT_ID belongs to by checking each feature type
// in turn. The ID format tells us where to look:
//   METHOD-name  → REST Endpoint
//   numeric      → Script Field (fieldConfigurationSchemeId)
//   UUID         → Job, Escalation Service, Post-Function, or Listener
//   anything else → JQL Function

String featureType = "Unknown"
String scriptName  = SCRIPT_ID  // fallback — overwritten below if found
String scriptExtra = ""         // optional second line of context

boolean isRestPattern = SCRIPT_ID.matches(/(?i)(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)-.+/)
boolean isNumeric     = SCRIPT_ID.matches(/\d+/)
boolean isUuid        = SCRIPT_ID.matches(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/)

if (isRestPattern) {
    // ── REST Endpoint ─────────────────────────────────────────────────────
    // The key encodes both the HTTP method and the endpoint name.
    featureType = "REST Endpoint"
    String method = SCRIPT_ID.split('-')[0].toUpperCase()
    String name   = SCRIPT_ID.replaceFirst(/(?i)^[A-Z]+-/, '')
    scriptName  = name
    scriptExtra = "HTTP Method: ${method}"

} else if (isNumeric) {
    // ── Script Field ──────────────────────────────────────────────────────
    // The RRD key is the fieldConfigurationSchemeId, not the Jira custom
    // field ID. We look up the display name from the customfields property.
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
    // ── UUID — check Jobs, Escalation Services, then Post-Functions ───────
    // If not found in any of those, it is most likely a Script Listener —
    // but we cannot confirm this programmatically.

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

    // Check Workflow Post-Functions (only if not already found above)
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

    // If still unknown — most likely a Script Listener
    if (featureType == "Unknown") {
        featureType = "Script Listener (unconfirmed)"
        scriptName  = "Unknown — UUID not found in Jobs, Escalation Services, or Post-Functions"
        scriptExtra = "If this is a listener, the UUID is correct. " +
                      "SR does not expose listener names via a public API."
    }

} else {
    // ── JQL Function ──────────────────────────────────────────────────────
    // The RRD key IS the function name.
    featureType = "JQL Function"
    scriptName  = SCRIPT_ID
    scriptExtra = "JQL usage: issueFunction in ${SCRIPT_ID}(...)"
}


// ── Read the RRD file ─────────────────────────────────────────────────────

RrdDb rrdDatabase = RrdDb.getBuilder()
    .setPath(rrdFile.absolutePath)
    .readOnly()
    .build()

FetchData fetchedData = rrdDatabase
    .createFetchRequest(ConsolFun.AVERAGE, nowSec - sec90d, nowSec)
    .fetchData()

rrdDatabase.close()

long[]   timestamps      = fetchedData.timestamps
double[] bucketCounts    = fetchedData.getValues('count')
double[] bucketDurations = fetchedData.getValues('duration')


// ── Accumulate metrics ────────────────────────────────────────────────────

double count30 = 0, count60 = 0, count90 = 0
double durSum  = 0
int    durCnt  = 0
long   lastTs  = 0L

for (int i = 0; i < fetchedData.rowCount; i++) {
    double c = bucketCounts[i]
    double d = bucketDurations[i]
    long   ts = timestamps[i]

    if (!Double.isNaN(c)) {
        count90 += c
        if (ts >= nowSec - sec60d) count60 += c
        if (ts >= nowSec - sec30d) count30 += c
        if (c > 0 && ts > lastTs)  lastTs = ts
    }
    if (!Double.isNaN(d)) { durSum += d; durCnt++ }
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

  <h2 style="color:#0052CC">ScriptRunner — RRD Usage Report</h2>

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
      <td style="color:#6B778C;padding-right:16px">RRD file</td>
      <td><code>${rrdFile.absolutePath}</code></td>
    </tr>
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
    ⏱ Counts come from the RRD daily archive and may lag by up to one
    consolidation cycle. Use the SR admin Performance tab for real-time data.
    Counts prefixed with ~ are approximate (RRD stores averages per window).
  </p>

</body></html>
"""
