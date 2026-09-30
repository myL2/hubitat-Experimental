/**
 * =============================  myL2 Dev Bridge Guardian ===================================
 *
 *  DESCRIPTION:
 *  Child app of myL2 Dev Bridge. Two jobs:
 *
 *  1. Self-update. An app cannot safely save its own code: the hub reloads it while the
 *     request is still running. The Bridge hands the new source to this app, which saves it
 *     from its own scheduled job, then health-checks the Bridge over its /mcp endpoint (new
 *     version running, tools/list still offers self_update, a real tool call works). If the
 *     check fails, the previous source is restored and checked again.
 *
 *  2. Monitoring. On a schedule it health-checks the Bridge, collects errors/warnings from
 *     the hub log for the Bridge and for a watch list of apps/devices under development, and
 *     records their runtime stats (CPU share, state size). Optional notifications on Bridge
 *     down/recovered and new errors; optional restore of the last known-good Bridge code
 *     after repeated failed checks.
 *
 *  TO INSTALL:
 *  Add this code in Apps Code (no OAuth needed). The Bridge creates the child instance itself.
 *
 * =======================================================================================
 *
 *  Changelog:
 *
 *  v1.0.1 (2026-09-30) - Self-update progress stored in atomicState so the Bridge sees each phase live
 *  v1.0.0 (2026-09-30) - First release
 *
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String APP_VERSION = "1.0.1"
@Field static final String HUB = "http://127.0.0.1:8080"
@Field static final int HEALTH_TRIES = 8
@Field static final long HEALTH_WAIT_MS = 2000
@Field static final long STALE_JOB_MS = 5 * 60 * 1000
@Field static final int KEEP_ISSUES = 20
@Field static final int KEEP_HISTORY = 48
@Field static final String LAST_GOOD_FILE = "devbridge-lastgood.groovy"
@Field static final List ACTIVE_PHASES = ["scheduled", "saving", "verifying", "rolling_back"]

definition(
    name: "myL2 Dev Bridge Guardian",
    namespace: "myL2",
    author: "myL2",
    parent: "myL2:myL2 Dev Bridge",
    description: "Applies and health-checks updates to the myL2 Dev Bridge, and monitors it and watched apps/devices",
    category: "Utility",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage", install: true, uninstall: true) {
        section("Bridge health") {
            def b = state.bridge
            if (!b) {
                paragraph "No check has run yet."
            } else {
                paragraph "<b>${b.ok ? 'OK' : 'FAILING'}</b> -- v${b.version ?: '?'}, ${b.latencyMs ?: '?'} ms, checked ${fmt(b.lastCheck)}" +
                          (b.ok ? "" : "<br>${b.consecutiveFailures} failed checks; last error: ${b.lastError}")
            }
        }
        section("Watched") {
            def items = state.watched ?: [:]
            if (!items) paragraph "Nothing watched besides the Bridge. Add items with the Bridge's watch tool."
            items.each { key, w ->
                paragraph "<b>${w.name ?: key}</b> (${key}): ${w.errors ?: 0} errors, ${w.warnings ?: 0} warnings" +
                          (w.stats ? ", CPU ${w.stats.pct}%, state ${w.stats.stateSize} B" : "")
            }
        }
        section("Last self-update") {
            def job = atomicState?.job
            if (!job) {
                paragraph "No update has run yet."
            } else {
                paragraph "<b>${job.phase}</b> -- ${job.fromVersion} -> ${job.toVersion}<br>${job.message ?: ''}"
                paragraph "<small>${(job.steps ?: []).join('<br>')}</small>"
            }
        }
        section("Settings") {
            input "checkInterval", "enum", title: "Check every", options: ["1": "1 minute", "5": "5 minutes", "15": "15 minutes", "30": "30 minutes"], defaultValue: "5"
            input "notifyDevice", "capability.notification", title: "Notify on Bridge down/recovered and new errors", required: false, multiple: true
            input "notifyWarnings", "bool", title: "Also notify on new warnings", defaultValue: false
            input "autoRestore", "bool", title: "Restore last known-good Bridge code after repeated failed checks", defaultValue: false
            input "autoRestoreAfter", "number", title: "Failed checks before restoring", defaultValue: 3, range: "2..20"
        }
        section {
            paragraph "<small>v${APP_VERSION}</small>"
        }
    }
}

def installed() { initialize() }
def updated()   { initialize() }

def initialize() {
    unschedule("monitorTick")
    switch (settings.checkInterval ?: "5") {
        case "1":  runEvery1Minute("monitorTick"); break
        case "15": runEvery15Minutes("monitorTick"); break
        case "30": runEvery30Minutes("monitorTick"); break
        default:   runEvery5Minutes("monitorTick")
    }
    log.info "Dev Bridge Guardian v${APP_VERSION} monitoring every ${settings.checkInterval ?: 5} min"
}

// ======================================================================================
//  Called by the parent Bridge
// ======================================================================================

// The Bridge passes its connection details on every call so they stay current.
def configure(Map conn) {
    state.conn = conn   // appTypeId, bridgeAppId, token
}

// req: sourceFile, backupFile, fromVersion, toVersion
def stageUpdate(Map req) {
    def job = atomicState.job
    if (job && job.phase in ACTIVE_PHASES && now() - (job.started as Long) < STALE_JOB_MS) {
        return [success: false, error: "Another self-update is in progress (${job.phase})", job: job]
    }
    state.req = req
    atomicState.job = [id: now(), phase: "scheduled", started: now(), fromVersion: req.fromVersion, toVersion: req.toVersion, steps: []]
    runIn(2, "applyUpdate")
    return [success: true, jobId: atomicState.job.id]
}

def getStatus() {
    return atomicState.job
}

def getMonitor() {
    return [bridge: (state.bridge ?: [:]) + [issues: state.bridgeIssues], watched: state.watched ?: [:], history: state.history ?: [],
            lastTick: state.lastTick, checkIntervalMinutes: (settings.checkInterval ?: "5") as Integer,
            autoRestore: settings.autoRestore == true, lastGoodVersion: state.lastGoodVersion]
}

// Clears the collected errors/warnings of one watched item (or all, key == null).
def clearIssues(String key) {
    def items = state.watched ?: [:]
    (key ? [key] : items.keySet() as List).each { k ->
        if (items[k]) items[k] += [errors: 0, warnings: 0, issues: []]
    }
    state.watched = items
}

// action: add | remove; type: app | dev
def setWatch(String action, String type, String id, String name) {
    def items = state.watched ?: [:]
    String key = "${type}:${id}"
    if (key == "app:${state.conn?.bridgeAppId}") return items.keySet() as List   // always monitored
    if (action == "add") {
        if (!items[key]) items[key] = [type: type, id: id, name: name, errors: 0, warnings: 0, issues: [], since: now()]
    } else {
        items.remove(key)
    }
    state.watched = items
    return items.keySet() as List
}

// Log/stats collection without the Bridge health check; safe to call from a Bridge request.
def collectNow() {
    collect()
    return getMonitor()
}

// ======================================================================================
//  Monitoring
// ======================================================================================

def monitorTick() {
    if (!state.conn) return   // Bridge has not configured us yet
    if (atomicState.job?.phase in ACTIVE_PHASES) return   // update in progress; it runs its own checks
    checkBridge()
    collect()
    state.lastTick = now()
}

private void checkBridge() {
    Map b = state.bridge ?: [consecutiveFailures: 0]
    boolean wasOk = b.ok != false
    long t0 = now()
    String problem = probeBridge(null)
    b.lastCheck = now()
    b.latencyMs = now() - t0
    if (problem == null) {
        b.ok = true
        b.consecutiveFailures = 0
        b.lastOkAt = now()
        b.version = state.probeVersion
        if (!wasOk) notify("Dev Bridge recovered (v${b.version})")
        snapshotLastGood(b.version)
    } else {
        b.ok = false
        b.consecutiveFailures = (b.consecutiveFailures ?: 0) + 1
        b.lastError = problem
        log.warn "Dev Bridge Guardian: Bridge check failed (${b.consecutiveFailures}): ${problem}"
        if (b.consecutiveFailures == 2) notify("Dev Bridge is failing: ${problem}")
        int limit = (settings.autoRestoreAfter ?: 3) as Integer
        if (settings.autoRestore && b.consecutiveFailures == limit) autoRestoreLastGood(problem)
    }
    state.bridge = b
    def hist = (state.history ?: []) + [[t: b.lastCheck, ok: b.ok, ms: b.latencyMs]]
    state.history = hist.size() > KEEP_HISTORY ? hist.drop(hist.size() - KEEP_HISTORY) : hist
}

// Keeps a copy of the Bridge source each time a new version is seen healthy.
private void snapshotLastGood(String version) {
    if (!version || version == state.lastGoodVersion) return
    try {
        Map code = readAppCode(state.conn.appTypeId)
        uploadHubFile(LAST_GOOD_FILE, (code.source as String).getBytes("UTF-8"))
        state.lastGoodVersion = version
        log.info "Dev Bridge Guardian: saved v${version} as last known-good"
    } catch (Exception e) {
        log.warn "Dev Bridge Guardian: could not snapshot last known-good: ${e.message}"
    }
}

private void autoRestoreLastGood(String problem) {
    if (!state.lastGoodVersion) {
        notify("Dev Bridge failing and no known-good copy to restore: ${problem}")
        return
    }
    Map req = [sourceFile: LAST_GOOD_FILE, backupFile: LAST_GOOD_FILE, fromVersion: state.bridge?.version ?: "?", toVersion: state.lastGoodVersion]
    state.req = req
    atomicState.job = [id: now(), phase: "scheduled", started: now(), fromVersion: req.fromVersion, toVersion: req.toVersion,
                 steps: ["Auto-restore after ${state.bridge?.consecutiveFailures} failed checks: ${problem}".toString()]]
    notify("Dev Bridge failing; restoring known-good v${state.lastGoodVersion}")
    runIn(1, "applyUpdate")
}

// Errors/warnings from the hub log and runtime stats, for the Bridge and each watched item.
private void collect() {
    def items = state.watched ?: [:]
    String bridgeKey = "app:${state.conn?.bridgeAppId}"
    Map all = [(bridgeKey): (items[bridgeKey] ?: [type: "app", id: state.conn?.bridgeAppId?.toString(), name: "myL2 Dev Bridge", errors: 0, warnings: 0, issues: []])] + items
    // Cursor is a parsed log timestamp (never now()), so both sides use the same clock/zone.
    Long since = state.logCursor as Long
    Long newest = since
    List fresh = []

    def lines = null
    try { lines = hubJson("/logs/past/json", null) } catch (Exception e) { log.warn "Dev Bridge Guardian: log read failed: ${e.message}" }
    if (lines instanceof List) {
        lines.each { raw ->
            def f = raw.toString().split("\t")
            if (f.size() < 3) return
            Long ts = parseLogTime(f[0])
            if (ts == null) return
            if (newest == null || ts > newest) newest = ts
            if (since != null && ts <= since) return
            String level = f[1].trim().toUpperCase()
            if (!(level in ["ERROR", "WARN"])) return
            def src = f[2].split("\\|", 4)   // type|id|name|message
            if (src.size() < 4) return
            String key = "${src[0]}:${src[1]}"
            Map w = all[key]
            if (!w) return
            if (level == "ERROR") w.errors = (w.errors ?: 0) + 1 else w.warnings = (w.warnings ?: 0) + 1
            def issues = (w.issues ?: []) + [[t: f[0], level: level, msg: src[3].take(300)]]
            w.issues = issues.size() > KEEP_ISSUES ? issues.drop(issues.size() - KEEP_ISSUES) : issues
            if (level == "ERROR" || settings.notifyWarnings) fresh << "${w.name ?: key}: ${level} ${src[3].take(120)}"
        }
    }
    // First run only sets the cursor so old log history is not reported as new.
    if (since == null) {
        all.each { k, w -> w.errors = 0; w.warnings = 0; w.issues = [] }
        fresh = []
    }
    if (newest != null) state.logCursor = newest

    try {
        def jobs = hubJson("/logs/json", null)
        all.each { key, w ->
            def list = (w.type == "dev") ? jobs?.deviceStats : jobs?.appStats
            def s = list?.find { it?.id?.toString() == w.id?.toString() }
            if (s) {
                w.stats = [pct: s.pct, stateSize: s.stateSize, count: s.count, avgMs: s.average, largeState: s.largeState]
                if (!w.name) w.name = s.name
            }
            w.scheduledJobs = jobs?.jobs?.findAll { it?.id?.toString() ==~ /^${w.type}${w.id}(Once|Recur)\..*/ }?.collect { it.methodName + " @ " + it.nextRun }
        }
    } catch (Exception e) {
        log.warn "Dev Bridge Guardian: stats read failed: ${e.message}"
    }

    state.bridgeIssues = all.remove(bridgeKey)
    state.watched = items.keySet().findAll { all[it] }.collectEntries { k -> [(k): all[k]] }
    if (fresh) notify("Dev Bridge monitor: ${fresh.size()} new issue(s)\n" + fresh.take(5).join("\n"))
}

