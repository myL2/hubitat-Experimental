/**
 * ===================  SONOFF Zigbee Presence Sensor (SNZB-06P24)  =======================
 *
 *  DESCRIPTION:
 *  Dedicated driver for the SONOFF SNZB-06P24 24 GHz mmWave presence sensor (USB powered,
 *  always listening). Parses everything known for it (Zigbee2MQTT / ZHA + own probing,
 *  see devices/SONOFF/SNZB-06P24.md):
 *    0x0406 occupancy (motion), fading time 0x0010 (15-65535 s)
 *    0x0400 illuminance (ZCL log scale -> lux)
 *    0xFC11 SONOFF private (mfg 0x1286): zone enable 0x2016 (bitmap16), illuminance offset
 *           0x2018 (-1000..1000 lx), radar sensitivity 0x2021 (-6..+6), zone activity 0x2015
 *           (only pushed during spatial learning), 0x2011 / 0x2012 / 0x2017 / 0x1FFF unknown
 *    FC11 command 0x04: spatial learning (start / accepted / result)
 *    0xFC57 Amazon WWAH: ignored
 *  Zones: 1 = 0-1 m (bits 0+1), 2 = 1-1.5 m (bit 2) ... 7 = 3.5-4 m (bit 7).
 *
 * =======================================================================================
 *
 *  Changelog:
 *
 *  v1.0.0 (2026-10-10) - First release
 *
 */

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

