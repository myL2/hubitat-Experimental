/**
 * =====================  Tuya Zigbee Security Remote (TS0215)  ===========================
 *
 *  DESCRIPTION:
 *  4-button arm / disarm / home / SOS key fob (Tuya TS0215 / TS0215A, sold as Heiman,
 *  Smart9 S9ZGBRC01, Nedis ZBRC10WT and others). The fob is an IAS ACE client: it sends
 *  "Arm" (mode away / home / disarm) and "Emergency" commands on cluster 0x0501.
 *
 *  Buttons (pushed):  1 = arm away (lock)   2 = disarm (unlock)   3 = arm home   4 = SOS
 *  Optionally sets Hubitat Safety Monitor directly (arm away / disarm / arm home).
 *
 *  The fob sends each command 3 times (about 1.6 s apart) and then re-announces itself, even
 *  with the Arm Response sent: Hubitat does not give drivers the ZCL sequence number, so the
 *  response cannot match the request. Repeats within REPEAT_WINDOW_MS are ignored, so each
 *  press is one event. The fob is busy for ~5.5 s after a press (repeats + rejoin): a second
 *  press in that window is lost or delivered late. Notes: devices/Heiman/TS0215.md
 *
 * =======================================================================================
 *
 *  Changelog:
 *
 *  v1.0.0 (2026-10-03) - First release (_TYZB01_qm6djpta TS0215)
 *
 */

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

@Field static final String DRIVER_VERSION = "1.0.0"
@Field static final long REPEAT_WINDOW_MS = 2500     // the fob resends after ~1.6 s
@Field static final Map ARM_MODES = [0: "disarm", 1: "armHome", 2: "armNight", 3: "armAway"]
@Field static final Map ACTION_BUTTON = [armAway: 1, disarm: 2, armHome: 3, armNight: 3, sos: 4]
@Field static final Map ACTION_TEXT = [armAway: "arm away", disarm: "disarm", armHome: "arm home", armNight: "arm night", sos: "SOS"]

