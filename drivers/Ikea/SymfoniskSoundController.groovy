/**
 *  Copyright 2019 Juha Tanskanen
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  IKEA SYMFONISK Sound Controller (E1744) - the first-gen round rotary puck
 *
 *  Version Author              Note
 *  0.9     Juha Tanskanen      Initial release
 *  0.9HE   CybrMage            Hubitat version Initial release
 *  1.0.0   myL2                Refactored parse() to contains()-based dispatch (fixes undefined
 *                              CLUSTER_SCENES bug); robust IKEA battery parsing (half-percent, 0xFF
 *                              guard); HealthCheck online/offline; PowerSource; Refresh; bind On/Off
 *                              + Level clusters to the hub; debug auto-off. Encoder logic preserved.
 *
 *  Insights on battery scaling & message dispatch adapted from Dan Danache's IKEA Zigbee drivers:
 *      https://codeberg.org/dan-danache/hubitat/src/branch/main/ikea-zigbee-drivers
 */

import hubitat.zigbee.zcl.DataType
import groovy.transform.Field

@Field static final String DRIVER_VERSION = "1.0.0"

// Mark the device offline if no Zigbee message is received within this window (ms). 12 hours.
@Field static final long HEALTH_CHECK_THRESHOLD_MS = 43200000L

// The SYMFONISK transmits each button/rotation command 2-3 times for reliability. Identical repeats
// arrive within ~30ms; genuine rotary notches are ~350ms+ apart. Collapse anything closer than this.
@Field static final int DEDUPE_MS = 250

metadata {
    definition (name: "SYMFONISK Sound Controller", namespace: "cybr", author: "Juha Tanskanen / CybrMage / myL2",
                importUrl: "https://raw.githubusercontent.com/myL2/hubitat-Experimental/main/drivers/Ikea/SymfoniskSoundController.groovy") {
        capability "Actuator"
        capability "Battery"
        capability "PushableButton"
        capability "Configuration"
        capability "Refresh"
        capability "HealthCheck"
        capability "PowerSource"

        attribute "healthStatus", "enum", ["offline", "online", "unknown"]

        // Ask the hub to push any newer OTA firmware image it holds for this device.
        command "updateFirmware"

        fingerprint inClusters: "0000, 0001, 0003, 0020, 1000", outClusters: "0003, 0004, 0006, 0008, 0019, 1000", manufacturer: "IKEA of Sweden", model: "SYMFONISK Sound Controller"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText (info) logging", defaultValue: true
    }
}

// ===================================================================================================================
// Button naming
// ===================================================================================================================

private getIkeaSoundControlNames() {
    [
        "singleTap",    // 1: single tap Control button
        "doubleTap",    // 2: double tap Control button
        "tripleTap",    // 3: triple tap Control button
        "levelUp",      // 4: rotate right (increase volume) - dial turned clockwise
        "levelDown",    // 5: rotate left  (decrease volume) - dial turned anti-clockwise
        "levelStop",    // 6: rotation stopped (dial released)
    ]
}

private getButtonLabel(buttonNum) { ikeaSoundControlNames[buttonNum - 1] }
private getButtonName(buttonNum)  { getButtonLabel(buttonNum) }

// ===================================================================================================================
// Lifecycle
// ===================================================================================================================

def installed() {
    logInfo "installed() - driver v${DRIVER_VERSION}"
    sendEvent(name: "numberOfButtons", value: 6, isStateChange: true)
    state.start = now()
    initialize()
}

def updated() {
    logInfo "updated() - driver v${DRIVER_VERSION}"
    sendEvent(name: "numberOfButtons", value: 6, isStateChange: true)
    initialize()
    if (logEnable) {
        logInfo "debug logging will automatically turn off in 30 minutes"
        runIn(1800, "logsOff")
    }
}

