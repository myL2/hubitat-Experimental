# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

A collection of **Hubitat Elevation** device drivers and apps written in Groovy, distributed via GitHub and the Hubitat Package Manager. Hubitat is a local home automation hub; drivers and apps run on-hub with no external build step.

## Development Workflow

There is no build system, test framework, or CI pipeline. Development cycle:
1. Edit `.groovy` files locally
2. Import/update code directly on a Hubitat hub (via the hub's web UI: Apps Code / Drivers Code editors, or via HTTP API)
3. Test manually on the hub by interacting with devices

**There are no commands to run locally** — Groovy is not compiled or executed locally; all execution happens on the hub.

## Repository Layout

- `/Shelly/` — Wi-Fi device drivers (HTTP/REST-based); uses child device pattern for multi-channel devices
- `/Xiaomi/` — Zigbee sensor drivers (contact, motion, water, temperature, buttons, light); "expanded" variants add extra features
- `/Tuya/` — Zigbee Tuya TRVs/thermostats, power meters, CO2 sensors
- `/Zemismart/` — Zigbee blind/shade drivers
- `/SmartWise/` — Zemismart/Tuya branded devices
- `/IMOU/` — IMOU camera button driver
- `/smartly/` — JavaScript/CSS injection for Hubitat dashboard customization (not a driver/app)
- `packageManifest.json` — Package Manager metadata; update this when adding/removing drivers or apps

## Groovy Driver/App Patterns

**Standard file structure:**
```groovy
metadata {
    definition(name: "...", namespace: "myL2", author: "...", ...) {
        capability "..."         // standard Hubitat capabilities
        attribute "...", "..."   // custom attributes
        command "..."            // custom commands
        fingerprint ...          // Zigbee/Z-Wave device matching
    }
    preferences {
        input name: "...", type: "...", title: "..."
    }
}

def installed()   { initialize() }
def updated()     { initialize() }
def initialize()  { /* subscribe, schedule, configure */ }
```

**Events:** `sendEvent(name: "switch", value: "on", descriptionText: "...")`

**Logging:** `log.debug`, `log.info`, `log.warn`, `log.error` — typically gated by a user preference

**Parent/child devices:** Parent driver calls `addChildDevice(...)`, child events bubble up via `parent.childEvent(...)`

**Zigbee parsing:** `parse(String description)` receives raw Zigbee messages; use `zigbee.parseDescriptionAsMap(description)` and cluster-based dispatch

**HTTP (Shelly/IP devices):** `httpGet`/`httpPost` with callbacks; async variants (`asynchttpGet`) for non-blocking calls

## packageManifest.json

When adding a new driver or app, add an entry:
```json
{
  "id": "<new-uuid>",
  "name": "Driver Display Name",
  "namespace": "myL2",
  "location": "https://raw.githubusercontent.com/myL2/hubitat-Experimental/main/Path/To/File.groovy",
  "required": false
}
```
under `"drivers"` or `"apps"` as appropriate.
