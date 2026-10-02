# SONOFF SNZB-06P24 — 24 GHz mmWave presence sensor (Zigbee)

Findings from setting up and probing one unit on Hubitat (C-8 Pro, platform 2.5.2) on 2026-10-02.
Hubitat device: **253 "Bedroom HPR"** (was "Living HPR").

## Device

| | |
|---|---|
| Model / manufacturer | `SNZB-06P24` / `SONOFF` (mfg code `0x1286`) |
| Radio | Zigbee 3.0, plus Bluetooth (used by the eWeLink app for setup and OTA) |
| Power | USB-C 5 V; reports power source `0x04` (DC) |
| Endpoints | `01` only |
| In clusters | `0000, 0003, 0400, 0406, FC11, FC57` |
| Out clusters | `0003, 0019` (OTA) |
| Range / FOV | ~4 m / 120° (manual); reviews: reliable static detection to ~3.5 m |
| Mounting | 1–2 m high, aimed at chest height (manual) |
| Firmware | shipped `1.0.2` (`firmwareMT 1286-0812-00001002`); updated to `1.0.4` (date code `20260820`, app version 16) |

Button: short press = check connection, 5 s = pairing mode, 10 s = factory reset.

## Driver

kkossev **"Tuya Zigbee mmWave Sensor"** (namespace `kkossev`) **v4.2.5** or newer, with standard profile file
`deviceProfilesV4_mmWave.json` **v4.1.6+** (profile `SONOFF_SNZB-06P24_RADAR`). Older driver/profile versions
see it as UNKNOWN.

Setup that works:
1. Change the device type to *Tuya Zigbee mmWave Sensor*.
2. Run **Load Standard Profiles From GitHub**, then **Configure** — the first Configure must run *after* the
   profile is loaded, otherwise nothing is bound ("no configureReporting section in the UNKNOWN profile").
3. Run **spatial learning** with the room empty (see below).

Local myL2 patch of that driver (on the hub only, overwritten by an upstream update):
- `startSpatialLearning` command button with `_status_` messages: 🚶 leave the room → ⏳ running (~35 s) → ✅ done / ❌ failed / ⌛ timed out.
- "skipped illuminance … less than delta" moved from debug to trace logging.
- Diagnostic commands `discoverAttributes(cluster, mfgCode)` and `readAttributes(cluster, attrs, mfgCode)`
  (ZCL Discover/Read Attributes; results in the debug log).

## Clusters and attributes

Discovered with ZCL *Discover Attributes*; values read on firmware 1.0.4 unless noted.

### `0406` Occupancy Sensing
| Attr | Type | Value | Meaning |
|---|---|---|---|
| `0x0000` | bitmap8 | 0/1 | occupancy → `motion` |
| `0x0001` | enum8 | 0 | occupancy sensor type (generic "PIR" placeholder) |
| `0x0002` | bitmap8 | 1 | sensor type bitmap |
| `0x0010` | uint16 | 30 | occupied→unoccupied delay = **fading time**, 15–65535 s |

### `0400` Illuminance Measurement
`0x0000` measured value (→ `illuminance` lx), `0x0001`/`0x0002` min/max.

### `FC11` SONOFF private (mfg `0x1286`)
| Attr | Type | Value | Meaning |
|---|---|---|---|
| `0x2016` | bitmap16 | 255 | **zone enable**: bits 0+1 = zone 1 (0–1 m), bit 2 = 1–1.5 m … bit 7 = 3.5–4 m |
| `0x2018` | int16 | 0 | illuminance compensation offset (−1000…1000 lx) |
| `0x2021` | int8 | 0 | radar sensitivity fine-tune (−6…+6); only meaningful after spatial learning |
| `0x2015` | bitmap16 | 0 | **see findings** — not per-zone occupancy |
| `0x2017` | array 16 × uint8 | all zero | unknown; zero before and after spatial learning (not the learned map) |
| `0x2011` | array 5 × uint8 | `10 02 B3 6D 80` | unknown; unchanged across firmware update and learning → identifier |
| `0x2012` | array 32 × uint8 | random-looking | unknown; unchanged → key / hash / fingerprint |
| `0x2014` | array | — | read fails (status 0x01) |
| `0x1FFF` | uint8 | 0 | unknown |