@Field static final String DRIVER_VERSION = "1.0.0"
@Field static final int FC11 = 0xFC11
@Field static final String SONOFF_MFG = "0x1286"
@Field static final int LEARNING_TIMEOUT_S = 90     // until the sensor reports the real duration
@Field static final int LEARNING_GRACE_S = 15
@Field static final long OFFLINE_AFTER_MS = 2 * 3600 * 1000   // occupancy is reported at least hourly
@Field static final List<String> ZONE_RANGES = ["0-1 m", "1-1.5 m", "1.5-2 m", "2-2.5 m", "2.5-3 m", "3-3.5 m", "3.5-4 m"]

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
    definition(name: "SONOFF Zigbee Presence Sensor", namespace: "myL2", author: "myL2",
               importUrl: "https://raw.githubusercontent.com/myL2/hubitat-Experimental/main/drivers/SONOFF/SonoffZigbeePresenceSensor.groovy",
               singleThreaded: true) {
        capability "Sensor"
        capability "MotionSensor"
        capability "IlluminanceMeasurement"
        capability "PowerSource"
        capability "Refresh"
        capability "Configuration"

        attribute "fadingTime", "number"
        attribute "radarSensitivity", "number"
        attribute "illuminanceOffset", "number"
        attribute "zoneEnable", "number"
        attribute "zonesEnabled", "string"
        attribute "spatialLearningState", "enum", ["idle", "learning", "success", "failed", "timeout"]
        attribute "_status_", "string"
        attribute "healthStatus", "enum", ["online", "offline"]

        command "startSpatialLearning", [[name: "🧭 Calibrate the EMPTY room: press Run, leave the room, come back when _status_ says so (~35 s)"]]
        command "setZones", [[name: "zones", type: "STRING", description: "🎯 Zones to enable as digits, e.g. 12345 (zones 1-5) • all • none • 1=0-1m 2=1-1.5m 3=1.5-2m 4=2-2.5m 5=2.5-3m 6=3-3.5m 7=3.5-4m"]]
        command "readAttributes", [[name: "cluster", type: "STRING", description: "🔎 Diagnostic: hex cluster, e.g. FC11"],
                                   [name: "attributes", type: "STRING", description: "comma-separated hex attribute ids, e.g. 2016,2018"],
                                   [name: "mfgCode", type: "STRING", description: "manufacturer code (hex, e.g. 1286), empty = none"]]
        command "updateFirmware"
        command "getInfo", [[name: "Read basic info and the endpoint's cluster lists into Device Data (like the generic Device driver)"]]
        command "discoverAttributes", [[name: "cluster", type: "STRING", description: "🔎 Diagnostic: list the attributes a cluster has (hex, e.g. FC11)"],
                                       [name: "mfgCode", type: "STRING", description: "manufacturer code (hex, e.g. 1286), empty = none"]]

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0400,0406,FC11,FC57", outClusters: "0003,0019", model: "SNZB-06P24", manufacturer: "SONOFF", deviceJoinName: "SONOFF Presence Sensor"
    }
    preferences {
        input name: "fadingTimePref", type: "number", title: "Fading time (s)", description: "How long presence stays active after the last detection, 15 - 65535 s (30 s or more recommended)", range: "15..65535"
        input name: "radarSensitivityPref", type: "number", title: "Radar sensitivity", description: "-6 (less sensitive) .. +6 (more sensitive); best after spatial learning", range: "-6..6"
        input name: "zonesPref", type: "string", title: "Detection zones", description: "Zones to enable as digits, e.g. 1234567 (all) or 12345 (up to 3 m)"
        input name: "illuminanceOffsetPref", type: "number", title: "Illuminance offset (lx)", description: "-1000 - 1000 lx", range: "-1000..1000"
        input name: "illuminanceThreshold", type: "number", title: "Illuminance change (lx)", description: "Only report illuminance changes of at least this many lux (stops 0 / 2 lx flicker in the dark)", defaultValue: 5, range: "1..1000"
        input name: "ignoreMotionDuringLearning", type: "bool", title: "Ignore motion during spatial learning", description: "Presence flickers while the sensor calibrates; don't let it trigger rules", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

// ======================================================================================
//  Lifecycle
// ======================================================================================

def installed() {
    log.info "${device.displayName} installed (v${DRIVER_VERSION})"
    sendEvent(name: "powerSource", value: "dc")
    sendEvent(name: "spatialLearningState", value: "idle")
    runIn(2, "configure")
}

def updated() {
    log.info "${device.displayName} updated (v${DRIVER_VERSION})"
    unschedule("logsOff")
    if (logEnable) runIn(1800, "logsOff")
    List<String> cmds = []
    if (fadingTimePref != null && device.currentValue("fadingTime")?.toString() != fadingTimePref.toString()) {
        cmds += zigbee.writeAttribute(0x0406, 0x0010, DataType.UINT16, fadingTimePref as Integer, [:], 200)
        cmds += zigbee.readAttribute(0x0406, 0x0010, [:], 200)
    }
    if (radarSensitivityPref != null && device.currentValue("radarSensitivity")?.toString() != radarSensitivityPref.toString()) {
        cmds += zigbee.writeAttribute(FC11, 0x2021, DataType.INT8, (radarSensitivityPref as Integer) & 0xFF, [mfgCode: SONOFF_MFG], 200)
        cmds += zigbee.readAttribute(FC11, 0x2021, [mfgCode: SONOFF_MFG], 200)
    }
    if (illuminanceOffsetPref != null && device.currentValue("illuminanceOffset")?.toString() != illuminanceOffsetPref.toString()) {
        cmds += zigbee.writeAttribute(FC11, 0x2018, DataType.INT16, (illuminanceOffsetPref as Integer) & 0xFFFF, [mfgCode: SONOFF_MFG], 200)
        cmds += zigbee.readAttribute(FC11, 0x2018, [mfgCode: SONOFF_MFG], 200)
    }
    if (zonesPref) {
        Integer bm = zonesToBitmap(zonesPref)
        if (bm == null) log.warn "${device.displayName}: invalid zones '${zonesPref}' - use digits 1-7, all or none"
        else if (device.currentValue("zoneEnable")?.toString() != bm.toString()) cmds += writeZones(bm)
    }
    cmds += configureReportingCmds()
    sendZigbeeCommands(cmds)
}

def logsOff() { device.updateSetting("logEnable", [value: "false", type: "bool"]) }

def configure() {
    log.info "${device.displayName} configuring"
    unschedule()                                 // also clears jobs left by a previous driver (e.g. after Swap Devices)
    runEvery1Hour("healthCheck")
    if (logEnable) runIn(1800, "logsOff")
    sendEvent(name: "powerSource", value: "dc")
    removeForeignAttributes()
    List<String> cmds = []
    [0x0400, 0x0406, FC11].each { cl ->
        cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x${hex4(cl)} {${device.zigbeeId}} {}"
        cmds += "delay 200"
    }
    cmds += configureReportingCmds()
    cmds += zigbee.readAttribute(0x0000, [0x0001, 0x4000], [:], 200)
    cmds += refreshCmds()
    sendZigbeeCommands(cmds)
}

// Delete current states this driver does not define (left by a previous driver, e.g. zoneStatus / zonesOccupied)
private void removeForeignAttributes() {
    List<String> own = ["motion", "illuminance", "powerSource", "fadingTime", "radarSensitivity", "illuminanceOffset",
                        "zoneEnable", "zonesEnabled", "spatialLearningState", "_status_", "healthStatus"]
    device.getCurrentStates()*.name.findAll { !(it in own) }.each { String n ->
        device.deleteCurrentState(n)
        log.info "${device.displayName}: removed attribute '${n}' left by a previous driver"
    }
    if (device.currentValue("spatialLearningState") in ["success", "failed", "timeout"] && !state.learnedHere) {
        sendEvent(name: "spatialLearningState", value: "idle", descriptionText: "spatial learning state reset (previous driver)")
    }
}

private List<String> configureReportingCmds() {
    List<String> cmds = []
    cmds += zigbee.configureReporting(0x0406, 0x0000, DataType.BITMAP8, 0, 3600, null, [:], 200)
    // 300 log units = about 7 % change; the driver filters further by illuminanceThreshold (lux)
    cmds += zigbee.configureReporting(0x0400, 0x0000, DataType.UINT16, 10, 3600, 300, [:], 200)
    return cmds
}

def refresh() {
    sendZigbeeCommands(refreshCmds())
}

private List<String> refreshCmds() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0406, [0x0000, 0x0010], [:], 200)
    cmds += zigbee.readAttribute(0x0400, [0x0000], [:], 200)
    cmds += zigbee.readAttribute(FC11, [0x2016, 0x2018, 0x2021], [mfgCode: SONOFF_MFG], 200)
    return cmds
}

