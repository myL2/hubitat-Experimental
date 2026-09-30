# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **Hubitat Elevation SmartApp** — a single-file Groovy application that runs directly on a Hubitat home automation hub. It monitors battery levels across Z-Wave/Zigbee devices with drain-rate tracking, trend analysis, replacement detection, and scheduled reporting.

## Deployment

There is no build step. The file `BatteryMonitor.groovy` is pushed to the hub (see the repo-root CLAUDE.md, Dev Bridge) or pasted into the Apps Code editor. It keeps the upstream name and namespace `jdthomas24` ("HPM SAFE") so the hub and HPM keep matching it. Hubitat interprets the Groovy at runtime. Testing requires a live Hubitat hub.

## Architecture

The entire app lives in `BatteryMonitor.groovy`. It follows Hubitat's lifecycle pattern:

- **`installed()` / `updated()` / `initialize()`** — app lifecycle; sets up event subscriptions and scheduling
- **`mainPage()`** — primary settings UI (device selection, scan interval, report frequency, notification targets)
- **`batteryHandler()` → `updateBattery()`** — processes every battery-level event; updates `state.history[deviceId]`
- **`detectReplacement()`** — heuristic: jump from ≤40% → ≥95% triggers auto-logged replacement
- **`updateTrend()` / `getDrain()`** — rolling 5-sample drain rate (% per day), clamped to 1.5%/day; classifies Stable / Moderate / Heavy Drain
- **`health()`** — maps drain rate to Excellent / Good / Fair / Poor
- **`scheduledSummary()` / `reportScheduler()`** — scheduled report dispatch (daily / every 2–3 days / weekly / critical-only)
- **UI pages** (`summaryPage`, `trendsPage`, `historyPage`, `manualReplacementPage`, `manualReplacementConfirmPage`, `infoPage`) — Hubitat `dynamicPage()` blocks for the dashboard

## State

All persistent data lives in `state.*`:

| Key | Contents |
|-----|----------|
| `state.history[deviceId]` | `{lastLevel, lastDate, drain, samples[], justReplaced, replacedTime}` |
| `state.trend[deviceId]` | Current trend string |
| `state.replacements` | Array of `{device, level, date, type}` replacement log entries |
| `state.lastReportRun` | Timestamp of last scheduled report |

## Hubitat-Specific Patterns

- `subscribe(devices, "battery", batteryHandler)` — event wiring
- `schedule("0 0 8 * * ?", scheduledSummary)` — cron-based scheduling
- `dynamicPage(name:, title:) { section { ... } }` — all UI is declared this way
- `input(name:, type:, title:, ...)` — preferences/settings inputs
- Device notifications sent via `notificationDevices*.deviceNotification(msg)`

## Versioning

Bump the patch version before every commit/push, in both the header comment (`// Version x.y.z`) and `definition(version: ...)`, and mirror it in the app's `packageManifest.json` entry.

## Web App

`webapp/server.js` (Node.js, no dependencies) is a LAN dashboard that proxies the app's OAuth endpoint `GET /summary` (declared in `mappings`). Run it with the endpoint URL in the environment — never hardcode the token:

```bash
HUBITAT_URL='http://<hub-ip>/apps/api/<appId>/summary?access_token=<token>' npm start   # PORT defaults to 3000
```
