import groovy.transform.Field

metadata {
  definition(name: "Simon IO Roller Blind", namespace: "simonio", author: "Sergey Kolodyazhnyy") {
    capability "Actuator"
    capability "Configuration"
    capability "SwitchLevel"
    capability "Refresh"

    attribute "calibrated", "ENUM"
    attribute "state", "ENUM"

    command "open"
    command "close"
    command "stop"
    command "identify"
    command "calibrate"

    fingerprint deviceId: "0000", inClusters: "0x5E,0x86,0x25,0x26,0x72,0x5A,0x59,0x85,0x7A,0x73,0x70", mfr: "0267", prod: "0004", deviceJoinName: "Simon IO Roller Blind"
  }
  
  preferences {
    input name: "logEnable", type: "bool", title: "Enable logging", defaultValue: false
    input name: "associationLed", type: "bool", title: "Association LED", defaultValue: false
    input name: "reposeLed", type: "bool", title: "Behavior of LED in Repose", defaultValue: false
    input name: "lockInput", type: "enum", title: "Lock input", options: ["lock": "Physical buttons are disabled", "unlock": "Physical buttons are enabled"], defaultValue: "unlock"
    input name: "upTime", type: "number", title: "Up time (seconds)", defaultValue: 60
    input name: "downTime", type: "number", title: "Down time (seconds)", defaultValue: 60
  }
}


// Configuration parameter numbers
@Field final CONFIG_ASSOCIATION_LED = 1
@Field final CONFIG_UP_TIME = 4
@Field final CONFIG_DOWN_TIME = 5
@Field final CONFIG_REPOSE_LED = 12
@Field final CONFIG_LOCK_INPUT = 13
@Field final CONFIG_CALIBRATE = 14 // according to the docs it's 9, but it didn't work for me
@Field final CONFIG_IDENTIFY = 20
@Field final CONFIG_LOAD_STATE = 21
@Field final CONFIG_CALIBRATION_REQUIRED = 23

// Send command to open blinds all the way up
def open() {
  return setLevel(99, 0)
}

// Send command to close blinds all the way down
def close() {
  return setLevel(0, 0)
}

// Send command to set blinds to a particular level (0..99)
def setLevel(level, duration = null) {
  return [
    secure(zwave.switchMultilevelV1.switchMultilevelSet(value: level)),
    "delay 1000",
    secure(zwave.configurationV2.configurationGet(parameterNumber: CONFIG_LOAD_STATE)),
  ]
}

// Stop blinds movement, if blind is currently opening or closing
def stop() {
  return [
    secure(zwave.switchMultilevelV3.switchMultilevelStopLevelChange()),
    "delay 1000",
    secure(zwave.configurationV2.configurationGet(parameterNumber: CONFIG_LOAD_STATE)),
  ]
}

// Refreshes blind state and configuration
def refresh() {
  return [
    secure(zwave.basicV1.basicGet()),
    secure(zwave.configurationV2.configurationBulkGet(numberOfParameters: 23, parameterOffset: 1)),
    // Request parameter 21 separately to get correct load state and level
    secure(zwave.configurationV2.configurationGet(parameterNumber: CONFIG_LOAD_STATE)),
  ]
}

// Send configuration to the device
def configure() {
  return [
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_ASSOCIATION_LED, scaledConfigurationValue: associationLed ? 0xFF : 0x00)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_UP_TIME,         scaledConfigurationValue: upTime)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_DOWN_TIME,       scaledConfigurationValue: downTime)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_REPOSE_LED,      scaledConfigurationValue: reposeLed ? 0xFF : 0x00)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_LOCK_INPUT,      scaledConfigurationValue: lockInput == "lock" ? 0xFF : 0x00)),
  ]
}

// Make central led blink for a few seconds
def identify() {
  return secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_IDENTIFY, scaledConfigurationValue: 0xFF))
}

// Automatically adjust up and down time
def calibrate() {
  return secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_CALIBRATE, scaledConfigurationValue: 0xFF))
}

// Save configuration when user presses "Update Preferences"
def updated() {
  return configure()
}

// Refreshes only blind state (off/opening/closing) and level (0..99)
def refreshLoadState() {
  if (logEnable) log.debug "Refreshing state and level values"
  return secure(zwave.configurationV2.configurationGet(parameterNumber: CONFIG_LOAD_STATE))
}

// Set state and level as reported by configuration parameter 21 (load state)
void setLoadState(value) {
  int state = (long) value >> 8
  int level = (long) value % 256

  if (state == 0) {
    sendEvent(name: "state", value: "off", isStateChange: true, type: "physical")
    return;
  }

  sendEvent(name: "state", value: state == 1 ? "opening" : "closing", isStateChange: true, type: "physical")
  sendEvent(name: "level", value: level, isStateChange: true, type: "physical")

  runIn(2, refreshLoadState)
}

