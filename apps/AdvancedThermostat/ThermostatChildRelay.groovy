definition(
    name: "Thermostat Child Relay",
    namespace: "myL2",
    parent: "myL2:Thermostat Master",
    author: "SebyM",
    description: "Thermostat Child for a zone driven by a relay (on/off switch) instead of a TRV",
    category: "Convenience",
    importUrl: "",
    iconUrl: "",
    iconX2Url: "")

/*
 *  Same control as Thermostat Child (hysteresis, windows, motion, Away mode), but the zone's
 *  heating is a relay: on while the zone calls for heat, off when idle. The thermostat gives
 *  the setpoint (and the temperature, unless a separate sensor is chosen). It can be a
 *  physical or a virtual thermostat: the operating state is mirrored to it when it supports
 *  setThermostatOperatingState, and the Master reads it from this app either way.
 */

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section("Choose devices") {
            input "thermostat", "capability.thermostat", title: "* Select Thermostat (setpoint)", submitOnChange: true, required: true
            input "relays", "capability.switch", title: "* Select Relay(s) that heat this zone", submitOnChange: true, required: true, multiple: true
            input "tempSensor", "capability.temperatureMeasurement", title: "Temperature sensor (optional; default: the thermostat's own temperature)", submitOnChange: true, required: false
            input "contactSensors", "capability.contactSensor", title: "Select Contact Sensor (that must be closed to start heating)", submitOnChange: true, required: false, multiple: true
            input "presenceSensors", "capability.motionSensor", title: "Select Motion Sensor (that must be active to start heating; none = not required)", submitOnChange: true, required: false, multiple: true
        }
        section("Other settings") {
            input name: "hysteresis", type: "decimal", title: "Set Hysteresis (Difference to start/stop, in degrees 0.5 - 5.0)", defaultValue: 1.0, required: true
            input name: "awayTemp", type: "number", title: "Set Temperature for Away Mode", defaultValue: 20
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    unschedule()
    unsubscribe()
    initialize()
}

def initialize() {
    if (state.operatingState == null) state.operatingState = thermostat?.currentValue("thermostatOperatingState") == "heating" ? "heating" : "idle"
    subscribe(thermostat, "heatingSetpoint", allHandler)
    subscribe(tempSensor ?: thermostat, "temperature", allHandler)
    for (contactSensor in contactSensors) {
        subscribe(contactSensor, "contact", allHandler)
    }
    for (presenceSensor in presenceSensors) {
        subscribe(presenceSensor, "motion", allHandler)
    }
    subscribe(location, "mode", allHandler)
    log.debug "initialized for ${thermostat} -> ${relays} with settings: hysteresis: ${hysteresis}, awayTemp: ${awayTemp}, temperature from: ${tempSensor ?: thermostat}"
    app.updateLabel("${relays.join(', ')} -> ${thermostat}")
    updateOperatingState()
}

def allHandler(evt) {
    switch (evt.name) {
        case "heatingSetpoint":
        case "mode":
            updateOperatingState()
            break
        case "temperature":
            if (tempSensor && thermostat.hasCommand("setTemperature")) thermostat.setTemperature(evt.value)
            updateOperatingState()
            break
        case "contact":
        case "motion":
            unschedule(updateOperatingState)
            runIn(parent.getContactSensorDelay(), updateOperatingState, [overwrite: true, misfire: 'ignore'])
            break
    }
}

def updateOperatingState() {
    unschedule(updateOperatingState)
    def temperature = (tempSensor ?: thermostat).currentValue("temperature")
    def setpoint = thermostat.currentValue("heatingSetpoint")
    if (temperature == null || setpoint == null) {
        log.warn "${app.label}: no temperature (${temperature}) or heating setpoint (${setpoint}) yet - relay left as is"
        return
    }
    BigDecimal temp = temperature as BigDecimal
    BigDecimal target = setpoint as BigDecimal
    BigDecimal hyst = (hysteresis ?: 1.0) as BigDecimal
    String currentState = state.operatingState ?: "idle"
    String evtValue = currentState
    if (currentState == "heating" && temp >= target + hyst) evtValue = "idle"
    else if (currentState == "idle" && temp <= target - hyst) evtValue = "heating"

    boolean anyContactSensorIsOpen = contactSensors?.any { it.currentValue("contact") == "open" } ?: false
    boolean presenceOk = !presenceSensors || presenceSensors.any { it.currentValue("motion") == "active" }

    if (anyContactSensorIsOpen) {
        applyState("idle")
        if (parent.logEnable && currentState != "idle") { log.debug "updateOperatingState of ${thermostat}: ${currentState} -> idle (window open)" }
    } else if (evtValue == "heating") {
        if (presenceOk) {
            applyState("heating")
            if (parent.logEnable && currentState != "heating") { log.debug "updateOperatingState of ${thermostat}: ${currentState} -> heating" }
        } else {
            applyState("idle")
            if (parent.logEnable && currentState != "idle") { log.debug "updateOperatingState of ${thermostat}: ${currentState} -> restricted idle (no motion)" }
        }
    } else if (location.mode == "Away" && temp < (awayTemp as BigDecimal)) {
        applyState("heating")
        if (parent.logEnable && currentState != "heating") { log.debug "updateOperatingState of ${thermostat}: ${currentState} -> away heating" }
    } else {
        applyState("idle")
        if (parent.logEnable && currentState != "idle") { log.debug "updateOperatingState of ${thermostat}: ${currentState} -> idle" }
    }
    parent.updateHeatingPlantSwitch()
}

// Commands only what changes: the relay(s), and the thermostat's operating state when it accepts one.
private void applyState(String operatingState) {
    state.operatingState = operatingState
    if (thermostat.hasCommand("setThermostatOperatingState") && thermostat.currentValue("thermostatOperatingState") != operatingState) {
        thermostat.setThermostatOperatingState(operatingState)
    }
    String wanted = operatingState == "heating" ? "on" : "off"
    relays.each { relay ->
        if (relay.currentValue("switch") != wanted) {
            if (wanted == "on") relay.on() else relay.off()
        }
    }
}

// ======================================================================================
//  Used by Thermostat Master
// ======================================================================================

String getOperatingState() {
    return state.operatingState ?: "idle"
}

def getThermostatDevice() {
    return thermostat
}
