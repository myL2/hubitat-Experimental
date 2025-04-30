import groovy.json.*
import groovy.transform.Field


metadata {
	definition (name: "Tuya Zigbee Power Meter Child", namespace: "myL2", author: "SebyM")
	{
        capability 'EnergyMeter'
        capability 'PowerMeter'
        capability 'CurrentMeter'
        capability 'VoltageMeasurement'
        
        attribute "energyExact", "number"
        attribute "lastPowerUpdate", "number"
        attribute "lastPowerValue", "number"
        attribute "energyReport", "number"
        
        command "resetEnergy"
        
        command "setEnergy", [[name:"Energy*", type: "NUMBER", description: "Set Fixed Energy Value"]]
    }
    
    preferences {
        input name: "debugOutput", type: "bool", title: "Enable debug logging?", defaultValue: false
    }
}

def resetEnergy(){
    sendEvent(name: "lastPowerValue", value: 0)
    sendEvent(name: "energy", value: 0)
    sendEvent(name: "energyExact", value: 0)
    sendEvent(name: "lastPowerUpdate", value: now())
}

def initialize() {
    logDebug "Tuya Zigbee Power Meter Child/Initialize"
    resetEnergy()
}

def installed() {
    logDebug "Tuya Zigbee Power Meter Child/Installed"
    resetEnergy()
}

def uninstalled() {
    unschedule()
    logDebug "Tuya Zigbee Power Meter Child/Uninstalled"
}

def updated() {
    log.info "Preferences updated..."
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
    logDebug "updateEnergy: $r time passed, power was $power, lastPower was $p, energy was $energy, newEnergy is $newEnergy"
    sendEvent(name: "energyExact", value: newEnergy)
    sendEvent(name: "energy", value: Math.round(newEnergy*100)/100)
    sendEvent(name: "lastPowerValue", value: power)
    sendEvent(name: "lastPowerUpdate", value: now())
}

def updateValue(command, value){
    Map map = [:]
    if(command == "energy"){
    	map.name = 'energyReport'
    }else{
        map.name = command
    }
    if(command == "power"){
		map.value = (value/10 as Float)
        updateEnergy(map.value)
    }else{
    	map.value = value
    }
    map.isStateChange = true
    map.descriptionText = "${map.name} is ${map.value}"

    sendEvent(map)
}

private logDebug(msg) {
    if (settings?.debugOutput || settings?.debugOutput == null) {
        log.debug "$msg"
    }
}