import groovy.json.*
import groovy.transform.Field

metadata {
	definition (
		name: "Shelly PLUS 2/2.5 as Roller Shutter",
		namespace: "myL2",
		author: "SebyM"
		)
	{
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "Switch Level"
        capability "Switch"
        capability "Window Shade"
        capability "Polling"
        capability "PowerSource"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "VoltageMeasurement"
        capability "SignalStrength"
        capability "TemperatureMeasurement"
        
        command "stop"
        command "UpdateDeviceFW" // ota?update=1
        command "calibrate"
        
        attribute "mode", "string"
        attribute "level","number"
        attribute "switch","string"
        attribute "windowShade","string"
        attribute "obstacle_power","string"
        attribute "safety_action","string"
        attribute "state","string"
        attribute "obstacle_mode","string"
        attribute "obstacle_action","string"
        attribute "stop_reason","string"
        attribute "safety_switch","string"
        attribute "safety_mode","string"
        attribute "safety_allowed_on_trigger","string"
        attribute "obstacle_delay","string"
        attribute "power","number"
        attribute "WiFiSignal","string"
        attribute "IP","number"
        attribute "SSID","string"
        attribute "MAC","string"
	}

	preferences {
	def refreshRate = [:]
		refreshRate << ["1 min" : "Refresh every minute"]
		refreshRate << ["5 min" : "Refresh every 5 minutes"]
		refreshRate << ["15 min" : "Refresh every 15 minutes"]
		refreshRate << ["30 min" : "Refresh every 30 minutes"]

	input("ip", "string", title:"IP", description:"Shelly IP Address", defaultValue:"" , required: false)
	input name: "username", type: "text", title: "Username:", description: "(blank if none)", required: false
	input name: "password", type: "password", title: "Password:", description: "(blank if none)", required: false
	input("preset", "number", title: "Pre-defined position (1-100)", defaultValue: 50, required: false)
	input("closedif", "number", title: "Closed if at most (1-100)", defaultvalue: 5, required: false)
	input("openif", "number", title: "Open if at least (1-100)", defaultvalue: 85, required: false)
    input("refresh_Rate", "enum", title: "Device Refresh Rate", options: refreshRate, defaultValue: true, required: true)
	input("locale", "enum", title: "Choose refresh date format", defaultValue: true, options: [US:"US MM/DD/YYYY",UK:"UK DD/MM/YYYY"], required: true)
	input name: "debugOutput", type: "bool", title: "Enable debug logging?", defaultValue: true
	input name: "debugParse", type: "bool", title: "Enable JSON parse logging?", defaultValue: true
	input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
	}
}

def initialize() {
	log.info "initialize"
	if (txtEnable) log.info "initialize"
}

def installed() {
    log.debug "Installed"
}

def uninstalled() {
    unschedule()
    log.debug "Uninstalled"
}

// App Version   *********************************************************************************
def setVersion(){
	state.Version = "2.0.2"
	state.InternalName = "ShellyAsARoller"
}

def updated() {
    log.debug "Updated"
    log.info "Preferences updated..."
    log.warn "Debug logging is: ${debugOutput == true}"
    log.warn "Debug Parse logging is: ${debugParse == true}"
    unschedule()

    switch(refresh_Rate) {
		case "1 min" :
			runEvery1Minute(autorefresh)
			break
		case "5 min" :
			runEvery5Minutes(autorefresh)
			break
		case "15 min" :
			runEvery15Minutes(autorefresh)
			break
		default:
			runEvery30Minutes(autorefresh)
	}
	if (txtEnable) log.info ("Auto Refresh set for every ${refresh_Rate} minute(s).")

    logDebug runIn(1800,logsOff)
    logJSON runIn(1800,logsOff)
    state.LastRefresh = new Date().format("YYYY/MM/dd \n HH:mm:ss", location.timeZone)
    
    refresh()
}

