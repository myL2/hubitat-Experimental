/* groovylint-disable CompileStatic, CouldBeElvis, CouldBeSwitchStatement, DuplicateMapLiteral, DuplicateNumberLiteral, DuplicateStringLiteral, ImplicitClosureParameter, InsecureRandom, LineLength, MethodCount, MethodSize, PublicMethodsBeforeNonPublicMethods, SpaceAroundOperator, ThrowException, UnnecessaryGetter, UnnecessarySetter */
/**
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *    in compliance with the License. You may obtain a copy of the License at:
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *    on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *    for the specific language governing permissions and limitations under the License.
 *
 * This DTH is coded based on iquix's tuya-window-shade DTH and these other files:
 * https://github.com/iquix/Smartthings/blob/master/devicetypes/iquix/tuya-window-shade.src/tuya-window-shade.groovy
 * https://raw.githubusercontent.com/shin4299/XiaomiSJ/master/devicetypes/shinjjang/zemismart-zigbee-blind.src/zemismart-zigbee-blind.groovy
 * https://templates.blakadder.com/zemismart_YH002.html
 * https://github.com/zigpy/zha-device-handlers/blob/f3302257fbb57f9f9f99ecbdffdd2e7862cc1fd7/zhaquirks/tuya/__init__.py#L846
 *
 * VERSION HISTORY
 *
 * 1.0.0 (2021-03-09) [Amos Yuen] - Initial Commit
 * 2.0.0 (2021-03-09) [Amos Yuen] - Change tilt mode open()/close() commands to use set position to open/close all the way.; Rename pause() to stop(); Remove superfluous setDirection() setMode() functions
 * 2.1.0 (2021-05-01) [Amos Yuen] - Add pushable button capability Add configurable close and open position thresholds
 * 2.2.0 (2021-06-06) [Amos Yuen] - Add commands for stepping; Fix push command not sending zigbee commands
 * 2.3.0 (2021-06-09) [Amos Yuen] - Add presence attribute to indicate whether device is responsive;
 * 3.0.0 (2021-06-18) [Amos Yuen] - Support new window shade command startPositionChange(); Rename stop() to stopPositionChange(); Handle ack and set time zigbee messages
 * 3.1.0 (2022-04-07) [kkossev]   - added new devices fingerprints; blind position reporting; Tuya time synchronization;
 * 3.1.1 (2022-04-26) [kkossev]   - added more TS0601 fingerprints; atomicState bug fix; added invertPosition option; added 'SwitchLevel' capability (Alexa); added POSITION_UPDATE_TIMEOUT timer
 * 3.1.2 (2022-04-30) [kkossev]   - added AdvancedOptions; positionReportTimeout as preference parameter; added Switch capability; commands Open/Close/Stop differ depending on the model/manufacturer
 * 3.1.3 (2022-05-01) [kkossev]   - _TZE200_nueqqe6k and _TZE200_rddyvrci O/C/S commands correction; startPositionChange bug fix;
 * 3.1.4 (2022-05-02) [kkossev]   - added 'target' state variable; handle mixedDP2reporting; Configure() loads default Advanced Options depending on model/manufacturer; added INFO logging; added importUrl:
 * 3.1.5 (2022-05-02) [kkossev]   - _TZE200_rddyvrci O/C/S commands DP bug fix: added Refresh and Battery capabilities; mixedDP2reporting logic rewritten
 * 3.1.6 (2022-05-13) [kkossev]   - _TZE200_gubdgai2 defaults fixed; 4 new models fingerprints and defaults added.
 * 3.1.7 (2022-05-14) [kkossev]   - _TZE200_fzo2pocs ZM25TQ Tubular motor test; reversed O/S/C commands bug fix; added new option 'Substitute Open/Close with SetPosition command'
 * 3.2.0 (2022-05-22) [kkossev]   - code moved to the new repository amosyuen/hubitat-zemismart-zigbee/; code cleanup
 * 3.2.1 (2022-05-23) [Amos Yuen] - Fixed bug with parsing speed
 * 3.2.2 (2022-05-26) [kkossev]   - _TZE200_zah67ekd and _TZE200_wmcdj3aq Open/Close/Stop commands fixes
 * 3.2.3 (2022-09-22) [kkossev]   - _TZE200_zpzndjez inClusters correction; secure updateWindowShadeArrived() for null values;
 * 3.2.4 (2022-12-02) [kkossev]   - added _TZE200_7eue9vhc ZM25RX-0.8/30; _TZE200_fdtjuw7u _TZE200_r0jdjrvi _TZE200_bqcqqjpb
 * 3.2.5 (2022-12-12) [kkossev]   - _TZE200_fzo2pocs new device version fingerprint ; added _TZE200_udank5zs; secured code for missing 'windowShade' state; unscheduling of old periodic tasks; _TZE200_7eue9vhc not inverted
 * 3.3.0 (2022-12-30) [kkossev]   - TS130F Curtain Modules support;  _TZE200_nhyj64w2 Touch Curtain Switch - moesCalibraion; ZM85 _TZE200_cf1sl3tj support, including calibration;
 * 3.3.1 (2023-03-09) [kkossev]   - added _TZE200_hsgrhjpf
 * 3.3.2 (2023-08-10) [kkossev]   - replaced some warnings with debug level logs; removed 'enable trace logging' and 'log Unexpected Messages' options;
 * 3.3.3 (2023-10-20) [kkossev]   - _TZE200_zah67ekd checks; code reformatting;
 * 3.3.4 (2023-12-13) [kkossev]   - (dev.banch) added _TZE200_cxu0jkjk (AM02); _TZE200_nv6nxo0c
 * 3.4.0 (2024-03-02) [kkossev]   - (dev.banch) Groovy lint; added targetPosition attribute; minor bug fixes; added _TZE200_gaj531w3; POSITION_UPDATE_TIMEOUT default value changed to 15 seconds;
 *
 *                                TODO: evaluate whether adding retries for setPos is possible : https://community.hubitat.com/t/release-zemismart-zigbee-blind-driver/67525/371?u=kkossev
 */

import groovy.json.*
import groovy.transform.Field
import hubitat.zigbee.zcl.DataType
import java.text.SimpleDateFormat
import groovy.transform.CompileStatic
import hubitat.helper.HexUtils

private String textVersion() {
    return '3.4.0 - 2024-03-02 8:10 PM'
}

private String textCopyright() {
    return 'Copyright �2021-2024\nAmos Yuen, kkossev, iquix, ShinJjang'
}

