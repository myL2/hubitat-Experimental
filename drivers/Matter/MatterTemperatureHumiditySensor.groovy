metadata {
   definition (name: "Matter TempMerature and Humidity Sensor", namespace: "myL2", author: "SebyM") {
      capability "TemperatureMeasurement"
      capability "Configuration"
      capability "RelativeHumidityMeasurement"
   }

   preferences {
      // For some Matter devices, you may want to offer additional options for reporting configuration or some
      // manufacturer-specific options, etc. here
      input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
   }
}

def installed() {
   log.debug "installed()"
}

def updated() {
   log.debug "updated()"
   log.warn "debug logging is: ${logEnable == true}"
   log.warn "description logging is: ${txtEnable == true}"
   if (logEnable) runIn(1800, "logsOff")  // 1800 seconds = 30 minutes
   // In drivers that offer preferences for configuration, you might also iterate over
   // these here and send Matter commands as needed here (or call configure() and do it there)
}

// handler method for scheduled job to disable debug logging:
def logsOff(){
   log.warn "debug logging disabled..."
   device.updateSetting("logEnable", [value:"false", type:"bool"])
}

def parse(String description) {
   if (logEnable) log.debug "parse description: ${description}"
   def descMap = matter.parseDescriptionAsMap(description)
   // Parses hex (base 16) string data to Integer -- perhaps easier to work with:
   def rawValue
   switch (descMap.clusterInt) {
      case 0x0402: // On/Off
         if (descMap.attrInt == 0) {
            rawValue = Integer.parseInt(descMap.value, 16)
            String switchValue
            // attribute value of 0 means off, 1 (only other valid value) means on
            switchValue = (rawValue == 0) ? "off" : "on"
            if (txtEnable) log.info "${device.displayName} switch is ${switchValue}"
            // this is what actually generates the event:
            sendEvent(name: "switch", value: switchValue, descriptionText: "${device.displayName} switch is ${switchValue}")
         }
         else {
            if (logEnable) log.debug "0x0006:${descMap.attrId}"
         }

      // In other drivers, you may have other cases here
      // For example, case 0x0008 for level, etc.

      default:
         if (logEnable) log.debug "ignoring {descMap.clusterId}:${descMap.attrId}"
         break
   }
}

def configure() {
   List<String> cmds = []
   List<Map<String, String>> attributePaths = []
   attributePaths.add(matter.attributePath(0x01, 0x0402, 0x00))
   // other kinds of devices will likely need to add more paths here
   Integer reportingInterval = 5
   String subscribeCmd = matter.subscribe(reportingInterval,0xFFFF,attributePaths)
   cmds.add(subscribeCmd)
   return cmds // or delayBetween(cmds)
}

def on() {
   if (logEnable) log.debug "on()"
   matter.on()
}

def off() {
   if (logEnable) log.debug "off()"
   matter.off()
}