/**
 * ======================  SONOFF Zigbee Smart Plug (S60ZBTPF)  ===========================
 *
 *  DESCRIPTION:
 *  Dedicated driver for the SONOFF S60ZBTPF metering plug. Parses everything known for it
 *  (Zigbee2MQTT definition + own probing):
 *    0x0006 on/off, power-on state (0x4003)
 *    0xFC11 SONOFF private (eWeLink) cluster: voltage 0x7005 (mV), current 0x7004 (mA),
 *           power 0x7006 (mW), energy today/month/yesterday 0x7009/0x700A/0x700B (Wh),
 *           overload protection 0x7003, outlet protection 0x7007, network LED 0x0001, ...
 *    0x0702 metering (energy), 0x0B04 electrical measurement (fallback), 0x0B05 RSSI/LQI
 *  Attributes nobody knows are logged and kept in state.unknownAttributes; the
 *  discoverAttributes / readAttributes commands help to find out more.
 *
 *  Always On: off commands from the hub are ignored, and an off at the plug (button, power
 *  blip, protection) is turned back on after 5 s.
 *  Persistent Always On: sends On on a schedule, so the plug comes back on even after it
 *  switched itself off (power blip with power-on state OFF, button press, protection).
 *
 *  The plug keeps reporting the last power/current after its relay turns off; the driver
 *  reports 0 W / 0 A while the switch is off.
 *
 * =======================================================================================
 *
 *  Changelog:
 *
 *  v1.0.0 (2026-10-08) - First release: full parsing, Always On (+5 s restore), Persistent Always On, power-on state,
 *                        network LED, outlet protect, overload protection limits, inching, firmware update
 *
 */

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

@Field static final String DRIVER_VERSION = "1.0.0"
@Field static final int FC11 = 0xFC11
@Field static final long DIGITAL_WINDOW_MS = 5000          // switch reports this soon after a command are "digital"
@Field static final long OFFLINE_AFTER_MS = 2 * 3600 * 1000 // on/off is reported at least every 30 min
@Field static final int ALWAYS_ON_RESTORE_S = 5             // Always On: turn back on this long after an off at the plug

