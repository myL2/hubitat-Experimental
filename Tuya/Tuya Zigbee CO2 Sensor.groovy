import groovy.transform.Field
import hubitat.zigbee.zcl.DataType
import groovy.transform.CompileStatic

metadata {
    definition(name: 'Tuya Zigbee CO2 Sensor', namespace: 'myL2', author: 'SebyM', importUrl: '', singleThreaded: true ) {
        capability 'Refresh'
        capability 'Health Check'
        capability 'CarbonDioxideMeasurement'
        capability 'RelativeHumidityMeasurement'
        capability 'TemperatureMeasurement'

        attribute 'rtt', 'number'
        attribute 'healthStatus', 'enum', ['unknown', 'offline', 'online']
        
        attribute 'alarmVolume', 'string' //DP05 0:low 1:high 2:mute
        attribute 'brightness', 'string' //DP11 1:low 2:med 3:high
		attribute 'carbonDioxideAlert', 'string' //DP01 1:low 2:med 3:high
        attribute 'batteryState', 'string' //DP14 1:low 2:med 3:high

		command 'initialize', [[name: 'Initialize the device after switching drivers.\n\r ***** Will load device default values! *****' ]]
        command 'forceUpdate', [[name: 'For the device to send the latest readings' ]]
        
        command 'test', [[name: 'dp', type: 'STRING', description: 'dp', defaultValue : ''], [name: 'dp_type', type: 'ENUM', constraints: ['DP_TYPE_VALUE', 'DP_TYPE_BOOL', 'DP_TYPE_ENUM'], description: 'dp_type', defaultValue : ''], [name: 'fncmd', type: 'STRING', description: 'fncmd', defaultValue : '']]
        
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0004,0005,EF00,0000,ED00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE284_xpvamyfz', deviceJoinName: 'Tuya Zigbee CO2 Sensor'
        
    }
    preferences {
        input(name: 'checkInterval', type: 'number', title: '<b>Check interval</b>, in seconds', description: 'The time period when the device will be checked for health status', range: '10..99999', defaultValue: 60)
        
        input(name: 'txtEnable', type: 'bool', title: '<b>Description text logging</b>', description: 'Display measured values in HE log page. Recommended setting is <b>on</b>', defaultValue: true)
        input(name: 'logEnable', type: 'bool', title: '<b>Debug logging</b>', description: 'Debug information, useful for troubleshooting. Recommended setting is <b>off</b>', defaultValue: false)
        /**/
		input "databaseHost", "text", title: "InfluxDB Host", defaultValue: "192.168.1.100", required: true
   	    input "databasePort", "text", title: "InfluxDB Port", defaultValue: "8086", required: true
       	input "databaseName", "text", title: "InfluxDB Database Name", defaultValue: "Hubitat", required: true
		/**/
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
                        if (logEnable) { log.debug "${device.displayName} Tuya check-in message (attribute ${it.attrId} reported: ${it.value})" }
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

// tuya DP type
private getDP_TYPE_RAW()        { '01' }    // [ bytes ]
private getDP_TYPE_BOOL()       { '01' }    // [ 0/1 ]
private getDP_TYPE_VALUE()      { '02' }    // [ 4 byte value ]
private getDP_TYPE_STRING()     { '03' }    // [ N byte string ]
private getDP_TYPE_ENUM()       { '04' }    // [ 0-255 ]
private getDP_TYPE_BITMAP()     { '05' }    // [ 1,2,4 bytes ] as bits

private getCLUSTER_TUYA()       { 0xEF00 }
private getSETDATA()            { 0x00 }

void parseZHAcommand(Map descMap) {
    //logWarn "Processing command: cluster=${descMap.clusterId} command=${descMap.command} attrId=${descMap.attrId} value=${descMap.value} data=${descMap.data}"
    switch (descMap.command) {
		case '02':
        switch (descMap.clusterId) {
            case 'EF00' :
            int value = getAttributeValue(descMap.data)
            String cmd = descMap.data[2]
            //if (logEnable == true) {logWarn "${device.displayName} cmd=${cmd} value=${value}"}
            if (logEnable == true) {logData(cmd,value)}
            switch (cmd) {
                case "01":
                valueText = ''
                switch(value){
                    case "0": valueText='low'; break;
                    case "1": valueText='high'; break;
                    case "2": valueText='alert'; break;
                    default : valueText='Unknown'; break;
                }
                sendEvent(name: 'carbonDioxideAlert', value: valueText, descriptionText: 'carbonDioxide status is ${valueText}', unit: '')
                forceUpdate()
                break;
                case "02":
                sendEvent(name: 'carbonDioxide', value: value, descriptionText: '', unit: '')
                break;
                case "05":
                valueText = ''
                switch(value){
                    case "0": valueText='low'; break;
                    case "1": valueText='high'; break;
                    case "2": valueText='mute'; break;
                    default : valueText='Unknown'; break;
                }
                sendEvent(name: 'alarmVolume', value: valueText, descriptionText: '', unit: '')
                break;
				case "11":
                valueText = ''
                switch(value){
                    case "1": valueText='low'; break;
                    case "2": valueText='med'; break;
                    case "3": valueText='high'; break;
                    default : valueText='Unknown'; break;
                }
                sendEvent(name: 'brightness', value: valueText, descriptionText: '', unit: '')
                break;
                case "12":
                sendEvent(name: 'temperature', value: value, descriptionText: '', unit: '')
                break;
                case "13":
                sendEvent(name: 'humidity', value: value, descriptionText: '', unit: '')
                break;
                case "14":
                case "0E":
                valueText = ''
                switch(value){
                    case "0": valueText='low'; break;
                    case "1": valueText='med'; break;
                    case "2": valueText='high'; break;
                    default : valueText='Unknown'; break;
                }
                sendEvent(name: 'batteryState', value: valueText, descriptionText: '', unit: '')
                break;
                case "65":
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


void parseZDOcommand(Map descMap) {
    switch (descMap.clusterId) {
        case '0006' :
            if (logEnable) { log.info "${device.displayName} Received match descriptor request, data=${descMap.data} (Sequence Number:${descMap.data[0]}, Input cluster count:${descMap.data[5]} Input cluster: 0x${descMap.data[7] + descMap.data[6]})" }
            break
        case '0013' : // device announcement
            if (logEnable) { log.info "${device.displayName} Received device announcement, data=${descMap.data} (Sequence Number:${descMap.data[0]}, Device network ID: ${descMap.data[2] + descMap.data[1]}, Capability Information: ${descMap.data[11]})" }
            state.rejoinCounter = (state.rejoinCounter ?: 0) + 1
            // sendZigbeeCommands(tuyaBlackMagic())
            // activeEndpoints()
            // configure()
            break
        case '8004' : // simple descriptor response
            if (logEnable) { log.info "${device.displayName} Received simple descriptor response, data=${descMap.data} (Sequence Number:${descMap.data[0]}, status:${descMap.data[1]}, length:${hubitat.helper.HexUtils.hexStringToInt(descMap.data[4])}" }
            parseSimpleDescriptorResponse(descMap)
            break
        case '8005' : // Active Endpoint Response
            if (logEnable) { log.info "${device.displayName} Received endpoint response: cluster: ${descMap.clusterId} (endpoint response) endpointCount = ${ descMap.data[4]}  endpointList = ${descMap.data[5]}" }
            break
        case '8021' : // bind response
            if (logEnable) { log.info "${device.displayName} Received bind response, data=${descMap.data} (Sequence Number:${descMap.data[0]}, Status: ${descMap.data[1] == '00' ? 'Success' : '<b>Failure</b>'})" }
            break
        case '8022' : // unbind response
            if (logEnable) { log.info "${device.displayName} Received unbind response, data=${descMap.data} (Sequence Number:${descMap.data[0]}, Status: ${descMap.data[1] == '00' ? 'Success' : '<b>Failure</b>'})" }
            break
        case '8034' : // leave response
            if (logEnable) { log.info "${device.displayName} Received leave response, data=${descMap.data}" }
            break
        case '8038' : // Management Network Update Notify
            if (logEnable) { log.info "${device.displayName} Received Management Network Update Notify, data=${descMap.data}" }
            break
        default :
            if (logEnable) { log.warn "${device.displayName} Unprocessed ZDO command: cluster=${descMap.clusterId} command=${descMap.command} attrId=${descMap.attrId} value=${descMap.value} data=${descMap.data}" }
            break    // 2022/09/16
    }
}


void parseSimpleDescriptorResponse(Map descMap) {
    //log.info "${device.displayName} Received simple descriptor response, data=${descMap.data} (Sequence Number:${descMap.data[0]}, status:${descMap.data[1]}, length:${hubitat.helper.HexUtils.hexStringToInt(descMap.data[4])}"
    if (logEnable == true) { log.info "${device.displayName} Endpoint: ${descMap.data[5]} Application Device:${descMap.data[9]}${descMap.data[8]}, Application Version:${descMap.data[10]}" }
    int inputClusterCount = hubitat.helper.HexUtils.hexStringToInt(descMap.data[11])
    String inputClusterList = ''
    for (int i in 1..inputClusterCount) {
        inputClusterList += descMap.data[13 + (i - 1) * 2] + descMap.data[12 + (i - 1) * 2 ] + ','
    }
    inputClusterList = inputClusterList.substring(0, inputClusterList.length() - 1)
    if (logEnable == true) { log.info "${device.displayName} Input Cluster Count: ${inputClusterCount} Input Cluster List : ${inputClusterList}" }
    if (getDataValue('inClusters') != inputClusterList)  {
        if (logEnable == true) { log.warn "${device.displayName} inClusters=${getDataValue('inClusters')} differs from inputClusterList:${inputClusterList} - will be updated!" }
        updateDataValue('inClusters', inputClusterList)
    }

    int outputClusterCount = hubitat.helper.HexUtils.hexStringToInt(descMap.data[12 + inputClusterCount * 2])
    String outputClusterList = ''
    if (outputClusterCount >= 1) {
        for (int i in 1..outputClusterCount) {
            outputClusterList += descMap.data[14 + inputClusterCount * 2 + (i - 1) * 2] + descMap.data[13 + inputClusterCount * 2 + (i - 1) * 2] + ','
        }
        outputClusterList = outputClusterList.substring(0, outputClusterList.length() - 1)
    }

    if (logEnable == true) { log.info "${device.displayName} Output Cluster Count: ${outputClusterCount} Output Cluster List : ${outputClusterList}" }
    if (getDataValue('outClusters') != outputClusterList)  {
        if (logEnable == true) { log.warn "${device.displayName} outClusters=${getDataValue('outClusters')} differs from outputClusterList:${outputClusterList} -  will be updated!" }
        updateDataValue('outClusters', outputClusterList)
    }
}

void forceUpdate(){
    //logData("99","99")
    ArrayList<String> cmds = []
    def dp = '65'
    def fn = '01'
	cmds = sendTuyaCommand(dp, DP_TYPE_BOOL, fn)
    sendZigbeeCommands(cmds)
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

/*
private sendTuyaCommand(dp, dp_type, fncmd, delay=200) {
    ArrayList<String> cmds = []
    cmds += zigbee.command(CLUSTER_TUYA, SETDATA, [:], delay, PACKET_ID + dp + dp_type + zigbee.convertToHexString((int)(fncmd.length() / 2), 4) + fncmd )
    logDebug "sendTuyaCommand dp=${dp}, dp_type=${dp_type}, fncmd=${fncmd}: ${cmds}"
    return cmds
}
*/

void logData(cmd,value){
    def data = "rawData,deviceId=${device.deviceNetworkId.toString()},deviceName=${cmd},groupName=Home,hubId=${hubId},hubName=${escapeStringForInfluxDB(device.hub.name.toString())},locationId=${escapeStringForInfluxDB(location.id.toString())},locationName=${escapeStringForInfluxDB(location.name)}"
    data += " value=${value}"
    try {
		def postParams = [
			uri: "http://${databaseHost}:${databasePort}/write?db=${databaseName}" ,
			requestContentType: 'application/json',
			contentType: 'application/json',
			body : data
			]
		asynchttpPost('handleInfluxResponse', postParams) 
	} catch (e) {	
		logDebug("postToInfluxDB(): Something went wrong when posting: ${e}","error")
	}
}

def handleInfluxResponse(hubResponse, data) {
    //logger("postToInfluxDB(): status of post call is: ${hubResponse.status}", "info")
    if(hubResponse.status >= 400) {
		logDebug("postToInfluxDB(): Something went wrong! Response from InfluxDB: Status: ${hubResponse.status}, Headers: ${hubResponse.headers}, Data: ${data}","error")
    }
}

private escapeStringForInfluxDB(str) {
    //logger("$str", "info")
    if (str) {
        str = str.replaceAll(" ", "\\\\ ") // Escape spaces.
        str = str.replaceAll(",", "\\\\,") // Escape commas.
        str = str.replaceAll("=", "\\\\=") // Escape equal signs.
        str = str.replaceAll("\"", "\\\\\"") // Escape double quotes.
        //str = str.replaceAll("'", "_")  // Replace apostrophes with underscores.
    }
    else {
        str = 'null'
    }
    return str
}

void test(dp, dp_type, fncmd) {
    tuyaTest(dp, fncmd, dp_type)
 //ArrayList<String> cmds = []
 //cmds = sendTuyaCommand(dp, dp_type, fncmd)
 //sendZigbeeCommands(cmds)
}

public List<String> getTuyaCommand(String dp, String dp_type, String fncmd, int tuyaCmdDefault = SETDATA) { return sendTuyaCommand(dp, dp_type, fncmd, tuyaCmdDefault) }

public List<String> sendTuyaCommand(String dp, String dp_type, String fncmd, int tuyaCmdDefault = SETDATA) {
    List<String> cmds = []
    int ep = safeToInt(state.destinationEP)
    if (ep == null || ep == 0) { ep = 1 }
    int tuyaCmd
    // added 07/01/2024 - deviceProfilesV3 device key tuyaCmd:04 : owerwrite all sendTuyaCommand calls for a specfic device profile, if specified!
    if (this.respondsTo('getDEVICE') && DEVICE?.device?.tuyaCmd != null) {
        tuyaCmd = DEVICE?.device?.tuyaCmd
    }
    else {
        tuyaCmd = tuyaCmdDefault // 0x00 is the default command for most of the Tuya devices, except some ..
    }
    cmds = zigbee.command(CLUSTER_TUYA, tuyaCmd, [destEndpoint :ep], delay = 201, PACKET_ID + dp + dp_type + zigbee.convertToHexString((int)(fncmd.length() / 2), 4) + fncmd )
    logDebug "${device.displayName} getTuyaCommand (dp=$dp fncmd=$fncmd dp_type=$dp_type) = ${cmds}"
    return cmds
}

private String getPACKET_ID() { return zigbee.convertToHexString(new Random().nextInt(65536), 4) }


public void tuyaTest(String dpCommand, String dpValue, String dpTypeString ) {
    String dpType   = dpTypeString == 'DP_TYPE_VALUE' ? DP_TYPE_VALUE : dpTypeString == 'DP_TYPE_BOOL' ? DP_TYPE_BOOL : dpTypeString == 'DP_TYPE_ENUM' ? DP_TYPE_ENUM : null
    String dpValHex = dpTypeString == 'DP_TYPE_VALUE' ? zigbee.convertToHexString(dpValue as int, 8) : dpValue
    if (settings?.logEnable) { log.warn "${device.displayName}  sending TEST command=${dpCommand} value=${dpValue} ($dpValHex) type=${dpType}" }
    sendZigbeeCommands( sendTuyaCommand(dpCommand, dpType, dpValHex) )
}

