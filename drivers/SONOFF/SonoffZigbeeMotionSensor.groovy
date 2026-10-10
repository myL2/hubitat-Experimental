/**
 * =====================  SONOFF Zigbee Motion Sensor (SNZB-03PR2)  =======================
 *
 *  DESCRIPTION:
 *  Dedicated driver for the SONOFF SNZB-03PR2 PIR motion sensor with light sensor. Parses
 *  everything known for it (Zigbee2MQTT definition + own probing):
 *    0x0406 occupancy (motion), detection duration 0x0010 (5-60 s; some firmware reports it
 *           byte-swapped as 0x3C00)
 *    0x0400 illuminance (ZCL log scale -> lux)
 *    0x0001 battery % (0x0021, half-percent)
 *    0xFC11 SONOFF private cluster: illuminance calibration offset 0x2018 (-1000..1000 lx)
 *  Unknown attributes are logged and kept in state.unknownAttributes; the
 *  discoverAttributes / readAttributes commands help to find out more.
 *
 *  The sensor sleeps (it polls for messages only every 27 min when idle). Commands
 *  (Configure, Refresh, getInfo, Identify, diagnostics, firmware update) are queued and sent
 *  the moment it next sends anything: motion, or its hourly Poll Control check-in, which
 *  the driver answers with "fast poll for 10 s". Detection duration and illuminance offset
 *  are queued on Save, written the same way, read back, and kept queued until confirmed.
 *
 * =======================================================================================
 *
 *  Changelog:
 *
 *  v1.0.0 (2026-10-09) - First release: queued commands, Poll Control check-in (fast poll), identify, battery voltage
 *
 */

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

@Field static final String DRIVER_VERSION = "1.0.0"
@Field static final int FC11 = 0xFC11
@Field static final String FAST_POLL_TIMEOUT = "2800"   // check-in response: fast poll for 40 quarter-seconds (10 s), LE
@Field static final int QUEUE_MAX = 200
@Field static final long PENDING_RETRY_MS = 30000     // resend queued settings at most this often

// ZCL data type -> length in bytes (0 = length-prefixed string)
@Field static final Map ZCL_TYPE_LEN = [
    0x08: 1, 0x09: 2, 0x0A: 3, 0x0B: 4, 0x10: 1, 0x18: 1, 0x19: 2, 0x1A: 3, 0x1B: 4,
    0x20: 1, 0x21: 2, 0x22: 3, 0x23: 4, 0x24: 5, 0x25: 6, 0x26: 7, 0x27: 8,
    0x28: 1, 0x29: 2, 0x2A: 3, 0x2B: 4, 0x2C: 5, 0x2D: 6, 0x2E: 7, 0x2F: 8,
    0x30: 1, 0x31: 2, 0x38: 2, 0x39: 4, 0x3A: 8, 0x41: 0, 0x42: 0,
    0xE0: 4, 0xE1: 4, 0xE2: 4, 0xE8: 2, 0xE9: 2, 0xEA: 4, 0xF0: 8, 0xF1: 16
]
@Field static final List SIGNED_TYPES = [0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F]

