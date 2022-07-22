/**
 *  Simon IO Dimmable Switch
 *  Copyright 2022 Sergey Kolodyazhnyy
 */

import groovy.transform.Field

metadata {
  definition(name: "Simon IO Dimmable Switch", namespace: "simonio", author: "Sergey Kolodyazhnyy") {
    capability "Configuration"
    capability "Switch"
    capability "SwitchLevel"
    capability "Refresh"
    capability "PowerMeter"
    
    attribute "calibrated", "ENUM"
    
    command "identify"
    command "calibrate", [
      [name: "Method*", type: "ENUM", constraints: ["Better edge", "Trailing Edge", "Leading Edge"]]
    ]

    fingerprint deviceId: "0062", inClusters: "0x5E,0x86,0x26,0x72,0x5A,0x59,0x85,0x73,0x70,0x7A,0x32", mfr: "0267", prod: "0007", deviceJoinName: "Simon IO Dimmable Switch"
  }
  
  preferences {
    input name: "logEnable", type: "bool", title: "Enable logging", defaultValue: false
    input name: "associationLed", type: "bool", title: "Association LED", defaultValue: false
    input name: "reposeLed", type: "bool", title: "Behavior of LED in Repose", defaultValue: false
    input name: "setTime", type: "number", title: "Set time (seconds*)", defaultValue: 0
    input name: "fadeTime", type: "number", title: "Fade time (seconds*)", defaultValue: 0
    input name: "delayOn", type: "number", title: "Delay on (seconds*)", defaultValue: 0
    input name: "activationTime", type: "number", title: "Activation time (seconds*)", defaultValue: 0
    input name: "delayOff", type: "number", title: "Delay off (seconds*)", defaultValue: 0
    input name: "minDimming", type: "number", title: "Min dimming margin (0-99)", defaultValue: 0
    input name: "maxDimming", type: "number", title: "Max dimming margin (0-99)", defaultValue: 0
    input name: "lockInput", type: "enum", title: "Lock input", options: ["lock": "Physical buttons are disabled", "unlock": "Physical buttons are enabled"], defaultValue: "unlock"
    input name: "pressAction", type: "enum", title: "Process action", options: ["toggle": "Toggle switch", "on": "Turn on", "off": "Turn off", "on_hold_off": "Turn on, hold to Turn off", "toggle_no_dimming": "Toggle switch (no dimming)",], defaultValue: "toggle"
  }
}


// Configuration parameter numbers
@Field final CONFIG_ASSOCIATION_LED = 1
@Field final CONFIG_SET_TIME = 4
@Field final CONFIG_FADE_TIME = 5
@Field final CONFIG_DELAY_ON = 10
@Field final CONFIG_ACTIVATION_TIME = 11
@Field final CONFIG_DELAY_OFF = 16
@Field final CONFIG_CALIBRATE = 9
@Field final CONFIG_MIN_DIMMING = 6
@Field final CONFIG_MAX_DIMMING = 7
@Field final CONFIG_REPOSE_LED = 12
@Field final CONFIG_LOCK_INPUT = 13
@Field final CONFIG_PRESS_ACTION = 19
@Field final CONFIG_IDENTIFY = 20
@Field final CONFIG_LOAD_STATE = 21
@Field final CONFIG_CALIBRATION_REQUIRED = 23

// Turn switch ON
def on() {
  return [
    secure(zwave.basicV1.basicSet(value: 0xFF)),
  ]
}

// Turn switch OFF
def off() {
  return [
    secure(zwave.basicV1.basicSet(value: 0x00)),
  ]
}

def setLevel(level, duration = null) {
  if (level > 99) { // trim position to 99 (because capability allows 0..100, but simon works with 0..99)
    level = 99
  }

  return [
    secure(zwave.switchMultilevelV3.switchMultilevelSet(value: level, dimmingDuration: duration)),
  ]
}