def initialize() {
    unschedule()
    // Hourly liveness check
    schedule("0 0 0/1 ? * * *", "healthCheck")

    if (state.lastRx == null) state.lastRx = 0
    if (state.lastTx == null) state.lastTx = 0
    state.driverVersion = DRIVER_VERSION

    if (device.currentValue("numberOfButtons") == null)
        sendEvent(name: "numberOfButtons", value: 6, isStateChange: true)
    if (device.currentValue("healthStatus") == null)
        sendEvent(name: "healthStatus", value: "unknown", descriptionText: "Health status initialized to unknown")
    if (device.currentValue("powerSource") == null)
        sendEvent(name: "powerSource", value: "battery", descriptionText: "Power source initialized to battery")
    sendEvent(name: "checkInterval", value: 3600, unit: "second", descriptionText: "Health check interval is 3600 seconds")
}

// ===================================================================================================================
// Capability: Configuration
// ===================================================================================================================

def configure() {
    logInfo "configure() - device ${device.getDataValue("model")} - driver v${DRIVER_VERSION}"
    logWarn "[IMPORTANT] This is a battery device: push a button on the controller FIRST to wake it up, then click Configure within a few seconds."

    sendEvent(name: "numberOfButtons", value: 6, isStateChange: true)
    sendEvent(name: "healthStatus", value: "online", descriptionText: "Health status set to online")
    sendEvent(name: "powerSource", value: "battery", descriptionText: "Power source set to battery")
    sendEvent(name: "checkInterval", value: 3600, unit: "second")
    state.lastButtonEvent = 0

    initialize()

    def cmds = []
    // Bind the On/Off (0x0006) and Level Control (0x0008) clusters so the hub receives the button/rotation commands.
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0006 {${device.zigbeeId}} {}"
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0008 {${device.zigbeeId}} {}"
    // Bind + configure reporting for the Power Configuration cluster (battery).
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0001 {${device.zigbeeId}} {}"
    cmds += zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x21, DataType.UINT8, 30, 21600, 0x01)
    cmds += zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x21)
    cmds += zigbee.readAttribute(0x0000, 0x0007) // PowerSource
    return cmds
}

// ===================================================================================================================
// Capability: Refresh
// ===================================================================================================================

def refresh() {
    logInfo "refresh() - reading battery and power source"
    logWarn "[IMPORTANT] Battery device: push a button to wake it up first, otherwise the refresh reply may not arrive."
    return zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x21) +
           zigbee.readAttribute(0x0000, 0x0007)
}

// ===================================================================================================================
// Capability: HealthCheck
// ===================================================================================================================

def ping() {
    logInfo "ping() - reading Basic cluster ZCLVersion"
    return zigbee.readAttribute(0x0000, 0x0000)
}

def healthCheck() {
    String status = (state.lastRx == null || state.lastRx == 0) ? "unknown"
        : (now() - state.lastRx < HEALTH_CHECK_THRESHOLD_MS ? "online" : "offline")
    if (device.currentValue("healthStatus") != status) {
        sendEvent(name: "healthStatus", value: status, descriptionText: "Health status is ${status}")
        logInfo "health status is ${status}"
    }
}