def SimpleDateFormat sdf() {new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")}

@Field static final Boolean _DEBUG = true
@Field static final Integer MAX_PING_MILISECONDS = 10000
@Field static final Integer presenceCountTreshold = 3
@Field static final Integer defaultPollingInterval = 3600


metadata {
    definition(name: 'ZemiSmart Zigbee Blind Test', namespace: 'amosyuen', author: 'Amos Yuen', importUrl: 'https://raw.githubusercontent.com/amosyuen/hubitat-zemismart-zigbee/development/Zemismart%20Zigbee%20Blind.groovy', singleThreaded: true ) {
        capability 'Actuator'
        capability 'Configuration'
        capability 'HealthCheck'
        capability 'PushableButton'
        capability 'WindowShade'
        capability 'Switch'
        capability 'SwitchLevel'
        capability 'Battery'

        attribute 'speed', 'number'
        attribute 'targetPosition', 'number'
        attribute 'calibrationTime', 'number'
        attribute "lastCheckin", "string"
        attribute 'rtt', 'number'
        attribute 'healthStatus', 'enum', ['offline', 'online', 'unknown']

        command 'configure', [[name: '*** will load all defaults! ***']]
        command 'push',      [[name: 'button number*', type: 'NUMBER', description: '1: Open, 2: Close, 3: Stop, 4: Step Open, 5: Step Close']]
        command 'stepClose', [[name: 'step',   type: 'NUMBER', description: 'Amount to change position towards close. Defaults to defaultStepAmount if not set.']]
        command 'stepOpen',  [[name: 'step',   type: 'NUMBER', description: 'Amount to change position towards open. Defaults to defaultStepAmount if not set.']]
        command 'setSpeed',  [[name: 'speed*', type: 'NUMBER', description: 'Motor speed (0 to 100). Values below 5 may not work.']]
        command 'calibrate', [
                [name:'cmd', type: 'ENUM', description: 'command', constraints: (settableParsMap.keySet() as List)],
                [name:'val', type: 'STRING', description: 'preference parameter value', constraints: ['STRING']]
        ]
        if (_DEBUG == true) {
            command 'test', [[name: 'test', type: 'STRING', description: 'test']]
        }
        command 'refresh'

        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019',      model:'mcdj3aq', manufacturer:'_TYST11_wmcdj3aq', deviceJoinName: 'Zemismart Zigbee Blind'             // direction is reversed ? // Stop and Close inverted or not?
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019',      model:'owvfni3', manufacturer:'_TYST11_cowvfr',   deviceJoinName: 'Zemismart Zigbee Curtain Motor'
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019',      model:'??????', manufacturer:'_TZE200_zah67ekd', deviceJoinName: 'Zemismart Zigbee Blind'
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_zah67ekd', deviceJoinName: 'Zemismart Zigbee Blind Motor'            // AM43-0.45/40-ES-EZ
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_xuzcvlku' ,deviceJoinName: 'Zemismart Zigbee Blind Motor M515EGBZTN'
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_gubdgai2' ,deviceJoinName: 'Zemismart Zigbee Blind Motor M515EGBZTN'
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_iossyxra' ,deviceJoinName: 'Zemismart Tubular Roller Blind Motor AM15'
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_uzinxci0' ,deviceJoinName: 'Zignito Tubular Roller Blind Motor AM15'
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_nueqqe6k' ,deviceJoinName: 'Zemismart Zigbee Blind Motor M515EGZT'    // mixedDP2reporting; {0x0000: 0x0000, 0x0001: 0x0002, 0x0002: 0x0001}
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_yenbr4om' ,deviceJoinName: 'Tuya Zigbee Blind Motor'
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_5sbebbzs' ,deviceJoinName: 'Tuya Zigbee Blind Tubular Motor'    // mixedDP2reporting
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_xaabybja' ,deviceJoinName: 'Tuya Zigbee Blind Motor'    // supportDp1State
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_hsgrhjpf' ,deviceJoinName: 'Tuya Zigbee Blind Motor'    // https://community.hubitat.com/t/tuya-zigbee-roller-shade-blind-motor-hubitat-issues/91223/184?u=kkossev https://www.aliexpress.com/item/4000739390813.html
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_68nvbio9' ,deviceJoinName: 'Tuya Tubular Motor ZM25EL'         // default commands https://www.aliexpress.com/item/1005001874380608.html
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_zuz7f94z' ,deviceJoinName: 'Tuya Zigbee Blind Motor'
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_ergbiejo' ,deviceJoinName: 'Tuya Zigbee Blind Motor'
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_rddyvrci' ,deviceJoinName: 'Zemismart Zigbee Blind Motor AM43' // !!! close: 1, open: 2, stop: 0
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_wmcdj3aq' ,deviceJoinName: 'Tuya Zigbee Blind Motor'           // !!! close: 0, open: 2, stop: 1
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_cowvfni3' ,deviceJoinName: 'Zemismart Zigbee Curtain Motor'    // !!! close: 0, open: 2, stop: 1 Curtain Motor ! Do NOT invert !
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TYST11_cowvfni3' ,deviceJoinName: 'Zemismart Zigbee Curtain Motor'    // !!! close: 0, open: 2, stop: 1 Curtain Motor
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_cf1sl3tj' ,deviceJoinName: 'Zemismart Electric Curtain Robot Rechargeable zm85el-2z'    //https://www.zemismart.com/products/zm85el-2z
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_3i3exuay' ,deviceJoinName: 'Tuya Zigbee Blind Motor'
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0004,0005,EF00,0000', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_zpzndjez' ,deviceJoinName: 'Zignito Zigbee Tubular Roller Blind Motor'    // https://www.ajaxonline.co.uk/product/zignito-zigbee-roller-blind-motor/
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_nhyj64w2' ,deviceJoinName: 'Touch Curtain Switch RF'           // model: 'ZTS-EUR-C' https://community.hubitat.com/t/moes-curtain-switch/102691/16?u=kkossev
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,000A,0004,0005,EF00', outClusters:'0019', model:'TS0601', manufacturer:'_TZE200_fzo2pocs' ,deviceJoinName: 'Zemismart ZM25TQ Tubular motor'    // app. version 52 //inverted reporting; default O/C/S
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_fzo2pocs' ,deviceJoinName: 'Motorized Window Opener'           // app. version 53 //https://www.aliexpress.com/item/1005004251482469.html
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,000A,0004,0005,EF00', outClusters:'0019', model:'TS0601', manufacturer:'_TZE200_4vobcgd3' ,deviceJoinName: 'Zemismart Zigbee Tubular motor'    // onClusters may be wrong
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_5zbp6j0u' ,deviceJoinName: 'Tuya Zigbee Curtain Motor'
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_rmymn92d' ,deviceJoinName: 'Tuya Zigbee Curtain Motor'         // inverted reporting
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_nogaemzt' ,deviceJoinName: 'Tuya Zigbee Curtain Motor'
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_7eue9vhc' ,deviceJoinName: 'Zemismart Zigbee Rechargable Roller Motor'    // ZM25RX-0.8/30 inverted reporting?
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_fdtjuw7u' ,deviceJoinName: 'Tuya Zigbee Curtain Motor'         // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_gaj531w3' ,deviceJoinName: 'Tuya Zigbee Curtain Motor'         // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_r0jdjrvi' ,deviceJoinName: 'Tuya Zigbee Curtain Motor'         // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_bqcqqjpb' ,deviceJoinName: 'Tuya Zigbee Curtain Motor'         // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_udank5zs' ,deviceJoinName: 'Motorized Window Opener'           // similar to _TZE200_fzo2pocs
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_cxu0jkjk' ,deviceJoinName: 'Tuya Zigbee AM02 motor'            // https://community.hubitat.com/t/window-roller-shutter-blinds-with-hubitat/125954/10?u=kkossev
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_nv6nxo0c' ,deviceJoinName: 'Tuya Zigbee AM25 Tubular Motor'            //Model: Am25-1/30-ES-EZ (DC5v rechargeable) https://community.hubitat.com/t/looking-for-driver-moes-tubular-motor-roller-blind-am25-zigbee-version/125406?u=kkossev
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_nw1r9hp6' ,deviceJoinName: 'Tuya Zigbee Blind Motor'           // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_3ylew7b4' ,deviceJoinName: 'Tuya Zigbee Blind Motor'           // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_llm0epxg' ,deviceJoinName: 'Tuya Zigbee Blind Motor'           // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_n1aauwb4' ,deviceJoinName: 'Tuya Zigbee Blind Motor'           // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_xu4a5rhj' ,deviceJoinName: 'Tuya Zigbee Blind Motor'           // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE204_r0jdjrvi' ,deviceJoinName: 'Tuya Zigbee Blind Motor'           // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_g5wdnuow' ,deviceJoinName: 'Tuya Zigbee Window Pusher'         // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_pw7mji0l' ,deviceJoinName: 'Tuya Zigbee Roller Blinds'         // https://community.hubitat.com/t/zemismart-set-level-position/129242?u=kkossev
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_feolm6rk' ,deviceJoinName: 'Tuya Zigbee Curtain Motor'         // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_nkoabg8w' ,deviceJoinName: 'Tuya Zigbee Curtain Motor'         // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_pk0sfzvr' ,deviceJoinName: 'Tuya Zigbee Curtain Motor'         // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE204_guvc7pdy' ,deviceJoinName: 'Tuya Zigbee Curtain Motor'         // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_ol5jlkkr' ,deviceJoinName: 'Tuya Zigbee Curtain Motor'         // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0004,0005,EF00', outClusters:'0019,000A', model:'TS0601', manufacturer:'_TZE200_b2u1drdv' ,deviceJoinName: 'Tuya Zigbee Curtain Motor'         // not tested
        //
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0004,0005,0006,0102,0000', outClusters:'0019,000A', model:'TS130F', manufacturer:'_TZ3000_zirycpws' ,deviceJoinName: 'Zigbee Curtain Module QS-Zigbee-CP03'    // https://www.aliexpress.com/item/1005003697194481.html
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0004,0005,0006,0102,0000', outClusters:'0019,000A', model:'TS130F', manufacturer:'_TZ3000_1dd0d5yi' ,deviceJoinName: 'Zigbee Curtain Module'        // https://community.hubitat.com/t/moes-smart-curtain-switch-module-ms-108zr/125260/3?u=kkossev
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0004,0005,0006,0102,0000', outClusters:'0019,000A', model:'TS130F', manufacturer:'_TZ3000_4uuaja4a' ,deviceJoinName: 'Zigbee Curtain Module'        // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0004,0005,0006,0102,0000', outClusters:'0019,000A', model:'TS130F', manufacturer:'_TZ3000_fccpjz5z' ,deviceJoinName: 'Zigbee Curtain Module'        // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0004,0005,0006,0102,0000', outClusters:'0019,000A', model:'TS130F', manufacturer:'_TZ3000_vd43bbfq' ,deviceJoinName: 'Zigbee Curtain Module'        // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0004,0005,0006,0102,0000', outClusters:'0019,000A', model:'TS130F', manufacturer:'_TZ3000_ke7pzj5d' ,deviceJoinName: 'Zigbee Curtain Module'        // not tested
        fingerprint profileId:'0104', endpointId:'01', inClusters:'0004,0005,0006,0102,E001,0000', outClusters:'0019,000A', model:'TS130F', manufacturer:'_TZ3000_yruungrl' ,deviceJoinName: 'Zigbee Curtain Module'        // not tested
        //
        // defaults are :  open: 0, stop: 1,  close: 2
    }

    preferences {
        input('enableInfoLog', 'bool', title: 'Enable descriptionText logging', required: true, defaultValue: true)
        input('enableDebugLog', 'bool', title: 'Enable debug logging', required: true, defaultValue: false)
        input('mode', 'enum', title: 'Mode',
            description: '<li><b>lift</b> - motor moves until button pressed again</li>'
                    + '<li><b>tilt</b> - pressing button < 1.5s, movement stops on release'
                    + '; pressing button > 1.5s, motor moves until button pressed again</li>',
            options: MODE_MAP, required: true, defaultValue: '1')
        input('direction', 'enum', title: 'Direction', options: DIRECTION_MAP, required: true, defaultValue: 0)
        input('maxClosedPosition', 'number', title: 'Max Closed Position', description: 'The max position value that window shade state should be set to closed', required: true, defaultValue: 1)
        input('minOpenPosition', 'number', title: 'Min Open Position', description: 'The min position value that window shade state should be set to open', required: true, defaultValue: 99)
        input('percentageAdjustOpen', 'number', title: 'Opening Position Adjust Percentage', description: 'Skew SetPosition open commands by this much absolute percentage', required: true, defaultValue: 0)
        input('percentageAdjustClose', 'number', title: 'Closing Position Adjust Percentage', description: 'Skew SetPosition close commands by this much absolute percentage', required: true, defaultValue: 0)
        input('defaultStepAmount', 'number', title: 'Default Step Amount', description: 'The default step amount', required: true, defaultValue: 10)
        input('advancedOptions', 'bool', title: 'Show Advanced options', description: 'These advanced options should have been already set correctly for your device/model when device was Configred', required: true, defaultValue: false)
        if (advancedOptions == true) {
            input('invertPosition', 'bool', title: 'Invert position reporting', description: 'Some devices report the position 0..100 inverted', required: true, defaultValue: false)
            input('mixedDP2reporting', 'bool', title: 'Ignore the first Position report',  description: 'Some devices report both the Target and the Current positions the same way', required: true, defaultValue: false)
            input('substituteOpenClose', 'bool', title: 'Substitute Open/Close commands with SetPosition',  description: "Turn this option on if your motor does not work in 'lift' mode", required: true, defaultValue: false)
            input('positionReportTimeout', 'number', title: 'Position report timeout, ms', description: 'The maximum time between position reports', required: true, defaultValue: POSITION_UPDATE_TIMEOUT)
            input('forcedTS130F', 'bool', title: 'Force TS130F Model',  description: 'Force TS130F model if Data section shows endpointId: <b>F2</b>', required: true, defaultValue: false)
        }
    }
}

@Field final String MODE_TILT = '0'
@Field final Map MODE_MAP = [1: 'lift', 0: 'tilt']
@Field final Map MODE_MAP_REVERSE = MODE_MAP.collectEntries { [(it.value): it.key] }
@Field final List MODES = MODE_MAP*.value
@Field final Map DIRECTION_MAP = [0: 'forward', 1: 'reverse']
@Field final Map MOVING_MAP = [0: 'up/open', 1: 'stop', 2: 'down/close' ]
@Field final Map DIRECTION_MAP_REVERSE = DIRECTION_MAP.collectEntries { [(it.value): it.key] }
@Field final List DIRECTIONS = DIRECTION_MAP*.value
@Field final int CHECK_FOR_RESPONSE_INTERVAL_SECONDS = 60
@Field final int HEARTBEAT_INTERVAL_SECONDS = 4000 // a little more than 1 hour
@Field final int POSITION_UPDATE_TIMEOUT = 15000    //  in milliseconds
@Field final int INVALID_POSITION = -1
@Field final int MIN_STEP = 10

@Field final int tuyaMovingState =     0xf000        //   type: enum8
@Field final int tuyaCalibration =     0xf001        //   type: enum8
@Field final int tuyaMotorReversal =   0xf002        //   type: enum8
@Field final int moesCalibrationTime = 0xf003        //   type: uint16
@Field final Map ZM85EL_MODE = [0: 'morning', 1: 'night']    // DP=4; enum
@Field final Map ZM85EL_SITUATION_SET = [0: 'fully_open', 1: 'fully_close']    // DP=11; enum
@Field final Map ZM85EL_BORDER = [0: 'up', 1: 'down', 2: 'up_delete', 3: 'down_delete', 4: 'remove_top_bottom']    // DP=16; enum
@Field final Map ZM85EL_CLICK_CONTROL = [0: 'up', 1: 'down']    // DP=20; enum

String getModel()  { return settings?.forcedTS130F == true ? 'TS130F' : device.getDataValue('model') }
boolean isTS130F() { return getModel() == 'TS130F' }
boolean isZM85EL() { return device.getDataValue('manufacturer') in ['_TZE200_cf1sl3tj'] }
boolean isAM43()   { return device.getDataValue('manufacturer') in ['_TZE200_zah67ekd'] }
boolean isAM02()   { return device.getDataValue('manufacturer') in ['_TZE200_iossyxra', '_TZE200_cxu0jkjk'] }

boolean isCurtainMotor() {
    String manufacturer = device.getDataValue('manufacturer')
    return manufacturer in ['_TYST11_cowvfni3', '_TZE200_cowvfni3', '_TYST11_cowvfr']
}

boolean isMoesCoverSwitch() { return device.getDataValue('manufacturer') in ['_TZE200_nhyj64w2'] }

// Open - default 0x00
int getDpCommandOpen() {
    String manufacturer = device.getDataValue('manufacturer')
    if (manufacturer in ['_TZE200_rddyvrci', '_TZE200_cowvfni3', '_TYST11_cowvfni3']) {
        return DP_COMMAND_CLOSE //0x02
    }
    return DP_COMMAND_OPEN //0x00
}

// Stop - default 0x01
int getDpCommandStop() {
    String manufacturer = device.getDataValue('manufacturer')
    if (manufacturer in ['_TZE200_nueqqe6k'] || isTS130F()) {
        return DP_COMMAND_CLOSE //0x02
    }
    else if (manufacturer in ['_TZE200_rddyvrci']) {
        return DP_COMMAND_OPEN //0x00
    }
    return DP_COMMAND_STOP //0x01
}

// Close - default 0x02
int getDpCommandClose() {
    String manufacturer = device.getDataValue('manufacturer')
    if (manufacturer in ['_TZE200_nueqqe6k', '_TZE200_rddyvrci'] || isTS130F()) {
        return DP_COMMAND_STOP //0x01
    }
    else if (manufacturer in ['_TZE200_cowvfni3', '_TYST11_cowvfni3']) {
        return DP_COMMAND_OPEN //0x00
    }
    return DP_COMMAND_CLOSE //0x02
}

boolean isMixedDP2reporting() {
    return (device.getDataValue('manufacturer') in ['_TZE200_xuzcvlku', '_TZE200_nueqqe6k', '_TZE200_5sbebbzs', '_TZE200_gubdgai2'])
}

boolean isInvertedPositionReporting() {
    return (device.getDataValue('manufacturer') in ['_TZE200_xuzcvlku', '_TZE200_nueqqe6k', '_TZE200_5sbebbzs', '_TZE200_gubdgai2', '_TZE200_fzo2pocs', '_TZE200_wmcdj3aq', '_TZE200_nogaemzt', '_TZE200_xaabybja', '_TZE200_yenbr4om', '_TZE200_zpzndjez',
                         '_TZE200_zuz7f94z', '_TZE200_rmymn92d', '_TZE200_udank5zs'])
}

boolean isZM25TQ() { return device.getDataValue('manufacturer') in ['_TZE200_fzo2pocs', '_TZE200_udank5zs'] }

boolean isOpenCloseSubstituted() { return device.getDataValue('manufacturer') in ['_TZE200_rddyvrci', '_TZE200_fzo2pocs', '_TZE200_udank5zs', '_TZE200_68nvbio9'] }

int getPositionReportTimeout() { return isZM85EL() ? 15000 : POSITION_UPDATE_TIMEOUT }

//
// Life Cycle
//

void installed() {
    configure()
}

// This method is called when the preferences of a device are updated.
void updated() {
    configure(fullInit = false)
    logDebug("updated ${device.displayName} model=${getModel()} manufacturer=${device.getDataValue('manufacturer')}")
}

void configure(boolean fullInit = true) {
    state.version = textVersion()
    state.copyright = textCopyright()
    
    resetStats()

    if (state.lastHeardMillis == null) { state.lastHeardMillis = 0 }
    if (state.target == null || state.target < 0 || state.target > 100) { state.target = 0 }
    state.isTargetRcvd = false

    sendEvent(name: 'numberOfButtons', value: 5, type: 'digital')
    sendEvent(name: 'targetPosition', value: 50, type: 'digital')
    unschedule()    // added 2022/12/10

    // Must run async otherwise, one will block the other
    runIn(1, setMode)
    runIn(2, setDirection)

    if (settings.enableInfoLog == null || fullInit == true) { device.updateSetting('enableInfoLog', [value: true, type: 'bool'])  }
    if (settings.advancedOptions == null || fullInit == true) { device.updateSetting('advancedOptions', [value: false, type: 'bool']) }

    // Reset the Advanced Options parameters to their default values depending on the model/manufacturer
    if (settings.mixedDP2reporting == null || fullInit == true) { device.updateSetting('mixedDP2reporting', [value: isMixedDP2reporting(), type: 'bool']) }
    if (settings.invertPosition == null || fullInit == true) { device.updateSetting('invertPosition', [value: isInvertedPositionReporting(), type: 'bool']) }
    if (settings.substituteOpenClose == null || fullInit == true) { device.updateSetting('substituteOpenClose', [value: isOpenCloseSubstituted(), type: 'bool']) }
    if (settings.positionReportTimeout == null || fullInit == true) { device.updateSetting('positionReportTimeout', [value: getPositionReportTimeout(), type: 'number']) }
    if (settings.maxClosedPosition == null || fullInit == true) { device.updateSetting('maxClosedPosition', [value: 1, type: 'number']) }
    if (settings.minOpenPosition == null || fullInit == true) { device.updateSetting('minOpenPosition', [value: 99, type: 'number']) }
    if (settings.forcedTS130F == null || fullInit == true) { device.updateSetting('forcedTS130F', [value: false, type: 'bool']) }

    if (isZM85EL()) {
        logDebug "isZM85EL() = ${isZM85EL()}"
    }

    if (settings?.maxClosedPosition < 0 || settings?.maxClosedPosition > 100) {
        /* groovylint-disable-next-line ThrowException */
        throw new Exception("Invalid maxClosedPosition \"${maxClosedPosition}\" should be between"
            + ' 0 and 100 inclusive.')
    }
    if (settings?.minOpenPosition < 0 || settings?.minOpenPosition > 100) {
        /* groovylint-disable-next-line ThrowException */
        throw new Exception("Invalid minOpenPosition \"${minOpenPosition}\" should be between 0"
            + ' and 100 inclusive.')
    }
    if (settings?.maxClosedPosition >= settings?.minOpenPosition) {
        /* groovylint-disable-next-line ThrowException */
        throw new Exception("maxClosedPosition \"${minOpenPosition}\" must be less than"
            + " minOpenPosition \"${minOpenPosition}\".")
    }

    logInfo("${device.displayName} configured : model=${getModel()} manufacturer=${device.getDataValue('manufacturer')}")
    logDebug(" fullInit=${fullInit} invertPosition=${settings.invertPosition}, positionReportTimeout=${positionReportTimeout}, mixedDP2reporting=${settings.mixedDP2reporting}, substituteOpenClose=${settings.substituteOpenClose}")
}

void  setDirection() {
    if (settings?.direction != null) {
        int directionValue = direction as int
        logDebug("setDirection: directionText=${DIRECTION_MAP[directionValue]}, directionValue=${directionValue}")
        sendTuyaCommand(DP_ID_DIRECTION, DP_TYPE_ENUM, directionValue, 2)
    }
}

void setMode() {
    if (settings?.mode != null) {
        int modeValue = mode as int
        logDebug("setMode: modeText=${MODE_MAP[modeValue].value}, modeValue=${modeValue}")
        sendTuyaCommand(DP_ID_MODE, DP_TYPE_ENUM, modeValue, 2)
    }
}

//
// Messages
//

@Field final int CLUSTER_TUYA = 0xEF00

@Field final int ZIGBEE_COMMAND_SET_DATA = 0x00
@Field final int ZIGBEE_COMMAND_REPORTING = 0x01
@Field final int ZIGBEE_COMMAND_SET_DATA_RESPONSE = 0x02
@Field final int ZIGBEE_COMMAND_ACK = 0x0B
@Field final int ZIGBEE_COMMAND_SET_TIME = 0x24

@Field final int DP_ID_COMMAND = 0x01
@Field final int DP_ID_TARGET_POSITION = 0x02
@Field final int DP_ID_CURRENT_POSITION = 0x03
@Field final int DP_ID_DIRECTION = 0x05
@Field final int DP_ID_COMMAND_REMOTE = 0x07
@Field final int DP_ID_MODE = 0x65
@Field final int DP_ID_SPEED = 0x69
@Field final int DP_ID_BATTERY = 0x0D

@Field final int DP_TYPE_BOOL = 0x01
@Field final int DP_TYPE_VALUE = 0x02
@Field final int DP_TYPE_ENUM = 0x04

@Field final int DP_COMMAND_OPEN = 0x00
@Field final int DP_COMMAND_STOP = 0x01
@Field final int DP_COMMAND_CLOSE = 0x02
@Field final int DP_COMMAND_PAUSE = 0x02
@Field final int DP_COMMAND_CONTINUE = 0x03
@Field final int DP_COMMAND_LIFTPERCENT = 0x05
@Field final int DP_COMMAND_CUSTOM = 0x06

void parse(String description) {
    unschedule('deviceCommandTimeout')
    sendEvent(name:"lastCheckin", value: sdf().format((new Date()).getTime()))
    def now = new Date().getTime()
    Map lastTxMap = stringToJsonMap(state.lastTx)
    if(lastTxMap.waiting){
        def timeRunning = now.toInteger() - (lastTxMap.pingTime ?: '0').toInteger()
        if (timeRunning < MAX_PING_MILISECONDS) {
            sendRttEvent()
        }
    }
    setHealthStatusOnline()
    if (description == null || (!description.startsWith('catchall:') && !description.startsWith('read attr -'))) {
        logWarn "parse: Unhandled description=${description}"
        return null
    }

    final Map descMap = zigbee.parseDescriptionAsMap(description)
    if (descMap.clusterInt != CLUSTER_TUYA) {
        parseNonTuyaMessage(descMap)
        return null
    }
    final int command = zigbee.convertHexToInt(descMap.command)
    switch (command) {
        case ZIGBEE_COMMAND_SET_DATA_RESPONSE: // 0x02
        case ZIGBEE_COMMAND_REPORTING : // 0x01
            if (!descMap?.data || descMap.data.size() < 7) {
                logWarn "parse: Invalid data size for SET_DATA_RESPONSE descMap=${descMap}"
                return null
            }
            parseSetDataResponse(descMap)
            break
        case ZIGBEE_COMMAND_ACK: // 0x0B
            if (!descMap?.data || descMap.data.size() < 2) {
                logWarn "parse: Invalid data size for ACK descMap=${descMap}"
                return null
            }
            logDebug "parse: ACK command=${descMap.data[0]}"
            break
        case 0x10 : // TUYA_MCU_VERSION_REQ
            logDebug "Tuya MCU Version Request : ${descMap?.data}"
            break
        case 0x11 : // TUYA_MCU_VERSION_RSP
            logDebug "Tuya MCU Version Respinse : ${descMap?.data}"
            break
        case ZIGBEE_COMMAND_SET_TIME: // 0x24
            processTuyaSetTime()
            break
        default:
            logWarn "parse: Unhandled command=${command} descMap=${descMap}"
            break
    }
}

void parseNonTuyaMessage(final Map descMap) {
    if (descMap == null) {
        return
    }
    if (descMap.cluster == '0400' && descMap.attrId == '0000') {
        final int rawLux = Integer.parseInt(descMap.value, 16)
        illuminanceEvent(rawLux)
    }
    else if (descMap?.clusterId == '0102' || descMap?.cluster == '0102') {
        // windowCovering standard cluster
        logDebug "windowCovering standard cluster descMap=${descMap}"
        if (descMap?.command == '01' || descMap?.command == '0A') {     //read attribute response or reportResponse
            if (descMap?.data != null &&  descMap?.data[2] == '86') {
                logWarn "read attribute response: unsupported cluster ${descMap?.clusterId} attribute ${descMap.data[1] + descMap.data[0]} (status ${descMap.data[2]})"
            }
            else {
                if (descMap?.attrId == '0008') {
                    int dataValue = zigbee.convertHexToInt(descMap?.value)
                    logInfo "WindowCovering cluster ${descMap?.cluster} attribute CurrentPositionLiftPercentage ${descMap?.attrId} value : ${dataValue}"
                    restartPositionReportTimeout()
                    updateWindowShadeArrived(dataValue)
                    updatePosition(dataValue)
                }
                else if (descMap?.attrId == 'F000') {
                    int val = zigbee.convertHexToInt(descMap?.value)
                    logInfo "WindowCovering cluster ${descMap?.cluster} attribute PositionState ${descMap?.attrId} value : ${val} - <b>${MOVING_MAP[val]}</b>"
                    // TODO !
                }
                else if (descMap?.attrId == 'F001') {
                    logInfo "WindowCovering cluster ${descMap?.cluster} attribute UpDownConfirm ${descMap?.attrId} value : ${zigbee.convertHexToInt(descMap?.value)}"
                    // TODO !
                }
                else if (descMap?.attrId == 'F002') {
                    logInfo "WindowCovering cluster ${descMap?.cluster} attribute ControlBack ${descMap?.attrId} value : ${zigbee.convertHexToInt(descMap?.value)}"
                    // TODO !
                }
                else if (descMap?.attrId == 'F003') {
                    logInfo "WindowCovering cluster ${descMap?.cluster} attribute ScheduleTime ${descMap?.attrId} value : ${zigbee.convertHexToInt(descMap?.value)}"
                    // TODO!
                }
                else {
                    logInfo "read attribute response: cluster ${descMap?.cluster} attribute ${descMap?.attrId} value : ${zigbee.convertHexToInt(descMap?.value)}"
                }
            }
        }
        else if (descMap?.command == '04') {    //write attribute response
            if (descMap.data[0] == '86') {
                logWarn "writeAttributeResponse: unsupported cluster ${descMap?.clusterId} attribute ${descMap.data[2] + descMap.data[1]} (status ${descMap.data[0]})"
            }
            else {
                logInfo "writeAttributeResponse: cluster ${descMap?.clusterId} <b>OK</b> (data : ${descMap.data})"
            }
        }
        else if (descMap?.command == '0B') {    // ZCL Command Response
            logDebug "ZCL Default Response to command ${descMap.data[0]} for cluster:${descMap.clusterId} , data=${descMap.data} (Status: ${descMap.data[1] == '00' ? 'Success' : '<b>Failure</b>'})"
        }
        else {
            logWarn "windowCovering unprocessed command ${descMap?.command}"
        }
    }
    else if ((descMap?.clusterId == '0006' || descMap?.cluster == '0006') && descMap?.attrId == '8001' && descMap?.command in ['01', '0A']) {
        logInfo "OnOff cluster ${descMap?.cluster} attribute IndicatorMode ${descMap?.attrId} value : ${zigbee.convertHexToInt(descMap?.value)}"
    }
    else if ((descMap?.clusterId == '0013' || descMap?.cluster == '0013') && descMap?.profileId == '0000') {
        logDebug "ZDO cluster ${descMap?.clusterId} device announcement Device network ID: ${descMap.data[2]}${descMap.data[1]}, Capability Information: ${descMap.data[11]}"
    }
    else {
        logDebug "parse: Not a Tuya Message descMap=${descMap}"
    }
}

/*
 * Data (sending and receiving) generally have this format:
 * [2 bytes] (packet id)
 * [1 byte] (dp ID)
 * [1 byte] (dp type)
 * [2 bytes] (fnCmd length in bytes)
 * [variable bytes] (fnCmd)
 *
 * https://developer.tuya.com/en/docs/iot-device-dev/zigbee-curtain-switch-access-standard?id=K9ik6zvra3twv
 */
void parseSetDataResponse(final Map descMap) {
    logDebug "parse: descMap=${descMap}"
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def data = descMap.data
    int dp = zigbee.convertHexToInt(data[2])
    int dataValue = zigbee.convertHexToInt(data[6..-1].join())
    switch (dp) {
        case 0x01 :      // DP_ID_COMMAND
            restartPositionReportTimeout()
            if (dataValue == getDpCommandOpen()) {        // OPEN - typically 0x00
                logDebug("parse (01): opening (DP=1, data=${dataValue})")
                updateWindowShadeOpening()
            }
            else if (dataValue == getDpCommandStop()) {    // STOP - typically 0x01
                logDebug("parse (01): stopping (DP=1, data=${dataValue})")
            }
            else if (dataValue == getDpCommandClose()) {   // CLOSE - typically 0x02
                logDebug("parse (01): closing (DP=1, data=${dataValue})")
                updateWindowShadeClosing()
            }
            else if (dataValue == DP_COMMAND_CONTINUE) {   // CONTINUE - 0x03
                logDebug("parse (01): continuing (DP=1, data=${dataValue})")
            }
            else {
                logWarn "parse (01): Unexpected DP_ID_COMMAND dataValue=${dataValue}"
            }
            break

        case 0x02 :     // DP_ID_TARGET_POSITION:   // for M515EGBZTN blinds models - this is ALSO the actual/current position !
            if (dataValue >= 0 && dataValue <= 100) {
                if (invertPosition == true) {
                    dataValue = 100 - dataValue
                }
                restartPositionReportTimeout()
                if (settings?.mixedDP2reporting == true) {
                    if (state.isTargetRcvd == false) {        // only for setPosition commands; Open and Close do not report the target pos!
                        if (dataValue == state.target) {
                            logDebug("parse (02): received target ${dataValue} from mixedDP2reporting device")
                        }
                        else {
                            logWarn "parse (02): received target ${dataValue} from mixedDP2reporting device, <b>set target was ${state.target}</b>"
                        }
                        state.isTargetRcvd = true
                    }
                    else {
                        logDebug "parse (02): reveived current position ${dataValue} from mixedDP2reporting device"
                        logDebug("parse (02): moved to position ${dataValue}")
                        updateWindowShadeMoving(dataValue)
                        updatePosition(dataValue)
                    }
                }
                else {                                        // for all other models - this is the Target position
                    logDebug("parse (02): received target ${dataValue}")
                    state.isTargetRcvd = true
                    sendEvent(name: 'targetPosition', value: dataValue, type: 'physical')
                }
            }
            else {
                logWarn "parse (02): Unexpected DP_ID_TARGET_POSITION dataValue=${dataValue}"
            }
            break

        case 0x03 :     // DP_ID_CURRENT_POSITION:  Current Position or Moes Cover Calibration
            if (isMoesCoverSwitch()) {
                logDebug("parse (03): Moes Cover Calibration DP ${dp} value = ${dataValue}")
            }
            else {
                if (dataValue >= 0 && dataValue <= 100) {
                    if (invertPosition == true) {
                        dataValue = 100 - dataValue
                    }
                    logDebug("parse (03): arrived at position ${dataValue}")    // for AM43 and ZM85 this is received just once, when arrived at the destination point!
                    if (isZM85EL()) {
                        stopPositionReportTimeout()
                    }
                    else { // TODO - same filtering for AM43 !
                        restartPositionReportTimeout()
                    }
                    updateWindowShadeArrived(dataValue)
                    updatePosition(dataValue)
                } else {
                    logWarn "parse (03): Unexpected DP_ID_CURRENT_POSITION dataValue=${dataValue}"
                }
            }
            break
        case 0x04 :     // working mode {�range�:[�morning�,�night�],�type�:�enum�}
            logDebug("parse (04): isZM85EL=${isZM85EL()} mode = ${ZM85EL_MODE[dataValue as int]} (value = ${dataValue})")
            break

        case 0x05 :     //DP_ID_DIRECTION:  Motor Direction (0-forward 1-backward), enum
            String directionText = DIRECTION_MAP[dataValue]
            if (directionText != null) {
                logDebug("parse (05): Motor Direction=${directionText}")
                updateDirection(dataValue)
            } else {
                logWarn "parse (05): Unexpected DP_ID_DIRECTION dataValue=${dataValue}"
            }
            break

        case 0x06:      // 0x06: Arrived at destination (with fncmd==0)
            logDebug "parse (06): Arrived at destination (dataValue==${dataValue})"
            break

        case 0x07 :     //DP_ID_COMMAND_REMOTE: Remote Command / work_state  (or Moes Curtain switch Backlight?)
            if (isMoesCoverSwitch()) {
                logDebug("parse (07): Moes Curtain switch Backlight DP ${dp} value = ${dataValue}")
            }
            else if (isZM85EL()) {
                logDebug("parse (07): moving from ZM85 up/down keys (data=${dataValue})")
                updateWindowShadeUndefined()
            }
            else {
                if (dataValue == 0) {
                    // _TZE200_68nvbio9 sends 0x00 on both open and close commands from the remote! :(
                    logDebug("parse (07): movement command from remote (data=${descMap.data} dataValue=${dataValue})")
                    // we do not know in which direction the motor is moving ..
                    sendEvent(name:'windowShade', value: 'moving', type: 'physical')
                    sendEvent(name:'targetPosition', value: '?', type: 'digital')
                    logInfo "movement command from remote (${dataValue})"
                    //updateWindowShadeOpening()
                } /*else if (dataValue == 1) {
                    logDebug("parse (07): closing from remote (data=${descMap.data} dataValue=${dataValue})")
                    updateWindowShadeClosing()
                } */else {
                    logWarn "parse (07): Unexpected DP_ID_COMMAND_REMOTE (data=${descMap.data} dataValue=${dataValue})"
                }
                restartPositionReportTimeout()
            }
            break
        case 0x08:      // also, for unknown curtain motors : Countdown  {�range�:[�cancel�,�1h�,�2h�,�3h�,�4h�],�type�:�enum�}
            logDebug("parse (08): Moes motor reversal DP ${dp} value = ${dataValue}")    // isMoesCoverSwitch()
            break

        case 0x09:      // for unknown curtain motors : Left time (Display the remaining time of the countdown) {�unit�:�s�,�min�:0,�max�:86400,�scale�:0,�step�:1,�type� :�value�}
            logDebug("parse (09): Moes motor reversal DP ${dp} value = ${dataValue}")
            break

        case 0x0B :     // (11) situation_set - enum ["fully_open", "fully_close"]
            logDebug("parse (11): isZM85EL=${isZM85EL()} situation_set = ${ZM85EL_SITUATION_SET[dataValue as int]} (value = ${dataValue})")
            break

        case 0x0C :     // (12) fault -     Bitmap
            logDebug("parse (12): fault code (DP ${dp}) value = ${dataValue}")
            break

        case 0x0D :     // (13) DP_ID_BATTERY:  Battery
            if (dataValue >= 0 && dataValue <= 100) {
                logDebug("parse (13): battery=${dataValue}")
                updateBattery(dataValue)
            } else {
                logWarn "parse (13): Unexpected DP_ID_BATTERY dataValue=${dataValue}"
            }
            break

        case 0x0E :     // (14) indicator light status for Moes Curtain switch ? {�range�:[�relay�,�pos�,�none�],�type�:�enum�}
            logDebug  "parse (14): indicator light status for Moes Curtain switch value = ${dataValue}"
            break

        case 0x10 :     // (16) ZM85EL_BORDER
            logDebug("parse (16): isZM85EL=${isZM85EL()} BORDER = ${ZM85EL_BORDER[dataValue as int]} (value = ${dataValue})")
            break

        case 0x13 :     // (19) position_best - Integer    (0..100)
            logDebug("parse (19): position_best (DP ${dp}) value = ${dataValue}")
            break

        case 0x14 :     // (20) click_control (2)    Enum     ["up", "down"]
            logDebug("parse (20): isZM85EL=${isZM85EL()} click_control = ${ZM85EL_CLICK_CONTROL[dataValue as int]} (value = ${dataValue})")
            break

        case 0x65 :     // (101) DP_ID_MODE:    //  enum 0: tilt , 1: lift
            String modeText = MODE_MAP[dataValue]
            if (modeText != null) {
                logDebug("parse (101): mode=${modeText}")
                updateMode(dataValue)
            } else {
                logWarn "parse: Unexpected DP_ID_MODE dataValue=${dataValue}"
            }
            break

        case 0x66 :     // (102) AM43 factory reset
            logDebug('parse (102): AM43 factory reset')
            break

        case 0x67 :      // (103) ZM25TQ UP limit (when direction is Forward; probably reversed in Backward direction?) // AM43 'set_upper_limit'
            logDebug("parse (103): ZM25TQ/AM43 Up limit was ${dataValue == 0 ? 'reset' : 'set'} (direction:${settings.direction}) (raw:${dataValue})")
            break

        case 0x68 :      // (104) ZM25TQ Middle limit   // AM43 'set_bottom_limit'
            logDebug("parse (104): ZM25TQ Middle / AM43 bottom limit was ${dataValue == 0 ? 'reset' : 'set'} (direction:${settings.direction}) (raw:${dataValue})")
            break

        case 0x69 :     // (105) DP_ID_SPEED: // Motor speed or ZM25TQ Down limit   // AM43 motor_speed
            if (isZM25TQ()) {
                logDebug("parse (105): ZM25TQ Down limit was ${dataValue == 0 ? 'reset' : 'set'} (direction:${settings.direction})")
            }
            else {      // Motor speed for AM43
                if (dataValue >= 0 && dataValue <= 100) {
                    logDebug("parse (105): speed=${dataValue}")
                    updateSpeed(dataValue)
                } else {
                    logWarn "parse (105): Unexpected DP_ID_SPEED dataValue=${dataValue}"
                }
            }
            break

        case 0x6A: // 106
            logDebug("parse (106): ZM25TQ motor mode (DP=${dp}) value = ${dataValue}")
            break

        default:
            logWarn "parse: Unknown DP_ID dp=0x${data[2]}, dataType=0x${data[3]} dataValue=${dataValue}"
            break
    }
}

void processTuyaSetTime() {
    logDebug('time synchronization request')    // every 61 minutes
    int offset = 0
    try {
        offset = location.getTimeZone().getOffset(new Date().getTime())
    }
    catch (e) {
        log.error "${device.displayName} cannot resolve current location. please set location in Hubitat location setting. Setting timezone offset to zero"
    }
    List<String> cmds = zigbee.command(CLUSTER_TUYA, ZIGBEE_COMMAND_SET_TIME, '0008' + zigbee.convertToHexString((int)(now() / 1000), 8) + zigbee.convertToHexString((int)((now() + offset) / 1000), 8))
    logDebug "sending time data : ${cmds}"
    cmds.each { sendHubCommand(new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE)) }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private boolean ignorePositionReport(int position) {
    int lastPosition = device.currentValue('position')
    lastPosition = lastPosition != null ? lastPosition : INVALID_POSITION
    logDebug("ignorePositionReport: position=${position}, lastPosition=${lastPosition}")
    if (lastPosition == INVALID_POSITION || isWithinOne(position)) {
        logDebug 'Ignore invalid reports'
        return true
    }
    return false
}

private boolean isWithinOne(int position) {
    int  lastPosition = device.currentValue('position')
    lastPosition = lastPosition != null ? lastPosition : INVALID_POSITION
    if (lastPosition != INVALID_POSITION && Math.abs(position - lastPosition) <= 1) {
        logDebug "isWithinOne:true (position=${position}, lastPosition=${lastPosition})"
        return true
    }
    logDebug "isWithinOne:false (position=${position}, lastPosition=${lastPosition})"
    return false
}

private void updateDirection(int directionValue) {
    String directionText = DIRECTION_MAP[directionValue]
    logDebug("updateDirection: directionText=${directionText}, directionValue=${directionValue}")
    if (directionValue != (settings?.direction as int)) {
        setDirection()
    }
}

private void updateMode(int modeValue) {
    String modeText = MODE_MAP[modeValue]
    logDebug("updateMode: modeText=${modeText}, modeValue=${modeValue}")
    if (modeValue != (settings?.mode as int)) {
        setMode()
    }
}

private void updatePosition(final int position) {
    logDebug "updatePosition(): position=${position}"
    sendEvent(name: 'position', value: position, unit: '%')
    sendEvent(name: 'level', value: position, unit: '%')
    if (position <= maxClosedPosition) {
        sendEvent(name:'switch', value: 'off')
    }
    else {
        sendEvent(name:'switch', value: 'on')
    }
    if (isWithinOne(position)) {
        logDebug "updatePosition(${position}): <b>arrived!</b>"
        updateWindowShadeArrived(position)
        stopPositionReportTimeout()    // added 12/30/2022
    }
}

private void updateSpeed(final int speed) {
    logDebug("updateSpeed: speed=${speed}")
    sendEvent(name: 'speed', value: speed)
}

private void updateBattery(final int battery) {
    logInfo("battery is ${battery} %")
    sendEvent(name: 'battery', value: battery)
}

private void updateWindowShadeMoving(int position) {
    int lastPosition = device.currentValue('position') ?: 0
    logDebug("updateWindowShadeMoving: position=${position} (lastPosition=${lastPosition}), target=${state.target}")
    if (lastPosition < position) {
        updateWindowShadeOpening()
    } else if (lastPosition > position) {
        updateWindowShadeClosing()
    }
}

private void updateWindowShadeOpening() {
    logDebug 'updateWindowShadeOpening()'
    if ((device.currentValue('windowShade') ?: 'undefined') != 'opening') {
        sendEvent(name:'windowShade', value: 'opening')
        logInfo 'is opening'
    }
}

private void updateWindowShadeClosing() {
    logDebug 'updateWindowShadeClosing()'
    if ((device.currentValue('windowShade') ?: 'undefined') != 'closing') {
        sendEvent(name:'windowShade', value: 'closing')
        logInfo 'is closing'
    }
}

private void updateWindowShadeUndefined() {
    logDebug 'updateWindowShadeUndefined()'
    if ((device.currentValue('windowShade') ?: 'undefined') != 'moving') {
        sendEvent(name:'windowShade', value: 'moving')
        logInfo 'is moving'
    }
}

// called from updatePosition(), setPosition(), endOfMovement()
private void updateWindowShadeArrived(int positionParam=null) {
    int position = positionParam
    if (position == null)  {
        position = device.currentValue('position') ?: INVALID_POSITION
    }
    logDebug("updateWindowShadeArrived: position=${position}")
    if (position == INVALID_POSITION || position < 0 || position > 100) {
        logWarn 'updateWindowShadeArrived: Need to setup limits on device'
        sendEvent(name: 'windowShade', value: 'unknown')
        logInfo 'windowShade is unknown'
        stopPositionReportTimeout()    // added 12/30/2022
    } else if (position <= settings?.maxClosedPosition) {
        if ((device.currentValue('windowShade') ?: 'undefined') != 'closed') {
            sendEvent(name: 'windowShade', value: 'closed')
            logInfo 'is closed'
            stopPositionReportTimeout()    // added 12/30/2022
        }
    } else if (position >= settings?.minOpenPosition) {
        if ((device.currentValue('windowShade') ?: 'undefined') != 'open') {
            sendEvent(name: 'windowShade', value: 'open')
            logInfo 'is open'
            stopPositionReportTimeout()    // added 12/30/2022
        }
    } else {
        if ((device.currentValue('windowShade') ?: 'undefined') != 'partially open') {
            sendEvent(name: 'windowShade', value: 'partially open')
            logInfo "is partially open ${position}%"
        }
    }
}

//
// Actions
//

void refresh() {
    logDebug 'Refresh called...'
    sendZigbeeCommands(zigbee.onOffRefresh())
}

void close() {  // 0 %
    if (mode == MODE_TILT || settings?.substituteOpenClose == true) {
        logDebug("sending command close ${settings?.substituteOpenClose == true ? 'substituted with setPosition(0)' : 'MODE_TILT'} ")
        setPosition(0)
    }
    else {
        state.target = 0
        state.isTargetRcvd = true
        sendEvent(name: 'targetPosition', value: 0, type: 'digital')
        restartPositionReportTimeout()
        int dpCommandClose = getDpCommandClose()
        logDebug "sending command close (${dpCommandClose}), direction = ${direction as int}"
        if (isTS130F()) {
            sendZigbeeCommands(zigbee.command(0x0102, dpCommandClose as int, [destEndpoint:0x01], delay = 200))
        }
        else if (isZM85EL()) {    // situation_set
            //sendTuyaCommand(0x0B, DP_TYPE_ENUM, 0x01, 2)
            setPosition(0)
            //sendTuyaCommand(DP_ID_COMMAND, DP_TYPE_ENUM, 2, 2)
        }
        else {
            sendTuyaCommand(DP_ID_COMMAND, DP_TYPE_ENUM, dpCommandClose, 2)
        }
    }
}

void open() {   // 100 %
    if (mode == MODE_TILT || settings?.substituteOpenClose == true) {
        logDebug "sending command open : ${settings?.substituteOpenClose == true ? 'substituted with setPosition(100)' : 'MODE_TILT'} "
        setPosition(100)
    }
    else {
        state.target = 100
        state.isTargetRcvd = true
        sendEvent(name: 'targetPosition', value: 100, type: 'digital')
        restartPositionReportTimeout()
        int dpCommandOpen = getDpCommandOpen()
        logDebug "sending command open (${dpCommandOpen}), direction = ${settings.direction as int}"
        if (isTS130F()) {
            sendZigbeeCommands(zigbee.command(0x0102, dpCommandOpen as int, [destEndpoint:0x01], delay = 200))
        }
        else if (isZM85EL()) {    // situation_set ?
            //sendTuyaCommand(0x0B, DP_TYPE_ENUM, 0x00, 2)
            setPosition(100)
            //sendTuyaCommand(DP_ID_COMMAND, DP_TYPE_ENUM, 0, 2)
        }
        else {
            sendTuyaCommand(DP_ID_COMMAND, DP_TYPE_ENUM, dpCommandOpen, 2)
        }
    }
}

void on() {
    open()
}

void off() {
    close()
}

void startPositionChange(final String state) {
    logDebug("startPositionChange: ${state}")
    switch (state) {
        case 'close' :
            close()
            break
        case 'open' :
            open()
            break
        default :
            throw new Exception("Unsupported startPositionChange state \"${state}\"")
    }
}

void stopPositionChange() {
    int dpCommandStop = getDpCommandStop()
    logDebug "sending command stopPositionChange (${dpCommandStop})"
    restartPositionReportTimeout()
    sendEvent(name: 'targetPosition', value: '?', type: 'digital')
    if (isTS130F()) {
        //sendZigbeeCommands(zigbee.command(0x0102, DP_COMMAND_PAUSE as int, zigbee.convertToHexString(DP_COMMAND_PAUSE, 1)))
        sendTuyaCommand2(0x01, "")
        //sendZigbeeCommands(zigbee.command(0x0102, 0x02))
    }
    else {
        sendTuyaCommand(DP_ID_COMMAND, DP_TYPE_ENUM, dpCommandStop, 2)
    }
}

void sendTuyaCommand2(Integer command, String payload) {
  Random rnd = new Random()
  String fullPayload = "00${HexUtils.integerToHexString(rnd.nextInt(255),1)}" + payload
  sendZigbeeCommands(zigbeeCommand(0x01, 0x0102, command, 101, fullPayload))
}

ArrayList<String> zigbeeCommand(Integer endpoint, Integer cluster, Integer command, int delay = 203, String... payload) {
    zigbeeCommand(endpoint, cluster, command, [:], delay, payload)
}

ArrayList<String> zigbeeCommand(Integer endpoint, Integer cluster, Integer command, Map additionalParams, int delay = 204, String... payload) {
    String mfgCode = ""
    if(additionalParams.containsKey("mfgCode")) {
        mfgCode = " {${HexUtils.integerToHexString(HexUtils.hexStringToInt(additionalParams.get("mfgCode")), 2)}}"
    }
    String finalPayload = payload != null && payload != [] ? payload[0] : ""
    String cmdArgs = "0x${device.deviceNetworkId} 0x${HexUtils.integerToHexString(endpoint, 1)} 0x${HexUtils.integerToHexString(cluster, 2)} " + 
                       "0x${HexUtils.integerToHexString(command, 1)} " + 
                       "{$finalPayload}" + 
                       "$mfgCode"
    ArrayList<String> cmd = ["he cmd $cmdArgs", "delay $delay"]
    return cmd
}

/* groovylint-disable-next-line UnusedMethodParameter */
void setLevel(BigDecimal level, BigDecimal duration = null) {
    setPosition(level)
}

void setPosition(final BigDecimal positionParam) {
    int position = positionParam as int
    if (position == null || position < 0 || position > 100) {
        throw new Exception("Invalid position ${position}. Position must be between 0 and 100 inclusive.")
    }

    int percentageAdjustOpen = settings?.percentageAdjustOpen as Integer
    int percentageAdjustClose = settings?.percentageAdjustClose as Integer
    int actualPercentageAdjust = 0;
    int lastPosition = device.currentValue('position') ?: 0
    if (lastPosition < position) { 
        actualPercentageAdjust = percentageAdjustOpen 
    } else {
        actualPercentageAdjust = percentageAdjustClose 
    }
    position = position + actualPercentageAdjust;
    if (position < 0) {position = 0} else if(position > 100) {position = 100}

    state.target = position
    sendEvent(name: 'targetPosition', value: position, type: 'digital')
    if (isWithinOne(position)) {
        logDebug('setPosition: no need to move!')
        updateWindowShadeArrived(position)
        state.isTargetRcvd = false
        return null
    }
    updateWindowShadeMoving(position)
    logDebug("setPosition: target is ${position}, currentPosition=${device.currentValue('position')}")
    if (invertPosition == true) {
        position = 100 - position
    }
    restartPositionReportTimeout()
    state.isTargetRcvd = false

    if (isTS130F()) {
        sendZigbeeCommands(zigbee.command(0x0102, DP_COMMAND_LIFTPERCENT as int, zigbee.convertToHexString(position.intValue(), 1)))
    }else{
        sendTuyaCommand(DP_ID_TARGET_POSITION, DP_TYPE_VALUE, position.intValue(), 8)
    }
}

void restartPositionReportTimeout() {
    int timeout = settings?.positionReportTimeout as Integer
    if (timeout > 100) { // milliseconds
        runInMillis(timeout, endOfMovement, [overwrite: true])
    }
    else {
        stopPositionReportTimeout()
    }
}

void stopPositionReportTimeout() {
    logDebug 'stopPositionReportTimeout(): unscheduling endOfMovement timer'
    unschedule(endOfMovement)
}

void stepClose(final BigDecimal stepParam=settings?.defaultStepAmount) {
    BigDecimal step = Math.max(MIN_STEP, stepParam?.doubleValue())
    if (isZM85EL()) {
        setZM85ClickControlDown()
    }
    else {
        logDebug("stepClose: step=${step}")
        BigDecimal position = Math.max(0, Math.min(100, (device.currentValue('position')?.doubleValue() - step.doubleValue())))
        sendEvent(name: 'targetPosition', value: position, unit: '%', type: 'digital') 
        setPosition(position)
    }
}

void stepOpen(final BigDecimal stepParam=settings?.defaultStepAmount) {
    BigDecimal step = Math.max(MIN_STEP, stepParam?.doubleValue())
    if (isZM85EL()) {
        setZM85ClickControlUp()
    }
    else {
        logDebug("stepOpen: step=${step}")
        BigDecimal position = Math.max(0, Math.min(100, (device.currentValue('position')?.doubleValue() + step.doubleValue())))
        sendEvent(name: 'targetPosition', value: position, unit: '%', type: 'digital') 
        setPosition(position)
    }
}

void setSpeed(final BigDecimal speed) {
    logDebug("setSpeed: speed=${speed}")
    if (speed < 0 || speed > 100) {
        throw new Exception("Invalid speed ${speed}. Speed must be between 0 and 100 inclusive.")
    }
    sendTuyaCommand(DP_ID_SPEED, DP_TYPE_ENUM, speed.intValue(), 8)
}

void push(final BigDecimal buttonNumber) {
    logDebug "push: buttonNumber=${buttonNumber}"
    sendEvent(name: 'pushed', value: buttonNumber, isStateChange: true)
    switch (buttonNumber) {
        case 1:
            open()
            break
        case 2:
            close()
            break
        case 3:
            stopPositionChange()
            break
        case 4:
            stepOpen()
            break
        case 5:
            stepClose()
            break
        default:
            throw new Exception("Unsupported buttonNumber \"${buttonNumber}\"")
    }
}

// scheduled from restartPositionReportTimeout()
void endOfMovement() {
    logWarn 'endOfMovement() timeout!'
    updateWindowShadeArrived(device.currentValue('position', true) as int)
}

//
// Helpers
//
private void sendTuyaCommand(int dp, int dpType, int fnCmd, int fnCmdLength) {
    state.waitingForResponseSinceMillis = now()

    String dpHex = zigbee.convertToHexString(dp, 2)
    String dpTypeHex = zigbee.convertToHexString(dpType, 2)
    String fnCmdHex = zigbee.convertToHexString(fnCmd, fnCmdLength)
    logDebug "sendTuyaCommand: dp=0x${dpHex}, dpType=0x${dpTypeHex}, fnCmd=0x${fnCmdHex}, fnCmdLength=${fnCmdLength}"
    String message = (randomPacketId().toString()
                   + dpHex
                   + dpTypeHex
                   + zigbee.convertToHexString((fnCmdLength / 2) as int, 4)
                   + fnCmdHex)
    sendZigbeeCommands(zigbee.command(CLUSTER_TUYA, ZIGBEE_COMMAND_SET_DATA, message))
}

private String randomPacketId() {
    return zigbee.convertToHexString(new Random().nextInt(65536), 4)
}

private void logInfo(final String text) {
    if (!enableInfoLog) {
        return
    }
    log.info "${device.displayName} " + text
}

private void logDebug(final String text) {
    if (!enableDebugLog) {
        return
    }
    log.debug "${device.displayName} " + text
}

private void logWarn(final String text) {
    if (!enableDebugLog) {
        return
    }
    log.warn "${device.displayName} " + text
}

/* groovylint-disable-next-line MethodParameterTypeRequired,  NoDef */
Integer safeToInt(val, Integer defaultVal=0) {
    return "${val}"?.isInteger() ? "${val}".toInteger() : defaultVal
}

/* groovylint-disable-next-line MethodParameterTypeRequired, NoDef, NoDouble */
Double safeToDouble(val, Double defaultVal=0.0) {
    return "${val}"?.isDouble() ? "${val}".toDouble() : defaultVal
}

void sendZigbeeCommands(List<String> cmd) {
    logDebug "<b>sendZigbeeCommands</b> (cmd=$cmd)"
    hubitat.device.HubMultiAction allActions = new hubitat.device.HubMultiAction()
    cmd.each {
            allActions.add(new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE))
            if (state.txCounter != null) { state.txCounter = state.txCounter + 1 } else { state.txCounter = 1 }
    }
    sendHubCommand(allActions)
}

