// ═══════════════════════════════════════════════════════════════════════
// scripts/01-discover-ids.groovy
// ScriptRunner RRD — ID Discovery Script
// Run from: Script Console  |  Output: HTML
//
// PURPOSE: Show the correct RRD key for every ScriptRunner feature type.
// Run this first. Copy the SCRIPT_ID you need, then paste it into
// 02-usage-report.groovy or 03-usage-report-multi-node.groovy.
// ═══════════════════════════════════════════════════════════════════════

// ── ⚙ SET YOUR NODE DIRECTORY NAME ──────────────────────────────────────
// Not sure what your node name is? Run this in the Script Console:
//   import com.atlassian.jira.component.ComponentAccessor
//   import com.atlassian.jira.config.util.JiraHome
//   def home = ComponentAccessor.getComponent(JiraHome).home
//   new File(home, "scriptrunner/rrd").listFiles()?.each { println it.name }
String NODE_ID = "dc-saunders-0"   // ← your node dir name

// ── Imports ──────────────────────────────────────────────────────────────
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome
import com.atlassian.jira.workflow.WorkflowManager
import com.onresolve.scriptrunner.scheduled.ScheduledScriptJobManager
import com.onresolve.scriptrunner.scheduled.model.AbstractScheduledJobCommand
import com.opensymphony.workflow.loader.FunctionDescriptor
import groovy.json.JsonSlurper
import groovy.sql.Sql
import groovy.xml.MarkupBuilder
import java.sql.Connection
import org.ofbiz.core.entity.ConnectionFactory
import org.ofbiz.core.entity.DelegatorInterface

// ── Shared CSS ────────────────────────────────────────────────────────────
String css = """
  body  { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
          font-size: 13px; color: #172B4D; margin: 16px; }
  h1    { font-size: 18px; color: #0052CC; margin: 0 0 4px; }
  h2    { font-size: 15px; color: #0052CC; margin: 28px 0 4px; }
  p.sub { font-size: 11px; color: #6B778C; margin: 0 0 10px; }
  .card         { border: 1px solid #DFE1E6; border-radius: 4px;
                  padding: 10px 14px; margin-bottom: 8px; background: #fff; }
  .card:hover   { background: #F4F5F7; }
  .card.inactive { border-color: #C1C7D0; background: #F4F5F7; opacity: 0.85; }
  .label  { font-size: 11px; color: #6B778C; margin-bottom: 2px; }
  .id-box { font-family: monospace; font-size: 14px; font-weight: bold;
             color: #0052CC; background: #DEEBFF;
             padding: 4px 10px; border-radius: 3px;
             display: inline-block; margin: 2px 0 6px; letter-spacing: 0.03em; }
  .id-box.inactive { color: #6B778C; background: #EBECF0; }
  .badge  { display: inline-block; font-size: 10px; font-weight: bold;
             padding: 2px 7px; border-radius: 3px; margin-left: 8px;
             vertical-align: middle; }
  .badge-inactive { background: #DFE1E6; color: #6B778C; }
  .badge-active   { background: #E3FCEF; color: #006644; }
  .badge-escalation { background: #EAE6FF; color: #403294; }
  .meta   { font-size: 11px; color: #6B778C; margin-top: 4px; }
  .meta span { margin-right: 16px; }
  .inactive-note { font-size: 11px; color: #97A0AF; margin-top: 4px;
                   font-style: italic; }
  .warn   { background: #FFFAE6; border: 1px solid #FFE380; border-radius: 4px;
             padding: 10px 14px; font-size: 12px; margin-bottom: 8px; }
  .info   { background: #DEEBFF; border: 1px solid #4C9AFF; border-radius: 4px;
             padding: 10px 14px; font-size: 12px; margin-bottom: 8px; }
  .none   { color: #97A0AF; font-style: italic; font-size: 12px; }
  .na     { background: #F4F5F7; border: 1px solid #DFE1E6; border-radius: 4px;
             padding: 10px 14px; font-size: 12px; color: #6B778C; margin-bottom: 8px; }
  hr      { border: none; border-top: 1px solid #DFE1E6; margin: 24px 0 0; }
"""

// ── Load STASH_SETTINGS (jobs + fragments) ────────────────────────────────
Map<String, List<Map<String, Object>>> stash = [:]
List<String> dbWarnings = []

