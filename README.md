# ScriptRunner Execution Insights

> A proof of concept showing how to surface ScriptRunner execution data
> programmatically on Jira Data Center.

---

## What is this?

ScriptRunner records execution data for every script it runs — listeners,
scheduled jobs, post-functions, script fields, REST endpoints, JQL functions
— into **RRD (Round Robin Database)** files on disk. These are the same files
that power the **Performance tab** graphs in the SR admin UI, and they retain
up to two years of history.

This repository shows you how to read that data from a Groovy script running
in the ScriptRunner Script Console. The goal is to demonstrate that the data
exists and is accessible. What you build on top of it is up to you.

**This is a starting point, not a finished product.** However, if you wish to see what a full implementation *could* look like, please check  
[`advanced/execution-insights-advanced.groovy`](advanced/execution-insights-advanced.groovy)

---

## Prerequisites

- Jira Data Center with ScriptRunner installed
- Access to the ScriptRunner **Script Console**


---

## Quick Start

### Step 1 — Find your node name

Every Jira Data Center node writes its RRD files to a separate directory.
You need to know your node name before running any script.

Run this one-liner in the Script Console:

```groovy
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome
def home = ComponentAccessor.getComponent(JiraHome).home
new File(home, "scriptrunner/rrd").listFiles()?.each { println it.name }
```

The output will be one or more directory names — for example, [/var/atlassian/application-data/jira/shared-home/scriptrunner/rrd/dc-saunders-0]
Where `dc-saunders-0`. is the `NODE_ID`.

---

### Step 2 — Run the discovery script

Run [`scripts/discover-ids.groovy`](scripts/discover-ids.groovy) in the
Script Console. Set your node name at the top first:

```groovy
String NODE_ID = "dc-saunders-0"   // ← change this to your node name
```

The discovery script produces an HTML report covering every ScriptRunner
feature type on your instance:

| Feature Type | What it shows | Auto-discovered? |
|---|---|---|
| Scheduled Jobs | UUID + name + owner | ✅ Yes |
| Escalation Services | UUID + name + owner | ✅ Yes |
| Script Fields | `fieldConfigurationSchemeId` + field name | ✅ Yes |
| Workflow Post-Functions | UUID + workflow + transition | ✅ Yes |
| REST Endpoints | `METHOD-name` key | ✅ Yes (if called at least once) |
| JQL Functions | Function name | ✅ Yes (if called at least once) |
| Script Fragments | Name + enabled status | ✅ Yes (inventory only — not tracked) |
| Behaviours | Name + enabled status | ✅ Yes (inventory only — not tracked) |
| Script Listeners | Instructions + UUID hints | ⚠ Partial — see below |

Each card in the report shows a **SCRIPT_ID** value and an **RRD ✓** badge
if an RRD file already exists for that script on your node.

#### A note on Script Listeners

ScriptRunner does not expose a public API to list listener UUIDs, so listeners
cannot be fully auto-discovered. We cannot confirm this programmatically. To find out what a UUID belongs to:
  1. Go to SR admin → Listeners
  2. Click Edit next to each listener
  3. Compare the UUID in the browser URL with the UUIDs listed below

---

### Step 3 — Run the usage report

Copy the **SCRIPT_ID** from the discovery report for the script you want to
inspect. Open [`scripts/usage-report.groovy`](scripts/usage-report.groovy)
and set the two values at the top:

```groovy
String SCRIPT_ID = "paste-your-id-here"   // ← from Step 2
String NODE_ID   = "dc-saunders-0"        // ← your node name
```

Run it in the Script Console. It outputs a simple HTML table showing:

| Metric | Description |
|---|---|
| Executions — last 30 days | How many times the script ran in the past 30 days |
| Executions — last 60 days | How many times the script ran in the past 60 days |
| Executions — last 90 days | How many times the script ran in the past 90 days |
| Avg duration | Mean execution time in ms over the 90-day window |
| Last execution | Date of the most recent recorded execution |

---

### Step 4 — Multi-node clusters (optional)

If your Jira instance runs on multiple nodes, use
[`scripts/usage-report-multi-node.groovy`](scripts/usage-report-multi-node.groovy)
instead. It discovers all node directories automatically and sums counts
across every node — no hardcoded node name needed.

---

## Repository Structure