// Set calibration as reported by configuration parameter 23 (Calibration Required)
void setCalibrationState(value) {
  sendEvent(name: "calibrated", value: value == 0 ? "yes" : "no", isStateChange: true, type: "physical")
}

// This method is triggered every time hubitat receives a command from the device.
void parse(String description){
  if (logEnable) log.debug "parse description: ${description}"

  hubitat.zwave.Command cmd = zwave.parse(description, [
    0x20:2, // Basic Command Class V2
    0x70:2, // Configuration Command Class V2
    0x86:2, // VersionReport V2
])

  if (cmd) {
    zwaveEvent(cmd)
  }
}

// Construct secure zwave command from a string.
String secure(String cmd){
  return zwaveSecureEncap(cmd)
}

// Construct secure zwave command from a command object.
String secure(hubitat.zwave.Command cmd){
  return zwaveSecureEncap(cmd)
}

// BasicReport is received every time blind has finished changing position
void zwaveEvent(hubitat.zwave.commands.basicv2.BasicReport report){
  if (logEnable) log.debug "Blinders position changed to ${report.value + 1}"
  setLoadState(report.value);
}


// ConfigurationReport communicates configuration (and state) value for a single parameter.
//
// This driver requests configuration for parameter 21 (load state), shortly after level change is requested and 
// then periodically as long as state is not off.
void zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport report){
  if (logEnable) log.debug "Configuration parameter ${report.parameterNumber} changed to ${report.scaledConfigurationValue}"

  switch (report.parameterNumber) {
    // load state, readonly
    case CONFIG_LOAD_STATE:
      setLoadState(report.scaledConfigurationValue)
      break
    case CONFIG_CALIBRATION_REQUIRED:
      setCalibrationState(report.scaledConfigurationValue)
      break
  }
}

// ConfigurationBulkReport communicates configuration (and state) value for all parameters at once.
void zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationBulkReport report){
  offset = report.parameterOffset
  length = report.numberOfParameters

  if (offset <= CONFIG_ASSOCIATION_LED && length + offset > CONFIG_ASSOCIATION_LED) {
    value = report.scaledConfigurationValues[CONFIG_ASSOCIATION_LED - offset] == 0 ? "false" : "true"
    device.updateSetting("associationLed", [type:"bool", value: value])
  }

  if (offset <= CONFIG_UP_TIME && length + offset > CONFIG_UP_TIME) {
    value = report.scaledConfigurationValues[CONFIG_UP_TIME - offset]
    device.updateSetting("upTime", [type:"number", value: value])
  }

  if (offset <= CONFIG_DOWN_TIME && length + offset > CONFIG_DOWN_TIME) {
    value = report.scaledConfigurationValues[5 - offset]
    device.updateSetting("downTime", [type:"number", value: value])
  }

  if (offset <= CONFIG_REPOSE_LED && length + offset > CONFIG_REPOSE_LED) {
    value = report.scaledConfigurationValues[CONFIG_REPOSE_LED - offset] == 0 ? "false" : "true"
    device.updateSetting("reposeLed", [type:"bool", value: value])
  }

  if (offset <= CONFIG_LOCK_INPUT && length + offset > CONFIG_LOCK_INPUT) {
    value = report.scaledConfigurationValues[CONFIG_LOCK_INPUT - offset] == 0 ? "unlock" : "lock"
    device.updateSetting("lockInput", [type:"enum", value: value])
  }

  // in batch mode parameter 21 only reports level value without state (opening/closing), so we ignore it here

  if (offset <= CONFIG_CALIBRATION_REQUIRED && length + offset > CONFIG_CALIBRATION_REQUIRED) {
    setCalibrationState(report.scaledConfigurationValues[CONFIG_CALIBRATION_REQUIRED - offset])
  }
}

// VersionReport communicates firware and protocol versions.
void zwaveEvent(hubitat.zwave.commands.versionv2.VersionReport cmd) {
  Double firmware0Version = cmd.firmware0Version + (cmd.firmware0SubVersion / 100)
  Double protocolVersion = cmd.zWaveProtocolVersion + (cmd.zWaveProtocolSubVersion / 100)

  log.info "Version Report - FirmwareVersion: ${firmware0Version}, ProtocolVersion: ${protocolVersion}, HardwareVersion: ${cmd.hardwareVersion}"
  device.updateDataValue("firmwareVersion", "${firmware0Version}")
  device.updateDataValue("protocolVersion", "${protocolVersion}")
  device.updateDataValue("hardwareVersion", "${cmd.hardwareVersion}")

  if (cmd.firmwareTargets > 0) {
    cmd.targetVersions.each { target ->
      Double targetVersion = target.version + (target.subVersion / 100)
      device.updateDataValue("firmware${target.target}Version", "${targetVersion}")
    }
  }
}
