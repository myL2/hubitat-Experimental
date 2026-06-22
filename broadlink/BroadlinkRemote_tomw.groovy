/*

Copyright 2022 - tomw

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

-------------------------------------------

Change history:

0.9.9 - tomw - Make device keep-alive ping a configurable option, and disable by default for RM4 devices.
0.9.8 - tomw - Fix for IllegalBlockSizeException.
0.9.7 - tomw - Support for sensors (temperature and humidity).
0.9.6 - tomw - Bugfixes and improved error reporting.
0.9.5 - tomw - Added ping for RM3 devices as keep-alive.
0.9.4 - tomw - Added 'sequence' scripting feature.
0.9.3 - tomw - Rework app UI to use buttons.
0.9.1 - tomw - Added push command, which uses the button name as a saved code to send.
0.9.0 - tomw - Initial release.

*/

metadata
{
    definition(name: "Broadlink Remote", namespace: "tomw", author: "tomw", 
               importUrl: "https://raw.githubusercontent.com/tomwpublic/hubitat_broadlink/main/broadlinkRemoteDriver")
    {
        capability "Actuator"
        capability "Initialize"
        capability "RelativeHumidityMeasurement"
        capability "Refresh"
        capability "TemperatureMeasurement"
        
        command "learnIR"
        
        command "cancelRF"
        command "learnRF"
        command "sweepRF"
        
        command "sendCodeData", [[name: "code*", type: "STRING"]]
        command "push", ["name"]
        
        command "clearSavedCodes"
        command "deleteSavedCode", [[name: "name*", type: "STRING"]]
        command "saveLearnedCode", [[name: "name*", type: "STRING"]]
        command "testLearnedCode"        
        command "sendSavedCode", [[name: "name*", type: "STRING"], [name: "reps", type: "NUMBER"]]
        
        command "cacheCodesForApp", ["retain"]

        // myL2 addition: create an Air Conditioner child device that drives saved AC* codes
        command "createAcChild"

        // compatibility for previous integration
        command "importCodes", [[name: "codeStore", type: "STRING"], [name: "overwrite", type: "STRING", description: "Type yes to overwrite"]]
        command "SendStoredCode", [[name: "name*", type: "STRING"], [name: "reps", type: "NUMBER"]]
        command "SendCode", ["code"]
        command "generateIR", ["offBurst", "onBurst", "leadIn", "bitmap", "leadOut"]
        // compatibility for previous integration
        
        attribute "learnedCode", "string"
        attribute "savedCode", "string"
        
        attribute "activity", "string"
        
        // enable to decode pcap trace
        //command "prepResponse", ["resp"]
    }
}