// ======================================================================================
//  Zones
// ======================================================================================

def setZones(String zones = null) {
    Integer bm = zonesToBitmap(zones)
    if (bm == null) { log.warn "${device.displayName} setZones: use digits 1-7 (e.g. 12345), all or none - zones: ${zoneHelp()}"; return }
    device.updateSetting("zonesPref", [value: bitmapToZones(bm).replace(",", "") ?: "none", type: "string"])
    sendZigbeeCommands(writeZones(bm))
}

// The sensor takes this BITMAP16 write in big-endian byte order: send the zone bitmap as the high byte
// ("XX00"), as the working kkossev patch does; reads come back as a plain 0x00XX value.
private List<String> writeZones(int bm) {
    log.info "${device.displayName} setting detection zones ${bitmapToZones(bm) ?: 'none'} (bitmap 0x${hex2(bm)})"
    return ["he wattr 0x${device.deviceNetworkId} 0x01 0xFC11 0x2016 0x19 {${hex2(bm)}00} {1286}", "delay 200"] +
           zigbee.readAttribute(FC11, 0x2016, [mfgCode: SONOFF_MFG], 300)
}

// "12345", "1,2,5", "all", "none" -> bitmap (zone 1 = bits 0+1, zone n = bit n)
private Integer zonesToBitmap(String zones) {
    String v = (zones ?: "").trim().toLowerCase()
    if (v in ["all", "on"]) return 0xFF
    if (v in ["none", "off"]) return 0
    List<String> digits = v.replaceAll("[\\s,;]", "").toList()
    if (!digits || digits.any { !(it ==~ /[1-7]/) }) return null
    int bm = 0
    digits.each { String d -> int z = d as int; bm |= (z == 1 ? 0x03 : (1 << z)) }
    return bm
}

private String bitmapToZones(int bm) {
    List<String> z = []
    if (bm & 0x03) z << "1"
    (2..7).each { int n -> if (bm & (1 << n)) z << "${n}" }
    return z.join(",")
}

private String zoneHelp() {
    return (1..7).collect { "${it}=${ZONE_RANGES[it - 1]}" }.join(" ")
}

// ======================================================================================
//  Spatial learning (FC11 cluster command 0x04, manufacturer specific)
// ======================================================================================

