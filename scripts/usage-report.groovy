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


// ── Calculate the time windows we care about ─────────────────────────────
// RRD works in epoch seconds (not milliseconds), so we convert everything
// here once. We'll use these boundaries when bucketing the fetched rows.

long currentTimeSeconds    = (long)(System.currentTimeMillis() / 1000)
long thirtyDaysInSeconds   = 30L * 86_400L
long sixtyDaysInSeconds    = 60L * 86_400L
long ninetyDaysInSeconds   = 90L * 86_400L


// ── Locate the RRD file on disk ───────────────────────────────────────────
// ScriptRunner writes one .rrd4j file per script under
// $JIRA_HOME/scriptrunner/rrd/. We build the path from the two config
// values at the top of this script.

JiraHome jiraHome = ComponentAccessor.getComponent(JiraHome)
File rrdFile = new File(
    jiraHome.home, "scriptrunner/rrd/${NODE_ID}/${SCRIPT_ID}.rrd4j"
)

if (!rrdFile.exists()) {
    return "<p style='color:red'>RRD file not found: ${rrdFile.absolutePath}<br>" +
           "This means the script has never run, or the ID / node name is wrong.</p>"
}


// ── Open the RRD database and fetch 90 days of data ──────────────────────
// ConsolFun.AVERAGE reads the daily consolidated archive — the same data
// source the SR admin UI Performance tab graphs use. The 5-minute archive
// (what the Performance tab reads in real time) is not used here because
// it only retains a short window; the daily archive covers up to ~2 years.

RrdDb rrdDatabase = RrdDb.getBuilder()
    .setPath(rrdFile.absolutePath)
    .readOnly()
    .build()

FetchData fetchedData = rrdDatabase
    .createFetchRequest(ConsolFun.AVERAGE, currentTimeSeconds - ninetyDaysInSeconds, currentTimeSeconds)
    .fetchData()

rrdDatabase.close()


// ── Unpack the raw arrays from the fetched data ───────────────────────────
// Each row is one consolidated daily bucket.
//   timestamps  — the epoch-second timestamp for that bucket
//   bucketCounts    — number of executions recorded in that bucket
//   bucketDurations — average execution time (ms) recorded in that bucket
// NaN means no data was recorded for that bucket — we skip those below.

long[]   timestamps      = fetchedData.timestamps
double[] bucketCounts    = fetchedData.getValues('count')
double[] bucketDurations = fetchedData.getValues('duration')


// ── Walk every daily bucket and accumulate the totals we need ────────────
// We tally execution counts into three rolling windows (30 / 60 / 90 days)
// and separately accumulate duration samples so we can compute an average.
// We also track the timestamp of the most recent bucket that had activity,
// which becomes the "last run" date shown in the table.

double executionCount30Days  = 0
double executionCount60Days  = 0
double executionCount90Days  = 0
double totalDurationSum      = 0
int    durationSampleCount   = 0
long   lastExecutionTimestamp = 0L

for (int i = 0; i < fetchedData.rowCount; i++) {
    long   bucketTimestamp = timestamps[i]
    double bucketCount     = bucketCounts[i]
    double bucketDuration  = bucketDurations[i]

    // Accumulate execution counts into whichever windows this bucket falls in.
    // A bucket can fall in multiple windows (e.g. 15 days ago is in all three).
    if (!Double.isNaN(bucketCount)) {
        executionCount90Days += bucketCount

        if (bucketTimestamp >= currentTimeSeconds - sixtyDaysInSeconds) {
            executionCount60Days += bucketCount
        }
        if (bucketTimestamp >= currentTimeSeconds - thirtyDaysInSeconds) {
            executionCount30Days += bucketCount
        }

        // Record the most recent bucket that actually had executions in it.
        if (bucketCount > 0 && bucketTimestamp > lastExecutionTimestamp) {
            lastExecutionTimestamp = bucketTimestamp
        }
    }

    // Accumulate duration samples separately so we can average them later.
    if (!Double.isNaN(bucketDuration)) {
        totalDurationSum    += bucketDuration
        durationSampleCount++
    }
}


// ── Format the accumulated totals for display ─────────────────────────────
// RRD stores averages per window, so the summed counts are approximate for
// high-frequency scripts. We round to the nearest integer and prefix with ~
// to signal that. Duration is a straight mean across all sampled buckets.
// The last-run timestamp is converted from epoch seconds to a readable date.

String formattedCount30Days = "~${(long)(executionCount30Days + 0.5)}"
String formattedCount60Days = "~${(long)(executionCount60Days + 0.5)}"
String formattedCount90Days = "~${(long)(executionCount90Days + 0.5)}"

String averageDurationDisplay = durationSampleCount > 0
    ? "${(long)(totalDurationSum / durationSampleCount)} ms"
    : "—"

// RRD timestamps are epoch seconds — multiply by 1000 to get milliseconds
// before passing to Java's Date, which expects milliseconds.
String lastRunDateDisplay = lastExecutionTimestamp > 0
    ? new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date(lastExecutionTimestamp * 1000L))
    : "— (no data in last 90 days)"


// ── Render the results as an HTML table ───────────────────────────────────
// The Script Console displays whatever this script returns, and it renders
// HTML directly. We build a simple styled table with one row per metric.

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
      <tr><td>Executions — last 30 days</td><td>${formattedCount30Days}</td></tr>
      <tr><td>Executions — last 60 days</td><td>${formattedCount60Days}</td></tr>
      <tr><td>Executions — last 90 days</td><td>${formattedCount90Days}</td></tr>
      <tr><td>Avg duration (over 90 days)</td><td>${averageDurationDisplay}</td></tr>
      <tr><td>Last execution (date only)</td><td>${lastRunDateDisplay}</td></tr>
    </tbody>
  </table>
  <p style="color:#6B778C;font-size:11px;margin-top:12px">
    ⏱ Counts come from the RRD daily archive and may lag by up to one
    consolidation cycle. Use the SR admin Performance tab for real-time data.
    Counts prefixed with ~ are approximate (RRD stores averages per window).
  </p>
</body></html>
"""
