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

**This is a starting point, not a finished product.**

---

## Prerequisites

- Jira Data Center with ScriptRunner installed
- Access to the ScriptRunner **Script Console**
  (`/plugins/servlet/scriptrunner/admin/console`)
- No server access required — everything runs inside Jira

---

## Quick Start

### Step 1 — Find your script IDs

Run [`scripts/discover-ids.groovy`](scripts/discover-ids.groovy) in the Script Console.

It prints a visual HTML report showing the correct RRD key for every:
- Script Field
- Workflow Post-Function
- REST Endpoint

> For Scheduled Jobs, Escalation Services, and Listeners the ID is a UUID
> visible in the SR admin URL when you click Edit. See the
> [Field Guide](docs/field-guide.md) for details.

Before running, set your node directory name at the top of the script:

```groovy
String NODE_ID = "dc-saunders-0"   // ← change this to your node name
```

Not sure what your node name is? Run this one-liner in the Script Console:

```groovy
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome
def home = ComponentAccessor.getComponent(JiraHome).home
new File(home, "scriptrunner/rrd").listFiles()?.each { println it.name }
```

---

### Step 2 — Run the usage report

Open [`scripts/usage-report.groovy`](scripts/usage-report.groovy) and
set the two values at the top:

```groovy
String SCRIPT_ID = "paste-your-id-here"   // ← from Step 1
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

### Step 3 — Multi-node clusters (optional)

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
│   ├── discover-ids.groovy            ← run first: find RRD keys for
│   │                                     script fields, post-functions,
│   │                                     and REST endpoints
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
| Script Field | `fieldConfigurationSchemeId` ⚠ not the custom field ID |
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
SR does not expose an API to list listener UUIDs. Go to SR admin →
Listeners, click Edit next to a listener, and copy the UUID from the
browser URL (`?id=...`).

**Behaviours and Script Fragments are not tracked.**
SR does not write RRD files for these feature types.

**REST Endpoints only appear after their first call.**
SR creates the RRD file on first invocation. An endpoint that has never
been called will not appear in the discovery script.

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

[`advanced/execution-insights-advanced.groovy`](https://github.com/SR-Saunders-CS/scriptrunner-execution-insights/blob/main/advanced/execution-insights-advanced.groovy)
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
