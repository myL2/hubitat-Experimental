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
 *  heating is a relay: on while any of the zone's thermostats calls for heat, off when none do.
 *  Each thermostat gives a setpoint (and its temperature, unless a separate sensor is chosen) and
 *  keeps its own hysteresis state. Thermostats can be physical or virtual: each one's operating
 *  state is mirrored to it when it supports setThermostatOperatingState, and the Master reads the
 *  zone's state from this app either way.
 */

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section("Choose devices") {
            input "thermostats", "capability.thermostat", title: "* Select Thermostat(s) (setpoint); the relay heats while any of them calls for heat", submitOnChange: true, required: true, multiple: true
            input "relays", "capability.switch", title: "${pump ? '' : '* '}Select Relay(s) that heat this zone${pump ? ' (optional with a pump: a floor without zone valves)' : ''}", submitOnChange: true, required: !pump, multiple: true
            input "pump", "capability.switch", title: "Distributor pump (optional): run by the Master while any zone using it heats", submitOnChange: true, required: false
            input "tempSensor", "capability.temperatureMeasurement", title: "Temperature sensor (optional, used for every selected thermostat; default: each thermostat's own temperature)", submitOnChange: true, required: false
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
    state.remove("operatingState")
    if (state.callStates == null) {
        Map calls = [:]
        thermostatList().each { calls[it.id as String] = it.currentValue("thermostatOperatingState") == "heating" ? "heating" : "idle" }
        state.callStates = calls
    }
    subscribe(thermostatList(), "heatingSetpoint", allHandler)
    subscribe(tempSensor ? [tempSensor] : thermostatList(), "temperature", allHandler)
    for (contactSensor in contactSensors) {
        subscribe(contactSensor, "contact", allHandler)
    }
    for (presenceSensor in presenceSensors) {
        subscribe(presenceSensor, "motion", allHandler)
    }
    subscribe(location, "mode", allHandler)
    log.debug "initialized for ${thermostatList()} -> ${relays ?: 'no relays'}${pump ? ", pump: ${pump}" : ''} with settings: hysteresis: ${hysteresis}, awayTemp: ${awayTemp}, temperature from: ${tempSensor ?: 'each thermostat'}"
    app.updateLabel("${relays ? relays.join(', ') + ' -> ' : ''}${thermostatList().join(', ')}${pump ? " (pump: ${pump})" : ''}")
    updateOperatingState()
}

// Installs from before multiple thermostats stored a single one under "thermostat".
private List thermostatList() {
    return thermostats ?: (settings.thermostat ? [settings.thermostat] : [])
}

def allHandler(evt) {
    switch (evt.name) {
        case "heatingSetpoint":
        case "mode":
            updateOperatingState()
            break
        case "temperature":
            if (tempSensor) {
                thermostatList().each { if (it.hasCommand("setTemperature")) it.setTemperature(evt.value) }
            }
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
    BigDecimal hyst = (hysteresis ?: 1.0) as BigDecimal
    Map oldCalls = state.callStates ?: [:]
    Map calls = [:]
    Map temps = [:]
    thermostatList().each { tstat ->
        String id = tstat.id as String
        String current = oldCalls[id] ?: "idle"
        def temperature = (tempSensor ?: tstat).currentValue("temperature")
        def setpoint = tstat.currentValue("heatingSetpoint")
        if (temperature == null || setpoint == null) {
            log.warn "${app.label}: ${tstat} has no temperature (${temperature}) or heating setpoint (${setpoint}) yet - its call left as ${current}"
            calls[id] = current
            return
        }
        BigDecimal temp = temperature as BigDecimal
        BigDecimal target = setpoint as BigDecimal
        temps[id] = temp
        if (current == "heating" && temp >= target + hyst) current = "idle"
        else if (current == "idle" && temp <= target - hyst) current = "heating"
        calls[id] = current
    }
    state.callStates = calls

    String previous = state.zoneState ?: "idle"
    boolean anyContactSensorIsOpen = contactSensors?.any { it.currentValue("contact") == "open" } ?: false
    boolean presenceOk = !presenceSensors || presenceSensors.any { it.currentValue("motion") == "active" }
    List calling = thermostatList().findAll { calls[it.id as String] == "heating" }
    List awayCold = location.mode == "Away" ? thermostatList().findAll { temps[it.id as String] != null && temps[it.id as String] < (awayTemp as BigDecimal) } : []

    String zone
    String reason
    List heatingThermostats = []
    if (anyContactSensorIsOpen) {
        zone = "idle"; reason = "window open"
    } else if (calling) {
        if (presenceOk) {
            zone = "heating"; reason = "${calling.join(', ')}"; heatingThermostats = calling
        } else {
            zone = "idle"; reason = "restricted, no motion"
        }
    } else if (awayCold) {
        zone = "heating"; reason = "away heating: ${awayCold.join(', ')}"; heatingThermostats = awayCold
    } else {
        zone = "idle"; reason = "no thermostat calling"
    }
    applyState(zone, heatingThermostats)
    if (parent.logEnable && previous != zone) { log.debug "updateOperatingState of ${app.label}: ${previous} -> ${zone} (${reason})" }
    parent.updateHeatingPlantSwitch()
}

// Commands only what changes: the relay(s), and each thermostat's operating state when it accepts one.
private void applyState(String zoneState, List heatingThermostats) {
    state.zoneState = zoneState
    List heatingIds = heatingThermostats*.id
    thermostatList().each { tstat ->
        String wantedState = tstat.id in heatingIds ? "heating" : "idle"
        if (tstat.hasCommand("setThermostatOperatingState") && tstat.currentValue("thermostatOperatingState") != wantedState) {
            tstat.setThermostatOperatingState(wantedState)
        }
    }
    String wanted = zoneState == "heating" ? "on" : "off"
    relays?.each { relay ->
        if (relay.currentValue("switch") != wanted) {
            if (wanted == "on") relay.on() else relay.off()
        }
    }
}

// ======================================================================================
//  Used by Thermostat Master
// ======================================================================================

String getOperatingState() {
    return state.zoneState ?: "idle"
}

// settings.pump: a bare "pump" here would resolve to this getter and recurse.
def getPump() {
    return settings.pump
}

// A list: the Master subscribes to it and lists it among the zones requesting heat.
def getThermostatDevice() {
    return thermostatList()
}
