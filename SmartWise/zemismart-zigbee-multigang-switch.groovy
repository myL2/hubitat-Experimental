/**
 *  Zemismart ZigBee Wall Switch Multi-Gang - Device Driver for Hubitat Elevation hub
 *
 *  https://community.hubitat.com/t/zemismart-zigbee-1-2-3-4-gang-light-switches/21124/36?u=kkossev
 *
 *  Based on Muxa's driver Version 0.2.0 (last updated Feb 5, 2020)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Ver. 0.0.1 2019-08-21 Muxa    - first version
 *  Ver. 0.1.0 2020-02-05 Muxa    - Driver name "Zemismart ZigBee Wall Switch Multi-Gang"
 *  Ver. 0.2.1 2022-02-26 kkossev - TuyaBlackMagic for TS0003 _TZ3000_vjhcenzo 
 *  Ver. 0.2.2 2022-02-27 kkossev - TS0004 4-button, logEnable, txtEnable, ping(), intercept cluster: E000 attrId: D001 and D002 exceptions;
 *  Ver. 0.2.3 2022-03-04 kkossev - powerOnState options
 *  Ver. 0.2.4 2022-04-16 kkossev - _TZ3000_w58g68s3 Yagusmart 3 gang zigbee switch fingerprint
 *  Ver. 0.2.5 2022-05-28 kkossev - _TYZB01_Lrjzz1UV Zemismart 3 gang zigbee switch fingerprint; added TS0011 TS0012 TS0013 models and fingerprints; more TS002, TS003, TS004 manufacturers
 *  Ver. 0.2.6 2022-06-03 kkossev - powerOnState and Debug logs improvements; importUrl; singleThreaded
 *  Ver. 0.2.7 2022-06-06 kkossev - command '0B' (command response) bug fix; added Tuya Zugbee mini switch TMZ02L (_TZ3000_txpirhfq); bug fix for TS0011 single-gang switches.
 *  Ver. 0.2.8 2022-07-13 kkossev - added _TZ3000_18ejxno0 and _TZ3000_qewo8dlz fingerprints; added TS0001 wall switches fingerprints; added TS011F 2-gang wall outlets; added switchType configuration
 *  Ver. 0.2.9 2022-09-29 kkossev - added _TZ3000_hhiodade (ZTS-EU_1gang); added TS0001 _TZ3000_oex7egmt; _TZ3000_b9vanmes; _TZ3000_zmy4lslw
 *  Ver. 0.2.10 2022-10-15 kkossev - _TZ3000_hhiodade fingerprint correction; added _TZ3000_ji4araar
 *  Ver. 0.2.11 2022-11-07 kkossev - added _TZ3000_tqlv4ug4
 *  Ver. 0.2.12 2022-11-11 kkossev - added _TZ3000_cfnprab5 (TS011F) Xenon 4-gang + 2 USB extension; _TYZB01_vkwryfdr (TS0115) UseeLink; _TZ3000_udtmrasg (TS0003)
 *  Ver. 0.2.13 2022-11-12 kkossev - tuyaBlackMagic() for Xenon similar to Tuya Metering Plug; _TZ3000_cfnprab5 fingerprint correction; added SiHAS and NodOn switches
 *  Ver. 0.2.14 2022-11-23 kkossev - added 'ledMOode' command; fingerprints critical bug fix.
 *  Ver. 0.2.15 2022-11-23 kkossev - added added _TZ3000_zmy1waw6
 *  Ver. 0.3.0  2023-01-07 kkossev - noBindingButPolling() for _TZ3000_fvh3pjaz _TZ3000_9hpxg80k _TZ3000_wyhuocal
 *  Ver. 0.3.1  2023-01-22 kkossev - restored TS0003 _TZ3000_vjhcenzo fingerprint; added _TZ3000_iwhuhzdo
 *  Ver. 0.4.0  2023-01-22 kkossev - parsing multiple attributes; 
 *  Ver. 0.4.1  2023-02-10 kkossev - IntelliJ lint; added _TZ3000_18ejxno0 third fingerprint; 
 *  Ver. 0.5.0  2023-03-13 kkossev - removed the Initialize capability and replaced it with a custom command
 *  Ver. 0.5.1  2023-04-15 kkossev - bugfix: initialize() was not called when a new device is paired; added _TZ3000_pfc7i3kt; added TS011F _TZ3000_18ejxno0 (2 gangs); _TZ3000_zmy1waw6 bug fix; added TS011F _TZ3000_yf8iuzil (2 gangs)
 *
 */

import hubitat.device.HubAction
import hubitat.device.Protocol
import groovy.transform.Field

def version() { "0.5.1" }

def timeStamp() { "2023/04/15 9:16 AM" }

@Field static final Boolean debug = false

