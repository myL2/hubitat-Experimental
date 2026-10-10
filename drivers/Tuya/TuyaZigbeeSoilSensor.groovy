/**
 * =========================  Tuya Zigbee Soil Sensor  =====================================
 *
 *  DESCRIPTION:
 *  Soil moisture sensors using Tuya data points (cluster 0xEF00). The data point map is
 *  chosen by model (Zigbee2MQTT definitions):
 *
 *  HOBEIAN ZG-303Z (same map as the COOLO CS-201Z):
 *    DP 3 soil moisture %, DP 5 temperature (x10), DP 109 air humidity %, DP 15 battery %,
 *    DP 106 dry alarm, DP 9 temperature unit, DP 104 temperature calibration (x10),
 *    DP 105 humidity calibration, DP 102 soil calibration, DP 111 temperature sampling (s),
 *    DP 112 soil sampling (s), DP 110 soil dryness warning threshold (%)
 *    Temperature also arrives on 0x0402, soil moisture on 0x0405 (not air humidity), battery on 0x0001.
 *  Tuya TS0601 soil sensor, sold as QT-07S (_TZE200/_TZE204/_TZE284_myd45weu and others):
 *    DP 3 soil moisture %, DP 5 temperature (whole degrees), DP 9 temperature unit,
 *    DP 14 battery state (low / middle / high), DP 15 battery %
 *
 *  Unknown data points and attributes are logged and kept in state.unknownAttributes.
 *
 *  The sensor sleeps between reports. Settings are queued on Save and sent the next time it
 *  reports (every sampling interval) or its button is pressed, and stay queued until the
 *  sensor reports the new value back. Refresh, Configure and getInfo are queued the same way.
 *
 * =======================================================================================
 *
 *  Changelog:
 *
 *  v1.0.0 (2026-10-10) - First release: HOBEIAN ZG-303Z and Tuya TS0601 soil sensors
 *
 */

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

@Field static final String DRIVER_VERSION = "1.0.0"
@Field static final int QUEUE_MAX = 200
@Field static final long PENDING_RETRY_MS = 30000     // resend queued settings at most this often

// Tuya DP types
@Field static final int DP_RAW = 0x00
@Field static final int DP_BOOL = 0x01
@Field static final int DP_VALUE = 0x02
@Field static final int DP_STRING = 0x03
@Field static final int DP_ENUM = 0x04
@Field static final int DP_BITMAP = 0x05