void setNotImplemented() {
    logWarn "not implemented!"
}

//---------------------
void setTS130FCalibrationTime(int val) {
    int calibrationTime = val * 10
    logInfo "changing moesCalibrationTime to : ${val} seconds (raw ${calibrationTime})"
    List<String> cmd = zigbee.writeAttribute(0x0102, moesCalibrationTime, DataType.UINT16, calibrationTime as int, [destEndpoint:0x01], delay = 200) + zigbee.readAttribute(0x0102, moesCalibrationTime, [destEndpoint:0x01], delay = 200)
    sendZigbeeCommands(cmd)
    sendEvent(name: 'calibrationTime', value: val)
}

void setTS130FCalibrationOn() {
    logInfo "setting TS130F Calibration<b>On</b>}"                    // On is 0 ?
    List<String> cmd =  zigbee.writeAttribute(0x0102, tuyaCalibration, DataType.ENUM8, 1, [destEndpoint:0x01], delay = 200) + zigbee.readAttribute(0x0102, tuyaCalibration, [destEndpoint:0x01], delay = 200)
    sendZigbeeCommands(cmd)	
}

void setTS130FCalibrationOff() {
    logInfo "setting TS130F Calibration<b>Off</b>"                  // Off is 1 ?
    List<String> cmd = zigbee.writeAttribute(0x0102, tuyaCalibration, DataType.ENUM8, 0, [destEndpoint:0x01], delay = 200) + zigbee.readAttribute(0x0102, tuyaCalibration, [destEndpoint:0x01], delay = 200)
    sendZigbeeCommands(cmd)
}