def logsOff() {
    logInfo "debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ===================================================================================================================
// Firmware update
// ===================================================================================================================

// Ask the hub to check its OTA store for a newer image and, if found, push it to the device.
// Hubitat carries IKEA OTA images, so this can pick up a newer firmware even without internet on the device side.
def updateFirmware() {
    logInfo "updateFirmware() - asking the hub to check for a newer firmware image"
    logWarn "[IMPORTANT] Battery device: push a button on the controller to wake it up right before clicking 'Update Firmware', then keep it awake. The update can take several minutes; watch Logs for OTA progress."
    return zigbee.updateFirmware()
}

// ===================================================================================================================
// Parse incoming Zigbee messages
// ===================================================================================================================

def parse(String description) {
    logDebug "parse: ${description}"

    Map msg = [:]
    try {
        msg = zigbee.parseDescriptionAsMap(description)
    } catch (e) {
        logDebug "parse: could not decode message: ${e}"
        return
    }
    if (msg == null || msg.isEmpty()) return

    // parseDescriptionAsMap already provides clusterInt; normalise command / attribute to ints for dispatch.
    if (msg.command != null) msg.commandInt = Integer.parseInt(msg.command, 16)
    if (msg.attrId != null)  msg.attrInt = Integer.parseInt(msg.attrId, 16)
    logDebug "parse: msg=${msg}"

    // HealthCheck: any message means the device is alive.
    state.lastRx = now()
    if (device.currentValue("healthStatus") != "online") {
        sendEvent(name: "healthStatus", value: "online", descriptionText: "Health status changed to online")
    }

    // Drop the SYMFONISK's duplicate button/rotation transmissions before they turn one tap into three.
    if (isDuplicateButton(msg)) {
        logDebug "ignored duplicate command: cluster=0x${msg.cluster}, command=0x${msg.command}, data=${msg.data}"
        return
    }

    switch (msg) {

        // ----- Battery (Power Configuration cluster) -----
        case { contains it, [clusterInt: 0x0001, attrInt: 0x0021] }:
            handleBattery(msg)
            return

        // ----- Single tap: On/Off Toggle -----
        case { contains it, [clusterInt: 0x0006, commandInt: 0x02] }:
            handleButton(1, "pushed")
            return

        // ----- Double / triple tap: Level Step -----
        case { contains it, [clusterInt: 0x0008, commandInt: 0x02] }:
            handleButton((msg.data && msg.data[0] == "00") ? 2 : 3, "pushed")
            return

        // ----- Rotation start: Level Move -----
        case { contains it, [clusterInt: 0x0008, commandInt: 0x01] }:
            boolean up = (msg.data && msg.data[0] == "00")
            handleButton(up ? 4 : 5, up ? "levelUp" : "levelDown")
            return

        // ----- Rotation stop: Level Stop (0x03) / StopWithOnOff (0x07) -----
        case { contains it, [clusterInt: 0x0008, commandInt: 0x03] }:
        case { contains it, [clusterInt: 0x0008, commandInt: 0x07] }:
            handleButton(6, "levelStop")
            return

        // ----- PowerSource (Basic cluster) -----
        case { contains it, [clusterInt: 0x0000, attrInt: 0x0007] }:
            handlePowerSource(msg.value)
            return

        // ----- Expected-but-uninteresting frames -----
        case { contains it, [clusterInt: 0x0001, commandInt: 0x07] }:   // Configure Reporting Response (battery)
            logDebug "received Configure Reporting Response (battery)"
            return
        case { contains it, [clusterInt: 0x0013] }:                     // Device announce
            logDebug "received Device Announce"
            return
        case { contains it, [clusterInt: 0x0003] }:                     // Identify cluster chatter (pairing)
            logDebug "ignored Identify frame"
            return

        // ZDP / network-management frames (source endpoint 0x00): interview, binds, LQI, routing, etc.
        case { it.sourceEndpoint == "00" }:
            logDebug "ignored ZDP/network frame: cluster=0x${msg.clusterId}, command=0x${msg.command}, data=${msg.data}"
            return

        default:
            logDebug "unhandled message: ${msg}"
    }
}

// ===================================================================================================================
// Message handlers
// ===================================================================================================================

private void handleBattery(Map msg) {
    String hex = msg.value
    // Hubitat occasionally fails to decode the Read Attributes Response value; fall back to the raw data bytes.
    if (hex == null && msg.data != null && msg.data.size() >= 3 && msg.data[0] == "21" && msg.data[1] == "00") {
        hex = msg.data[2]
    }
    if (hex == null) {
        logDebug "handleBattery: no value in message"
        return
    }
    if (hex.toUpperCase() == "FF") {
        logWarn "ignoring invalid battery reading (0xFF)"
        return
    }

    // IKEA reports batteryPercentageRemaining (0x0021) per the Zigbee spec as half-percent (0-200).
    // If your unit turns out to report 0-100 natively, remove the "/ 2" below.
    int raw = zigbee.convertHexToInt(hex)
    int pct = Math.min(100, Math.round(raw / 2.0) as int)

    sendEvent(name: "battery", value: pct, unit: "%",
              descriptionText: "${device.displayName} battery is ${pct}%")
    state.lastBattery = now()
    logInfo "battery is ${pct}%"
}

private void handlePowerSource(String value) {
    // 0x01/0x02/0x05/0x06 = mains, 0x03 = battery, 0x04 = dc, else unknown
    String powerSource = "unknown"
    switch (value) {
        case ["01", "02", "05", "06"]: powerSource = "mains";   break
        case "03":                     powerSource = "battery"; break
        case "04":                     powerSource = "dc";      break
    }
    sendEvent(name: "powerSource", value: powerSource, descriptionText: "${device.displayName} power source is ${powerSource}")
    logInfo "power source is ${powerSource}"
}

private void handleButton(int buttonNumber, String buttonState) {
    String name = getButtonName(buttonNumber)
    String type = eventType()
    def descriptionText = "${device.displayName} button ${buttonNumber} (${name}) was pushed"
    sendEvent(name: "pushed", value: buttonNumber, descriptionText: descriptionText, isStateChange: true, type: type)
    state.lastButtonEvent = now()
    logInfo "button ${buttonNumber} (${name}) pushed [${type}]"

    sendLevelEvent(buttonNumber, buttonState)
}

// Preserved encoder logic: turn the time spent rotating into a signed volume delta (-100..100).
private sendLevelEvent(buttonNumber, buttonState) {
    if (buttonNumber <= 3) return

    switch (buttonState) {
        case "levelUp":
            state.start = now()
            state.direction = 1
            break
        case "levelDown":
            state.start = now()
            state.direction = 0
            break
        case "levelStop":
            long iTime = now() - (state.start ?: now())
            def iChange = 0

            // Ignore turns over 5 seconds, probably a lag issue
            if (iTime > 5000) {
                iTime = 0
            }

            // Change based on 5 seconds for full 0-100 change in volume
            iChange = iTime / 5000 * 100
            def volumeChange = state.direction ? ((BigInteger) iChange).intValue() : ((BigInteger) (0 - iChange)).intValue()
            def descriptionText = "Volume level change was ${volumeChange}"
            sendEvent(name: "level", value: volumeChange, descriptionText: descriptionText, isStateChange: true, type: "physical")
            logInfo "volume level change was ${volumeChange}"
            break
    }
}

// ===================================================================================================================
// Zigbee helpers
// ===================================================================================================================

// True when this is a repeat of the previous button/rotation command within DEDUPE_MS. Only clusters
// 0x0006 (On/Off) and 0x0008 (Level) are deduped; everything else always passes through.
private boolean isDuplicateButton(Map msg) {
    if (!(msg.clusterInt in [0x0006, 0x0008])) return false
    String sig = "${msg.clusterInt}:${msg.commandInt}:${msg.data}"
    long t = now()
    if (sig == state.lastBtnSig && (t - (state.lastBtnSeen ?: 0L)) < DEDUPE_MS) {
        return true
    }
    state.lastBtnSig = sig
    state.lastBtnSeen = t
    return false
}

// If this driver sent a command in the last 3 seconds, treat the resulting event as digital, otherwise physical.
private String eventType() {
    (state.lastTx && (now() - state.lastTx < 3000)) ? "digital" : "physical"
}

// switch/case syntactic sugar: true when every key/value in spec is present and equal in msg.
private boolean contains(Map msg, Map spec) {
    spec.every { msg[it.key] == it.value }
}

// ===================================================================================================================
// Logging helpers
// ===================================================================================================================

private void logDebug(String msg) { if (logEnable) log.debug "${device.displayName} ${msg}" }
private void logInfo(String msg)  { if (txtEnable != false) log.info "${device.displayName} ${msg}" }
private void logWarn(String msg)  { log.warn "${device.displayName} ${msg}" }
private void logError(String msg) { log.error "${device.displayName} ${msg}" }
