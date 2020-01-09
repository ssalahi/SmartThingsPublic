/*
 *  Copyright 2018 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License. You may obtain a copy
 *  of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 *
 *  The code is based on zigbee-motion-detector from "jinkang zhang / jk0218.zhang@samsung.com"
 */
import physicalgraph.zigbee.clusters.iaszone.ZoneStatus
import physicalgraph.zigbee.zcl.DataType
metadata {
	definition(name: "Konko Motion Sensor", namespace: "ssalahi", author: "SmartThings", runLocally: false, mnmn: "SmartThings") {
		capability "Motion Sensor"
		capability "Configuration"
		capability "Battery"
		capability "Refresh"
		capability "Health Check"
		capability "Sensor"
		fingerprint profileId: "0104", deviceId: "0402", inClusters: "0000,0001,0003,0500,FCC0", outClusters: "0003,FCC0", manufacturer: "Konke", model: "3AFE14010402000D", deviceJoinName: "Konke Motion Sensor"
	}
	simulator {
		status "active": "zone status 0x0001 -- extended status 0x00"
		for (int i = 0; i <= 100; i += 11) {
			status "battery ${i}%": "read attr - raw: 2E6D01000108210020C8, dni: 2E6D, endpoint: 01, cluster: 0001, size: 08, attrId: 0021, encoding: 20, value: ${i}"
		}
	}
	tiles(scale: 2) {
		multiAttributeTile(name: "motion", type: "generic", width: 6, height: 4) {
			tileAttribute("device.motion", key: "PRIMARY_CONTROL") {
				attributeState "active", label: 'motion', icon: "st.motion.motion.active", backgroundColor: "#00A0DC"
				attributeState "inactive", label: 'no motion', icon: "st.motion.motion.inactive", backgroundColor: "#cccccc"
			}
		}
		valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
			state "battery", label: '${currentValue}% battery', unit: ""
		}
		standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", action: "refresh.refresh", icon: "st.secondary.refresh"
		}
		main(["motion"])
		details(["motion","battery", "refresh"])
	}
    preferences {
		//Battery Voltage Offset
        input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.9). Default = 2.5 Volts", description: "", type: "decimal", range: "2..2.9", defaultValue: 2.5
		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.95 to 3.4). Default = 3.2 Volts", description: "", type: "decimal", range: "2.95..3.4", defaultValue: 3.2
	}
}

def stopMotion() {
	//log.debug "motion inactive"
	sendEvent(getMotionResult(false))
}

def installed(){
	//log.debug "installed"
	return zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0021) +
					zigbee.readAttribute(zigbee.IAS_ZONE_CLUSTER,zigbee.ATTRIBUTE_IAS_ZONE_STATUS)

}

def parse(String description) {
	//log.debug "description(): $description"
	def map = zigbee.getEvent(description)
	ZoneStatus zs
	if (!map) {
		if (description?.startsWith('zone status')) {
			zs = zigbee.parseZoneStatus(description)
			map = parseIasMessage(zs)
		} else {
			def descMap = zigbee.parseDescriptionAsMap(description)
			if (descMap?.clusterInt == zigbee.POWER_CONFIGURATION_CLUSTER) {
				map = batteyHandler(description)
			} else if (descMap?.clusterInt == zigbee.IAS_ZONE_CLUSTER && descMap.commandInt != 0x07 && descMap.value) {
				//log.debug "parseDescriptionAsMap: $descMap.value"
				zs = new ZoneStatus(zigbee.convertToInt(descMap.value, 16))
				map = parseIasMessage(zs)
			}
		}
	}
	//log.debug "Parse returned $map"
	def result = map ? createEvent(map) : [:]
	if (description?.startsWith('enroll request')) {
		List cmds = zigbee.enrollResponse()
		//log.debug "enroll response: ${cmds}"
		result = cmds?.collect { new physicalgraph.device.HubAction(it) }
	}

	return result
}

def batteyHandler(String description){
	//log.debug "batteyHandler: ${description}"
	def descMap = zigbee.parseDescriptionAsMap(description)
	def map = [:]
	if (descMap?.clusterInt == zigbee.POWER_CONFIGURATION_CLUSTER && descMap.commandInt != 0x07 && descMap.value) {
		map = getBatteryPercentageResult(Integer.parseInt(descMap.value, 16))
	}
	return map
}

def parseIasMessage(ZoneStatus zs) {
	Boolean motionActive = zs.isAlarm1Set() || zs.isAlarm2Set()
    if (motionActive) {
        def timeout = 30
        //log.debug "Stopping motion in ${timeout} seconds"
        runIn(timeout, stopMotion)
    }
	return getMotionResult(motionActive)
}

def getBatteryPercentageResult(rawValue) {	
    def rawVolts = rawValue / 10
    def minVolts = (voltsmin == null || voltsmin == "") ? 2.5 : voltsmin
    def maxVolts = (voltsmax == null || voltsmax == "") ? 3.2 : voltsmax

    def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
    def roundedPct = Math.min(100, Math.round(pct * 100))
    
    //log.debug "Battery Percentage rawValue = ${rawValue} -> ${roundedPct}%"
    
    def result = [
        name: 'battery',
        value: roundedPct,
        unit: "%",
        isStateChange: true,
        descriptionText : "${device.displayName} Battery level is ${roundedPct}% (${rawVolts} Volts)"
    ]
	return result
}

def getMotionResult(value) {
	def descriptionText = value ? "${device.displayName} detected motion" : "${device.displayName} motion has stopped"
	return [
			name			: 'motion',
			value			: value ? 'active' : 'inactive',
			descriptionText : descriptionText,
			translatable	: true
	]
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
	//log.debug "ping "
	return zigbee.readAttribute(zigbee.IAS_ZONE_CLUSTER, zigbee.ATTRIBUTE_IAS_ZONE_STATUS) + zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0021)
}

def refresh() {
	//log.debug "Refreshing Values"
	return  zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0021) +
					zigbee.readAttribute(zigbee.IAS_ZONE_CLUSTER,zigbee.ATTRIBUTE_IAS_ZONE_STATUS) +
					zigbee.enrollResponse()
}

def configure() {
	//log.debug "configure"
    sendEvent(name: "checkInterval", value:2 * 60 * 60 + 2*60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID, offlinePingable: "1"])
		return zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0021, DataType.UINT8, 30, 1200, 0x10) + refresh()
}