private Long parseLogTime(String s) {
    try {
        return Date.parse("yyyy-MM-dd HH:mm:ss.SSS", s.trim()).time
    } catch (Exception e) {
        return null
    }
}

private void notify(String msg) {
    log.info "Dev Bridge Guardian: ${msg}"
    settings.notifyDevice?.each { it.deviceNotification(msg) }
}

// ======================================================================================
//  Update job
// ======================================================================================

def applyUpdate() {
    Map req = state.req
    Map conn = state.conn
    if (!req || !conn) return
    try {
        phase("saving", "Saving ${req.toVersion}")
        String source = readFile(req.sourceFile)
        Map current = readAppCode(conn.appTypeId)
        def resp = saveAppCode(conn.appTypeId, source, current.version)
        if (!(resp instanceof Map) || resp.success != true) {
            finish("failed", "Hub rejected the new source; ${req.fromVersion} is still running: ${message(resp)}")
            return
        }
        step("Saved (code version ${current.version} -> ${readAppCode(conn.appTypeId).version})")

        phase("verifying", "Checking the Bridge runs ${req.toVersion}")
        String problem = healthCheck(req.toVersion)
        if (problem == null) {
            finish("done", "Bridge updated to ${req.toVersion} and passed the health check")
            state.bridge = (state.bridge ?: [:]) + [ok: true, consecutiveFailures: 0, version: req.toVersion, lastCheck: now(), lastOkAt: now()]
            snapshotLastGood(req.toVersion)
            return
        }
        step("Health check failed: ${problem}")

        phase("rolling_back", "Restoring ${req.fromVersion}")
        String backup = readFile(req.backupFile)
        Map broken = readAppCode(conn.appTypeId)
        resp = saveAppCode(conn.appTypeId, backup, broken.version)
        if (!(resp instanceof Map) || resp.success != true) {
            finish("rollback_failed", "New code failed (${problem}) and restoring ${req.backupFile} was rejected: ${message(resp)}")
            return
        }
        String after = healthCheck(req.fromVersion)
        if (after == null) {
            finish("rolled_back", "New code failed the health check (${problem}); ${req.fromVersion} restored and healthy")
        } else {
            finish("rollback_failed", "New code failed (${problem}); restored ${req.fromVersion} but it is unhealthy too: ${after}")
        }
    } catch (Exception e) {
        finish("failed", "Guardian error: ${e.class.simpleName}: ${e.message}")
    }
}