metadata {
    definition(name: "Zemismart ZigBee Wall Switch Multi-Gang", namespace: "muxa", author: "Muxa", importUrl: "https://raw.githubusercontent.com/kkossev/hubitat-muxa-fork/development/drivers/zemismart-zigbee-multigang-switch.groovy", singleThreaded: true) {
        //capability "Initialize" removed 2023-03-14
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "Switch"
        capability "Health Check"

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0001", manufacturer: "_TZ3000_npzfdcof", deviceJoinName: "Tuya Zigbee Switch"            // https://www.aliexpress.com/item/1005002852788275.html
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0001", manufacturer: "_TZ3000_hktqahrq", deviceJoinName: "Tuya Zigbee Switch"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0001", manufacturer: "_TZ3000_mx3vgyea", deviceJoinName: "Tuya Zigbee Switch"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0001", manufacturer: "_TZ3000_5ng23zjs", deviceJoinName: "Tuya Zigbee Switch"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0001", manufacturer: "_TZ3000_rmjr4ufz", deviceJoinName: "Tuya Zigbee Switch"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0001", manufacturer: "_TZ3000_v7gnj3ad", deviceJoinName: "Tuya Zigbee Switch"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0001", manufacturer: "_TZ3000_mx3vgyea", deviceJoinName: "Tuya Zigbee Switch"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0001", manufacturer: "_TZ3000_qsp2pwtf", deviceJoinName: "Tuya Zigbee Switch"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS000F", manufacturer: "_TZ3000_m9af2l6g", deviceJoinName: "Tuya Zigbee Switch"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0001", manufacturer: "_TZ3000_oex7egmt", deviceJoinName: "Tuya 1 gang Zigbee switch MYQ-KLS01L"        //https://expo.tuya.com/product/601097
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0001", manufacturer: "_TZ3000_tqlv4ug4", deviceJoinName: "GIRIER Tuya ZigBee 3.0 Light Switch Module"  //https://community.hubitat.com/t/girier-tuya-zigbee-3-0-light-switch-module-smart-diy-breaker-1-2-3-4-gang-supports-2-way-control/104546
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0002", manufacturer: "Zemismart", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,000A,0004,0005,0006", outClusters: "0019", model: "TS0002", manufacturer: "_TZ3000_tas0zemd", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,000A,0004,0005,0006", outClusters: "0019", model: "TS0002", manufacturer: "_TYZB01_tas0zemd", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,000A,0004,0005,0006", outClusters: "0019", model: "TS0002", manufacturer: "_TZ3000_7hp93xpr", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0004,0005,0006", outClusters: "0019", model: "TS0002", manufacturer: "_TZ3000_7hp93xpr", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0004,0005,0006", outClusters: "0019,000A", model: "TS0002", manufacturer: "_TZ3000_vjhyd6ar", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0002", manufacturer: "_TZ3000_tonrapsk", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0002", manufacturer: "_TZ3000_bvrlqyj7", deviceJoinName: "Avatto Zigbee Switch Multi-Gang"            // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0002", manufacturer: "_TZ3000_atp7xmd9", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"         // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0002", manufacturer: "_TZ3000_h34ihclt", deviceJoinName: "Tuya Zigbee Switch Multi-Gang"              // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0002", manufacturer: "_TYZB01_wmak4qjy", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"         // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,E000,E001", outClusters: "0019,000A", model: "TS0002", manufacturer: "_TZ3000_qn8qvk9y", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0002", manufacturer: "_TZ3000_b9vanmes", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0002", manufacturer: "_TZ3000_tqlv4ug4", deviceJoinName: "GIRIER Tuya ZigBee 3.0 Light Switch Module"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,0702,0B04,E000,E001,0000", outClusters: "0019,000A", model: "TS0002", manufacturer: "_TZ3000_zmy4lslw", deviceJoinName: "Tuya Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,000A,0004,0005,0006", outClusters: "0019", model: "TS0003", manufacturer: "_TYZB01_pdevogdj", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,000A,0004,0005,0006", outClusters: "0019", model: "TS0003", manufacturer: "_TZ3000_pdevogdj", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0003", manufacturer: "_TZ3000_odzoiovu", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0003", manufacturer: "_TZ3000_vsasbzkf", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0003", manufacturer: "_TZ3000_34zbimxh", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0003", manufacturer: "_TZ3000_odzoiovu", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0004,0005,0006", outClusters: "0019", model: "TS0003", manufacturer: "_TZ3000_wqfdvxen", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0004,0005,0006", outClusters: "0019", model: "TS0003", manufacturer: "_TZ3000_c0wbnbbf", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0004,0005,0006", outClusters: "0019", model: "TS0003", manufacturer: "_TZ3000_c0wbnbbf", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001", outClusters: "0019,000A", model: "TS0003", manufacturer: "_TZ3000_tbfw3xj0", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0003", manufacturer: "_TZ3000_tqlv4ug4", deviceJoinName: "GIRIER Tuya ZigBee 3.0 Light Switch Module"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0003", manufacturer: "_TZ3000_vjhcenzo", deviceJoinName: "Tuya 3-gang Switch"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "2101,0000", outClusters: "0021", model: "TS0003", manufacturer: "_TZ3000_udtmrasg", deviceJoinName: "Tuya 3-gang Switch"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0003", manufacturer: "_TZ3000_iwhuhzdo", deviceJoinName: "Zemismart ZL-LU03"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,0702,0B04,E000,E001,0000", outClusters: "0019,000A", model: "TS0003", manufacturer: "_TZ3000_pfc7i3kt", deviceJoinName: "MOES Tuya Zigebee Module"    // https://community.hubitat.com/t/driver-needed-for-moes-3-gang-smart-switch-module-ms-104cz/116449?u=kkossev
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0004", manufacturer: "_TZ3000_ltt60asa", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"        // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0004", manufacturer: "_TZ3000_excgg5kb", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"        // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0004", manufacturer: "_TZ3000_a37eix1s", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"        // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,000A,0004,0005,0006", outClusters: "0019", model: "TS0004", manufacturer: "_TZ3000_go9rahj5", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001", outClusters: "0019", model: "TS0004", manufacturer: "_TZ3000_aqgofyol", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0004", manufacturer: "_TZ3000_excgg5kb"        // 4-relays module
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0004", manufacturer: "_TZ3000_w58g68s3"        // Yagusmart 3 gang zigbee switch
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0004", manufacturer: "_TZ3000_tqlv4ug4", deviceJoinName: "GIRIER Tuya ZigBee 3.0 Light Switch Module"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0011", manufacturer: "_TZ3000_ybaprszv", deviceJoinName: "Zemismart Zigbee Switch No Neutral"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0011", manufacturer: "_TZ3000_txpirhfq", deviceJoinName: "Tuya Zigbee Mini Switch TMZ02L"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0011", manufacturer: "_TZ3000_hhiodade", deviceJoinName: "Moes ZTS-EU_1gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,0000", outClusters: "0019,000A", model: "TS0011", manufacturer: "_TZ3000_hhiodade", deviceJoinName: "Moes ZTS-EU_1gang"                    // https://community.hubitat.com/t/uk-moes-zigbee-1-2-3-or-4-gang-light-switch/89747/5?u=kkossev
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0011", manufacturer: "_TZ3000_ji4araar", deviceJoinName: "Tuya 1 gang switch"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,0000", outClusters: "0019,000A", model: "TS0011", manufacturer: "_TZ3000_9hpxg80k", deviceJoinName: "Tuya 1 gang"                          // https://github.com/zigpy/zha-device-handlers/issues/535
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0001,0007,0000,0003,0004,0005,0006,E000,E001,0002", outClusters: "0019,000A", model: "TS0012", manufacturer: "_TZ3000_k008kbls", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"        // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0012", manufacturer: "_TZ3000_uz5xzdgy", deviceJoinName: "Zemismart Zigbee Switch No Neutral"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0004,0005,0006", outClusters: "0019,000A", model: "TS0012", manufacturer: "_TZ3000_fvh3pjaz", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0004,0005,0006,EF00", outClusters: "0019,000A", model: "TS0012", manufacturer: "_TZ3000_lupfd8zu", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001", outClusters: "0019,000A", model: "TS0012", manufacturer: "_TZ3000_jl7qyupf", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,0000", outClusters: "0019,000A", model: "TS0012", manufacturer: "_TZ3000_18ejxno0", deviceJoinName: "Tuya Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,E000,E001", outClusters: "0019,000A", model: "TS0012", manufacturer: "_TZ3000_18ejxno0", deviceJoinName: "Tuya Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", model: "TS0012", manufacturer: "_TZ3000_18ejxno0", deviceJoinName: "Tuya Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006", outClusters: "0019", model: "TS0013", manufacturer: "_TYZB01_Lrjzz1UV", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"                // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006", outClusters: "0019", model: "TS0013", manufacturer: "_TZ3000_bvrlqyj7", deviceJoinName: "Avatto Zigbee Switch Multi-Gang"                   // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006", outClusters: "0019", model: "TS0013", manufacturer: "_TZ3000_wu0shw0i", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"                // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006", outClusters: "0019", model: "TS0013", manufacturer: "_TYZB01_stv9a4gy", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"                // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0004,0005,0006", outClusters: "0019,000A", model: "TS0013", manufacturer: "_TZ3000_wyhuocal", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0004,0005,0006", outClusters: "0019", model: "TS0013", manufacturer: "_TYZB01_mqel1whf", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006", outClusters: "0019", model: "TS0013", manufacturer: "_TZ3000_fvh3pjaz", deviceJoinName: "Zemismart Zigbee Switch Multi-Gang"                // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006", outClusters: "0019", model: "TS0013", manufacturer: "_TYZB01_mtlhqn48", deviceJoinName: "Lonsonho Zigbee Switch Multi-Gang"                 // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006", outClusters: "0019", model: "TS0013", manufacturer: "TUYATEC-O6SNCwd6", deviceJoinName: "TUYATEC Zigbee Switch Multi-Gang"                  // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006", outClusters: "0019", model: "TS0013", manufacturer: "_TZ3000_h34ihclt", deviceJoinName: "Tuya Zigbee Switch Multi-Gang"                     // check!
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006", outClusters: "0019", model: "TS0013", manufacturer: "_TZ3000_k44bsygw", deviceJoinName: "Zemismart Zigbee Switch No Neutral"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,0000", outClusters: "0019,000A", model: "TS0013", manufacturer: "_TZ3000_qewo8dlz", deviceJoinName: "Tuya Zigbee Switch 3 Gang No Neutral"    // @dingyang.yee https://www.aliexpress.com/item/4000298926256.html https://github.com/Koenkk/zigbee2mqtt/issues/6138#issuecomment-774720939
        /* these do NOT work with Hubitat !
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0003,0004,0005,0006,E000,E001,0000", outClusters:"0019,000A", model:"TS011F", manufacturer:"_TZ3000_cfnprab5", deviceJoinName: "Xenon 4-gang + 2 USB extension"    //https://community.hubitat.com/t/xenon-4-gang-2-usb-extension-unable-to-switch-off-individual-sockets/101384/14?u=kkossev
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0006,0003,0004,0005,E001",      outClusters:"0019,000A", model:"TS011F", manufacturer:"_TZ3000_cfnprab5", deviceJoinName: "Xenon 4-gang + 2 USB extension"    //https://community.hubitat.com/t/xenon-4-gang-2-usb-extension-unable-to-switch-off-individual-sockets/101384/14?u=kkossev
        */
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0004,0005,0006", outClusters: "0019,000A", model: "TS011F", manufacturer: "_TZ3000_zmy1waw6", deviceJoinName: "Moes 1 gang"                                // https://github.com/zigpy/zha-device-handlers/issues/1262
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,0702,0B04,E000,E001,0000", outClusters: "0019,000A", model: "TS011F", manufacturer: "_TZ3000_18ejxno0", deviceJoinName: "Moes 2 gang"       // https://pl.aliexpress.com/item/1005002061628356.html
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0003,0004,0005,0006,0702,0B04,E000,E001,0000", outClusters: "0019,000A", model: "TS011F", manufacturer: "_TZ3000_yf8iuzil", deviceJoinName: "Moes 2 gang"       // https://community.hubitat.com/t/moes-zigbee-wall-touch-smart-light-switch/97870/36?u=kkossev
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,000A,0004,0005,0006", outClusters: "0019", model: "TS0115", manufacturer: "_TYZB01_vkwryfdr", deviceJoinName: "UseeLink Power Strip"                       //https://community.hubitat.com/t/another-brick-in-the-wall-tuya-joins-the-zigbee-alliance/44152/28?u=kkossev
        // SiHAS Switch (2~6 Gang)
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006,0019", outClusters: "0003,0004,0019", manufacturer: "ShinaSystem", model: "SBM300Z2", deviceJoinName: "SiHAS Switch 2-gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006,0019", outClusters: "0003,0004,0019", manufacturer: "ShinaSystem", model: "SBM300Z3", deviceJoinName: "SiHAS Switch 3-gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006,0019", outClusters: "0003,0004,0019", manufacturer: "ShinaSystem", model: "SBM300Z4", deviceJoinName: "SiHAS Switch 4-gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006,0019", outClusters: "0003,0004,0019", manufacturer: "ShinaSystem", model: "SBM300Z5", deviceJoinName: "SiHAS Switch 5-gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006,0019", outClusters: "0003,0004,0019", manufacturer: "ShinaSystem", model: "SBM300Z6", deviceJoinName: "SiHAS Switch 6-gang"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006,0019", outClusters: "0003,0004,0019", manufacturer: "ShinaSystem", model: "ISM300Z3", deviceJoinName: "SiHAS Switch 3-gang"
        // NodOn
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,0007,0008,1000,FC57", outClusters: "0003,0006,0019", manufacturer: "NodOn", model: "SIN-4-2-20", deviceJoinName: "NodOn Light 2 channels"
        // https://nodon.pro/en/produits/zigbee-pro-on-off-lighting-relay-switch/
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,0007,0008,1000,FC57", outClusters: "0003,0006,0019", manufacturer: "NodOn", model: "SIN-4-2-20_PRO", deviceJoinName: "NodOn Light 2 channels"

        command "powerOnState", [
                [name: "powerOnState", type: "ENUM", constraints: ["--- Select ---", "OFF", "ON", "Last state"], description: "Select Power On State"]
        ]
        command "switchType", [
                [name: "switchType", type: "ENUM", constraints: ["--- Select ---", "toggle", "state", "momentary"], description: "Select Switch Type"]     // 0: 'toggle', 1: 'state', 2: 'momentary'
        ]
        command "ledMode", [
                [name: "ledMode", type: "ENUM", constraints: ["--- Select ---", "Disabled", "Lit when On", "Lit when Off"], description: "Select LED Mode"]
        ]
        command "initialize", [[name: "Select 'Yes' to re-initialize the device", type: "ENUM", description: "re-creates the child devices!", constraints: ["--- Select ---", "Yes", "No"]]]
        if (debug == true) {
            command "test", ["string"]
        }

        attribute "lastCheckin", "string"
    }
    preferences {
        input(name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false)
        input(name: "txtEnable", type: "bool", title: "Enable description text logging", defaultValue: true)
    }
}