void setMoesCalibrationOn()    { logInfo "setting setMoesCalibration<b>On</b>"; sendTuyaCommand(0x03, DP_TYPE_ENUM, 0x00, 2) }
void setMoesCalibrationOff()   { logInfo "setting setMoesCalibration<b>Off</b>"; sendTuyaCommand(0x03, DP_TYPE_ENUM, 0x01, 2) }
void setMoesBacklightOn()      { logInfo "setting Moes Backlight <b>On</b>"; sendTuyaCommand(0x07, DP_TYPE_BOOL, 0x01, 2) }
void setMoesBacklightOff()     { logInfo "setting Moes Backlight <b>Off</b>"; sendTuyaCommand(0x07, DP_TYPE_BOOL, 0x00, 2) }
void setMoesMotorReversalOn()  { logInfo "setting Moes Motor Reversal <b>On</b>"; sendTuyaCommand(0x08, DP_TYPE_ENUM, 0x01, 2) }
void setMoesMotorReversalOff() { logInfo "setting Moes Motor Reversal <b>Off</b>"; sendTuyaCommand(0x08, DP_TYPE_ENUM, 0x00, 2) }
void setZM85UpperLimit()       { logInfo "setting ZM85 Upper limit"; sendTuyaCommand(0x10, DP_TYPE_ENUM, 0x00, 2) }
void setZM85LowerLimit()       { logInfo "setting ZM85 Lower limit"; sendTuyaCommand(0x10, DP_TYPE_ENUM, 0x01, 2) }
void removeZM85UpperLimit()    { logInfo "removing ZM85 Upper limit"; sendTuyaCommand(0x10, DP_TYPE_ENUM, 0x02, 2) }
void removeZM85LowerLimit()    { logInfo "removing ZM85 Lower limit"; sendTuyaCommand(0x10, DP_TYPE_ENUM, 0x03, 2) }
void removeZM85TopBottom()     { logInfo "removing ZM85 both Top and Bottom limits"; sendTuyaCommand(0x10, DP_TYPE_ENUM, 0x04, 2) }
void setZM85ClickControlUp()   { logInfo "ZM85 Click Control <b>Up</b>";   sendTuyaCommand(0x14, DP_TYPE_ENUM, 0x00, 2) }
void setZM85ClickControlDown() { logInfo "ZM85 Click Control <b>Down</b>"; sendTuyaCommand(0x14, DP_TYPE_ENUM, 0x01, 2) }
void setZM85ModeMorning()      { logInfo "ZM85 Mode <b>Morning</b>";    sendTuyaCommand(0x04, DP_TYPE_ENUM, 0x00, 2) }
void setZM85ModeNight()        { logInfo "ZM85 Mode <b>Night</b>";  sendTuyaCommand(0x04, DP_TYPE_ENUM, 0x01, 2) }

