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
        capability "EnergyMeter"
        capability "Sensor"
        capability "Refresh"
        capability "Switch"
        capability "RelaySwitch"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "SignalStrength"
        capability "TemperatureMeasurement"
        capability "VoltageMeasurement"

        attribute "lastPowerUpdate", "number"
        attribute "lastPowerValue", "number"
        attribute "energyExact", "number"
        
        command "resetEnergy"
    }

    preferences {
        input("ip", "string", title:"IP", description:"Shelly IP Address", defaultValue:"" , required: true)
        input("channel", "number", title:"Channel", description:"Child device Channel" , required: true)
        input("prefix", "string", title:"API RPC Prefix", description:" (switch, pm1, etc)", defaultValue:"switch" , required: true)
        input name: "username", type: "text", title: "Username:", description: "(blank if none)", required: false
        input name: "password", type: "password", title: "Password:", description: "(blank if none)", required: false
        input name: "debugOutput", type: "bool", title: "Enable debug logging?", defaultValue: false
    }
}

def initialize() {
    logDebug "Shelly Plus 2nd Gen/Child/Initialize"
    resetEnergy()
}

def installed() {
    logDebug "Shelly Plus 2nd Gen/Child/Installed"
    resetEnergy()
}

def uninstalled() {
    unschedule()
    logDebug "Shelly Plus 2nd Gen/Child/Uninstalled"
}

def updated() {
    log.info "Preferences updated..."
    refresh()
}

def refresh(){
    //log.info "Shelly Refresh called"
    def params = [uri: "http://${username}:${password}@${ip}/rpc/${prefix}.GetStatus?id=${channel}"]
    //log.info params
    try {
        httpGet(params) {
            resp -> resp.headers.each {    }
            obs = resp.data
            if(obs.voltage != null){
                sendEvent(name: "voltage", value: obs.voltage)
            }
            try{
            if (obs.temperature.tC != null){
                if (state.temp_scale == "C") sendEvent(name: "temperature", value: obs.temperature.tC)
                if (state.temp_scale == "F") sendEvent(name: "temperature", value: obs.temperature.tF)
            }
            }catch(e){}
            ison = obs.output
            if (ison == true) {
                sendEvent(name: "switch", value: "on")
            } else {
                sendEvent(name: "switch", value: "off")
            }

            if(obs.apower != null){
                powerValue = Math.round(obs.apower*100)/100
                sendEvent(name: "power", unit: "W", value: powerValue)
                updateEnergy(powerValue)
            }

        } // End try
    } catch (e) {
        log.error "something went wrong: $e"
    }

}

def resetEnergy(){
    sendEvent(name: "lastPowerValue", value: 0)
    sendEvent(name: "energy", value: 0)
    sendEvent(name: "energyExact", value: 0)
    sendEvent(name: "lastPowerUpdate", value: now())
}

def updateRelayState(newState){
    sendEvent(name: "switch", value: newState)
}

def updateRelayPower(newPower){
    powerValue = Float.parseFloat(newPower)
    sendEvent(name: "power", value: Math.round(powerValue*100)/100)
    updateEnergy(powerValue)
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

private logDebug(msg) {
    if (settings?.debugOutput || settings?.debugOutput == null) {
        log.debug "$msg"
    }
}