// sub command 0x00 = start, followed by uint64 LE sequence number (current time, ms)
def startSpatialLearning() {
    String seq = (0..7).collect { int k -> hex2(((now() >> (8 * k)) & 0xFF) as int) }.join()
    log.info "${device.displayName} spatial learning: starting - LEAVE THE ROOM and keep it empty until it completes"
    state.learning = true
    sendEvent(name: "spatialLearningState", value: "learning", descriptionText: "spatial learning started")
    sendEvent(name: "_status_", value: "🚶 Spatial learning started - LEAVE THE ROOM and keep it empty until this says you can come back in")
    runIn(LEARNING_TIMEOUT_S, "spatialLearningTimeout", [overwrite: true])
    sendZigbeeCommands(zigbee.command(FC11, 0x04, [mfgCode: SONOFF_MFG], 200, "00" + seq))
}

def spatialLearningTimeout() {
    if (device.currentValue("spatialLearningState") != "learning") return
    state.learning = false
    log.warn "${device.displayName} spatial learning: no result from the sensor"
    sendEvent(name: "spatialLearningState", value: "timeout", descriptionText: "spatial learning timed out")
    sendEvent(name: "_status_", value: "⌛ Spatial learning timed out (no result from the sensor) - you can come back in; try again")
}

// From the sensor: 0x01 accepted = seq (uint64 LE) + expected end (uint64 LE); 0x02 result = seq + state + reason
private void parseSpatialLearning(List<String> d) {
    if (!d) return
    switch (d[0]) {
        case "01":
            if (d.size() < 17) break
            long seq = leListToLong(d, 1, 8)
            long end = leListToLong(d, 9, 8)
            int duration = Math.max(0L, (end - seq).intdiv(1000L)) as int
            log.info "${device.displayName} spatial learning: accepted, about ${duration} s - keep the room EMPTY"
            sendEvent(name: "_status_", value: "⏳ Spatial learning running, about ${duration} seconds - keep the room EMPTY")
            runIn(duration + LEARNING_GRACE_S, "spatialLearningTimeout", [overwrite: true])
            return
        case "02":
            if (d.size() < 11) break
            int st = Integer.parseInt(d[9], 16)
            int reason = Integer.parseInt(d[10], 16)
            unschedule("spatialLearningTimeout")
            state.learning = false
            state.learnedHere = true
            if (st == 0 && reason == 0) {
                log.info "${device.displayName} spatial learning: completed successfully"
                sendEvent(name: "spatialLearningState", value: "success", descriptionText: "spatial learning completed")
                sendEvent(name: "_status_", value: "✅ Spatial learning done - you can come back in")
            } else {
                log.warn "${device.displayName} spatial learning: FAILED (state ${st}, reason ${reason})"
                sendEvent(name: "spatialLearningState", value: "failed", descriptionText: "spatial learning failed (state ${st}, reason ${reason})")
                sendEvent(name: "_status_", value: "❌ Spatial learning failed (state ${st}, reason ${reason}) - you can come back in; try again with the room empty")
            }
            runIn(60, "clearStatus")
            return
    }
    log.warn "${device.displayName} spatial learning: unexpected message ${d}"
}

def clearStatus() { sendEvent(name: "_status_", value: "clear") }

private static long leListToLong(List<String> d, int from, int len) {
    long v = 0
    for (int k = len - 1; k >= 0; k--) v = (v << 8) | Integer.parseInt(d[from + k], 16)
    return v
}

def readAttributes(String cluster, String attributes, String mfgCode = null) {
    Integer cl = hexInt(cluster)
    List<Integer> attrs = attributes.split("[,\\s]+").findAll { it }.collect { hexInt(it) }
    Map opts = mfgCode?.trim() ? [mfgCode: "0x${mfgCode.trim().replace('0x', '')}"] : [:]
    log.info "${device.displayName} readAttributes: cluster 0x${hex4(cl)} attributes ${attrs.collect { hex4(it) }}${opts ? ' mfgCode ' + opts.mfgCode : ''}"
    sendZigbeeCommands(zigbee.readAttribute(cl, attrs, opts, 200))
}

