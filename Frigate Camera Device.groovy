/**
 * Frigate Camera Device
 * 
 * Represents a single Frigate camera with motion/object detection and snapshot capabilities
 * Updates motion state based on MQTT events from Frigate; can store/display snapshots
 * 
 * Copyright 2025
 *
 * Change History:
 * 1.00 - Initial release (as Frigate Motion Device)
 * 1.01 - Updated to work with improved event processing in parent app
 * 1.02 - 2025-10-31 - Added snapshotImage/snapshotUrl attributes, updateSnapshot command; device now renders snapshot on dashboard tiles via image attribute
 * 1.03 - 2025-10-31 - Replaced non-existent capability 'Image' with standard 'Image Capture'; added take() to fetch snapshot
 * 1.04 - 2025-10-31 - Renamed driver to "Frigate Camera Device" to reflect broader scope
 * 1.05 - 2025-11-07 - Added safe confidence casting, motion threshold normalization, and improved detection logging
 * 1.06 - 2025-11-08 - Added switch capability, zone-aware metadata, and support for event clips/snapshots
 * 
 * @author Simon Mason
 * @version 1.06
 * @date 2025-11-08
 */

metadata {
    definition (
        name: "Frigate Camera Device",
        namespace: "simonmason",
        author: "Simon Mason",
        description: "Frigate camera device (motion, objects, snapshots)",
        category: "Safety & Security",
        iconUrl: "",
        iconX2Url: "",
        iconX3Url: "",
        importUrl: ""
    ) {
        capability "MotionSensor"
        capability "Switch"
        capability "Refresh"
        // Removed Image Capture to avoid showing a Take button that always returns latest.jpg
        
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
        attribute "latestSnapshotUrl", "string"
        attribute "lastMotionSnapshotUrl", "string"
        attribute "lastSnapshotUrl", "string"
        attribute "lastClipUrl", "string"
        attribute "hasSnapshot", "string"
        attribute "hasClip", "string"
        attribute "currentZones", "string"
        attribute "enteredZones", "string"
        attribute "previousZones", "string"
        attribute "lastEventId", "string"
        attribute "lastEventLabel", "string"
        attribute "lastEventConfidence", "number"
        attribute "lastEventStart", "string"
        attribute "lastEventEnd", "string"
        attribute "motionScore", "number"
        attribute "trackId", "string"
        attribute "zoneName", "string"
        
        // Commands
        command "refresh"
        command "updateMotionState", ["string"]
        command "updateObjectDetection", ["string", "number"]
        command "getStats"
        command "updateLatestSnapshotUrl", ["string"]
        command "updateLastMotionSnapshotUrl", ["string"]
        command "clearDetections"
    }
    
    preferences {
        section("Camera Settings") {
            input "confidenceThreshold", "number", title: "Confidence Threshold (0.0-1.0)", required: true, defaultValue: 0.5
        }
        
        section("Debug") {
            input "debugLogging", "bool", title: "Enable Debug Logging", required: true, defaultValue: false
        }
    }
}

def installed() {
    log.info "Frigate Camera Device: Installing device"
    initialize()
}

def updated() {
    log.info "Frigate Camera Device: Updating device"
    initialize()
}

def initialize() {
    log.info "Frigate Camera Device: Initializing device"
    
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
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "lastSnapshotUrl", value: "")
    sendEvent(name: "lastClipUrl", value: "")
    sendEvent(name: "hasSnapshot", value: "no")
    sendEvent(name: "hasClip", value: "no")
    sendEvent(name: "currentZones", value: "none")
    sendEvent(name: "enteredZones", value: "none")
    sendEvent(name: "previousZones", value: "none")
    sendEvent(name: "lastEventId", value: "")
    sendEvent(name: "lastEventLabel", value: "")
    sendEvent(name: "lastEventConfidence", value: 0.0)
    sendEvent(name: "lastEventStart", value: "")
    sendEvent(name: "lastEventEnd", value: "")
    sendEvent(name: "motionScore", value: 0.0)
    sendEvent(name: "trackId", value: "")
    
}

def refresh() {
    log.info "Frigate Camera Device: Refreshing device state"
    
    // Update last update time
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}