// Retries probeBridge; null when healthy, else the last failure.
private String healthCheck(String expectedVersion) {
    String last = null
    for (int i = 0; i < HEALTH_TRIES; i++) {
        pauseExecution(HEALTH_WAIT_MS)
        last = probeBridge(expectedVersion)
        if (last == null) return null
    }
    return last
}

// One check: initialize (version), tools/list (self-update tools present), a real tool call.
// Returns null when healthy. expectedVersion null = any version.
private String probeBridge(String expectedVersion) {
    try {
        def init = callBridge([jsonrpc: "2.0", id: 1, method: "initialize",
                               params: [protocolVersion: "2025-06-18", capabilities: [:], clientInfo: [name: "guardian", version: APP_VERSION]]])
        String running = init?.result?.serverInfo?.version
        state.probeVersion = running
        if (!running) return "initialize failed: ${init}"
        if (expectedVersion && running != expectedVersion) return "running version is ${running}, expected ${expectedVersion}"

        def tools = callBridge([jsonrpc: "2.0", id: 2, method: "tools/list"])
        def names = tools?.result?.tools?.collect { it.name } ?: []
        if (!names.containsAll(["self_update", "self_update_status"])) return "tools/list lacks self_update tools: ${names}"

        def call = callBridge([jsonrpc: "2.0", id: 3, method: "tools/call", params: [name: "list_code", arguments: [type: "library"]]])
        if (call?.result == null || call.result.isError) return "list_code call failed: ${call?.error ?: call?.result}"
        return null
    } catch (Exception e) {
        return "${e.class.simpleName}: ${e.message}"
    }
}