@Field static final Map POWER_ON_STATES = ["0": "off", "1": "on", "2": "toggle", "255": "previous"]
@Field static final Map PERSISTENT_OPTS = ["0": "Disabled", "1": "Every 1 minute", "15": "Every 15 minutes", "60": "Every 1 hour"]

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
    definition(name: "SONOFF Zigbee Smart Plug", namespace: "myL2", author: "myL2",
               importUrl: "https://raw.githubusercontent.com/myL2/hubitat-Experimental/main/drivers/SONOFF/SonoffZigbeeSmartPlug.groovy",
               singleThreaded: true) {
        capability "Actuator"
        capability "Switch"
        capability "Outlet"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "CurrentMeter"
        capability "VoltageMeasurement"
        capability "Refresh"
        capability "Configuration"

        attribute "energyToday", "number"
        attribute "energyYesterday", "number"
        attribute "energyMonth", "number"
        attribute "powerOnState", "enum", ["off", "on", "toggle", "previous"]
        attribute "overloadProtection", "string"
        attribute "faultCode", "number"
        attribute "healthStatus", "enum", ["online", "offline"]

        command "readAttributes", [[name: "cluster", type: "STRING", description: "🔎 Diagnostic: hex cluster, e.g. FC11"],
                                   [name: "attributes", type: "STRING", description: "comma-separated hex attribute ids, e.g. 7003,7005"],
                                   [name: "mfgCode", type: "STRING", description: "manufacturer code (hex, e.g. 1286), empty = none"]]
        command "setInching", [[name: "inching", type: "ENUM", description: "Auto-switch after a delay (SONOFF inching). Do not use with Always On", constraints: ["disable", "enable"]],
                               [name: "seconds", type: "NUMBER", description: "Delay, 0.5 - 3599.5 s"],
                               [name: "mode", type: "ENUM", description: "off = turns off after the delay once turned on; on = turns on after the delay once turned off", constraints: ["off", "on"]]]
        command "updateFirmware"
        command "getInfo", [[name: "Read basic info and the endpoint's cluster lists into Device Data (like the generic Device driver)"]]
        command "discoverAttributes", [[name: "cluster", type: "STRING", description: "🔎 Diagnostic: list the attributes a cluster has (hex, e.g. FC11)"],
                                       [name: "mfgCode", type: "STRING", description: "manufacturer code (hex, e.g. 1286), empty = none"]]

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,0702,0B04,0B05,FC57,FC11", outClusters: "000A,0019", model: "S60ZBTPF", manufacturer: "SONOFF", deviceJoinName: "SONOFF Smart Plug"
    }
    preferences {
        input name: "alwaysOn", type: "bool", title: "Always On", description: "Ignore Off commands from the hub", defaultValue: false
        input name: "persistentOn", type: "enum", title: "Persistent Always On", description: "Send On on this schedule, also when the plug turned itself off", options: PERSISTENT_OPTS, defaultValue: "0"
        input name: "powerOnStatePref", type: "enum", title: "Power-on state", description: "Relay state after mains power returns (written to the plug)", options: POWER_ON_STATES, defaultValue: "1"
        input name: "networkLed", type: "bool", title: "Network LED", description: "Blue network status LED on the plug", defaultValue: false
        input name: "outletProtect", type: "bool", title: "Outlet control protect", description: "SONOFF 'outlet overload protection settings' flag (0x7007)", defaultValue: false
        input name: "overloadEdit", type: "bool", title: "Edit overload protection", description: "Show and write the protection limits below on Save", defaultValue: false
        if (overloadEdit) {
            input name: "opMaxCurrent", type: "decimal", title: "Max current (A)", description: "0.1 - 17 A", defaultValue: 14, range: "0.1..17"
            input name: "opMinCurrentOn", type: "bool", title: "Enable min current", defaultValue: false
            input name: "opMinCurrent", type: "decimal", title: "Min current (A)", defaultValue: 0.1, range: "0.1..17"
            input name: "opMaxVoltageOn", type: "bool", title: "Enable max voltage", defaultValue: false
            input name: "opMaxVoltage", type: "number", title: "Max voltage (V)", description: "165 - 277 V", defaultValue: 253, range: "165..277"
            input name: "opMinVoltageOn", type: "bool", title: "Enable min voltage", defaultValue: false
            input name: "opMinVoltage", type: "number", title: "Min voltage (V)", description: "165 - 277 V", defaultValue: 190, range: "165..277"
            input name: "opMaxPower", type: "decimal", title: "Max power (W)", description: "0.1 - 4000 W", defaultValue: 3250, range: "0.1..4000"
            input name: "opMinPowerOn", type: "bool", title: "Enable min power", description: "Turns the plug off below this power - not for fridges", defaultValue: false
            input name: "opMinPower", type: "decimal", title: "Min power (W)", defaultValue: 1, range: "0.1..4000"
        }
        input name: "powerChange", type: "number", title: "Power report change (W)", description: "Report power when it changes by this much (min. 10 s apart)", defaultValue: 5, range: "1..1000"
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
    schedulePersistentOn()
    runEvery1Hour("healthCheck")
    if (logEnable) runIn(1800, "logsOff")
    List<String> cmds = []
    if (powerOnStatePref != null && powerOnStatePref != state.powerOnStateRaw?.toString()) {
        cmds += writePowerOnState()
    }
    cmds += writeFc11Settings()
    cmds += configureReportingCmds()
    sendZigbeeCommands(cmds)
}

def logsOff() { device.updateSetting("logEnable", [value: "false", type: "bool"]) }

def configure() {
    log.info "${device.displayName} configuring"
    unschedule()                                 // also clears jobs left by a previous driver (e.g. after Swap Devices)
    schedulePersistentOn()
    runEvery1Hour("healthCheck")
    if (logEnable) runIn(1800, "logsOff")
    String dni = device.deviceNetworkId
    List<String> cmds = []
    [0x0006, 0x0702, 0x0B04, FC11].each { cl ->
        cmds += "zdo bind 0x${dni} 0x01 0x01 0x${hex4(cl)} {${device.zigbeeId}} {}"
        cmds += "delay 200"
    }
    cmds += configureReportingCmds()
    cmds += writePowerOnState()
    cmds += zigbee.readAttribute(0x0702, [0x0301, 0x0302], [:], 200)                    // energy multiplier / divisor
    cmds += zigbee.readAttribute(0x0B04, [0x0600, 0x0601, 0x0602, 0x0603, 0x0604, 0x0605], [:], 200)
    cmds += zigbee.readAttribute(0x0000, [0x0001, 0x4000], [:], 200)
    cmds += refreshCmds()
    sendZigbeeCommands(cmds)
}

