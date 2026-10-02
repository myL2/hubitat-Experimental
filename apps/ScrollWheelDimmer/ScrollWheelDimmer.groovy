/**
 * =============================  Scroll Wheel Dimmer ===================================
 *
 *  DESCRIPTION:
 *  Dims lights with a rotary / scroll-wheel button device that reports one "pushed" event
 *  per detent (e.g. IKEA BILRESA scroll wheel: 3 channels x [right, left, press] =
 *  buttons 1-9). Each channel controls its own dimmers or color bulbs; for color bulbs only
 *  the brightness changes (setLevel), color is left alone.
 *
 *  Detents are applied to a target level kept by the app, so N detents always move the
 *  level by N x step, even when they arrive faster than the bulb reports its new level.
 *  setLevel is sent at most every THROTTLE_MS per channel: a fast turn becomes one or two
 *  commands that land on the right level, a slow turn follows every detent.
 *
 * =======================================================================================
 *
 *  Changelog:
 *
 *  v1.0.1 (2026-10-01) - Read the lights only at the start of a turn (fast bursts); skip setLevel when the level is unchanged
 *  v1.0.0 (2026-10-01) - First release
 *
 */

import groovy.transform.Field

@Field static final String APP_VERSION = "1.0.1"
@Field static final int CHANNELS = 3
@Field static final long THROTTLE_MS = 250      // minimum gap between setLevel commands per channel
@Field static final long GESTURE_GAP_MS = 1500  // after this much quiet, re-read the bulb's level

definition(
    name: "Scroll Wheel Dimmer",
    namespace: "myL2",
    author: "myL2",
    description: "Dim dimmers or color bulbs (brightness only) with a scroll-wheel button device",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleThreaded: true
)

preferences {
    page(name: "mainPage", install: true, uninstall: true) {
        section("Scroll wheel") {
            input "wheel", "capability.pushableButton", title: "Rotary / scroll-wheel button device", required: true
            paragraph "<small>Buttons per channel n: 3n-2 = turn right, 3n-1 = turn left, 3n = press " +
                      "(BILRESA: channel 1 = buttons 1/2/3, channel 2 = 4/5/6, channel 3 = 7/8/9).</small>"
        }
        (1..CHANNELS).each { ch ->
            section("Channel ${ch}") {
                input "dimmers${ch}", "capability.switchLevel", title: "Dimmers / color bulbs", multiple: true, required: false
            }
        }
        section("Dimming") {
            input "stepPercent", "number", title: "Step per detent (%)", defaultValue: 5, range: "1..50", required: true
            input "minLevel", "number", title: "Minimum level (%)", defaultValue: 1, range: "1..100", required: true
            input "maxLevel", "number", title: "Maximum level (%)", defaultValue: 100, range: "1..100", required: true
            input "transition", "decimal", title: "Transition time (seconds, 0 = device default)", defaultValue: 0.4, range: "0..10", required: true
            input "reverseDirection", "bool", title: "Reverse direction (turn left = brighter)", defaultValue: false
            input "pressAction", "enum", title: "Pressing the wheel", options: ["toggle": "Toggle the channel's lights", "none": "Do nothing"], defaultValue: "toggle", required: true
        }
        section("Logging") {
            input "txtEnable", "bool", title: "Log actions", defaultValue: true
            input "logEnable", "bool", title: "Debug logging", defaultValue: false
        }
        section {
            paragraph "<small>v${APP_VERSION}</small>"
        }
    }
}

def installed() { initialize() }
def updated()   { unsubscribe(); unschedule(); initialize() }

def initialize() {
    subscribe(wheel, "pushed", pushedHandler)
    state.target = [:]
    state.lastStepAt = [:]
    state.lastSentAt = [:]
    state.lastSentLevel = [:]
    if (settings.logEnable) runIn(1800, "logsOff")
    logDebug "Initialized for ${wheel}: " + (1..CHANNELS).collect { "ch${it}=${settings["dimmers${it}"] ?: '-'}" }.join(", ")
}

def logsOff() { app.updateSetting("logEnable", [value: "false", type: "bool"]) }

// ======================================================================================
//  Wheel events
// ======================================================================================

