/*

Smart Dehumidifier Manager  (Hubitat app, namespace myL2)

Replaces the old Rule Machine pile (Virtual Sync / Physical Sync / Timer / On with TH)
for a dehumidifier plugged into a power-metering smart plug, that moves between rooms.

It creates one child "Dehumidifier" virtual-dimmer device (level = minutes remaining)
and drives the real plug.

Two location PROFILES (Bathroom / Pantry), each with its own humidity sensor + target
+ optional door/contact sensor, and a per-profile START MODE:

  - Auto   : humidity >= target + hysteresis  ->  app turns the plug ON
  - Manual : the app does NOT auto-start; you turn the plug on with its physical button
             and the app just takes over managing the cycle. (Bathroom use.)

Once running (however it started), the cycle ENDS on ANY of:
  - power stays <= idle threshold for the grace period  (appliance's own humidistat
    satisfied, or tank full)            -> plug OFF
  - humidity falls back to/below target (but not before the minimum run time, to
    avoid short-cycling the compressor)                 -> plug OFF
  - max run time reached                                -> plug OFF
  - plug turned off manually (physical or device tile)

Compressor protection: an AUTO restart is held off for the minimum off time after a
cycle ends. Manual / physical starts are never blocked.

NAG: when humidity is high, the dehumidifier is OFF, and the door is open, beep a
sound device + send a notification — but only while the hub is in one of the selected
modes (so it stays quiet at night / away). Repeats no faster than the nag interval.

Drivers can't subscribe to other devices, so this app does all the wiring.

v1.0

*/

definition(
    name: "Smart Dehumidifier Manager",
    namespace: "myL2",
    author: "myL2",
    description: "Humidity/power-aware dehumidifier control with Bathroom/Pantry profiles",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/myL2/hubitat-Experimental/main/apps/SmartDehumidifier/SmartDehumidifierManager.groovy"
)

preferences
{
    page(name: "mainPage")
}

def mainPage()
{
    dynamicPage(name: "mainPage", title: "Smart Dehumidifier Manager", install: true, uninstall: true)
    {
        section("Dehumidifier plug")
        {
            input name: "plug", type: "capability.switch", title: "Power-metering smart plug (the appliance)", required: true, multiple: false,
                  description: "on/off control; its 'power' (W) is used for idle / tank-full detection"
        }
        section("Active location profile")
        {
            input name: "activeProfile", type: "enum", title: "Where is the dehumidifier now?", options: ["Bathroom", "Pantry"],
                  defaultValue: "Bathroom", required: true, submitOnChange: true,
                  description: "can also be switched from the Dehumidifier device tile (setProfile)"
        }

        section("Bathroom profile")
        {
            input name: "bathSensor",  type: "capability.relativeHumidityMeasurement", title: "Bathroom humidity sensor", required: false
            input name: "bathTarget",  type: "number", title: "Bathroom target humidity (%)", required: false, description: "default 60"
            input name: "bathStart",   type: "enum",   title: "Bathroom start mode", options: ["Auto", "Manual"], defaultValue: "Manual"
            input name: "bathContact", type: "capability.contactSensor", title: "Bathroom door/contact sensor (optional, for nag)", required: false
        }
        section("Pantry profile")
        {
            input name: "pantrySensor",  type: "capability.relativeHumidityMeasurement", title: "Pantry humidity sensor", required: false
            input name: "pantryTarget",  type: "number", title: "Pantry target humidity (%)", required: false, description: "default 60"
            input name: "pantryStart",   type: "enum",   title: "Pantry start mode", options: ["Auto", "Manual"], defaultValue: "Auto"
            input name: "pantryContact", type: "capability.contactSensor", title: "Pantry door/contact sensor (optional, for nag)", required: false
        }

        section("Cycle control")
        {
            input name: "hysteresis", type: "number", title: "Hysteresis (%) — start at target + this, stop at target", required: false, description: "default 3"
            input name: "minRunTime", type: "number", title: "Minimum run time (minutes) — never humidity-stop before this (anti short-cycle)", required: false, description: "default 10"
            input name: "maxRunTime", type: "number", title: "Max run time per cycle (minutes)", required: false, description: "default 45"
            input name: "minOffTime", type: "number", title: "Minimum off time (minutes) before an AUTO restart (compressor protection)", required: false, description: "default 3"
            input name: "idlePower",  type: "number", title: "Idle power threshold (W) — at/below = not actively dehumidifying", required: false, description: "default 30"
            input name: "idleGrace",  type: "number", title: "Idle grace (minutes) below threshold before ending the cycle", required: false, description: "default 2"
            input name: "stopOnHumidity", type: "bool", title: "Stop when humidity reaches target — OFF = run until the appliance's own humidistat idles it (deeper dry-down, slower rebound)", required: false, defaultValue: true
            input name: "notifyIdleStop", type: "bool", title: "Notify when a cycle ends on idle (humidistat satisfied / tank full)", required: false, defaultValue: false
        }

        section("Nag (high humidity, off, door open)")
        {
            input name: "beepDevice", type: "capability.tone",         title: "Sound device to beep (optional)", required: false
            input name: "notifier",   type: "capability.notification", title: "Notification device (optional)", required: false
            input name: "nagModes",   type: "mode",   title: "Only nag in these hub modes", required: false, multiple: true,
                  description: "leave empty to nag in every mode"
            input name: "nagInterval", type: "number", title: "Minimum minutes between nags", required: false, description: "default 5"
        }

        section("Logging")
        {
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
            input name: "txtEnable", type: "bool", title: "Enable description-text (info) logging", defaultValue: true
        }

        section()
        {
            def bits = []
            bits << "profile: ${profKey()} (start: ${startMode()})"
            bits << "humidity: ${curHumidity() ?: 'n/a'}%  target: ${target()}%  (start >= ${target() + hyst()}, ${stopOnHum() ? "stop <= ${target()}" : 'stop = appliance humidistat / idle power'})"
            bits << "plug: ${plug?.currentValue('switch') ?: 'n/a'}  power: ${plug?.currentValue('power') ?: 'n/a'}W"
            bits << "cycle: ${state.running ? "RUNNING, ${state.minutesLeft}m left" : 'idle'}"
            bits << "door: ${contactDev() ? (contactDev().currentValue('contact') ?: 'n/a') : 'no sensor'}"
            bits << "hub mode: ${location.mode} (${nagModeOk() ? 'nag allowed' : 'nag muted'})"
            paragraph bits.join("\n")
        }
    }
}