private List<String> configureReportingCmds() {
    Integer powerMw = ((powerChange ?: 5) as Integer) * 1000
    List<String> cmds = []
    cmds += zigbee.configureReporting(0x0006, 0x0000, DataType.BOOLEAN, 1, 1800, null, [:], 200)
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 60, 3600, 1, [:], 200)
    cmds += zigbee.configureReporting(FC11, 0x7006, DataType.UINT32, 10, 3600, powerMw, [:], 200)   // power, mW
    cmds += zigbee.configureReporting(FC11, 0x7005, DataType.UINT32, 10, 3600, 2000, [:], 200)      // voltage, mV
    cmds += zigbee.configureReporting(FC11, 0x7004, DataType.UINT32, 10, 3600, 100, [:], 200)       // current, mA
    [0x7009, 0x700A, 0x700B].each { a ->                                                            // energy today/month/yesterday, Wh
        cmds += zigbee.configureReporting(FC11, a, DataType.UINT32, 60, 3600, 50, [:], 200)
    }
    return cmds
}

def refresh() {
    sendZigbeeCommands(refreshCmds())
}

private List<String> refreshCmds() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0006, [0x0000, 0x4003], [:], 200)
    cmds += zigbee.readAttribute(FC11, [0x7004, 0x7005, 0x7006], [:], 200)
    cmds += zigbee.readAttribute(FC11, [0x7009, 0x700A, 0x700B], [:], 200)
    cmds += zigbee.readAttribute(FC11, [0x7003, 0x7007, 0x0001], [:], 200)
    cmds += zigbee.readAttribute(0x0702, [0x0000], [:], 200)
    return cmds
}

// Network LED (0x0001), outlet control protect (0x7007) and, when editing is on, overload protection (0x7003)
private List<String> writeFc11Settings() {
    List<String> cmds = []
    String led = networkLed ? "on" : "off"
    if (networkLed != null && state.networkLed != led) {
        cmds += zigbee.writeAttribute(FC11, 0x0001, DataType.BOOLEAN, networkLed ? 1 : 0, [:], 200)
        cmds += zigbee.readAttribute(FC11, 0x0001, [:], 200)
    }
    Integer op = outletProtect ? 1 : 0
    if (outletProtect != null && state.outletControlProtect != op) {
        cmds += zigbee.writeAttribute(FC11, 0x7007, DataType.UINT8, op, [:], 200)
        cmds += zigbee.readAttribute(FC11, 0x7007, [:], 200)
    }
    if (overloadEdit) {
        String payload = buildOverloadProtectionPayload()
        if (payload) {
            // Write Attributes (0x02): attr 0x7003 LE, type 0x42, value = length-prefixed payload
            cmds += zclFrame(FC11, false, "02 03 70 42 ${payload}")
            cmds += zigbee.readAttribute(FC11, 0x7003, [:], 500)
        }
    }
    return cmds
}

// Zigbee2MQTT buildOverloadProtectionPayload(): [len][0x04][len-2][currentFlags][voltageFlags][powerFlags]
// then uint32 LE: max current, [min current], [max voltage], [min voltage], max power, [min power]
// (mA / mV / mW). Max current and max power are always present.
private String buildOverloadProtectionPayload() {
    long maxCur = Math.round(((opMaxCurrent ?: 17) as BigDecimal) * 1000)
    long minCur = Math.round(((opMinCurrent ?: 0) as BigDecimal) * 1000)
    long maxV = ((opMaxVoltage ?: 253) as Long) * 1000
    long minV = ((opMinVoltage ?: 190) as Long) * 1000
    long maxP = Math.round(((opMaxPower ?: 4000) as BigDecimal) * 1000)
    long minP = Math.round(((opMinPower ?: 0) as BigDecimal) * 1000)
    if (opMinCurrentOn && minCur >= maxCur) { log.warn "${device.displayName}: min current must be below max current - overload protection not written"; return null }
    if (opMaxVoltageOn && opMinVoltageOn && minV >= maxV) { log.warn "${device.displayName}: min voltage must be below max voltage - overload protection not written"; return null }
    if (opMinPowerOn && minP >= maxP) { log.warn "${device.displayName}: min power must be below max power - overload protection not written"; return null }
    int curFlags = 1 | (opMinCurrentOn ? 2 : 0)
    int voltFlags = (opMaxVoltageOn ? 1 : 0) | (opMinVoltageOn ? 2 : 0)
    int pwrFlags = 1 | (opMinPowerOn ? 2 : 0)
    List<Long> limits = [maxCur]
    if (opMinCurrentOn) limits << minCur
    if (opMaxVoltageOn) limits << maxV
    if (opMinVoltageOn) limits << minV
    limits << maxP
    if (opMinPowerOn) limits << minP
    List<Integer> body = [0x04, 0, curFlags, voltFlags, pwrFlags]
    limits.each { long l -> (0..3).each { int k -> body << (int) ((l >> (8 * k)) & 0xFF) } }
    body[1] = body.size() - 2
    List<Integer> all = [body.size()] + body
    logDebug "overload protection payload: ${all.collect { hex2(it) }.join(' ')}"
    return all.collect { hex2(it) }.join(" ")
}