@Field static final Map settableParsMap = [
    'TS130F Calibration Time': [ min: 1,   scale: 0, max: 99, step: 1,   type: 'number',   defaultValue: 7   , function: 'setTS130FCalibrationTime'],
    'TS130F Calibration On'  : [ type: 'none', function: 'setTS130FCalibrationOn'],
    'TS130F Calibration Off' : [ type: 'none', function: 'setTS130FCalibrationOff'],

    'Moes Calibration On'  : [ type: 'none', function: 'setMoesCalibrationOn'],
    'Moes Calibration Off' : [ type: 'none', function: 'setMoesCalibrationOff'],
    'Moes Backlight On'  : [ type: 'none', function: 'setMoesBacklightOn'],
    'Moes Backlight Off' : [ type: 'none', function: 'setMoesBacklightOff'],
    'Moes Motor Reversal On'  : [ type: 'none', function: 'setMoesMotorReversalOn'],
    'Moes Motor Reversal Off' : [ type: 'none', function: 'setMoesMotorReversalOff'],

    'ZM85 Set Upper Limit' : [ type: 'none', function: 'setZM85UpperLimit'],
    'ZM85 Set Lower Limit' : [ type: 'none', function: 'setZM85LowerLimit'],
    'ZM85 Remove Upper Limit' : [ type: 'none', function: 'removeZM85UpperLimit'],
    'ZM85 Remove Lower Limit' : [ type: 'none', function: 'removeZM85LowerLimit'],
    'ZM85 Remove Both Limits' : [ type: 'none', function: 'removeZM85TopBottom'],
    'ZM85 Mode Morning' : [ type: 'none', function: 'setZM85ModeMorning'],
    'ZM85 Mode Night' : [ type: 'none', function: 'setZM85ModeNight']
]

