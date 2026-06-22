/*

Broadlink AC - child device for the "Broadlink Remote" driver (by tomw)

Controls a Samsung air conditioner through a Broadlink IR blaster.

These were identified as standard Samsung-AC IR codes (protocol per the
IRremoteESP8266 project). Instead of relying only on the handful of captured
snapshots, this driver SYNTHESISES the IR signal for any state:

  - power on/off
  - mode: cool / heat / auto
  - temperature: 16..30 C
  - fan: auto / low / med / high / turbo
  - swing: on / off
  - eco (Econo): on / off

Samsung framing (verified by regenerating every captured code byte-for-byte):
  message = header(7B) + extended-marker(7B) + settings(7B), LSB-first bits,
  per-section checksum = ~(popcount of data bits) & 0xFF, split across two
  nibbles (byte1 high nibble = low nibble of checksum, byte2 low nibble = high).
  Settings section bytes: [01][cksumLo|2][swing|cksumHi][eco][temp][mode/fan][pwr]
    temp  = (T-16)<<4 ;  modeFan = (mode<<4)|(fan<<1)|1 ;  swing A=on/F=off
    eco   = 0x7F on / 0x71 off ;  pwr = 0xF0 on / 0xC0 off

The original captured snapshots are kept as one-tap "preset" buttons (they send
the exact learned code via the parent), since those are handy comfort presets.

*/

import groovy.transform.Field

// Samsung field encodings
@Field static final Map SS_MODE = [cool:1, heat:4, auto:0, dry:2, fan:3]
@Field static final Map SS_FAN  = [auto:0, low:2, med:4, high:5, turbo:7]

// Samsung IR timing (microseconds)
@Field static final int HDR_MARK = 690
@Field static final int HDR_SPACE = 17844
@Field static final int SEC_MARK = 3086
@Field static final int SEC_SPACE = 8864
@Field static final int BIT_MARK = 586
@Field static final int ONE_SPACE = 1432
@Field static final int ZERO_SPACE = 436
@Field static final int SEC_GAP = 2886

// captured snapshots kept as comfort presets -> [captured code name : display spec]
@Field static final Map presetSpecs =
    [
        AC23OnA:    [temp: 23, swing: "on",  fan: "auto",  eco: false],
        AC23OffA:   [temp: 23, swing: "off", fan: "auto",  eco: false],
        AC24OnEco:  [temp: 24, swing: "on",  fan: "low",   eco: true ],
        AC24OffEco: [temp: 24, swing: "off", fan: "low",   eco: true ],
        AC24On1:    [temp: 24, swing: "on",  fan: "low",   eco: false],
        AC24Off1:   [temp: 24, swing: "off", fan: "low",   eco: false],
        AC24Off2:   [temp: 24, swing: "off", fan: "med",   eco: false],
        AC24Off3:   [temp: 24, swing: "off", fan: "high",  eco: false],
        AC24Off4:   [temp: 24, swing: "off", fan: "turbo", eco: false],
        AC24OffA:   [temp: 24, swing: "off", fan: "auto",  eco: false]
    ]

// preset -> Easy Dashboard momentary-button label (subset exposed as child switches)
@Field static final Map presetButtons = [AC24OnEco: "AC Eco", AC24Off1: "AC Low", AC24OffA: "AC Auto"]