private callBridge(Map body) {
    Map conn = state.conn
    def params = [uri: HUB, path: "/apps/api/${conn.bridgeAppId}/mcp", query: [access_token: conn.token],
                  requestContentType: "application/json", textParser: true, body: JsonOutput.toJson(body), timeout: 30]
    String text = null
    httpPost(params) { resp -> text = readText(resp) }
    return text ? new JsonSlurper().parseText(text) : null
}

// ======================================================================================
//  Helpers
// ======================================================================================

private hubJson(String path, Map query) {
    def params = [uri: HUB, path: path, textParser: true, timeout: 30]
    if (query) params.query = query
    String text = null
    httpGet(params) { resp -> text = readText(resp) }
    return text ? new JsonSlurper().parseText(text) : null
}

private Map readAppCode(appTypeId) {
    def parsed = hubJson("/app/ajax/code", [id: appTypeId])
    if (!(parsed instanceof Map) || parsed.version == null) throw new IllegalStateException("Could not read app code ${appTypeId}")
    return parsed
}

private saveAppCode(appTypeId, String source, version) {
    def params = [uri: HUB, path: "/app/saveOrUpdateJson", requestContentType: "application/json", textParser: true,
                  body: JsonOutput.toJson([id: appTypeId as Integer, source: source, version: version]), timeout: 120]
    String text = null
    httpPost(params) { resp -> text = readText(resp) }
    if (!text) return null
    try { return new JsonSlurper().parseText(text) } catch (Exception e) { return text.take(500) }
}

