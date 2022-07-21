/**
 *  Simon IO Roller Blind
 *  Copyright 2022 Sergey Kolodyazhnyy
 */

import groovy.transform.Field

metadata {
  definition(name: "Simon IO Switch", namespace: "simonio", author: "Sergey Kolodyazhnyy") {
    capability "Configuration"
    capability "Switch"
    capability "Refresh"

    command "identify"

    fingerprint deviceId: "0000", inClusters: "0x5E,0x86,0x25,0x26,0x72,0x5A,0x59,0x85,0x7A,0x73,0x70", mfr: "0267", prod: "0004", deviceJoinName: "Simon IO Roller Blind"
  }
  
  preferences {
    input name: "logEnable", type: "bool", title: "Enable logging", defaultValue: false
    input name: "associationLed", type: "bool", title: "Association LED", defaultValue: false
    input name: "reposeLed", type: "bool", title: "Behavior of LED in Repose", defaultValue: false
    input name: "delayOn", type: "number", title: "Delay on (seconds*)", defaultValue: 0
    input name: "activationTime", type: "number", title: "Activation time (seconds*)", defaultValue: 0
    input name: "delayOff", type: "number", title: "Delay off (seconds*)", defaultValue: 0
    input name: "lockInput", type: "enum", title: "Lock input", options: ["lock": "Physical buttons are disabled", "unlock": "Physical buttons are enabled"], defaultValue: "unlock"
    input name: "pressAction", type: "enum", title: "Process action", options: ["toggle": "Toggle switch", "on": "Turn on", "off": "Turn off", "on_hold_off": "Turn on, hold to Turn off"], defaultValue: "toggle"
  }
}


// Configuration parameter numbers
@Field final CONFIG_ASSOCIATION_LED = 1
@Field final CONFIG_DELAY_ON = 10
@Field final CONFIG_ACTIVATION_TIME = 11
@Field final CONFIG_DELAY_OFF = 16
@Field final CONFIG_CALIBRATE = 9
@Field final CONFIG_REPOSE_LED = 12
@Field final CONFIG_LOCK_INPUT = 13
@Field final CONFIG_PRESS_ACTION = 19
@Field final CONFIG_IDENTIFY = 20
@Field final CONFIG_LOAD_STATE = 21
@Field final CONFIG_CALIBRATION_REQUIRED = 23

// Turn switch ON
def on() {
  return secure(zwave.basicV1.basicSet(value: 0xFF))
}

// Turn switch OFF
def off() {
  return secure(zwave.basicV1.basicSet(value: 0x00))
}

// Refreshes blind state and configuration
def refresh() {
  return [
    secure(zwave.basicV1.basicGet()),
    secure(zwave.configurationV2.configurationBulkGet(numberOfParameters: 23, parameterOffset: 1)),
  ]
}

// Send configuration to the device
def configure() {
  return [
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_ASSOCIATION_LED, scaledConfigurationValue: associationLed ? 0xFF : 0x00)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_DELAY_ON,        scaledConfigurationValue: delayOn)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_ACTIVATION_TIME, scaledConfigurationValue: activationTime)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_DELAY_OFF,       scaledConfigurationValue: delayOff)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_REPOSE_LED,      scaledConfigurationValue: reposeLed ? 0xFF : 0x00)),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_PRESS_ACTION,    scaledConfigurationValue: pressActionValue(pressAction))),
    secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_LOCK_INPUT,      scaledConfigurationValue: lockInput == "lock" ? 0xFF : 0x00)),
  ]
}

// Make central led blink for a few seconds
def identify() {
  return secure(zwave.configurationV2.configurationSet(parameterNumber: CONFIG_IDENTIFY, scaledConfigurationValue: 0xFF))
}

// Save configuration when user presses "Update Preferences"
def updated() {
  return configure()
}

def pressActionValue(name) {
  switch (name) {
  case "on": return 1
  case "off": return 2
  case "on_hold_off": return 4
  }
  
  return 0
}

def pressActionName(value) {
  switch (value) {
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
  if (logEnable) log.debug "Switch state changed to ${report.value ? 'ON' : 'OFF'}"
  sendEvent(name: "switch", value: report.value ? "on" : "off", isStateChange: true, type: "physical")
}


// ConfigurationBulkReport communicates configuration (and state) value for all parameters at once.
void zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationBulkReport report){
  offset = report.parameterOffset
  length = report.numberOfParameters

  if (offset <= CONFIG_ASSOCIATION_LED && length + offset > CONFIG_ASSOCIATION_LED) {
    value = report.scaledConfigurationValues[CONFIG_ASSOCIATION_LED - offset] == 0 ? "false" : "true"
    device.updateSetting("associationLed", [type:"bool", value: value])
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

  if (offset <= CONFIG_PRESS_ACTION && length + offset > CONFIG_PRESS_ACTION) {
    value = pressActionName(report.scaledConfigurationValues[CONFIG_PRESS_ACTION - offset])
    device.updateSetting("pressAction", [type:"enum", value: value])
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
