/**
 * ========================  Tuya Zigbee SOS Button (TS0211)  =============================
 *
 *  DESCRIPTION:
 *  Single-button SOS / panic button (Tuya TS0211, sold as Heiman). The button is an IAS
 *  zone (type 0x0115 key fob): every press is one zone status change notification with
 *  the alarm1 bit set. It sends nothing for hold or release, and no "clear" afterwards.
 *
 *  Events: pushed 1 on every press. With "Detect double press" on, two presses within
 *  DOUBLE_PRESS_MS give doubleTapped 1 instead (the single press is then reported
 *  DOUBLE_PRESS_MS late, once no second press came).
 *
 * =======================================================================================
 *
 *  Changelog:
 *
 *  v1.0.0 (2026-10-04) - First release (_TZ1800_rrv4u5fr TS0211)
 *
 */

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

@Field static final String DRIVER_VERSION = "1.0.0"
@Field static final long DOUBLE_PRESS_MS = 600     // a double press arrives 0.15-0.4 s apart
@Field static final long DUPLICATE_MS = 80         // closer than this = the same press delivered again
@Field static final BigDecimal BATTERY_FULL_V = 3.0
@Field static final BigDecimal BATTERY_EMPTY_V = 2.1

metadata {
    definition(name: "Tuya Zigbee SOS Button", namespace: "myL2", author: "myL2",
               importUrl: "https://raw.githubusercontent.com/myL2/hubitat-Experimental/main/drivers/Tuya/TuyaZigbeeSosButton.groovy",
               singleThreaded: true) {
        capability "Sensor"
        capability "Battery"
        capability "PushableButton"
        capability "DoubleTapableButton"
        capability "Configuration"

        attribute "batteryLow", "enum", ["false", "true"]

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0001,0003,0020,0500,0B05", outClusters: "0019", model: "TS0211", manufacturer: "_TZ1800_rrv4u5fr", deviceJoinName: "Tuya SOS Button"
    }
    preferences {
        input name: "doublePress", type: "bool", title: "Detect double press", description: "Two quick presses = doubleTapped 1 (single presses are then reported 0.6 s late)", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() {
    sendEvent(name: "numberOfButtons", value: 1)
}

def updated() {
    log.info "${device.displayName} updated (v${DRIVER_VERSION})"
    sendEvent(name: "numberOfButtons", value: 1)
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() { device.updateSetting("logEnable", [value: "false", type: "bool"]) }

// The button sleeps: press it right after Configure so it is awake to receive it.
def configure() {
    log.info "${device.displayName} configuring - press the button now to wake it"
    sendEvent(name: "numberOfButtons", value: 1)
    List<String> cmds = []
    // IAS enrolment: tell the button where the CIE (the hub) is, then enrol it
    cmds += "he wattr 0x${device.deviceNetworkId} 0x01 0x0500 0x0010 0xF0 {${reverseHex(location.hub.zigbeeEui)}}"
    cmds += "delay 300"
    cmds += zigbee.enrollResponse(300)
    cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8, 3600, 21600, 1)
    cmds += zigbee.readAttribute(0x0001, 0x0020)
    return cmds
}

def push(button = 1) { pressed("digital") }
def doubleTap(button = 1) { emit("doubleTapped", "digital") }

// ======================================================================================
//  Parsing
// ======================================================================================

def parse(String description) {
    logDebug "parse: ${description}"
    if (description.startsWith("enroll request")) {
        logDebug "enroll request - sending enroll response"
        return zigbee.enrollResponse()
    }
    if (description.startsWith("zone status")) {
        // "zone status 0x0001 -- extended status 0x00 - ..." (read the bitmap directly)
        def m = description =~ /zone status 0x([0-9A-Fa-f]{4})/
        if (m.find()) parseZoneStatus(Integer.parseInt(m.group(1), 16))
        return null
    }
    Map msg = zigbee.parseDescriptionAsMap(description)
    if (msg.profileId == "0000") return null            // ZDO: announce, descriptor requests
    switch (msg.clusterId ?: msg.cluster) {
        case "0001": parseBattery(msg); break
        case "0500": break                               // write / enroll responses to configure()
        default: logDebug "unhandled: ${msg}"
    }
    return null
}

private void parseZoneStatus(int zs) {
    // bit 0 alarm1 = press, bit 3 battery low
    boolean batteryLow = (zs & 0x0008) != 0
    String low = batteryLow ? "true" : "false"
    if (device.currentValue("batteryLow") != low) {
        sendEvent(name: "batteryLow", value: low, descriptionText: "${device.displayName} battery low is ${low}")
        if (batteryLow) log.warn "${device.displayName} reports a low battery"
    }
    if (zs & 0x0001) pressed("physical")
}

private void parseBattery(Map msg) {
    if (msg.attrId == "0020" && msg.value) {
        BigDecimal volts = Integer.parseInt(msg.value, 16) / 10.0
        Integer pct = Math.max(0, Math.min(100, Math.round((volts - BATTERY_EMPTY_V) / (BATTERY_FULL_V - BATTERY_EMPTY_V) * 100) as Integer))
        sendEvent(name: "battery", value: pct, unit: "%", descriptionText: "${device.displayName} battery is ${pct}% (${volts} V)")
        if (txtEnable) log.info "${device.displayName} battery is ${pct}% (${volts} V)"
    } else if (msg.attrId == "0021" && msg.value) {
        Integer pct = Math.min(100, Math.round(Integer.parseInt(msg.value, 16) / 2.0f) as Integer)
        sendEvent(name: "battery", value: pct, unit: "%", descriptionText: "${device.displayName} battery is ${pct}%")
        if (txtEnable) log.info "${device.displayName} battery is ${pct}%"
    }
}

// ======================================================================================
//  Presses
// ======================================================================================

private void pressed(String type) {
    long t = now()
    // the same press is sometimes delivered 2-3 times within a few ms
    if (type == "physical" && t - ((state.lastPressAt ?: 0L) as Long) < DUPLICATE_MS) {
        logDebug "duplicate press ignored"
        return
    }
    if (type == "physical") state.lastPressAt = t
    if (!doublePress) { emit("pushed", type); return }
    if (state.pendingAt && t - (state.pendingAt as Long) < DOUBLE_PRESS_MS) {
        unschedule("singlePressTimeout")
        state.remove("pendingAt")
        emit("doubleTapped", type)
    } else {
        state.pendingAt = t
        state.pendingType = type
        runInMillis(DOUBLE_PRESS_MS, "singlePressTimeout")
    }
}

def singlePressTimeout() {
    String type = state.pendingType ?: "physical"
    state.remove("pendingAt")
    emit("pushed", type)
}

private void emit(String name, String type) {
    String text = "${device.displayName} ${name == 'pushed' ? 'pressed' : 'double pressed'} [${type}]"
    sendEvent(name: name, value: 1, descriptionText: text, type: type, isStateChange: true)
    if (txtEnable) log.info text
}

// ======================================================================================
//  Helpers
// ======================================================================================

private String reverseHex(String hex) {
    return hex.replace("0x", "").split("(?<=\\G.{2})").reverse().join("")
}

private void logDebug(msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}