// SONOFF inching (Zigbee2MQTT inchingControlSet): FC11 cluster command 0x01 "protocolData",
// manufacturer specific (0x1286): 01 17 07 80 <mode> <channel> <time LE, 0.5 s units> 00 00 <XOR checksum>
def setInching(String inching, BigDecimal seconds, String mode) {
    int t = Math.round(((seconds ?: 0) as BigDecimal) * 2) as int
    t = Math.max(0, Math.min(7199, t))
    int m = (inching == "enable" ? 0x80 : 0x00) | (mode == "on" ? 0x01 : 0x00)
    List<Integer> d = [0x01, 0x17, 0x07, 0x80, m, 0x00, t & 0xFF, (t >> 8) & 0xFF, 0x00, 0x00]
    int chk = 0
    d.each { chk ^= it }
    d << chk
    if (inching == "enable" && alwaysOn && mode != "on") log.warn "${device.displayName}: inching will switch the plug off automatically although Always On is enabled"
    log.info "${device.displayName} setInching: ${inching}, ${t / 2} s, mode ${mode}"
    sendZigbeeCommands(zclFrame(FC11, true, "01 " + d.collect { hex2(it) }.join(" "), true))
}

def updateFirmware() {
    log.info "${device.displayName}: checking for a firmware update (Hubitat firmware repository)"
    sendZigbeeCommands(zigbee.updateFirmware())
}

// Raw ZCL frame: frame control, [manufacturer code 0x1286], sequence number, then 'rest' (command + payload)
private List<String> zclFrame(Integer cluster, boolean mfgSpecific, String rest, boolean clusterSpecific = false) {
    Integer seq = (((state.zclSeq ?: 0) as Integer) + 1) % 256
    state.zclSeq = seq
    int fc = (clusterSpecific ? 0x01 : 0x00) | (mfgSpecific ? 0x04 : 0x00)
    String hdr = mfgSpecific ? "${hex2(fc)} 86 12 ${hex2(seq)}" : "${hex2(fc)} ${hex2(seq)}"
    return ["he raw 0x${device.deviceNetworkId} 1 0x01 0x${hex4(cluster)} {${hdr} ${rest}}", "delay 200"]
}

private List<String> writePowerOnState() {
    Integer v = (powerOnStatePref ?: "1") as Integer
    logDebug "writing power-on state ${POWER_ON_STATES[v.toString()]} (0x${hex2(v)})"
    return zigbee.writeAttribute(0x0006, 0x4003, DataType.ENUM8, v, [:], 200) + zigbee.readAttribute(0x0006, 0x4003, [:], 200)
}

// ======================================================================================
//  Commands
// ======================================================================================

def on() {
    state.lastCommandAt = now()
    sendZigbeeCommands(zigbee.on())
}

def off() {
    if (alwaysOn) {
        log.warn "${device.displayName}: Always On is enabled - Off command ignored"
        return
    }
    state.lastCommandAt = now()
    sendZigbeeCommands(zigbee.off())
}

private void schedulePersistentOn() {
    unschedule("persistentOnTick")
    switch (persistentOn ?: "0") {
        case "1":  runEvery1Minute("persistentOnTick"); break
        case "15": runEvery15Minutes("persistentOnTick"); break
        case "60": runEvery1Hour("persistentOnTick"); break
    }
}

def persistentOnTick() {
    if (device.currentValue("switch") != "on") {
        log.warn "${device.displayName}: Persistent Always On - plug reported off, sending On"
    } else {
        logDebug "Persistent Always On: sending On"
    }
    sendZigbeeCommands(zigbee.on())
}