void calibrate(String par=null, String val=null ) {
    logDebug "calibrate ${par} ${val}"
    List<String> cmds = []
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def value = null
    Boolean validated = false
    if (par == null || !(par in (settableParsMap.keySet() as List))) {
        logWarn "calibrate: parameter <b>${par}</b> must be one of these : ${settableParsMap.keySet() as List}"
        return
    }
    if (settableParsMap[par]?.type != null && settableParsMap[par]?.type != 'none' ) {
        value = settableParsMap[par]?.type == 'number' ? safeToInt(val, -1) : safeToDouble(val, -1.0)
        if (value >= settableParsMap[par]?.min && value <= settableParsMap[par]?.max) { validated = true }
        if (validated == false) {
            log.warn "calibrate: parameter <b>par</b> value <b>${val}</b> must be within ${settableParsMap[par]?.min} and ${settableParsMap[par]?.max}"
            return
        }
    }
    //
    String func
    try {
        func = settableParsMap[par]?.function
        //def type = settableParsMap[par]?.type
        //device.updateSetting("$par", [value:value, type:type])
        if (value != null) {
            cmds = "$func"(value)
        }
        else {
            cmds = "$func"()
        }
    }

    catch (e) {
        logWarn "calibrate: Exception ${e} caught while processing <b>$func</b>(<b>$val</b>)"
        return
    }
    logDebug "calibrate: executed <b>$func</b>(<b>$val</b>)"
}