private boolean isHEProblematic() {
    device.getDataValue("manufacturer") in ["_TZ3000_okaz9tjs", "_TZ3000_r6buo8ba", "_TZ3000_cfnprab5", "SONOFF", "Woolley", "unknown"]
}

private boolean noBindingButPolling() {
    device.getDataValue("manufacturer") in ['_TZ3000_fvh3pjaz', '_TZ3000_9hpxg80k', '_TZ3000_wyhuocal']
}    //0x4001 OnTime: value 0 //0x4002 OffWaitTime: value 0

// Parse incoming device messages to generate events

def parse(String description) {
    checkDriverVersion()
    //log.debug "${device.displayName} Parsing '${description}'"
    def descMap = [:]
    try {
        descMap = zigbee.parseDescriptionAsMap(description)
    }
    catch (e) {
        if (settings?.logEnable) log.warn "${device.displayName} exception caught while parsing description ${description} \r descMap:  ${descMap}"
        return null
    }
    logDebug "Parsed descMap: ${descMap} (description:${description})"

    Map map = null // [:]

    if (descMap.attrId != null) {
        //log.trace "parsing descMap.attrId ${descMap.attrId}"
        parseAttributes(descMap)
        return
    }
    /*
    else if (descMap.cluster == "0006" && descMap.attrId == "0000") {
       processOnOff( descMap )
    } // OnOff cluster, attrId "0000"
    else if (descMap.cluster == "0006" && descMap.attrId != "0000") { // other attr
        processOnOfClusterOtherAttr( descMap )
    }
    else if (descMap.cluster == "E001") { // Tuya Switch Mode cluster
        processOnOfClusterOtherAttr( descMap )
    }
    */
    else if (descMap?.clusterId == "0013" && descMap?.profileId != null && descMap?.profileId == "0000") {
        logInfo "device model ${device.data.model}, manufacturer ${device.data.manufacturer} <b>re-joined the network</b> (deviceNetworkId ${device.properties.deviceNetworkId}, zigbeeId ${device.properties.zigbeeId})"
    } else {
        logDebug "${device.displayName} unprocessed EP: ${descMap.sourceEndpoint} cluster: ${descMap.clusterId} attrId: ${descMap.attrId}"
    }
}