Command `0x04` (cluster-specific, mfg-specific) = **spatial learning**: payload `00` + uint64 LE timestamp (ms).
Device answers `0x04` sub-command `0x01` (accepted, includes expected end time → ~35 s) and `0x02` (result:
state, reason; `00 00` = success).

### `FC57` = Amazon **WWAH** ("Works With All Hubs")
Network-robustness settings for Amazon hubs; leave alone on Hubitat. Values (unchanged by the 1.0.4 update):
`disableOTADowngrades`=1, `mgmtLeaveWithoutRejoinEnabled`=1, `nwkRetryCount`=3, `macRetryCount`=3,
`routerCheckInEnabled`=0, `touchlinkInterpanEnabled`=1, `wwahParentClassificationEnabled`=0,
`configurationModeEnabled`=1, `currentDebugReportID`=0, `tcSecurityOnNwkKeyRotationEnabled`=0,
`pendingNetworkUpdateChannel`=255, `pendingNetworkUpdatePANID`=65535, `otaMaxOfflineDuration`=0.

### Not present
`0001` (power), `0500` (IAS zone), `FC12` — no attributes.

## Findings

- **No per-zone occupancy exists.** Zigbee2MQTT, ZHA and SONOFF's own eWeLink cloud (`human`, `judgeTime`,
  `illumination`, `detectionArea`, `childLock`) only offer overall occupancy plus zone *enable* switches.
- **`0x2015` is pushed only during spatial learning** (≈1/s), showing distance bands with activity while the
  sensor scans the room — interference it is learning to ignore. During normal presence (people walking) it is
  never reported and reads 0. The driver's `zoneStatus` / `zonesOccupied` attributes are therefore misleading.
  - Binding `FC11` alone did not make it report; a configured report (BITMAP16, mfg `0x1286`, min 1 / max 300 s)
    was accepted (`Success`) and only produced the periodic max-interval report with value 0.
  - Reporting was switched off again with max interval `0xFFFF`.
- **`motion` flickers during spatial learning** (5 changes in ~25 s, room empty). Rules triggered by this sensor
  may fire during a calibration.
- **Spatial learning is required** for sensible zones/sensitivity, and must be repeated after moving the sensor
  or changing fixed objects. Takes ~35 s; the room must be empty.
- **Use zones as an exclusion filter** (doorway, window, fan), not as a locator.
- **Firmware 1.0.4 (via eWeLink app over Bluetooth)** added no new clusters/attributes and kept the settings.
  The update made the sensor leave/stop talking on Zigbee: rejoin by unplugging it, or Hubitat *Add Device →
  Zigbee* + 5 s button press **without deleting the device** (same device id, new network address). Then run
  Configure again (bindings) and spatial learning.
- `childLock` exists in the eWeLink cloud model but is not exposed over Zigbee (not identified among the unknown
  attributes).

## Settings in use

Fading time 30 s, sensitivity 0, illuminance offset 0, all zones enabled, illuminance reporting min 10 s /
change 5 lx, health check every 60 min.

## Sources

- [SONOFF SNZB-06P24 user manual](https://support.sonoff.tech/snzb-06p24-usermanual/)
- [Zigbee2MQTT — SNZB-06P24](https://www.zigbee2mqtt.io/devices/SNZB-06P24.html)
- [ZHA quirk PR #4907](https://github.com/zigpy/zha-device-handlers/pull/4907)
- [SonoffLAN issue #1852 (eWeLink cloud params)](https://github.com/AlexxIT/SonoffLAN/issues/1852)
- [SmartHomeScene review](https://smarthomescene.com/reviews/sonoff-senseguard-presence-sensor-snzb-06p24/)
- [zigbee-herdsman cluster definitions (WWAH 0xFC57)](https://github.com/Koenkk/zigbee-herdsman/blob/master/src/zspec/zcl/definition/cluster.ts)
- [kkossev mmWave driver + profiles](https://github.com/kkossev/Hubitat/tree/development/Drivers/Tuya%20Zigbee%20mmWave%20Sensor)
