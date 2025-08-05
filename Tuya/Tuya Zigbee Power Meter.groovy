import groovy.transform.Field
import hubitat.zigbee.zcl.DataType
import groovy.transform.CompileStatic

metadata {
    definition(name: 'Tuya Zigbee Power Meter', namespace: 'myL2', author: 'SebyM', importUrl: '', singleThreaded: true ) {
        capability 'Refresh'
        capability 'Health Check'

        attribute 'rtt', 'number'
        attribute 'healthStatus', 'enum', ['unknown', 'offline', 'online']

		command 'initialize', [[name: 'Initialize the relay after switching drivers.\n\r ***** Will load device default values! *****' ]]
        command 'removeChildren'
        
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0004,0005,EF00,0000', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE204_goecjd1t', deviceJoinName: 'Tuya Zigbee 1 Gang Power Meter'
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0004,0005,EF00,0000', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE204_jrcfsaa3', deviceJoinName: 'Tuya Zigbee 2 Gang Power Meter'
        
    }
    preferences {
        input(name: 'numberOfChannels', type: 'number', title: 'Number of channels', description: 'Number of channels', range: '1..2', defaultValue: 1)

        input(name: 'checkInterval', type: 'number', title: '<b>Check interval</b>, in seconds', description: 'The time period when the smart plug will be checked for health status', range: '10..99999', defaultValue: 60)
        
        input(name: 'txtEnable', type: 'bool', title: '<b>Description text logging</b>', description: 'Display measured values in HE log page. Recommended setting is <b>on</b>', defaultValue: true)
        input(name: 'logEnable', type: 'bool', title: '<b>Debug logging</b>', description: 'Debug information, useful for troubleshooting. Recommended setting is <b>off</b>', defaultValue: false)
    }
}

void parse(String description) {
    //logDebug "parse: description is $description"

    if (state.rxCounter != null) { state.rxCounter = state.rxCounter + 1 }
    setPresent()
    unschedule('deviceCommandTimeout')
    
    Map event = [:]
    try {
        event = zigbee.getEvent(description)
    }
    catch (e) {
        logWarn "parse: exception caught while trying to getEvent... description: ${description}"
        // continue
    }
    if (event) {
        logDebug "Event enter: $event"
    }else{
        Map descMap = [:]
        //try {
            descMap = zigbee.parseDescriptionAsMap(description)
            if (descMap.attrId != null) {
                List attrData = [[cluster: descMap.cluster, attrId: descMap.attrId, value: descMap.value, status: descMap.status]]
                attrData.each {
                    if (it.attrId == '0001') {
                        //if (logEnable) { log.debug "${device.displayName} Tuya check-in message (attribute ${it.attrId} reported: ${it.value})" }
                        Long now = new Date().getTime()
                        if (state.lastTx == null) { state.lastTx = [:] }
                        int timeRunning = now.toInteger() - (state.lastTx['pingTime'] ?: '0').toInteger()
                        if (timeRunning < MAX_PING_MILISECONDS) {
                            sendRttEvent()
                        }
                    }else{
                        logDebug "attribute report: cluster=${it.cluster} attrId=${it.attrId} value=${it.value} status=${it.status} data=${descMap.data}"
                    }
                }
            }
            else if (descMap.profileId == '0000') { //zdo
            	parseZDOcommand(descMap)
            }
            else if (descMap.clusterId != null && descMap.profileId == '0104') { // ZHA global command
                parseZHAcommand(descMap)
            }
            else {
                logDebug "Unprocessed unknown command: cluster=${descMap.clusterId} command=${descMap.command} attrId=${descMap.attrId} value=${descMap.value} data=${descMap.data}"
            }
        //}
        //catch (e) {
        //    logWarn "parse: exception caught while parsing descMap:  ${descMap}"
        //    return
        //}
    }
}

@Field static final Integer energyDiv = 10
@Field static final Integer currentDiv = 1000
@Field static final Integer powerDiv = 1 //divide in child app
@Field static final Integer voltageDiv = 10
@Field static final Integer refreshTimer = 3000
@Field static final Integer COMMAND_TIMEOUT = 6
@Field static final Integer MAX_PING_MILISECONDS = 10000
@Field static final Integer presenceCountThreshold = 3