def parseAttributes(Map descMap) {
    // attribute report received
    List attrData = [[cluster: descMap.cluster, attrId: descMap.attrId, value: descMap.value, status: descMap.status]]
    descMap.additionalAttrs.each {
        attrData << [cluster: descMap.cluster, attrId: it.attrId, value: it.value, status: it.status]
    }
    //log.trace "attrData 2 = ${attrData} "
    attrData.each {
        parseSingleAttribute(it, descMap)
    } // for each attribute    
}

private void parseSingleAttribute(Map it, Map descMap) {
    //log.trace "parseSingleAttribute :${it}"
    if (it.status == "86") {
        log.warn "${device.displayName} Read attribute response: unsupported Attributte ${it.attrId} cluster ${descMap.cluster}"
        return
    }
    switch (it.cluster) {
        case "0000":
            parseBasicClusterAttribute(it)
            break
        case "0006":
            //log.warn "case cluster 0006"
            switch (it.attrId) {
                case "0000":
                    //log.warn "case cluster 0006 attrId 0000"
                    processOnOff(it, descMap)
                    break
                default:
                    //log.warn "case cluster 0006 attrId ${it.attrId}"
                    processOnOfClusterOtherAttr(it)
                    break
            }
            break
        case "0008":
            if (logEnable) log.warn "${device.displayName} may be a dimmer? This is not the right driver...cluster:${cluster} attrId ${it.attrId} value:${it.value}"
            break
        case "0300":
            if (logEnable) log.warn "${device.displayName} may be a bulb? This is not the right driver...cluster:${cluster} attrId ${it.attrId} value:${it.value}"
            break
        case "0702":
        case "0B04":
            if (logEnable) log.warn "${device.displayName} may be a power monitoring socket? This is not the right driver...cluster:${cluster} attrId ${it.attrId} value:${it.value}"
            break
        case "E000":
        case "E001":
            processOnOfClusterOtherAttr(it)
            break
        case "EF00": // Tuya cluster
            log.warn "${device.displayName} NOT PROCESSED Tuya Cluster EF00 attribute ${it.attrId}\n descMap = ${descMap}"
            break
        case "FFFD": // TuyaClusterRevision
            if (logEnable) log.warn "${device.displayName}  Tuya Cluster Revision cluster:${cluster} attrId ${it.attrId} value:${it.value}"
            break
        case "FFFE": // AttributeReportingStatus
            if (logEnable) log.warn "${device.displayName}  Tuya Attribute Reporting Status cluster:${cluster} attrId ${it.attrId} value:${it.value}"
            break
        default:
            if (logEnable) {
                String respType = (command == "0A") ? "reportResponse" : "readAttributeResponse"
                log.warn "${device.displayName} parseAttributes: <b>NOT PROCESSED</b>: <b>cluster ${descMap.cluster}</b> attribite:${it.attrId}, value:${it.value}, encoding:${it.encoding}, respType:${respType}"
            }
            break
    } // it.cluster
}