def refresh(){
    getSettings()
    logDebug "Shelly Status called"
    def params1 = [uri: "http://${username}:${password}@${ip}/rpc/Cover.GetStatus?id=0"]

try {
    httpGet(params1) {
        resp1 -> resp1.headers.each {
        logJSON "Response1: ${it.name} : ${it.value}"
    }
        obs1 = resp1.data
       
        logJSON "params1: ${params1}"
        logJSON "response1 contentType: ${resp1.contentType}"
	    logJSON "response1 data: ${resp1.data}"

    if( state.current_pos != obs1.current_pos){
        runIn(3, refresh)
    }
        
        state.powerSource = settings?.powersource
        sendEvent(name: "powerSource", value: state.powerSource)
        state.current_pos = obs1.current_pos
       
        sendEvent(name: "level", value: state.current_pos)
        sendEvent(name: "stop_reason", value: obs1.source)

    if ( state.current_pos < closedif ) {
        //if (txtEnable) log.info "CreateEvent closed"
        sendEvent(name: "windowShade", value: "closed")
        sendEvent(name: "switch", value: "off")
    } else
        if ( state.current_pos > openif ) {
        //if (txtEnable) log.info "CreateEvent open"
        sendEvent(name: "windowShade", value: "open")
        sendEvent(name: "switch", value: "on")
    } else {
        //if (txtEnable) log.info "CreateEvent Partially open"
        sendEvent(name: "windowShade", value: "partially open")
        sendEvent(name: "switch", value: "on")
    }
        
        sendEvent(name: "power", value: obs1.apower)
        sendEvent(name: "voltage", value: obs1.voltage)
        sendEvent(name: "energy", value: obs1.aenergy.total)
        sendEvent(name: "temperature", value: obs1.temperature.tC)
        
        
       // def each state 
        /*state.rssi = obs1.wifi_sta.rssi
        state.ssid = obs1.wifi_sta.ssid
        state.mac = obs1.mac
        state.has_update = obs1.has_update
        state.cloud = obs1.cloud.enabled
        state.cloud_connected = obs1.cloud.connected

        sendEvent(name: "MAC", value: state.mac)
        sendEvent(name: "SSID", value: state.ssid)
        sendEvent(name: "IP", value: obs1.wifi_sta.ip)


        signal = state.rssi
        if (signal <= 0 && signal >= -70) {
            sendEvent(name:  "WiFiSignal", value: "<font color='green'>Excellent</font>", isStateChange: true);
        } else
        if (signal < -70 && signal >= -80) {
            sendEvent(name:  "WiFiSignal", value: "<font color='green'>Good</font>", isStateChange: true);
        } else
        if (signal < -80 && signal >= -90) {
            sendEvent(name: "WiFiSignal", value: "<font color='yellow'>Poor</font>", isStateChange: true);
        } else 
        if (signal < -90 && signal >= -100) {
            sendEvent(name: "WiFiSignal", value: "<font color='red'>Weak</font>", isStateChange: true);
        }

        sendEvent(name: "rssi", value: state.rssi)
        updateDataValue("model", state.DeviceType)
        updateDataValue("ShellyHostname", state.ShellyHostname)
        updateDataValue("ShellyIP", obs1.wifi_sta.ip)
        updateDataValue("ShellySSID", state.ssid)
        updateDataValue("manufacturer", "Allterco Robotics")
        updateDataValue("MAC", state.mac)*/

} // End try
        
    } catch (e) {
        log.error "something went wrong: $e"
    }
    
} // End refresh

// Get shelly device type
def getSettings(){
 logDebug "Get Shelly Settings"
    def paramsSettings = [uri: "http://${username}:${password}@${ip}/rpc/Shelly.GetStatus"]

try {
    httpGet(paramsSettings) {
        respSettings -> respSettings.headers.each {
        logJSON "ResponseSettings: ${it.name} : ${it.value}"
    }
        obsSettings = respSettings.data

        logJSON "params: ${paramsSettings}"
        logJSON "response contentType: ${respSettings.contentType}"
	    logJSON "response data: ${respSettings.data}"
/*
        state.DeviceType = obsSettings.device.type
        if (state.DeviceType == "SHSW-1") sendEvent(name: "DeviceType", value: "Shelly 1")
        if (state.DeviceType == "SHSW-PM") sendEvent(name: "DeviceType", value: "Shelly 1PM")
        if (state.DeviceType == "SHSW-21") sendEvent(name: "DeviceType", value: "Shelly 2")
        if (state.DeviceType == "SHSW-25") sendEvent(name: "DeviceType", value: "Shelly 2.5")
        if (state.DeviceType == "SHSW-44") sendEvent(name: "DeviceType", value: "Shelly 4Pro")
        if (state.DeviceType == "SHEM") sendEvent(name: "DeviceType", value: "Shelly EM")
        if (state.DeviceType == "SHPLG-1") sendEvent(name: "DeviceType", value: "Shelly Plug")
        if (state.DeviceType == "SHPLG-S") sendEvent(name: "DeviceType", value: "Shelly PlugS")

        updateDataValue("model", state.DeviceType)
        updateDataValue("ShellyHostname", state.ShellyHostname)
        updateDataValue("ShellyIP", obsSettings.wifi_sta.ip)
        updateDataValue("ShellySSID", obsSettings.wifi_sta.ssid)
        updateDataValue("manufacturer", "Allterco Robotics")
        updateDataValue("MAC", state.mac)
*/
        if (obsSettings.wifi != null) {
        state.rssi = obsSettings.wifi.rssi
        state.Secondary_ssid = obsSettings.wifi.ssid
        state.Secondary_IP = obsSettings.wifi.sta_ip
        //if (obsSettings.wifi_sta1.enabled == true) sendEvent(name: "Secondary_SSID", value: state.Secondary_ssid)
        //if (state.Secondary_IP != null) sendEvent(name: "Secondary_IP", value: state.Secondary_IP)
        sendEvent(name: "WiFiSignal", value: state.rssi)
        }

        //state.mode = obsSettings.mode
        //state.ShellyHostname = obsSettings.device.hostname
        
// Under /settings
        /*
        if (state.mode == "relay" ) {
            sendEvent(name: "mode", value: "!!CHANGE DEVICE TO ROLLER!!")
        } else {
            sendEvent(name: "mode", value: state.mode)
        }
    */
        //sendEvent(name: "power", value: obsSettings.cover.apower)
        /*sendEvent(name: "safety_switch", value: obsSettings.safety_switch)
        sendEvent(name: "safety_mode", value: obsSettings.safety_mode)
        sendEvent(name: "safety_action", value: obsSettings.safety_action)
        sendEvent(name: "safety_allowed_on_trigger", value: obsSettings.safety_allowed_on_trigger)
        sendEvent(name: "state", value: obsSettings.state)
        sendEvent(name: "obstacle_mode", value: obsSettings.obstacle_mode)
        sendEvent(name: "obstacle_action", value: obsSettings.obstacle_action)
        sendEvent(name: "obstacle_power", value: obsSettings.obstacle_power)
        sendEvent(name: "obstacle_delay", value: obsSettings.obstacle_delay)*/
// End Settings

} // End try
       } catch (e) {
           log.error "something went wrong: $e"
       }
} // End Refresh Status


