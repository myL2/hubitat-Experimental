definition(
    name: "Thermostat Child",
    namespace: "myL2",
    parent: "myL2:Thermostat Master",
    author: "SebyM",
    description: "Thermostat Child",
    category: "Convenience",
    importUrl: "",
    iconUrl: "",
    iconX2Url: "")

preferences {
    page(name: "mainPage")
}


def mainPage() {
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section("Choose devices") {
                input "virtualDevice", "capability.thermostat", title: "* Select Virtual Thermostat (Master)", submitOnChange: true, required: true
                input "physicalDevice", "capability.thermostat", title: "* Select Physical Thermostat (Slave)", submitOnChange: true, required: true
            	input "contactSensors", "capability.contactSensor", title: "Select Contact Sensor (that must be closed to start heating)", submitOnChange: true, required: false, multiple: true
            	input "presenceSensors", "capability.motionSensor", title: "Select Motion Sensor (that must be active to start heating)", submitOnChange: true, required: false, multiple: true
      
        }
         section("Other settings") {
            	input name: "hysteresis", type: "decimal", title: "Set Hysteresis (Difference to start/stop, in degrees 0.5 - 5.0)", defaultValue: 1.0, required: true
             	input name: "awayTemp", type: "number", title: "Set Temperature for Away Mode (AND for when there is no motion)", defaultValue: 20	
            	input name: "lowMin", type: "number", title: "Set Thermostat to this value to CLOSE", defaultValue: 15
                input name: "highMax", type: "number", title: "Set Thermostat to this value to OPEN", defaultValue: 25

            	if(physicalDevice && virtualDevice){
                	state.error = false
            	}
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
    if(!state.error){
        subscribe(virtualDevice, "heatingSetpoint", allHandler)
        subscribe(virtualDevice, "coolingSetpoint", allHandler)
        subscribe(virtualDevice, "thermostatSetpoint", allHandler)
        subscribe(physicalDevice, "temperature", allHandler)
        //subscribe(virtualDevice, "thermostatOperatingState", allHandler)
        for(contactSensor in contactSensors){
        	subscribe(contactSensor, "contact", allHandler)
        }
        for(presenceSensor in presenceSensors){
        	subscribe(presenceSensor, "motion", allHandler)
        }
        log.debug "initialized for ${virtualDevice} -> ${physicalDevice} with settings: hysteresis: ${hysteresis}, awayTemp: ${awayTemp}, lowMin: ${lowMin}, highMax: ${highMax}"
        updateAppName("${physicalDevice} -> ${virtualDevice}")
        updateOperatingState()
    }
    else{
        updateAppName("ERROR: ${state.errorMsg}")
    }
}

def allHandler(evt) {
    if (logEnable) { log.debug evt }
    switch(evt.name){
        //case "thermostatSetpoint":
        case "heatingSetpoint":
        //virtualDevice.setTemperature(physicalDevice.currentValue("temperature"))
        updateOperatingState()
        break
        case "thermostatOperatingState":
        //updateOperatingState()
        break
        case "temperature":
        virtualDevice.setTemperature(evt.value)
        updateOperatingState()
        break
        case "contact":
        case "motion":
        unschedule(updateOperatingState)
        runIn(parent.getContactSensorDelay(), updateOperatingState, [overwrite: true, misfire: 'ignore'])
        break
    }
}

def updateOperatingState(){
    def currentState = virtualDevice.currentValue("thermostatOperatingState")
    def evtValue = currentState
    unschedule(updateOperatingState)
    
    //log.debug "${virtualDevice} updateOperatingState ${currentState}"
    
    if(currentState == "heating"){
        if(virtualDevice.currentValue("temperature") >= (virtualDevice.currentValue("heatingSetpoint") + hysteresis)){
            evtValue = "idle"
            //if (logEnable) { log.debug "changing to is ${evtValue}" }
        }
    } else{
        if(currentState == "idle"){
            if(virtualDevice.currentValue("temperature") <= (virtualDevice.currentValue("heatingSetpoint") - hysteresis)){
                evtValue = "heating"
                //if (logEnable) { log.debug "changing to is ${evtValue}" }
            }
        }
    }
    
    def anyContactSensorIsOpen = false
    for(contactSensor in contactSensors){
        if (contactSensor.currentValue("contact") == "open"){
            if (logEnable) { log.debug "${contactSensor} is open" }
            anyContactSensorIsOpen = true;
        }
    }

    if(anyContactSensorIsOpen){
        virtualDevice.setThermostatOperatingState("idle")
        physicalDevice.setHeatingSetpoint(lowMin)
        if (parent.logEnable && currentState != "idle") { log.debug "updateOperatingState of ${virtualDevice}: ${currentState} -> idle (window open)" }
    } else if(evtValue == "heating"){
     	def anyPresenceSensorIsActive = false
        for(presenceSensor in presenceSensors){
            if (presenceSensor.currentValue("motion") == "active"){
                if (logEnable) { log.debug "${presenceSensor} is active" }
                anyPresenceSensorIsActive = true;
            }
        }
        if(anyPresenceSensorIsActive){
            virtualDevice.setThermostatOperatingState("heating")
            physicalDevice.setHeatingSetpoint(highMax)
            if (parent.logEnable && currentState != "heating") { log.debug "updateOperatingState of ${virtualDevice}: ${currentState} -> heating" }
        }else{
            virtualDevice.setThermostatOperatingState("idle")
            physicalDevice.setHeatingSetpoint(lowMin)
            if (parent.logEnable && currentState != "idle") { log.debug "updateOperatingState of ${virtualDevice}: ${currentState} -> restricted idle" }
        }
    } else {
        if(location.mode == "Away" && virtualDevice.currentValue("temperature") < awayTemp){
            virtualDevice.setThermostatOperatingState("heating")
            physicalDevice.setHeatingSetpoint(highMax)
            if (parent.logEnable && currentState != "heating") { log.debug "updateOperatingState of ${virtualDevice}: ${currentState} -> away heating" }
        }else{
            virtualDevice.setThermostatOperatingState("idle")
            physicalDevice.setHeatingSetpoint(lowMin)
            if (parent.logEnable && currentState != "idle") { log.debug "updateOperatingState of ${virtualDevice}: ${currentState} -> idle" }
        }
    }
    //parent.updateHeatingPlantSwitch()
}

def updateAppName(def string){
    app.updateLabel(string)
}

// used in the parent app to check for endless looping
def getMasterId(){
    master.getId()
}