metadata {
    definition(name: "SONOFF Zigbee Motion Sensor", namespace: "myL2", author: "myL2",
               importUrl: "https://raw.githubusercontent.com/myL2/hubitat-Experimental/main/drivers/SONOFF/SonoffZigbeeMotionSensor.groovy",
               singleThreaded: true) {
        capability "Sensor"
        capability "MotionSensor"
        capability "IlluminanceMeasurement"
        capability "Battery"
        capability "Refresh"
        capability "Configuration"

        attribute "detectionDuration", "number"
        attribute "batteryVoltage", "number"
        attribute "illuminanceOffset", "number"
        attribute "healthStatus", "enum", ["online", "offline"]

        command "readAttributes", [[name: "cluster", type: "STRING", description: "🔎 Diagnostic: hex cluster, e.g. FC11 (queued until the sensor wakes)"],
                                   [name: "attributes", type: "STRING", description: "comma-separated hex attribute ids, e.g. 2018"],
                                   [name: "mfgCode", type: "STRING", description: "manufacturer code (hex, e.g. 1286), empty = none"]]
        command "identify", [[name: "seconds", type: "NUMBER", description: "Blink the sensor LED for this many seconds (sent when the sensor next wakes)"]]
        command "updateFirmware"
        command "getInfo", [[name: "Read basic info and the endpoint's cluster lists into Device Data (like the generic Device driver)"]]
        command "discoverAttributes", [[name: "cluster", type: "STRING", description: "🔎 Diagnostic: list the attributes a cluster has (hex, e.g. FC11)"],
                                       [name: "mfgCode", type: "STRING", description: "manufacturer code (hex, e.g. 1286), empty = none"]]

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0001,0003,0020,0406,0400,FC57,FC11", outClusters: "0003,0019", model: "SNZB-03PR2", manufacturer: "SONOFF", deviceJoinName: "SONOFF Motion Sensor"
    }
    preferences {
        input name: "detectionDurationPref", type: "number", title: "Detection duration (s)", description: "How long motion stays active after the last movement, 5 - 60 s (written when the sensor next wakes)", range: "5..60"
        input name: "illuminanceOffsetPref", type: "number", title: "Illuminance offset (lx)", description: "Light calibration offset, -1000 - 1000 lx (written when the sensor next wakes)", range: "-1000..1000"
        input name: "offlineHours", type: "number", title: "Offline after (hours)", description: "healthStatus goes offline after this long without any message (the sensor checks in every hour)", defaultValue: 3, range: "2..48"
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

// ======================================================================================
//  Lifecycle
// ======================================================================================

def installed() {
    log.info "${device.displayName} installed (v${DRIVER_VERSION})"
    runIn(2, "configure")
}

def updated() {
    log.info "${device.displayName} updated (v${DRIVER_VERSION})"
    unschedule()
    runEvery1Hour("healthCheck")
    if (logEnable) runIn(1800, "logsOff")
    Map pending = [:]
    if (detectionDurationPref != null && device.currentValue("detectionDuration")?.toString() != detectionDurationPref.toString()) {
        pending.detectionDuration = detectionDurationPref as Integer
    }
    if (illuminanceOffsetPref != null && device.currentValue("illuminanceOffset")?.toString() != illuminanceOffsetPref.toString()) {
        pending.illuminanceOffset = illuminanceOffsetPref as Integer
    }
    state.pending = pending
    state.remove("pendingSentAt")
    if (pending) {
        log.info "${device.displayName}: ${pending} queued - written when the sensor next wakes (motion, or its hourly check-in)"
        sendPending()
    }
}

def logsOff() { device.updateSetting("logEnable", [value: "false", type: "bool"]) }

def configure() {
    log.info "${device.displayName} configure"
    unschedule()                                 // also clears jobs left by a previous driver (e.g. after Swap Devices)
    runEvery1Hour("healthCheck")
    if (logEnable) runIn(1800, "logsOff")
    String dni = device.deviceNetworkId
    List<String> cmds = []
    [0x0001, 0x0020, 0x0400, 0x0406, FC11].each { cl ->
        cmds += "zdo bind 0x${dni} 0x01 0x01 0x${hex4(cl)} {${device.zigbeeId}} {}"
        cmds += "delay 200"
    }
    // battery % every 1-2 h or on a 1 % change; occupancy and illuminance are reported by the sensor itself
    cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 3600, 7200, 2, [:], 200)
    cmds += refreshCmds()
    queueForWake(cmds, "Configure")
}

def refresh() {
    queueForWake(refreshCmds(), "Refresh")
}

private List<String> refreshCmds() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0406, [0x0000, 0x0010], [:], 200)
    cmds += zigbee.readAttribute(0x0400, [0x0000], [:], 200)
    cmds += zigbee.readAttribute(0x0001, [0x0020, 0x0021], [:], 200)
    cmds += zigbee.readAttribute(FC11, [0x2018], [:], 200)
    return cmds
}

// Writes the queued settings (and reads them back). Called on Save and whenever the sensor
// sends something, i.e. while it is awake; entries are removed once the read-back matches.
private void sendPending() {
    Map pending = state.pending ?: [:]
    if (!pending) return
    long last = (state.pendingSentAt ?: 0L) as Long
    if (now() - last < PENDING_RETRY_MS) return
    state.pendingSentAt = now()
    List<String> cmds = []
    if (pending.detectionDuration != null) {
        cmds += zigbee.writeAttribute(0x0406, 0x0010, DataType.UINT16, pending.detectionDuration as Integer, [:], 200)
        cmds += zigbee.readAttribute(0x0406, 0x0010, [:], 200)
    }
    if (pending.illuminanceOffset != null) {
        cmds += zigbee.writeAttribute(FC11, 0x2018, DataType.INT16, (pending.illuminanceOffset as Integer) & 0xFFFF, [:], 200)
        cmds += zigbee.readAttribute(FC11, 0x2018, [:], 200)
    }
    logDebug "sending queued settings ${pending}"
    sendZigbeeCommands(cmds)
}