def open() {
    if (txtEnable) log.info "Executing 'open'"
    sendCommand "/roller/0?go=open"
}

def close() {
    if (txtEnable) log.info "Executing 'close'"
    sendCommand "/roller/0?go=close"
}

def on() {
    if (txtEnable) log.info "Executing open"
    open()
}

def off() {
    if (txtEnable) log.info "Executing close"
    close()
}

def setLevel(value, duration = null) {
    if (txtEnable) log.info "Executing setLevel value with $value"
    sendCommand "/roller/0?go=to_pos&roller_pos="+value
}

def setPosition(position) {
    if (txtEnable) log.info "Executing 'setPosition'"
//    setLevel(value)
    sendCommand "/roller/0?go=to_pos&roller_pos="+position
}

def stop() {
    if (txtEnable) log.info "Executing stop()"
    sendCommand "/roller/0?go=stop"
}

def calibrate() {
    if (txtEnable) log.info "Executing calibrate"
    sendCommand "/rpc/Cover.calibrate?id=0"
}

def ping() {
    if (txtEnable) log.info "Ping"
	poll()
}

def UpdateDeviceFW() {
    sendCommand "/rpc/Shelly.Update?stage=stable"
}

def logsOff(){
	log.warn "debug logging auto disabled..."
	device.updateSetting("debugOutput",[value:"false",type:"bool"])
	device.updateSetting("debugParse",[value:"false",type:"bool"])
}

def autorefresh() {
	if (locale == "UK") {
	logDebug "Get last UK Date DD/MM/YYYY"
	state.LastRefresh = new Date().format("d/MM/YYYY \n HH:mm:ss", location.timeZone)
	sendEvent(name: "LastRefresh", value: state.LastRefresh, descriptionText: "Last refresh performed")
	} 
	if (locale == "US") {
	logDebug "Get last US Date MM/DD/YYYY"
	state.LastRefresh = new Date().format("MM/d/YYYY \n HH:mm:ss", location.timeZone)
	sendEvent(name: "LastRefresh", value: state.LastRefresh, descriptionText: "Last refresh performed")
	}
	if (txtEnable) log.info "Executing 'auto refresh'" //RK
    refresh()
}

def poll() {
	if (locale == "UK") {
	logDebug log.info "Get last UK Date DD/MM/YYYY"
	state.LastRefresh = new Date().format("d/MM/YYYY \n HH:mm:ss", location.timeZone)
	sendEvent(name: "LastRefresh", value: state.LastRefresh, descriptionText: "Last refresh performed")
	} 
	if (locale == "US") {
	logDebug log.info "Get last US Date MM/DD/YYYY"
	state.LastRefresh = new Date().format("MM/d/YYYY \n HH:mm:ss", location.timeZone)
	sendEvent(name: "LastRefresh", value: state.LastRefresh, descriptionText: "Last refresh performed")
	}
	if (txtEnable) log.info "Executing 'poll'" //RK
	refresh()
}

def sendCommand(action) {
    //if (txtEnable) log.info "Calling $action"
    def params = [uri: "http://${username}:${password}@${ip}/${action}"]
try {
    httpPost(params) {
        resp -> resp.headers.each {
        logDebug "Response: ${it.name} : ${it.value}"
    }
} // End try
        
} catch (e) {
        log.error "something went wrong: $e"
    }
    runIn(3, refresh)
}

private logJSON(msg) {
	if (settings?.debugParse || settings?.debugParse == null) {
	log.info "$msg"
	}
}

private logDebug(msg) {
	if (settings?.debugOutput || settings?.debugOutput == null) {
	log.debug "$msg"
	}
}
