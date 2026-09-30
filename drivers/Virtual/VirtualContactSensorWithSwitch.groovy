/*
 * Virtual Contact Sensor with Switch
 *
 * Created by Stephan Hackett
 * Modified by SebyM (added Turn Off After)
 * 
 */

metadata {
    definition (name: "Virtual Contact Sensor with Switch", namespace: "stephack", author: "Stephan Hackett") {
		capability "Sensor"
        capability "Contact Sensor"
        capability "Switch"
		
		command "open"
		command "close"
    }
	preferences {
        input name: "reversed", type: "bool", title: "Reverse Action"
        input name: "turnOffAfter", type: "number", title:"Turn Off After", description:"0: disabled; valid: 0-15", defaultValue:"0", range: "0..15"
	}
}

def open(){
	sendEvent(name: "contact", value: "open")
	if(reversed) switchVal = "off"
	else switchVal = "on"
	sendEvent(name: "switch", value: switchVal)
    if (turnOffAfter>0) runIn(turnOffAfter, off)
}

def close(){
	sendEvent(name: "contact", value: "closed")
	if(reversed) switchVal = "on"
	else switchVal = "off"
	sendEvent(name: "switch", value: switchVal)
}

def on(){
    sendEvent(name: "switch", value: "on")
	if(reversed==true) contactVal = "closed"
	else contactVal = "open"
	sendEvent(name: "contact", value: contactVal)
    if (turnOffAfter>0) runIn(turnOffAfter, off)
}

def off(){
    sendEvent(name: "switch", value: "off")
	if(reversed==true) contactVal = "open"
	else contactVal = "closed"
	sendEvent(name: "contact", value: contactVal)
}

def installed(){
	initialize()
}

def updated(){
	initialize()
}

def initialize(){
	sendEvent(name: "switch", value: "off")
	sendEvent(name: "contact", value: "closed")
}
