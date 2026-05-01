// ═══════════════════════════════════════════════════════════════════════
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

// ── Time windows (RRD works in epoch seconds, not milliseconds) ───────────

long nowSec = System.currentTimeMillis().intdiv(1000) as long
long sec30  = 30L * 86_400L
long sec60  = 60L * 86_400L
long sec90  = 90L * 86_400L

// ── Locate all node directories ───────────────────────────────────────────
// On a single-node instance there will be exactly one directory.
// On a cluster there will be one per node — we read all of them.

JiraHome jiraHome = ComponentAccessor.getComponent(JiraHome)
File rrdBase = new File(jiraHome.home, "scriptrunner/rrd")

List<File> nodeDirs = rrdBase.listFiles()
    ?.findAll { it.isDirectory() }
    ?.sort { it.name }
    ?: []

if (!rrdBase.exists() || nodeDirs.isEmpty()) {
    return "<p style='color:red'>No RRD node directories found under: " +
           "${rrdBase.absolutePath}</p>"
}

// ── Read and accumulate across all nodes ──────────────────────────────────
// Each node writes its own copy of the RRD file. We sum the counts and
// average the durations across nodes to get an instance-wide total.

double sum30 = 0, sum60 = 0, sum90 = 0
double durSum = 0
int    durCnt = 0
long   lastTs = 0L
List<String> nodesRead   = []
List<String> nodesMissed = []

nodeDirs.each { File nodeDir ->
    File rrdFile = new File(nodeDir, "${SCRIPT_ID}.rrd4j")

    // A node only has an RRD file if the script has run on that node.
    // Missing files are normal — skip silently and note it for the report.
    if (!rrdFile.exists()) {
        nodesMissed << nodeDir.name
        return
    }

    RrdDb db = RrdDb.getBuilder()
        .setPath(rrdFile.absolutePath)
        .readOnly()
        .build()

    def fd = db.createFetchRequest(ConsolFun.AVERAGE, nowSec - sec90, nowSec)
        .fetchData()
    db.close()

    long[]   timestamps = fd.timestamps
    double[] counts     = fd.getValues('count')
    double[] durations  = fd.getValues('duration')

    (0..<fd.rowCount).each { int i ->
        double c = counts[i]
        double d = durations[i]
        if (!Double.isNaN(c)) {
            sum90 += c
            if (timestamps[i] >= nowSec - sec60) sum60 += c
            if (timestamps[i] >= nowSec - sec30) sum30 += c
            if (c > 0 && timestamps[i] > lastTs) lastTs = timestamps[i]
        }
        if (!Double.isNaN(d)) { durSum += d; durCnt++ }
    }
    nodesRead << nodeDir.name
}

if (nodesRead.isEmpty()) {
    return "<p style='color:red'>RRD file not found on any node for: " +
           "<code>${SCRIPT_ID}</code><br>" +
           "This means the script has never run, or the ID is wrong.</p>"
}

// ── Format results ────────────────────────────────────────────────────────

String fmt30  = "~${(long)(sum30 + 0.5)}"
String fmt60  = "~${(long)(sum60 + 0.5)}"
String fmt90  = "~${(long)(sum90 + 0.5)}"
String avgDur = durCnt > 0 ? "${(long)(durSum / durCnt)} ms" : "—"
String lastRun = lastTs > 0
    ? new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date(lastTs * 1000L))
    : "— (no data in last 90 days)"

// ── Render HTML ───────────────────────────────────────────────────────────

return """
<html><body style="font-family:sans-serif;font-size:13px;color:#172B4D;margin:16px">
  <h2 style="color:#0052CC">ScriptRunner — RRD Usage PoC (Multi-Node)</h2>
  <p style="color:#6B778C">
    Script ID: <code>${SCRIPT_ID}</code>
  </p>
  <p style="color:#6B778C;font-size:11px">
    Nodes read: <strong>${nodesRead.join(', ')}</strong>
    ${nodesMissed ? "<br>Nodes with no data for this script (normal): ${nodesMissed.join(', ')}" : ''}
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
    ⏱ Counts are summed across ${nodesRead.size()} node(s) and come from the
    RRD daily archive — they may lag by up to one consolidation cycle.
    Counts prefixed with ~ are approximate (RRD stores averages per window).
    Use the SR admin Performance tab for real-time data.
  </p>
</body></html>
"""