metadata
{
    definition(name: "Broadlink AC", namespace: "myL2", author: "myL2")
    {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "Refresh"
        capability "Initialize"             // Initialize button (+ re-init on reboot)
        capability "TemperatureMeasurement"  // needed for Easy Dashboard Thermostat tile
        capability "Thermostat"              // Easy Dashboard Thermostat tile

        attribute "swing", "string"     // on / off
        attribute "eco", "string"       // on / off
        attribute "acFan", "string"     // auto / low / med / high / turbo
        attribute "acState", "string"   // human-readable summary
        attribute "lastCode", "string"  // last action (preset name or "synth:...")

        // full synthesised control
        command "setSetpoint", [[name: "temp*", type: "NUMBER", description: "16-30 C"]]
        command "setFan",      [[name: "fan*", type: "ENUM", constraints: ["auto", "low", "med", "high", "turbo"]]]
        command "setSwing",    [[name: "swing*", type: "ENUM", constraints: ["on", "off"]]]
        command "setEco",      [[name: "eco*", type: "ENUM", constraints: ["on", "off"]]]
        command "setRoomTemperature", [[name: "temp*", type: "NUMBER", description: "actual room temp (feed from a sensor via a rule/app)"]]
        command "awayEco"      // go to AC24OnEco without overwriting the remembered last state
        command "resend"       // re-transmit the current state

        command "createPresetButtons"  // create momentary child switches for Easy Dashboard
        command "removePresetButtons"

        // one-tap comfort presets (send the exact captured code).
        // Named "Ac..." so Hubitat's label humanizer doesn't split "AC" into "A C".
        command "Ac23OnA"
        command "Ac23OffA"
        command "Ac24OnEco"
        command "Ac24OffEco"
        command "Ac24On1"
        command "Ac24Off1"
        command "Ac24Off2"
        command "Ac24Off3"
        command "Ac24Off4"
        command "Ac24OffA"
    }

    preferences
    {
        input name: "defMode",     type: "enum", title: "Default mode when turned on", options: ["cool", "heat", "auto"], defaultValue: "cool"
        input name: "defSetpoint", type: "number", title: "Default setpoint (16-30 C)", defaultValue: 24
        input name: "defFan",      type: "enum", title: "Default fan", options: ["auto", "low", "med", "high", "turbo"], defaultValue: "auto"
        input name: "logEnable",   type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def logDebug(msg) { if(logEnable) log.debug(msg) }

def installed() { initialize() }
def updated()   { initialize() }

def initialize()
{
    // state.ac is the synchronous source of truth (avoids sendEvent read-back races)
    if(null == state.ac)
    {
        state.ac = [power: false,
                    mode:  (settings.defMode ?: "cool"),
                    temp:  clampTemp((settings.defSetpoint ?: 24) as Integer),
                    fan:   (settings.defFan ?: "auto"),
                    swing: false,
                    eco:   false]
    }

    // advertise supported modes so the Easy Dashboard Thermostat tile appears
    sendEvent(name: "supportedThermostatModes", value: groovy.json.JsonOutput.toJson(["cool", "heat", "off"]))
    sendEvent(name: "supportedThermostatFanModes", value: groovy.json.JsonOutput.toJson(["auto", "low", "med", "high", "turbo"]))

    reflect()
}

//////////////////////////////////////
// Switch
//////////////////////////////////////

def on()
{
    // resume the last on-state that was actually transmitted
    if(state.lastOn) { state.ac = ([:] + state.lastOn) }
    state.ac.power = true
    composeAndSend(false)   // restoring -> don't re-snapshot
}

def off() { state.ac.power = false; composeAndSend() }

// app-driven "presence away": go to AC24OnEco but DON'T update the remembered last
// state, so on()/presence-return can restore the pre-away settings
def awayEco()
{
    state.ac = [power: true, mode: "cool", temp: 24, fan: "low", swing: true, eco: true]
    composeAndSend(false)
}

//////////////////////////////////////
// Thermostat capability
//////////////////////////////////////

def cool()          { setThermostatMode("cool") }
def heat()          { setThermostatMode("heat") }
def auto()          { setThermostatMode("auto") }
def emergencyHeat() { setThermostatMode("heat") }

def setThermostatMode(mode)
{
    if("off" == mode) { off(); return }
    if("emergency heat" == mode) { mode = "heat" }
    if(!SS_MODE.containsKey(mode)) { log.warn "Broadlink AC: unsupported mode ${mode}"; return }

    state.ac.power = true
    state.ac.mode = mode
    composeAndSend()
}

def setCoolingSetpoint(v) { state.ac.temp = clampTemp(v as BigDecimal); composeAndSend() }
def setHeatingSetpoint(v) { state.ac.temp = clampTemp(v as BigDecimal); composeAndSend() }

def setThermostatFanMode(mode) { setFan(mode) }
def fanAuto()      { setFan("auto") }
def fanCirculate() { setFan("auto") }
def fanOn()        { setFan("high") }

//////////////////////////////////////
// custom setters
//////////////////////////////////////

def setSetpoint(temp) { state.ac.temp = clampTemp(temp as BigDecimal); composeAndSend() }

def setFan(fan)
{
    if(!SS_FAN.containsKey(fan)) { log.warn "Broadlink AC: unknown fan ${fan}"; return }
    state.ac.fan = fan
    composeAndSend()
}

def setSwing(swing) { state.ac.swing = ("on" == swing); composeAndSend() }
def setEco(eco)     { state.ac.eco   = ("on" == eco);   composeAndSend() }

// fed externally (rule/app) from a real temperature sensor; does NOT transmit anything,
// it only updates what the thermostat tile shows as the current temperature
def setRoomTemperature(temp)
{
    if(null == temp) { return }
    state.roomTemp = (temp as BigDecimal)
    reflect()   // refresh temperature + the "current >> target" acState summary
}

def resend()  { composeAndSend() }
def refresh() { reflect() }

//////////////////////////////////////
// one-tap preset commands (send the exact captured code)
//////////////////////////////////////

def Ac23OnA()    { preset("AC23OnA") }
def Ac23OffA()   { preset("AC23OffA") }
def Ac24OnEco()  { preset("AC24OnEco") }
def Ac24OffEco() { preset("AC24OffEco") }
def Ac24On1()    { preset("AC24On1") }
def Ac24Off1()   { preset("AC24Off1") }
def Ac24Off2()   { preset("AC24Off2") }
def Ac24Off3()   { preset("AC24Off3") }
def Ac24Off4()   { preset("AC24Off4") }
def Ac24OffA()   { preset("AC24OffA") }

def preset(codeName)
{
    def spec = presetSpecs[codeName]
    if(!spec) { log.warn "Broadlink AC: unknown preset '${codeName}'"; return }

    // load the preset into the model and transmit a synthesised code (byte-identical
    // to the original capture, so no dependency on the parent's saved codes)
    state.ac.power = true
    state.ac.mode  = "cool"
    state.ac.temp  = spec.temp
    state.ac.fan   = spec.fan
    state.ac.swing = (spec.swing == "on")
    state.ac.eco   = spec.eco

    logDebug("Broadlink AC preset ${codeName}")
    composeAndSend()
}

//////////////////////////////////////
// compose + send (synthesised Samsung code)
//////////////////////////////////////

def composeAndSend(boolean remember = true)
{
    def a = state.ac
    def hex = buildSamsung(a.power, a.mode, a.temp, a.fan, a.swing, a.eco)

    if(null == parent)
    {
        log.error "Broadlink AC has no parent device; create it via the parent's 'createAcChild' command"
        return
    }

    logDebug("Broadlink AC synth ${describe()} -> ${hex.size()} chars")
    parent.sendCodeData(hex)
    sendEvent(name: "lastCode", value: "synth:${describe()}")

    // remember the last transmitted ON state (skip off, and skip restore/away calls)
    if(remember && a.power) { state.lastOn = ([:] + a) }

    reflect()
}

def describe()
{
    def a = state.ac
    return a.power ? "${a.mode} ${a.temp}C fan=${a.fan} swing=${a.swing ? 'on' : 'off'}${a.eco ? ' eco' : ''}" : "off"
}

//////////////////////////////////////
// Samsung encoder
//////////////////////////////////////

def clampTemp(t) { Math.max(16, Math.min(30, (t as BigDecimal).intValue())) }

def popcount(int x) { Integer.bitCount(x & 0xFF) }

// build a 7-byte section, computing the split-nibble checksum from the data bits
def cksumSection(int b0, int b1low, int b2high, int b3, int b4, int b5, int b6)
{
    int calc = popcount(b0) + popcount(b1low) + popcount(b2high) +
               popcount(b3) + popcount(b4) + popcount(b5) + popcount(b6)
    int c = (~calc) & 0xFF
    int b1 = ((c & 0x0F) << 4) | (b1low & 0x0F)
    int b2 = ((b2high & 0x0F) << 4) | ((c >> 4) & 0x0F)
    return [b0, b1, b2, b3, b4, b5, b6]
}

def headerSection(boolean powerOn)    { cksumSection(0x02, 0x02, 0x00, 0x00, 0x00, 0x00, powerOn ? 0xF0 : 0xC0) }
def extendedMarker()                  { cksumSection(0x01, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00) }

def settingsSection(boolean powerOn, mode, int temp, fan, boolean swingOn, boolean eco)
{
    int b2high = swingOn ? 0xA : 0xF
    int b3 = eco ? 0x7F : 0x71
    int b4 = ((temp - 16) & 0x0F) << 4
    int b5 = ((SS_MODE[mode] & 0x7) << 4) | ((SS_FAN[fan] & 0x7) << 1) | 0x01
    int b6 = powerOn ? 0xF0 : 0xC0
    return cksumSection(0x01, 0x02, b2high, b3, b4, b5, b6)
}

def buildSamsung(boolean powerOn, mode, int temp, fan, boolean swingOn, boolean eco)
{
    def secs = [headerSection(powerOn), extendedMarker(), settingsSection(powerOn, mode, temp, fan, swingOn, eco)]

    // sections -> Samsung pulse train (us)
    def pulses = [HDR_MARK, HDR_SPACE]
    secs.each { sec ->
        pulses << SEC_MARK << SEC_SPACE
        sec.each { b ->
            (0..7).each { bit ->                       // LSB-first
                pulses << BIT_MARK << ((((b as int) >> bit) & 1) ? ONE_SPACE : ZERO_SPACE)
            }
        }
        pulses << BIT_MARK << SEC_GAP
    }

    // pulses -> Broadlink IR body (each duration in ~30.45us units)
    def body = []
    pulses.each { us ->
        int v = Math.round((us as double) * 269.0d / 8192.0d) as int
        if(v < 256) { body << v }
        else { body << 0x00 << ((v >> 8) & 0xFF) << (v & 0xFF) }
    }
    body << 0x00 << 0x0D << 0x05                        // leadout

    int L = body.size()
    def out = [0x26, 0x00, L & 0xFF, (L >> 8) & 0xFF] + body
    return out.collect { String.format("%02X", (it as int) & 0xFF) }.join()
}

//////////////////////////////////////
// state reflection (display + Thermostat mirror)
//////////////////////////////////////

def reflect()
{
    def a = state.ac
    boolean on = a.power

    sendEvent(name: "switch", value: on ? "on" : "off")
    sendEvent(name: "swing", value: a.swing ? "on" : "off")
    sendEvent(name: "eco", value: a.eco ? "on" : "off")
    sendEvent(name: "acFan", value: a.fan)

    // current room temp (sensor / parent) >> target setpoint
    def curTemp = (state.roomTemp != null) ? state.roomTemp : parent?.currentValue("temperature")
    def tempStr = (curTemp != null) ? "${curTemp} >> ${a.temp}°C" : "${a.temp}°C"

    sendEvent(name: "acState", value: on
        ? "On - ${a.mode} ${tempStr}, fan ${a.fan}, swing ${a.swing ? 'on' : 'off'}${a.eco ? ', Eco' : ''}"
        : "Off")

    // Thermostat-capability mirror for the Easy Dashboard tile
    sendEvent(name: "thermostatMode", value: on ? a.mode : "off")
    sendEvent(name: "thermostatOperatingState", value: on ? (a.mode == "heat" ? "heating" : "cooling") : "idle")
    sendEvent(name: "thermostatFanMode", value: a.fan)
    sendEvent(name: "coolingSetpoint", value: a.temp)
    sendEvent(name: "heatingSetpoint", value: a.temp)
    sendEvent(name: "thermostatSetpoint", value: a.temp)

    // temperature shown = real room sensor (via setRoomTemperature) / parent sensor / setpoint
    sendEvent(name: "temperature", value: (curTemp != null) ? curTemp : a.temp)
}

//////////////////////////////////////
// Easy Dashboard preset buttons (momentary child switches)
//////////////////////////////////////

def createPresetButtons()
{
    presetButtons.each { code, label ->
        def dni = "${device.deviceNetworkId}-btn-${code}"
        if(!getChildDevice(dni))
        {
            addChildDevice("hubitat", "Generic Component Switch", dni,
                [name: "AC Preset", label: label, isComponent: false])
            log.info "created preset button: ${label}"
        }
    }
}

def removePresetButtons()
{
    getChildDevices()?.findAll { it.deviceNetworkId?.contains("-btn-") }?.each { deleteChildDevice(it.deviceNetworkId) }
    log.info "removed preset buttons"
}

// Generic Component Switch callbacks (the child switch calls these on its parent)
def componentOn(cd)
{
    def dni = cd.deviceNetworkId
    def code = dni.substring(dni.indexOf("-btn-") + 5)

    logDebug("preset button ${code}")
    preset(code)

    // momentary: light the tapped tile, then clear all after ~2s (visible flash)
    cd.sendEvent(name: "switch", value: "on")
    runIn(2, buttonsOff)
}

def componentOff(cd)     { cd.sendEvent(name: "switch", value: "off") }
def componentRefresh(cd) { }

def buttonsOff()
{
    getChildDevices()?.findAll { it.deviceNetworkId?.contains("-btn-") }?.each { it.sendEvent(name: "switch", value: "off") }
}
