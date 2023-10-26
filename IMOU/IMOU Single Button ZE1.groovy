import hubitat.helper.HexUtils

metadata {
	definition (name: "IMOU Single Button", namespace: "myL2", author: "SebyM") {
        capability "Battery"
        capability "Configuration"
        capability "PushableButton"
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0001,0003,0500,0B05", outClusters:"0003", model:"ZE1-EN", manufacturer:"MultIR", application:"04"
	}

    preferences {
        input(name: "debugLogging", type: "bool", title: "Enable debug logging", description: "", defaultValue: false, submitOnChange: true, displayDuringSetup: false, required: false)
        input(name: "infoLogging", type: "bool", title: "Enable info logging", description: "", defaultValue: true, submitOnChange: true, displayDuringSetup: false, required: false)
	}
}

void initialize() {
    configure()
}

void installed() {
    configure()
}

void updated() {
}

def configure(){
    sendEvent(name:"pushed", value: 0, isStateChange: true)
}

ArrayList<String> parse(String description) {
    if (debugLogging) log.debug description

    ArrayList<String> cmd = []
    Map msgMap = null
    msgMap = zigbee.parseDescriptionAsMap(description)
    if(msgMap == [:] && description.indexOf("zone") == 0) {
        msgMap["type"] = "zone"
        java.util.regex.Matcher zoneMatcher = description =~ /.*zone.*status.*0x(?<status>([0-9a-fA-F][0-9a-fA-F])+).*extended.*status.*0x(?<statusExtended>([0-9a-fA-F][0-9a-fA-F])+).*/
        if(zoneMatcher.matches()) {
            msgMap["parsed"] = true
            msgMap["status"] = zoneMatcher.group("status")
            msgMap["statusInt"] = Integer.parseInt(msgMap["status"], 16)
            msgMap["statusExtended"] = zoneMatcher.group("statusExtended")
            msgMap["statusExtendedInt"] = Integer.parseInt(msgMap["statusExtended"], 16)
        } else {
            msgMap["parsed"] = false
        }
    } else if(description.indexOf('encoding: 42') >= 0) {

        List values = description.split("value: ")[1].split("(?<=\\G..)")
        String fullValue = values.join()
        Integer zeroIndex = values.indexOf("01")
        if(zeroIndex > -1) {
            msgMap = zigbee.parseDescriptionAsMap(description.replace(fullValue, values.take(zeroIndex).join()))
        } else {
            msgMap = zigbee.parseDescriptionAsMap(description)
        }
    } else {
        msgMap = zigbee.parseDescriptionAsMap(description)
    }

    if (debugLogging) log.debug "msgMap: ${msgMap}"

    if(msgMap["type"] == "zone"){
        switch(msgMap["status"]) {
            case "0002":
            sendEvent(name:"pushed", value: 1, isStateChange: true, descriptionText: "Button 1 pressed")
            if (infoLogging) log.info "Button 1 pressed"
            break

            default:
                log.warn "Unhandled Event PLEASE REPORT TO DEV - description:${description} | msgMap:${msgMap}"
            break
        }
    }else{
        switch(msgMap["attrId"]) {
            case "0021":
            value = HexUtils.hexStringToInt(msgMap["value"])/2
            sendEvent(name:"battery", value: value, isStateChange: true, descriptionText: "Battery is now ${value}%")
            if (infoLogging) log.info "Battery is now ${value}%"
            break
            default:
                log.warn "Unhandled Event PLEASE REPORT TO DEV - description:${description} | msgMap:${msgMap}"
            break
        }
    }
}
