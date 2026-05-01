# Field Guide

> `docs/field-guide.md` — part of the [ScriptRunner RRD Usage PoC](../README.md)

This guide explains everything you need to run the PoC scripts against any
ScriptRunner feature on your instance. It covers how to find the right ID,
what the node directory is, and how each feature type works.

**New here?** Start with the [README](../README.md) for the Quick Start,
then come back here when you need more detail.

---

## How the data works

ScriptRunner records execution data for every script it runs into **RRD
(Round Robin Database)** files on disk. These are the same files that power
the **Performance tab** graphs in the SR admin UI.

Each script gets its own file:

```
$JIRA_HOME/scriptrunner/rrd/{nodeId}/{scriptId}.rrd4j
```

The file contains two archives:
- **5-minute archive** — what the SR Performance tab reads in real time
- **Daily archive** — what the PoC script reads (up to ~2 years of history)

The PoC reads the **daily archive** using `ConsolFun.AVERAGE`. This means
counts may lag by up to one consolidation cycle after a script runs. For
real-time data, use the SR admin Performance tab directly.

> **RRD timestamps are epoch seconds, not milliseconds.**
> Multiply by 1000 when converting to a Java `Date`.

---

## Step 1 — Find your node ID

The node ID is the name of the directory directly under
`$JIRA_HOME/scriptrunner/rrd/`.

**Option A — Check the filesystem (if you have server access):**
```
ls $JIRA_HOME/scriptrunner/rrd/
```
You will see one directory per node, e.g. `dc-saunders-0` or `jira-node-1`.

**Option B — Read it from Groovy (Script Console):**
```groovy
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome

def home = ComponentAccessor.getComponent(JiraHome).home
def rrdBase = new File(home, "scriptrunner/rrd")
rrdBase.listFiles()?.findAll { it.isDirectory() }?.each {
    println it.name
}
```
Run this in the Script Console and it prints every node directory name.

**Single-node vs. multi-node:**
- Single-node: one directory, use that name directly.
- Multi-node (Data Center cluster): one directory per node. The PoC reads
  one node only. To sum across all nodes you would loop over every node
  directory, read the same `.rrd4j` file from each, and add the counts
  together. See the **Multi-node** section at the bottom of this guide
  for a minimal worked example.

---

## Step 2 — Find the script ID (by feature type)

The script ID is the **filename without `.rrd4j`**. The format varies by
feature type.

> **Shortcut for the three tricky ones:** Script Fields, Workflow
> Post-Functions, and REST Endpoints all have IDs that are hard to find
> manually. Run the **ID Discovery Script** (separate artifact) from the
> Script Console and it prints every ID for all three feature types in one
> go — ready to copy and paste into the PoC.

---

### Scheduled Jobs & Escalation Services

**ID format:** UUID

**How to find it:**
1. Go to **SR admin → Jobs**
   (`/plugins/servlet/scriptrunner/admin/jobs`)
2. Click **Edit** next to the job
3. The UUID appears in the browser URL:
   `?id=e2c59022-d52f-48ae-bf23-ec04dc5238dc`
4. Copy everything after `?id=`

**Example RRD filename:**
```
e2c59022-d52f-48ae-bf23-ec04dc5238dc.rrd4j
```

> Escalation Services live on the same Jobs page and use the same UUID
> format. They are distinguished by their type in the SR config, not by
> their ID format.

---

### Script Listeners

**ID format:** UUID

**How to find it:**
1. Go to **SR admin → Listeners**
   (`/plugins/servlet/scriptrunner/admin/listeners`)
2. Click **Edit** next to the listener
3. Copy the UUID from the browser URL: `?id=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`

**Example RRD filename:**
```
dae8a1ee-f9fa-4300-af7a-f836597c9c2f.rrd4j
```

> SR does not expose an API to list all listener UUIDs programmatically.
> You must collect them manually from the admin UI, one by one.

---

### Workflow Post-Functions

**ID format:** UUID (`FIELD_FUNCTION_ID` stored in the function config)

The easiest way is to run the **ID Discovery Script** — it walks every
workflow and transition automatically and prints a table of all SR
post-function IDs alongside their workflow and transition names.

If you prefer to find one manually:
1. Go to **Jira admin → Workflows**
   (`/secure/admin/workflows/ListWorkflows.jspa`)
2. Click **Edit** on the workflow → click the transition → **Post Functions**
3. Click **Edit** on the ScriptRunner post-function
4. The UUID is in the URL as `FIELD_FUNCTION_ID=xxxxxxxx-xxxx-...`

