// ═══════════════════════════════════════════════════════════════════════
// ScriptRunner RRD Usage PoC — Multi-Node Variant
// Run from: Script Console  |  Output: HTML
//
// Use this instead of usage-report.groovy if your Jira instance runs
// on multiple nodes (Data Center cluster). It discovers all node
// directories automatically and sums execution counts across every node.
//
// On a single-node instance this produces the same result as
// 02-usage-report.groovy — it is safe to use either way.
//
// See docs/field-guide.md for how to find the right SCRIPT_ID for each
// feature type (Listeners, Jobs, Post-Functions, Script Fields, etc.).
// ═══════════════════════════════════════════════════════════════════════


// ── ⚙ ONE VALUE TO CONFIGURE ─────────────────────────────────────────────
//
// The RRD key is the filename (without .rrd4j) under:
//   $JIRA_HOME/scriptrunner/rrd/{nodeId}/
//
// Run 01-discover-ids.groovy to find the correct ID for Script Fields,
// Workflow Post-Functions, and REST Endpoints.
//
// For Scheduled Jobs, Escalation Services, Listeners, and Post-Functions
// the ID is the UUID in the SR admin URL when you click Edit.

String SCRIPT_ID = "paste-your-script-id-here"   // ← the only thing to change


// ── Imports ──────────────────────────────────────────────────────────────

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome
import org.rrd4j.ConsolFun
import org.rrd4j.core.RrdDb


// ── Calculate the time windows we care about ─────────────────────────────
// RRD works in epoch seconds (not milliseconds), so we convert everything
// here once. We'll use these boundaries when bucketing the fetched rows.

long currentTimeSeconds  = (long)(System.currentTimeMillis() / 1000)
long thirtyDaysInSeconds = 30L * 86_400L
long sixtyDaysInSeconds  = 60L * 86_400L
long ninetyDaysInSeconds = 90L * 86_400L


// ── Locate all node directories under the RRD base folder ────────────────
// On a single-node instance there will be exactly one directory.
// On a cluster there will be one per node — we read all of them.

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
// Each node writes its own copy of the RRD file. We loop over every node
// directory, open its RRD file (if it exists), and add its counts and
// durations into running totals. A missing file just means the script has
// never run on that node — that is normal and we note it for the report.

double executionCount30Days   = 0
double executionCount60Days   = 0
double executionCount90Days   = 0
double totalDurationSum       = 0
int    durationSampleCount    = 0
long   lastExecutionTimestamp = 0L
List<String> nodesWithData    = []
List<String> nodesWithoutData = []

for (File nodeDirectory : nodeDirectories) {
    File rrdFile = new File(nodeDirectory, "${SCRIPT_ID}.rrd4j")

    // A node only has an RRD file if the script has run on that node.
    // Missing files are normal — skip silently and note it for the report.
    if (!rrdFile.exists()) {
        nodesWithoutData << nodeDirectory.name
        continue
    }

    RrdDb rrdDatabase = RrdDb.getBuilder()
        .setPath(rrdFile.absolutePath)
        .readOnly()
        .build()

    def fetchedData = rrdDatabase
        .createFetchRequest(ConsolFun.AVERAGE, currentTimeSeconds - ninetyDaysInSeconds, currentTimeSeconds)
        .fetchData()

    rrdDatabase.close()

    long[]   timestamps      = fetchedData.timestamps
    double[] bucketCounts    = fetchedData.getValues('count')
    double[] bucketDurations = fetchedData.getValues('duration')

    // Walk every daily bucket for this node and add it to the running totals.
    // NaN means no data was recorded for that bucket — we skip those.
    for (int i = 0; i < fetchedData.rowCount; i++) {
        long   bucketTimestamp = timestamps[i]
        double bucketCount     = bucketCounts[i]
        double bucketDuration  = bucketDurations[i]

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

        if (!Double.isNaN(bucketDuration)) {
            totalDurationSum    += bucketDuration
            durationSampleCount++
        }
    }

    nodesWithData << nodeDirectory.name
}

if (nodesWithData.isEmpty()) {
    return "<p style='color:red'>RRD file not found on any node for: " +
           "<code>${SCRIPT_ID}</code><br>" +
           "This means the script has never run, or the ID is wrong.</p>"
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
// HTML directly. Counts are summed across all nodes that had data for this
// script. Nodes with no data are listed separately for transparency.

return """
<html><body style="font-family:sans-serif;font-size:13px;color:#172B4D;margin:16px">
  <h2 style="color:#0052CC">ScriptRunner — RRD Usage PoC (Multi-Node)</h2>
  <p style="color:#6B778C">
    Script ID: <code>${SCRIPT_ID}</code>
  </p>
  <p style="color:#6B778C;font-size:11px">
    Nodes read: <strong>${nodesWithData.join(', ')}</strong>
    ${nodesWithoutData ? "<br>Nodes with no data for this script (normal): ${nodesWithoutData.join(', ')}" : ''}
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
    ⏱ Counts are summed across ${nodesWithData.size()} node(s) and come from the
    RRD daily archive — they may lag by up to one consolidation cycle.
    Counts prefixed with ~ are approximate (RRD stores averages per window).
    Use the SR admin Performance tab for real-time data.
  </p>
</body></html>
"""