def readAttributes(String cluster, String attributes, String mfgCode = null) {
    Integer cl = hexInt(cluster)
    List<Integer> attrs = attributes.split("[,\\s]+").findAll { it }.collect { hexInt(it) }
    Map opts = mfgCode?.trim() ? [mfgCode: "0x${mfgCode.trim().replace('0x', '')}"] : [:]
    log.info "${device.displayName} readAttributes: cluster 0x${hex4(cl)} attributes ${attrs.collect { hex4(it) }}${opts ? ' mfgCode ' + opts.mfgCode : ''}"
    sendZigbeeCommands(zigbee.readAttribute(cl, attrs, opts, 200))
}

// Like the generic Device driver's getInfo: basic cluster attributes + ZDO Active Endpoints (0x0005)
// and Simple Descriptor (0x0004) requests; responses fill Device Data (inClusters, outClusters, ...).
def getInfo() {
    log.info "${device.displayName} getInfo${''}"
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
    } else if (description.startsWith("on/off:")) {
        switchEvent(description.endsWith("1") ? "on" : "off")
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
    if (msg.command == "07") { logDebug "configure reporting response 0x${cl}: ${msg.data}"; return }
    if (msg.command == "04") { logDebug "write attribute response 0x${cl}: ${msg.data}"; return }
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
        case 0x0006:
            if (attr == 0x0000) { switchEvent(v ? "on" : "off"); return }
            if (attr == 0x4003) {
                state.powerOnStateRaw = v
                syncPreference("powerOnStatePref", v.toString(), "enum")
                String s = POWER_ON_STATES[v.toString()] ?: "unknown(${v})"
                sendEventLog("powerOnState", s, null, "power-on state is ${s}")
                return
            }
            break
        case 0x0702:
            if (attr == 0x0000) {
                BigDecimal mult = (state.energyMultiplier ?: 1) as BigDecimal
                BigDecimal div = (state.energyDivisor ?: 1000) as BigDecimal
                BigDecimal kwh = (v * mult / div).setScale(3, BigDecimal.ROUND_HALF_UP)
                sendEventLog("energy", kwh, "kWh")
                return
            }
            if (attr == 0x0301) { state.energyMultiplier = v ?: 1; return }
            if (attr == 0x0302) { state.energyDivisor = v ?: 1000; return }
            if (attr == 0x0400) { logDebug "instantaneous demand ${v}"; return }
            // status, unit of measure (0 = kWh), summation formatting, metering device type (0 = electric)
            if (attr in [0x0200, 0x0300, 0x0303, 0x0306]) { logDebug "metering 0x${hex4(attr)} = ${v}"; return }
            break
        case 0x0B04:
            if (attr in [0x0600, 0x0601, 0x0602, 0x0603, 0x0604, 0x0605]) { state["em_" + hex4(attr)] = v ?: 1; return }
            if (attr == 0x0000) { logDebug "measurement type ${v}"; return }     // 1 = AC active measurement
            if (attr == 0x0505) { sendEventLog("voltage", scaled(v, 0x0600, 0x0601, 1), "V"); return }
            if (attr == 0x0508) { if (device.currentValue("switch") != "off") sendEventLog("amperage", scaled(v, 0x0602, 0x0603, 3), "A"); return }
            if (attr == 0x050B) { if (device.currentValue("switch") != "off") sendEventLog("power", scaled(v, 0x0604, 0x0605, 1), "W"); return }
            break
        case 0x0B05:
            if (attr == 0x011C) { state.lqi = v; return }
            if (attr == 0x011D) { state.rssi = v; return }
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
        case FC11:
            if (handleFc11(attr, v, valueLe)) return
            break
    }
    logInfoUnknown(key, "type 0x${hex2(type)} value ${v != null ? v : valueLe}")
}

