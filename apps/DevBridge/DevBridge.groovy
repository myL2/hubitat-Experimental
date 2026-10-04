/**
 * =============================  myL2 Dev Bridge ===================================
 *
 *  DESCRIPTION:
 *  Development bridge for writing and testing Hubitat apps and drivers from an AI
 *  assistant or scripts. Exposes, on the hub's local OAuth API:
 *    - /mcp        MCP server (Streamable HTTP, JSON responses, stateless)
 *    - /code/...   plain REST routes for pushing/pulling raw Groovy source
 *    - /logs, /device/..., /app/...  REST mirrors of the test tools
 *
 *  Talks to the hub's own web-UI endpoints over loopback (127.0.0.1:8080), which the
 *  hub exempts from Hub Security, so no hub credentials are needed.
 *
 *  Code writes (create/update/delete) are limited to the namespaces listed in the app
 *  preferences; both the existing code and the new source must be in an allowed namespace.
 *  The previous source is saved to File Manager (devbridge-backup-<type>-<id>.groovy)
 *  before every update/delete.
 *
 *  TO INSTALL:
 *  1. Apps Code > New App > paste this > Save > "OAuth" button > Enable OAuth > Update.
 *  2. Apps > Add User App > myL2 Dev Bridge > Done. The app page shows the URLs and token.
 *
 *  SELF-UPDATE AND MONITORING:
 *  An app cannot save its own code safely (the hub reloads it mid-request), so updates to
 *  this Bridge go through its child app "myL2 Dev Bridge Guardian" (DevBridgeGuardian.groovy):
 *  self_update hands the source over, the Guardian saves it from its own job, health-checks
 *  the Bridge and rolls back on failure. The Guardian also monitors the Bridge and a watch
 *  list of apps/devices (errors, warnings, runtime stats). Install the Guardian code in Apps
 *  Code first; the Bridge creates the child instance itself.
 *
 * =======================================================================================
 *
 *  Changelog:
 *
 *  v1.1.5 (2026-10-02) - Hub 4xx (e.g. deleted device id) answered as not found, without an error log entry
 *  v1.1.4 (2026-10-02) - read_file / write_file tools for the hub File Manager (e.g. custom driver profiles)
 *  v1.1.3 (2026-09-30) - Namespace/name read only from the definition() call, resolving constants (e.g. namespace: sNamespace)
 *  v1.1.2 (2026-09-30) - get_logs folds multi-line messages (e.g. exception details) into their entry
 *  v1.1.1 (2026-09-30) - First release deployed through self_update
 *  v1.1.0 (2026-09-30) - self_update via Guardian child app with health check + rollback; monitor_report and watch tools
 *  v1.0.2 (2026-09-30) - run_command refuses undeclared commands unless allowUndeclared=true
 *  v1.0.1 (2026-09-30) - get_jobs scoped to one device/app with its runtime stats; clear "not found" for unknown code ids
 *  v1.0.0 (2026-09-30) - First release
 *
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String APP_VERSION = "1.1.5"
@Field static final String HUB = "http://127.0.0.1:8080"
@Field static final List SUPPORTED_PROTOCOLS = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"]

@Field static final String GUARDIAN_NAME = "myL2 Dev Bridge Guardian"
@Field static final String SELF_UPDATE_FILE = "devbridge-selfupdate.groovy"
@Field static final String SELF_BACKUP_FILE = "devbridge-backup-self.groovy"

@Field static final Map CODE_PATHS = [
    app    : [list: "/hub2/userAppTypes",    read: "/app/ajax/code",    save: "/app/saveOrUpdateJson",     delete: "/app/edit/deleteJsonSafe/"],
    driver : [list: "/hub2/userDeviceTypes", read: "/driver/ajax/code", save: "/driver/saveOrUpdateJson",  delete: "/driver/editor/deleteJson/"],
    library: [list: "/hub2/userLibraries",   read: "/library/list/single/data/", save: "/library/saveOrUpdateJson", delete: "/library/edit/deleteJson/"]
]

definition(
    name: "myL2 Dev Bridge",
    namespace: "myL2",
    author: "myL2",
    description: "MCP + REST bridge for developing and testing Hubitat apps and drivers",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    oauth: true,
    singleInstance: true
)

preferences {
    page(name: "mainPage", install: true, uninstall: true) {
        section("Endpoints") {
            if (!state.accessToken) {
                paragraph "Enable OAuth for this app in Apps Code (OAuth button), then click Done and reopen this page."
            } else {
                paragraph "<b>MCP (Streamable HTTP):</b><br><code>${getFullLocalApiServerUrl()}/mcp</code>"
                paragraph "<b>REST base:</b><br><code>${getFullLocalApiServerUrl()}</code>"
                paragraph "<b>Header:</b> <code>Authorization: Bearer ${state.accessToken}</code><br>(or append <code>?access_token=${state.accessToken}</code>)"
                paragraph "<b>Claude Code:</b><br><code>claude mcp add --transport http hubitat-dev ${getFullLocalApiServerUrl()}/mcp --header \"Authorization: Bearer ${state.accessToken}\"</code>"
                paragraph "<b>Push a driver from disk:</b><br><code>curl -X PUT --data-binary @MyDriver.groovy -H 'Content-Type: text/plain' -H 'Authorization: Bearer &lt;token&gt;' ${getFullLocalApiServerUrl()}/code/driver/&lt;id&gt;</code>"
            }
        }
        section("Guardian (self-update and monitoring)") {
            app(name: "guardian", appName: GUARDIAN_NAME, namespace: "myL2", title: "Dev Bridge Guardian", multiple: false)
        }
        section("Safety") {
            input "allowedNamespaces", "text", title: "Namespaces allowed for code writes (comma-separated)", defaultValue: "myL2", required: true
            input "allowDeviceCommands", "bool", title: "Allow running device commands", defaultValue: true
        }
        section("Logging") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
        }
        section {
            paragraph "<small>v${APP_VERSION}</small>"
        }
    }
}

mappings {
    path("/mcp")                  { action: [POST: "handleMcp", GET: "handleMcpGet"] }
    path("/code/:type")           { action: [GET: "restListCode", POST: "restCreateCode"] }
    path("/code/:type/:id")       { action: [GET: "restGetCode", PUT: "restUpdateCode", POST: "restUpdateCode", DELETE: "restDeleteCode"] }
    path("/device/:id")           { action: [GET: "restGetDevice"] }
    path("/device/:id/events")    { action: [GET: "restGetDeviceEvents"] }
    path("/device/:id/run/:cmd")  { action: [POST: "restRunCommand"] }
    path("/app/:id")              { action: [GET: "restGetAppStatus"] }
    path("/logs")                 { action: [GET: "restGetLogs"] }
    path("/jobs")                 { action: [GET: "restGetJobs"] }
    path("/self")                 { action: [GET: "restSelfStatus", PUT: "restSelfUpdate", POST: "restSelfUpdate"] }
    path("/monitor")              { action: [GET: "restMonitor"] }
    path("/file/:name")           { action: [GET: "restReadFile", PUT: "restWriteFile", POST: "restWriteFile"] }
}

def installed() { initialize() }
def updated()   { initialize() }

def initialize() {
    if (!state.accessToken) {
        try {
            createAccessToken()
        } catch (Exception e) {
            log.warn "Dev Bridge: could not create access token -- enable OAuth for this app in Apps Code (${e.message})"
        }
    }
    log.info "Dev Bridge v${APP_VERSION} ready${state.accessToken ? ' at ' + getFullLocalApiServerUrl() : ' (OAuth not enabled)'}"
}

private logDebug(msg) { if (settings.logEnable) log.debug "Dev Bridge: ${msg}" }

// ======================================================================================
//  MCP
// ======================================================================================

def handleMcpGet() {
    // No server-initiated stream; spec allows 405 here.
    render(status: 405, contentType: "application/json", data: JsonOutput.toJson([error: "SSE stream not supported; use POST"]))
}

def handleMcp() {
    def req
    try {
        req = request.JSON
    } catch (Exception e) {
        req = null
    }
    if (!(req instanceof Map)) {
        return renderJson(rpcError(null, -32700, "Parse error: expected a single JSON-RPC object"))
    }
    // Notifications and client responses carry no id and get 202 with no body.
    if (req.id == null) {
        return render(status: 202, contentType: "application/json", data: "")
    }
    logDebug "MCP ${req.method}"
    def id = req.id
    try {
        switch (req.method) {
            case "initialize":
                def asked = req.params?.protocolVersion?.toString()
                return renderJson(rpcResult(id, [
                    protocolVersion: SUPPORTED_PROTOCOLS.contains(asked) ? asked : SUPPORTED_PROTOCOLS[1],
                    capabilities   : [tools: [listChanged: false]],
                    serverInfo     : [name: "myl2-dev-bridge", version: APP_VERSION],
                    instructions   : MCP_INSTRUCTIONS
                ]))
            case "ping":
                return renderJson(rpcResult(id, [:]))
            case "tools/list":
                return renderJson(rpcResult(id, [tools: toolDefinitions()]))
            case "tools/call":
                return renderJson(rpcResult(id, callTool(req.params?.name?.toString(), (req.params?.arguments ?: [:]) as Map)))
            default:
                return renderJson(rpcError(id, -32601, "Method not found: ${req.method}"))
        }
    } catch (Exception e) {
        log.error "Dev Bridge MCP ${req.method} failed: ${e}"
        return renderJson(rpcError(id, -32603, "Internal error: ${e.message ?: e.toString()}"))
    }
}

private Map callTool(String name, Map args) {
    def result
    try {
        switch (name) {
            case "list_code":          result = listCode(reqType(args.type)); break
            case "get_code":           result = getCode(reqType(args.type), reqId(args.id), args.includeSource != false); break
            case "create_code":        result = createCode(reqType(args.type), reqStr(args.source, "source")); break
            case "update_code":        result = updateCode(reqType(args.type), reqId(args.id), reqStr(args.source, "source"), args.expectedVersion); break
            case "delete_code":        result = deleteCode(reqType(args.type), reqId(args.id), args.confirm == true); break
            case "list_devices":       result = listDevices(args.filter?.toString()); break
            case "get_device":         result = getDevice(reqId(args.id)); break
            case "get_device_events":  result = getDeviceEvents(reqId(args.id), (args.max ?: 50) as Integer); break
            case "run_command":        result = runCommand(reqId(args.id), reqStr(args.command, "command"), (args.args ?: []) as List, args.allowUndeclared == true); break
            case "list_installed_apps": result = listInstalledApps(args.filter?.toString()); break
            case "get_app_status":     result = getAppStatus(reqId(args.id)); break
            case "get_logs":           result = getLogs(args.sourceType?.toString(), args.id?.toString(), (args.limit ?: 100) as Integer, args.contains?.toString()); break
            case "self_update":        result = selfUpdate(reqStr(args.source, "source")); break
            case "self_update_status": result = selfUpdateStatus(); break
            case "monitor_report":     result = monitorReport(args.refresh == true, args.clear); break
            case "watch":              result = watch(args.action?.toString() ?: "list", args.sourceType?.toString(), args.id?.toString()); break
            case "read_file":          result = readFile(reqFileName(args.name)); break
            case "write_file":         result = writeFile(reqFileName(args.name), reqStr(args.content, "content")); break
            case "get_jobs":           result = getJobs(args.sourceType?.toString(), args.id?.toString()); break
            default:
                return toolError("Unknown tool: ${name}")
        }
    } catch (IllegalArgumentException e) {
        return toolError(e.message)
    } catch (Exception e) {
        Integer hubStatus = hubClientError(e)
        if (hubStatus) {
            logDebug "tool ${name}: hub answered HTTP ${hubStatus}"
            return toolError("Not found on the hub (HTTP ${hubStatus}) - check the id")
        }
        log.error "Dev Bridge tool ${name} failed: ${e}"
        return toolError("${e.class.simpleName}: ${e.message}")
    }
    boolean failed = (result instanceof Map) && result.success == false
    return [content: [[type: "text", text: JsonOutput.toJson(result)]], isError: failed]
}

private Map toolError(String msg) {
    [content: [[type: "text", text: msg]], isError: true]
}

private Map rpcResult(id, result) { [jsonrpc: "2.0", id: id, result: result] }
private Map rpcError(id, int code, String msg) { [jsonrpc: "2.0", id: id, error: [code: code, message: msg]] }

private renderJson(Object obj, int status = 200) {
    render(status: status, contentType: "application/json", data: JsonOutput.toJson(obj))
}

@Field static final String MCP_INSTRUCTIONS = """Development bridge for Hubitat apps/drivers/libraries.
Workflow: list_code -> get_code (note version) -> update_code with expectedVersion -> check the returned compile result.
Then test: list_devices/list_installed_apps to find instances, run_command, get_device (attributes, state, data, preferences),
get_app_status (settings, state, schedules, subscriptions), get_logs (sourceType=dev|app with id) and get_device_events.
Writes are limited to allowed namespaces. Update this Bridge itself only with self_update (then poll self_update_status).
monitor_report shows errors/warnings/stats for the Bridge and watched items (manage with watch). Large sources can be pushed without MCP via REST: PUT <base>/code/<type>/<id> with the raw file as body."""

private List toolDefinitions() {
    def typeProp = [type: "string", enum: ["app", "driver", "library"], description: "Code type"]
    def idProp = [type: "string", description: "Numeric id"]
    return [
        [name: "list_code", description: "List user code (Apps Code / Drivers Code / Libraries Code) with ids, names and namespaces.",
         inputSchema: [type: "object", properties: [type: typeProp], required: ["type"]]],
        [name: "get_code", description: "Read one app/driver/library: id, version (needed for update), status, errorMessage, source.",
         inputSchema: [type: "object", properties: [type: typeProp, id: idProp,
                       includeSource: [type: "boolean", description: "Default true; false returns only metadata"]], required: ["type", "id"]]],
        [name: "create_code", description: "Create a new app/driver/library from Groovy source. Returns the new id or the compile error. Namespace must be allowed.",
         inputSchema: [type: "object", properties: [type: typeProp, source: [type: "string"]], required: ["type", "source"]]],
        [name: "update_code", description: "Replace the source of existing code and compile it. Returns success/new version or the hub's compile error. Old source is backed up to File Manager.",
         inputSchema: [type: "object", properties: [type: typeProp, id: idProp, source: [type: "string"],
                       expectedVersion: [type: "integer", description: "Optional optimistic lock: fail if hub version differs"]], required: ["type", "id", "source"]]],
        [name: "delete_code", description: "Delete an app/driver/library code entry (backed up to File Manager first). Requires confirm=true.",
         inputSchema: [type: "object", properties: [type: typeProp, id: idProp, confirm: [type: "boolean"]], required: ["type", "id", "confirm"]]],
        [name: "list_devices", description: "List devices (id, name, driver type). Optional case-insensitive filter matched against each entry, e.g. a driver name.",
         inputSchema: [type: "object", properties: [filter: [type: "string"]]]],
        [name: "get_device", description: "Full device detail: driver, current attributes, state variables, data values, preferences, commands with parameter types.",
         inputSchema: [type: "object", properties: [id: idProp], required: ["id"]]],
        [name: "get_device_events", description: "Recent events for one device, newest first.",
         inputSchema: [type: "object", properties: [id: idProp, max: [type: "integer", description: "Default 50"]], required: ["id"]]],
        [name: "run_command", description: "Run any command on a device, with positional arguments (types taken from the driver's command declaration).",
         inputSchema: [type: "object", properties: [id: idProp, command: [type: "string"], args: [type: "array", items: [:]],
                       allowUndeclared: [type: "boolean", description: "Call a method the driver does not declare as a command (e.g. an internal handler under test). The hub reports success even if the method does not exist."]],
                       required: ["id", "command"]]],
        [name: "list_installed_apps", description: "List installed app instances (id, name, type, parent/children). Optional case-insensitive filter.",
         inputSchema: [type: "object", properties: [filter: [type: "string"]]]],
        [name: "get_app_status", description: "Installed app instance internals: settings, state, scheduled jobs, event subscriptions, child apps/devices.",
         inputSchema: [type: "object", properties: [id: idProp], required: ["id"]]],
        [name: "get_logs", description: "Past hub logs, newest last. Scope with sourceType=dev|app and id; optional substring filter (e.g. 'error').",
         inputSchema: [type: "object", properties: [sourceType: [type: "string", enum: ["dev", "app"]], id: idProp,
                       limit: [type: "integer", description: "Default 100"], contains: [type: "string"]]]],
        [name: "self_update", description: "Update this Dev Bridge's own code. The Guardian child app saves it, health-checks the new version and rolls back automatically on failure. APP_VERSION must change. Returns immediately; poll self_update_status (takes ~10-60 s).",
         inputSchema: [type: "object", properties: [source: [type: "string"]], required: ["source"]]],
        [name: "self_update_status", description: "Progress/result of the last self_update: phase (scheduled, saving, verifying, rolling_back, done, failed, rolled_back, rollback_failed), steps and message.",
         inputSchema: [type: "object", properties: [:]]],
        [name: "monitor_report", description: "Guardian monitoring: Bridge health history, and for the Bridge and each watched app/device the errors/warnings logged since last cleared, recent messages, runtime stats and scheduled jobs.",
         inputSchema: [type: "object", properties: [refresh: [type: "boolean", description: "Collect logs/stats now instead of returning the last scheduled check"],
                       clear: [type: "string", description: "Reset error/warning counts after reading: 'all' or a key such as 'dev:251'"]]]],
        [name: "watch", description: "Manage the Guardian watch list of apps/devices under development. action: add, remove or list.",
         inputSchema: [type: "object", properties: [action: [type: "string", enum: ["add", "remove", "list"]],
                       sourceType: [type: "string", enum: ["dev", "app"]], id: idProp], required: ["action"]]],
        [name: "read_file", description: "Read a text file from the hub's File Manager.",
         inputSchema: [type: "object", properties: [name: [type: "string", description: "File name, e.g. deviceProfilesV4_custom.json"]], required: ["name"]]],
        [name: "write_file", description: "Create or replace a text file in the hub's File Manager (e.g. a custom driver profile). An existing file is copied to <name>.bak first.",
         inputSchema: [type: "object", properties: [name: [type: "string"], content: [type: "string"]], required: ["name", "content"]]],
        [name: "get_jobs", description: "Scheduled and running jobs (Logs > Scheduled Jobs). With sourceType+id: only that device's/app's jobs plus its runtime stats (CPU, state size, event counts).",
         inputSchema: [type: "object", properties: [sourceType: [type: "string", enum: ["dev", "app"]], id: idProp]]]
    ]
}

// ======================================================================================
//  REST
// ======================================================================================

def restListCode()   { restWrap { listCode(reqType(params.type)) } }
def restGetCode() {
    try {
        def r = getCode(reqType(params.type), reqId(params.id), true)
        if (params.raw == "true" && r.source != null) return render(status: 200, contentType: "text/plain", data: r.source)
        return renderJson(r)
    } catch (Exception e) {
        return renderJson([success: false, error: e.message], 400)
    }
}
def restCreateCode() { restWrap { createCode(reqType(params.type), reqStr(rawBody(), "request body")) } }
def restUpdateCode() {
    restWrap { updateCode(reqType(params.type), reqId(params.id), reqStr(rawBody(), "request body"), params.version) }
}
def restDeleteCode()      { restWrap { deleteCode(reqType(params.type), reqId(params.id), params.confirm == "true") } }
def restGetDevice()       { restWrap { getDevice(reqId(params.id)) } }
def restGetDeviceEvents() { restWrap { getDeviceEvents(reqId(params.id), (params.max ?: 50) as Integer) } }
def restRunCommand() {
    restWrap {
        def body = null
        try { body = request.JSON } catch (Exception ignored) { }
        def args = (body instanceof List) ? body : ((body instanceof Map && body.args instanceof List) ? body.args : [])
        runCommand(reqId(params.id), params.cmd, args, params.allowUndeclared == "true")
    }
}
def restGetAppStatus() { restWrap { getAppStatus(reqId(params.id)) } }
def restGetLogs()      { restWrap { getLogs(params.sourceType, params.id, (params.limit ?: 100) as Integer, params.contains) } }
def restGetJobs()      { restWrap { getJobs(params.sourceType, params.id) } }
def restSelfUpdate()   { restWrap { selfUpdate(reqStr(rawBody(), "request body")) } }
def restSelfStatus()   { restWrap { selfUpdateStatus() } }
def restMonitor()      { restWrap { monitorReport(params.refresh == "true", params.clear) } }
def restReadFile()     { restWrap { readFile(reqFileName(params.name)) } }
def restWriteFile()    { restWrap { writeFile(reqFileName(params.name), reqStr(rawBody(), "request body")) } }

private restWrap(Closure c) {
    try {
        def r = c()
        return renderJson(r, (r instanceof Map && r.success == false) ? 422 : 200)
    } catch (IllegalArgumentException e) {
        return renderJson([success: false, error: e.message], 400)
    } catch (Exception e) {
        Integer hubStatus = hubClientError(e)
        if (hubStatus) {
            logDebug "REST ${request?.requestURI}: hub answered HTTP ${hubStatus}"
            return renderJson([success: false, error: "Not found on the hub (HTTP ${hubStatus}) - check the id"], hubStatus)
        }
        log.error "Dev Bridge REST failed: ${e}"
        return renderJson([success: false, error: "${e.class.simpleName}: ${e.message}"], 500)
    }
}

// HTTP 4xx from a hub endpoint (e.g. 404 for a deleted device) is a bad id from the caller,
// not a Bridge failure: answer it without an error log entry. Returns null for anything else.
private Integer hubClientError(Exception e) {
    def m = (e.message ?: "") =~ /status code: (4\d\d)/
    return m.find() ? (m.group(1) as Integer) : null
}

private String rawBody() {
    def b = request.body
    return (b instanceof String) ? b : b?.toString()
}

// ======================================================================================
//  Code management
// ======================================================================================

private listCode(String type) {
    return parseJson(hubGet(CODE_PATHS[type].list))
}

private Map getCode(String type, String id, boolean includeSource) {
    Map item = readCode(type, id)
    if (!includeSource) item.remove("source")
    return item
}

// Normalised [id, version, status, errorMessage, namespace, source] for any code type.
private Map readCode(String type, String id) {
    def parsed
    try {
        if (type == "library") {
            def list = parseJson(hubGet(CODE_PATHS.library.read + id))
            if (!(list instanceof List) || list.isEmpty()) throw new IllegalArgumentException("Library ${id} not found")
            parsed = list[0]
        } else {
            parsed = parseJson(hubGet(CODE_PATHS[type].read, [id: id]))
        }
    } catch (IllegalArgumentException e) {
        throw e
    } catch (Exception e) {
        // The hub answers an unknown id with HTTP 500
        throw new IllegalArgumentException("${type} ${id} not found (${e.message})")
    }
    if (!(parsed instanceof Map)) throw new IllegalArgumentException("${type} ${id}: unexpected response from hub")
    if (parsed.source == null && parsed.status == "error") {
        throw new IllegalArgumentException("${type} ${id}: ${parsed.errorMessage ?: 'not found'}")
    }
    return [type: type, id: id, version: parsed.version, status: parsed.status, errorMessage: parsed.errorMessage,
            namespace: namespaceOf(parsed.source as String), source: parsed.source]
}

private Map createCode(String type, String source) {
    requireAllowedNamespace(source, "new source")
    def resp = hubPostJson(CODE_PATHS[type].save, [id: null, source: source, version: 1])
    if (!(resp instanceof Map) || resp.success != true) {
        return [success: false, error: hubMessage(resp) ?: "hub rejected the source", hubResponse: resp]
    }
    Map created = readCode(type, resp.id.toString())
    return [success: created.status != "error", id: resp.id.toString(), version: created.version,
            status: created.status, errorMessage: created.errorMessage, message: resp.message]
}

private Map updateCode(String type, String id, String source, expectedVersion) {
    requireNotSelf(type, id)
    Map current = readCode(type, id)
    if (!nsAllowList().contains(current.namespace)) {
        throw new IllegalArgumentException("${type} ${id} is in namespace '${current.namespace}', which is not in the allowed list ${nsAllowList()}")
    }
    requireAllowedNamespace(source, "new source")
    if (expectedVersion != null && expectedVersion.toString() != current.version?.toString()) {
        return [success: false, error: "Version conflict: expected ${expectedVersion}, hub has ${current.version}. Re-read and retry."]
    }
    if (current.source == source) {
        return [success: true, unchanged: true, id: id, version: current.version, message: "Source identical; nothing saved"]
    }
    String backup = backupSource(type, id, current.source as String)

    def resp = hubPostJson(CODE_PATHS[type].save, [id: id as Integer, source: source, version: current.version])
    if (!(resp instanceof Map) || resp.success != true) {
        // Compile errors land here with the hub's message
        return [success: false, id: id, error: hubMessage(resp) ?: "hub did not confirm the save", hubResponse: resp, backupFile: backup]
    }
    if (resp.id != null && resp.id.toString() != id) {
        return [success: false, error: "Hub saved to id ${resp.id} instead of ${id}; check for a duplicate entry", backupFile: backup]
    }
    Map after = readCode(type, id)
    return [success: after.status != "error", id: id, previousVersion: current.version, version: after.version,
            status: after.status, errorMessage: after.errorMessage, message: resp.message, backupFile: backup]
}

private Map deleteCode(String type, String id, boolean confirm) {
    if (!confirm) throw new IllegalArgumentException("delete_code requires confirm=true")
    requireNotSelf(type, id)
    Map current = readCode(type, id)
    if (!nsAllowList().contains(current.namespace)) {
        throw new IllegalArgumentException("${type} ${id} is in namespace '${current.namespace}', which is not in the allowed list ${nsAllowList()}")
    }
    String backup = backupSource(type, id, current.source as String)
    def text = hubGet(CODE_PATHS[type].delete + id)
    def parsed = null
    try { parsed = new JsonSlurper().parseText(text ?: "") } catch (Exception ignored) { }
    boolean ok = (parsed instanceof Map) ? (parsed.status?.toString() == "true" || parsed.success == true) : false
    return [success: ok, id: id, backupFile: backup, hubResponse: parsed ?: text?.take(300),
            note: ok ? null : "Delete not confirmed; in-use code (installed instances/devices) cannot be deleted"]
}

private String backupSource(String type, String id, String source) {
    if (source == null) return null
    String name = "devbridge-backup-${type}-${id}.groovy"
    try {
        uploadHubFile(name, source.getBytes("UTF-8"))
        return name
    } catch (Exception e) {
        log.warn "Dev Bridge: backup of ${type} ${id} failed: ${e.message}"
        return null
    }
}

private String namespaceOf(String source) {
    return definitionField(source, "namespace")
}

// Value of a field inside the definition(...) / library(...) call, following a constant
// (e.g. namespace: sNamespace) to its String assignment elsewhere in the source.
private String definitionField(String source, String field) {
    String blk = definitionBlock(source)
    if (blk == null) return null
    def lit = blk =~ /\b${field}\s*:\s*["']([^"']+)["']/
    if (lit.find()) return lit.group(1)
    def ref = blk =~ /\b${field}\s*:\s*([A-Za-z_]\w*)/
    if (ref.find()) {
        def c = source =~ /\b${ref.group(1)}\s*=\s*["']([^"']+)["']/
        if (c.find()) return c.group(1)
    }
    return null
}

// Text between the parentheses of the first definition( / library( call, honouring quotes and nesting.
private String definitionBlock(String source) {
    if (!source) return null
    def start = source =~ /(?m)^[ \t]*(?:definition|library)\s*\(/   // line start, so comments mentioning definition( don't match
    if (!start.find()) return null
    int i = start.end()
    int depth = 1
    String quote = null
    StringBuilder blk = new StringBuilder()
    while (i < source.length()) {
        String c = source.substring(i, i + 1)
        if (quote != null) {
            if (c == quote && source.substring(i - 1, i) != "\\") quote = null
        } else if (c == '"' || c == "'") {
            quote = c
        } else if (c == "(") {
            depth++
        } else if (c == ")") {
            depth--
            if (depth == 0) return blk.toString()
        }
        blk.append(c)
        i++
    }
    return null
}

private List nsAllowList() {
    return (settings.allowedNamespaces ?: "myL2").split(",").collect { it.trim() }.findAll { it }
}

private void requireAllowedNamespace(String source, String what) {
    String ns = namespaceOf(source)
    if (!ns) throw new IllegalArgumentException("Could not find a namespace in the ${what} definition()")
    if (!nsAllowList().contains(ns)) {
        throw new IllegalArgumentException("Namespace '${ns}' in the ${what} is not in the allowed list ${nsAllowList()}")
    }
}

private String hubMessage(resp) {
    if (!(resp instanceof Map)) return resp?.toString()?.take(500)
    return (resp.message ?: resp.errorMessage)?.toString()
}

// ======================================================================================
//  Self-update and monitoring (via the Guardian child app)
// ======================================================================================

private Map selfUpdate(String source) {
    String name = definitionName(source)
    if (name != app.name) throw new IllegalArgumentException("Source defines '${name}', not '${app.name}'; use update_code for other apps")
    requireAllowedNamespace(source, "new source")
    String newVersion = (source =~ /APP_VERSION\s*=\s*"([^"]+)"/).with { it.find() ? it.group(1) : null }
    if (!newVersion) throw new IllegalArgumentException("New source has no APP_VERSION; the Guardian needs it to confirm the new code is running")
    if (newVersion == APP_VERSION) throw new IllegalArgumentException("New source has APP_VERSION ${newVersion}, same as the running Bridge; bump it")

    def g = guardianApp()
    String typeId = bridgeAppTypeId()
    Map current = readCode("app", typeId)
    uploadHubFile(SELF_BACKUP_FILE, (current.source as String).getBytes("UTF-8"))
    uploadHubFile(SELF_UPDATE_FILE, source.getBytes("UTF-8"))
    def staged = g.stageUpdate([sourceFile: SELF_UPDATE_FILE, backupFile: SELF_BACKUP_FILE, fromVersion: APP_VERSION, toVersion: newVersion])
    if (staged?.success != true) return [success: false, error: staged?.error ?: "Guardian refused the update", job: staged?.job]
    return [success: true, status: "scheduled", fromVersion: APP_VERSION, toVersion: newVersion, backupFile: SELF_BACKUP_FILE,
            note: "The Guardian applies and verifies it in the background; poll self_update_status in ~10 s"]
}

private Map selfUpdateStatus() {
    def job = guardianApp().getStatus()
    return [runningVersion: APP_VERSION, job: job ?: "no self-update has run"]
}

private Map monitorReport(boolean refresh, clear) {
    def g = guardianApp()
    Map report = refresh ? g.collectNow() : g.getMonitor()
    if (clear) g.clearIssues(clear == "all" ? null : clear.toString())
    return report
}

private Map watch(String action, String sourceType, String id) {
    def g = guardianApp()
    if (action == "list") return [watched: (g.getMonitor().watched ?: [:]).keySet() as List]
    if (!(action in ["add", "remove"])) throw new IllegalArgumentException("action must be add, remove or list")
    if (!(sourceType in ["dev", "app"])) throw new IllegalArgumentException("sourceType must be 'dev' or 'app'")
    reqId(id)
    String name = null
    if (action == "add") {
        // Validate the id and pick up a readable name
        try {
            def info = parseJson(hubGet(sourceType == "dev" ? "/device/fullJson/${id}" : "/installedapp/statusJson/${id}"))
            name = sourceType == "dev" ? (info?.device?.label ?: info?.device?.name) : (info?.installedApp?.label ?: info?.installedApp?.name)
        } catch (Exception e) {
            throw new IllegalArgumentException("No ${sourceType == 'dev' ? 'device' : 'installed app'} ${id}")
        }
    }
    return [watched: g.setWatch(action, sourceType, id, name)]
}

// Finds or creates the Guardian child, and keeps its connection details current.
private guardianApp() {
    def g = getChildApps()?.find { it.name == GUARDIAN_NAME }
    if (!g) {
        try {
            g = addChildApp("myL2", GUARDIAN_NAME, "Dev Bridge Guardian")
        } catch (Exception e) {
            throw new IllegalArgumentException("Guardian not available: add ${GUARDIAN_NAME} (DevBridgeGuardian.groovy) in Apps Code first (${e.message})")
        }
        g.initialize()
    }
    g.configure([appTypeId: bridgeAppTypeId(), bridgeAppId: app.id.toString(), token: state.accessToken])
    return g
}

private String bridgeAppTypeId() {
    if (state.appTypeId) return state.appTypeId
    def data = parseJson(hubGet("/hub2/appsList"))
    def list = (data instanceof Map && data.apps instanceof List) ? data.apps : data
    def mine = list?.find { it?.id?.toString() == app.id.toString() }
    String typeId = mine?.data?.appTypeId?.toString()
    if (!typeId) throw new IllegalStateException("Could not find this app's code id in /hub2/appsList")
    state.appTypeId = typeId
    return typeId
}

private void requireNotSelf(String type, String id) {
    if (type == "app" && id == bridgeAppTypeId()) {
        throw new IllegalArgumentException("That is this Dev Bridge's own code; use self_update, which applies it safely through the Guardian")
    }
}

private String definitionName(String source) {
    return definitionField(source, "name")
}

// ======================================================================================
//  File Manager
// ======================================================================================

private Map readFile(String name) {
    def bytes = downloadHubFile(name)
    if (bytes == null) throw new IllegalArgumentException("File ${name} not found")
    return [name: name, size: bytes.length, content: new String(bytes, "UTF-8")]
}

private Map writeFile(String name, String content) {
    String backup = null
    def old = null
    try { old = downloadHubFile(name) } catch (Exception ignored) { }
    if (old != null) {
        backup = "${name}.bak"
        uploadHubFile(backup, old)
    }
    uploadHubFile(name, content.getBytes("UTF-8"))
    return [success: true, name: name, size: content.length(), backupFile: backup]
}

private String reqFileName(n) {
    String s = n?.toString()?.trim()
    if (!s || !(s ==~ /[A-Za-z0-9._-]{1,100}/)) throw new IllegalArgumentException("name must be a plain file name (letters, digits, . _ -)")
    return s
}

// ======================================================================================
//  Testing: devices, apps, logs
// ======================================================================================

private listDevices(String filter) {
    def data = parseJson(hubGet("/hub2/devicesList"))
    def list = (data instanceof Map && data.devices instanceof List) ? data.devices : data
    return filterEntries(list, filter)
}

private getDevice(String id) {
    return parseJson(hubGet("/device/fullJson/${id}"))
}

private getDeviceEvents(String id, Integer max) {
    def data = parseJson(hubGet("/device/eventsJson/${id}"))
    if (data instanceof List && max && data.size() > max) return data.take(max)
    return data
}

private Map runCommand(String id, String command, List args, boolean allowUndeclared = false) {
    if (settings.allowDeviceCommands == false) throw new IllegalArgumentException("Device commands are disabled in Dev Bridge preferences")
    def full = parseJson(hubGet("/device/fullJson/${id}"))
    def cmdDef = (full instanceof Map && full.commands instanceof List) ? full.commands.find { it?.name == command } : null
    List declared = []
    if (cmdDef?.parameters instanceof List) declared = cmdDef.parameters.collect { (it instanceof Map) ? it.type?.toString() : it?.toString() }
    else if (cmdDef?.arguments instanceof List) declared = cmdDef.arguments.collect { (it instanceof Map) ? it.type?.toString() : it?.toString() }
    if (cmdDef == null && !allowUndeclared) {
        def known = (full instanceof Map && full.commands instanceof List) ? full.commands.collect { it?.name } : []
        return [success: false, error: "Device ${id} has no command '${command}'. Declared: ${known}. Pass allowUndeclared=true to call it anyway."]
    }
    def typedArgs = []
    args.eachWithIndex { v, i ->
        String t = (i < declared.size() && declared[i]) ? declared[i] :
            ((v instanceof Map || v instanceof List) ? "JSON_OBJECT" : (v instanceof Number ? "NUMBER" : "STRING"))
        typedArgs << [type: t, value: v]
    }
    def resp = hubPostJson("/device/runmethod", [id: id as Integer, method: command, args: typedArgs])
    boolean ok = (resp instanceof Map) && resp.success == true
    return [success: ok, id: id, command: command, args: typedArgs, hubResponse: resp,
            knownCommand: cmdDef != null,
            note: ok ? "Command sent; state changes arrive asynchronously -- re-read with get_device or get_logs" : "Hub did not confirm the command"]
}

private listInstalledApps(String filter) {
    def data = parseJson(hubGet("/hub2/appsList"))
    def list = (data instanceof Map && data.apps instanceof List) ? data.apps : data
    return filterEntries(list, filter)
}

private getAppStatus(String id) {
    return parseJson(hubGet("/installedapp/statusJson/${id}"))
}

private Map getLogs(String sourceType, String id, Integer limit, String contains) {
    Map query = null
    if (sourceType) {
        if (!(sourceType in ["dev", "app"])) throw new IllegalArgumentException("sourceType must be 'dev' or 'app'")
        if (!id?.isInteger()) throw new IllegalArgumentException("id must be numeric when sourceType is set")
        query = [type: sourceType, id: id]
    }
    def lines = parseJson(hubGet("/logs/past/json", query, 30))
    if (!(lines instanceof List)) return [success: false, error: "Unexpected logs response", hubResponse: lines]
    // Multi-line messages arrive as extra entries without tabs; fold them into the entry above
    def out = []
    lines.each { l ->
        String s = l.toString()
        if (!s.contains("\t") && out) out[-1] = out[-1] + "\n" + s
        else out << s
    }
    if (contains) out = out.findAll { it.toLowerCase().contains(contains.toLowerCase()) }
    int total = out.size()
    if (limit && total > limit) out = out.drop(total - limit)
    return [count: out.size(), matched: total, lines: out.collect { it.split("\t") as List }]
}

// /logs/json also carries runtime stats for every app and device (~250 KB), so only the
// jobs are returned, plus the stats entry of the one device/app asked for.
private Map getJobs(String sourceType, String id) {
    def data = parseJson(hubGet("/logs/json"))
    if (!(data instanceof Map)) return [success: false, error: "Unexpected /logs/json response"]
    def jobs = data.jobs ?: []
    def running = data.runningJobs ?: []
    def result = [uptime: data.uptime]
    if (sourceType) {
        if (!(sourceType in ["dev", "app"])) throw new IllegalArgumentException("sourceType must be 'dev' or 'app'")
        reqId(id)
        String prefix = "${sourceType}${id}"
        def mine = { j -> j instanceof Map && (j.id?.toString() ==~ /^${prefix}(Once|Recur)\..*/) }
        jobs = jobs.findAll(mine)
        running = running.findAll { it instanceof Map ? mine(it) : it?.toString()?.contains(prefix) }
        def stats = (sourceType == "dev" ? data.deviceStats : data.appStats)?.find { it?.id?.toString() == id }
        result.stats = stats?.findAll { k, v -> !k.toString().startsWith("formatted") }
    }
    result.jobs = jobs
    result.runningJobs = running
    result.hubCommands = data.hubCommands
    return result
}

