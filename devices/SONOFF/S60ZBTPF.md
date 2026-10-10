# SONOFF S60ZBTPF — Zigbee smart plug with metering

Findings from probing one unit on Hubitat (C-8 Pro, platform 2.5.2) on 2026-10-08.
Hubitat device: **220 "Fridge"**.

## Device

| | |
|---|---|
| Model / manufacturer | `S60ZBTPF` / `SONOFF` (mfg code `0x1286`) |
| Firmware | 1.0.2 (application 16, hardware 16, date code 20250411) |
| Power | mains; router |
| Endpoints | `01`, `F2` (Green Power) |
| Device type | `0x0051` (smart plug) |
| In clusters | `0000, 0003, 0004, 0005, 0006, 0702, 0B04, 0B05, FC57, FC11` |
| Out clusters | `000A, 0019` |

## Driver

myL2 **"SONOFF Zigbee Smart Plug"** (`drivers/SONOFF/SonoffZigbeeSmartPlug.groovy`). Previously on kkossev's
*Tuya Zigbee Metering Plug*, which divided the energy total by 100 instead of 1000 (showed 1458.7 kWh for 146.2).

Always On blocks Off from the hub and turns the plug back on 5 s after it reports off by itself; Persistent
Always On sends On every 1 / 15 / 60 min. Power-on state, network LED, outlet protect and overload limits are
preferences (synced back from the plug).

## Clusters and attributes

### `0006` On/Off
`0x0000` on/off, `0x4003` **StartUpOnOff** (enum8: 0 off, 1 on, 2 toggle, 0xFF previous). Nothing else.

### `FC11` SONOFF private (eWeLink) — read without manufacturer code
| Attr | Type | Meaning |
|---|---|---|
| `0x0001` | bool | network LED |
| `0x0010` | uint32 | fault code |
| `0x001C` | bitmap32 | unknown (1) |
| `0x7003` | char string | overload protection (see below) |
| `0x7004` | uint32 | current, mA |
| `0x7005` | uint32 | voltage, mV |
| `0x7006` | uint32 | power, mW — **keeps the last value after the relay turns off** |
| `0x7007` | uint8 | outlet control protect (0) |
| `0x7008` | uint32 | unknown (0) |
| `0x7009` / `0x700A` / `0x700B` | uint32 | energy today / month / yesterday, Wh |
| `0x700C` / `0x700D` | uint8 / uint32 | flag 0 / 17000 — probably max current 17 A |
| `0x700E` / `0x700F` | uint8 / uint32 | flag 0 / 253000 — probably a 253 V voltage limit |
| `0x7010` / `0x7011` | uint8 / uint32 | flag 0 / 4000000 — probably max power 4000 W |

`0x7003` payload (Zigbee2MQTT): `[len] 04 [len-2] [currentFlags] [voltageFlags] [powerFlags]` then uint32 LE
limits (mA / mV / mW) for each enabled flag: max/min current, max/min voltage, max/min power (1 = max, 2 = min,
3 = both). This unit: `0D 04 0B 01 00 01 B0360000 50973100` = max current 14 A, max power 3250 W, no voltage or
minimum limits. Written with a raw Write Attributes frame, type 0x42, length-prefixed.

### `0702` Metering
`0x0000` summation (uint48), multiplier `0x0301` = 1, divisor `0x0302` = 1000 → kWh. `0x0200`, `0x0300`,
`0x0303`, `0x0306` all 0 (status, kWh, formatting, electric meter).

### `0B04` Electrical measurement
Present (`0x0505` V, `0x0508` A, `0x050B` W; voltage and current divisor 100, power divisor 1) but the plug
reports through `FC11`.

## Findings

- **The fridge was found off twice.** Not the hub and not overload protection: **StartUpOnOff was 0 (off)**,
  so any mains blip left the relay off. Setting it to 1 (on) is the fix; the driver writes it on Save.
- Voltage protection was off and voltage is ~237 V, so voltage was not the cause.
- Inching (auto-off timer): FC11 cluster command 0x01, manufacturer specific, payload
  `01 17 07 80 <mode> <channel> <time LE, 0.5 s> 00 00 <XOR checksum>`.
- Discovery found no delayed-power-on or child-lock attributes on this model.

## Sources

- [zigbee-herdsman-converters — SONOFF definitions](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/sonoff.ts)
- [Zigbee2MQTT issue 28470 — power reported after turning off](https://github.com/Koenkk/zigbee2mqtt/issues/28470)
