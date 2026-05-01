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
- Multi-node (Data Center cluster): one directory per node. Use
  `03-usage-report-multi-node.groovy` — it discovers all node directories
  automatically and sums counts across every node with no hardcoded node
  name needed.

---

## Step 2 — Find the script ID (by feature type)

The script ID is the **filename without `.rrd4j`**. The format varies by
feature type.

> **Shortcut:** Run [`scripts/discover-ids.groovy`](../scripts/discover-ids.groovy)
> from the Script Console. It automatically discovers the correct RRD key
> for every feature type on your instance — Scheduled Jobs, Escalation
> Services, Script Fields, Workflow Post-Functions, REST Endpoints, JQL
> Functions, Script Fragments, and Behaviours — and displays them in a
> single HTML report ready to copy from. Script Listeners are the only
> exception (see below).

---

### Scheduled Jobs & Escalation Services

**ID format:** UUID  
**Auto-discovered by:** `01-discover-ids.groovy` ✅

The discovery script lists every job and escalation service with its UUID,
name, and owner. If you prefer to find one manually:

1. Go to **SR admin → Jobs**
   (`/plugins/servlet/scriptrunner/admin/jobs`)
2. Click **Edit** next to the job
3. Copy the UUID from the browser URL: `?id=e2c59022-d52f-48ae-bf23-ec04dc5238dc`

**Example RRD filename:**
```
e2c59022-d52f-48ae-bf23-ec04dc5238dc.rrd4j
```

> Escalation Services live on the same Jobs page and use the same UUID
> format.

---

### Script Listeners

**ID format:** UUID  
**Auto-discovered by:** ❌ Not possible — manual steps required

ScriptRunner does not expose a public API to list listener UUIDs, so the
discovery script cannot show them automatically. You need to collect each
UUID manually:

1. Go to **SR admin → Listeners**
   (`/plugins/servlet/scriptrunner/admin/listeners`)
2. Click **Edit** next to the listener
3. Copy the UUID from the browser URL:
   ```
   .../scriptrunner/admin/listeners/edit/dae8a1ee-f9fa-4300-af7a-f836597c9c2f
   ```
   The UUID is the last segment — `dae8a1ee-f9fa-4300-af7a-f836597c9c2f`
4. Use that UUID as the `SCRIPT_ID` in the usage report

The execution data for that listener is already on disk — you are just
telling the script where to look. Repeat once per listener.

**Example RRD filename:**
```
dae8a1ee-f9fa-4300-af7a-f836597c9c2f.rrd4j
```

---

### Workflow Post-Functions

**ID format:** UUID (`FIELD_FUNCTION_ID` stored in the function config)  
**Auto-discovered by:** `01-discover-ids.groovy` ✅

The discovery script walks every workflow and transition automatically and
shows every SR post-function alongside its workflow name, transition name,
and active/inactive status. If you prefer to find one manually:

1. Go to **Jira admin → Workflows**
   (`/secure/admin/workflows/ListWorkflows.jspa`)
2. Click **Edit** on the workflow → click the transition → **Post Functions**
3. Click **Edit** on the ScriptRunner post-function
4. Copy the UUID from the URL as `FIELD_FUNCTION_ID=xxxxxxxx-xxxx-...`

**Example RRD filename:**
```
a1b2c3d4-e5f6-7890-abcd-ef1234567890.rrd4j
```

---

### Script Fields

**ID format:** `fieldConfigurationSchemeId` — **NOT** the Jira custom field ID  
**Auto-discovered by:** `01-discover-ids.groovy` ✅

This is the most important gotcha with script fields. SR uses the
**field configuration scheme ID** as the RRD key — not the Jira custom
field ID (e.g. `10900`) that you see in the field's URL or in
`customfield_XXXXX`. Using the wrong ID will show 0 executions for every
script field even when they are running constantly.

The discovery script reads the correct mapping directly from the Jira API
and prints every script field alongside both its custom field ID and its
correct RRD key:

```
Field Name                    RRD Key (use this)    Custom Field ID (do not use)
------------------------------------------------------------------------------
My Calculated Field           11300                 10900
Sprint Health Score           11450                 10950
```

**Example RRD filename:**
```
11300.rrd4j   ← fieldConfigurationSchemeId, not 10900 (the customFieldId)
```

---

### REST Endpoints

**ID format:** `{HTTP_METHOD}-{endpointName}` e.g. `GET-myEndpoint`  
**Auto-discovered by:** `01-discover-ids.groovy` ✅ (if called at least once)

The discovery script scans the RRD directory and lists every REST endpoint
that has ever been called. If you prefer to find one manually:

1. Go to **SR admin → REST Endpoints**
   (`/plugins/servlet/scriptrunner/admin/restendpoints`)
2. Note the endpoint name from the script (the method name in the Groovy
   closure, e.g. `myEndpoint`)
3. Note the HTTP method (GET, POST, etc.)
4. Combine them: `GET-myEndpoint`

