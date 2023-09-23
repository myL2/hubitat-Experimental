definition(
    name: "Edge Mirror",
    namespace: "myL2",
    author: "SebyM",
    singleInstance: true,
    description: "Toggle one switch with the change of another",
    category: "Convenience",
    importUrl: "",
    iconUrl: "",
    iconX2Url: "")

preferences {
    page(name: "mainPage")
}

def mainPage(){
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section ("Edge Mirror"){
            app(name: "childApps1", appName: "Edge Mirror Child", namespace: "myL2", title: "Create New Edge Mirror", submitOnChange: true, multiple: true)
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    initialize()
}

def initialize() {
    log.debug "there are ${childApps.size()}"
    childApps.each {child ->
        log.debug "child app: ${child.label}"
    }
}

def checkSlavesExist(def slaves, def master, def iterations, Boolean b){
    if(childApps.size() == 0){
        for(slave in slaves){
            if(slave == master){
                if(iterations <= 0){
                    return true
                }
                else{
                    if(checkSlavesExist(slaves, master, iterations-1, b)){
                        return true
                    }
                }
            }
        }
    }


    for(child in childApps){
        for(slave in slaves){
            if(child.getMasterId() == slave || master == slave){
                log.debug "masterChild: ${child.getMasterId()}  master: ${master}   slave: ${slave}"
                if(iterations <= 0){
                    return true
                }
                else{s
                    log.warn "Iterations: ${iterations}"
                    if(checkSlavesExist(slaves, master, iterations-1, b)){
                        return true
                    }
                }
            }
        }
    }
    return b
}