def updateMotionState(String state) {
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateMotionState() called with state: ${state} for device: ${device.label}"
    }
    
    def currentMotion = device.currentValue("motion")
    
    if (debugLogging) {
        log.debug "Frigate Camera Device: Current motion state: ${currentMotion}, New state: ${state}"
    }
    
    if (currentMotion != state) {
        if (debugLogging) {
            log.debug "Frigate Camera Device: State changed, sending motion event: ${state}"
        }
        
        sendEvent(name: "motion", value: state)
        sendEvent(name: "switch", value: (state == "active") ? "on" : "off")
        sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
        
        if (state == "active") {
            log.info "Frigate Camera Device: Motion detected on ${device.label}"
        }
        
        if (debugLogging) {
            log.debug "Frigate Camera Device: Motion state updated successfully"
        }
    } else {
        // Motion state is unchanged
        if (debugLogging) {
            log.debug "Frigate Camera Device: State unchanged (${state}), not modifying timeout"
        }
    }
}

def on() {
    if (debugLogging) {
        log.debug "Frigate Camera Device: on() called, forcing motion active"
    }
    updateMotionState("active")
}

def off() {
    if (debugLogging) {
        log.debug "Frigate Camera Device: off() called, forcing motion inactive"
    }
    updateMotionState("inactive")
}

def updateObjectDetection(String objectType, Number confidence) {
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateObjectDetection() called - Object: ${objectType}, Confidence: ${confidence} on device: ${device.label}"
    }
    
    // Track when we last got a detection
    state.lastDetectionTime = now()
    
    BigDecimal numericConfidence = 0.0G
    try {
        if (confidence != null) {
            numericConfidence = new BigDecimal(confidence.toString())
        }
    } catch (Exception ignored) {
        numericConfidence = 0.0G
    }

    // Update confidence
    sendEvent(name: "confidence", value: numericConfidence)
    sendEvent(name: "objectType", value: objectType)
    sendEvent(name: "lastDetection", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    
    if (debugLogging) {
        log.debug "Frigate Camera Device: Updated confidence, objectType, lastDetection, lastUpdate events"
    }
    
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
    
    if (debugLogging) {
        log.debug "Frigate Camera Device: Reset all object detection states to 'no'"
    }
    
    // Set the detected object to "yes"
    if (objectStates.containsKey(objectType)) {
        sendEvent(name: objectStates[objectType], value: "yes")
        log.info "Frigate Camera Device: ${objectType} detected on ${device.label} with confidence ${numericConfidence}"
        
        if (debugLogging) {
            log.debug "Frigate Camera Device: Set ${objectType} detection state to 'yes'"
        }
    } else {
        if (debugLogging) {
            log.debug "Frigate Camera Device: Object type '${objectType}' not in supported list: ${objectStates.keySet()}"
        }
    }
    
    // Update motion state if confidence is above threshold
    def threshold = confidenceThreshold ?: 0.5
    if (debugLogging) {
        log.debug "Frigate Camera Device: Checking confidence ${numericConfidence} against threshold ${threshold}"
    }
    
    def thresholdValue = 0.0G
    try {
        thresholdValue = new BigDecimal(threshold.toString())
    } catch (Exception ignored) {
        thresholdValue = 0.5G
    }

    if (numericConfidence >= thresholdValue) {
        if (debugLogging) {
            log.debug "Frigate Camera Device: Confidence ${numericConfidence} >= threshold ${thresholdValue}, updating motion to active"
        }
        updateMotionState("active")
    }
    
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateObjectDetection() completed"
    }
}

// No motion timeout logic; motion goes inactive on 'end' events from Frigate

def clearDetections() {
    if (debugLogging) {
        log.debug "Frigate Camera Device: clearDetections() called for ${device.label}"
    }
    ["personDetected", "carDetected", "dogDetected", "catDetected"].each { attr ->
        sendEvent(name: attr, value: "no")
    }
    sendEvent(name: "objectType", value: "none")
    sendEvent(name: "confidence", value: 0.0)
    updateMotionState("inactive")
}

