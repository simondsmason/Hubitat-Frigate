/**
 * Frigate Motion Device
 * 
 * Represents a single Frigate camera with motion detection capabilities
 * Updates motion state based on MQTT events from Frigate
 * 
 * @author Simon Mason
 * @version 1.00
 * @date 2025-01-15
 */

metadata {
    definition (
        name: "Frigate Motion Device",
        namespace: "simonmason",
        author: "Simon Mason",
        description: "Frigate camera motion detection device",
        category: "Safety & Security",
        iconUrl: "",
        iconX2Url: "",
        iconX3Url: "",
        importUrl: ""
    ) {
        capability "MotionSensor"
        capability "Refresh"
        
        // Custom attributes for Frigate-specific data
        attribute "personDetected", "string"
        attribute "carDetected", "string"
        attribute "dogDetected", "string"
        attribute "catDetected", "string"
        attribute "lastDetection", "string"
        attribute "confidence", "number"
        attribute "objectType", "string"
        attribute "cameraName", "string"
        attribute "lastUpdate", "string"
        
        // Commands
        command "refresh"
        command "updateMotionState", ["string"]
        command "updateObjectDetection", ["string", "number"]
        command "getSnapshot"
        command "getStats"
    }
    
    preferences {
        section("Camera Settings") {
            input "motionTimeout", "number", title: "Motion Timeout (seconds)", required: true, defaultValue: 30
            input "confidenceThreshold", "number", title: "Confidence Threshold (0.0-1.0)", required: true, defaultValue: 0.5
        }
        
        section("Debug") {
            input "debugLogging", "bool", title: "Enable Debug Logging", required: true, defaultValue: false
        }
    }
}

def installed() {
    log.info "Frigate Motion Device: Installing device"
    initialize()
}

def updated() {
    log.info "Frigate Motion Device: Updating device"
    initialize()
}

def initialize() {
    log.info "Frigate Motion Device: Initializing device"
    
    // Set initial states
    sendEvent(name: "motion", value: "inactive")
    sendEvent(name: "personDetected", value: "no")
    sendEvent(name: "carDetected", value: "no")
    sendEvent(name: "dogDetected", value: "no")
    sendEvent(name: "catDetected", value: "no")
    sendEvent(name: "lastDetection", value: "Never")
    sendEvent(name: "confidence", value: 0.0)
    sendEvent(name: "objectType", value: "none")
    sendEvent(name: "cameraName", value: device.label.replace("Frigate ", "").replace(" ", "_").toLowerCase())
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    
    // Schedule motion timeout
    runIn(motionTimeout ?: 30, "motionTimeoutHandler")
}

def refresh() {
    log.info "Frigate Motion Device: Refreshing device state"
    
    // Update last update time
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    
    // This could trigger a request to parent app for latest data
    parent?.refreshCameraData(device.deviceNetworkId)
}

def updateMotionState(String state) {
    if (debugLogging) {
        log.debug "Frigate Motion Device: Updating motion state to: ${state}"
    }
    
    def currentMotion = device.currentValue("motion")
    
    if (currentMotion != state) {
        sendEvent(name: "motion", value: state)
        sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
        
        if (state == "active") {
            log.info "Frigate Motion Device: Motion detected on ${device.label}"
            
            // Schedule motion timeout
            runIn(motionTimeout ?: 30, "motionTimeoutHandler")
        }
    }
}

def updateObjectDetection(String objectType, Number confidence) {
    if (debugLogging) {
        log.debug "Frigate Motion Device: Object detection - ${objectType} with confidence ${confidence}"
    }
    
    // Update confidence
    sendEvent(name: "confidence", value: confidence)
    sendEvent(name: "objectType", value: objectType)
    sendEvent(name: "lastDetection", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    
    // Update specific object detection states
    def objectStates = [
        "person": "personDetected",
        "car": "carDetected", 
        "dog": "dogDetected",
        "cat": "catDetected"
    ]
    
    // Reset all object states to "no"
    objectStates.each { objType, attrName ->
        sendEvent(name: attrName, value: "no")
    }
    
    // Set the detected object to "yes"
    if (objectStates.containsKey(objectType)) {
        sendEvent(name: objectStates[objectType], value: "yes")
        log.info "Frigate Motion Device: ${objectType} detected on ${device.label} with confidence ${confidence}"
    }
    
    // Update motion state if confidence is above threshold
    if (confidence >= (confidenceThreshold ?: 0.5)) {
        updateMotionState("active")
    }
}

def motionTimeoutHandler() {
    if (debugLogging) {
        log.debug "Frigate Motion Device: Motion timeout reached"
    }
    
    sendEvent(name: "motion", value: "inactive")
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}

def getSnapshot() {
    log.info "Frigate Motion Device: Requesting snapshot for ${device.label}"
    
    // This would request a snapshot from the parent app
    parent?.getCameraSnapshot(device.deviceNetworkId)
}

def getStats() {
    log.info "Frigate Motion Device: Requesting stats for ${device.label}"
    
    // This would request stats from the parent app
    parent?.getCameraStats(device.deviceNetworkId)
}

// Helper method to get camera name from device
def getCameraName() {
    return device.currentValue("cameraName") ?: device.label.replace("Frigate ", "").replace(" ", "_").toLowerCase()
}
