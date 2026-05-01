// ═══════════════════════════════════════════════════════════════════════
// scripts/discover-ids.groovy
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
// Defined once here and injected into the <head> of the HTML output below.
// All ten sections share these styles so the page looks consistent.

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


// ── Load STASH_SETTINGS from the database ────────────────────────────────
// ScriptRunner stores configuration for jobs, fragments, and other features
// in the AO_4B00E6_STASH_SETTINGS table. We read it here so the Fragments
// and Behaviours sections can list what is configured. Warnings are
// collected rather than thrown so the rest of the report still renders.

Map<String, List<Map<String, Object>>> stashSettings = [:]
List<String> dataLoadWarnings = []

Connection databaseConnection = null
try {
    DelegatorInterface delegator = ComponentAccessor.getComponent(DelegatorInterface)
    databaseConnection = ConnectionFactory.getConnection(delegator.getGroupHelperName("default"))
    Sql sql = new Sql(databaseConnection)
    sql.eachRow('SELECT "KEY", "SETTING" FROM "AO_4B00E6_STASH_SETTINGS"') { row ->
        String settingKey   = row.getAt('KEY') as String
        String settingValue = row.getAt('SETTING') as String
        if (settingKey && settingValue) {
            try {
                stashSettings[settingKey] = new JsonSlurper().parseText(settingValue) as List<Map<String, Object>>
            } catch (ignored) {}
        }
    }
} catch (ex) {
    dataLoadWarnings << ("Could not load STASH_SETTINGS: " + ex.message)
} finally {
    try { databaseConnection?.close() } catch (ignored) {}
}


// ── Scan the RRD directory for files on this node ────────────────────────
// ScriptRunner creates one .rrd4j file per script the first time it runs.
// We collect the filenames (without the extension) as a Set of "RRD keys"
// so each section below can quickly check whether a given script has data.

JiraHome jiraHome = ComponentAccessor.getComponent(JiraHome)
File nodeDirectory = new File(jiraHome.home, "scriptrunner/rrd/${NODE_ID}")

List<File> rrdFilesOnDisk = nodeDirectory.exists()
    ? (nodeDirectory.listFiles()?.findAll { it.name.endsWith('.rrd4j') } ?: [])
    : []

Set<String> rrdKeysOnDisk = rrdFilesOnDisk
    .collect { it.name.replace('.rrd4j', '') } as Set<String>


// ── Build the HTML report using MarkupBuilder ─────────────────────────────
// MarkupBuilder lets us write HTML as nested Groovy method calls. Each
// method name becomes an HTML tag. The result is written into htmlWriter
// and returned at the end of the script for the Script Console to display.

StringWriter htmlWriter = new StringWriter()
MarkupBuilder markupBuilder = new MarkupBuilder(htmlWriter)