def pushedHandler(evt) {
    Integer button = evt.value as Integer
    Integer ch = ((button - 1).intdiv(3) + 1) as Integer
    Integer action = (button - 1) % 3          // 0 = right, 1 = left, 2 = press
    List devs = channelDevices(ch)
    if (!devs) {
        logDebug "button ${button} (channel ${ch}) has no lights configured"
        return
    }
    if (action == 2) {
        if (settings.pressAction == "toggle") toggle(ch, devs)
        return
    }
    boolean up = (action == 0) != (settings.reverseDirection == true)
    step(ch, devs, up)
}

private void step(Integer ch, List devs, boolean up) {
    String key = ch.toString()
    long t = now()
    Map target = state.target ?: [:]
    Map lastStepAt = state.lastStepAt ?: [:]

    // Start of a turn: take the level from the lights (they may have changed elsewhere).
    // Within a turn only the app's target is used, so bursts of detents stay cheap.
    Integer base = target[key] as Integer
    if (base == null || t - ((lastStepAt[key] ?: 0L) as Long) > GESTURE_GAP_MS) {
        boolean anyOn = devs.any { it.currentValue("switch") == "on" }
        base = anyOn ? currentLevel(devs) : 0
        Map lastSentLevel = state.lastSentLevel ?: [:]
        lastSentLevel.remove(key)
        state.lastSentLevel = lastSentLevel
    }
    if (!up && base == 0) {
        logDebug "channel ${ch}: dim down while off - ignored"
        return
    }

    Integer stepSize = (settings.stepPercent ?: 5) as Integer
    Integer lo = (settings.minLevel ?: 1) as Integer
    Integer hi = (settings.maxLevel ?: 100) as Integer
    Integer next = Math.max(lo, Math.min(hi, base + (up ? stepSize : -stepSize)))
    target[key] = next
    lastStepAt[key] = t
    state.target = target
    state.lastStepAt = lastStepAt
    logDebug "channel ${ch}: ${up ? 'up' : 'down'} ${base} -> ${next}"

    long sinceSent = t - (((state.lastSentAt ?: [:])[key] ?: 0L) as Long)
    if (sinceSent >= THROTTLE_MS) {
        flush(ch)
    } else {
        runInMillis(THROTTLE_MS - sinceSent, "flushChannel${ch}", [overwrite: true])
    }
}

def flushChannel1() { flush(1) }
def flushChannel2() { flush(2) }
def flushChannel3() { flush(3) }

private void flush(Integer ch) {
    String key = ch.toString()
    Integer level = (state.target ?: [:])[key] as Integer
    if (level == null) return
    Map lastSentLevel = state.lastSentLevel ?: [:]
    if (lastSentLevel[key] == level) return      // e.g. still clamped at min/max
    lastSentLevel[key] = level
    state.lastSentLevel = lastSentLevel
    BigDecimal duration = (settings.transition ?: 0) as BigDecimal
    channelDevices(ch).each { dev ->
        if (duration > 0) dev.setLevel(level, duration) else dev.setLevel(level)
    }
    Map lastSentAt = state.lastSentAt ?: [:]
    lastSentAt[key] = now()
    state.lastSentAt = lastSentAt
    if (settings.txtEnable) log.info "${app.label}: channel ${ch} -> ${level}%"
}

private void toggle(Integer ch, List devs) {
    boolean anyOn = devs.any { it.currentValue("switch") == "on" }
    devs.each { anyOn ? it.off() : it.on() }
    // Next turn re-reads the level from the lights
    Map target = state.target ?: [:]
    target.remove(ch.toString())
    state.target = target
    Map lastSentLevel = state.lastSentLevel ?: [:]
    lastSentLevel.remove(ch.toString())
    state.lastSentLevel = lastSentLevel
    if (settings.txtEnable) log.info "${app.label}: channel ${ch} ${anyOn ? 'off' : 'on'}"
}

// ======================================================================================
//  Helpers
// ======================================================================================

private List channelDevices(Integer ch) {
    def devs = settings["dimmers${ch}"]
    return devs ? (devs instanceof List ? devs : [devs]) : []
}

// Brightest of the channel's lights that are on (they move together from there)
private Integer currentLevel(List devs) {
    def levels = devs.findAll { it.currentValue("switch") == "on" }.collect { (it.currentValue("level") ?: 0) as Integer }
    return levels ? levels.max() : 0
}

private void logDebug(msg) {
    if (settings.logEnable) log.debug "${app.label}: ${msg}"
}