private String readFile(String name) {
    def bytes = downloadHubFile(name)
    if (bytes == null) throw new IllegalStateException("File Manager file ${name} not found")
    return new String(bytes, "UTF-8")
}

private String readText(resp) {
    def d = resp?.data
    if (d == null) return null
    if (d instanceof CharSequence) return d.toString()
    return d.text
}

private String message(resp) {
    if (resp instanceof Map) return (resp.message ?: resp.errorMessage ?: resp).toString()
    return resp?.toString() ?: "no response"
}

private String fmt(t) {
    return t ? new Date(t as Long).format("yyyy-MM-dd HH:mm:ss", location.timeZone) : "never"
}

// state only persists reassigned top-level values, so job updates rebuild the map.
private void phase(String p, String msg) {
    atomicState.job = atomicState.job + [phase: p]
    step(msg)
}

private void step(String msg) {
    log.info "Dev Bridge Guardian: ${msg}"
    Map job = atomicState.job
    atomicState.job = job + [steps: (job.steps ?: []) + ["${new Date().format('HH:mm:ss', location.timeZone)} ${msg}".toString()]]
}

private void finish(String p, String msg) {
    atomicState.job = atomicState.job + [phase: p, message: msg, finished: now()]
    step(msg)
    if (p in ["failed", "rollback_failed"]) log.error "Dev Bridge Guardian: ${msg}"
    if (p in ["rolled_back", "rollback_failed"]) notify("Dev Bridge self-update ${p}: ${msg}")
    state.remove("req")
}