void test(final String par) {
    sendTuyaCommand2(par as int, "")
    logWarn "test: ${par}"
}

def setHealthStatusOnline() {
    if (!((device.currentValue('healthStatus') ?: 'unknown') in ['online']))  {
        sendHealthStatusEvent('online')
        runIn(defaultPollingInterval, deviceHealthCheck, [overwrite: true, misfire: 'ignore'])
    }
    state.notPresentCounter = 0
}

def deviceHealthCheck() {
    if (state.notPresentCounter != null) {
        state.notPresentCounter = state.notPresentCounter + 1
        if (state.notPresentCounter >= presenceCountTreshold) {
            if ((device.currentValue('healthStatus') ?: 'unknown') != 'offline' ) {
                log.warn 'not present!'
                sendHealthStatusEvent('offline')
            }
        }
    }
    else {
        state.notPresentCounter = 0
    }
    runIn(defaultPollingInterval, deviceHealthCheck, [overwrite: true, misfire: 'ignore'])
}

void sendHealthStatusEvent(value) {
    def descriptionText = "healthStatus changed to ${value}"
    sendEvent(name: 'healthStatus', value: value, descriptionText: descriptionText, isStateChange: true, isDigital: true)
    if (value == 'online') {
        logInfo "${descriptionText}"
    }
    else {
        if (settings?.txtEnable) { log.warn "${device.displayName}} <b>${descriptionText}</b>" }
    }
}