// Show the sensor's value in the preference, unless the user changed it and the new value
// is still queued (then the preference holds what will be written).
private void syncPreference(String pref, String key, value) {
    if ((state.pending ?: [:]).containsKey(key)) return
    if (settings[pref]?.toString() == value?.toString()) return
    device.updateSetting(pref, [value: value, type: "number"])
    logDebug "preference ${pref} set to the sensor's ${value}"
}

private void confirmPending(String key, value) {
    Map pending = state.pending ?: [:]
    if (pending.containsKey(key) && pending[key]?.toString() == value?.toString()) {
        pending.remove(key)
        state.pending = pending
        log.info "${device.displayName}: ${key} ${value} confirmed by the sensor"
    }
}

def readAttributes(String cluster, String attributes, String mfgCode = null) {
    Integer cl = hexInt(cluster)
    List<Integer> attrs = attributes.split("[,\\s]+").findAll { it }.collect { hexInt(it) }
    Map opts = mfgCode?.trim() ? [mfgCode: "0x${mfgCode.trim().replace('0x', '')}"] : [:]
    log.info "${device.displayName} readAttributes: cluster 0x${hex4(cl)} attributes ${attrs.collect { hex4(it) }}${opts ? ' mfgCode ' + opts.mfgCode : ''}"
    queueForWake(zigbee.readAttribute(cl, attrs, opts, 200), "readAttributes 0x${hex4(cl)}")
}

// Asks Hubitat's firmware repository for an OTA image; the sensor only listens right after it wakes.
// Identify (cluster 0x0003, command 0x00): blink the LED for 'seconds'
def identify(BigDecimal seconds = 5) {
    int t = Math.max(1, Math.min(60, (seconds ?: 5) as int))
    queueForWake(zigbee.command(0x0003, 0x00, hex2(t & 0xFF) + hex2(t >> 8)), "Identify ${t} s")
}

def updateFirmware() {
    log.info "${device.displayName}: checking for a firmware update (Hubitat firmware repository)"
    queueForWake(zigbee.updateFirmware(), "updateFirmware")
}

// Like the generic Device driver's getInfo: basic cluster attributes + ZDO Active Endpoints (0x0005)
// and Simple Descriptor (0x0004) requests; responses fill Device Data (inClusters, outClusters, ...).
def getInfo() {
    log.info "${device.displayName} getInfo"
    String dni = device.deviceNetworkId
    String nwk = dni.substring(2, 4) + " " + dni.substring(0, 2)
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0000, [0x0000, 0x0001, 0x0002, 0x0003, 0x0004, 0x0005], [:], 200)
    cmds += zigbee.readAttribute(0x0000, [0x0006, 0x0007, 0x4000], [:], 200)
    cmds += "he raw 0x${dni} 0 0 0x0005 {00 ${nwk}} {0x0000}"
    cmds += "delay 200"
    cmds += "he raw 0x${dni} 0 0 0x0004 {00 ${nwk} 01} {0x0000}"
    queueForWake(cmds, "getInfo")
}

// ZDO responses: 0x8005 Active Endpoints, 0x8004 Simple Descriptor (little-endian fields)
private void parseZdo(Map msg) {
    List<String> d = msg.data ?: []
    if (msg.clusterId == "8005" && d.size() >= 5 && d[1] == "00") {
        int n = Integer.parseInt(d[4], 16)
        List<String> eps = (0..<n).collect { d[5 + it] }.findAll { it }
        log.info "${device.displayName} getInfo: endpoints ${eps}"
        return
    }
    if (msg.clusterId == "8004" && d.size() >= 12 && d[1] == "00") {
        String ep = d[5]
        String profile = d[7] + d[6]
        String deviceId = d[9] + d[8]
        int i = 11
        int inCount = Integer.parseInt(d[i++], 16)
        List<String> inCl = (0..<inCount).collect { int k -> d[i + k * 2 + 1] + d[i + k * 2] }
        i += inCount * 2
        int outCount = i < d.size() ? Integer.parseInt(d[i++], 16) : 0
        List<String> outCl = (0..<outCount).collect { int k -> d[i + k * 2 + 1] + d[i + k * 2] }
        device.updateDataValue("endpointId", ep)
        device.updateDataValue("inClusters", inCl.join(","))
        device.updateDataValue("outClusters", outCl.join(","))
        log.info "${device.displayName} getInfo: endpoint ${ep} profile 0x${profile} device type 0x${deviceId} in ${inCl.join(',')} out ${outCl.join(',')}"
        log.info "${device.displayName} fingerprint profileId:\"${profile}\", endpointId:\"${ep}\", inClusters:\"${inCl.join(',')}\", outClusters:\"${outCl.join(',')}\", model:\"${device.getDataValue('model')}\", manufacturer:\"${device.getDataValue('manufacturer')}\""
    }
}

