# SONOFF SNZB-04PR2 — Zigbee door/window sensor

Findings from pairing three units on Hubitat (C-8 Pro, platform 2.5.2) on 2026-10-08/09.
Hubitat devices: **36 "Living Dresser CS 1"**, **37 "Living Dresser CS 2"** (swapped onto the former IKEA Parasoll
devices).

## Device

| | |
|---|---|
| Model / manufacturer | `SNZB-04PR2` / `SONOFF` |
| Firmware | 1.0.1 (application 16, ZCL 8, date code 20251029) |
| Power | battery (3.2 V new); sleepy end device |
| Device type | `0x0402` (IAS zone), zone type `0x0015` (contact switch) |
| In clusters | `0000, 0001, 0003, 0020, 0500, FC57, FC11` |
| Out clusters | `0003, 0019` |

## Driver

myL2 **"SONOFF Zigbee Contact Sensor"** (`drivers/SONOFF/SonoffZigbeeContactSensor.groovy`). kkossev's *Tuya
Zigbee Contact Sensor++* works for open/close and tamper but does not know the model (profile UNKNOWN), so it
never configures battery reporting.

Commands are queued and sent the next time the sensor wakes (open/close or a report).

## Findings

- Contact: IAS zone status bit 0. Tamper: `FC11:0x2000` (uint8, reported on change; read with mfg `0x1286`).
  Low battery: zone status bit 3.
- Battery % (`0x0001:0x0021`, half-percent) reporting 3600 / 7200 s / change 2 is accepted; the sensor then
  reports every 2 h exactly. **Voltage (`0x0020`) reporting is refused** (UNSUPPORTED_ATTRIBUTE 0x86); read only.
- `discoverAttributes FC11` lists only `0x2000`.
- Poll Control (`0x0020`) is present and binding it is accepted, but **no check-ins were sent** overnight.
- Commands sent while it sleeps are lost; they arrive only right after an event (open/close, battery report).

## Sources

- [zigbee-herdsman-converters — SONOFF definitions](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/sonoff.ts)
