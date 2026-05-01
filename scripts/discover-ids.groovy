// ═══════════════════════════════════════════════════════════════════════
// scripts/01-discover-ids.groovy
// ScriptRunner RRD — ID Discovery Script
// Run from: Script Console  |  Output: HTML
//
// PURPOSE: Show the correct RRD key for every Script Field, Workflow
// Post-Function, and REST Endpoint — the three feature types where the
// ID is not obvious from the SR admin UI.
//
// Run this first. Copy the SCRIPT_ID value you need, then paste it into
// 02-usage-report.groovy or 03-usage-report-multi-node.groovy.
// ═══════════════════════════════════════════════════════════════════════

// ── ⚙ SET YOUR NODE DIRECTORY NAME ──────────────────────────────────────
// Run the node-finder snippet from the Field Guide if you are unsure.
String NODE_ID = "dc-saunders-0"   // ← your node dir name

// ── Imports ──────────────────────────────────────────────────────────────
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome
import com.atlassian.jira.workflow.WorkflowManager
import com.opensymphony.workflow.loader.FunctionDescriptor
import groovy.xml.MarkupBuilder

// ── Shared CSS ────────────────────────────────────────────────────────────
String css = """
  body  { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
          font-size: 13px; color: #172B4D; margin: 16px; }
  h2    { font-size: 15px; color: #0052CC; margin: 24px 0 4px; }
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
  .meta   { font-size: 11px; color: #6B778C; margin-top: 4px; }
  .meta span { margin-right: 16px; }
  .inactive-note { font-size: 11px; color: #97A0AF; margin-top: 4px;
                   font-style: italic; }
  .warn   { background: #FFFAE6; border: 1px solid #FFE380; border-radius: 4px;
             padding: 10px 14px; font-size: 12px; }
  .none   { color: #97A0AF; font-style: italic; font-size: 12px; }
"""

StringWriter sw = new StringWriter()
MarkupBuilder html = new MarkupBuilder(sw)

html.html {
  head { style(css) }
  body {

    // ══════════════════════════════════════════════════════════════════
    // 1. SCRIPT FIELDS
    //    RRD key = fieldConfigurationSchemeId (NOT the Jira custom field
    //    ID). Read directly from the Java API — no DB query needed.
    // ══════════════════════════════════════════════════════════════════
    h2("Script Fields")
    p(class: "sub",
      "Use the SCRIPT_ID value below. " +
      "The Custom Field ID (customfield_XXXXX) is shown for reference only — " +
      "do not use it as the RRD key.")

    List srFields = ComponentAccessor.customFieldManager
        .getCustomFieldObjects()
        .findAll { cf ->
            String typeKey = cf.customFieldType?.key ?: ''
            typeKey.contains('onresolve') || typeKey.contains('scriptrunner')
        }
        .sort { it.name }

    if (srFields) {
        srFields.each { cf ->
            // A field can have multiple config schemes (one per context).
            // Most have just one — we show all to be safe.
            cf.getConfigurationSchemes().each { scheme ->
                div(class: "card") {
                    p(class: "label", "SCRIPT_ID")
                    span(class: "id-box", scheme.id.toString())
                    div(class: "meta") {
                        span { mkp.yield("Field: "); b(cf.name ?: '(unnamed)') }
                        span("Custom Field ID (do not use): ${cf.idAsLong}")
                    }
                }
            }
        }
    } else {
        p(class: "none", "(no ScriptRunner script fields found)")
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. WORKFLOW POST-FUNCTIONS
    //    RRD key = FIELD_FUNCTION_ID stored in the function args.
    //    Walk every workflow and transition to find SR post-functions.
    // ══════════════════════════════════════════════════════════════════
    h2("Workflow Post-Functions")
    p(class: "sub",
      "Each card is one ScriptRunner post-function. " +
      "Inactive workflows are shown greyed out — their post-functions " +
      "will not run and will show no execution data in the PoC.")

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

                String cardClass = active ? "card" : "card inactive"
                String idClass   = active ? "id-box" : "id-box inactive"

                div(class: cardClass) {
                    p(class: "label", "SCRIPT_ID")
                    span(class: idClass, fid)
                    if (!active) {
                        span(class: "badge badge-inactive", "INACTIVE WORKFLOW")
                    } else {
                        span(class: "badge badge-active", "ACTIVE")
                    }
                    div(class: "meta") {
                        span { mkp.yield("Workflow: "); b(wf.name ?: '(unnamed)') }
                        span { mkp.yield("Transition: "); b(action.name ?: '(unnamed)') }
                    }
                    if (!active) {
                        p(class: "inactive-note",
                          "⚠ This workflow is not assigned to any project. " +
                          "The post-function will not execute and the PoC " +
                          "will show no data for this ID.")
                    }
                }
            }
        }
    }
    if (!anyPostFn) p(class: "none", "(no ScriptRunner post-functions found)")

    // ══════════════════════════════════════════════════════════════════
    // 3. REST ENDPOINTS
    //    RRD key = {HTTP_METHOD}-{endpointName}  e.g. GET-myEndpoint
    //    SR only creates the RRD file on the first call, so endpoints
    //    that have never been called will not appear here.
    // ══════════════════════════════════════════════════════════════════
    h2("REST Endpoints")
    p(class: "sub",
      "Only endpoints that have been called at least once appear here. " +
      "SR creates the RRD file on first invocation.")

    JiraHome jiraHome = ComponentAccessor.getComponent(JiraHome)
    File nodeDir = new File(jiraHome.home, "scriptrunner/rrd/${NODE_ID}")

    if (!nodeDir.exists()) {
        div(class: "warn") {
            mkp.yieldUnescaped(
                "⚠ Node directory not found: <code>${nodeDir.absolutePath}</code><br>" +
                "Check the NODE_ID value at the top of this script."
            )
        }
    } else {
        List<String> restKeys = nodeDir.listFiles()
            ?.findAll { f ->
                f.name.matches(/(?i)(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)-.+\.rrd4j/)
            }
            ?.collect { it.name.replace('.rrd4j', '') }
            ?.sort()
            ?: []

        if (restKeys) {
            restKeys.each { String key ->
                // Split METHOD-name for display
                String method = key.split('-')[0].toUpperCase()
                String name   = key.replaceFirst(/(?i)^[A-Z]+-/, '')
                div(class: "card") {
                    p(class: "label", "SCRIPT_ID")
                    span(class: "id-box", key)
                    div(class: "meta") {
                        span { mkp.yield("Method: "); b(method) }
                        span { mkp.yield("Endpoint name: "); b(name) }
                    }
                }
            }
        } else {
            p(class: "none", "(no REST endpoints have been called yet)")
        }
    }

  } // body
} // html

return sw.toString()