void parseZHAcommand(Map descMap) {
    //logWarn "Processing command: cluster=${descMap.clusterId} command=${descMap.command} attrId=${descMap.attrId} value=${descMap.value} data=${descMap.data}"
    switch (descMap.command) {
		case '02':
        switch (descMap.clusterId) {
            case 'EF00' :
            int value = getAttributeValue(descMap.data)
            String cmd = descMap.data[2]
            if (logEnable == true) {logWarn "${device.displayName} cmd=${cmd} value=${value}"}
            //if (logEnable == true) {logData(cmd,value)}
            switch (cmd) {
            case '68': //energy this session
                //updateChildValues(0, "energy", value / (energyDiv as Float))
            break
            case '69':
                if (numberOfChannels==2) updateChildValues(0, "power", value / (powerDiv as Float))
        	break;
            case '6A':
                if (numberOfChannels==2) updateChildValues(0, "amperage", value / (currentDiv as Float))
        	break;
            case '6B':
                if (numberOfChannels==2) updateChildValues(0, "voltage", value / (voltageDiv as Float))
        	break;
            case '73':2
                if (numberOfChannels==2) updateChildValues(1, "power", value / (powerDiv as Float))
        	break;
            case '74':
                if (numberOfChannels==2) updateChildValues(1, "amperage", value / (currentDiv as Float))
        	break;
            case '75':
                if (numberOfChannels==2) updateChildValues(1, "voltage", value / (voltageDiv as Float))
        	break;
            case '12':
                if (numberOfChannels==1) updateChildValues(0, "amperage", value / (currentDiv as Float))
        	break;
            case '13':
                if (numberOfChannels==1) updateChildValues(0, "power", value / (powerDiv as Float))
        	break;
            case '14':
                if (numberOfChannels==1) updateChildValues(0, "voltage", value / (voltageDiv as Float))
        	break;
                //unhandled
            case '01':
            case '67':
            case '69':
            case '6C':
            case '6D':
            case '6E':
            case '70':
            case '71':
            case '72':
            case '76':
            case '77':
            case '78':
            case '7A':
        	break;
            default:
                if (logEnable == true) { log.warn "${device.displayName} Tuya unknown attribute: ${descMap.data[0]}${descMap.data[1]}; cmd ${descMap.data[2]}; ${descMap.data[3]}${descMap.data[4]} data.size() = ${descMap.data.size()} value: ${value}}" }
            break
        	}
       	}
        break
        case '0B' : // ZCL Default Response
            String status = descMap.data[1]
            if (status != '00') {
                switch (descMap.clusterId) {
                    case '0003' : // Identify response
                        if (txtEnable == true) { log.warn "${device.displayName} Identify command is not supported by ${device.getDataValue('manufacturer')}" }
                        break
                    case '0006' : // Switch state
                        if (logEnable == true) { log.warn "${device.displayName} Switch state is not supported -> Switch polling will be disabled." }
                        state.switchPollingSupported = false
                        break    // fixed in ver. 1.5.0
                    case '0B04' : // Electrical Measurement
                        if (logEnable == true) { log.warn "${device.displayName} Electrical measurement is not supported -> Power, Voltage and Amperage polling will be disabled." }
                        state.powerPollingSupported = false
                        state.voltagePollingSupported = false
                        state.currentPollingSupported = false
                        break
                    case '0702' : // Energy
                        if (logEnable == true) { log.warn "${device.displayName} Energy measurement is not supported -> Energy polling will be disabled." }
                        state.energyPollingSupported = false
                        break
                    default :
                        if (logEnable == true) { log.info "${device.displayName} Received ZCL Default Response to Command ${descMap.data[0]} for cluster:${descMap.clusterId} , data=${descMap.data} (Status: ${descMap.data[1] == '00' ? 'Success' : '<b>Failure</b>'})" }
                        break
                }
            }
        break
        case '24' :    // Tuya time sync
        //logDebug 'Tuya time sync'
        if (descMap?.clusterInt == 0xEF00 && descMap?.command == '24') {        //getSETTIME
            //if (settings?.logEnable) { log.debug "${device.displayName} time synchronization request from device, descMap = ${descMap}" }
            int offset = 0
            try {
                offset = location.getTimeZone().getOffset(new Date().getTime())
            }
            catch (e) {
                if (settings?.logEnable) { log.error "${device.displayName} cannot resolve current location. please set location in Hubitat location setting. Setting timezone offset to zero" }
            }
            List<String> cmds = zigbee.command(0xEF00, 0x24, '0008' + zigbee.convertToHexString((int)(now() / 1000), 8) + zigbee.convertToHexString((int)((now() + offset) / 1000), 8))
            //logDebug "now is: ${now()}"  // KK TODO - convert to Date/Time string!
            //logDebug "sending time data : ${cmds}"
            cmds.each { sendHubCommand(new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE)) }
            if (state.txCounter != null) { state.txCounter = state.txCounter + 1 } else { state.txCounter = 1 }
            return
        }
        break
        default:
        	if (logEnable == true) { log.warn "${device.displayName} Unprocessed global command: cluster=${descMap.clusterId} command=${descMap.command} attrId=${descMap.attrId} value=${descMap.value} data=${descMap.data}" }
        break
    }
}