// Asks Hubitat's firmware repository for an OTA image; the sensor only listens right after it wakes.
def updateFirmware() {
    log.info "${device.displayName}: checking for a firmware update (Hubitat firmware repository)"
    sendZigbeeCommands(zigbee.updateFirmware())
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
    sendZigbeeCommands(cmds)
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
    sendZigbeeCommands(["he raw 0x${device.deviceNetworkId} 1 0x01 0x${hex4(cl)} {${frame}}"])
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
        if (type == 0x48 && i + 6 <= body.length()) {            // array: element type, count (uint16 LE), elements
            Integer elemLen = ZCL_TYPE_LEN[Integer.parseInt(body.substring(i, i + 2), 16)] ?: 1
            int count = Integer.parseInt(body.substring(i + 4, i + 6) + body.substring(i + 2, i + 4), 16)
            i += 6
            len = count * elemLen
            if (i + len * 2 > body.length()) break
            String arr = body.substring(i, i + len * 2)
            i += len * 2
            handleAttribute(cluster, attr, type, arr)
            continue
        }
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
    if (cl == "FC11" && msg.isClusterSpecific && msg.command == "04") { parseSpatialLearning(msg.data ?: []); return }
    if (cl == "FC57") { logDebug "WWAH (FC57) message ignored: ${msg.data}"; return }
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
    Long v = (type in [0x41, 0x42, 0x48]) ? null : leToLong(valueLe, SIGNED_TYPES.contains(type))
    switch (cluster) {
        case 0x0406:
            if (attr == 0x0000) {
                String motion = (v & 0x01) ? "active" : "inactive"
                if (state.learning && ignoreMotionDuringLearning != false) { logDebug "motion ${motion} ignored (spatial learning)"; return }
                sendEventLog("motion", motion, null, null, "physical")
                return
            }
            if (attr == 0x0010) { sendEventLog("fadingTime", v, "s"); syncPreference("fadingTimePref", v, "number"); return }
            if (attr in [0x0001, 0x0002]) { logDebug "occupancy sensor type 0x${hex4(attr)} = ${v}"; return }
            break
        case 0x0400:
            if (attr == 0x0000) {
                // ZCL: MeasuredValue = 10000 * log10(lux) + 1; 0 = too dark to measure
                Integer lux = v > 0 ? Math.round(Math.pow(10, (v - 1) / 10000.0)) as Integer : 0
                Integer last = device.currentValue("illuminance") as Integer
                if (last != null && Math.abs(lux - last) < ((illuminanceThreshold ?: 5) as Integer)) {
                    logDebug "illuminance ${lux} lx ignored (change < ${illuminanceThreshold ?: 5} lx)"
                    return
                }
                sendEventLog("illuminance", lux, "lx")
                return
            }
            if (attr in [0x0001, 0x0002]) { logDebug "illuminance range 0x${hex4(attr)} = ${v}"; return }
            break
        case FC11:
            switch (attr) {
                case 0x2016:
                    int bm = (v as int) & 0xFF
                    sendEventLog("zoneEnable", bm, null)
                    sendEventLog("zonesEnabled", bitmapToZones(bm) ?: "none", null, "detection zones are ${bitmapToZones(bm) ?: 'none'}")
                    syncPreference("zonesPref", bitmapToZones(bm).replace(",", "") ?: "none", "string")
                    return
                case 0x2018: sendEventLog("illuminanceOffset", v, "lx"); syncPreference("illuminanceOffsetPref", v, "number"); return
                case 0x2021: sendEventLog("radarSensitivity", v, null); syncPreference("radarSensitivityPref", v, "number"); return
                case 0x2015: logDebug "zone activity during spatial learning: ${bitmapToZones((v as int) & 0xFF) ?: 'none'} (0x${hex4(v as int)})"; return
                case 0x2011: case 0x2012: case 0x2017: case 0x1FFF:
                    logDebug "FC11 0x${hex4(attr)} (unknown) = ${valueLe}"; return
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

// Show the sensor's value in the preference (settings are written on Save, nothing is queued)
private void syncPreference(String pref, value, String type) {
    if (settings[pref]?.toString() == value?.toString()) return
    device.updateSetting(pref, [value: value, type: type])
    logDebug "preference ${pref} set to the sensor's ${value}"
}

def healthCheck() {
    if (now() - ((state.lastRx ?: 0L) as Long) > OFFLINE_AFTER_MS && device.currentValue("healthStatus") != "offline") {
        sendEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName} is offline")
        log.warn "${device.displayName} is offline (no messages for 2 h)"
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