private boolean handleFc11(Integer attr, Long v, String valueLe) {
    boolean off = device.currentValue("switch") == "off"
    switch (attr) {
        case 0x7004: sendEventLog("amperage", off ? 0 : (v / 1000.0).setScale(3, BigDecimal.ROUND_HALF_UP), "A"); return true
        case 0x7005: sendEventLog("voltage", (v / 1000.0).setScale(1, BigDecimal.ROUND_HALF_UP), "V"); return true
        case 0x7006: sendEventLog("power", off ? 0 : (v / 1000.0).setScale(1, BigDecimal.ROUND_HALF_UP), "W"); return true
        case 0x7009: sendEventLog("energyToday", (v / 1000.0).setScale(3, BigDecimal.ROUND_HALF_UP), "kWh"); return true
        case 0x700A: sendEventLog("energyMonth", (v / 1000.0).setScale(3, BigDecimal.ROUND_HALF_UP), "kWh"); return true
        case 0x700B: sendEventLog("energyYesterday", (v / 1000.0).setScale(3, BigDecimal.ROUND_HALF_UP), "kWh"); return true
        case 0x7003: sendEventLog("overloadProtection", decodeOverloadProtection(valueLe), null); syncOverloadPreferences(valueLe); return true
        case 0x7007: state.outletControlProtect = v; logDebug "outlet control protect: ${v}"; syncPreference("outletProtect", v ? true : false, "bool"); return true
        case 0x0001: state.networkLed = v ? "on" : "off"; logDebug "network LED: ${state.networkLed}"; syncPreference("networkLed", v ? true : false, "bool"); return true
        case 0x0010: sendEventLog("faultCode", v, null); return true
        case 0x0012: state.radioPower = v; return true
        case 0x0014: state.delayedPowerOnState = v ? "on" : "off"; return true
        case 0x0015: state.delayedPowerOnTime = v; return true
        // Undocumented; discovered on firmware 1.0.2. Flag + limit pairs, most likely the plug's
        // built-in protection: 0x700D = 17000 mA (17 A), 0x700F = 253000 mV (253 V),
        // 0x7011 = 4000000 mW (4000 W); flags 0x700C / 0x700E / 0x7010 read 0.
        case 0x700C: case 0x700D: case 0x700E: case 0x700F: case 0x7010: case 0x7011:
            state.fc11Limits = (state.fc11Limits ?: [:]) + [("0x" + hex4(attr)): v]
            logDebug "FC11 0x${hex4(attr)} (probable protection flag/limit) = ${v}"
            return true
    }
    return false
}

// 0x7003 payload (Zigbee2MQTT): [cfg flag][?][currentFlag][voltageFlag][powerFlag] then uint32 LE limits
// for each enabled flag in order max/min current (mA), max/min voltage (mV), max/min power (mW).
// Flags: 1 = max enabled, 2 = min enabled, 3 = both. cfg flag 1/2/3 = only current/voltage/power flag present, 4 = all three.
private String decodeOverloadProtection(String hex) {
    Map lim = overloadLimits(hex)
    if (lim == null) return "unknown (${hex})"
    List<List> names = [["maxCurrent", "max current", "A"], ["minCurrent", "min current", "A"], ["maxVoltage", "max voltage", "V"],
                        ["minVoltage", "min voltage", "V"], ["maxPower", "max power", "W"], ["minPower", "min power", "W"]]
    List<String> parts = names.findAll { lim[it[0]] != null }.collect { "${it[1]} ${(lim[it[0]] as BigDecimal).stripTrailingZeros().toPlainString()} ${it[2]}" }
    return parts ? parts.join(", ") : "disabled"
}

// Overload protection limits that are enabled, as [maxCurrent: A, minCurrent: A, maxVoltage: V, ...]; null if unreadable
private Map overloadLimits(String hex) {
    List<Integer> b = (0..<hex.length().intdiv(2)).collect { Integer.parseInt(hex.substring(it * 2, it * 2 + 2), 16) }
    if (b.size() < 3) return null
    int cfg = b[0]
    int idx = 2
    int cur = 0
    int volt = 0
    int pwr = 0
    if (cfg == 1) { cur = b[idx]; idx += 1 }
    else if (cfg == 2) { volt = b[idx]; idx += 1 }
    else if (cfg == 3) { pwr = b[idx]; idx += 1 }
    else if (cfg == 4) { cur = b[idx]; volt = b[idx + 1]; pwr = b[idx + 2]; idx += 3 }
    // limits follow in this order, each uint32 LE, only when enabled
    List<List> limits = [[cur & 1, "maxCurrent"], [cur & 2, "minCurrent"], [volt & 1, "maxVoltage"],
                         [volt & 2, "minVoltage"], [pwr & 1, "maxPower"], [pwr & 2, "minPower"]]
    Map out = [:]
    for (List l in limits) {
        if (!l[0]) continue
        if (idx + 4 > b.size()) break
        long r = 0
        for (int k = 3; k >= 0; k--) { r = (r << 8) | b[idx + k] }
        idx += 4
        out[l[1]] = r / 1000.0
    }
    return out
}

