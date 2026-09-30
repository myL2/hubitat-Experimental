definition(
    name: "Thermostat Master",
    namespace: "myL2",
    author: "SebyM",
    singleInstance: true,
    description: "Toggle one switch with the change of another",
    category: "Convenience",
    importUrl: "",
    iconUrl: "",
    iconX2Url: "")

preferences {
    page(name: "mainPage")
}

def mainPage(){
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section ("Heating Plant"){
            input "heatingPlant", "capability.switch", title: "Select Heating Plat Switch", submitOnChange: true, required: true
        }
        section ("Thermostats"){
            app(name: "childApps1", appName: "Thermostat Child", namespace: "myL2", title: "Add new Thermostat Child", submitOnChange: true, multiple: true)
        }
        section("Other Settings") {
        	input name: "logEnable", type: "bool", title: "Enable debug logging"
            input name: "contactDelay", type: "number", title: "Delay in seconds to process windows opened/closed", defaultValue: 10
    	}
    }
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    initialize()
}

def initialize() {
    log.debug "updated with  ${childApps.size()} valid device pairs"
    childApps.each {child ->
        log.debug "child app: ${child.label}"
        subscribe(child.switchDevice, "switch", allHandler)
    }
    updateHeatingPlantSwitch()
}

def allHandler(evt) {
    switch(evt.name){
        case "switch":
        updateHeatingPlantSwitch()
        break
    }
}

def updateHeatingPlantSwitch(){
    def requestingAreas = []
    def anyHeating = false
    childApps.each {child ->
        def switchState = child.switchDevice.currentValue("switch")
        if (switchState == "on") { 
            requestingAreas << [child.switchDevice]
            anyHeating = true 
        }
    }
    if (anyHeating){
        if (logEnable) { log.debug "Turning ${heatingPlant} -> ON (${requestingAreas})" }
        heatingPlant.on()
    }else{
        if (logEnable) { log.debug "Turning ${heatingPlant} -> OFF" }
        heatingPlant.off()
    }
}

def getContactSensorDelay(){
    return contactDelay
}

def logEnable(){
    return logEnable
}