Connection conn = null
try {
    DelegatorInterface del = ComponentAccessor.getComponent(DelegatorInterface)
    conn = ConnectionFactory.getConnection(del.getGroupHelperName("default"))
    Sql sql = new Sql(conn)
    sql.eachRow('SELECT "KEY", "SETTING" FROM "AO_4B00E6_STASH_SETTINGS"') { r ->
        String k = r.getAt('KEY') as String
        String v = r.getAt('SETTING') as String
        if (k && v) {
            try { stash[k] = new JsonSlurper().parseText(v) as List<Map<String, Object>> }
            catch (ignored) {}
        }
    }
} catch (ex) {
    dbWarnings << ("Could not load STASH_SETTINGS: " + ex.message)
} finally {
    try { conn?.close() } catch (ignored) {}
}

// ── RRD file scan ─────────────────────────────────────────────────────────
JiraHome jiraHome = ComponentAccessor.getComponent(JiraHome)
File nodeDir = new File(jiraHome.home, "scriptrunner/rrd/${NODE_ID}")
List<File> rrdFiles = nodeDir.exists()
    ? (nodeDir.listFiles()?.findAll { it.name.endsWith('.rrd4j') } ?: [])
    : []
Set<String> rrdKeys = rrdFiles.collect { it.name.replace('.rrd4j', '') } as Set<String>

// ── Build HTML ────────────────────────────────────────────────────────────
StringWriter sw = new StringWriter()
MarkupBuilder html = new MarkupBuilder(sw)