preferences
{
    section
    {
        input name: "ipAddress", type: "text", title: "IP address", required: true
        input name: "deviceHasRF", type: "bool", title: "Does your device support RF?", defaultValue: false
        input name: "doDevicePing", type: "bool", title: "Send keep-alive ping to RM3 and RM Pro devices?", defaultValue: true
    }
    section
    {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

import groovy.transform.Field
    
@Field INIT_KEY = "097628343fe99e23765c1513accf8b02"
@Field INIT_VECT = "562e17996d093d28ddb3ba695a2e6f58"

@Field cmdAuthReq = 0x65
@Field cmdControl = 0x6A

// common payloads that don't change
@Field byte[] getFwVers       = [0x68]
@Field byte[] checkSensors    = [0x01]
@Field byte[] checkSensors4   = [0x24]

import hubitat.device.HubAction
import hubitat.device.Protocol
import hubitat.helper.HexUtils

def logDebug(msg) 
{
    if (logEnable)
    {
        log.debug(msg)
    }
}

def initialize()
{
    unschedule()
    
    // clear these code values, in case the long strings cause browser problems
    device.deleteCurrentState("learnedCode")
    device.deleteCurrentState("savedCode")
    
    scan()
}

def updated()
{
    initialize()
}

def cancelAll()
{
    causeWaitCancel()
}

def scan()
{
    // always cancel and try to re-init on scan()
    cancelAll()    
    updateDevStatus("initializing")
    
    // https://github.com/mjg59/python-broadlink/blob/master/protocol.md#network-discovery    
    byte[] packet = [0] * 48
    packet[0x26] = 6
    
    def cs = calcChecksum(packet)
    packet = replaceSubArr(packet, cs, 0x20)
    
    def str = HexUtils.byteArrayToHexString(packet)

    def params = [type: HubAction.Type.LAN_TYPE_UDPCLIENT, destinationAddress: ipAddress, encoding: HubAction.Encoding.HEX_STRING, callback: "scanResponse"]
    
    def hubAction = new HubAction(str, Protocol.LAN, params)
    sendHubCommand(hubAction)
}

def scanResponse(message)
{
    def lanMsg = parseLanMessage(message)
    //logDebug("parse: ${lanMsg}")
    def plb = HexUtils.hexStringToByteArray(lanMsg.payload)
    logDebug("scanResp = ${HexUtils.byteArrayToHexString(plb)}")
    
    def devType = bytesToInt(subBytes(plb, 0x34, 2), "little")
    def mac = HexUtils.byteArrayToHexString(reverseBytes(subBytes(plb, 0x3A, 6)))    
    def name = new String(subBytes(plb, 0x40, plb.size() - 0x40))    
    def isLocked = plb[0x7F]
    
    def devDetails = [devType: devType, mac: mac, name: name, isLocked: isLocked, id: 0, authKey: INIT_KEY]
    setDevDetails(devDetails)
    
    if(isLocked)
    {
        updateDevStatus("device is locked in Broadlink app")
        return        
    }
    
    pauseExecution(500)
    
    // need to auth this device
    auth()
    updateDevStatus("waiting for auth", false)
}

def setDevDetails(devDetails) { state.devDetails = devDetails }

def getDevDetails() { return state.devDetails ?: [:]}

def updateDevStatus(activity, readyForAction = true)
{
    sendEvent(name: "activity", value: activity)
    setVolatileState("readyForAction", readyForAction)
}

def checkReady()
{
    def rfA = getVolatileState("readyForAction")
    if(null == rfA)
    {
        // not initialized, so assume "ready"
        return true
    }
    
    return rfA
}

def updateSeqStatus(inSeq = false)
{
    logDebug("updateSeqStatus = ${inSeq}")
    setVolatileState("seqExecuting", inSeq)
}

def checkSeqStatus()
{
    def sS = getVolatileState("seqExecuting")
    if(null == sS)
    {
        // not initialized, so assume "ready"
        return false
    }
    
    return sS
}

def auth()
{
    if(!checkReady()) { return } 
    
    byte[] authPacket = [0] * 80
    
    // https://github.com/mjg59/python-broadlink/blob/master/broadlink/device.py#L173
    authPacket[0x1E] = 1
    authPacket[0x2D] = 1
    
    // clear message count since we are re-auth'ing
    clrCount()
    
    send_packet(getDevDetails() << [callback: "authResponse"], cmdAuthReq, authPacket)
}

def authResponse(message)
{
    def resp = prepResponse(message, false, true)    
    if(null == resp) { return }
    
    logDebug("authResp = ${HexUtils.byteArrayToHexString(resp)}")
    
    // update device id and authKey
    def id = bytesToInt(subBytes(resp, 0, 4), "little")
    def authKey = HexUtils.byteArrayToHexString(subBytes(resp, 4, 16))
    getDevDetails() << [id: id, authKey: authKey]
    
    updateDevStatus("idle")
    
    // kick off ping() loop as keep-alive
    if(doDevicePing || (null == doDevicePing))
    {
        if(null == doDevicePing)
        {
            // no setting was specified, so this must be someone who upgraded source code
            //  so, make sure the default value is used at least once
            device.updateSetting("doDevicePing", true)
        }
        
        runIn(60, ping)
    }

    // try to check basic info
    interrogateDevice()
}

def senseAPI()
{
    def knownOldDevs = 
        [0x2737,0x278F,0x27C2,0x27C7,0x27CC,0x27CD,0x27D0,0x27D1,0x27D3,0x27DC,0x27DE,
         0x2712,0x272A,0x273D,0x277C,0x2783,0x2787,0x278B,0x2797,0x279D,0x27A1,0x27A6,
         0x27A9,0x27C3]
    
    if(knownOldDevs.contains(getDevDetails()?.devType))
    {
        // check against known list first
        setNewApi(false)
        return        
    }
    
    // otherwise, try new API by default
    setNewApi(true)
    pauseExecution(500)
    
    send_packet(getDevDetails() << [callback: "senseResponse"], cmdControl, [0x01] as byte[])
}

def senseResponse(message)
{
    def resp = prepResponse(message, false, true)
    
    if([] == resp)
    {
        if(isNewApi() == true)
        {
            log.debug "unmatched old API device: ${getDevDetails()?.devType}"
            setNewApi(false)
            setErrors(0)
        }
        
        return
    }
    
    // if we got this far, new API is confirmed
    //  so, disable ping()
    device.updateSetting("doDevicePing", false)
    
    // second way of getting device name
    //logDebug(new String(subBytes(resp, 0x48, resp.size() - 0x48)))
    //logDebug(resp.size())
    
    // second way of getting lock status
    //logDebug(resp[0x87])
}

def interrogateDevice()
{
    if(!checkReady()) { return }
    
    // get_fwversion
    send_packet(getDevDetails() << [callback: "interrogateResp"], cmdControl, getFwVers)
}

def refresh()
{
    if(!checkReady()) { return }
    
    // checkSensors
    send_packet(getDevDetails() << [callback: "sensorResponse"], cmdControl, isNewApi() ? checkSensors4 : checkSensors)
}

def sensorResponse(message)
{
    def resp = prepResponse(message)    
    if(resp?.size() == 0) { return }
    
    logDebug("sensorResp = ${HexUtils.byteArrayToHexString(resp)}")
    
    def temperature = resp[0] + (resp[1] / 100)
    if(temperature)
    {
        temperature = convertTemperatureIfNeeded(temperature, "C", 2)
        sendEvent(name: "temperature", value: temperature)
    }
    
    def humidity = resp[2] + (resp[3] / 100)
    if(humidity)
    {
        sendEvent(name: "humidity", value: humidity)
    }
}

def interrogateResp(message)
{
    def resp = prepResponse(message, false, true)    
    if(resp?.size() == 0) { return }
    
    //logDebug("interrogateResp = ${HexUtils.byteArrayToHexString(resp)}")
    
    switch(resp[0])
    {
        case getFwVers[0]:
            logDebug("get_fwversion: ${bytesToInt(subBytes(resp, 4, 2), "little")}")
            break
        default:
            logDebug resp
    }
    
    // try to determine which API version the device speaks
    senseAPI()
}

def ping()
{
    // send heartbeat ping that would normally come from cloud
    //  so that offline devices don't periodically reboot
    
    byte[] packet = [0] * 0x30
    packet[0x26] = 1

    def params = [type: HubAction.Type.LAN_TYPE_UDPCLIENT, destinationAddress: ipAddress, encoding: HubAction.Encoding.HEX_STRING]
    sendHubCommand(new HubAction(HexUtils.byteArrayToHexString(packet), Protocol.LAN, params))
    
    if(doDevicePing) { runIn(60, ping) }
}

def learnIR()
{
    if(!checkReady()) { return }
    
    // https://github.com/mjg59/python-broadlink/blob/master/protocol.md#entering-learning-mode    
    updateDevStatus("learning IR", false)

    send_packet(getDevDetails() << [callback: "learnResponse"], cmdControl, [0x03] as byte[])
}

def learnResponse(message)
{
    prepResponse(message)
    
    if(!checkError())
    {
        // initialize a counter for the readback
        setVolatileState("iter", 0)
        readback()
    }
}

def readback()
{
    // https://github.com/mjg59/python-broadlink/blob/master/protocol.md#entering-learning-mode    
    send_packet(getDevDetails() << [callback: "readbackResponse"], cmdControl, [0x04] as byte[])
}

def readbackResponse(message)
{
    waitForResponse(message, "readback", "processCodeResp", 40)
}

def processCodeResp(resp)
{
    def code = HexUtils.byteArrayToHexString(resp)
    //logDebug ("raw code = ${code}")

    sendEvent(name: "learnedCode", value: code)
    updateDevStatus("code captured")
}

def testLearnedCode()
{
    sendCodeData(device.currentValue("learnedCode"))    
}

//////////////////////////////////////
// RF state machine
//////////////////////////////////////

@Field byte[] startSweep  = [0x19]
@Field byte[] checkSweep  = [0x1A]
@Field byte[] cancelSweep = [0x1E]
@Field byte[] rfLearn     = [0x1B]

def hasRF()
{
    if(!deviceHasRF)
    {
        log.info "RF command ignored.  Check device preference setting."
    }

    return deviceHasRF
}

def cancelRF()
{
    if(!hasRF()) { return }
    
    cancel_sweep()
}

def learnRF()
{
    if(!checkReady()) { return }
    if(!hasRF()) { return }
    
    rf_learn()
}

def sweepRF()
{
    if(!checkReady()) { return }
    if(!hasRF()) { return }
    
    start_sweep()
}

def setTargetRF(freq)
{
    if(!hasRF()) { return }
    
    freq = freq?.toFloat()    
    if(!freq) { return }
    
    if(freq < 300 || freq > 450) { return }
    
    // user input expected in terms of MHz
    //   but stored in sweepFreq in terms of kHz
    freq = (freq * 1000)?.toInteger()    
    if(freq) { getDevDetails() << [sweepFreq: freq] }
}

def start_sweep()
{
    setVolatileState("iter", 0)
    
    send_packet(getDevDetails() << [callback: "parseRF"], cmdControl, startSweep)
    updateDevStatus("sweeping frequency - press and HOLD remote", false)
}

def check_sweep()
{
    send_packet(getDevDetails() << [callback: "parseRF"], cmdControl, checkSweep)
}

def cancel_sweep(resp = null)
{
    def msg = "trying to cancel RF sweep..."
    
    causeWaitCancel()
    
    updateDevStatus(msg, false)
    send_packet(getDevDetails() << [callback: "parseRF"], cmdControl, cancelSweep)
    updateDevStatus(msg, true)
}

def rf_learn(resp = null)
{
    if(null == getDevDetails()?.sweepFreq)
    {
        updateDevStatus("missing sweep frequency - be sure to run sweepRF first")
        return
    }
    
    updateDevStatus("learning RF - press and release button multiple times", false)
    
    byte[] learnCmd = appendByteArr(rfLearn, HexUtils.hexStringToByteArray("00000000000000000000000000"))
    byte[] freqTerm = subBytes(intToBytes(getDevDetails()?.sweepFreq, 4, "little"), 0, 3)
    learnCmd = replaceSubArr(learnCmd, freqTerm, 4)
    
    send_packet(getDevDetails() << [callback: "parseRF"], cmdControl, learnCmd)
}

def parseRF(message)
{
    def resp = prepResponse(message, false)
    if(null == resp) { return }
    
    logDebug("parseRF resp = ${HexUtils.byteArrayToHexString(resp)}")    
    
    switch(resp[0])
    {
        case startSweep[0]:
            if(checkError()) { return }
            // we started sweeping, so start checking for a success response
            check_sweep()
            break
            
        case checkSweep[0]:
            // sweeping RF; wait for flag to clear in checkSweepResponse
            waitForResponse(message, "check_sweep", "sweepSuccess", 40, "checkSweepResponse")
            break
        
        case cancelSweep[0]:
            if(checkError()) { return }
            updateDevStatus("cancelled RF sweep")
            break
        
        case rfLearn[0]:
            setVolatileState("iter", 0)
            readback()
            break
    }
}

def sweepSuccess(resp)
{
    def sweepFreq = bytesToInt(subBytes(resp, 1, 3), "little")
    
    def msg = "sweep successful: freq = ${sweepFreq}"
    
    logDebug(msg)
    getDevDetails() << [sweepFreq: sweepFreq]
    
    updateDevStatus(msg)
}

def checkSweepResponse(resp)
{
    // flag is set when sweep was successful
    return (resp?.getAt(0) == 1)
}

//////////////////////////////////////
// RF state machine
//////////////////////////////////////

def causeWaitCancel()
{
    setVolatileState("iter", 0xFFFF)
}

def waitForResponse(message, retryHandler, successHandler, timeout = 10, addlCheckLogic = null)
{
    def resp = prepResponse(message)
    def iter = getVolatileState("iter")
    
    // if we timed out, we're done done
    if(iter >= timeout)
    {
        updateDevStatus("timed out")
        return
    }
    
    try
    {
        // if the transfer had an error
        if(checkError()) { throw new Exception("errored") }
        
        // if our additional check was specified AND failed
        if(null != addlCheckLogic)
        {
            if(!"$addlCheckLogic"(resp)) { throw new Exception("check failed") }
        }
        
        // otherwise, success!        
        "$successHandler"(resp)
        return
    }
    catch(e)
    {
        //logDebug e.message
        
        // try again in 1/2 second
        setVolatileState("iter", iter + 1)
        pauseExecution(500)
        "$retryHandler"()
    }
}

def sendCodeData(code, reps = 1)
{
    if(!checkReady()) { return }
    if(!code)
    {
        logDebug("sendCodeData() error: no code supplied")
        return
    }
    
    byte[] sendPacket = [0x02] + [0] * 3
    
    def cb = HexUtils.hexStringToByteArray(code)    
    sendPacket = appendByteArr(sendPacket, cb)
    
    if(sendPacket[4] == 0x26)
    {
        // FOR IR ONLY (0x26 first byte of command),
        //   code will be sent (n+1) times this value
        reps = reps.toInteger()
        reps--
        sendPacket[5] = (reps < 0) ? 0 : reps
    }
    else
    {
        // if this isn't an IR command AND we don't have RF, bail
        if(!hasRF()) { return }
    }
    
    logDebug("sendCodeData() packet: ${HexUtils.byteArrayToHexString(sendPacket)}")
    
    send_packet(getDevDetails() << [callback: "sendCodeResponse"], cmdControl, sendPacket)
    updateDevStatus("code sent (ts: ${new Date().getTime()})")
}

def sendCodeResponse(message)
{
    prepResponse(message)
}

def generateIR(offBurst, onBurst, leadIn, bitmap, leadOut)
{
    if([offBurst, onBurst, leadIn, bitmap, leadOut].contains(null))
    {
        logDebug("generateIR: invalid code specs")
        return null
    }
    
    def code = ""
    
    // build the code sequence
    code += leadIn
    
    bitmap.each
    { code += ((it == "1") ? onBurst : offBurst) }
    
    code += leadOut
    // code sequence complete
    
    // apply length and build the actual packet    
    def len = HexUtils.hexStringToByteArray(code).size()
    len = HexUtils.byteArrayToHexString([0xFF & len, 0xFF & (len >> 8)] as byte[])    
    code = "2600" + len + code + "0D05"
    
    logDebug("generateIR: offBurst=${offBurst}, onBurst=${onBurst}, leadIn=${leadIn}, bitmap=${bitmap}, leadOut=${leadOut}")
    logDebug("generateIR: code = ${code}")
    sendCodeData(code)
}

//////////////////////////////////////
// saved code operations
//////////////////////////////////////



def sendSavedCode(name, reps = 1)
{
    sendCodeData(getSavedCode(name), reps) 
}

def saveLearnedCode(name)
{
    def entry = addSavedCode(name, device.currentValue("learnedCode"))
    
    sendEvent(name: "savedCode", value: entry)
}

def executeSequence(sequence)
{
    if(!sequence) { return }
    
    if(checkSeqStatus())
    {
        log.error "sequence already executing; try again later"
        return
    }
    
    try
    {
        updateSeqStatus(true)
        
        sequence = sequence?.split("sequence:")?.getAt(1)
        sequence = sequence.tokenize(";")
        
        logDebug("sending sequence: ${sequence}")
        
        sequence.each
        {
            it = it.trim()
            switch(it)
            {
                case ~/.*delay.*/:
                    def delay = it.split("=")?.getAt(1)?.trim()?.toInteger() ?: 0
                
                    // limit to 5s delay
                    delay = (delay > 5000) ? 5000 : delay
                    logDebug("seq delay: ${delay} ms")
                    pauseExecution(delay ?: 0)
                    break
                
                case ~/.*code.*/:
                    def codeName = it.split("=")?.getAt(1)?.trim()
                
                    logDebug("seq code: ${codeName}")
                    sendSavedCode(codeName)
                    // throttle before next code
                    pauseExecution(100)
                
                    break
                
                case ~/.*repeat.*/:
                    def codeName = it.split("repeat")?.getAt(1)?.trim()
                    def params = codeName?.tokenize("=")
                    if(2 == params.size())
                    {
                        logDebug("seq code: ${params[1]?.trim()} reps: ${params[0]?.trim()?.toString()}")
                        sendSavedCode(params[1]?.trim(), params[0]?.trim()?.toInteger())
                        // throttle before next code
                        pauseExecution(100)
                    }
                    break
                
                default:
                    logDebug("sequence bad entry: ${it}")
            }
        }
    }    
    catch(Exception e)
    {
        logDebug("executeSequence: ${e.message}")
    }
    finally
    {
        updateSeqStatus(false)
    }
}

def push(name)
{
    if(name instanceof String)
    {
        logDebug("push: ${name}")
        
        if(name.contains("sequence:"))
        {
            executeSequence(name)            
            return
        }
        
        // if button number was a String, try to send a saved code with that name
        sendSavedCode(name)
    }
}

def SendStoredCode(name, reps = 1)
{
    sendSavedCode(name, reps)
}

def SendCode(code)
{
    sendCodeData(code)
}

def cacheCodesForApp(retain)
{
    if(!retain)
    {
        // delete codes from data area once they are no longer needed
        removeDataValue("codes")
        return
    }
    
    // store copy of codes in data space, so app can get at them
    def codesStr = new groovy.json.JsonOutput().toJson(state.codes)
    updateDataValue("codes", codesStr)
}

//////////////////////////////////////
// myL2 addition: Air Conditioner child
//////////////////////////////////////

def createAcChild()
{
    // create a "Broadlink AC" child device that sends the saved AC* snapshot codes
    def dni = "${device.deviceNetworkId}-AC"

    if(getChildDevice(dni))
    {
        log.info "AC child device already exists"
        return
    }

    addChildDevice("myL2", "Broadlink AC", dni,
        [name: "Broadlink AC", label: "Air Conditioner", isComponent: false])

    log.info "created AC child device (${dni})"
}

//////////////////////////////////////
// saved code operations
//////////////////////////////////////


def clrCount()
{
    state.count = 0
    return 0
}

def incCount()
{
    if(null == state.count)
    {
        return clrCount()
    }
    
    def count = (state.count + 1) & 0xffff
    
    state.count = count
    return count
}

def calcChecksum(byte[] packet)
{
    def cs = 0xBEAF
    packet.each
    {
        cs = (cs + i8Tou8(it)) & 0xFFFF
    }    
    
    return intToBytes(cs, 2, "little")
}

def isNewApi() { return newApi }//state.newApi }

def setNewApi(val) { device.updateSetting("newApi", val) }//state.newApi = true }

def prepPayload(payload, command)
{
    if([cmdAuthReq].contains(command))
    {
        // special commands
        return payload
    }
    
    if([getFwVers[0]].contains(payload[0]))
    {
        // special payloads
        return payload
    }
    
    if(isNewApi())
    {
        // additional bytes at head of payload for "RMMINIB" and derivatives like "RM4MINI" and "RM4PRO"
        def temp = intToBytes(4, 2, "little")
        payload = appendByteArr(temp, payload)
    }
    
    return payload
}

def prepResponse(message, trim = true, useRaw = false)
{
    // allow putting a pcap "hex stream" in as 'message' for decoding
    def lanMsg = parseLanMessage(message)?.payload ?: message    
    //logDebug("parse: ${lanMsg}")
    
    def plb = HexUtils.hexStringToByteArray(lanMsg)    
    // header payload
    //logDebug("command = ${HexUtils.byteArrayToHexString(subBytes(plb, 0, 0x38))}")
    
    updateError(plb)
    byte[] resp = []
    
    if(plb.size() > 0x38)
    {
        // decode payload, starts at 0x38
        plb = subBytes(plb, 0x38, plb.size() - 0x38)
        
        // pad for size, if necessary
        def padding = 16 - (plb.size() % 16)
        plb = appendByteArr(plb, [0] * padding)
        
        resp = aes_cbc(plb, "dec", getDevDetails().authKey, INIT_VECT)        
        if(!resp) { return }
        
        if(isNewApi() && !useRaw)
        {
            resp = subBytes(resp, 2, resp.size() - 2)
        }
        
        if(trim)
        {
            // trim unused content
            resp = subBytes(resp, 4, resp.size() - 4)
        }
    }
    
    // decoded payload
    //logDebug(HexUtils.byteArrayToHexString(resp))    
    return resp
}

def send_packet(devDetails, command, payload)
{
    try
    {
        // https://github.com/mjg59/python-broadlink/blob/master/protocol.md#command-packet-format
    
        byte[] packet = [0] * 56
    
        def magic = HexUtils.hexStringToByteArray("5aa5aa555aa5aa55")
        packet = replaceSubArr(packet, magic, 0)
    
        // apply devType
        packet = replaceSubArr(packet, intToBytes(devDetails.devType, 2, "little"), 0x24)
    
        // apply command
        packet = replaceSubArr(packet, intToBytes(command, 1, "little"), 0x26)
    
        // update and apply count
        packet = replaceSubArr(packet, intToBytes(incCount(), 2, "little"), 0x28)
    
        // apply device MAC
        def mac = reverseBytes(HexUtils.hexStringToByteArray(devDetails.mac))
        packet = replaceSubArr(packet, mac, 0x2A)
    
        // apply device ID
        packet = replaceSubArr(packet, intToBytes(devDetails.id, 4, "little"), 0x30)
    
        // prep payload with device- and command-specific details
        payload = prepPayload(payload, command)
    
        // apply checksum to header
        packet = replaceSubArr(packet, calcChecksum(payload), 0x34)
       
        // encrypt payload
        def padding = 16 - (payload.size() % 16)
        byte[] paddedPayload = appendByteArr(payload, [0] * padding)
        paddedPayload = aes_cbc(paddedPayload, "enc", devDetails.authKey, INIT_VECT)
    
        // compose whole packet (header + encrypted payload)
        byte[] totalPacket = appendByteArr(packet, paddedPayload)
    
        // apply checksum to totalPacket
        totalPacket = replaceSubArr(totalPacket, calcChecksum(totalPacket), 0x20)
    
        def str = HexUtils.byteArrayToHexString(totalPacket)
        //logDebug("${str}")
    
        def params = [type: HubAction.Type.LAN_TYPE_UDPCLIENT, destinationAddress: ipAddress, encoding: HubAction.Encoding.HEX_STRING, callback: devDetails.callback]
    
        def hubAction = new HubAction(str, Protocol.LAN, params)
        sendHubCommand(hubAction)
        
        return true
    }
    catch(Exception e)
    {
        updateDevStatus("send failed")
        logDebug("send_packet failed: check device details")
        //logDebug("message: ${e.message}")
        
        return false
    }
}

def updateError(packet)
{
    def error = bytesToInt(subBytes(packet, 0x22, 2), "little")
    
    setErrors(error)    
    return error    
}

def setErrors(error)
{
    setVolatileState("lastError", error)    
}

def checkError()
{
    def lE = getVolatileState("lastError")
    if(null == lE)
    {
        // if not initialized, assume no error
        return false
    }
    
    return (lE != 0)
}

def parse(message)
{
    def lanMsg = parseLanMessage(message)
    logDebug("parse: ${lanMsg}")
    
    def resp = prepResponse(lanMsg.payload, false)
    if(null == resp) { return }
    
    logDebug("parseResp = ${HexUtils.byteArrayToHexString(resp)}")
}

import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.Cipher

def aes_cbc(data, op, key, iv)
{
    // thanks: https://community.hubitat.com/t/groovy-aes-encryption-driver/31556
    try
    {
        def cipher = Cipher.getInstance("AES/CBC/NoPadding", "SunJCE")
        
        byte[] keyBytes = HexUtils.hexStringToByteArray(key)
        SecretKeySpec aKey = new SecretKeySpec(keyBytes, "AES")
        
        byte[] ivBytes = HexUtils.hexStringToByteArray(iv)
        IvParameterSpec aIv = new IvParameterSpec(ivBytes)
        
        cipher.init(op == "enc" ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, aKey, aIv)
        
        return cipher.doFinal(data) as byte[]
    }
    catch (Exception e)
    {
        log.debug("aes_cbc() exception: ${e}")
    }
    
    return null
}

def appendByteArr(a, b)
{
    byte[] c = new byte[a.size() + b.size()]
    
    a.eachWithIndex()
    {
        it, i ->
        c[i] = it
    }
    
    def aSz = a.size()
    
    b.eachWithIndex()
    {
        it, i ->
        c[i + aSz] = it
    }
    
    return c
}

def replaceSubArr(orig_arr, new_arr, start)
{
    def tmp_arr = orig_arr.collect()
    new_arr.eachWithIndex
    {
        it, i ->
        tmp_arr[i + start] = it
    }
    
    return tmp_arr
}

private subBytes(arr, start, length)
{
    byte[] sub = new byte[length]
    
    for(int i = 0; i < length; i++)
    {
        sub[i] = arr[i + start]
    }
    
    return sub
}

private reverseBytes(arr)
{
    byte[] sub = new byte[arr.size()]
    
    byte end = arr.size() - 1    
    for(int i = 0; i < arr.size(); i++)
    {
        sub[i] = arr[end]
        end--
    }
    
    return sub
}

def swapEndiannessU16(input)
{    
    return [i8Tou8(input[1]), i8Tou8(input[0])]
}

def swapEndiannessU32(input)
{
    return [input[3], input[2], input[1], input[0]]
}

def swapEndiannessU64(input)
{
    return [input[7], input[6], input[5], input[4],
            input[3], input[2], input[1], input[0]]
}

def intToBytes(input, width, endian = "little")
{
    def output = new BigInteger(input).toByteArray()
   
    if(output.size() > width)
    {
        // if we got too many bytes, lop off the MSB(s)
        output = subBytes(output, output.size() - width, width)
        output = output.collect{it & 0xFF}
    }
    
    byte[] pad    
   
    if(output.size() < width)
    {
        def padding = width - output.size()
        pad = [0] * padding
        output = appendByteArr(pad, output)        
    }
    
    if("little" == endian)
    {
        switch(width)
        {
            case 1:
                break
            case 2:
                output = swapEndiannessU16(output)
                break
            case 4:
                output = swapEndiannessU32(output)
                break
            case 8:
                output = swapEndiannessU64(output)
                break
        }
    }
    
    return output.collect{it & 0xFF}
}

def bytesToInt(input, endian = "little")
{
    def output = subBytes(input, 0, input.size())
    
    long retVal = 0
    output.eachWithIndex
    {
        it, i ->
        
        switch(endian)
        {
            case "little":
                retVal += ((it & 0xFF).toLong() << (i * 8))
                break
            case "big":
            default:
                retVal += (it & 0xFF).toLong() << ((output.size() - 1 - i) * 8)
                break
        }
    }
    
    if(input.size() == 8)
    {
        // 8 bytes is too big for integer
        return retVal
    }
    
    return retVal as Integer
}

def i8Tou8(input)
{
    return input & 0xFF
}

def i16Tou16(input)
{
    return input & 0xFFFF
}

// ~~~~~ start include (1) tomw.broadlinkHelpers ~~~~~
/* // library marker tomw.broadlinkHelpers, line 1

Copyright 2022 - tomw // library marker tomw.broadlinkHelpers, line 3

Licensed under the Apache License, Version 2.0 (the "License"); // library marker tomw.broadlinkHelpers, line 5
you may not use this file except in compliance with the License. // library marker tomw.broadlinkHelpers, line 6
You may obtain a copy of the License at // library marker tomw.broadlinkHelpers, line 7

    http://www.apache.org/licenses/LICENSE-2.0 // library marker tomw.broadlinkHelpers, line 9

Unless required by applicable law or agreed to in writing, software // library marker tomw.broadlinkHelpers, line 11
distributed under the License is distributed on an "AS IS" BASIS, // library marker tomw.broadlinkHelpers, line 12
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. // library marker tomw.broadlinkHelpers, line 13
See the License for the specific language governing permissions and // library marker tomw.broadlinkHelpers, line 14
limitations under the License. // library marker tomw.broadlinkHelpers, line 15

------------------------------------------- // library marker tomw.broadlinkHelpers, line 17

Change history: // library marker tomw.broadlinkHelpers, line 19

0.9.10 - tomw - Added lirc code import helpers. // library marker tomw.broadlinkHelpers, line 21
0.9.3  - tomw - Rework app UI to use buttons. // library marker tomw.broadlinkHelpers, line 22
0.9.2  - tomw - Rename codes (supported in app). // library marker tomw.broadlinkHelpers, line 23
0.9.0  - tomw - Initial release. // library marker tomw.broadlinkHelpers, line 24

*/ // library marker tomw.broadlinkHelpers, line 26

library ( // library marker tomw.broadlinkHelpers, line 28
    author: "tomw", // library marker tomw.broadlinkHelpers, line 29
    category: "", // library marker tomw.broadlinkHelpers, line 30
    description: "Broadlink helpers", // library marker tomw.broadlinkHelpers, line 31
    name: "broadlinkHelpers", // library marker tomw.broadlinkHelpers, line 32
    namespace: "tomw", // library marker tomw.broadlinkHelpers, line 33
    documentationLink: "", // library marker tomw.broadlinkHelpers, line 34
    importUrl: "https://raw.githubusercontent.com/tomwpublic/hubitat_broadlink/main/libraries/broadlinkHelpers" // library marker tomw.broadlinkHelpers, line 35
) // library marker tomw.broadlinkHelpers, line 36

import groovy.transform.Field // library marker tomw.broadlinkHelpers, line 38
import hubitat.helper.HexUtils // library marker tomw.broadlinkHelpers, line 39


//////////////////////////// // library marker tomw.broadlinkHelpers, line 42
// saved codes operations // library marker tomw.broadlinkHelpers, line 43
//////////////////////////// // library marker tomw.broadlinkHelpers, line 44

@Field String codesSync = "" // library marker tomw.broadlinkHelpers, line 46

def deleteSavedCode(name) // library marker tomw.broadlinkHelpers, line 48
{ // library marker tomw.broadlinkHelpers, line 49
    def res // library marker tomw.broadlinkHelpers, line 50
    synchronized(codesSync) // library marker tomw.broadlinkHelpers, line 51
    { // library marker tomw.broadlinkHelpers, line 52
        res = state.codes?.remove(name) // library marker tomw.broadlinkHelpers, line 53
    } // library marker tomw.broadlinkHelpers, line 54

    if(res) // library marker tomw.broadlinkHelpers, line 56
    { // library marker tomw.broadlinkHelpers, line 57
        log.info "removed code: ${name}" // library marker tomw.broadlinkHelpers, line 58
        return true         // library marker tomw.broadlinkHelpers, line 59
    } // library marker tomw.broadlinkHelpers, line 60
    else // library marker tomw.broadlinkHelpers, line 61
    { // library marker tomw.broadlinkHelpers, line 62
        log.info "code not found: ${name}" // library marker tomw.broadlinkHelpers, line 63
        return false // library marker tomw.broadlinkHelpers, line 64
    } // library marker tomw.broadlinkHelpers, line 65
} // library marker tomw.broadlinkHelpers, line 66

def addSavedCode(name, code) // library marker tomw.broadlinkHelpers, line 68
{ // library marker tomw.broadlinkHelpers, line 69
    if(null == state.codes) { clearSavedCodes() } // library marker tomw.broadlinkHelpers, line 70

    Map codeEntry = [(name): code] // library marker tomw.broadlinkHelpers, line 72
    logDebug("adding code: ${codeEntry}") // library marker tomw.broadlinkHelpers, line 73

    synchronized(codesSync) // library marker tomw.broadlinkHelpers, line 75
    { // library marker tomw.broadlinkHelpers, line 76
        state.codes << codeEntry // library marker tomw.broadlinkHelpers, line 77
    } // library marker tomw.broadlinkHelpers, line 78

    return codeEntry // library marker tomw.broadlinkHelpers, line 80
} // library marker tomw.broadlinkHelpers, line 81

def getSavedCode(name) // library marker tomw.broadlinkHelpers, line 83
{ // library marker tomw.broadlinkHelpers, line 84
    codes = state.codes // library marker tomw.broadlinkHelpers, line 85

    // try to get at user-specific name first, // library marker tomw.broadlinkHelpers, line 87
    //   then check against modified naming style used by old integration as a last resort // library marker tomw.broadlinkHelpers, line 88
    code = codes?.get(name) ?: codes?.get(name?.tokenize(" ,!@#\$%^&()")?.join("_")) // library marker tomw.broadlinkHelpers, line 89

    if(null == code) // library marker tomw.broadlinkHelpers, line 91
    { // library marker tomw.broadlinkHelpers, line 92
        log.info "code not found: ${name}" // library marker tomw.broadlinkHelpers, line 93
    } // library marker tomw.broadlinkHelpers, line 94

    return code // library marker tomw.broadlinkHelpers, line 96
} // library marker tomw.broadlinkHelpers, line 97

def renameSavedCode(oldName, newName) // library marker tomw.broadlinkHelpers, line 99
{ // library marker tomw.broadlinkHelpers, line 100
    if([oldName, newName].contains(null)) { return } // library marker tomw.broadlinkHelpers, line 101

    def code = getSavedCode(oldName)     // library marker tomw.broadlinkHelpers, line 103
    if(!code) { return } // library marker tomw.broadlinkHelpers, line 104

    if(deleteSavedCode(oldName)) // library marker tomw.broadlinkHelpers, line 106
    { // library marker tomw.broadlinkHelpers, line 107
        return addSavedCode(newName, code) // library marker tomw.broadlinkHelpers, line 108
    } // library marker tomw.broadlinkHelpers, line 109
} // library marker tomw.broadlinkHelpers, line 110

def clearSavedCodes() // library marker tomw.broadlinkHelpers, line 112
{ // library marker tomw.broadlinkHelpers, line 113
    synchronized(codesSync) // library marker tomw.broadlinkHelpers, line 114
    { // library marker tomw.broadlinkHelpers, line 115
        state.codes = [:] // library marker tomw.broadlinkHelpers, line 116
    } // library marker tomw.broadlinkHelpers, line 117
} // library marker tomw.broadlinkHelpers, line 118

def importCodes(String codeStore, String overwrite) // library marker tomw.broadlinkHelpers, line 120
{ // library marker tomw.broadlinkHelpers, line 121
    // use codeStore from previous integration, using "name1=code1,name2=code2" // library marker tomw.broadlinkHelpers, line 122
    //   Hubitat formatting in State Variables, with or without {} // library marker tomw.broadlinkHelpers, line 123
    codeStore = codeStore?.replace('{','')?.replace('}','') // library marker tomw.broadlinkHelpers, line 124
    if(overwrite == 'yes'){ // library marker tomw.broadlinkHelpers, line 125
        state.codes = [:] // library marker tomw.broadlinkHelpers, line 126
    } // library marker tomw.broadlinkHelpers, line 127
    // turn it into a map... // library marker tomw.broadlinkHelpers, line 128
    codeStore?.split(',')?.each // library marker tomw.broadlinkHelpers, line 129
    { // library marker tomw.broadlinkHelpers, line 130
        def entry = it.split(':') // library marker tomw.broadlinkHelpers, line 131
        if(entry.size() == 2) // library marker tomw.broadlinkHelpers, line 132
        { // library marker tomw.broadlinkHelpers, line 133
            // ...and import each one // library marker tomw.broadlinkHelpers, line 134
            addSavedCode(entry[0].toString().replace('"','').trim(), entry[1].toString().replace('"','').trim()) // library marker tomw.broadlinkHelpers, line 135
        } // library marker tomw.broadlinkHelpers, line 136
    } // library marker tomw.broadlinkHelpers, line 137
} // library marker tomw.broadlinkHelpers, line 138

def allKnownCodesMap() // library marker tomw.broadlinkHelpers, line 140
{ // library marker tomw.broadlinkHelpers, line 141
    return state.codes ?: [:] // library marker tomw.broadlinkHelpers, line 142
} // library marker tomw.broadlinkHelpers, line 143

def allKnownCodesKeys() // library marker tomw.broadlinkHelpers, line 145
{ // library marker tomw.broadlinkHelpers, line 146
    return allKnownCodesMap()?.keySet()?.sort() // library marker tomw.broadlinkHelpers, line 147
} // library marker tomw.broadlinkHelpers, line 148

//////////////////////////// // library marker tomw.broadlinkHelpers, line 150
// import codes operations (pronto, lirc) // library marker tomw.broadlinkHelpers, line 151
//////////////////////////// // library marker tomw.broadlinkHelpers, line 152

String prepCodeStr(rawCode) // library marker tomw.broadlinkHelpers, line 154
{ // library marker tomw.broadlinkHelpers, line 155
    // build the full Broadlink code string from the raw IR sequence // library marker tomw.broadlinkHelpers, line 156

    def strCode // library marker tomw.broadlinkHelpers, line 158

    // length of raw conversion (no header, no leadout) // library marker tomw.broadlinkHelpers, line 160
    def len = HexUtils.hexStringToByteArray(rawCode).size() // library marker tomw.broadlinkHelpers, line 161
    len = HexUtils.byteArrayToHexString([0xFF & len, 0xFF & (len >> 8)] as byte[]) // library marker tomw.broadlinkHelpers, line 162

    strCode = "2600" + len + rawCode // library marker tomw.broadlinkHelpers, line 164

    // IR leadout (standard) // library marker tomw.broadlinkHelpers, line 166
    strCode += HexUtils.byteArrayToHexString([0x0d, 0x05] as byte[]) // library marker tomw.broadlinkHelpers, line 167

    return strCode // library marker tomw.broadlinkHelpers, line 169
} // library marker tomw.broadlinkHelpers, line 170

String encodeValToCode(code, mult) // library marker tomw.broadlinkHelpers, line 172
{ // library marker tomw.broadlinkHelpers, line 173
    // encode each code byte into the Broadlink scheme based on its value // library marker tomw.broadlinkHelpers, line 174
    code = (code * mult).toInteger() // library marker tomw.broadlinkHelpers, line 175

    byte[] tmpCode // library marker tomw.broadlinkHelpers, line 177
    if(code <= 0xFF) // library marker tomw.broadlinkHelpers, line 178
    { // library marker tomw.broadlinkHelpers, line 179
        tmpCode = [code]  // library marker tomw.broadlinkHelpers, line 180
    } // library marker tomw.broadlinkHelpers, line 181
    else // library marker tomw.broadlinkHelpers, line 182
    { // library marker tomw.broadlinkHelpers, line 183
        // leading zero, then big-endian if larger than one byte // library marker tomw.broadlinkHelpers, line 184
        tmpCode = [0, 0xFF & (code >> 8), 0xFF & code] // library marker tomw.broadlinkHelpers, line 185
    } // library marker tomw.broadlinkHelpers, line 186

    return HexUtils.byteArrayToHexString(tmpCode)     // library marker tomw.broadlinkHelpers, line 188
} // library marker tomw.broadlinkHelpers, line 189

def convertProntoToBroadlink(pronto) // library marker tomw.broadlinkHelpers, line 191
{ // library marker tomw.broadlinkHelpers, line 192
    def codes = parseProntoStringToCodes(pronto) // library marker tomw.broadlinkHelpers, line 193

    if(!codes || (codes == [])) // library marker tomw.broadlinkHelpers, line 195
    { // library marker tomw.broadlinkHelpers, line 196
        logDebug("pronto error: invalid bytes") // library marker tomw.broadlinkHelpers, line 197
    } // library marker tomw.broadlinkHelpers, line 198

    if(validateProntoCodes(codes)) // library marker tomw.broadlinkHelpers, line 200
    { // library marker tomw.broadlinkHelpers, line 201
        return buildBroadlinkFromProntoCodes(codes)         // library marker tomw.broadlinkHelpers, line 202
    } // library marker tomw.broadlinkHelpers, line 203
} // library marker tomw.broadlinkHelpers, line 204

def buildBroadlinkFromProntoCodes(codes) // library marker tomw.broadlinkHelpers, line 206
{ // library marker tomw.broadlinkHelpers, line 207
    // http://www.remotecentral.com/features/irdisp2.htm // library marker tomw.broadlinkHelpers, line 208
    // https://github.com/mjg59/python-broadlink/blob/master/protocol.md // library marker tomw.broadlinkHelpers, line 209

    // freq in MHz // library marker tomw.broadlinkHelpers, line 211
    def freq = 1 / (codes[1] * 0.241246) // library marker tomw.broadlinkHelpers, line 212
    logDebug("freq = ${freq}") // library marker tomw.broadlinkHelpers, line 213

    // here we are multiplying by the period of the specified signal // library marker tomw.broadlinkHelpers, line 215
    def mult = (269 / 8192) / freq // library marker tomw.broadlinkHelpers, line 216
    logDebug("pulse mult = ${mult}") // library marker tomw.broadlinkHelpers, line 217

    def pairsCnt1 = codes[2] // library marker tomw.broadlinkHelpers, line 219
    def pairsCnt2 = codes[3] // library marker tomw.broadlinkHelpers, line 220

    def strCode = "" // library marker tomw.broadlinkHelpers, line 222

    for(i = 4; i < 2*(pairsCnt1 + pairsCnt2) + 4; i++) // library marker tomw.broadlinkHelpers, line 224
    { // library marker tomw.broadlinkHelpers, line 225
        strCode += encodeValToCode(codes[i], mult) // library marker tomw.broadlinkHelpers, line 226
    } // library marker tomw.broadlinkHelpers, line 227

    logDebug("raw pronto conversion: ${strCode}") // library marker tomw.broadlinkHelpers, line 229

    return prepCodeStr(strCode) // library marker tomw.broadlinkHelpers, line 231
} // library marker tomw.broadlinkHelpers, line 232

def parseProntoStringToCodes(pronto) // library marker tomw.broadlinkHelpers, line 234
{ // library marker tomw.broadlinkHelpers, line 235
    if(!pronto || (pronto == "")) { return } // library marker tomw.broadlinkHelpers, line 236

    def code = pronto.replace(" ", "") // library marker tomw.broadlinkHelpers, line 238
    byte[] bCode = HexUtils.hexStringToByteArray(code) // library marker tomw.broadlinkHelpers, line 239

    def codes = [] // library marker tomw.broadlinkHelpers, line 241
    for(i = 0; i < bCode.size(); i = i + 2) // library marker tomw.broadlinkHelpers, line 242
    { // library marker tomw.broadlinkHelpers, line 243
        // repack this into 2-byte codes // library marker tomw.broadlinkHelpers, line 244
        codes += ((bCode[i] & 0xFF) << 8) | (bCode[i+1] & 0xFF) // library marker tomw.broadlinkHelpers, line 245
    } // library marker tomw.broadlinkHelpers, line 246

    return codes // library marker tomw.broadlinkHelpers, line 248
} // library marker tomw.broadlinkHelpers, line 249

def validateProntoCodes(codes) // library marker tomw.broadlinkHelpers, line 251
{ // library marker tomw.broadlinkHelpers, line 252
    if(0 != codes[0] || 0 == codes[1]) // library marker tomw.broadlinkHelpers, line 253
    { // library marker tomw.broadlinkHelpers, line 254
        // first word must be 0x0000 // library marker tomw.broadlinkHelpers, line 255
        // second word is frequency and should be non-zero // library marker tomw.broadlinkHelpers, line 256

        logDebug("pronto error: invalid preamble") // library marker tomw.broadlinkHelpers, line 258
        return // library marker tomw.broadlinkHelpers, line 259
    } // library marker tomw.broadlinkHelpers, line 260

    def pairsCnt1 = codes[2] // library marker tomw.broadlinkHelpers, line 262
    def pairsCnt2 = codes[3] // library marker tomw.broadlinkHelpers, line 263

    if((codes.size() - 4) != (2 * (pairsCnt1 + pairsCnt2))) // library marker tomw.broadlinkHelpers, line 265
    { // library marker tomw.broadlinkHelpers, line 266
        // check remaining data against expected numbers of *pairs* // library marker tomw.broadlinkHelpers, line 267

        logDebug("pronto error: invalid size") // library marker tomw.broadlinkHelpers, line 269
        return // library marker tomw.broadlinkHelpers, line 270
    } // library marker tomw.broadlinkHelpers, line 271

    return [pairsCnt1: pairsCnt1, pairsCnt2: pairsCnt2] // library marker tomw.broadlinkHelpers, line 273
} // library marker tomw.broadlinkHelpers, line 274

def generateCodesFromLircBits(numBits, codeVal, lircSpec) // library marker tomw.broadlinkHelpers, line 276
{ // library marker tomw.broadlinkHelpers, line 277
    def strCode = "" // library marker tomw.broadlinkHelpers, line 278

    // here we are multiplying by actual times (in us) // library marker tomw.broadlinkHelpers, line 280
    def mult = (269 / 8192) // library marker tomw.broadlinkHelpers, line 281

    for(int i = numBits - 1; i >= 0; i--) // library marker tomw.broadlinkHelpers, line 283
    { // library marker tomw.broadlinkHelpers, line 284
        if((codeVal >> i) & 0x01) // library marker tomw.broadlinkHelpers, line 285
        { // library marker tomw.broadlinkHelpers, line 286
            strCode += encodeValToCode(lircSpec.one.high, mult) // library marker tomw.broadlinkHelpers, line 287
            strCode += encodeValToCode(lircSpec.one.low, mult) // library marker tomw.broadlinkHelpers, line 288
        } // library marker tomw.broadlinkHelpers, line 289
        else // library marker tomw.broadlinkHelpers, line 290
        { // library marker tomw.broadlinkHelpers, line 291
            strCode += encodeValToCode(lircSpec.zero.high, mult) // library marker tomw.broadlinkHelpers, line 292
            strCode += encodeValToCode(lircSpec.zero.low, mult) // library marker tomw.broadlinkHelpers, line 293
        } // library marker tomw.broadlinkHelpers, line 294
    } // library marker tomw.broadlinkHelpers, line 295

    return strCode // library marker tomw.broadlinkHelpers, line 297
} // library marker tomw.broadlinkHelpers, line 298

def buildBroadlinkFromLircCode(Map lircSpec) // library marker tomw.broadlinkHelpers, line 300
{ // library marker tomw.broadlinkHelpers, line 301
    if([lircSpec, lircSpec.code, lircSpec.bits].contains(null)) // library marker tomw.broadlinkHelpers, line 302
    { // library marker tomw.broadlinkHelpers, line 303
        logDebug("lirc error: invalid spec") // library marker tomw.broadlinkHelpers, line 304
        return         // library marker tomw.broadlinkHelpers, line 305
    } // library marker tomw.broadlinkHelpers, line 306

    def strCode = "" // library marker tomw.broadlinkHelpers, line 308

    // here we are multiplying by actual times (in us) // library marker tomw.broadlinkHelpers, line 310
    def mult = (269 / 8192) // library marker tomw.broadlinkHelpers, line 311

    if(lircSpec.header) // library marker tomw.broadlinkHelpers, line 313
    { // library marker tomw.broadlinkHelpers, line 314
        // any header data, if present // library marker tomw.broadlinkHelpers, line 315
        strCode += encodeValToCode(lircSpec.header.high, mult) // library marker tomw.broadlinkHelpers, line 316
        strCode += encodeValToCode(lircSpec.header.low, mult) // library marker tomw.broadlinkHelpers, line 317
    } // library marker tomw.broadlinkHelpers, line 318

    if(lircSpec.pre_data && lircSpec.pre_data_bits) // library marker tomw.broadlinkHelpers, line 320
    { // library marker tomw.broadlinkHelpers, line 321
        // any pre_data, if present // library marker tomw.broadlinkHelpers, line 322
        strCode += generateCodesFromLircBits(lircSpec.pre_data_bits, lircSpec.pre_data, lircSpec) // library marker tomw.broadlinkHelpers, line 323
    } // library marker tomw.broadlinkHelpers, line 324

    // the actual code // library marker tomw.broadlinkHelpers, line 326
    strCode += generateCodesFromLircBits(lircSpec.bits, lircSpec.code, lircSpec) // library marker tomw.broadlinkHelpers, line 327

    if(lircSpec.post_data && lircSpec.post_data_bits) // library marker tomw.broadlinkHelpers, line 329
    { // library marker tomw.broadlinkHelpers, line 330
        // any post_data, if present // library marker tomw.broadlinkHelpers, line 331
        strCode += generateCodesFromLircBits(lircSpec.post_data_bits, lircSpec.post_data, lircSpec) // library marker tomw.broadlinkHelpers, line 332
    } // library marker tomw.broadlinkHelpers, line 333

    if(lircSpec.ptrail) // library marker tomw.broadlinkHelpers, line 335
    { // library marker tomw.broadlinkHelpers, line 336
        // any ptrail, if present // library marker tomw.broadlinkHelpers, line 337
        strCode += encodeValToCode(lircSpec.ptrail, mult)         // library marker tomw.broadlinkHelpers, line 338
        // low value for ptrail is never(?) present, so just use the low value for a one // library marker tomw.broadlinkHelpers, line 339
        strCode += encodeValToCode(lircSpec.one.low, mult) // library marker tomw.broadlinkHelpers, line 340
    } // library marker tomw.broadlinkHelpers, line 341

    logDebug("raw lirc conversion: ${strCode}") // library marker tomw.broadlinkHelpers, line 343

    return prepCodeStr(strCode) // library marker tomw.broadlinkHelpers, line 345
} // library marker tomw.broadlinkHelpers, line 346

private _lircGetIntValsByName(input, name, countOfVals) // library marker tomw.broadlinkHelpers, line 348
{ // library marker tomw.broadlinkHelpers, line 349
    def splitInput = input?.split()     // library marker tomw.broadlinkHelpers, line 350
    def idxName = splitInput.findIndexOf { it.toLowerCase() == name } // library marker tomw.broadlinkHelpers, line 351

    if(-1 == idxName) { return } // library marker tomw.broadlinkHelpers, line 353

    List retList = []     // library marker tomw.broadlinkHelpers, line 355
    for(int i = idxName + 1; i <= (idxName + countOfVals); i++) // library marker tomw.broadlinkHelpers, line 356
    { // library marker tomw.broadlinkHelpers, line 357
        thisVal = splitInput[i] // library marker tomw.broadlinkHelpers, line 358
        thisVal = thisVal.startsWith("0x") ? _turn0xStringIntoInt(thisVal) : thisVal // library marker tomw.broadlinkHelpers, line 359

        retList += thisVal?.toInteger() // library marker tomw.broadlinkHelpers, line 361
    } // library marker tomw.broadlinkHelpers, line 362

    return retList     // library marker tomw.broadlinkHelpers, line 364
} // library marker tomw.broadlinkHelpers, line 365

private _lircGetStringValByName(input, name) // library marker tomw.broadlinkHelpers, line 367
{ // library marker tomw.broadlinkHelpers, line 368
    def splitInput = input?.split()     // library marker tomw.broadlinkHelpers, line 369
    def idxName = splitInput.findIndexOf { it.toLowerCase() == name } // library marker tomw.broadlinkHelpers, line 370

    if(-1 == idxName) { return } // library marker tomw.broadlinkHelpers, line 372

    return splitInput[idxName + 1] // library marker tomw.broadlinkHelpers, line 374
} // library marker tomw.broadlinkHelpers, line 375

def _turn0xStringIntoInt(hexString) // library marker tomw.broadlinkHelpers, line 377
{ // library marker tomw.broadlinkHelpers, line 378
    if(!hexString) { return } // library marker tomw.broadlinkHelpers, line 379

    // TODO: this will probably break for codes larger than 32 bits? // library marker tomw.broadlinkHelpers, line 381
    return new BigInteger(hexString?.replace("0x", ""), 16)?.toInteger() // library marker tomw.broadlinkHelpers, line 382
} // library marker tomw.broadlinkHelpers, line 383

def lircParseFile(input) // library marker tomw.broadlinkHelpers, line 385
{ // library marker tomw.broadlinkHelpers, line 386
    // remove comment lines and empty lines (into cleanedInput) // library marker tomw.broadlinkHelpers, line 387
    def cleanedInput = "" // library marker tomw.broadlinkHelpers, line 388
    input?.eachLine // library marker tomw.broadlinkHelpers, line 389
    { line -> // library marker tomw.broadlinkHelpers, line 390
        if(line && !line?.trim()?.startsWith('#')) { cleanedInput += (line + "\n") } // library marker tomw.broadlinkHelpers, line 391
    } // library marker tomw.broadlinkHelpers, line 392

    logDebug("cleanedInput file follows:") // library marker tomw.broadlinkHelpers, line 394
    logDebug(cleanedInput) // library marker tomw.broadlinkHelpers, line 395

    input = cleanedInput // library marker tomw.broadlinkHelpers, line 397


    // first, extract the basic lirc details... // library marker tomw.broadlinkHelpers, line 400

    def one = _lircGetIntValsByName(input, "one", 2) // library marker tomw.broadlinkHelpers, line 402
    def zero = _lircGetIntValsByName(input, "zero", 2) // library marker tomw.broadlinkHelpers, line 403

    def header = _lircGetIntValsByName(input, "header", 2)     // library marker tomw.broadlinkHelpers, line 405
    // header may not be in source file, so process it here first // library marker tomw.broadlinkHelpers, line 406
    if(header) { header = [high: header[0], low: header[1]] } // library marker tomw.broadlinkHelpers, line 407

    def lircSpec = // library marker tomw.broadlinkHelpers, line 409
        [ // library marker tomw.broadlinkHelpers, line 410
            name: _lircGetStringValByName(input, "name"), // library marker tomw.broadlinkHelpers, line 411
            bits: _lircGetIntValsByName(input, "bits", 1)?.get(0), // library marker tomw.broadlinkHelpers, line 412
            header: header, // library marker tomw.broadlinkHelpers, line 413
            one: [high: one[0], low: one[1]], // library marker tomw.broadlinkHelpers, line 414
            zero: [high: zero[0], low: zero[1]], // library marker tomw.broadlinkHelpers, line 415
            pre_data: _lircGetIntValsByName(input, "pre_data", 1)?.get(0), // library marker tomw.broadlinkHelpers, line 416
            pre_data_bits: _lircGetIntValsByName(input, "pre_data_bits", 1)?.get(0), // library marker tomw.broadlinkHelpers, line 417
            post_data: _lircGetIntValsByName(input, "post_data", 1)?.get(0), // library marker tomw.broadlinkHelpers, line 418
            post_data_bits: _lircGetIntValsByName(input, "post_data_bits", 1)?.get(0), // library marker tomw.broadlinkHelpers, line 419
            ptrail: _lircGetIntValsByName(input, "ptrail", 1)?.get(0) // library marker tomw.broadlinkHelpers, line 420
        ] // library marker tomw.broadlinkHelpers, line 421

    // Get the text between 'begin codes' and 'end codes' // library marker tomw.broadlinkHelpers, line 423
    // Note: this will break if there is more than one such section in a lirc file // library marker tomw.broadlinkHelpers, line 424
    def codesInput = input.split('begin codes')?.getAt(1)?.split('end codes') // library marker tomw.broadlinkHelpers, line 425

    logDebug("codesInput section follows:") // library marker tomw.broadlinkHelpers, line 427
    logDebug(codesInput)     // library marker tomw.broadlinkHelpers, line 428

    // ...then, extract each code for processing // library marker tomw.broadlinkHelpers, line 430

    def codeSpecs = [] // library marker tomw.broadlinkHelpers, line 432
    codesInput?.getAt(0)?.eachLine // library marker tomw.broadlinkHelpers, line 433
    { line -> // library marker tomw.broadlinkHelpers, line 434
        line = line.split() // library marker tomw.broadlinkHelpers, line 435
        if(line.size() >= 2) // library marker tomw.broadlinkHelpers, line 436
        { // library marker tomw.broadlinkHelpers, line 437
            // prepend remote name to code name, if present // library marker tomw.broadlinkHelpers, line 438
            codeSpecs += [name: (lircSpec.name ? "${lircSpec.name}_" : "") + line[0], code: _turn0xStringIntoInt(line[1])] // library marker tomw.broadlinkHelpers, line 439
        } // library marker tomw.broadlinkHelpers, line 440
    } // library marker tomw.broadlinkHelpers, line 441

    def finalSpec = [lircSpec: lircSpec, codeSpecs: codeSpecs] // library marker tomw.broadlinkHelpers, line 443
    logDebug("finalSpec follows:") // library marker tomw.broadlinkHelpers, line 444
    logDebug(finalSpec) // library marker tomw.broadlinkHelpers, line 445

    return finalSpec // library marker tomw.broadlinkHelpers, line 447
} // library marker tomw.broadlinkHelpers, line 448

////////////////////////////////////// // library marker tomw.broadlinkHelpers, line 450
// volatile state // library marker tomw.broadlinkHelpers, line 451
////////////////////////////////////// // library marker tomw.broadlinkHelpers, line 452

@Field static volatileState = [:].asSynchronized() // library marker tomw.broadlinkHelpers, line 454

def vsUid() // library marker tomw.broadlinkHelpers, line 456
{ // library marker tomw.broadlinkHelpers, line 457
    return device?.getDeviceNetworkId() ?: app.getId() // library marker tomw.broadlinkHelpers, line 458
} // library marker tomw.broadlinkHelpers, line 459

def setVolatileState(name, value) // library marker tomw.broadlinkHelpers, line 461
{ // library marker tomw.broadlinkHelpers, line 462
    def tempState = volatileState[vsUid()] ?: [:] // library marker tomw.broadlinkHelpers, line 463
    tempState.putAt(name, value) // library marker tomw.broadlinkHelpers, line 464

    volatileState.putAt(vsUid(), tempState) // library marker tomw.broadlinkHelpers, line 466

    return volatileState // library marker tomw.broadlinkHelpers, line 468
} // library marker tomw.broadlinkHelpers, line 469

def getVolatileState(name) // library marker tomw.broadlinkHelpers, line 471
{ // library marker tomw.broadlinkHelpers, line 472
    return volatileState.getAt(vsUid())?.getAt(name) // library marker tomw.broadlinkHelpers, line 473
} // library marker tomw.broadlinkHelpers, line 474

// ~~~~~ end include (1) tomw.broadlinkHelpers ~~~~~