def getStats() {
    log.info "Frigate Camera Device: Requesting stats for ${device.label}"
    parent?.getCameraStats(device.deviceNetworkId)
}

// Helper method to get camera name from device
def getCameraName() {
    return device.currentValue("cameraName") ?: device.label.replace("Frigate ", "").replace(" ", "_").toLowerCase()
}

// Parent calls this to update the latest snapshot URL (latest.jpg)
def updateLatestSnapshotUrl(String imageUrl) {
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateLatestSnapshotUrl() called for ${device.label} - url: ${imageUrl}"
    }
    if (imageUrl) {
        sendEvent(name: "latestSnapshotUrl", value: imageUrl)
    }
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}

// Parent calls this on motion start to store the event snapshot URL and reflect it in the image tile
def updateLastMotionSnapshotUrl(String imageUrl) {
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateLastMotionSnapshotUrl() called for ${device.label} - url: ${imageUrl}"
    }
    if (imageUrl) {
        sendEvent(name: "lastMotionSnapshotUrl", value: imageUrl)
        // Copy to generic image attribute so dashboards show the event-time snapshot
        sendEvent(name: "image", value: imageUrl)
    }
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}

def updateEventMetadata(Map data) {
    if (!data) {
        return
    }
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateEventMetadata() called with data: ${data}"
    }
    def nowTs = new Date().format("yyyy-MM-dd HH:mm:ss")
    
    if (data.cameraName) {
        sendEvent(name: "cameraName", value: data.cameraName)
    }
    if (data.zoneName) {
        sendEvent(name: "zoneName", value: data.zoneName)
    }
    if (data.currentZones != null) {
        def value = data.currentZones instanceof Collection ? data.currentZones.join(",") : data.currentZones.toString()
        sendEvent(name: "currentZones", value: value ?: "none")
    }
    if (data.enteredZones != null) {
        def value = data.enteredZones instanceof Collection ? data.enteredZones.join(",") : data.enteredZones.toString()
        sendEvent(name: "enteredZones", value: value ?: "none")
    }
    if (data.previousZones != null) {
        def value = data.previousZones instanceof Collection ? data.previousZones.join(",") : data.previousZones.toString()
        sendEvent(name: "previousZones", value: value ?: "none")
    }
    if (data.eventId) {
        sendEvent(name: "lastEventId", value: data.eventId)
    }
    if (data.label != null) {
        sendEvent(name: "lastEventLabel", value: data.label)
    }
    if (data.confidence != null) {
        try {
            sendEvent(name: "lastEventConfidence", value: new BigDecimal(data.confidence.toString()))
        } catch (Exception ignored) {
            sendEvent(name: "lastEventConfidence", value: 0.0)
        }
    }
    if (data.startTime != null) {
        sendEvent(name: "lastEventStart", value: data.startTime.toString())
    }
    if (data.endTime != null) {
        sendEvent(name: "lastEventEnd", value: data.endTime.toString())
    }
    if (data.motionScore != null) {
        try {
            sendEvent(name: "motionScore", value: new BigDecimal(data.motionScore.toString()))
        } catch (Exception ignored) {
            sendEvent(name: "motionScore", value: 0.0)
        }
    }
    if (data.trackId != null) {
        sendEvent(name: "trackId", value: data.trackId.toString())
    }
    
    if (data.hasSnapshot != null) {
        sendEvent(name: "hasSnapshot", value: data.hasSnapshot ? "yes" : "no")
    }
    if (data.hasClip != null) {
        sendEvent(name: "hasClip", value: data.hasClip ? "yes" : "no")
    }
    if (data.snapshotUrl) {
        sendEvent(name: "lastSnapshotUrl", value: data.snapshotUrl)
        sendEvent(name: "latestSnapshotUrl", value: data.snapshotUrl)
        sendEvent(name: "image", value: data.snapshotUrl)
    }
    if (data.clipUrl) {
        sendEvent(name: "lastClipUrl", value: data.clipUrl)
    }
    if (data.lastMotionSnapshotUrl) {
        updateLastMotionSnapshotUrl(data.lastMotionSnapshotUrl)
    }
    
    sendEvent(name: "lastUpdate", value: nowTs)
}


