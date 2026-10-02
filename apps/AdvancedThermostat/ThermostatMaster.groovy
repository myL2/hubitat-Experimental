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

@groovy.transform.Field static final int PLANT_VERIFY_DELAY = 15   // seconds to wait for the plant to report
@groovy.transform.Field static final int PLANT_MAX_ATTEMPTS = 3

preferences {
    page(name: "mainPage")
}

def mainPage(){
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section ("Heating Plant"){
            input "heatingPlant", "capability.switch", title: "Select Heating Plat Switch", submitOnChange: true, required: true
            input "heatingPlantHealth", "capability.healthCheck", title: "Heating plant relay (optional): warn when its healthStatus goes offline", required: false
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
    unschedule()
    initialize()
}

def initialize() {
    state.remove("plantRefreshedAt"); state.remove("plantStaleWarnedAt")
    log.debug "updated with  ${childApps.size()} valid device pairs"
    childApps.each {child ->
        log.debug "child app: ${child.label}"
        subscribe(child.virtualDevice, "thermostatOperatingState", allHandler)
    }
    if (heatingPlantHealth) subscribe(heatingPlantHealth, "healthStatus", plantHealthHandler)
    updateHeatingPlantSwitch()
    runEvery5Minutes(updateHeatingPlantSwitch)
}

// The relay driver pings the device periodically; offline = several unanswered pings.
def plantHealthHandler(evt) {
    if (evt.value == "offline") {
        log.warn "Heating plant relay '${evt.device}' is offline (health-check pings unanswered); heating plant state '${heatingPlant.currentValue("switch")}' may be stale"
    } else if (evt.value == "online") {
        log.info "Heating plant relay '${evt.device}' is back online"
    }
}

def allHandler(evt) {
    switch(evt.name){
        case "thermostatOperatingState":
        updateHeatingPlantSwitch()
        break
    }
}

def updateHeatingPlantSwitch(){
    def requestingAreas = []
    def anyHeating = false
        childApps.each {child ->
            def operatingState = child.virtualDevice.currentValue("thermostatOperatingState")
            if (operatingState == "heating") { 
                requestingAreas << [child.virtualDevice]
                anyHeating = true 
            }
        }
        // Only command the plant when its reported state differs; the 5-minute schedule
        // still corrects it if a command was missed.
        String wanted = anyHeating ? "on" : "off"
        state.plantWanted = wanted
        if (heatingPlant.currentValue("switch") == wanted) return
        if (logEnable) { log.debug "heatingPlant -> ${wanted.toUpperCase()}${anyHeating ? " (${requestingAreas})" : ''}" }
        sendPlantCommand(wanted)
        state.plantAttempts = 1
        runIn(PLANT_VERIFY_DELAY, "verifyHeatingPlant")
}

// Like Reliable Locks: check the plant reported the commanded state; if not, refresh
// and resend, and warn when it never confirms (e.g. relay offline).
def verifyHeatingPlant() {
    String wanted = state.plantWanted
    Integer attempts = (state.plantAttempts ?: 1) as Integer
    if (heatingPlant.currentValue("switch") == wanted) {
        if (attempts > 1) log.info "Heating plant confirmed '${wanted}' after ${attempts} attempts"
        state.plantAttempts = 0
        return
    }
    if (attempts < PLANT_MAX_ATTEMPTS) {
        if (logEnable) { log.debug "heatingPlant did not confirm '${wanted}' (attempt ${attempts}), refreshing and resending" }
        heatingPlant.refresh()
        sendPlantCommand(wanted)
        state.plantAttempts = attempts + 1
        runIn(PLANT_VERIFY_DELAY, "verifyHeatingPlant")
    } else {
        log.warn "Heating plant '${heatingPlant}' did not confirm '${wanted}' after ${attempts} attempts (reports '${heatingPlant.currentValue("switch")}'${heatingPlantHealth ? ", relay health '${heatingPlantHealth.currentValue("healthStatus")}'" : ''}); will retry on the next 5-minute check"
        state.plantAttempts = 0
    }
}

private void sendPlantCommand(String wanted) {
    if (wanted == "on") heatingPlant.on() else heatingPlant.off()
}

def getContactSensorDelay(){
    return contactDelay
}

def logEnable(){
    return logEnable
}
