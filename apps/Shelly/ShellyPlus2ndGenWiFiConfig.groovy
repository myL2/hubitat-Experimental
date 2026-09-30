definition(
    name: "Shelly Plus 2nd Gen WiFi Config",
    namespace: "myL2",
    author: "SebyM",
    description: "Setup Shelly Gen2 Relays easily",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "")

preferences {
    page name:"pageSetup"
    page name:"pageWork"
    page name:"pageSuccess"
}

def pageSetup() {

    return dynamicPage(name: "pageSetup", title: "${app.label}", nextPage: "pageWork") {
        section() {
            input "SSID", "text", title: "SSID",default: "Lights Relay",description:"Enter the network SSID", required:true
            input "pass", "text", title: "Pass",description:"Enter the network password.", required:true
            input "baseIP", "text", title: "Shelly IP",default:"192.168.33.1",description:"Leave empty for '192.168.33.1'", required:true
        }
    }
}

def pageWork(){
    try{       
        def ip="192.168.33.1"
        if(baseIP)ip=baseIP;
        def url="http://${ip}/rpc/WiFi.SetConfig?config={\"sta\":{\"ssid\":\"${SSID}\",\"pass\":\"${pass}\",\"enable\":true}, \"ap\":{\"enable\":true}}";
        str="<a target='_ShellyConfig' href='${url}'>${url}</a>"
        return dynamicPage(name:"pageSuccess", title:"Success!", nextPage:"pageSetup") {
            section {
                paragraph "${str}"
            }        
        }
    } catch (error) {
        log.debug("Failed to generate link:\r${error}")
    }
}

def installed() {
    log.debug "installed()"
}

def updated() {
    log.debug "updated()"
}

def uninstalled() {}