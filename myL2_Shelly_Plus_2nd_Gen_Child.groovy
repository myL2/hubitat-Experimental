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
        name: "Shelly Plus 2nd Gen Child",
        namespace: "myL2",
        author: "SebyM"
    )
    {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "Switch"
        capability "RelaySwitch"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "SignalStrength"
        capability "TemperatureMeasurement"
        capability "VoltageMeasurement"
    }

    preferences {
        input("ip", "string", title:"IP", description:"Shelly IP Address", defaultValue:"" , required: true)
        input("channel", "number", title:"Channel", description:"Child device Channel" , required: true)
        input name: "username", type: "text", title: "Username:", description: "(blank if none)", required: false
        input name: "password", type: "password", title: "Password:", description: "(blank if none)", required: false
    }
}

def initialize() {
    log.info "Shelly Plus 2nd Gen/Child/Initialize"
}

def installed() {
    log.debug "Shelly Plus 2nd Gen/Child/Installed"
}

def uninstalled() {
    unschedule()
    log.debug "Shelly Plus 2nd Gen/Child/Uninstalled"
}

def updated() {
    log.info "Preferences updated..."
    refresh()
}

def refresh(){
    //log.info "Shelly Refresh called"
    def params = [uri: "http://${username}:${password}@${ip}/rpc/Switch.GetStatus?id=${channel}"]

    try {
        httpGet(params) {
            resp -> resp.headers.each {    }
            obs = resp.data
            if(obs.voltage != null){
                sendEvent(name: "voltage", value: obs.voltage)
            }

            if (obs.temperature.tC != null){
                if (state.temp_scale == "C") sendEvent(name: "temperature", value: obs.temperature.tC)
                if (state.temp_scale == "F") sendEvent(name: "temperature", value: obs.temperature.tF)
            }

            ison = obs.output
            if (ison == true) {
                sendEvent(name: "switch", value: "on")
            } else {
                sendEvent(name: "switch", value: "off")
            }

            if(obs.apower != null){
                power = obs.apower
                sendEvent(name: "power", unit: "W", value: power)
            }

        } // End try
    } catch (e) {
        log.error "something went wrong: $e"
    }

}

def updateRelayState(newState){
    sendEvent(name: "switch", value: newState)
}

def on() {
    sendSwitchCommand "/rpc/Switch.Set?id=${channel}&on=true"
}

def off() {
    sendSwitchCommand "/rpc/Switch.Set?id=${channel}&on=false"
}

def sendSwitchCommand(action) {
    def params = [uri: "http://${username}:${password}@${ip}/${action}"]
    try {
        httpGet(params) {
            resp -> resp.headers.each {
            }
        } 
    } catch (e) {
        log.error "something went wrong: $e"
    }
}