// ZCL Discover Attributes (0x0C) from attribute 0x0000, up to 0xFF attributes
def discoverAttributes(String cluster, String mfgCode = null) {
    Integer cl = hexInt(cluster)
    String mfg = mfgCode?.trim() ? mfgCode.trim().replace("0x", "").padLeft(4, "0") : null
    Integer seq = (((state.zclSeq ?: 0) as Integer) + 1) % 256
    state.zclSeq = seq
    String frame = mfg ? "04 ${mfg.substring(2, 4)} ${mfg.substring(0, 2)} ${hex2(seq)} 0C 00 00 FF" : "00 ${hex2(seq)} 0C 00 00 FF"
    log.info "${device.displayName} discoverAttributes: cluster 0x${hex4(cl)}${mfg ? ' mfgCode 0x' + mfg : ''}"
    queueForWake(["he raw 0x${device.deviceNetworkId} 1 0x01 0x${hex4(cl)} {${frame}}"], "discoverAttributes 0x${hex4(cl)}")
}

// ======================================================================================
//  Sleepy device: wake queue and Poll Control check-in
// ======================================================================================

// Commands wait here until the sensor is awake (any message from it, or its check-in).
private void queueForWake(List<String> cmds, String what) {
    List<String> q = (state.wakeQueue ?: []) as List<String>
    q.addAll(cmds)
    if (q.size() > QUEUE_MAX) q = q.takeRight(QUEUE_MAX)
    state.wakeQueue = q
    state.wakeQueueWhat = (((state.wakeQueueWhat ?: []) as List) + what).unique()
    log.info "${device.displayName}: ${what} queued - sent when the sensor next wakes (motion, or its hourly check-in)"
}

private void flushWakeQueue(String reason) {
    List<String> q = (state.wakeQueue ?: []) as List<String>
    if (q) {
        log.info "${device.displayName}: sensor awake (${reason}) - sending ${state.wakeQueueWhat}"
        state.remove("wakeQueue")
        state.remove("wakeQueueWhat")
        sendZigbeeCommands(q)
    }
    sendPending()
}

// Poll Control check-in (cluster 0x0020, command 0x00): answer "start fast polling" so the
// sensor listens for the next 10 s, send what is queued, read the battery, then stop fast polling.
private void handleCheckIn() {
    logDebug "Poll Control check-in"
    List<String> cmds = []
    cmds += zigbee.command(0x0020, 0x00, "01${FAST_POLL_TIMEOUT}")
    cmds += zigbee.readAttribute(0x0001, [0x0020, 0x0021], [:], 200)
    sendZigbeeCommands(cmds)
    flushWakeQueue("check-in")
    sendZigbeeCommands(["delay 3000"] + zigbee.command(0x0020, 0x01, ""))   // Fast Poll Stop
}

// ======================================================================================
//  Parsing
// ======================================================================================

def parse(String description) {
    logDebug "parse: ${description}"
    state.lastRx = now()
    if (device.currentValue("healthStatus") != "online") sendEvent(name: "healthStatus", value: "online", descriptionText: "${device.displayName} is online")

    Map msg = description.startsWith("catchall:") ? zigbee.parseDescriptionAsMap(description) : null
    if (msg?.clusterId == "0020" && msg?.command == "00" && msg?.isClusterSpecific) {
        handleCheckIn()
        return null
    }
    if (description.startsWith("read attr -")) {
        parseReadAttr(description)
    } else if (msg) {
        parseCatchall(msg)
    } else {
        logDebug "unhandled description: ${description}"
    }
    flushWakeQueue("message")           // the sensor is awake now
    return null
}

