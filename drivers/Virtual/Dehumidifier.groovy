/*

Dehumidifier  (Hubitat virtual driver, namespace myL2)

A dashboard face + state holder for the "Smart Dehumidifier Manager" app.

It is a virtual DIMMER:
  - switch on/off  = manual start / stop of a cycle (override)
  - level (0-100)  = MINUTES REMAINING in the current cycle (counts down on the tile);
                     dragging the level starts a manual cycle of that many minutes.

Extra read-only attributes let one tile show the whole picture:
  humidity, humidityTarget, power, status, profile, minutesRemaining.

Drivers can't subscribe to other devices, so all logic lives in the parent app;
on/off/setLevel/setProfile here just call back into the parent.

*/

metadata
{
    definition(name: "Dehumidifier", namespace: "myL2", author: "myL2",
               importUrl: "https://raw.githubusercontent.com/myL2/hubitat-Experimental/main/drivers/Virtual/Dehumidifier.groovy")
    {
        capability "Switch"
        capability "SwitchLevel"
        capability "Actuator"
        capability "Refresh"

        attribute "humidity",         "number"   // current room humidity (%)
        attribute "humidityTarget",   "number"   // target humidity for the active profile (%)
        attribute "power",            "number"   // plug power draw (W)
        attribute "status",           "string"   // human-readable state
        attribute "profile",          "string"   // Bathroom / Pantry
        attribute "minutesRemaining", "number"   // same as level, named for clarity

        command "setProfile", [[name: "Profile*", type: "ENUM", constraints: ["Bathroom", "Pantry"]]]
    }
    preferences
    {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() { sendEvent(name: "switch", value: "off"); sendEvent(name: "level", value: 0) }
def updated()   { }

// ---- control: bubble everything up to the parent app ----

def on()  { if (logEnable) log.debug "device on() -> parent";  parent?.childOn() }
def off() { if (logEnable) log.debug "device off() -> parent"; parent?.childOff() }

def setLevel(level, duration = null)
{
    if (logEnable) log.debug "device setLevel(${level}) -> parent"
    parent?.childSetLevel(level as Integer)
}

def refresh()       { parent?.childRefresh() }
def setProfile(p)   { if (logEnable) log.debug "device setProfile(${p}) -> parent"; parent?.childSetProfile(p) }