def parseBasicClusterAttribute(Map it) {
    // https://github.com/zigbeefordomoticz/Domoticz-Zigbee/blob/6df64ab4656b65ec1a450bd063f71a350c18c92e/Modules/readClusters.py 
    switch (it.attrId) {
        case "0000":
            logDebug "ZLC version: ${it.value}"        // default 0x03
            break
        case "0001":
            logDebug "Applicaiton version: ${it.value}"    // For example, 0b 01 00 0001 = 1.0.1, where 0x41 is 1.0.1
            break                                                            // https://developer.tuya.com/en/docs/iot-device-dev/tuya-zigbee-lighting-dimmer-swith-access-standard?id=K9ik6zvlvbqyw
        case "0002":
            logDebug "Stack version: ${it.value}"        // default 0x02
            break
        case "0003":
            logDebug "HW version: ${it.value}"        // default 0x01
            break
        case "0004":
            logDebug "Manufacturer name: ${it.value}"
            break
        case "0005":
            logDebug "Model Identifier: ${it.value}"
            break
        case "0007":
            logDebug "Power Source: ${it.value}"        // enum8-0x30 default 0x03
            break
        case "4000":    //software build
            logDebug "softwareBuild: ${it.value}"
            //updateDataValue("$LAB softwareBuild",it.value ?: "unknown")
            break
        case "FFE2":
        case "FFE4":
            logDebug "Attribite ${it.attrId} : ${it.value}"
            break
        case "FFFD":    // Cluster Revision (Tuya specific)
            logDebug "Cluster Revision 0xFFFD: ${it.value}"    //uint16 -0x21 default 0x0001
            break
        case "FFFE":    // Tuya specific
            logDebug "Tuya specific 0xFFFE: ${it.value}"
            break
        default:
            if (logEnable) log.warn "${device.displayName} parseBasicClusterAttribute cluster:${cluster} UNKNOWN  attrId ${it.attrId} value:${it.value}"
    }
}


