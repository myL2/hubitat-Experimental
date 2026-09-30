# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

A collection of **Hubitat Elevation** device drivers and apps written in Groovy, distributed via GitHub and the Hubitat Package Manager. Hubitat is a local home automation hub; drivers and apps run on-hub with no external build step.

## Development Workflow

There is no build system, test framework, or CI pipeline. Groovy is not compiled or executed locally; all execution happens on the hub. Development cycle:
1. Edit `.groovy` files locally
2. Push the code to the hub through the **Dev Bridge** (below), or paste it into the hub's Apps Code / Drivers Code editor
3. Test on the hub: run commands, read device/app state, logs and events

### Dev Bridge (hub access for development)

`apps/DevBridge/DevBridge.groovy` is an app installed on the hub (192.168.100.200, installed app **294**) that exposes the hub's code editor, devices, apps and logs:

- **MCP server** `hubitat-dev` (registered in Claude Code, local scope): `http://192.168.100.200/apps/api/294/mcp`
- **REST base**: `http://192.168.100.200/apps/api/294`, header `Authorization: Bearer <token>` (token: Dev Bridge app page, or `claude mcp get hubitat-dev`; never commit it)

Main tools: `list_code`, `get_code`, `create_code`, `update_code`, `delete_code`, `list_devices`, `get_device`, `get_device_events`, `run_command`, `list_installed_apps`, `get_app_status`, `get_logs`, `get_jobs`, `self_update`, `self_update_status`, `monitor_report`, `watch`.

Pushing code from disk (avoids sending whole files through MCP):
```bash
curl -X PUT --data-binary @drivers/Tuya/TuyaZigbeeCO2Sensor.groovy -H 'Content-Type: text/plain' \
  -H "Authorization: Bearer $TOKEN" http://192.168.100.200/apps/api/294/code/driver/<id>   # update
curl -X POST ... /code/<app|driver|library>                                                 # create
```
The response carries the hub's compile result (`success`, `errorMessage` with line/column). Find a code id with `list_code` (its `usedBy` lists the devices/app instances running it).

Rules:
- Writes are limited to the namespaces allowlisted in the Dev Bridge preferences (default `myL2`). Third-party namespaces (kkossev, stephack, jdthomas24, cybr, ...) are refused until added there.
- The previous source is saved to the hub's File Manager (`devbridge-backup-<type>-<id>.groovy`) before every update/delete.
- `run_command` sends real commands. The safe test device is **251 `. Dev Test Switch`** (Virtual Switch); ask before commanding real devices.
- **Updating the Dev Bridge itself**: never with `update_code`; bump `APP_VERSION`, then `self_update` (or `PUT /self` with the file). Its child app **Dev Bridge Guardian** (`DevBridgeGuardian.groovy`) saves it, health-checks the new version over `/mcp` and rolls back automatically on failure; poll `self_update_status`.
- The Guardian also monitors the Bridge and a watch list (`watch add dev|app <id>`): errors/warnings from the hub log, runtime stats, scheduled jobs. Read with `monitor_report`.
- Hub Security is on: the hub's web UI endpoints cannot be called directly from this machine; go through the Bridge.

## Repository Layout

- `apps/<Project>/` — apps, one folder per project (parent + child apps together), e.g. `apps/DevBridge/`, `apps/AdvancedThermostat/`, `apps/BatteryMonitor/` (includes a Node.js `webapp/`, configured via `HUBITAT_URL` env var)
- `drivers/<Vendor>/` — drivers grouped by vendor or kind: `Broadlink`, `IMOU`, `Ikea`, `Matter`, `Shelly`, `Smartly` (plus dashboard `assets/`), `Tuya`, `Virtual` (virtual/helper drivers), `Xiaomi` (oh-lalabs "expanded" drivers), `Zemismart`
- `archive/` — drafts and superseded versions; not in the package manifest, not deployed
- `packageManifest.json` — Package Manager metadata

File names are PascalCase without spaces (clean raw URLs). Several drivers are modified copies of third-party code and keep the original author's namespace (kkossev, oh-lalabs.com, RMoRobert, stephack, tomw, dandanache, cybr, jdthomas24); do not change those namespaces or names — the hub and HPM match code by name + namespace.

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

**State:** only reassigned top-level `state` values persist reliably — rebuild nested maps (`state.x = state.x + [k: v]`), don't mutate them in place. Use `atomicState` when another app/request must see a value before the current execution ends.

## packageManifest.json

Every `.groovy` file under `apps/` and `drivers/` has an entry; `name` and `namespace` must match the file's `definition(...)` exactly. When adding a new driver or app:
```json
{
  "id": "<new-uuid>",
  "name": "Driver Display Name",
  "namespace": "myL2",
  "location": "https://raw.githubusercontent.com/myL2/hubitat-Experimental/main/drivers/Vendor/File.groovy",
  "required": false,
  "version": "1.0"
}
```
under `"drivers"` or `"apps"` (apps also take `"oauth"` — true when the app declares `oauth: true` — and `"primary"`). Keep existing `id`s when moving files, and update any `importUrl` inside the file to the new location.