def updateChildValues(channelNo, command, value){
        if (numberOfChannels) {
        String thisId = device.deviceNetworkId
        child = getChildDevice("${thisId}-${channelNo}")
        if (child) {
            //logDebug "${child} ${command} is now ${value}"
            child.updateValue(command, value)
        }else{
            logDebug "Got a request for a child that does not exist: ${channelNo}"
        }
    }
}

def configureChildren(){
    if (numberOfChannels) {
        String thisId = device.deviceNetworkId
        for (int myindex = 0; myindex < numberOfChannels; myindex++) {
            if (!getChildDevice("${thisId}-${myindex}")) {
                newDevice= addChildDevice("myL2", "Tuya Zigbee Power Meter Child", "${thisId}-${myindex}", [name: "${thisId} - ${myindex}", isComponent: true])
                log.info "Installing child ${thisId}-${myindex}."
            }
        }
    }    
}

def removeChildren(){
    if (numberOfChannels) {
        String thisId = device.deviceNetworkId
        for (int myindex = 0; myindex < numberOfChannels; myindex++) {
            log.info myindex
            if (getChildDevice("${thisId}-${myindex}")) {
                deleteChildDevice("${thisId}-${myindex}")
                log.info "Removing child ${thisId}-${myindex}."
            }
        }
    }    
}

void logInfo(String msg) {
    if (settings?.txtEnable) {
        log.info "${device.displayName} " + msg
    }
}

void logDebug(String msg) {
    if (settings?.logEnable) {
        log.debug "${device.displayName} " + msg
    }
}

void logWarn(String msg) {
    if (settings?.logEnable) {
        log.warn "${device.displayName} " + msg
    }
}

private int getAttributeValue(ArrayList _data) {
    int retValue = 0
    try {
        if (_data.size() >= 6) {
            int dataLength = zigbee.convertHexToInt(_data[5]) as Integer
            int power = 1
            for (i in dataLength..1) {
                retValue = retValue + power * zigbee.convertHexToInt(_data[i + 5])
                power = power * 256
            }
        }
    }
    catch (e) {
        log.error "${device.displayName} Exception caught : data = ${_data}"
    }
    return retValue
}

void sendHealthStatusEvent(String value) {
    sendEvent(name: 'healthStatus', value: value, descriptionText: "${device.displayName} healthStatus set to $value")
}

// called when any event was received from the Zigbee device in parse() method..
void setPresent() {
    if ((device.currentValue('healthStatus', true) ?: 'unknown') != 'online') {
        sendHealthStatusEvent('online')
    }
    state.notPresentCounter = 0
}

void autoPoll() {
    checkIfNotPresent()
}

String autoCron(int timeInSeconds) {
    if (timeInSeconds < 60) {
        schedule("*/$timeInSeconds * * * * ? *", autoPoll)
        return timeInSeconds.toString() + ' seconds'
    }
    else {
        int minutes = (timeInSeconds / 60) as int
        if (minutes < 60) {
            schedule("0 */$minutes * ? * *", autoPoll)
            return minutes.toString() + ' minutes'
        }
        else {
            int hours = (minutes / 60) as int
            if (hours > 23) { hours = 23 }
            schedule("0 0 */$hours ? * *", autoPoll)
            return hours.toString() + ' hours'
        }
    }
}

// called from autoPoll()
void checkIfNotPresent() {
    if (state.notPresentCounter != null) {
        state.notPresentCounter = state.notPresentCounter + 1
        if (state.notPresentCounter > presenceCountThreshold) {
            if ((device.currentValue('healthStatus', true) ?: 'unknown') != 'offline') {
                sendHealthStatusEvent('offline')
                if (logEnable == true) { log.warn "${device.displayName} not present!" }
            }
        }
    }
}

void configure() {
}

void updated() {
    if (txtEnable == true) { log.info "${device.displayName} Updating ${device.getLabel()} (${device.getName()})" }
    if (txtEnable == true) { log.info "${device.displayName} Debug logging is <b>${logEnable}</b> Description text logging is  <b>${txtEnable}</b>" }
    autoCron(settings.checkInterval as int)
    if (logEnable == true) {
        runIn(86400, logsOff, [overwrite: true, misfire: 'ignore'])    // turn off debug logging after 24 hours
        if (txtEnable == true) { log.info "${device.displayName} Debug logging will be automatically switched off after 24 hours" }
    }
    else {
        unschedule(logsOff)
    }
}