def processOnOff(it, descMap) {
    // descMap.command =="0A" - switch toggled physically
    // descMap.command =="01" - get switch status
    // descMap.command =="0B" - command response
    def cd = getChildDevice("${device.id}-${descMap.endpoint}")
    if (cd == null) {
        if (!(device.data.model in ['TS0011', 'TS0001'])) {
            log.warn "${device.displayName} Child device ${device.id}-${descMap.endpoint} not found. Initialise parent device first"
            return
        }
    }
    def switchAttribute = descMap.value == "01" ? "on" : "off"
    if (cd != null) {
        if (descMap.command in ["0A", "0B"]) {
            // switch toggled
            cd.parse([[name: "switch", value: switchAttribute, descriptionText: "Child switch ${descMap.endpoint} turned $switchAttribute"]])
        } else if (descMap.command == "01") {
            // report switch status
            cd.parse([[name: "switch", value: switchAttribute, descriptionText: "Child switch  ${descMap.endpoint} is $switchAttribute"]])
        }
    }
    if (switchAttribute == "on") {
        logDebug "Parent switch on"
        sendEvent(name: "switch", value: "on")
        return
    } else if (switchAttribute == "off") {
        def cdsOn = 0
        // cound number of switches on
        getChildDevices().each { child ->
            if (getChildId(child) != descMap.endpoint && child.currentValue('switch') == "on") {
                cdsOn++
            }
        }
        if (cdsOn == 0) {
            logDebug "Parent switch off"
            sendEvent(name: "switch", value: "off")
            return
        }
    }
}

def off() {
    if (settings?.txtEnable) log.info "${device.displayName} Turning all child switches off"
    "he cmd 0x${device.deviceNetworkId} 0xFF 0x0006 0x0 {}"
}

def on() {
    if (settings?.txtEnable) log.info "${device.displayName} Turning all child switches on"
    "he cmd 0x${device.deviceNetworkId} 0xFF 0x0006 0x1 {}"
}

def refresh() {
    logDebug "refreshing"
    "he rattr 0x${device.deviceNetworkId} 0xFF 0x0006 0x0"
}

def ping() {
    refresh()
}

private Integer convertHexToInt(hex) {
    Integer.parseInt(hex, 16)
}

private String getChildId(childDevice) {
    return childDevice.deviceNetworkId.substring(childDevice.deviceNetworkId.length() - 2)
}

def componentOn(childDevice) {
    logDebug "sending componentOn ${childDevice.deviceNetworkId}"
    sendHubCommand(new HubAction("he cmd 0x${device.deviceNetworkId} 0x${getChildId(childDevice)} 0x0006 0x1 {}", Protocol.ZIGBEE))
}

def componentOff(childDevice) {
    logDebug "sending componentOff ${childDevice.deviceNetworkId}"
    sendHubCommand(new HubAction("he cmd 0x${device.deviceNetworkId} 0x${getChildId(childDevice)} 0x0006 0x0 {}", Protocol.ZIGBEE))
}

def componentRefresh(childDevice) {
    logDebug "sending componentRefresh ${childDevice.deviceNetworkId} ${childDevice}"
    sendHubCommand(new HubAction("he rattr 0x${device.deviceNetworkId} 0x${getChildId(childDevice)} 0x0006 0x0", Protocol.ZIGBEE))
}