// "read attr - raw: <dni><ep><cluster><size><records>, ..., command: 01|0A, ..."
// Records: attrId (LE) [status, only for 2nd+ record of a read response] type value.
private void parseReadAttr(String description) {
    def mRaw = description =~ /raw: ([0-9A-Fa-f]+)/
    def mCmd = description =~ /command: ([0-9A-Fa-f]{2})/
    if (!mRaw.find()) return
    String raw = mRaw.group(1).toUpperCase()
    boolean readResponse = mCmd.find() && mCmd.group(1) == "01"
    Integer cluster = Integer.parseInt(raw.substring(6, 10), 16)
    String body = raw.substring(12)
    int i = 0
    boolean first = true
    while (i + 4 <= body.length()) {
        Integer attr = Integer.parseInt(body.substring(i + 2, i + 4) + body.substring(i, i + 2), 16)
        i += 4
        if (readResponse && !first) {
            Integer status = Integer.parseInt(body.substring(i, i + 2), 16)
            i += 2
            if (status != 0) {
                logDebug "read 0x${hex4(cluster)}:0x${hex4(attr)} failed, status 0x${hex2(status)}"
                first = false
                continue
            }
        }
        first = false
        if (i + 2 > body.length()) break
        Integer type = Integer.parseInt(body.substring(i, i + 2), 16)
        i += 2
        Integer len = ZCL_TYPE_LEN[type]
        if (len == null) {
            logInfoUnknown("0x${hex4(cluster)}:0x${hex4(attr)}", "unsupported type 0x${hex2(type)}, rest ${body.substring(i)}")
            break
        }
        if (len == 0) {                                   // octet / character string: length prefix
            len = Integer.parseInt(body.substring(i, i + 2), 16)
            i += 2
        }
        if (i + len * 2 > body.length()) break
        String valueLe = body.substring(i, i + len * 2)
        i += len * 2
        handleAttribute(cluster, attr, type, valueLe)
    }
}

private void parseCatchall(Map msg) {
    if (msg.profileId == "0000") { parseZdo(msg); return }               // ZDO
    String cl = msg.clusterId
    if (msg.command == "0D" && !msg.isClusterSpecific) {                 // Discover Attributes response
        List<String> d = msg.data ?: []
        List<String> found = []
        for (int i = 1; i + 2 < d.size(); i += 3) {
            found << "0x${d[i + 1]}${d[i]} (type 0x${d[i + 2]})"
        }
        log.info "${device.displayName} discoverAttributes 0x${cl}: ${found ? found.join(', ') : 'none'}${d && d[0] == '00' ? ' (more: discover again)' : ''}"
        state.discovered = (state.discovered ?: [:]) + [(cl): found]
        return
    }
    if (cl == "0003" && msg.isClusterSpecific) { logDebug "identify from the sensor (sent when it wakes up)"; return }
    if (msg.command == "07") { logDebug "configure reporting response 0x${cl}: ${msg.data}"; return }
    if (msg.command == "04") {
        List<String> d = msg.data ?: []
        if (d && d[0] != "00") log.warn "${device.displayName} write to cluster 0x${cl} refused, status 0x${d[0]}${d.size() >= 3 ? ' attribute 0x' + d[2] + d[1] : ''}"
        else logDebug "write attribute response 0x${cl}: ${msg.data}"
        return
    }
    if (msg.command == "0B") { logDebug "default response 0x${cl}: ${msg.data}"; return }
    logDebug "unhandled catchall: ${msg}"
}

// ======================================================================================
//  Attributes
// ======================================================================================