html.html {
  head { style(css) }
  body {

    h1("ScriptRunner — RRD ID Discovery")
    p(class: "sub",
      "Generated: ${new Date().format('yyyy-MM-dd HH:mm:ss z')} | " +
      "Node: ${NODE_ID} | " +
      "RRD files found: ${rrdFiles.size()}")

    if (!nodeDir.exists()) {
        div(class: "warn") {
            mkp.yieldUnescaped(
                "⚠ Node directory not found: <code>${nodeDir.absolutePath}</code><br>" +
                "Check the NODE_ID value at the top of this script.")
        }
    }

    if (dbWarnings) {
        div(class: "warn") {
            mkp.yieldUnescaped("<strong>⚠ Data load warnings:</strong><br>" +
                dbWarnings.collect { "• ${it}" }.join("<br>"))
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. SCHEDULED JOBS
    // ════════════════════════════════════════════════════════════════════
    h2("Scheduled Jobs")
    p(class: "sub", "RRD key = the job UUID. Shown directly from the SR job manager.")

    ScheduledScriptJobManager jobMgr = null
    try {
        jobMgr = ComponentAccessor.getOSGiComponentInstanceOfType(ScheduledScriptJobManager)
    } catch (ignored) {}

    List<AbstractScheduledJobCommand> jobs = (jobMgr?.load()
        ?.findAll { it.class.simpleName != 'EscalationServiceCommand' }
        ?.sort { it.notes ?: it.id }
        ?: []) as List<AbstractScheduledJobCommand>

    if (jobs) {
        jobs.each { AbstractScheduledJobCommand j ->
            boolean hasRrd = rrdKeys.contains(j.id)
            div(class: "card") {
                p(class: "label", "SCRIPT_ID")
                span(class: "id-box", j.id ?: "—")
                span(class: "badge " + (j.disabled ? "badge-inactive" : "badge-active"),
                     j.disabled ? "DISABLED" : "ACTIVE")
                if (hasRrd) span(class: "badge badge-active", "RRD ✓")
                div(class: "meta") {
                    span { mkp.yield("Name: "); b(j.notes ?: j.id ?: "—") }
                    span("Owner: ${j.ownedBy ?: j.userId ?: '—'}")
                }
            }
        }
    } else {
        p(class: "none", "(no scheduled jobs found)")
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. ESCALATION SERVICES
    // ════════════════════════════════════════════════════════════════════
    h2("Escalation Services")
    p(class: "sub", "RRD key = the escalation service UUID.")

    List<AbstractScheduledJobCommand> escalations = (jobMgr?.load()
        ?.findAll { it.class.simpleName == 'EscalationServiceCommand' }
        ?.sort { it.notes ?: it.id }
        ?: []) as List<AbstractScheduledJobCommand>

    if (escalations) {
        escalations.each { AbstractScheduledJobCommand j ->
            boolean hasRrd = rrdKeys.contains(j.id)
            div(class: "card") {
                p(class: "label", "SCRIPT_ID")
                span(class: "id-box", j.id ?: "—")
                span(class: "badge badge-escalation", "ESCALATION")
                span(class: "badge " + (j.disabled ? "badge-inactive" : "badge-active"),
                     j.disabled ? "DISABLED" : "ACTIVE")
                if (hasRrd) span(class: "badge badge-active", "RRD ✓")
                div(class: "meta") {
                    span { mkp.yield("Name: "); b(j.notes ?: j.id ?: "—") }
                    span("Owner: ${j.ownedBy ?: j.userId ?: '—'}")
                }
            }
        }
    } else {
        p(class: "none", "(no escalation services found)")
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. SCRIPT LISTENERS
    // ════════════════════════════════════════════════════════════════════
    h2("Script Listeners")
    div(class: "info") {
        mkp.yieldUnescaped("""
<strong>Script Listeners cannot be auto-discovered — manual action required.</strong><br><br>
ScriptRunner does not expose a public API to list listener UUIDs, so this script
cannot show them automatically. You need to register each listener manually.<br><br>
<strong>How to find a listener UUID — 3 steps:</strong><br>
&nbsp;&nbsp;1. Go to
<a href="/plugins/servlet/scriptrunner/admin/listeners" target="_blank">SR admin → Listeners</a><br>
&nbsp;&nbsp;2. Click <strong>Edit</strong> next to the listener<br>
&nbsp;&nbsp;3. Copy the UUID from the browser URL — it ends with
<code>?id=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx</code><br><br>
<strong>What to do with the UUID:</strong><br>
Open <code>usage-report.groovy</code> and set <code>SCRIPT_ID</code> to the UUID.
That is all — the full execution history for that listener will appear immediately.
The data was always recorded by SR; you are just telling the script where to look.<br><br>
Repeat once per listener. This takes about 30 seconds each.
""")
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. SCRIPT FIELDS
    // ════════════════════════════════════════════════════════════════════
    h2("Script Fields")
    p(class: "sub",
      "RRD key = fieldConfigurationSchemeId — NOT the Jira custom field ID. " +
      "The custom field ID is shown for reference only.")

    List srFields = ComponentAccessor.customFieldManager
        .getCustomFieldObjects()
        .findAll { cf ->
            String typeKey = cf.customFieldType?.key ?: ''
            (typeKey.contains('onresolve') || typeKey.contains('scriptrunner')) &&
            !typeKey.contains('jqlFunctions')
        }
        .sort { it.name }

    if (srFields) {
        srFields.each { cf ->
            cf.getConfigurationSchemes().each { scheme ->
                boolean hasRrd = rrdKeys.contains(scheme.id.toString())
                div(class: "card") {
                    p(class: "label", "SCRIPT_ID")
                    span(class: "id-box", scheme.id.toString())
                    if (hasRrd) span(class: "badge badge-active", "RRD ✓")
                    div(class: "meta") {
                        span { mkp.yield("Field name: "); b(cf.name ?: '(unnamed)') }
                        span("Custom Field ID (do not use as key): customfield_${cf.idAsLong}")
                    }
                }
            }
        }
    } else {
        p(class: "none", "(no ScriptRunner script fields found)")
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. WORKFLOW POST-FUNCTIONS
    // ════════════════════════════════════════════════════════════════════
    h2("Workflow Post-Functions")
    p(class: "sub",
      "RRD key = FIELD_FUNCTION_ID from the post-function configuration. " +
      "Inactive workflows are shown greyed out.")

    Set<String> seenIds = [] as Set<String>
    WorkflowManager wfm = ComponentAccessor.getWorkflowManager()
    boolean anyPostFn = false

    wfm.getWorkflows().sort { it.name }.each { wf ->
        boolean active = wf.isActive()
        wf.allActions?.sort { it.name }?.each { action ->
            List<FunctionDescriptor> fns = []
            try {
                fns = action.unconditionalResult?.postFunctions
                    as List<FunctionDescriptor> ?: []
            } catch (ignored) {}

            fns.each { FunctionDescriptor fd ->
                String cls = fd.args['class.name'] as String ?: ''
                if (!cls.contains('onresolve') && !cls.contains('scriptrunner')) return
                String fid = fd.args['FIELD_FUNCTION_ID'] as String
                if (!fid || seenIds.contains(fid)) return
                seenIds << fid
                anyPostFn = true
                boolean hasRrd = rrdKeys.contains(fid)

                div(class: active ? "card" : "card inactive") {
                    p(class: "label", "SCRIPT_ID")
                    span(class: active ? "id-box" : "id-box inactive", fid)
                    span(class: "badge " + (active ? "badge-active" : "badge-inactive"),
                         active ? "ACTIVE" : "INACTIVE WORKFLOW")
                    if (hasRrd) span(class: "badge badge-active", "RRD ✓")
                    div(class: "meta") {
                        span { mkp.yield("Workflow: "); b(wf.name ?: '(unnamed)') }
                        span { mkp.yield("Transition: "); b(action.name ?: '(unnamed)') }
                    }
                    if (!active) {
                        p(class: "inactive-note",
                          "⚠ Inactive workflow — post-function will not run.")
                    }
                }
            }
        }
    }
    if (!anyPostFn) p(class: "none", "(no ScriptRunner post-functions found)")

    // ════════════════════════════════════════════════════════════════════
    // 6. REST ENDPOINTS
    // ════════════════════════════════════════════════════════════════════
    h2("REST Endpoints")
    p(class: "sub",
      "RRD key = {METHOD}-{endpointName} e.g. GET-myEndpoint. " +
      "Only endpoints called at least once appear here — SR creates the RRD file on first invocation.")

    List<String> restKeys = rrdKeys.findAll { String k ->
        k.matches(/(?i)(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)-.+/)
    }.sort()

    if (restKeys) {
        restKeys.each { String key ->
            String method = key.split('-')[0].toUpperCase()
            String name   = key.replaceFirst(/(?i)^[A-Z]+-/, '')
            div(class: "card") {
                p(class: "label", "SCRIPT_ID")
                span(class: "id-box", key)
                span(class: "badge badge-active", "RRD ✓")
                div(class: "meta") {
                    span { mkp.yield("Method: "); b(method) }
                    span { mkp.yield("Endpoint name: "); b(name) }
                }
            }
        }
    } else {
        p(class: "none", "(no REST endpoints have been called yet)")
    }

    // ════════════════════════════════════════════════════════════════════
    // 7. JQL FUNCTIONS
    // ════════════════════════════════════════════════════════════════════
    h2("JQL Functions")
    p(class: "sub",
      "RRD key = the function name. Only functions called at least once appear here.")

    List<String> jqlKeys = rrdKeys.findAll { String k ->
        !k.matches(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/) &&
        !k.matches(/\d+/) &&
        !k.matches(/(?i)(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)-.+/)
    }.sort()

    if (jqlKeys) {
        jqlKeys.each { String key ->
            div(class: "card") {
                p(class: "label", "SCRIPT_ID")
                span(class: "id-box", key)
                span(class: "badge badge-active", "RRD ✓")
                div(class: "meta") {
                    span("Use in JQL: issueFunction in ${key}(...)")
                }
            }
        }
    } else {
        p(class: "none", "(no JQL functions have been called yet)")
    }

    // ════════════════════════════════════════════════════════════════════
    // 8. SCRIPT FRAGMENTS
    // ════════════════════════════════════════════════════════════════════
    h2("Script Fragments")
    div(class: "na") {
        mkp.yieldUnescaped("""
<strong>Script Fragments are not tracked by ScriptRunner.</strong><br>
SR does not write RRD files for fragments — there is no execution data to report.
Fragments are listed here for inventory purposes only.
""")
    }

    List<Map<String, Object>> fragments = stash['ui_fragments'] ?: stash['fragments'] ?: []
    if (fragments) {
        fragments.sort { it['FIELD_NOTES'] ?: it['name'] ?: '' }.each { Map cfg ->
            div(class: "card") {
                div(class: "meta") {
                    span { mkp.yield("Name: ")
                        b((cfg['FIELD_NOTES'] ?: cfg['name'] ?: cfg['id'] ?: '—') as String) }
                    span("Enabled: ${cfg.disabled ? 'false' : 'true'}")
                    span("Owner: ${cfg.ownedBy ?: '—'}")
                }
            }
        }
    } else {
        p(class: "none", "(no script fragments found)")
    }

    // ════════════════════════════════════════════════════════════════════
    // 9. BEHAVIOURS
    // ════════════════════════════════════════════════════════════════════
    h2("Behaviours")
    div(class: "na") {
        mkp.yieldUnescaped("""
<strong>Behaviours are not tracked by ScriptRunner.</strong><br>
SR does not write RRD files for behaviours — there is no execution data to report.
Behaviours are listed here for inventory purposes only.
""")
    }

    try {
        com.onresolve.jira.behaviours.BehaviourManager behaviourMgr =
            ComponentAccessor.getOSGiComponentInstanceOfType(
                com.onresolve.jira.behaviours.BehaviourManager)
        Map<String, com.onresolve.jira.behaviours.types.BehaviourEditState> configs =
            behaviourMgr?.getAllConfigs()
        if (configs) {
            configs.each { String id, com.onresolve.jira.behaviours.types.BehaviourEditState state ->
                div(class: "card") {
                    div(class: "meta") {
                        span { mkp.yield("Name: "); b(state?.name ?: id) }
                        span("Enabled: ${state?.disabled ? 'false' : 'true'}")
                    }
                }
            }
        } else {
            p(class: "none", "(no behaviours found)")
        }
    } catch (ignored) {
        p(class: "none", "(behaviours unavailable)")
    }



    hr()
    p(class: "sub",
      "RRD ✓ = an RRD file exists for this script on node ${NODE_ID}. " +
      "Scripts without RRD ✓ have either never run or have not yet had their " +
      "first execution recorded to disk.")

  } // body
} // html

return sw.toString()
