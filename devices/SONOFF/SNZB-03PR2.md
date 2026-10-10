# SONOFF SNZB-03PR2 — Zigbee PIR motion sensor with light sensor

Findings from pairing one unit on Hubitat (C-8 Pro, platform 2.5.2) on 2026-10-09/10.
Hubitat device: **85 "Bathroom tMS"** (paired as 267, then 268, swapped onto the former Aqara sensor).

## Device

| | |
|---|---|
| Model / manufacturer | `SNZB-03PR2` / `SONOFF` |
| Firmware | 1.0.5 (application 16, date code 20260409) |
| Power | battery (3.2 V new); sleepy end device |
| Device type | `0x0107` (occupancy sensor) |
| In clusters | `0000, 0001, 0003, 0020, 0406, 0400, FC57, FC11` |
| Out clusters | `0003, 0019` |

## Driver

myL2 **"SONOFF Zigbee Motion Sensor"** (`drivers/SONOFF/SonoffZigbeeMotionSensor.groovy`). No built-in driver
matched; it joined as a generic *Device*.

Detection duration and illuminance offset are queued on Save, written when the sensor next wakes and kept
queued until it reports them back. Other commands are queued the same way.

## Clusters and attributes

| Cluster / attr | Type | Value | Meaning |
|---|---|---|---|
| `0406:0x0000` | bitmap8 | 0/1 | occupancy → motion (reported by the sensor, no reporting config needed) |
| `0406:0x0010` | uint16 | 60 → 30 | detection duration, 5–60 s (writable); some firmware reports it as `0x3C00` |
| `0406:0x0020` | uint16 | 0 | unused (on the SNZB-03P this is the timeout) |
| `0400:0x0000` | uint16 | e.g. 13080 | illuminance, lux = 10^((v−1)/10000); range 0–30001 ≈ max 1000 lx |
| `0001:0x0021` / `0x0020` | uint8 | 200 / 32 | battery % (half-percent) / 3.2 V (voltage not reportable) |
| `FC11:0x2018` | int16 | 0 | illuminance calibration offset, −1000…1000 lx (writable) |
| `FC11:0x1FFF` | uint8 | 0 | unknown |
| `0020:0x0000` … `0x0003` | — | 14400 / 6480 / 1 / 40 | Poll Control, quarter-seconds: check-in 1 h, long poll 27 min, short poll 0.25 s, fast poll 10 s |

Manufacturer-specific discovery (`1286`) of `FC11` and `0406` lists the same attributes.

## Findings

- **It polls for messages only every 27 min when idle**: commands sent while it sleeps are lost unless motion
  wakes it. The driver queues them and sends on the next message.
- Binding Poll Control is accepted, but **no check-ins were sent** overnight (same as the SNZB-04PR2).
- It sends an Identify command (`0003`, cmd `00`, `0100`) to the hub each time it wakes.
- Battery reporting 3600 / 7200 / 2 accepted.
- With 30 s detection duration, motion returned to inactive ~30 s after leaving.

## Sources

- [zigbee-herdsman-converters — SONOFF definitions](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/sonoff.ts)