void initializeVars(boolean fullInit = false) {
    //
    if (logEnable == true) { log.info "${device.displayName} InitializeVars()... fullInit = ${fullInit}" }
    if (fullInit == true) {
        logDebug 'clearing states and preferences ...'
        logDebug "preservedResetEnergy = ${preservedResetEnergy}"
        state.clear()
    }
    if (device.currentValue('healthStatus') == null) { sendHealthStatusEvent('unknown') }

    switch (device.data.manufacturer) {
        case '_TZE204_goecjd1t':
            numberOfChannels = 1
            break
        case '_TZE204_jrcfsaa3':
            numberOfChannels = 2
 			break
     }
    
    state.rxCounter = 0
    state.txCounter = 0
    if (state.lastRx == null) { state.lastRx = [:] }
    if (state.lastTx == null) { state.lastTx = [:] }
	if (fullInit == true || state.notPresentCounter == null) { state.notPresentCounter = 0 }
    
    final String ep = device.getEndpointId()
    if (ep  != null && ep != 'F2') {
        state.destinationEP = ep
        logDebug "destinationEP = ${state.destinationEP}"
    }
    else {
        if (txtEnable == true) { log.warn "${device.displayName} Destination End Point not found or invalid(${ep}), please re-pair the device!" }
        state.destinationEP = '01'    // fallback EP
    }
}

void initialize() {
    logDebug 'Initialize()...'
    unschedule()
    initializeVars(fullInit = true)
    configureChildren()
    runIn(2, refresh, [overwrite: true])
    runIn(10, updated, [overwrite: true])         // calls also configure()
}

void installed() {
    if (txtEnable == true) { log.info "${device.displayName} Installed()..." }
    runIn(5, initialize, [overwrite: true])
}

void uninstalled() {
    if (logEnable == true) { log.info "${device.displayName} Uninstalled()..." }
    unschedule()     // unschedule any existing schedules
}

void logsOff() {
    log.warn "${device.displayName} debug logging disabled..."
    device.updateSetting('logEnable', [value:'false', type:'bool'])
}

void poll(boolean refreshAll = false) {
    logDebug "polling.. refreshAll is ${refreshAll}"
    List<String> cmds = []
    int ep = safeToInt(state.destinationEP)
    // todo cmds += zigbee.readAttribute(0xEF00, [0x0068, 0x0069], [destEndpoint :ep], delay = 200)
    cmds += zigbee.readAttribute(0x0000, 0x0001, [destEndpoint :ep], delay = 200)
    state.isRefreshRequest = refreshAll
    runInMillis(refreshTimer, clearRefreshRequest, [overwrite: true])           // 3 seconds
    sendZigbeeCommands(cmds)    // 11/16/2022
}

void sendZigbeeCommands(List<String> cmds) {
    if (logEnable) { log.trace "${device.displayName} sendZigbeeCommands : ${cmds}" }
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
    if (state.txCounter != null) { state.txCounter = state.txCounter + 1 }
}

void refresh() {
    logInfo 'refresh()...'
    state.isRefreshRequest = true
    poll(refreshAll = true)
    scheduleCommandTimeoutCheck()
    runInMillis(refreshTimer, clearRefreshRequest, [overwrite: true])           // 3 seconds
}

void clearRefreshRequest() { state.isRefreshRequest = false }

void ping() {
    logInfo 'ping...'
    scheduleCommandTimeoutCheck()
    if (state.lastTx == null) { state.lastTx = [:] }
    state.lastTx['pingTime'] = new Date().getTime()
    sendZigbeeCommands(zigbee.readAttribute(zigbee.BASIC_CLUSTER, 0x01, [:], 0))
}

Integer safeToInt(val, Integer defaultVal=0) {
    return "${val}"?.isInteger() ? "${val}".toInteger() : defaultVal
}

void scheduleCommandTimeoutCheck(int delay = COMMAND_TIMEOUT) {
    runIn(delay, 'deviceCommandTimeout')
}

void deviceCommandTimeout() {
    logWarn 'no response received (sleepy device or offline?)'
    sendRttEvent('timeout')
}

void sendRttEvent(String value=null) {
    Long now = new Date().getTime()
    if (state.lastTx == null) { state.lastTx = [:] }
    int timeRunning = now.toInteger() - (state.lastTx['pingTime'] ?: now).toInteger()
    String descriptionText = "Round-trip time is ${timeRunning} ms"
    if (value == null) {
        logInfo "${descriptionText}"
        sendEvent(name: 'rtt', value: timeRunning, descriptionText: descriptionText, unit: 'ms', isDigital: true)
    }
    else {
        descriptionText = "Round-trip time : ${value}"
        logInfo "${descriptionText}"
        sendEvent(name: 'rtt', value: value, descriptionText: descriptionText, isDigital: true)
    }
}