// Copy the plug's limits into the overload preferences while they are not being edited
private void syncOverloadPreferences(String hex) {
    if (overloadEdit) return
    Map lim = overloadLimits(hex)
    if (lim == null) return
    if (lim.maxCurrent != null) syncPreference("opMaxCurrent", lim.maxCurrent, "decimal")
    if (lim.maxPower != null) syncPreference("opMaxPower", lim.maxPower, "decimal")
    syncPreference("opMinCurrentOn", lim.minCurrent != null, "bool")
    if (lim.minCurrent != null) syncPreference("opMinCurrent", lim.minCurrent, "decimal")
    syncPreference("opMaxVoltageOn", lim.maxVoltage != null, "bool")
    if (lim.maxVoltage != null) syncPreference("opMaxVoltage", (lim.maxVoltage as BigDecimal).intValue(), "number")
    syncPreference("opMinVoltageOn", lim.minVoltage != null, "bool")
    if (lim.minVoltage != null) syncPreference("opMinVoltage", (lim.minVoltage as BigDecimal).intValue(), "number")
    syncPreference("opMinPowerOn", lim.minPower != null, "bool")
    if (lim.minPower != null) syncPreference("opMinPower", lim.minPower, "decimal")
}

// Show the plug's value in the preference (settings are written on Save, so no queue to respect)
private void syncPreference(String pref, value, String type) {
    def cur = settings[pref]
    boolean same = (cur instanceof Number && value instanceof Number) ? ((cur as BigDecimal) == (value as BigDecimal)) : (cur?.toString() == value?.toString())
    if (same) return
    device.updateSetting(pref, [value: value, type: type])
    logDebug "preference ${pref} set to the plug's ${value}"
}

private void switchEvent(String value) {
    boolean digital = now() - ((state.lastCommandAt ?: 0L) as Long) < DIGITAL_WINDOW_MS
    String type = digital ? "digital" : "physical"
    if (device.currentValue("switch") != value) {
        String text = "${device.displayName} was turned ${value} [${type}]"
        sendEvent(name: "switch", value: value, type: type, descriptionText: text)
        if (txtEnable) log.info text
    }
    if (value == "off") {                      // the plug keeps reporting the last load after turning off
        if (device.currentValue("power") != 0) sendEvent(name: "power", value: 0, unit: "W")
        if (device.currentValue("amperage") != 0) sendEvent(name: "amperage", value: 0, unit: "A")
        if (alwaysOn && !digital) {
            log.warn "${device.displayName}: turned off at the plug while Always On is enabled - turning it back on in ${ALWAYS_ON_RESTORE_S} s"
            runIn(ALWAYS_ON_RESTORE_S, "alwaysOnRestore")
        }
    } else {
        unschedule("alwaysOnRestore")
    }
}

def alwaysOnRestore() {
    if (!alwaysOn || device.currentValue("switch") == "on") return
    log.info "${device.displayName}: Always On - turning back on"
    on()
}

def healthCheck() {
    if (now() - ((state.lastRx ?: 0L) as Long) > OFFLINE_AFTER_MS && device.currentValue("healthStatus") != "offline") {
        sendEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName} is offline")
        log.warn "${device.displayName} is offline (no messages for ${OFFLINE_AFTER_MS / 3600000} h)"
    }
}

// ======================================================================================
//  Helpers
// ======================================================================================

private void sendEventLog(String name, value, String unit, String text = null) {
    String desc = "${device.displayName} ${text ?: name + ' is ' + value + (unit ? ' ' + unit : '')}"
    if (device.currentValue(name)?.toString() == value?.toString()) { logDebug "${desc} (no change)"; return }
    sendEvent(name: name, value: value, unit: unit, descriptionText: desc)
    if (txtEnable) log.info desc
}

private void logInfoUnknown(String key, String detail) {
    log.info "${device.displayName} unknown attribute ${key}: ${detail}"
    state.unknownAttributes = (state.unknownAttributes ?: [:]) + [(key): detail]
}

private BigDecimal scaled(Long v, int multAttr, int divAttr, int decimals) {
    BigDecimal mult = (state["em_" + hex4(multAttr)] ?: 1) as BigDecimal
    BigDecimal div = (state["em_" + hex4(divAttr)] ?: 1) as BigDecimal
    return (v * mult / div).setScale(decimals, BigDecimal.ROUND_HALF_UP)
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
