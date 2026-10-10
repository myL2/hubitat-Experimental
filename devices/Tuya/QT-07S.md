# Tuya QT-07S — Zigbee soil moisture sensor (3-prong, resistive)

Findings from pairing one unit on Hubitat (C-8 Pro, platform 2.5.2) on 2026-10-10.
Hubitat device: **272 "Tuya QT-07S SMS"**.

## Device

| | |
|---|---|
| Product | QT-07S |
| Zigbee model / manufacturer | `TS0601` / `_TZE284_myd45weu` (Tuya version 1.1.0) |
| Power | battery; sleepy end device |
| Probe | three metal prongs (resistive / conductivity) |
| In clusters | `0004, 0005, EF00, 0000, ED00` |
| Out clusters | `0019, 000A` |

## Driver

myL2 **"Tuya Zigbee Soil Sensor"** (`drivers/Tuya/TuyaZigbeeSoilSensor.groovy`), shared with the HOBEIAN ZG-303Z
(the model is chosen by `TS0601`). kkossev's *Tuya Zigbee TRVs and Thermostats* logged every report as
"NOT PROCESSED".

## Tuya data points (Zigbee2MQTT `TS0601_soil`)

| DP | Meaning | Values seen |
|---|---|---|
| 3 | soil moisture % (no scaling: raw 0–100) | 0 (air), 40–72 (dry soil), 100 (wet) |
| 5 | temperature, whole °C | 22–24 |
| 9 | temperature unit | never reported |
| 14 | battery state (0 low, 1 middle, 2 high) | 2 |
| 15 | battery % | 100 |

No calibration or sampling settings.

## Findings

- **Raw 100 is the top of its scale, not 10 % ×10**: in dry soil it reported intermediate values (40–72).
- Resistive probe: saturates at 100 % in wet / fertilised soil, reads ~20 points higher than the capacitive
  ZG-303Z in the same soil (66 vs 44 %). Use its own thresholds, not the other sensor's.
- A button press or a soil change triggers a burst of 2–3 readings ~1–2 s apart that scatter by 6–8 points
  (80 / 100 / 97); once settled it was steady (±2) for hours, so no smoothing was added.
- Reported every ~5 s just after pairing, then only on change.
- **Tuya heartbeat every 4 h**: basic cluster `0x0001` (app version 0x50), `0xFFE2` = 56, `0xFFE4` = 0 (both
  undocumented; unchanged in air, dry soil and while watering). Also seen once: `0xFFCF` = 3840, `0xFFDF` string.

## Sources

- [zigbee-herdsman-converters — Tuya TS0601_soil](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/tuya.ts)
