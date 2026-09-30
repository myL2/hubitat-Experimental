	
definition(
    name: "MQTT to OsmAnd",
	parent: "",
    namespace: "myL2",
    author: "SebyM",
    description: "",
    category: "",
	iconUrl: "",
    iconX2Url: "",
    iconX3Url: "")

def latitudeDevice = [
		name:				"latitudeDevice",
		type:				"capability.sensor",
		title:				"Choose latitude MQTT Device",
		multiple:			false,
		required:			false
	]
def longitudeDevice = [
		name:				"longitudeDevice",
		type:				"capability.sensor",
		title:				"Choose longitude MQTT Device",
		multiple:			false,
		required:			false
	]
def engineOnDevice = [
		name:				"engineOnDevice",
		type:				"capability.sensor",
		title:				"Choose engineOn MQTT Device",
		multiple:			false,
		required:			false
	]
def chargeDevice = [
		name:				"chargeDevice",
		type:				"capability.sensor",
		title:				"Choose Charge MQTT Device",
		multiple:			false,
		required:			false
	]
def uniqueID = [
		name:				"uniqueID",
		type:				"text",
		title:				"Unique identifier",
		defaultValue:		"",
		required:			true
	]
def server = [
		name:				"server",
		type:				"text",
		title:				"Server string",
		defaultValue:		"",
		required:			true
	]
def enableLogging = [
		name:				"enableLogging",
		type:				"bool",
		title:				"Enable Debug Logging",
		defaultValue:		false,
		required:			true
	]

preferences {
    	section() {
            input server
            input uniqueID
        }
			section(hideable: true, "Select devices:") {
			input latitudeDevice
			input longitudeDevice
			input engineOnDevice
			input chargeDevice
		}
    	section() {
            input enableLogging
		}
}


def installed() {
	log.info "Installed with settings: ${settings}"
	initialize()
}


def updated() {
	log.info "Updated with settings: ${settings}"
	initialize()
}


def initialize() {
	unsubscribe()
    state.remove("gotLatitude")
    state.remove("gotLongitude")
    state.latitude = state.lastLatitude = latitudeDevice.currentValue("payload").toDouble()
    state.longitude = state.lastLongitude = longitudeDevice.currentValue("payload").toDouble()
    state.engineOn = (engineOnDevice.currentValue("payload") == "yes" ? true : false)
    state.charge = chargeDevice.currentValue("payload").toDouble()
    state.gotLatitude = state.gotLongitude = false
	subscribe(latitudeDevice, "payload", payloadChangedHandler)
	subscribe(longitudeDevice, "payload", payloadChangedHandler)
	subscribe(engineOnDevice, "payload", payloadChangedHandler)
	subscribe(chargeDevice, "payload", payloadChangedHandler)
}

def isMoving(double precision){
    def distance = calculateDistanceBetweenPoints(state.lastLatitude.toDouble(), state.lastLongitude.toDouble(), state.latitude.toDouble(), state.longitude.toDouble())
    log "Distance is ${distance}"
    return distance >= precision
}

def calculateDistanceBetweenPoints(double userLat, double userLng, double venueLat, double venueLng) {
    double latDistance = Math.toRadians(userLat - venueLat);
    double lngDistance = Math.toRadians(userLng - venueLng);
    double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2) + Math.cos(Math.toRadians(userLat)) * Math.cos(Math.toRadians(venueLat))* Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2);
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return 6371 * c;
}

def triggerUpdate(){
    long unixTime = now() / 1000L;
    def params = [uri: "http://${server}/?id=${uniqueID}&timestamp=${unixTime}&lat=${state.latitude}&lon=${state.longitude}" + (state.charge!=null ? "&batt=${state.charge}" : "") + (state.engineOn!=null ? "&ignition=${state.engineOn}" : "")]
    log.info params
    try {
        httpGet(params) {
            
        	}
        } catch (e) {
        log.error "something went wrong: $e"
    }
}

def checkLocation(){
    double precision = 1.0 //in km
    log "Running checkLocation with Lat: ${state.gotLatitude} and Lon: ${state.gotLongitude}"
    if (state.gotLatitude && state.gotLongitude){
        if(isMoving(precision)){
    		triggerUpdate()
        	state.gotLatitude = state.gotLongitude = false
        }
    }
}

def payloadChangedHandler(evt) {
    def payload = evt.device.currentValue("payload")
    log "Payload for ${evt.device.name} is now ${payload}"
    switch(evt.device.name){
        case latitudeDevice.name: 
        if(state.latitude == null){
            state.latitude = state.lastLatitude = payload.toDouble()
        }else{
            if(!state.gotLatitude) {state.lastLatitude = state.latitude}
			state.latitude = payload.toDouble()
            state.gotLatitude = true
			runIn(10, checkLocation)
        }
        break;
        case longitudeDevice.name: 
        if(state.longitude == null){
            state.longitude = state.lastLongitude = payload.toDouble()
        }else{
            if(!state.gotLongitude) {state.lastLongitude = state.longitude}
			state.longitude = payload.toDouble()
            state.gotLongitude = true
			runIn(10, checkLocation)
        }
        break;
        case engineOnDevice.name:
        	state.engineOn = (payload == "yes" ? true : false)
        	triggerUpdate();
        break;
        case chargeDevice.name:
        	state.charge = payload.toDouble()
        	triggerUpdate();
        break;
        default: 
            log "Unhandled device reported payload change: ${evt.device.name}"
        break;
    }
}

def log(msg) {
	if (enableLogging) {
		log.debug msg
	}
}