// Refreshes blind state and configuration
def refresh() {
  return [
    secure(zwave.configurationV2.configurationBulkGet(numberOfParameters: 23, parameterOffset: 1)),
    secure(zwave.configurationV2.configurationGet(parameterNumber: 21)),
    secure(zwave.meterV4.meterGet(rateType: 1, scale: 2))
  ]
}

// Send configuration to the device
def configure() {
  return [
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_ASSOCIATION_LED, scaledConfigurationValue: associationLed ? 0xFF : 0x00)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_SET_TIME,        scaledConfigurationValue: setTime)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_FADE_TIME,       scaledConfigurationValue: fadeTime)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_DELAY_ON,        scaledConfigurationValue: delayOn)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_ACTIVATION_TIME, scaledConfigurationValue: activationTime)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_DELAY_OFF,       scaledConfigurationValue: delayOff)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_MIN_DIMMING,     scaledConfigurationValue: minDimming)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_MAX_DIMMING,     scaledConfigurationValue: maxDimming)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_REPOSE_LED,      scaledConfigurationValue: reposeLed ? 0xFF : 0x00)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_PRESS_ACTION,    scaledConfigurationValue: pressActionValue(pressAction))),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_LOCK_INPUT,      scaledConfigurationValue: lockInput == "lock" ? 0xFF : 0x00)),
  ]
}

// Make central led blink for a few seconds
def identify() {
  return secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_IDENTIFY, scaledConfigurationValue: 0xFF))
}

// Calibration
def calibrate(method) {
  value = 0x01
  switch (method) {
  case "Trailing Edge": 
    value = 0x02
    break
  case "Leading Edge": 
    value = 0x03
    break
  }
  
  return secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_CALIBRATE, scaledConfigurationValue: value))
}

// Save configuration when user presses "Update Preferences"
def updated() {
  return configure()
}

def pressActionValue(name) {
  switch (name) {
  case "toggle_no_dimming": return 0
  case "on": return 1
  case "off": return 2
  case "on_hold_off": return 4
  }
  
  return 5
}

def pressActionName(value) {
  switch (value) {
  case 0: return "toggle_no_dimming"
  case 1: return "on"
  case 2: return "off"
  case 4: return "on_hold_off"
  }
  
  return "toggle"
}