**Example RRD filename:**
```
a1b2c3d4-e5f6-7890-abcd-ef1234567890.rrd4j
```

---

### Script Fields

**ID format:** `fieldConfigurationSchemeId` — **NOT** the Jira custom field ID

This is the most important gotcha with script fields. SR uses the
**field configuration scheme ID** as the RRD key — not the Jira custom
field ID (e.g. `10900`) that you see in the field's URL or in
`customfield_XXXXX`. Using the wrong ID will show 0 executions for every
script field even when they are running constantly.

The easiest way to get the correct ID is to run the **ID Discovery Script**
— it reads the mapping directly from the Jira Java API (no DB query needed)
and prints every script field alongside both its custom field ID and its
correct RRD key.

**Example output from the discovery script:**
```
Field Name                               RRD Key          Custom Field ID
-----------------------------------------------------------------------
My Calculated Field                      11300            10900
Sprint Health Score                      11450            10950
```

Use the **RRD Key** column value in the PoC script — not the Custom Field ID.

**Example RRD filename:**
```
11300.rrd4j   ← fieldConfigurationSchemeId, not 10900 (the customFieldId)
```

---

### REST Endpoints

**ID format:** `{HTTP_METHOD}-{endpointName}` e.g. `GET-myEndpoint`

The easiest way is to run the **ID Discovery Script** — it scans the RRD
directory and lists every REST endpoint that has ever been called, with
the exact string to paste into the PoC script.

If you want to find one manually:
1. Go to **SR admin → REST Endpoints**
   (`/plugins/servlet/scriptrunner/admin/restendpoints`)
2. Note the endpoint URL path, e.g. `.../custom/myEndpoint`
3. Note the HTTP method (GET, POST, etc.)
4. Combine them: `GET-myEndpoint`

**Example RRD filenames:**
```
GET-myEndpoint.rrd4j
POST-createIssue.rrd4j
```

> **Important:** SR only creates the RRD file on the first call to an
> endpoint. If an endpoint has never been called it will not appear in the
> discovery script output — and the PoC will return "RRD file not found"
> for it. That is expected, not a bug.

---

### JQL Functions

**ID format:** the function name itself

**How to find it:**
1. Go to **SR admin → JQL Functions**
   (`/plugins/servlet/scriptrunner/admin/jqlfunctions`)
2. The function name shown in the UI is the RRD key

**Alternative — list all JQL function RRD files from Groovy:**
```groovy
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome

def home = ComponentAccessor.getComponent(JiraHome).home
def nodeDir = new File(home, "scriptrunner/rrd/dc-saunders-0")  // ← your node
nodeDir.listFiles()
    ?.findAll { f ->
        String n = f.name.replace('.rrd4j', '')
        !n.matches(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/) &&
        !n.matches(/\d+/) &&
        !n.matches(/(?i)(GET|POST|PUT|DELETE|PATCH)-.+/)
    }
    ?.sort { it.name }
    ?.each { println it.name.replace('.rrd4j', '') }
```

**Example RRD filename:**
```
myJqlFunction.rrd4j
```

> JQL functions only appear if they have been called at least once.
> SR creates the RRD file on first use.

---

### Behaviours & Script Fragments

**Execution tracking: not available.**

SR does not record execution history for Behaviours or Script Fragments.
No RRD file is created for these feature types. They will not appear in
any RRD-based report.

---

## Step 3 — List all RRD files on your instance

To see everything SR is tracking, run this in the Script Console:

```groovy
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome

def home = ComponentAccessor.getComponent(JiraHome).home
def rrdBase = new File(home, "scriptrunner/rrd")

rrdBase.listFiles()?.findAll { it.isDirectory() }?.sort { it.name }?.each { nodeDir ->
    println "=== Node: ${nodeDir.name} ==="
    nodeDir.listFiles()
        ?.findAll { it.name.endsWith('.rrd4j') }
        ?.sort { it.name }
        ?.each { println "  ${it.name.replace('.rrd4j', '')}" }
}
```

This gives you a complete inventory of every script SR has ever tracked on
this instance, across all nodes.

---

## Step 4 — Understand the output

| Value | Meaning |
|---|---|
| `~42` | Approximate count. RRD stores averages per 5-min window; the total is the sum of those averages. Common for script fields and listeners. |
| `0` | No executions recorded in this window, but the RRD file exists (script has run at some point). |
| `—` | No data at all — either the file is new or no executions have consolidated yet. |
| `n/a` | RRD file not found — script has never run (REST endpoints, JQL functions). |
| Last execution date only | RRD's daily archive has day-level resolution. No time component is available. |