// Writable settings: preference -> [dp, type, scale, attribute]
@Field static final Map SETTINGS = [
    temperatureUnit       : [dp: 9,   type: 0x04, scale: 1,  attr: "temperatureUnit"],
    temperatureCalibration: [dp: 104, type: 0x02, scale: 10, attr: "temperatureCalibration"],
    humidityCalibration   : [dp: 105, type: 0x02, scale: 1,  attr: "humidityCalibration"],
    soilCalibration       : [dp: 102, type: 0x02, scale: 1,  attr: "soilCalibration"],
    temperatureSampling   : [dp: 111, type: 0x02, scale: 1,  attr: "temperatureSampling"],
    soilSampling          : [dp: 112, type: 0x02, scale: 1,  attr: "soilSampling"],
    soilWarning           : [dp: 110, type: 0x02, scale: 1,  attr: "soilWarning"]
]

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
    definition(name: "Tuya Zigbee Soil Sensor", namespace: "myL2", author: "myL2",
               importUrl: "https://raw.githubusercontent.com/myL2/hubitat-Experimental/main/drivers/Tuya/TuyaZigbeeSoilSensor.groovy",
               singleThreaded: true) {
        capability "Sensor"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Battery"
        capability "Refresh"
        capability "Configuration"

        attribute "soilMoisture", "number"
        attribute "soilDry", "enum", ["false", "true"]
        attribute "batteryVoltage", "number"
        attribute "batteryState", "enum", ["low", "middle", "high"]
        attribute "temperatureUnit", "enum", ["celsius", "fahrenheit"]
        attribute "temperatureCalibration", "number"
        attribute "humidityCalibration", "number"
        attribute "soilCalibration", "number"
        attribute "temperatureSampling", "number"
        attribute "soilSampling", "number"
        attribute "soilWarning", "number"
        attribute "healthStatus", "enum", ["online", "offline"]

        command "getInfo", [[name: "Read basic info and the endpoint's cluster lists into Device Data (like the generic Device driver)"]]

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,EF00,0402,0405,0001", outClusters: "0003", model: "ZG-303Z", manufacturer: "HOBEIAN", deviceJoinName: "HOBEIAN Soil Sensor"
        ["_TZE200_myd45weu", "_TZE204_myd45weu", "_TZE284_myd45weu", "_TZE200_ga1maeof", "_TZE200_2se8efxh", "_TZE284_2se8efxh",
         "_TZE284_oitavov2", "_TZE284_2nhqasjh", "_TZE200_9cqcpkgb"].each { mfr ->
            fingerprint profileId: "0104", endpointId: "01", inClusters: "0004,0005,EF00,0000,ED00", outClusters: "0019,000A", model: "TS0601", manufacturer: mfr, deviceJoinName: "Tuya QT-07S Soil Sensor"
        }
    }
    preferences {
        if (!isTs0601()) {
        input name: "soilWarningPref", type: "number", title: "Soil dryness warning (%)", description: "The sensor raises its dry alarm below this soil moisture, 0 - 100 %", range: "0..100"
        input name: "soilSamplingPref", type: "number", title: "Soil sampling interval (s)", description: "5 - 3600 s; longer saves battery", range: "5..3600"
        input name: "temperatureSamplingPref", type: "number", title: "Temperature / humidity sampling interval (s)", description: "5 - 3600 s; longer saves battery", range: "5..3600"
        input name: "soilCalibrationPref", type: "number", title: "Soil moisture calibration (%)", description: "-30 - 30 %", range: "-30..30"
        input name: "temperatureCalibrationPref", type: "decimal", title: "Temperature calibration (°C)", description: "-2.0 - 2.0 °C", range: "-2..2"
        input name: "humidityCalibrationPref", type: "number", title: "Humidity calibration (%)", description: "-30 - 30 %", range: "-30..30"
        }
        input name: "temperatureUnitPref", type: "enum", title: "Temperature unit on the sensor", options: ["celsius", "fahrenheit"]
        input name: "offlineHours", type: "number", title: "Offline after (hours)", description: "healthStatus goes offline after this long without any message", defaultValue: 2, range: "1..48"
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

// TS0601 soil sensors use a smaller data point map than the HOBEIAN ZG-303Z
private boolean isTs0601() {
    return device?.getDataValue("model") == "TS0601"
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
    SETTINGS.each { String key, Map s ->
        if (isTs0601() && key != "temperatureUnit") return     // the TS0601 only has the temperature unit
        def pref = settings["${key}Pref"]
        if (pref == null) return
        def current = device.currentValue(s.attr)
        if (current?.toString() != pref.toString() && !(current != null && pref instanceof Number && (current as BigDecimal) == (pref as BigDecimal))) {
            pending[key] = (key == "temperatureUnit") ? pref : (pref as BigDecimal)
        }
    }
    state.pending = pending
    state.remove("pendingSentAt")
    if (pending) {
        log.info "${device.displayName}: ${pending} queued - written when the sensor next reports"
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
    // Tuya "magic" read of the basic cluster: some Tuya devices only start reporting after it
    cmds += zigbee.readAttribute(0x0000, [0x0004, 0x0000, 0x0001, 0x0005, 0x0007, 0xFFFE], [:], 200)
    (isTs0601() ? [0xEF00] : [0x0001, 0x0402, 0x0405, 0xEF00]).each { cl ->
        cmds += "zdo bind 0x${dni} 0x01 0x01 0x${hex4(cl)} {${device.zigbeeId}} {}"
        cmds += "delay 200"
    }
    cmds += refreshCmds()
    queueForWake(cmds, "Configure")
}

def refresh() {
    queueForWake(refreshCmds(), "Refresh")
}

// Tuya "data query" (0x03): the sensor reports all its data points
private List<String> refreshCmds() {
    List<String> cmds = zigbee.command(0xEF00, 0x03, "")
    if (!isTs0601()) cmds += zigbee.readAttribute(0x0001, [0x0020, 0x0021], [:], 200)
    return cmds
}

// Commands wait here until the sensor is awake (any message from it, or its check-in).
private void queueForWake(List<String> cmds, String what) {
    List<String> q = (state.wakeQueue ?: []) as List<String>
    q.addAll(cmds)
    if (q.size() > QUEUE_MAX) q = q.takeRight(QUEUE_MAX)
    state.wakeQueue = q
    state.wakeQueueWhat = (((state.wakeQueueWhat ?: []) as List) + what).unique()
    log.info "${device.displayName}: ${what} queued - sent when the sensor next wakes (next report or a button press)"
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


// ======================================================================================
//  Settings (Tuya data points)
// ======================================================================================

// Writes the queued settings; entries are removed once the sensor reports the new value.
private void sendPending() {
    Map pending = state.pending ?: [:]
    if (!pending) return
    long last = (state.pendingSentAt ?: 0L) as Long
    if (now() - last < PENDING_RETRY_MS) return
    state.pendingSentAt = now()
    List<String> cmds = []
    pending.each { String key, value ->
        Map s = SETTINGS[key]
        if (!s) return
        if (key == "temperatureUnit") {
            cmds += tuyaSetDp(s.dp as int, DP_ENUM, value == "fahrenheit" ? 1 : 0)
        } else {
            cmds += tuyaSetDp(s.dp as int, s.type as int, Math.round((value as BigDecimal) * (s.scale as int)) as int)
        }
    }
    logDebug "sending queued settings ${pending}"
    sendZigbeeCommands(cmds)
}

private void confirmPending(String key, value) {
    Map pending = state.pending ?: [:]
    if (!pending.containsKey(key)) return
    def want = pending[key]
    boolean same = (want instanceof Number || want instanceof BigDecimal) ? ((value as BigDecimal) == (want as BigDecimal)) : (value?.toString() == want?.toString())
    if (same) {
        pending.remove(key)
        state.pending = pending
        log.info "${device.displayName}: ${key} ${value} confirmed by the sensor"
    }
}

// Tuya "set data point" (0x00): seq(2) dp type len(2, BE) value(BE)
private List<String> tuyaSetDp(int dp, int type, int value) {
    int len = (type == DP_VALUE) ? 4 : 1
    String v = (type == DP_VALUE) ? String.format("%08X", value & 0xFFFFFFFFL) : hex2(value)
    String payload = nextTuyaSeq() + hex2(dp) + hex2(type) + String.format("%04X", len) + v
    return zigbee.command(0xEF00, 0x00, payload)
}

private String nextTuyaSeq() {
    int seq = (((state.tuyaSeq ?: 0) as int) + 1) % 0x10000
    state.tuyaSeq = seq
    return String.format("%04X", seq)
}

// ======================================================================================
//  Parsing
// ======================================================================================

def parse(String description) {
    logDebug "parse: ${description}"
    state.lastRx = now()
    if (device.currentValue("healthStatus") != "online") sendEvent(name: "healthStatus", value: "online", descriptionText: "${device.displayName} is online")

    if (description.startsWith("read attr -")) {
        parseReadAttr(description)
    } else if (description.startsWith("catchall:")) {
        parseCatchall(zigbee.parseDescriptionAsMap(description))
    } else {
        logDebug "unhandled description: ${description}"
    }
    flushWakeQueue("message")           // the sensor is awake now
    return null
}

private void parseCatchall(Map msg) {
    if (msg.profileId == "0000") { parseZdo(msg); return }               // ZDO
    String cl = msg.clusterId
    if (cl == "EF00") { parseTuya(msg); return }
    if (msg.command == "07") { logDebug "configure reporting response 0x${cl}: ${msg.data}"; return }
    if (msg.command == "04") { logDebug "write attribute response 0x${cl}: ${msg.data}"; return }
    if (msg.command == "0B") { logDebug "default response 0x${cl}: ${msg.data}"; return }
    logDebug "unhandled catchall: ${msg}"
}

// Tuya cluster: 0x01 data response / 0x02 data report / 0x06 active report: seq(2) then DP records;
// 0x24 time sync request; 0x0B default response
private void parseTuya(Map msg) {
    List<String> d = msg.data ?: []
    switch (msg.command) {
        case "01": case "02": case "06":
            int i = 2
            while (i + 4 <= d.size()) {
                int dp = Integer.parseInt(d[i], 16)
                int type = Integer.parseInt(d[i + 1], 16)
                int len = Integer.parseInt(d[i + 2] + d[i + 3], 16)
                if (i + 4 + len > d.size()) break
                List<String> val = d.subList(i + 4, i + 4 + len)
                long v = 0
                if (type in [DP_BOOL, DP_VALUE, DP_ENUM, DP_BITMAP]) {
                    val.each { v = (v << 8) | Integer.parseInt(it, 16) }
                    if (type == DP_VALUE && len == 4 && v > 0x7FFFFFFFL) v -= 0x100000000L
                }
                handleDp(dp, type, v, val.join())
                i += 4 + len
            }
            break
        case "24":                                       // time sync request
            sendTimeSync(d.size() >= 2 ? d[0] + d[1] : "0000")
            break
        case "0B":
            logDebug "Tuya default response: ${d}"
            break
        default:
            logDebug "Tuya command 0x${msg.command}: ${d}"
    }
}

// Time sync response: same seq, UTC and local seconds (uint32 BE each)
private void sendTimeSync(String seq) {
    long utc = (now() / 1000L) as long
    long local = utc + ((location.timeZone?.getOffset(now()) ?: 0) / 1000L) as long
    String payload = seq + String.format("%08X", utc) + String.format("%08X", local)
    logDebug "time sync response ${payload}"
    sendZigbeeCommands(zigbee.command(0xEF00, 0x24, payload))
}

private void handleDp(int dp, int type, long v, String hex) {
    switch (dp) {
        case 3:   sendEventLog("soilMoisture", v, "%"); return
        case 5:   sendTemperature(isTs0601() ? (v as BigDecimal) : v / 10.0); return
        case 14:
            if (isTs0601()) { sendEventLog("batteryState", ["low", "middle", "high"][v as int] ?: v.toString(), null); return }
            break
        case 109: sendEventLog("humidity", v, "%"); return
        case 15:  sendEventLog("battery", Math.min(100L, v), "%"); return
        case 106:
            String dry = v ? "true" : "false"
            sendEventLog("soilDry", dry, null, "soil dry alarm is ${dry}")
            if (v) log.warn "${device.displayName}: soil is dry (below ${device.currentValue('soilWarning') ?: '?'} %)"
            return
        case 9:
            String unit = v ? "fahrenheit" : "celsius"
            sendEventLog("temperatureUnit", unit, null); confirmPending("temperatureUnit", unit); syncPreference("temperatureUnit", unit); return
        case 104: settingEvent("temperatureCalibration", (v / 10.0).setScale(1, BigDecimal.ROUND_HALF_UP), "°C"); return
        case 105: settingEvent("humidityCalibration", v, "%"); return
        case 102: settingEvent("soilCalibration", v, "%"); return
        case 111: settingEvent("temperatureSampling", v, "s"); return
        case 112: settingEvent("soilSampling", v, "s"); return
        case 110: settingEvent("soilWarning", v, "%"); return
    }
    logInfoUnknown("DP ${dp}", "type 0x${hex2(type)} value ${type in [DP_RAW, DP_STRING] ? hex : v}")
}

private void settingEvent(String name, value, String unit) {
    sendEventLog(name, value, unit)
    confirmPending(name, value)
    syncPreference(name, value)
}

// Show the sensor's value in the preference, unless the user changed it and the new value
// is still queued (then the preference holds what will be written).
private void syncPreference(String key, value) {
    if ((state.pending ?: [:]).containsKey(key)) return
    String pref = "${key}Pref"
    if (settings[pref]?.toString() == value?.toString()) return
    String type = (key == "temperatureUnit") ? "enum" : (key == "temperatureCalibration" ? "decimal" : "number")
    device.updateSetting(pref, [value: value, type: type])
    logDebug "preference ${pref} set to the sensor's ${value}"
}

// Hub scale; the sensor reports in its own unit (DP 9)
private void sendTemperature(BigDecimal t) {
    boolean sensorF = device.currentValue("temperatureUnit") == "fahrenheit"
    BigDecimal c = sensorF ? (t - 32) * 5 / 9 : t
    BigDecimal out = location.temperatureScale == "F" ? c * 9 / 5 + 32 : c
    sendEventLog("temperature", out.setScale(1, BigDecimal.ROUND_HALF_UP), "°${location.temperatureScale}")
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

// ======================================================================================
//  Attributes (standard clusters)
// ======================================================================================

private void handleAttribute(Integer cluster, Integer attr, Integer type, String valueLe) {
    String key = "0x${hex4(cluster)}:0x${hex4(attr)}"
    Long v = (type in [0x41, 0x42]) ? null : leToLong(valueLe, SIGNED_TYPES.contains(type))
    switch (cluster) {
        case 0x0402:
            if (attr == 0x0000) {
                BigDecimal c = v / 100.0
                BigDecimal out = location.temperatureScale == "F" ? c * 9 / 5 + 32 : c
                sendEventLog("temperature", out.setScale(1, BigDecimal.ROUND_HALF_UP), "°${location.temperatureScale}")
                return
            }
            break
        case 0x0405:
            if (attr == 0x0000) {
                // ZG-303Z: the humidity cluster carries the SOIL moisture (matches DP 3 exactly); air humidity is DP 109
                sendEventLog("soilMoisture", Math.round(v / 100.0) as Integer, "%")
                return
            }
            break
        case 0x0001:
            if (attr == 0x0021) { sendEventLog("battery", Math.min(100, Math.round(v / 2.0f) as Integer), "%"); return }
            if (attr == 0x0020) { sendEventLog("batteryVoltage", (v / 10.0).setScale(1, BigDecimal.ROUND_HALF_UP), "V"); return }
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
            if (attr == 0xFFFE) { logDebug "attribute reporting status ${v}"; return }
            // Tuya heartbeat bundle (sent with 0x0001 app version); meaning not documented
            if (attr in [0xFFE2, 0xFFE4, 0xFFCF, 0xFFDF, 0xFFDE]) { logDebug "Tuya check-in attribute 0x${hex4(attr)} = ${v != null ? v : valueLe}"; return }
            break
    }
    logInfoUnknown(key, "type 0x${hex2(type)} value ${v != null ? v : valueLe}")
}

def healthCheck() {
    long limit = ((offlineHours ?: 2) as Long) * 3600000L
    if (now() - ((state.lastRx ?: 0L) as Long) > limit && device.currentValue("healthStatus") != "offline") {
        sendEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName} is offline")
        log.warn "${device.displayName} is offline (no messages for ${offlineHours ?: 2} h)"
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