**Example RRD filenames:**
```
GET-myEndpoint.rrd4j
POST-createIssue.rrd4j
```

> **Important:** SR only creates the RRD file on the first call to an
> endpoint. If an endpoint has never been called it will not appear in the
> discovery script output — and the usage report will return "RRD file not
> found" for it. That is expected, not a bug.

---

### JQL Functions

**ID format:** the function name itself  
**Auto-discovered by:** `01-discover-ids.groovy` ✅ (if called at least once)

The discovery script lists every JQL function that has an RRD file. If you
prefer to find one manually:

1. Go to **SR admin → JQL Functions**
   (`/plugins/servlet/scriptrunner/admin/jqlfunctions`)
2. The function name shown in the UI is the RRD key

**Example RRD filename:**
```
myJqlFunction.rrd4j
```

> JQL functions only appear if they have been called at least once.
> SR creates the RRD file on first use.

---

### Behaviours & Script Fragments

**Execution tracking: not available.**  
**Auto-discovered by:** `01-discover-ids.groovy` ✅ (inventory only)

SR does not record execution history for Behaviours or Script Fragments.
No RRD file is created for these feature types. The discovery script lists
them for inventory purposes — name and enabled status — but there is no
execution data to report.

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

The usage report shows an **identity section** at the top, followed by the
**metrics table**. The identity section is looked up automatically from the
SCRIPT_ID — you do not need to tell the script what type it is.

**Identity section:**

| Field | Description |
|---|---|
| Feature Type | e.g. Scheduled Job, Script Field, REST Endpoint |
| Name | The script's display name, looked up automatically |
| Details | Owner, HTTP method, workflow name — depends on feature type |
| Script ID | The ID you provided |
| RRD file / Nodes with data | Path to the file (single-node) or nodes that had data (multi-node) |

> **Script Listeners** will show "Script Listener (unconfirmed)" as the
> feature type — SR does not expose listener names via a public API so the
> name cannot be looked up automatically. The execution data is still correct.

**Metrics table:**

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

| Feature Type | RRD Key Format | Auto-discovered? | Where to find it manually |
|---|---|---|---|
| Scheduled Job | UUID | ✅ `01-discover-ids.groovy` | SR admin → Jobs → Edit → URL `?id=` |
| Escalation Service | UUID | ✅ `01-discover-ids.groovy` | SR admin → Jobs → Edit → URL `?id=` |
| Script Listener | UUID | ❌ Manual only | SR admin → Listeners → Edit → URL |
| Workflow Post-Function | UUID | ✅ `01-discover-ids.groovy` | Workflow editor → Post Functions → Edit → URL |
| Script Field | `fieldConfigurationSchemeId` | ✅ `01-discover-ids.groovy` | Not the custom field ID — use discovery script |
| REST Endpoint | `{METHOD}-{name}` | ✅ `01-discover-ids.groovy` | SR admin → REST Endpoints → name + method |
| JQL Function | function name | ✅ `01-discover-ids.groovy` | SR admin → JQL Functions → function name |
| Behaviour | *(not tracked)* | ✅ inventory only | — |
| Script Fragment | *(not tracked)* | ✅ inventory only | — |

---

## Putting it all together

1. Run `01-discover-ids.groovy` to get the correct SCRIPT_ID for the
   script you want to inspect.
2. For Script Listeners, collect the UUID manually from SR admin → Listeners.
3. Paste the SCRIPT_ID into `02-usage-report.groovy` (single-node) or
   `03-usage-report-multi-node.groovy` (cluster) and run it.
4. The report identifies the script automatically and shows its name,
   feature type, and execution metrics.

The PoC is intentionally minimal — it shows you the mechanism. Once you
are comfortable with how the data is read, you can extend it in any
direction: loop over multiple script IDs, cover every feature type, add
a DB fallback, or build a full HTML inventory. The building blocks are
all here.

---

## Multi-node — how it works

On a single-node instance the PoC reads one directory and you are done.
On a multi-node Data Center cluster, SR writes a separate RRD file for
each node under its own subdirectory. `03-usage-report-multi-node.groovy`
handles this automatically — it discovers all node directories, reads the
same script ID from each, and sums the counts.

**What it does differently from the single-node script:**
- Discovers node directories automatically — no hardcoded node name needed
- Skips nodes that have no RRD file for this script (normal — a node only
  has a file if the script has run on that node)
- Shows which nodes had data and which did not
- Adds counts across nodes (a script that ran 10 times on node-0 and 5
  times on node-1 shows 15 total)

**One thing to be aware of:** RRD stores averages per 5-minute window, so
the summed counts are already approximate (hence the `~` prefix). Summing
across nodes compounds that approximation slightly, but the result is a
reliable indicator of relative activity.

If you want to extend the multi-node approach yourself — for example to
loop over multiple script IDs — the core pattern is in
`03-usage-report-multi-node.groovy`. Read it, understand the loop, and
build from there.
