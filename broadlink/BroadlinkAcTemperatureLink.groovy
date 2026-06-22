/*

Broadlink AC Companion  (Hubitat app, namespace myL2)

Two jobs for a "Broadlink AC" device:

  1. Room temperature: feed the actual room temperature from one or more sensors
     into the AC (so the thermostat tile shows real temp, not the setpoint).

  2. Presence + hub mode automation:
       - Absence (mmwave motion -> inactive) while the AC is ON and the current
         hub mode is one of the selected modes  -> switch AC to eco (AC24OnEco),
         remembering the prior state.
       - Presence (motion -> active)            -> restore the AC's last state
         (only if it is still on; if it was turned off, leave it off).
       - Hub mode changes to a mode NOT selected -> turn the AC off.

Drivers can't subscribe to other devices, so this app does the wiring and calls
the AC's commands (setRoomTemperature / awayEco / on / off).

*/

definition(
    name: "Broadlink AC Companion",
    namespace: "myL2",
    author: "myL2",
    description: "Room-temperature feed + presence/hub-mode automation for a Broadlink AC",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/myL2/hubitat-Experimental/main/broadlink/BroadlinkAcTemperatureLink.groovy"
)

preferences
{
    page(name: "mainPage")
}

def mainPage()
{
    dynamicPage(name: "mainPage", title: "Broadlink AC Companion", install: true, uninstall: true)
    {
        section("Air conditioner")
        {
            input name: "acDevice", type: "capability.thermostat", title: "Broadlink AC device", required: true, multiple: false
        }
        section("Room temperature feed")
        {
            input name: "sensors", type: "capability.temperatureMeasurement", title: "Room temperature sensor(s)", required: false, multiple: true,
                  description: "average is used if more than one"
            input name: "offset", type: "decimal", title: "Temperature offset to add (°, optional)", required: false, defaultValue: 0
        }
        section("Presence")
        {
            input name: "presenceSensor", type: "capability.motionSensor", title: "Presence sensor (mmwave) - uses 'motion'", required: false, multiple: false
        }
        section("Hub modes")
        {
            input name: "activeModes", type: "mode", title: "Modes in which the AC may run", required: false, multiple: true,
                  description: "absence->eco applies in these; entering any OTHER mode turns the AC off. Leave empty to disable mode control."
        }
        section("Options")
        {
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        }
        section()
        {
            def bits = []
            if(sensors) bits << "source temp: ${currentAverage() ?: 'n/a'}"
            if(presenceSensor) bits << "motion: ${presenceSensor.currentValue('motion') ?: 'n/a'}"
            bits << "hub mode: ${location.mode} (${isActiveMode(location.mode) ? 'active' : 'NOT active'})"
            if(state.away) bits << "currently in away-eco"
            paragraph bits.join("\n")
        }
    }
}

def installed() { initialize() }

def updated()
{
    unsubscribe()
    initialize()
}

def initialize()
{
    if(sensors)        { subscribe(sensors, "temperature", sensorHandler) }
    if(presenceSensor) { subscribe(presenceSensor, "motion", motionHandler) }
    if(activeModes)    { subscribe(location, "mode", modeChangeHandler) }

    state.away = (state.away ?: false)
    pushTemperature()
}

//////////////////////////////////////
// room temperature feed
//////////////////////////////////////

def sensorHandler(evt)
{
    if(logEnable) log.debug "sensor ${evt.displayName} -> ${evt.value}"
    pushTemperature()
}

def currentAverage()
{
    def vals = sensors?.collect { it.currentValue("temperature") }?.findAll { it != null }
    if(!vals) { return null }

    def avg = (vals.sum() as BigDecimal) / vals.size()
    return (avg + ((offset ?: 0) as BigDecimal)).setScale(1, java.math.RoundingMode.HALF_UP)
}

def pushTemperature()
{
    def t = currentAverage()
    if(null == t) { return }

    if(logEnable) log.debug "pushing room temperature ${t} to ${acDevice}"
    acDevice?.setRoomTemperature(t)
}

//////////////////////////////////////
// presence
//////////////////////////////////////

def motionHandler(evt)
{
    if(logEnable) log.debug "motion -> ${evt.value}"

    if("inactive" == evt.value) { onAbsence() }
    else if("active" == evt.value) { onPresence() }
}

def onAbsence()
{
    if(!acDevice) { return }
    if(state.away) { return }                                   // already away
    if(acDevice.currentValue("switch") != "on") { return }      // only if previously On
    if(!isActiveMode(location.mode)) { return }                 // only in selected hub modes

    if(logEnable) log.debug "absence -> AC awayEco"
    state.away = true
    acDevice.awayEco()
}

def onPresence()
{
    if(!state.away) { return }
    state.away = false

    // restore only if the AC is still on (if it was turned off while away, leave it off)
    if(acDevice?.currentValue("switch") == "on")
    {
        if(logEnable) log.debug "presence -> restore AC last state"
        acDevice.on()
    }
}

//////////////////////////////////////
// hub mode
//////////////////////////////////////

def modeChangeHandler(evt)
{
    def m = evt.value
    if(logEnable) log.debug "hub mode -> ${m}"

    if(!isActiveMode(m))
    {
        if(logEnable) log.debug "mode ${m} not in active list -> AC off"
        acDevice?.off()
        state.away = false
    }
}

// no modes selected => mode control disabled (every mode treated as active)
private boolean isActiveMode(m) { !activeModes || activeModes.contains(m) }
