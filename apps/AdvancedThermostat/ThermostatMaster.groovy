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
            app(name: "childApps1", appName: "Thermostat Child", namespace: "myL2", title: "Add new TRV Thermostat (virtual thermostat + TRV)", submitOnChange: true, multiple: true)
            app(name: "childApps2", appName: "Thermostat Child Relay", namespace: "myL2", title: "Add new Relay Thermostat (thermostat + on/off relay)", submitOnChange: true, multiple: true)
        }
        section("Other Settings") {
        	input name: "logEnable", type: "bool", title: "Enable debug logging"
            input name: "contactDelay", type: "number", title: "Delay in seconds to process windows opened/closed", defaultValue: 10
            input name: "pumpDelay", type: "number", title: "Pump delay in seconds: start a distributor pump this long after its first valve opens, and keep it running this long after its last valve closes", defaultValue: 60
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
        subscribe(child.getThermostatDevice(), "thermostatOperatingState", allHandler)
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
    Map pumpDevices = [:]
    Map pumpDemand = [:]
    childApps.each {child ->
        // TRV and Relay children both expose getOperatingState() / getThermostatDevice() / getPump()
        def operatingState = child.getOperatingState()
        def pump = null
        try { pump = child.getPump() } catch (MissingMethodException e) { }   // child code older than the pump option
        if (pump) {
            String pumpId = pump.id as String
            pumpDevices[pumpId] = pump
            pumpDemand[pumpId] = pumpDemand[pumpId] || operatingState == "heating"
        }
        if (operatingState == "heating") {
            requestingAreas << [child.getThermostatDevice()]
            // Zones with a pump call the plant through their pump, once it runs.
            if (!pump) anyHeating = true
        }
    }
    if (updatePumps(pumpDevices, pumpDemand)) anyHeating = true
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

// A pump starts pumpDelay after its first zone calls (valves take time to open) and stops
// pumpDelay after its last zone stops. Returns true when any pump should be running.
private boolean updatePumps(Map pumpDevices, Map pumpDemand) {
    long delayMs = ((pumpDelay != null ? pumpDelay : 60) as Long) * 1000L
    long nowMs = now()
    // atomicState: children call in from their own executions, and plain state writes were lost between them.
    Map oldPumps = atomicState.pumps ?: [:]
    Map pumps = [:]
    boolean anyOn = false
    Long nextCheckMs = null
    for (entry in pumpDevices) {
        String pumpId = entry.key
        def pump = entry.value
        Map old = oldPumps[pumpId]
        // A pump seen for the first time keeps running if it already runs, rather than stopping for the delay.
        boolean wasOn = old != null ? (old.on ?: false) : pump.currentValue("switch") == "on"
        old = old ?: [:]
        Long since = old.since as Long
        Long idleSince = old.idleSince as Long
        boolean on
        if (pumpDemand[pumpId]) {
            since = since ?: nowMs
            idleSince = null
            on = wasOn || nowMs - since >= delayMs
            if (!on) nextCheckMs = Math.min(nextCheckMs ?: Long.MAX_VALUE, since + delayMs - nowMs)
        } else {
            since = null
            idleSince = wasOn ? (idleSince ?: nowMs) : null
            on = wasOn && nowMs - idleSince < delayMs
            if (on) nextCheckMs = Math.min(nextCheckMs ?: Long.MAX_VALUE, idleSince + delayMs - nowMs)
            else idleSince = null
        }
        pumps[pumpId] = [on: on, since: since, idleSince: idleSince]
        if (on) anyOn = true
        String wanted = on ? "on" : "off"
        if (pump.currentValue("switch") != wanted) {
            if (logEnable) { log.debug "pump ${pump} -> ${wanted.toUpperCase()}" }
            if (on) pump.on() else pump.off()
        }
    }
    atomicState.pumps = pumps
    if (nextCheckMs != null) runIn(Math.max(1L, (long) Math.ceil(nextCheckMs / 1000.0d)) as Integer, "pumpTimer", [overwrite: true])
    return anyOn
}

def pumpTimer() {
    updateHeatingPlantSwitch()
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