metadata {
    definition(name: "Tuya Zigbee Security Remote", namespace: "myL2", author: "myL2",
               importUrl: "https://raw.githubusercontent.com/myL2/hubitat-Experimental/main/drivers/Tuya/TuyaZigbeeSecurityRemote.groovy",
               singleThreaded: true) {
        capability "Sensor"
        capability "Battery"
        capability "PushableButton"
        capability "Configuration"

        attribute "action", "enum", ["armAway", "disarm", "armHome", "armNight", "sos"]

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0001,0003,0020,0500,0B05", outClusters: "0003,0019,0501", model: "TS0215", manufacturer: "_TYZB01_qm6djpta", deviceJoinName: "Tuya Security Remote"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0001,0500,0501", outClusters: "0019,000A", model: "TS0215A", manufacturer: "_TZ3000_fsiepnrh", deviceJoinName: "Nedis Security Remote"
    }
    preferences {
        input name: "hsmControl", type: "bool", title: "Control Hubitat Safety Monitor", description: "Arm away / disarm / arm home HSM directly from buttons 1-3", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() {
    sendEvent(name: "numberOfButtons", value: 4)
}

def updated() {
    log.info "${device.displayName} updated (v${DRIVER_VERSION})"
    sendEvent(name: "numberOfButtons", value: 4)
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() { device.updateSetting("logEnable", [value: "false", type: "bool"]) }

// The fob sleeps: press any button right after Configure so it is awake to receive it.
def configure() {
    log.info "${device.displayName} configuring - press a button on the remote now to wake it"
    sendEvent(name: "numberOfButtons", value: 4)
    List<String> cmds = []
    // IAS enrolment: tell the fob where the CIE (the hub) is, then enrol it
    cmds += "he wattr 0x${device.deviceNetworkId} 0x01 0x0500 0x0010 0xF0 {${reverseHex(location.hub.zigbeeEui)}}"
    cmds += "delay 300"
    cmds += zigbee.enrollResponse(300)
    // Bind the clusters Zigbee2MQTT binds for TS0215A (basic, power, IAS zone, IAS ACE)
    [0x0000, 0x0001, 0x0500, 0x0501].each { cl ->
        cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x${String.format('%04X', cl)} {${device.zigbeeId}} {}"
        cmds += "delay 300"
    }
    cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 3600, 21600, 2)
    cmds += zigbee.readAttribute(0x0001, 0x0021)
    return cmds
}

def push(button) {
    Integer b = button as Integer
    if (b < 1 || b > 4) { log.warn "${device.displayName} push: button must be 1-4"; return }
    String action = ACTION_BUTTON.find { k, v -> v == b && k != "armNight" }?.key
    handleAction(action, "digital")
}

// ======================================================================================
//  Parsing
// ======================================================================================

def parse(String description) {
    logDebug "parse: ${description}"
    if (description.startsWith("enroll request")) {
        logDebug "enroll request - sending enroll response"
        return zigbee.enrollResponse()
    }
    if (description.startsWith("zone status")) return null    // the fob has no zone state of its own

    Map msg = zigbee.parseDescriptionAsMap(description)
    if (msg.profileId == "0000") {               // ZDO: device announce after each press, bind/descriptor responses
        if (msg.clusterId == "8021") logDebug "bind response: ${msg.data?.getAt(1) == '00' ? 'success' : 'status ' + msg.data?.getAt(1)}"
        else if (msg.clusterId == "0013") logDebug "device announce (rejoined)"
        return null
    }
    switch (msg.clusterId ?: msg.cluster) {
        case "0501": return parseAce(msg)
        case "0001": parseBattery(msg); break
        case "0500": break                       // write / enroll responses to configure()
        default: logDebug "unhandled: ${msg}"
    }
    return null
}

private List<String> parseAce(Map msg) {
    if (!msg.isClusterSpecific) return null
    List<String> data = msg.data ?: []
    switch (msg.command) {
        case "00":   // Arm: armMode, arm/disarm code (string), zone id
            Integer mode = data ? Integer.parseInt(data[0], 16) : -1
            String action = ARM_MODES[mode]
            if (!action) { log.warn "${device.displayName} unknown arm mode ${mode}: ${msg}"; return null }
            handleAction(action, "physical")
            // Arm Response (server -> client): arm notification = the mode just set
            return ["he raw 0x${device.deviceNetworkId} 1 0x${msg.sourceEndpoint ?: '01'} 0x0501 {19 00 00 ${String.format('%02X', mode)}}"]
        case "02":   // Emergency
            handleAction("sos", "physical")
            // Default Response: command 0x02, status SUCCESS
            return ["he raw 0x${device.deviceNetworkId} 1 0x${msg.sourceEndpoint ?: '01'} 0x0501 {18 00 0B 02 00}"]
        default:
            logDebug "unhandled IAS ACE command ${msg.command}: ${msg}"
    }
    return null
}

private void parseBattery(Map msg) {
    if (msg.attrId == "0021" && msg.value) {
        Integer pct = Math.min(100, Math.round(Integer.parseInt(msg.value, 16) / 2.0f) as Integer)
        sendEvent(name: "battery", value: pct, unit: "%", descriptionText: "${device.displayName} battery is ${pct}%")
        if (txtEnable) log.info "${device.displayName} battery is ${pct}%"
    }
}

// ======================================================================================
//  Actions
// ======================================================================================

private void handleAction(String action, String type) {
    long t = now()
    if (type == "physical" && state.lastAction == action && t - ((state.lastActionAt ?: 0L) as Long) < REPEAT_WINDOW_MS) {
        logDebug "repeat of ${action} ignored"
        state.lastActionAt = t
        return
    }
    state.lastAction = action
    state.lastActionAt = t

    Integer button = ACTION_BUTTON[action]
    String text = "${device.displayName} ${ACTION_TEXT[action]} (button ${button}) [${type}]"
    sendEvent(name: "action", value: action, descriptionText: text, type: type, isStateChange: true)
    sendEvent(name: "pushed", value: button, descriptionText: text, type: type, isStateChange: true)
    if (txtEnable) log.info text

    if (hsmControl && action != "sos") {
        String hsm = (action == "armNight") ? "armNight" : action
        sendLocationEvent(name: "hsmSetArm", value: hsm)
        if (txtEnable) log.info "${device.displayName} set HSM: ${hsm}"
    }
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
