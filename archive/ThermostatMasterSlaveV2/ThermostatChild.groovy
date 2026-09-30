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
                input "switchDevice", "capability.switch", title: "* Select Zone Valve Switch (that will open when heating required)", submitOnChange: true, required: true
                input "channelDevices", "capability.thermostat", title: "* Select Physical Thermostats (Rooms/Zones)", submitOnChange: true, required: true, multiple: true
            	input "contactSensors", "capability.contactSensor", title: "Select Contact Sensor (that must be closed to start heating)", submitOnChange: true, required: false, multiple: true
            	input "presenceSensors", "capability.motionSensor", title: "Select Motion Sensor (that must be active to start heating)", submitOnChange: true, required: false, multiple: true
      
        }
         section("Other settings") {
             	input name: "awayTemp", type: "number", title: "Set Temperature for Away Mode (AND for when there is no motion)", defaultValue: 20	

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
        for(channelDevice in channelDevices){
        	subscribe(channelDevice, "heatingSetpoint", allHandler)
            subscribe(channelDevice, "temperature", allHandler)
            subscribe(channelDevice, "thermostatOperatingState", allHandler)
        }
        for(contactSensor in contactSensors){
        	subscribe(contactSensor, "contact", allHandler)
        }
        for(presenceSensor in presenceSensors){
        	subscribe(presenceSensor, "motion", allHandler)
        }
        log.debug "initialized for ${switchDevice} -> ${channelDevices.size()} thermostats, settings: awayTemp: ${awayTemp}"
        updateAppName("${switchDevice}")
        updateOperatingState()
    }
    else{
        updateAppName("ERROR: ${state.errorMsg}")
    }
}

def allHandler(evt) {
    if (logEnable) { log.debug evt }
    switch(evt.name){
        case "heatingSetpoint":
        case "thermostatOperatingState":
        case "temperature":
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
    def requestingAreas = []
    def anyHeating = false
    channelDevices.each {channelDevice ->
        def operatingState = channelDevice.currentValue("thermostatOperatingState")
        if (operatingState == "heating") { 
            requestingAreas << [channelDevice]
            anyHeating = true 
        }
    }
    if (anyHeating){
        if (logEnable) { log.debug "Turning ${switchDevice} -> ON (${requestingAreas})" }
        switchDevice.on()
    }else{
        if (logEnable) { log.debug "Turning ${switchDevice} -> OFF" }
        switchDevice.off()
    }
}

def updateAppName(def string){
    app.updateLabel(string)
}

// used in the parent app to check for endless looping
def getMasterId(){
    master.getId()
}