// This method is triggered every time hubitat receives a command from the device.
void parse(String description){
  if (logEnable) log.debug "parse description: ${description}"

  hubitat.zwave.Command cmd = zwave.parse(description, [
    0x20:2, // Basic V2
    0x26:3, // SwitchMultilevel V3
    0x32:4, // MeterReport V4
    0x70:2, // Configuration V2
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


// Set state and level as reported by configuration parameter 21 (load state)
void setLoadState(value) {
  int state = (long) value >> 8
  int level = (long) value % 256
  if (level == 99) { // report 99 as 100 to match capability range
    level = 100
  }

  sendEvent(name: "level", value: level, isStateChange: true, type: "physical")
  sendEvent(name: "switch", value: state ? "on" : "off", isStateChange: true, type: "physical")
}

// Set calibration as reported by configuration parameter 23 (Calibration Required)
void setCalibrationState(value) {
  sendEvent(name: "calibrated", value: value == 0 ? "yes" : "no", isStateChange: true, type: "physical")
}

// BasicReport is received every time blind has finished changing position
void zwaveEvent(hubitat.zwave.commands.basicv2.BasicReport report){
  if (logEnable) log.debug "Switch state changed to ${report.value}"
   
  if (report.value == 0) {
    sendEvent(name: "switch", value: report.value ? "on" : "off", isStateChange: true, type: "physical")
    return
  }
  
  // basic report with non-zero value means light is on, so add 256 to mark switch status as on
  setLoadState(report.value + 256);
}

void zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport report) {
    switch (report.parameterNumber) {
    case CONFIG_LOAD_STATE:
        setLoadState(report.scaledConfigurationValue)
    }
}

// ConfigurationBulkReport communicates configuration (and state) value for all parameters at once.
void zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationBulkReport report) {
  offset = report.parameterOffset
  length = report.numberOfParameters

  if (offset <= CONFIG_ASSOCIATION_LED && length + offset > CONFIG_ASSOCIATION_LED) {
    value = report.scaledConfigurationValues[CONFIG_ASSOCIATION_LED - offset] == 0 ? "false" : "true"
    device.updateSetting("associationLed", [type:"bool", value: value])
  }

  if (offset <= CONFIG_SET_TIME && length + offset > CONFIG_SET_TIME) {
    value = report.scaledConfigurationValues[CONFIG_SET_TIME - offset]
    device.updateSetting("setTime", [type:"number", value: value])
  }

  if (offset <= CONFIG_FADE_TIME && length + offset > CONFIG_FADE_TIME) {
    value = report.scaledConfigurationValues[CONFIG_FADE_TIME - offset]
    device.updateSetting("fadeTime", [type:"number", value: value])
  }

  if (offset <= CONFIG_DELAY_ON && length + offset > CONFIG_DELAY_ON) {
    value = report.scaledConfigurationValues[CONFIG_DELAY_ON - offset]
    device.updateSetting("delayOn", [type:"number", value: value])
  }

  if (offset <= CONFIG_ACTIVATION_TIME && length + offset > CONFIG_ACTIVATION_TIME) {
    value = report.scaledConfigurationValues[CONFIG_ACTIVATION_TIME - offset]
    device.updateSetting("activationTime", [type:"number", value: value])
  }

  if (offset <= CONFIG_DELAY_OFF && length + offset > CONFIG_DELAY_OFF) {
    value = report.scaledConfigurationValues[CONFIG_DELAY_OFF - offset]
    device.updateSetting("delayOff", [type:"number", value: value])
  }

  if (offset <= CONFIG_REPOSE_LED && length + offset > CONFIG_REPOSE_LED) {
    value = report.scaledConfigurationValues[CONFIG_REPOSE_LED - offset] == 0 ? "false" : "true"
    device.updateSetting("reposeLed", [type:"bool", value: value])
  }

  if (offset <= CONFIG_LOCK_INPUT && length + offset > CONFIG_LOCK_INPUT) {
    value = report.scaledConfigurationValues[CONFIG_LOCK_INPUT - offset] == 0 ? "unlock" : "lock"
    device.updateSetting("lockInput", [type:"enum", value: value])
  }
    
  // Parameter 21 (load state) is not correctly reported
  
  if (offset <= CONFIG_PRESS_ACTION && length + offset > CONFIG_PRESS_ACTION) {
    value = pressActionName(report.scaledConfigurationValues[CONFIG_PRESS_ACTION - offset])
    device.updateSetting("pressAction", [type:"enum", value: value])
  }
    
  if (offset <= CONFIG_MIN_DIMMING && length + offset > CONFIG_MIN_DIMMING) {
    value = report.scaledConfigurationValues[CONFIG_MIN_DIMMING - offset]
    device.updateSetting("minDimming", [type:"number", value: value])
  }

  if (offset <= CONFIG_MAX_DIMMING && length + offset > CONFIG_MAX_DIMMING) {
    value = report.scaledConfigurationValues[CONFIG_MAX_DIMMING - offset]
    device.updateSetting("maxDimming", [type:"number", value: value])
  }

  if (offset <= CONFIG_CALIBRATION_REQUIRED && length + offset > CONFIG_CALIBRATION_REQUIRED) {
    setCalibrationState(report.scaledConfigurationValues[CONFIG_CALIBRATION_REQUIRED - offset])
  }
}

// MeterReport communicates power consumption metrics
void zwaveEvent(hubitat.zwave.commands.meterv4.MeterReport report){
  if (report.meterType != report.METER_TYPE_ELECTRIC_METER || report.rateType != 1 || report.scale != 2) {
    return
  }
  
  if (logEnable) log.debug("Power consumption is ${report.scaledMeterValue}W")
  
  sendEvent(name: "power", value: report.scaledMeterValue)
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
