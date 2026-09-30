
metadata {
    definition (
        name: "Advanced Virtual Thermostat",
        namespace: "myL2",
        author: "SebyM"
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "Temperature Measurement"
        capability "Thermostat"

        command "setTemperature", ["NUMBER"]
        command "setThermostatOperatingState", ["String"]
        command "initialize"
        
//        attribute "SupportedThermostatFanModes","String"
//        attribute "SupportedThermostatModes","String"
    }

    preferences {
        input( name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false)
        input( name: "txtEnable", type:"bool", title: "Enable descriptionText logging", defaultValue: true)
    }
}

import groovy.json.JsonOutput
import java.time.Instant

def installed() {
    log.info "installed..."
    initialize()
}

def updated() {
    log.info "updated..."
    log.info "debug logging is: ${logEnable == true}"
    unschedule()
    if (logEnable)
        runIn(1800, logsOff)
}

def initialize() {
    log.info "initializing..."

    if (state?.current != null) return

    state.current = [:]
    state.current.mode = "off"
    state.current.temperature = 20
    state.current.heatingSetpoint = state.current.temperature
    state.current.coolingSetpoint = 30

    state.operatingState = "idle"

    // Publish that state.
    def text = ""
    def events = []
    events << [name:"thermostatMode", value:"${state.current.mode}", descriptionText:text]
    events << [name:"temperature", value:"${state.current.temperature}", descriptionText:text]
    events << [name:"thermostatSetpoint", value:"${state.current.heatingSetpoint}", descriptionText:text]
    events << [name:"heatingSetpoint", value:"${state.current.heatingSetpoint}", descriptionText:text]
    events << [name:"coolingSetpoint", value:"${state.current.coolingSetpoint}", descriptionText:text]
    events << [name:"thermostatOperatingState", value:"${state.operatingState}", descriptionText:text]
    events << [name:"thermostatFanMode", value:"auto", descriptionText:text]
    events << [name:"supportedThermostatFanModes", value:JsonOutput.toJson(["auto","circulate","on"]), descriptionText:text]
    events << [name:"supportedThermostatModes", value:JsonOutput.toJson(["auto", "cool", "emergency heat", "heat", "off"]), descriptionText:text]
    sendEvents events
}

def logsOff() {
    log.info "debug logging disabled..."
    device.updateSetting("logEnable", [value:"false", type:"bool"])
}

def setTemperature(temperature) {
    if (temperature == null) {
        log.warn "null temperature update ignored"
        return
    }

    state.current.temperature = Float.parseFloat("${temperature}")

    def events = []
    def u = "°${getTemperatureScale()}"
    events << [
        name:"temperature",
        value:"${state.current.temperature}",
        unit:"${u}",
        descriptionText:"temperature set to ${state.current.temperature}${u}"
    ]
    sendEvents events
}

def auto() { setThermostatMode("auto") }

def cool() { setThermostatMode("cool") }

def emergencyHeat() { setThermostatMode("emergency heat") }

def heat() { setThermostatMode("heat") }
def off() { setThermostatMode("off") }

def setThermostatOperatingState(state){
    def events = []
    events << [name:"thermostatOperatingState", value:state]
    sendEvents events
}

def setThermostatMode(mode) {
    if (!(mode in ["off", "auto", "cool", "heat", "emergency heat"])) {
        log.warn "Unknown mode, ${mode}, ignored"
        return
    }
    if (mode == "emergency heat") {
        mode = "heat"
    }

    state.current.mode = mode

    def events = []
    events << [name:"thermostatMode", value:state.current.mode]
    sendEvents events
}

def fanAuto() { setThermostatFanMode("auto") }
def fanCirculate() { setThermostatFanMode("circulate") }
def fanOn() { setThermostatFanMode("on") }

def setThermostatFanMode(fanMode) {
    if (!(fanMode in ["auto", "circulate", "on"])) {
        log.warn "unknown fan-mode, ${fanMode}, ignored"
        return
    }

    def events = []
    events << [name:"thermostatFanMode", value:fanMode]
    sendEvents events
}

def setCoolingSetpoint(setpoint) {
    if (setpoint == null) {
        log.warn "null cooling set-point update ignored"
        return
    }

    def events = []
    events << [name:"setCoolingSetpoint", value:"${setpoint}"]

    sendEvents events
}

def setHeatingSetpoint(setpoint) {
    if (setpoint == null) {
        log.warn "null heating set-point update ignored"
        return
    }

    def events = []
	events << [name:"heatingSetpoint", value:"${setpoint}"]
    sendEvents events
}

def parse(String description) {
    log.err "$description"
}

private logDebug(msg) {
    if (settings.logEnable) log.debug "${msg}"
}

private sendEvents(events) {
    for (event in events) {
        def descriptionText = event?.descriptionText ?: "${event.name} set to ${event.value}"
        logDebug descriptionText

        Map e = event.findAll {it -> it.key != "descriptionText"}
        if (settings.txtEnable) {
            e.descriptionText = descriptionText
        }

        sendEvent e
    }
}