def installed() { initialize() }

def updated()
{
    unsubscribe()
    unschedule()
    initialize()
}

def initialize()
{
    ensureChild()

    state.profile     = (settings.activeProfile ?: "Bathroom")
    state.running     = (state.running ?: false)
    state.minutesLeft = (state.minutesLeft ?: 0)
    state.lastNag     = (state.lastNag ?: 0)

    subscribe(plug, "switch", plugSwitchHandler)
    subscribe(plug, "power",  powerHandler)

    def hs = humSensor()
    if (hs) { subscribe(hs, "humidity", humidityHandler) }

    def cs = contactDev()
    if (cs) { subscribe(cs, "contact", contactHandler) }

    subscribe(location, "mode", modeHandler)

    runEvery1Minute("tick")
    refreshChild()
    if (txtEnable) log.info "initialized — profile ${profKey()}, plug ${plug?.displayName}"
}

// ----------------------------------------------------------------------------
// profile helpers
// ----------------------------------------------------------------------------

private String profKey()   { state.profile ?: (settings.activeProfile ?: "Bathroom") }
private boolean pantry()   { profKey() == "Pantry" }

private humSensor()        { pantry() ? pantrySensor : bathSensor }
private contactDev()       { pantry() ? pantryContact : bathContact }
private String startMode() { (pantry() ? (pantryStart ?: "Auto") : (bathStart ?: "Manual")) }

private BigDecimal target()
{
    def t = pantry() ? pantryTarget : bathTarget
    return ((t != null ? t : 60) as BigDecimal)
}

private BigDecimal hyst()       { ((hysteresis != null ? hysteresis : 3) as BigDecimal) }
private Integer    maxMinutes() { ((maxRunTime != null ? maxRunTime : 45) as Integer) }

private BigDecimal runMinutes() { state.cycleStart ? (now() - state.cycleStart) / 60000.0 : 99999 }
private boolean    minRunMet()  { runMinutes() >= ((minRunTime != null ? minRunTime : 10) as BigDecimal) }
private boolean    offTimeMet() { (now() - (state.lastStop ?: 0)) >= ((minOffTime != null ? minOffTime : 3) as long) * 60000 }
private boolean    stopOnHum()  { (stopOnHumidity == null) ? true : stopOnHumidity }

private BigDecimal curHumidity()
{
    def h = humSensor()?.currentValue("humidity")
    return (h != null ? (h as BigDecimal) : null)
}

