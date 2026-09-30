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

def initialize() {
    sendEvent(name: "lastPowerUpdate", value: now())
}

def installed() {
    sendEvent(name: "lastPowerUpdate", value: now())
    sendEvent(name: "energy", value: 0)
    sendEvent(name: "energyExact", value: 0)
    sendEvent(name: "lastPowerValue", value: 0)    
}

def uninstalled() {
}

def updated() {
    sendEvent(name: "lastPowerUpdate", value: now())
}

def refresh(){
}

def resetEnergy(){
    sendEvent(name: "energy", value: 0)
    sendEvent(name: "energyExact", value: 0)
}

def reset(){
    sendEvent(name: "lastPowerValue", value: 0)
    sendEvent(name: "energy", value: 0)
    sendEvent(name: "power", value: 0)
    sendEvent(name: "energyExact", value: 0)
    sendEvent(name: "lastPowerUpdate", value: now())
}

def setEnergy(float energy) {
    sendEvent(name: "energyExact", value: energy)
    sendEvent(name: "energy", value: Math.round(energy*100)/100)
}

def updateEnergy(float power) {
    sendEvent(name: "power", value: power)
    r = (now() - device.currentValue("lastPowerUpdate")) / 1000
    p = device.currentValue("lastPowerValue")
    energy = device.currentValue("energyExact")
    newEnergy = (energy + (p/1000/60/60*r))
    //log.debug "VEM: $r time passed, power was $power, lastPower was $p, energy was $energy, newEnergy is $newEnergy"
    sendEvent(name: "energyExact", value: newEnergy)
    sendEvent(name: "energy", value: Math.round(newEnergy*100)/100)
    sendEvent(name: "lastPowerValue", value: power)
    sendEvent(name: "lastPowerUpdate", value: now())
}