**Why counts may be zero for a script that just ran:**
The daily archive consolidates from the 5-minute archive periodically.
A script that ran minutes ago may still show 0 until the next rollup.
Use the SR admin **Performance tab** for real-time confirmation.

---

## Quick reference — ID format by feature type

| Feature Type | RRD Key Format | Where to find it |
|---|---|---|
| Scheduled Job | UUID | SR admin → Jobs → Edit → URL `?id=` |
| Escalation Service | UUID | SR admin → Jobs → Edit → URL `?id=` |
| Script Listener | UUID | SR admin → Listeners → Edit → URL `?id=` |
| Workflow Post-Function | UUID | **ID Discovery Script** (or: Workflow editor → Post Functions → Edit → URL) |
| Script Field | `fieldConfigurationSchemeId` | **ID Discovery Script** (not the custom field ID — see note above) |
| REST Endpoint | `{METHOD}-{name}` | **ID Discovery Script** (or: SR admin → REST Endpoints → URL + method) |
| JQL Function | function name | SR admin → JQL Functions → function name |
| Behaviour | *(not tracked)* | — |
| Script Fragment | *(not tracked)* | — |

---

## Putting it all together

1. Run the **"list all RRD files"** snippet to see what SR is tracking.
2. Cross-reference with the table above to identify which feature each file belongs to.
3. For script fields, run the **DB snippet** to get the correct `fieldConfigurationSchemeId`.
4. Plug the ID and your node name into the PoC script and run it.

The PoC is intentionally minimal — it shows you the mechanism. Once you
are comfortable with how the data is read, you can extend it in any
direction: loop over multiple script IDs, cover every feature type, add
a DB fallback, or build a full HTML inventory. The building blocks are
all here.

---

## Multi-node — extending the PoC

On a single-node instance the PoC reads one directory and you are done.
On a multi-node Data Center cluster, SR writes a separate RRD file for
each node under its own subdirectory. To get a complete picture you need
to read the same script ID from every node and sum the counts.

Here is the minimal extension — swap the single-file read in the PoC for
this block:

```groovy
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome
import org.rrd4j.ConsolFun
import org.rrd4j.core.RrdDb

String SCRIPT_ID = "paste-your-script-id-here"

long nowSec = System.currentTimeMillis().intdiv(1000) as long
long sec30  = 30L * 86_400L
long sec60  = 60L * 86_400L
long sec90  = 90L * 86_400L

def home    = ComponentAccessor.getComponent(JiraHome).home
def rrdBase = new File(home, "scriptrunner/rrd")

double sum30 = 0, sum60 = 0, sum90 = 0
double durSum = 0
int    durCnt = 0
long   lastTs = 0L

// Loop over every node directory and accumulate counts
rrdBase.listFiles()?.findAll { it.isDirectory() }?.each { nodeDir ->
    File rrdFile = new File(nodeDir, "${SCRIPT_ID}.rrd4j")
    if (!rrdFile.exists()) return   // this node has no data for this script

    RrdDb db = RrdDb.getBuilder().setPath(rrdFile.absolutePath).readOnly().build()
    def fd = db.createFetchRequest(ConsolFun.AVERAGE, nowSec - sec90, nowSec).fetchData()
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
}

println "30d: ~${(long)(sum30 + 0.5)}"
println "60d: ~${(long)(sum60 + 0.5)}"
println "90d: ~${(long)(sum90 + 0.5)}"
println "Avg duration: ${durCnt > 0 ? (long)(durSum / durCnt) + ' ms' : '—'}"
println "Last run: ${lastTs > 0 ? new java.text.SimpleDateFormat('yyyy-MM-dd').format(new Date(lastTs * 1000L)) : '—'}"
```

**What this does differently from the single-node PoC:**
- Discovers node directories automatically — no hardcoded node name needed
- Skips nodes that have no RRD file for this script (normal — a node only
  has a file if the script has run on that node)
- Adds counts across nodes (a script that ran 10 times on node-0 and 5
  times on node-1 shows 15 total)
- Averages duration across nodes (a rough approximation — good enough for
  a PoC)

**One thing to be aware of:** RRD stores averages per 5-minute window, so
the summed counts are already approximate (hence the `~` prefix). Summing
across nodes compounds that approximation slightly, but the result is a
reliable indicator of relative activity.
