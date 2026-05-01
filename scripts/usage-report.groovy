// ═══════════════════════════════════════════════════════════════════════
// scripts/02-usage-report.groovy
// ScriptRunner RRD Usage PoC — Single Node
// Run from: Script Console  |  Output: HTML
//
// PURPOSE: Show that ScriptRunner records execution data for every script
// it runs, and that data is accessible programmatically. This reads one
// script's RRD file and renders a simple metrics table.
//
// MULTI-NODE CLUSTER? Use 03-usage-report-multi-node.groovy instead.
//
// See docs/field-guide.md for how to find the right SCRIPT_ID and
// NODE_ID for each feature type (Listeners, Jobs, Post-Functions, etc.).
// Run 01-discover-ids.groovy to find IDs for Script Fields,
// Post-Functions, and REST Endpoints automatically.
// ═══════════════════════════════════════════════════════════════════════

// ── ⚙ CONFIGURE THESE TWO VALUES ────────────────────────────────────────

// The RRD key is the filename (without .rrd4j) under:
//   $JIRA_HOME/scriptrunner/rrd/{nodeId}/
//
// Key format by feature type:
//   Scheduled Job / Escalation Service / Listener / Post-Function
//     → the UUID shown in the SR admin URL  e.g. "e2c59022-d52f-48ae-..."
//   Script Field
//     → fieldConfigurationSchemeId (NOT the Jira custom field ID)
//       Load it from the 'com.onresolve.jira.groovy.groovyrunner:customfields'
//       row in the propertytext table — see the full v54 report for how.
//   REST Endpoint  → "{METHOD}-{endpointName}"  e.g. "GET-myEndpoint"
//   JQL Function   → the function name itself    e.g. "myJqlFunction"

String SCRIPT_ID = "e2c59022-d52f-48ae-bf23-ec04dc5238dc"  // ← swap this
String NODE_ID   = "dc-saunders-0"                          // ← your node dir

// ── Imports ──────────────────────────────────────────────────────────────

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome
import org.rrd4j.ConsolFun
import org.rrd4j.core.FetchData
import org.rrd4j.core.RrdDb

// ── Time windows (RRD works in epoch seconds, not milliseconds) ───────────

long nowSec = System.currentTimeMillis().intdiv(1000) as long
long sec30  = 30L * 86_400L
long sec60  = 60L * 86_400L
long sec90  = 90L * 86_400L

// ── Locate the RRD file ───────────────────────────────────────────────────
// SR writes one .rrd4j file per script under $JIRA_HOME/scriptrunner/rrd/

JiraHome jiraHome = ComponentAccessor.getComponent(JiraHome)
File rrdFile = new File(
    jiraHome.home, "scriptrunner/rrd/${NODE_ID}/${SCRIPT_ID}.rrd4j"
)

if (!rrdFile.exists()) {
    return "<p style='color:red'>RRD file not found: ${rrdFile.absolutePath}<br>" +
           "This means the script has never run, or the ID / node name is wrong.</p>"
}

// ── Read the daily archive ────────────────────────────────────────────────
// ConsolFun.AVERAGE reads the daily consolidated archive — the same data
// source the SR admin UI Performance tab graphs use. The 5-minute archive
// (what the Performance tab reads in real time) is not used here because
// it only retains a short window; the daily archive covers up to ~2 years.

RrdDb db = RrdDb.getBuilder()
    .setPath(rrdFile.absolutePath)
    .readOnly()
    .build()

FetchData fd = db.createFetchRequest(ConsolFun.AVERAGE, nowSec - sec90, nowSec)
    .fetchData()

db.close()

// ── Aggregate the fetched rows ────────────────────────────────────────────
// Each row is one consolidated daily bucket. 'count' = executions in that
// window; 'duration' = average execution time in ms for that window.
// NaN means no data was recorded for that bucket — skip it.

long[]   timestamps = fd.timestamps
double[] counts     = fd.getValues('count')
double[] durations  = fd.getValues('duration')

double sum30 = 0, sum60 = 0, sum90 = 0
double durSum = 0
int    durCnt = 0
long   lastTs = 0L

(0..<fd.rowCount).each { int i ->
    long   ts = timestamps[i]
    double c  = counts[i]
    double d  = durations[i]

    if (!Double.isNaN(c)) {
        sum90 += c
        if (ts >= nowSec - sec60) sum60 += c
        if (ts >= nowSec - sec30) sum30 += c
        if (c > 0 && ts > lastTs) lastTs = ts
    }
    if (!Double.isNaN(d)) { durSum += d; durCnt++ }
}

// RRD stores averages per window, so the sums are approximate for high-
// frequency scripts (script fields, listeners). Round to the nearest integer.
String fmt30 = "~${(long)(sum30 + 0.5)}"
String fmt60 = "~${(long)(sum60 + 0.5)}"
String fmt90 = "~${(long)(sum90 + 0.5)}"
String avgDur = durCnt > 0 ? "${(long)(durSum / durCnt)} ms" : "—"
// RRD timestamps are epoch seconds — multiply by 1000 for Java Date
String lastRun = lastTs > 0
    ? new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date(lastTs * 1000L))
    : "— (no data in last 90 days)"

// ── Render a simple HTML table ────────────────────────────────────────────

return """
<html><body style="font-family:sans-serif;font-size:13px;color:#172B4D;margin:16px">
  <h2 style="color:#0052CC">ScriptRunner — RRD Usage PoC</h2>
  <p style="color:#6B778C">
    Script ID: <code>${SCRIPT_ID}</code><br>
    RRD file: <code>${rrdFile.absolutePath}</code>
  </p>
  <table border="1" cellpadding="8" cellspacing="0"
         style="border-collapse:collapse;min-width:400px">
    <thead>
      <tr style="background:#0052CC;color:#fff">
        <th>Metric</th><th>Value</th>
      </tr>
    </thead>
    <tbody>
      <tr><td>Executions — last 30 days</td><td>${fmt30}</td></tr>
      <tr><td>Executions — last 60 days</td><td>${fmt60}</td></tr>
      <tr><td>Executions — last 90 days</td><td>${fmt90}</td></tr>
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