private boolean nagModeOk() { !nagModes || nagModes.contains(location.mode) }

// ----------------------------------------------------------------------------
// cycle engine
// ----------------------------------------------------------------------------

private void beginCycle(String reason, boolean commandPlug, Integer minutes = null)
{
    state.running     = true
    state.cycleStart  = now()
    state.minutesLeft = (minutes != null ? minutes : maxMinutes())
    state.idleSince   = null

    if (commandPlug && plug?.currentValue("switch") != "on") { plug.on() }

    if (txtEnable) log.info "cycle START (${reason}) — ${state.minutesLeft}m, profile ${profKey()}"
    updateChildRunning("running — ${reason}")
}

private void endCycle(String reason, boolean commandPlug, boolean idle = false)
{
    boolean wasRunning = state.running
    state.running     = false
    state.idleSince   = null
    state.minutesLeft = 0
    state.lastStop    = now()

    if (commandPlug && plug?.currentValue("switch") == "on") { plug.off() }

    if (txtEnable && wasRunning) log.info "cycle END (${reason})"

    if (idle && wasRunning && notifyIdleStop)
    {
        notifier?.deviceNotification("Dehumidifier (${profKey()}) stopped — humidistat satisfied or tank full. Check the tank.")
    }

    def ch = getChildDevice(childDni())
    ch?.sendEvent(name: "switch", value: "off")
    ch?.sendEvent(name: "level", value: 0)
    ch?.sendEvent(name: "minutesRemaining", value: 0)
    ch?.sendEvent(name: "status", value: "off — ${reason}")
    refreshChild()

    evaluateNag()
}

def tick()
{
    // the plug only updates 'power' on demand -> refresh first, then evaluate a few
    // seconds later with the fresh reading (mirrors the old Refresh -> Delay -> read rule)
    if (plug?.hasCommand("refresh")) { plug.refresh() }
    runIn(3, "tickEval")
}

def tickEval()
{
    if (state.running)
    {
        state.minutesLeft = (state.minutesLeft ?: 0) - 1

        // 1) idle / tank-full via power
        def p = plug?.currentValue("power")
        def thr = ((idlePower != null ? idlePower : 30) as BigDecimal)
        if (p != null && (p as BigDecimal) <= thr)
        {
            if (!state.idleSince) { state.idleSince = now() }
            def idleMin = (now() - state.idleSince) / 60000.0
            if (idleMin >= ((idleGrace != null ? idleGrace : 2) as BigDecimal))
            {
                endCycle("idle ${p}W ${(idleMin as BigDecimal).setScale(0)}min — humidistat/tank", true, true)
                return
            }
        }
        else { state.idleSince = null }

        // 2) humidity back to target (optional; held off until the minimum run time, to avoid short-cycling)
        def h = curHumidity()
        if (stopOnHum() && h != null && h <= target() && minRunMet()) { endCycle("humidity ${h}% back to target", true); return }

        // 3) max run time
        if (state.minutesLeft <= 0) { endCycle("max run time", true); return }

        updateChildRunning("running")
    }
    else
    {
        evaluateNag()
    }
}

// ----------------------------------------------------------------------------
// event handlers
// ----------------------------------------------------------------------------

def plugSwitchHandler(evt)
{
    if (logEnable) log.debug "plug switch -> ${evt.value} (running=${state.running})"

    if (evt.value == "on")
    {
        // external/physical turn-on (e.g. Bathroom manual) -> take over managing the cycle
        if (!state.running) { beginCycle("started at the plug", false) }
    }
    else if (evt.value == "off")
    {
        if (state.running) { endCycle("plug turned off", false) }
        else { refreshChild() }
    }
}

def powerHandler(evt)
{
    def ch = getChildDevice(childDni())
    ch?.sendEvent(name: "power", value: evt.value)
    if (logEnable) log.debug "power -> ${evt.value}W"
}

def humidityHandler(evt)
{
    def h = (evt.value as BigDecimal)
    def ch = getChildDevice(childDni())
    ch?.sendEvent(name: "humidity", value: h)
    if (logEnable) log.debug "humidity -> ${h}% (target ${target()}, running=${state.running})"

    if (state.running)
    {
        if (stopOnHum() && h <= target() && minRunMet()) { endCycle("humidity ${h}% back to target", true) }
    }
    else
    {
        if (startMode() == "Auto" && h >= target() + hyst())
        {
            if (offTimeMet())
            {
                beginCycle("humidity ${h}% >= ${target() + hyst()} (auto)", true)
            }
            else
            {
                if (logEnable) log.debug "auto-start held — off-time lockout (${minOffTime}m)"
                evaluateNag()
            }
        }
        else
        {
            evaluateNag()
        }
    }
}