```
scriptrunner-execution-insights/
│
├── README.md                           ← you are here
│
├── scripts/
│   ├── discover-ids.groovy            ← run first: discovers RRD keys
│   │                                     for every SR feature type
│   │
│   ├── usage-report.groovy            ← single-node usage report for
│   │                                     one script ID
│   │
│   └── usage-report-multi-node.groovy ← same report, sums across all
│                                         nodes automatically
│
├── advanced/
│   └── execution-insights-advanced.groovy  ← see below
│
└── docs/
    └── field-guide.md                 ← deep dive: how RRD works, how
                                          to find IDs for every feature
                                          type, output explained
```

---

## How it works

ScriptRunner writes one `.rrd4j` file per script under:

```
$JIRA_HOME/scriptrunner/rrd/{nodeId}/{scriptId}.rrd4j
```

Each file contains two archives:

| Archive | Resolution | Used by |
|---|---|---|
| 5-minute | Immediate, short window | SR admin Performance tab |
| Daily | Consolidated, up to ~2 years | These scripts |

The scripts read the **daily archive** using `ConsolFun.AVERAGE` from the
`org.rrd4j` library, which is bundled with ScriptRunner. RRD timestamps are
in **epoch seconds** — the scripts multiply by 1000 when converting to dates.

The RRD key (filename without `.rrd4j`) varies by feature type:

| Feature Type | RRD Key Format |
|---|---|
| Scheduled Job | UUID |
| Escalation Service | UUID |
| Script Listener | UUID |
| Workflow Post-Function | UUID (`FIELD_FUNCTION_ID`) |
| Script Field | `fieldConfigurationSchemeId` ⚠ not the Jira custom field ID |
| REST Endpoint | `{METHOD}-{name}` e.g. `GET-myEndpoint` |
| JQL Function | the function name itself |

---

## Limitations

**Counts are approximate for high-frequency scripts.**
RRD stores the *average* executions per 5-minute window. The displayed
total is the sum of those averages — reliable as a relative indicator,
not an exact count. High-frequency scripts (script fields, busy listeners)
will show a `~` prefix.

**Counts may lag after a recent execution.**
The daily archive consolidates from the 5-minute archive periodically.
A script that ran minutes ago may still show 0. Use the SR admin
Performance tab for real-time confirmation.

**Listener IDs must be found manually.**
SR does not expose an API to list listener UUIDs. The discovery script
surfaces unattributed UUID RRD files as hints, but cannot confirm which
belong to listeners. Go to SR admin → Listeners, click Edit next to each
listener, and copy the UUID from the browser URL (`?id=...`).

**Behaviours and Script Fragments are not tracked.**
SR does not write RRD files for these feature types. They appear in the
discovery script for inventory purposes only.

**REST Endpoints and JQL Functions only appear after their first call.**
SR creates the RRD file on first invocation. Scripts that have never run
will not appear in the discovery report.

---

## Going further

The scripts in this repo are intentionally minimal. Once you are comfortable
with how the data is read, natural next steps include:

- Loop over multiple script IDs to build a full inventory report
- Add a Confluence page that updates nightly via a scheduled job
- Alert via Slack or email when a script stops running
- Compare execution counts before and after a Jira upgrade

See the [Field Guide](docs/field-guide.md) for a deeper explanation of
every concept used here.

---

## What a full implementation looks like

The simple scripts above cover one script ID at a time. To show how far this
approach can be taken, the `advanced/` folder contains a more complete example
built on the same foundations.

[`advanced/execution-insights-advanced.groovy`](advanced/execution-insights-advanced.groovy)
covers every ScriptRunner feature type in a single run and adds:

- Automatic inventory of all feature types — Scheduled Jobs, Escalation
  Services, Script Listeners, Workflow Post-Functions, Script Fields, REST
  Endpoints, JQL Functions, Script Fragments, and Behaviours
- Fallback data sources (database and in-memory) when no RRD file exists
- Script Field RRD key resolution via the `customfields` property
- Broken trigger detection via live Quartz scheduler data
- Multi-node cluster support
- Orphaned script detection from database records
- A styled HTML report with inline documentation and known limitations

**This advanced script is provided as-is, for reference only.**
It is not maintained, not supported, and not intended to be used in
production. It exists to show what is possible when you build on top of
the primitives demonstrated in the simple scripts — nothing more.

If you want to build something like it, start with the simple scripts
and add what you need.