def setupChildDevices() {
    logDebug "Parent setupChildDevices"
    deleteObsoleteChildren()
    def buttons = 0
    switch (device.data.model) {
        case 'SBM300Z6':
            buttons = 6
            break
        case 'TS011F':
            if (device.data.manufacturer == '_TZ3000_zmy1waw6') {
                buttons = 0
            } 
            else if (device.data.manufacturer in ['_TZ3000_18ejxno0', '_TZ3000_yf8iuzil']) {
                buttons = 2
            } 
            else {
                buttons = 5
            }
            break
        case 'TS0115':
        case 'SBM300Z5':
            buttons = 5
            break
        case 'TS0004':
        case 'TS0014':
        case 'SBM300Z4':
            buttons = 4
            break
        case 'TS0003':
        case 'TS0013':
        case 'SBM300Z3':
        case 'ISM300Z3':
            buttons = 3
            break
        case 'TS0002':
        case 'TS0012':
        case 'SBM300Z2':
        case 'SIN-4-2-20':
        case 'SIN-4-2-20_PRO':
            buttons = 2
            break
        case 'TS0011':
        case 'TS0001':
            buttons = 0
            break
        default:
            break
    }
    logDebug "model: ${device.data.model} buttons: $buttons"
    createChildDevices((int) buttons)
}

def createChildDevices(int buttons) {
    logDebug "Parent createChildDevices"

    if (buttons == 0)
        return

    for (i in 1..buttons) {
        def childId = "${device.id}-0${i}"
        def existingChild = getChildDevices()?.find { it.deviceNetworkId == childId }

        if (existingChild) {
            log.info "${device.displayName} Child device ${childId} already exists (${existingChild})"
        } else {
            log.info "${device.displayName} Creatung device ${childId}"
            addChildDevice("hubitat", "Generic Component Switch", childId, [isComponent: true, name: "Switch EP0${i}", label: "${device.displayName} EP0${i}"])
        }
    }
}

def deleteObsoleteChildren() {
    logDebug "Parent deleteChildren"

    getChildDevices().each { child ->
        if (!child.deviceNetworkId.startsWith(device.id) || child.deviceNetworkId == "${device.id}-00") {
            log.info "${device.displayName} Deleting ${child.deviceNetworkId}"
            deleteChildDevice(child.deviceNetworkId)
        }
    }
}

def driverVersionAndTimeStamp() { version() + ' ' + timeStamp() }

def checkDriverVersion() {
    if (state.driverVersion == null || (driverVersionAndTimeStamp() != state.driverVersion)) {
        if (txtEnable == true) log.debug "${device.displayName} updating the settings from the current driver ${device.properties.typeName} version ${state.driverVersion} to the new version ${driverVersionAndTimeStamp()} [model ${device.data.model}, manufacturer ${device.data.manufacturer}, application ${device.data.application}, endpointId ${device.endpointId}]"
        initializeVars(fullInit = false)
        state.driverVersion = driverVersionAndTimeStamp()
    }
}

void initializeVars(boolean fullInit = true) {
    if (settings?.txtEnable) log.info "${device.displayName} InitializeVars()... fullInit = ${fullInit}"
    if (fullInit == true) {
        state.clear()
        state.driverVersion = driverVersionAndTimeStamp()
    }
    if (settings?.logEnable == null) device.updateSetting("logEnable", true)
    if (settings?.txtEnable == null) device.updateSetting("txtEnable", true)
}

def initialize( str ) {
    if (str == "Yes") {
        initialize() 
    }
    else {
        logInfo "initialize aborted!"
    }
}
def initialize() {
    logDebug "Initializing..."
    initializeVars(fullInit = true)
    configure()    // added 11/12/2022
    setupChildDevices()
}

def installed() {
    logInfo "<b>Parent installed</b>, typeName ${device.properties.typeName}, version ${driverVersionAndTimeStamp()}, deviceNetworkId ${device.properties.deviceNetworkId}, zigbeeId ${device.properties.zigbeeId}"
    logInfo "model ${device.data.model}, manufacturer ${device.data.manufacturer}, application ${device.data.application}, endpointId ${device.endpointId}"
    initialize()
}

def updated() {
    logDebug "Parent updated"
}

def tuyaBlackMagic() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0000, [0x0004, 0x0000, 0x0001, 0x0005, 0x0007, 0xfffe], [:], delay = 200)
    cmds += zigbee.writeAttribute(0x0000, 0xffde, 0x20, 0x0d, [destEndpoint: 0x01], delay = 50)
    return cmds
}