private filterEntries(list, String filter) {
    if (!filter || !(list instanceof List)) return list
    String f = filter.toLowerCase()
    return list.findAll { JsonOutput.toJson(it).toLowerCase().contains(f) }
}

// ======================================================================================
//  Hub loopback HTTP
// ======================================================================================

private String hubGet(String path, Map query = null, int timeout = 30) {
    def params = [uri: HUB, path: path, textParser: true, timeout: timeout]
    if (query) params.query = query
    String text = null
    httpGet(params) { resp -> text = readText(resp) }
    return text
}

private hubPostJson(String path, Map body, int timeout = 120) {
    def params = [uri: HUB, path: path, requestContentType: "application/json", textParser: true,
                  body: JsonOutput.toJson(body), timeout: timeout]
    String text = null
    httpPost(params) { resp -> text = readText(resp) }
    if (!text) return null
    try {
        return new JsonSlurper().parseText(text)
    } catch (Exception e) {
        return text.take(1000)
    }
}

private String readText(resp) {
    def d = resp?.data
    if (d == null) return null
    if (d instanceof CharSequence) return d.toString()
    return d.text
}

private parseJson(String text) {
    if (text == null || text.trim().isEmpty()) throw new IllegalStateException("Empty response from hub")
    try {
        return new JsonSlurper().parseText(text)
    } catch (Exception e) {
        throw new IllegalStateException("Hub returned non-JSON (endpoint missing on this firmware?): ${text.take(200)}")
    }
}

private String reqType(t) {
    String s = t?.toString()?.toLowerCase()
    if (!CODE_PATHS.containsKey(s)) throw new IllegalArgumentException("type must be one of ${CODE_PATHS.keySet()}")
    return s
}

private String reqId(id) {
    String s = id?.toString()?.trim()
    if (!s?.isInteger()) throw new IllegalArgumentException("id must be numeric, got '${id}'")
    return s
}

private String reqStr(v, String name) {
    if (v == null || v.toString().trim().isEmpty()) throw new IllegalArgumentException("${name} is required")
    return v.toString()
}
