import groovy.json.*
import groovy.transform.Field


metadata {
	definition (name: "Virtual Power and Energy Meter", namespace: "myL2", author: "SebyM")
	{
        capability "EnergyMeter"
        capability "PowerMeter"
        
        attribute "energy", "number"
        attribute "power", "number"
        attribute "energyExact", "number"
        attribute "lastPowerUpdate", "number"
        attribute "lastPowerValue", "number"
        
        command "updateEnergy", [[name:"Power*", type: "NUMBER", description: "Current Power Consumption"]]
        command "setEnergy", [[name:"Energy*", type: "NUMBER", description: "Set Fixed Energy Value"]]
        
        command "reset"
        command "resetEnergy"
    }
}

// Integration bookkeeping (time and power of the last update, unrounded energy) lives in
// state: as attributes every updateEnergy() call produced 3 extra events, enough for the
// hub's "too many events" warning when fed every ~30 s. energyExact is still published,
// but only when the rounded energy changes.
def initialize() {
    state.lastPowerUpdate = now()
}

def installed() {
    state.lastPowerUpdate = now()
    state.lastPowerValue = 0
    state.energyExact = 0
    sendEvent(name: "energy", value: 0)
    sendEvent(name: "energyExact", value: 0)
}

def uninstalled() {
}

def updated() {
    state.lastPowerUpdate = now()
}

def refresh(){
}

def resetEnergy(){
    state.energyExact = 0
    sendEvent(name: "energy", value: 0)
    sendEvent(name: "energyExact", value: 0)
}

def reset(){
    state.lastPowerValue = 0
    state.energyExact = 0
    state.lastPowerUpdate = now()
    sendEvent(name: "energy", value: 0)
    sendEvent(name: "power", value: 0)
    sendEvent(name: "energyExact", value: 0)
}

def setEnergy(float energy) {
    state.energyExact = energy
    publishEnergy(energy)
}

def updateEnergy(float power) {
    migrateBookkeeping()
    sendEvent(name: "power", value: power)
    def r = (now() - (state.lastPowerUpdate as Long)) / 1000
    def p = state.lastPowerValue as BigDecimal
    def newEnergy = (state.energyExact as BigDecimal) + (p/1000/60/60*r)
    //log.debug "VEM: $r time passed, power was $power, lastPower was $p, newEnergy is $newEnergy"
    state.energyExact = newEnergy
    state.lastPowerValue = power
    state.lastPowerUpdate = now()
    publishEnergy(newEnergy)
}

// Attribute events only when the rounded value moves
private void publishEnergy(energy) {
    BigDecimal rounded = (energy as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP)
    if (device.currentValue("energy") == null || (device.currentValue("energy") as BigDecimal).compareTo(rounded as BigDecimal) != 0) {
        sendEvent(name: "energy", value: rounded)
        sendEvent(name: "energyExact", value: energy)
    }
}

// Before this change the bookkeeping was kept in attributes; take it over once.
private void migrateBookkeeping() {
    if (state.lastPowerUpdate == null) state.lastPowerUpdate = (device.currentValue("lastPowerUpdate") ?: now()) as Long
    if (state.lastPowerValue == null) state.lastPowerValue = (device.currentValue("lastPowerValue") ?: 0) as BigDecimal
    if (state.energyExact == null) state.energyExact = (device.currentValue("energyExact") ?: device.currentValue("energy") ?: 0) as BigDecimal
}
