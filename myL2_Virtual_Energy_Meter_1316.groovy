import groovy.json.*
import groovy.transform.Field


metadata {
	definition (name: "Virtual Energy Meter", namespace: "myL2", author: "SebyM")
	{
        capability "EnergyMeter"
        
        attribute "energy", "number"
        attribute "energyExact", "number"
        attribute "lastPowerUpdate", "number"
        attribute "lastPowerValue", "number"
        
        command "updateEnergy", [[name:"Power*", type: "NUMBER", description: "Current Power Consumption"]]
        command "setEnergy", [[name:"Energy*", type: "NUMBER", description: "Set Fixed Energy Value"]]
        
        command "reset"
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

def reset(){
    sendEvent(name: "lastPowerValue", value: 0)
    sendEvent(name: "energy", value: 0)
    sendEvent(name: "energyExact", value: 0)
    sendEvent(name: "lastPowerUpdate", value: now())
}

def setEnergy(float energy) {
    sendEvent(name: "energyExact", value: energy)
    sendEvent(name: "energy", value: Math.round(energy*100)/100)
}

def updateEnergy(float power) {
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
