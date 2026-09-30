/*
*
*  Shelly PLUS Driver
*
*  Original Author: Copyright © 2018-2019 Scott Grayban
*  Shelly Devices: Copyright © 2020 Allterco Robotics US
*
*  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License. You may obtain a copy of the License at:
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
*  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
*  for the specific language governing permissions and limitations under the License.
*
* Hubitat is the Trademark and intellectual Property of Hubitat Inc.
* Shelly is the Trademark and Intellectual Property of Allterco Robotics Ltd
*
*-------------------------------------------------------------------------------------------------------------------
*
*/

import groovy.json.*
    import groovy.transform.Field
import org.apache.commons.codec.binary.Base64

metadata {
    definition (
        name: "Shelly Plus 2nd Gen",
        namespace: "myL2",
        author: "SebyM"
    )
    {
        capability "Refresh"
        capability "SignalStrength"
        capability "TemperatureMeasurement"
        capability "VoltageMeasurement"
        capability "Polling"

        attribute "StableFW_Update", "string"
        attribute "LastRefresh", "string"
        attribute "power", "number"
        attribute "overpower", "string"
        attribute "DeviceOverTemp", "string"
        attribute "MAC", "string"
        attribute "Primary_IP", "string"
        attribute "Primary_SSID", "string"
        attribute "Secondary_IP", "string"
        attribute "Secondary_SSID", "string"
        attribute "WiFiSignal", "string"
        attribute "Cloud", "string"
        attribute "Cloud_Connected", "string"
        attribute "energy", "number"
        attribute "DeviceType", "string"
        attribute "eMeter", "number"
        attribute "reactive", "number"
        attribute "MaxPower", "number"
        attribute "CircuitAmp", "string"
        attribute "LED_Output", "string"
        attribute "LED_NetworkStatus", "string"
        attribute "Eco_Mode", "string"

        command "RebootDevice"
        command "UpdateDeviceFW"
        command "configureChildren"
        command "removeChildren"
        command "uploadScripts"
        command "disableApAndBt"
    }

    preferences {
        def refreshRate = [:]
        refreshRate << ["1 min" : "Refresh every minute"]
        refreshRate << ["5 min" : "Refresh every 5 minutes"]
        refreshRate << ["15 min" : "Refresh every 15 minutes"]
        refreshRate << ["30 min" : "Refresh every 30 minutes"]
        refreshRate << ["manual" : "Manually or Polling Only"]

        input("ip", "string", title:"IP", description:"Shelly IP Address", defaultValue:"" , required: true)
        input name: "username", type: "text", title: "Username:", description: "(blank if none)", required: false
        input name: "password", type: "password", title: "Password:", description: "(blank if none)", required: false
        input("channels", "number", title:"Number of channels", description:"1,2,3,4", defaultValue:"0" , required: true)
        input("refresh_Rate", "enum", title: "Device Refresh Rate", description:"<font color=red>!!WARNING!!</font><br>DO NOT USE if you have over 50 Shelly devices.", options: refreshRate, defaultValue: "30 min")

        input name: "debugOutput", type: "bool", title: "Enable debug logging?", defaultValue: false
        input name: "debugParse", type: "bool", title: "Enable JSON parse logging?", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

def initialize() {
    if (txtEnable) log.info "Shelly Plus 2nd Gen/Parent/initialize"
}

def installed() {
    logDebug "Shelly Plus 2nd Gen/Parent/Installed"
}

def uninstalled() {
    unschedule()
    logDebug "Shelly Plus 2nd Gen/Parent/Uninstalled"
}

def updated() {
    if (txtEnable) log.info "Preferences updated..."
    log.warn "Debug logging is: ${debugOutput == true}"
    unschedule()
    dbCleanUp()

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
        case "30 min" :
        runEvery30Minutes(autorefresh)
        break
        case "manual" :
        unschedule(autorefresh)
        log.info "Autorefresh disabled"
        break
    }
    if (txtEnable) log.info ("Auto Refresh set for every ${refresh_Rate} minute(s).")

    if (debugOutput) runIn(1800,logsOff)
    if (debugParse) runIn(300,logsOff)

    state.LastRefresh = new Date().format("YYYY/MM/dd \n HH:mm:ss", location.timeZone)
    poll()
    configureChildren()

    refresh()
}

def configureChildren(){
    if (channels) {
        String thisId = device.deviceNetworkId
        for (int myindex = 0; myindex < channels; myindex++) {
            if (!getChildDevice("${thisId}-${myindex}")) {
                newDevice= addChildDevice("myL2", "Shelly Plus 2nd Gen Child", "${thisId}-${myindex}", [name: "${device.label.replace(' Relay','')} - ${myindex}", isComponent: true])
                newDevice.updateSetting("ip",[type:"string", value: ip])
                newDevice.updateSetting("channel",[type:"number", value: (myindex)])
                newDevice.updateSetting("username",[type:"string", value: username])
                newDevice.updateSetting("password",[type:"string", value: password])
                log.info "Installing child ${thisId}-${myindex}."
            }
        }
    }    
}

def removeChildren(){
    if (channels) {
        String thisId = device.deviceNetworkId
        for (int myindex = 0; myindex < channels; myindex++) {
            log.info myindex
            if (getChildDevice("${thisId}-${myindex}")) {
                deleteChildDevice("${thisId}-${myindex}")
                log.info "Removing child ${thisId}-${myindex}."
            }
        }
    }    
}

private dbCleanUp() {
    state.remove("ver")
    state.remove("id")
    state.remove("ShellyfwUpdate")
    state.remove("power")
    state.remove("overpower")
    state.remove("dcpower")
    state.remove("max_power")
    state.remove("internal_tempC")
    state.remove("Status")
    state.remove("max_power")
    state.remove("powerSource")
    state.remove("has_update")
}

def parse(values){
    def original = "{\""+values.replace(":","\":\"").replace(", ","\",\"")+"-\"}"
    def json = new JsonSlurper().parseText(original)
    def header = new String(json.headers.decodeBase64())
    def s = header.split(' ')
    def ss = s[1].split('/')
    def channelNo = ss[2]
    def channelValue = ss[3]

    if (channels) {
        String thisId = device.deviceNetworkId
        child = getChildDevice("${thisId}-${channelNo}")
        if (child) {
            if(debugOutput) log.debug "${child} is now ${channelValue}"
            switch (ss[1]){
                case "switch":
                child.updateRelayState(channelValue)
                break;
                case "power":
                child.updateRelayPower(channelValue)
                break;
            }
        }else{
            log.debug "Got a request for a child that does not exist: ${ss}"
        }
    }

}

def disableApAndBt(){
    sendScriptCmd("http://${ip}/rpc/WiFi.SetConfig", [config: "{ap:{enable:false}}"]);
    sendScriptCmd("http://${ip}/rpc/BLE.SetConfig", [config: "{enable:false}"]);
}

def uploadScripts(){
    def chunks=[
        [id: 1, append: true, code: "\"let minThreshold=10;\\nlet lastPower=0;\\nlet baseUrl='http://${location.hub.localIP}:39501/';\\nShelly.addStatusHandler(function(event) {\\nif (event.name===\\\"switch\\\"){\\n \""],
        [id: 1, append: true, code: "\"if(typeof(event.delta.output)!==\\\"undefined\\\"){\\n  let url=baseUrl+'switch/'+JSON.stringify(event.id)+'/'+\""],
        [id: 1, append: true, code: "\"(event.delta.output?\\\"on\\\":\\\"off\\\")+\\\"/\\\"; Shelly.call(\\\"HTTP.GET\\\", {\\\"url\\\": url});\\n }\\n\""],
        [id: 1, append: true, code: "\" if (typeof event.delta.apower!==\\\"undefined\\\"){\\n  if (Math.abs(event.delta.apower-lastPower)>minThreshold){\\n   lastPower=event.delta.apower;\\n\""],
        [id: 1, append: true, code: "\"   let url=baseUrl+'power/'+JSON.stringify(event.id)+\\\"/\\\"+JSON.stringify(event.delta.apower)+\\\"/\\\"; Shelly.call(\\\"HTTP.GET\\\", {\\\"url\\\": url});\\n  }\\n }\\n}});\"\""],
    ]
    sendScriptCmd("http://${ip}/rpc/Script.Stop", [id: 1]);
    sendScriptCmd("http://${ip}/rpc/Script.Delete", [id: 1]);
    sendScriptCmd("http://${ip}/rpc/Script.Create?", [:]);
    sendScriptCmd("http://${ip}/rpc/Script.PutCode",chunks[0]);
    sendScriptCmd("http://${ip}/rpc/Script.PutCode",chunks[1]);
    sendScriptCmd("http://${ip}/rpc/Script.PutCode",chunks[2]);
    sendScriptCmd("http://${ip}/rpc/Script.PutCode",chunks[3]);
    sendScriptCmd("http://${ip}/rpc/Script.PutCode",chunks[4]);
    sendScriptCmd("http://${ip}/rpc/Script.SetConfig",[id: 1, config: "{enable:true, name:\"Hubitat\"}"]);
    sendScriptCmd("http://${ip}/rpc/Script.Start", [id: 1]);
    log.debug "Script upload finished"
}

def sendScriptCmd(uri, args){
    def params = [
        uri: uri,
        requestContentType: 'text/html',
        query: args
    ]
    //log.info params
    try{
        httpGet(params) {
            resp -> resp.headers.each {
                //log.debug "Response: ${it.name} : ${it.value}"
            }
        }
    }catch (e){
        logDebug "Err: " + e.message
        logDebug "Err response: " + e.response.getData()
    }
}

def refresh(){
    logDebug "Shelly Refresh called"

    if (channels) {
        String thisId = device.deviceNetworkId
        for (int myindex = 0; myindex < channels; myindex++) {
            child = getChildDevice("${thisId}-${myindex}")
            if (child) {
                child.refresh()
            }
        }
    }

}

def getWiFi(){
    logDebug "WiFi Status called"
    def params = [uri: "http://${username}:${password}@${ip}/rpc/wifi.GetStatus"]

    try {
        httpGet(params) {
            resp -> resp.headers.each {
                logJSON "Response: ${it.name} : ${it.value}"
            }
            obs = resp.data
            logJSON "params: ${params}"
            logJSON "response contentType: ${resp.contentType}"
            logJSON "response data: ${resp.data}"

            state.rssi = obs.rssi
            state.ssid = obs.ssid
            state.ip = obs.sta_ip

            sendEvent(name: "Primary_SSID", value: state.ssid)
            sendEvent(name: "Primary_IP", value: state.ip)

            /*
-30 dBm Excellent | -67 dBm     Good | -70 dBm  Poor | -80 dBm  Weak | -90 dBm  Dead
*/
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

        } // End try
    } catch (e) {
        log.error "something went wrong: $e"
    }

}

def getDeviceInfo(){

    logDebug "Sys Status called"
    //getSettings()
    def params = [uri: "http://${username}:${password}@${ip}/rpc/Shelly.GetDeviceInfo"]

    try {
        httpGet(params) {
            resp -> resp.headers.each {
                logJSON "Response: ${it.name} : ${it.value}"
            }
            obs = resp.data
            logJSON "params: ${params}"
            logJSON "response contentType: ${resp.contentType}"
            logJSON "response data: ${resp.data}"

            state.mac = obs.mac

            device.deviceNetworkId = state.mac.replace(":","").toUpperCase()

            updateDataValue("FW Version", obs.ver)
            updateDataValue("model", obs.model)
            updateDataValue("ShellyHostname", obs.id)
            updateDataValue("Device Type", obs.app)

        } // End try
    } catch (e) {
        log.error "something went wrong: $e"
    }

}

def getGetConfig(){

    logDebug "Sys Status called"
    //getSettings()
    def params = [uri: "http://${username}:${password}@${ip}/rpc/Sys.GetConfig"]

    try {
        httpGet(params) {
            resp -> resp.headers.each {
                logJSON "Response: ${it.name} : ${it.value}"
            }
            obs = resp.data
            logJSON "params: ${params}"
            logJSON "response contentType: ${resp.contentType}"
            logJSON "response data: ${resp.data}"

            sendEvent(name: "Eco_Mode", value: obs.device.eco_mode)

        } // End try
    } catch (e) {
        log.error "something went wrong: $e"
    }

}

def CheckForUpdate() {
    if (txtEnable) log.info "Check Device FW"
    def params = [uri: "http://${username}:${password}@${ip}/rpc/Shelly.CheckForUpdate"]
    try {
        httpGet(params) {
            resp -> resp.headers.each {
                //logDebug "Response: ${it.name} : ${it.value}"
            }
            obs = resp.data
            response = "${obs.toString()}"
            logJSON "params: ${params}"
            logJSON "response contentType: ${resp.contentType}"
            logJSON "response data: ${resp.data}"

            if(response.contains("stable")) {
                sendEvent(name:  "StableFW_Update", value: "<font color='green'>Available</font>", isStateChange: true);
            }else
                if(!(response.contains("stable"))) {
                    sendEvent(name:  "StableFW_Update", value: "Current", isStateChange: true);
                }

        } // End try

    } catch (e) {
        log.error "something went wrong: $e"
    }
}


def ping() {
    logDebug "ping"
    poll()
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
    refresh()
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

def poll() {
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
    if (txtEnable) log.info "Polling"
    getWiFi()
    getDeviceInfo()
    getGetConfig()
    CheckForUpdate()
}

def RebootDevice() {
    if (txtEnable) log.info "Rebooting Device"
    def params = [uri: "http://${username}:${password}@${ip}/rpc/Shelly.Reboot"]
    try {
        httpGet(params) {
            resp -> resp.headers.each {
                logDebug "Response: ${it.name} : ${it.value}"
            }
        } // End try

    } catch (e) {
        log.error "something went wrong: $e"
    }
    runIn(15,refresh)
}

def UpdateDeviceFW() {
    if (txtEnable) log.info "Updating Device FW"
    def params = [uri: "http://${username}:${password}@${ip}/rpc/Shelly.Update?stage=stable"]
    try {
        httpGet(params) {
            resp -> resp.headers.each {
                logDebug "Response: ${it.name} : ${it.value}"
            }
        } // End try

    } catch (e) {
        log.error "something went wrong: $e"
    }
    runIn(30,refresh)
}