def contactHandler(evt)
{
    if (logEnable) log.debug "contact -> ${evt.value}"
    evaluateNag()
}

def modeHandler(evt)
{
    if (logEnable) log.debug "hub mode -> ${evt.value}"
    evaluateNag()
}

// ----------------------------------------------------------------------------
// nag
// ----------------------------------------------------------------------------

def evaluateNag()
{
    if (state.running) { return }

    def h = curHumidity()
    if (h == null) { return }

    boolean high      = h >= target() + hyst()
    boolean plugOff   = plug?.currentValue("switch") != "on"
    boolean doorOpen  = contactDev()?.currentValue("contact") == "open"

    if (!(high && plugOff && doorOpen && nagModeOk())) { return }

    if (now() - (state.lastNag ?: 0) < ((nagInterval != null ? nagInterval : 5) as long) * 60000) { return }

    state.lastNag = now()
    def msg = "Dehumidifier should be running — ${profKey()} humidity ${h}% (target ${target()}%), it's OFF and the door is open."
    if (txtEnable) log.info "NAG: ${msg}"

    try { beepDevice?.beep() } catch (e) { if (logEnable) log.debug "beep failed: ${e.message}" }
    notifier?.deviceNotification(msg)

    def ch = getChildDevice(childDni())
    ch?.sendEvent(name: "status", value: "NAG — high humidity, off, door open")
}

// ----------------------------------------------------------------------------
// child device
// ----------------------------------------------------------------------------

private String childDni() { "dehumidifier-${app.id}" }

private void ensureChild()
{
    if (!getChildDevice(childDni()))
    {
        addChildDevice("myL2", "Dehumidifier", childDni(),
                       [name: "Dehumidifier", label: "Dehumidifier", isComponent: false])
        if (txtEnable) log.info "created child Dehumidifier device"
    }
}

private void updateChildRunning(String status = "running")
{
    def ch = getChildDevice(childDni())
    if (!ch) { return }

    int lvl = Math.max(0, Math.min(100, (state.minutesLeft ?: 0) as int))
    def h = curHumidity()
    def p = plug?.currentValue("power")
    // e.g. "running (78% >> 60% | 24m @ 310W)"
    String detail = "running (${h != null ? h : '?'}% >> ${target()}% | ${lvl}m @ ${p != null ? p : '?'}W)"

    ch.sendEvent(name: "switch", value: "on")
    ch.sendEvent(name: "level", value: lvl)
    ch.sendEvent(name: "minutesRemaining", value: lvl)
    ch.sendEvent(name: "status", value: detail)
    refreshChild()
}

private void refreshChild()
{
    def ch = getChildDevice(childDni())
    if (!ch) { return }

    ch.sendEvent(name: "profile", value: profKey())
    ch.sendEvent(name: "humidityTarget", value: target())
    def h = curHumidity();                 if (h != null) { ch.sendEvent(name: "humidity", value: h) }
    def p = plug?.currentValue("power");   if (p != null) { ch.sendEvent(name: "power", value: p) }

    if (!state.running)
    {
        ch.sendEvent(name: "switch", value: (plug?.currentValue("switch") == "on" ? "on" : "off"))
    }
}

// ----------------------------------------------------------------------------
// calls from the child device tile
// ----------------------------------------------------------------------------

def childOn()  { if (txtEnable) log.info "manual ON from device"; beginCycle("manual (device tile)", true) }
def childOff() { if (txtEnable) log.info "manual OFF from device"; endCycle("manual (device tile)", true) }

def childSetLevel(Integer v)
{
    if (v == null || v <= 0) { endCycle("set to 0 (device tile)", true); return }
    if (txtEnable) log.info "manual run ${v}m from device"
    beginCycle("manual ${v}m (device tile)", true, Math.min(v, maxMinutes()))
}

def childRefresh() { refreshChild() }

def childSetProfile(String p)
{
    if (!(p in ["Bathroom", "Pantry"])) { return }
    if (txtEnable) log.info "profile -> ${p} (from device)"
    app.updateSetting("activeProfile", [type: "enum", value: p])
    state.profile = p
    unsubscribe()
    unschedule()
    initialize()
}
