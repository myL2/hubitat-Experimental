definition(
    name: "Shelly Plus 2nd Gen Quick Setup",
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
            input "deviceLabel", "text", title: "New device label",default: "",description:"Enter the nice name of the device", required:true
            input "deviceIP", "text", title: "New device IP Address",description:"Make sure the IP is Reserved on your router.", required:true
            input "deviceChannels", "text", title: "New device Number of Channels",description:"1/2/3/4", required:true
        }
      }
}

def pageWork(){
    log.info("(configureDevice) Attempting to add device with type ${deviceType} and label ${deviceLabel}")
    try {
        def newDevice = addChildDevice(
            "myL2",
            "Shelly Plus 2nd Gen",
            deviceIP,
            ,
            [
                "label" : deviceLabel,
                "name" : deviceType,
                isComponent: false
            ]
        )
        newDevice.updateSetting("ip",[type:"string", value: deviceIP])
        newDevice.updateSetting("channels",[type:"number", value: deviceChannels])
        newDevice.updateSetting("refresh_Rate",[type:"enum", value: "manual"])
        
        newDevice.updated()
        
        newDeviceId = newDevice.getId()

        hubIp=location.hub["localIP"]
        log.debug("Added device ${deviceLabel} with id ${newDeviceId}")
        
        str="<a target='_new{$newDeviceId}' href='http://${hubIp}/device/edit/${newDeviceId}'>Added device ${deviceLabel} with id ${newDeviceId}, click to open</a>"
        return dynamicPage(name:"pageSuccess", title:"Success!", nextPage:"pageSetup") {
            section {
                paragraph "${str}"
            }        
        }
    } catch (error) {
        log.debug("Failed to add device:\r${error}")
    }
}

def appButtonHandler(btn) {
    switch(btn) {
        case "createChild":
        createChild()
        break

    }
}



def installed() {
    log.debug "installed()"
}

def updated() {
    log.debug "updated()"
}

def uninstalled() {}