markupBuilder.html {
  head { style(css) }
  body {

    // ── Page header ───────────────────────────────────────────────────────
    h1("ScriptRunner — RRD ID Discovery")
    p(class: "sub",
      "Generated: ${new Date().format('yyyy-MM-dd HH:mm:ss z')} | " +
      "Node: ${NODE_ID} | " +
      "RRD files found: ${rrdFilesOnDisk.size()}")

    // Show a warning if the node directory itself does not exist.
    if (!nodeDirectory.exists()) {
        div(class: "warn") {
            mkp.yieldUnescaped(
                "⚠ Node directory not found: <code>${nodeDirectory.absolutePath}</code><br>" +
                "Check the NODE_ID value at the top of this script.")
        }
    }

    // Show any warnings that were collected while loading the database.
    if (dataLoadWarnings) {
        div(class: "warn") {
            mkp.yieldUnescaped("<strong>⚠ Data load warnings:</strong><br>" +
                dataLoadWarnings.collect { "• ${it}" }.join("<br>"))
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. SCHEDULED JOBS
    // RRD key = the job UUID, read directly from the SR job manager.
    // ════════════════════════════════════════════════════════════════════
    h2("Scheduled Jobs")
    p(class: "sub", "RRD key = the job UUID. Shown directly from the SR job manager.")

    ScheduledScriptJobManager jobManager = null
    try {
        jobManager = ComponentAccessor.getOSGiComponentInstanceOfType(ScheduledScriptJobManager)
    } catch (ignored) {}

    // Load all jobs, excluding escalation services (handled in section 2).
    List<AbstractScheduledJobCommand> scheduledJobs = (jobManager?.load()
        ?.findAll { it.class.simpleName != 'EscalationServiceCommand' }
        ?.sort { it.notes ?: it.id }
        ?: []) as List<AbstractScheduledJobCommand>

    if (scheduledJobs) {
        for (AbstractScheduledJobCommand job : scheduledJobs) {
            boolean hasRrdFile = rrdKeysOnDisk.contains(job.id)
            div(class: "card") {
                p(class: "label", "SCRIPT_ID")
                span(class: "id-box", job.id ?: "—")
                span(class: "badge " + (job.disabled ? "badge-inactive" : "badge-active"),
                     job.disabled ? "DISABLED" : "ACTIVE")
                if (hasRrdFile) span(class: "badge badge-active", "RRD ✓")
                div(class: "meta") {
                    span { mkp.yield("Name: "); b(job.notes ?: job.id ?: "—") }
                    span("Owner: ${job.ownedBy ?: job.userId ?: '—'}")
                }
            }
        }
    } else {
        p(class: "none", "(no scheduled jobs found)")
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. ESCALATION SERVICES
    // RRD key = the escalation service UUID.
    // Escalation services are stored alongside jobs in the job manager
    // but have a different class name, so we filter for them separately.
    // ════════════════════════════════════════════════════════════════════
    h2("Escalation Services")
    p(class: "sub", "RRD key = the escalation service UUID.")

    List<AbstractScheduledJobCommand> escalationServices = (jobManager?.load()
        ?.findAll { it.class.simpleName == 'EscalationServiceCommand' }
        ?.sort { it.notes ?: it.id }
        ?: []) as List<AbstractScheduledJobCommand>

    if (escalationServices) {
        for (AbstractScheduledJobCommand escalation : escalationServices) {
            boolean hasRrdFile = rrdKeysOnDisk.contains(escalation.id)
            div(class: "card") {
                p(class: "label", "SCRIPT_ID")
                span(class: "id-box", escalation.id ?: "—")
                span(class: "badge badge-escalation", "ESCALATION")
                span(class: "badge " + (escalation.disabled ? "badge-inactive" : "badge-active"),
                     escalation.disabled ? "DISABLED" : "ACTIVE")
                if (hasRrdFile) span(class: "badge badge-active", "RRD ✓")
                div(class: "meta") {
                    span { mkp.yield("Name: "); b(escalation.notes ?: escalation.id ?: "—") }
                    span("Owner: ${escalation.ownedBy ?: escalation.userId ?: '—'}")
                }
            }
        }
    } else {
        p(class: "none", "(no escalation services found)")
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. SCRIPT LISTENERS
    // Listeners cannot be auto-discovered — SR does not expose an API
    // to list listener UUIDs. We show instructions instead.
    // ════════════════════════════════════════════════════════════════════
    h2("Script Listeners")
    div(class: "info") {
        mkp.yieldUnescaped("""
<strong>Listeners cannot be auto-discovered.</strong><br>
ScriptRunner does not expose an API to list listener UUIDs. To find a listener's ID:<br>
&nbsp;&nbsp;1. Go to SR admin → Listeners<br>
&nbsp;&nbsp;2. Click <strong>Edit</strong> next to the listener<br>
&nbsp;&nbsp;3. Copy the UUID from the browser URL — it ends with
<code>?id=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx</code><br><br>
Once you have the UUID, use it directly as the <code>SCRIPT_ID</code> in the usage report.
""")
    }

    // Listener section — just the instructions, no UUID hints here.
    // Unattributed UUIDs are shown in a dedicated section at the bottom.

    // ════════════════════════════════════════════════════════════════════
    // 4. SCRIPT FIELDS
    // RRD key = fieldConfigurationSchemeId — NOT the Jira custom field ID.
    // We find all custom fields whose type key belongs to ScriptRunner,
    // then show the scheme ID for each configuration scheme on that field.
    // ════════════════════════════════════════════════════════════════════
    h2("Script Fields")
    p(class: "sub",
      "RRD key = fieldConfigurationSchemeId — NOT the Jira custom field ID. " +
      "The custom field ID is shown for reference only.")

    List scriptRunnerFields = ComponentAccessor.customFieldManager
        .getCustomFieldObjects()
        .findAll { customField ->
            String typeKey = customField.customFieldType?.key ?: ''
            (typeKey.contains('onresolve') || typeKey.contains('scriptrunner')) &&
            !typeKey.contains('jqlFunctions')
        }
        .sort { it.name }

    if (scriptRunnerFields) {
        for (def customField : scriptRunnerFields) {
            for (def fieldConfigScheme : customField.getConfigurationSchemes()) {
                boolean hasRrdFile = rrdKeysOnDisk.contains(fieldConfigScheme.id.toString())
                div(class: "card") {
                    p(class: "label", "SCRIPT_ID")
                    span(class: "id-box", fieldConfigScheme.id.toString())
                    if (hasRrdFile) span(class: "badge badge-active", "RRD ✓")
                    div(class: "meta") {
                        span { mkp.yield("Field name: "); b(customField.name ?: '(unnamed)') }
                        span("Custom Field ID (do not use as key): customfield_${customField.idAsLong}")
                    }
                }
            }
        }
    } else {
        p(class: "none", "(no ScriptRunner script fields found)")
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. WORKFLOW POST-FUNCTIONS
    // RRD key = FIELD_FUNCTION_ID from the post-function configuration.
    // We walk every workflow → every transition → every post-function,
    // keeping only ScriptRunner ones and skipping duplicates.
    // ════════════════════════════════════════════════════════════════════
    h2("Workflow Post-Functions")
    p(class: "sub",
      "RRD key = FIELD_FUNCTION_ID from the post-function configuration. " +
      "Inactive workflows are shown greyed out.")

    // Track IDs we have already rendered to avoid showing duplicates
    // (the same post-function can appear in multiple workflow versions).
    Set<String> processedPostFunctionIds = [] as Set<String>
    WorkflowManager workflowManager = ComponentAccessor.getWorkflowManager()
    boolean foundAnyPostFunction = false

    for (def workflow : workflowManager.getWorkflows().sort { it.name }) {
        boolean isActiveWorkflow = workflow.isActive()

        for (def workflowAction : (workflow.allActions?.sort { it.name } ?: [])) {
            List<FunctionDescriptor> postFunctions = []
            try {
                postFunctions = workflowAction.unconditionalResult?.postFunctions
                    as List<FunctionDescriptor> ?: []
            } catch (ignored) {}

            for (FunctionDescriptor functionDescriptor : postFunctions) {
                String className = functionDescriptor.args['class.name'] as String ?: ''

                // Skip post-functions that do not belong to ScriptRunner.
                if (!className.contains('onresolve') && !className.contains('scriptrunner')) {
                    continue
                }

                String postFunctionId = functionDescriptor.args['FIELD_FUNCTION_ID'] as String

                // Skip entries with no ID, or ones we have already rendered.
                if (!postFunctionId || processedPostFunctionIds.contains(postFunctionId)) {
                    continue
                }

                processedPostFunctionIds << postFunctionId
                foundAnyPostFunction = true
                boolean hasRrdFile = rrdKeysOnDisk.contains(postFunctionId)

                div(class: isActiveWorkflow ? "card" : "card inactive") {
                    p(class: "label", "SCRIPT_ID")
                    span(class: isActiveWorkflow ? "id-box" : "id-box inactive", postFunctionId)
                    span(class: "badge " + (isActiveWorkflow ? "badge-active" : "badge-inactive"),
                         isActiveWorkflow ? "ACTIVE" : "INACTIVE WORKFLOW")
                    if (hasRrdFile) span(class: "badge badge-active", "RRD ✓")
                    div(class: "meta") {
                        span { mkp.yield("Workflow: "); b(workflow.name ?: '(unnamed)') }
                        span { mkp.yield("Transition: "); b(workflowAction.name ?: '(unnamed)') }
                    }
                    if (!isActiveWorkflow) {
                        p(class: "inactive-note",
                          "⚠ Inactive workflow — post-function will not run.")
                    }
                }
            }
        }
    }
    if (!foundAnyPostFunction) p(class: "none", "(no ScriptRunner post-functions found)")

    // ════════════════════════════════════════════════════════════════════
    // 6. REST ENDPOINTS
    // RRD key = {METHOD}-{endpointName} e.g. GET-myEndpoint.
    // SR creates the RRD file on first invocation, so only endpoints
    // that have been called at least once appear here.
    // ════════════════════════════════════════════════════════════════════
    h2("REST Endpoints")
    p(class: "sub",
      "RRD key = {METHOD}-{endpointName} e.g. GET-myEndpoint. " +
      "Only endpoints called at least once appear here — SR creates the RRD file on first invocation.")

    // Identify REST endpoint keys by checking whether the key starts with
    // an HTTP method name followed by a dash.
    List<String> restEndpointKeys = rrdKeysOnDisk.findAll { String rrdKey ->
        rrdKey.matches(/(?i)(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)-.+/)
    }.sort()

    if (restEndpointKeys) {
        for (String restKey : restEndpointKeys) {
            String httpMethod    = restKey.split('-')[0].toUpperCase()
            String endpointName  = restKey.replaceFirst(/(?i)^[A-Z]+-/, '')
            div(class: "card") {
                p(class: "label", "SCRIPT_ID")
                span(class: "id-box", restKey)
                span(class: "badge badge-active", "RRD ✓")
                div(class: "meta") {
                    span { mkp.yield("Method: "); b(httpMethod) }
                    span { mkp.yield("Endpoint name: "); b(endpointName) }
                }
            }
        }
    } else {
        p(class: "none", "(no REST endpoints have been called yet)")
    }

    // ════════════════════════════════════════════════════════════════════
    // 7. JQL FUNCTIONS
    // RRD key = the function name itself.
    // We identify JQL function keys by elimination: anything that is not
    // a UUID, not a plain number, and not an HTTP-method-prefixed key
    // is assumed to be a JQL function name.
    // ════════════════════════════════════════════════════════════════════
    h2("JQL Functions")
    p(class: "sub",
      "RRD key = the function name. Only functions called at least once appear here.")

    List<String> jqlFunctionKeys = rrdKeysOnDisk.findAll { String rrdKey ->
        !rrdKey.matches(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/) &&
        !rrdKey.matches(/\d+/) &&
        !rrdKey.matches(/(?i)(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)-.+/)
    }.sort()

    if (jqlFunctionKeys) {
        for (String jqlKey : jqlFunctionKeys) {
            div(class: "card") {
                p(class: "label", "SCRIPT_ID")
                span(class: "id-box", jqlKey)
                span(class: "badge badge-active", "RRD ✓")
                div(class: "meta") {
                    span("Use in JQL: issueFunction in ${jqlKey}(...)")
                }
            }
        }
    } else {
        p(class: "none", "(no JQL functions have been called yet)")
    }

    // ════════════════════════════════════════════════════════════════════
    // 8. SCRIPT FRAGMENTS
    // SR does not write RRD files for fragments, so there is no execution
    // data to report. We list them here for inventory purposes only,
    // reading their configuration from the stash settings loaded earlier.
    // ════════════════════════════════════════════════════════════════════
    h2("Script Fragments")
    div(class: "na") {
        mkp.yieldUnescaped("""
<strong>Script Fragments are not tracked by ScriptRunner.</strong><br>
SR does not write RRD files for fragments — there is no execution data to report.
Fragments are listed here for inventory purposes only.
""")
    }

    List<Map<String, Object>> fragmentConfigs = stashSettings['ui_fragments'] ?: stashSettings['fragments'] ?: []
    if (fragmentConfigs) {
        for (Map<String, Object> fragmentConfig : fragmentConfigs.sort { it['FIELD_NOTES'] ?: it['name'] ?: '' }) {
            div(class: "card") {
                div(class: "meta") {
                    span { mkp.yield("Name: ")
                        b((fragmentConfig['FIELD_NOTES'] ?: fragmentConfig['name'] ?: fragmentConfig['id'] ?: '—') as String) }
                    span("Enabled: ${fragmentConfig.disabled ? 'false' : 'true'}")
                    span("Owner: ${fragmentConfig.ownedBy ?: '—'}")
                }
            }
        }
    } else {
        p(class: "none", "(no script fragments found)")
    }

    // ════════════════════════════════════════════════════════════════════
    // 9. BEHAVIOURS
    // SR does not write RRD files for behaviours, so there is no execution
    // data to report. We list them here for inventory purposes only.
    // The BehaviourManager is accessed via OSGi and may not be available
    // on all instances, so the whole section is wrapped in a try/catch.
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
        com.onresolve.jira.behaviours.BehaviourManager behaviourManager =
            ComponentAccessor.getOSGiComponentInstanceOfType(
                com.onresolve.jira.behaviours.BehaviourManager)

        Map<String, com.onresolve.jira.behaviours.types.BehaviourEditState> allBehaviours =
            behaviourManager?.getAllConfigs()

        if (allBehaviours) {
            for (def entry : allBehaviours.entrySet()) {
                String behaviourId = entry.key
                com.onresolve.jira.behaviours.types.BehaviourEditState behaviourState = entry.value
                div(class: "card") {
                    div(class: "meta") {
                        span { mkp.yield("Name: "); b(behaviourState?.name ?: behaviourId) }
                        span("Enabled: ${behaviourState?.disabled ? 'false' : 'true'}")
                    }
                }
            }
        } else {
            p(class: "none", "(no behaviours found)")
        }
    } catch (ignored) {
        p(class: "none", "(behaviours unavailable)")
    }

    // ════════════════════════════════════════════════════════════════════
    // 10. UNATTRIBUTED UUID RRD FILES
    // These UUIDs have RRD data on disk but could not be matched to any
    // known job, escalation service, or post-function. They may be
    // listeners — but we cannot confirm this programmatically.
    // ════════════════════════════════════════════════════════════════════
    h2("Unattributed RRD Files — Possible Listeners")

    // Build the full set of UUIDs we have already explained in sections
    // 1, 2, and 5 so we can exclude them from this "unknown" list.
    Set<String> knownJobAndEscalationIds = (scheduledJobs + escalationServices)
        .collect { AbstractScheduledJobCommand job -> job.id } as Set<String>
    Set<String> accountedForUuids = (knownJobAndEscalationIds + processedPostFunctionIds) as Set<String>

    // Any UUID-shaped key that is not accounted for is listed here.
    List<String> unattributedUuids = rrdKeysOnDisk.findAll { String rrdKey ->
        rrdKey.matches(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/) &&
        !accountedForUuids.contains(rrdKey)
    }.sort()

    div(class: "warn") {
        mkp.yieldUnescaped("""
<strong>What is this section?</strong><br>
The RRD files listed below exist on disk but could not be matched to any Scheduled Job,
Escalation Service, or Workflow Post-Function found on this instance.<br><br>
They <em>may</em> be Script Listeners — SR records listener executions using the listener
UUID as the RRD filename, and listener UUIDs are not accessible via any public API.<br><br>
<strong>We cannot confirm this programmatically.</strong> To find out what a UUID belongs to:<br>
&nbsp;&nbsp;1. Go to SR admin → Listeners<br>
&nbsp;&nbsp;2. Click <strong>Edit</strong> next to each listener<br>
&nbsp;&nbsp;3. Compare the UUID in the browser URL with the UUIDs listed below<br><br>
If you find a match, that UUID is your listener's <code>SCRIPT_ID</code> for the usage report.
If none match, the UUID may belong to a deleted script whose RRD file was never cleaned up.
""")
    }

    if (unattributedUuids) {
        // Sort by last-modified date so most recently active appear first.
        List<File> unattributedRrdFiles = rrdFilesOnDisk
            .findAll { File rrdFile -> unattributedUuids.contains(rrdFile.name.replace('.rrd4j', '')) }
            .sort { File a, File b -> b.lastModified() <=> a.lastModified() }

        for (File rrdFile : unattributedRrdFiles) {
            String fileUuid        = rrdFile.name.replace('.rrd4j', '')
            String lastModifiedDate = new Date(rrdFile.lastModified()).format('yyyy-MM-dd HH:mm z')
            div(class: "card") {
                p(class: "label", "UUID — verify in SR admin → Listeners")
                span(class: "id-box", fileUuid)
                div(class: "meta") {
                    span("RRD file last modified: ${lastModifiedDate}")
                    span("Cross-reference with SR admin → Listeners to confirm identity")
                }
            }
        }
    } else {
        p(class: "none",
          "(no unattributed UUID RRD files — all UUIDs are accounted for)")
    }

    // ── Footer ────────────────────────────────────────────────────────────
    hr()
    p(class: "sub",
      "RRD ✓ = an RRD file exists for this script on node ${NODE_ID}. " +
      "Scripts without RRD ✓ have either never run or have not yet had their " +
      "first execution recorded to disk.")

  } // body
} // html

return htmlWriter.toString()