void resetStats() {
    Map lastTx = [waiting:false]
    state.lastTx =  mapToJsonString( lastTx )
    if (txtEnable == true) log.info "${device.displayName} Statistics were reset. Press F5 to refresh the device page"
}

void sendRttEvent(String value=null) {
    def now = new Date().getTime()
    Map lastTxMap = stringToJsonMap(state.lastTx)
    lastTxMap.waiting = false
    state.lastTx = mapToJsonString(lastTxMap)
    def timeRunning = now.toInteger() - (lastTxMap.pingTime ?: now).toInteger()
    def descriptionText = "Round-trip time is ${timeRunning} (ms)"
    if (value == null) {
        logInfo "${descriptionText}"
        sendEvent(name: 'rtt', value: timeRunning, descriptionText: descriptionText, unit: 'ms', isDigital: true)
    }
    else {
        descriptionText = "Round-trip time is ${value}"
        logInfo "${descriptionText}"
        sendEvent(name: 'rtt', value: value, descriptionText: descriptionText, isDigital: true)
    }
}

def ping() {
    logInfo 'ping...'
    scheduleCommandTimeoutCheck()
    Map lastTxMap = stringToJsonMap(state.lastTx)
    lastTxMap.waiting = true
    lastTxMap.pingTime = new Date().getTime()
    state.lastTx = mapToJsonString(lastTxMap)
    refresh()
 }

private void scheduleCommandTimeoutCheck(int delay = 10) {
    runIn(delay, 'deviceCommandTimeout')
}

@CompileStatic
String mapToJsonString( Map map) {
    if (map == null || map == [:]) return ''
    String str = JsonOutput.toJson(map)
    return str
}

@CompileStatic
Map stringToJsonMap( String str) {
    if (str == null) return [:]
    JsonSlurper jsonSlurper = new JsonSlurper()
    Map map = jsonSlurper.parseText( str ) as Map
    return map
}