private void handleAttribute(Integer cluster, Integer attr, Integer type, String valueLe) {
    String key = "0x${hex4(cluster)}:0x${hex4(attr)}"
    Long v = (type in [0x41, 0x42]) ? null : leToLong(valueLe, SIGNED_TYPES.contains(type))
    switch (cluster) {
        case 0x0406:
            if (attr == 0x0000) { sendEventLog("motion", (v & 0x01) ? "active" : "inactive", null, null, "physical"); return }
            if (attr == 0x0010 || attr == 0x3C00) {     // 0x3C00 = 0x0010 byte-swapped (firmware quirk)
                sendEventLog("detectionDuration", v, "s")
                confirmPending("detectionDuration", v)
                syncPreference("detectionDurationPref", "detectionDuration", v)
                return
            }
            if (attr == 0x0001) { logDebug "occupancy sensor type ${v}"; return }
            if (attr == 0x0002) { logDebug "occupancy sensor type bitmap ${v}"; return }
            break
        case 0x0400:
            if (attr == 0x0000) {
                // ZCL: MeasuredValue = 10000 * log10(lux) + 1; 0 = too dark to measure
                Integer lux = v > 0 ? Math.round(Math.pow(10, (v - 1) / 10000.0)) as Integer : 0
                sendEventLog("illuminance", lux, "lx")
                return
            }
            if (attr in [0x0001, 0x0002, 0x0003, 0x0004]) { logDebug "illuminance attribute 0x${hex4(attr)} = ${v}"; return }
            break
        case 0x0001:
            if (attr == 0x0021) {
                Integer pct = Math.min(100, Math.round(v / 2.0f) as Integer)
                sendEventLog("battery", pct, "%")
                return
            }
            if (attr == 0x0020) { sendEventLog("batteryVoltage", (v / 10.0).setScale(1, BigDecimal.ROUND_HALF_UP), "V"); return }
            break
        case FC11:
            if (attr == 0x2018) {
                sendEventLog("illuminanceOffset", v, "lx")
                confirmPending("illuminanceOffset", v)
                syncPreference("illuminanceOffsetPref", "illuminanceOffset", v)
                return
            }
            break
        case 0x0020:     // Poll Control, quarter-seconds
            if (attr in [0x0000, 0x0001, 0x0002, 0x0003]) {
                String n = [0: "checkInInterval", 1: "longPollInterval", 2: "shortPollInterval", 3: "fastPollTimeout"][attr]
                state[n] = "${v / 4} s"
                logDebug "poll control ${n} = ${v / 4} s"
                return
            }
            break
        case 0x0000:
            if (attr == 0x0001) { device.updateDataValue("application", v.toString()); return }
            if (attr == 0x4000) { device.updateDataValue("softwareBuild", hexToAscii(valueLe)); return }
            if (attr == 0x0004) { device.updateDataValue("manufacturer", hexToAscii(valueLe)); return }
            if (attr == 0x0005) { device.updateDataValue("model", hexToAscii(valueLe)); return }
            if (attr == 0x0000) { device.updateDataValue("zclVersion", v.toString()); return }
            if (attr == 0x0002) { device.updateDataValue("stackVersion", v.toString()); return }
            if (attr == 0x0003) { device.updateDataValue("hwVersion", v.toString()); return }
            if (attr == 0x0006) { device.updateDataValue("dateCode", hexToAscii(valueLe)); return }
            if (attr == 0x0007) { device.updateDataValue("powerSource", ["0": "unknown", "1": "mains", "2": "mains 3-phase", "3": "battery", "4": "dc"][v.toString()] ?: v.toString()); return }
            break
    }
    logInfoUnknown(key, "type 0x${hex2(type)} value ${v != null ? v : valueLe}")
}

def healthCheck() {
    long limit = ((offlineHours ?: 3) as Long) * 3600000L
    if (now() - ((state.lastRx ?: 0L) as Long) > limit && device.currentValue("healthStatus") != "offline") {
        sendEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName} is offline")
        log.warn "${device.displayName} is offline (no messages for ${offlineHours ?: 3} h)"
    }
}

// ======================================================================================
//  Helpers
// ======================================================================================

private void sendEventLog(String name, value, String unit, String text = null, String type = null) {
    String desc = "${device.displayName} ${text ?: name + ' is ' + value + (unit ? ' ' + unit : '')}"
    if (device.currentValue(name)?.toString() == value?.toString()) { logDebug "${desc} (no change)"; return }
    Map evt = [name: name, value: value, descriptionText: desc]
    if (unit) evt.unit = unit
    if (type) evt.type = type
    sendEvent(evt)
    if (txtEnable) log.info desc
}

private void logInfoUnknown(String key, String detail) {
    log.info "${device.displayName} unknown attribute ${key}: ${detail}"
    state.unknownAttributes = (state.unknownAttributes ?: [:]) + [(key): detail]
}

private static Long leToLong(String hexLe, boolean signed) {
    String be = (0..<hexLe.length().intdiv(2)).collect { hexLe.substring(it * 2, it * 2 + 2) }.reverse().join()
    if (!be) return 0L
    BigInteger n = new BigInteger(be, 16)
    if (signed && n.testBit(be.length() * 4 - 1)) n = n - BigInteger.ONE.shiftLeft(be.length() * 4)
    return n.longValue()
}

private static String hexToAscii(String hex) {
    return (0..<hex.length().intdiv(2)).collect { (char) Integer.parseInt(hex.substring(it * 2, it * 2 + 2), 16) }.join()
}

private static Integer hexInt(String s) { Integer.parseInt(s.trim().replace("0x", ""), 16) }
private static String hex2(Integer v) { String.format("%02X", v & 0xFF) }
private static String hex4(Integer v) { String.format("%04X", v & 0xFFFF) }

private void sendZigbeeCommands(List<String> cmds) {
    if (!cmds) return
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

private void logDebug(msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}
