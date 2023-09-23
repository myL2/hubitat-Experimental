definition(
    name: "Edge Mirror Child",
    namespace: "myL2",
    parent: "myL2:Edge Mirror",
    author: "SebyM",
    description: "Edge Mirror Child",
    category: "Convenience",
    importUrl: "",
    iconUrl: "",
    iconX2Url: "")

preferences {
    page(name: "mainPage")
}


def mainPage() {
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section("Change state on every hit") {
            if(!masterDeviceType && !master){
                input "masterDeviceType", "enum", title:"Choose Parent Device Type", options: ["Light Switch", "Light Dimmer"], submitOnChange: true
            }
            if(masterDeviceType || master){
                input "master", "capability.${getCapability()}", title: "Select Switch", submitOnChange: true, required: true

            }
            if(master){
                state.error = false
                def masterAttributes = showAndGetMasterAttributes()
                input "slaves", "capability.${getCapability()}", title: "Select Light(s)", submitOnChange: true, required: true, multiple: true
                if(slaves){
                    Boolean p = parent.checkSlavesExist(getSlavesId(), getMasterId(), 4, false)
                    if(!p){
                        def slaveList = []
                        for(slave in slaves){
                            def slaveAttributes = getAttributes(slave)
                            for(attribute in slaveAttributes){
                                if(masterAttributes.contains(attribute) && !slaveList.contains(attribute)){
                                    slaveList.add(attribute)
                                    paragraph "<font color=\"green\">[${attribute}] events may be received...</font>"
                                }
                            }
                        }
                        state.error = false
                    }
                    else{
                        state.error = true
                        state.errorMsg = "Endless Loop - Select A Different Device"
                        paragraph "<font color=\"red\">Select A Different Device</font>" 
                    }
                }
            }
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    initialize()
    log.info "updated"   
}

def initialize() {
    if(!state.error){
        subscribe(master, "switch", switchHandler) // switch
        subscribe(master, "level", setLevelHandler) // set level
        log.debug "initialize"
        updateAppName("${master} -> ${slaves[0]}")
    }
    else{
        updateAppName("ERROR: ${state.errorMsg}")
    }
}


// converts the masterDeviceType input into a valid capability
def getCapability(){
    switch(masterDeviceType){
        case "Light Dimmer":
            return "switchLevel"
            break

        case "Light Switch":
            return "switch"
            break

        default:
            log.debug "getCapability() default case"
            return "default"
            break
    }
}

// name the app
def updateAppName(def string){
    app.updateLabel(string)
}

// switch on and off
def switchHandler(evt) {
    if(evt.value == "on" || evt.value == "off"){
        for(slave in slaves){
            if(slave.currentValue("switch")=="off"){
                slave.on()
            }else{
                slave.off()
            }
        }
    }
}

// switch set level
def setLevelHandler(evt){
    def level = evt.value.toFloat()
    level = level.toInteger()
    if(level>0){
        slaves?.on()
    }else{
        slaves?.off()
    }
}
// returns a map (Attribute name : Value)
def getAttributeValuesMap(def userDevice){
    def attributes = userDevice.getSupportedAttributes()
    def allowedAttributes = getAllowedAttributesList()
    def values = [:]
    for (attribute in attributes){
        if(allowedAttributes.contains(attribute.getName())){
            values.put(attribute.getName(), userDevice.currentValue(attribute.getName()))
        }
    }
    return values
}

// returns a list of command names of userDevice
def getCommands(def userDevice){
    def commands = userDevice.getSupportedCommands()
    def names = []
    for (command in commands){
        names.add(command.getName())
    }
    return names
}

// returns a list of attribute names from userDevice
def getAttributes(def userDevice){
    def attributeNames = []
    def attributes = userDevice.getSupportedAttributes()

    for (attribute in attributes){
        attributeNames.add(attribute.getName())
    }

    return attributeNames
}

// gets a list of the allowed attributes
def getAllowedAttributesList(){
    def allowedList = []
    switch(getCapability()){
        case "switchLevel":
        case "switch":
        case "fanControl":
            allowedList = ["switch", "level"]
            break

        default:
            log.debug "getAllowedAttributesList() default case"
            break
    }
    allowedList
}

// shows the allowed attributes of master device in paragraphs and returns the list of attribute names
def showAndGetMasterAttributes(){
    def allowedAttributes = getAllowedAttributesList()
    def attributes = master.getSupportedAttributes()
    def savedAttributes = []
    for (attribute in attributes){
        def attributeName = attribute.getName()
        if(allowedAttributes.contains(attributeName)){
            if(!savedAttributes.contains(attributeName)){
                savedAttributes.add(attributeName)
                paragraph "<font color=\"green\">[${attribute}] events may be forwarded...</font>"
            }
        }
    }
    return savedAttributes
}

// used in the parent app to check for endless looping
def getMasterId(){
    master.getId()
}

// used in the parent app to check for endless looping
def getSlavesId(){
    def list = []
    for(slave in slaves){
        list.add(slave.getId())
    }
    return list
}