def configure() {
    logDebug " configure().."
    List<String> cmds = []
    if (device.data.manufacturer in ["_TZ3000_cfnprab5", "_TZ3000_okaz9tjs"]) {
        log.warn "this device ${device.data.manufacturer} is known to NOT work with HE!"
    }
    cmds += tuyaBlackMagic()
    if (noBindingButPolling()) {
        //these  will send out device anounce message at ervery 2 mins as heart beat, setting 0x0099 to 1 will disable it.
        cmds += zigbee.writeAttribute(zigbee.BASIC_CLUSTER, 0x0099, 0x20, 0x01, [mfgCode: 0x0000])
        // Hack : Need to disable reporting for thoses devices, else It will enable a auto power off after 2mn.     // see https://github.com/dresden-elektronik/deconz-rest-plugin/issues/3693
        // https://github.com/Mariano-Github/Edge-Drivers-Beta/blob/652bcfbcf7b8ab8a14557e097b740216760f2b02/zigbee-multi-switch-v4-childs/src/init.lua 
        log.warn "disabling ${device.data.manufacturer} device announce message every 2 mins and skipping reporting configuiration!"
        cmds += zigbee.onOffRefresh()
    } else {
        //cmds += refresh()
        cmds += zigbee.onOffConfig()
        cmds += zigbee.onOffRefresh()
    }
    sendZigbeeCommands(cmds)
}

void sendZigbeeCommands(List<String> cmds) {
    logDebug "sendZigbeeCommands : ${cmds}"
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}


def logDebug(msg) {
    String sDnMsg = device?.displayName + " " + msg
    if (settings?.logEnable) log.debug sDnMsg
}

def logInfo(msg) {
    String sDnMsg = device?.displayName + " " + msg
    if (settings?.txtEnable) log.info sDnMsg
}

def powerOnState(relayMode) {
    List<String> cmds = []
    int modeEnum = 99
    switch (relayMode) {
        case "OFF":
            modeEnum = 0
            break
        case "ON":
            modeEnum = 1
            break
        case "Last state":
            modeEnum = 2
            break
        default:
            log.error "${device.displayName} please select a Power On State option"
            return
    }
    logDebug("setting  Power On State option to: ${relayMode}  (${modeEnum}")
    cmds += zigbee.writeAttribute(0x0006, 0x8002, DataType.ENUM8, modeEnum)
    sendZigbeeCommands(cmds)
}

def switchType(type) {
    List<String> cmds = []
    int modeEnum = 99
    switch (type) {
        case "toggle":
            modeEnum = 0
            break
        case "state":
            modeEnum = 1
            break
        case "momentary":
            modeEnum = 2
            break
        default:
            log.error "${device.displayName} please select a Switch Type"
            return
    }
    logDebug("setting  Switch Type to: ${type} (${modeEnum})")
    cmds += zigbee.writeAttribute(0xE001, 0xD030, DataType.ENUM8, modeEnum)
    sendZigbeeCommands(cmds)
}

//  mode = value == 0 ? "Disabled"  : value == 1 ? "Lit when On" : value == 2 ? "Lit when Off" : null
// [name:"ledMode",    type: "ENUM",   constraints: ["--- Select ---", "Disabled", "Lit when On", "Lit when Off], description: "Select LED Mode"] 

def ledMode(mode) {
    List<String> cmds = []
    int modeEnum = 99
    switch (mode) {
        case "Disabled":
            modeEnum = 0
            break
        case "Lit when On":
            modeEnum = 1
            break
        case "Lit when Off":
            modeEnum = 2
            break
        default:
            log.error "${device.displayName} please select a LED mode option"
            return
    }
    logDebug("setting  LED mode option to: ${mode}  (${modeEnum})")
    cmds += zigbee.writeAttribute(0x0006, 0x8001, DataType.ENUM8, modeEnum)
    sendZigbeeCommands(cmds)
}


def processOnOfClusterOtherAttr(Map it) {
    //logDebug "processOnOfClusterOtherAttr attribute ${it.attrId} value=${it.value}"
    def mode
    def attrName
    def value
    try {
        value = it.value as int
    }
    catch (e) {
        value = it.value
    }
    def clusterPlusAttr = it.cluster + "_" + it.attrId
    //log.trace "clusterPlusAttr = ${clusterPlusAttr}"
    switch (clusterPlusAttr) {
        case "0006_4001":
        case "0006_4002":
            attrName = "attribute " + clusterPlusAttr
            mode = value.toString()
            break
        case "0006_8000":
            attrName = "Child Lock"
            mode = value == 0 ? "off" : "on"
            break
        case "0006_8001":
            attrName = "LED mode"
            mode = value == 0 ? "Disabled" : value == 1 ? "Lit when On" : value == 2 ? "Lit when Off" : null
            break
        case "0006_8002":
            attrName = "Power On State"
            mode = value == 0 ? "off" : value == 1 ? "on" : value == 2 ? "Last state" : null
            break
        case "E000_D001":
        case "E000_D002":
        case "E000_D003":
            attrName = "attribute " + clusterPlusAttr
            mode = value.toString()
            break
        case "E001_D030":
            attrName = "Switch Type"
            mode = value == 0 ? "toggle" : value == 1 ? "state" : value == 2 ? "momentary state" : null
            break
        default:
            logDebug "processOnOfClusterOtherAttr: <b>UNPROCESSED On/Off Cluster</b>  attrId: ${it.attrId} value: ${it.value}"
            return
    }
    if (txtEnable) log.info "${device.displayName} ${attrName} is: ${mode} (${value})"
}

def test(description) {
    log.warn "testing <b>${description}</b>"
    parse(description)
}

