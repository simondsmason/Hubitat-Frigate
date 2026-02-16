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
 * 1.07 - 2025-11-14 - Added version attribute to device state; fixed unix timestamp formatting for lastEventStart and lastEventEnd to display as readable dates
 * 1.08 - 2025-11-14 - CRITICAL PERFORMANCE FIX: Optimized event sending to only send events when values actually change, preventing LimitExceededException errors. Reduced event queue pressure by ~70% by checking current values before sending events and only resetting object detection states that need to change.
 * 1.09 - 2026-01-18 - REFACTOR: Added driverVersion() function and updated all log statements to use version variable approach for consistency and easier maintenance.
 * 
 * @author Simon Mason
 * @version 1.09
 * @date 2026-01-18
 */

/**
 * Returns the current driver version number
 * This is used in all log statements to ensure version consistency
 */
String driverVersion() { return "1.09" }

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
        attribute "version", "string"
        
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
    log.info "Frigate Camera Device: Installing device (v${driverVersion()})"
    initialize()
}

def updated() {
    log.info "Frigate Camera Device: Updating device (v${driverVersion()})"
    initialize()
}

def initialize() {
    log.info "Frigate Camera Device: Initializing device (v${driverVersion()})"
    
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
    sendEvent(name: "version", value: driverVersion())
    
}

def refresh() {
    log.info "Frigate Camera Device: Refreshing device state (v${driverVersion()})"
    
    // Update last update time
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}

def updateMotionState(String state) {
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateMotionState() called with state: ${state} for device: ${device.label} (v${driverVersion()})"
    }
    
    def currentMotion = device.currentValue("motion")
    
    if (debugLogging) {
        log.debug "Frigate Camera Device: Current motion state: ${currentMotion}, New state: ${state} (v${driverVersion()})"
    }
    
    if (currentMotion != state) {
        if (debugLogging) {
            log.debug "Frigate Camera Device: State changed, sending motion event: ${state} (v${driverVersion()})"
        }
        
        sendEvent(name: "motion", value: state)
        sendEvent(name: "switch", value: (state == "active") ? "on" : "off")
        sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
        
        if (state == "active") {
            log.info "Frigate Camera Device: Motion detected on ${device.label} (v${driverVersion()})"
        }
        
        if (debugLogging) {
            log.debug "Frigate Camera Device: Motion state updated successfully (v${driverVersion()})"
        }
    } else {
        // Motion state is unchanged
        if (debugLogging) {
            log.debug "Frigate Camera Device: State unchanged (${state}), not modifying timeout (v${driverVersion()})"
        }
    }
}

def on() {
    if (debugLogging) {
        log.debug "Frigate Camera Device: on() called, forcing motion active (v${driverVersion()})"
    }
    updateMotionState("active")
}

def off() {
    if (debugLogging) {
        log.debug "Frigate Camera Device: off() called, forcing motion inactive (v${driverVersion()})"
    }
    updateMotionState("inactive")
}

def updateObjectDetection(String objectType, Number confidence) {
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateObjectDetection() called - Object: ${objectType}, Confidence: ${confidence} on device: ${device.label} (v${driverVersion()})"
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

    // Update confidence (only if changed to reduce event queue pressure)
    def currentConfidence = device.currentValue("confidence")
    if (currentConfidence != numericConfidence) {
        sendEvent(name: "confidence", value: numericConfidence, isStateChange: true)
    }
    
    def currentObjectType = device.currentValue("objectType")
    if (currentObjectType != objectType) {
        sendEvent(name: "objectType", value: objectType, isStateChange: true)
    }
    
    def detectionTime = new Date().format("yyyy-MM-dd HH:mm:ss")
    sendEvent(name: "lastDetection", value: detectionTime, isStateChange: true)
    sendEvent(name: "lastUpdate", value: detectionTime, isStateChange: false) // Always update timestamp
    
    if (debugLogging) {
        log.debug "Frigate Camera Device: Updated confidence, objectType, lastDetection, lastUpdate events (v${driverVersion()})"
    }
    
    // Update specific object detection states (only send events for changes to reduce queue pressure)
    def objectStates = [
        "person": "personDetected",
        "car": "carDetected", 
        "dog": "dogDetected",
        "cat": "catDetected"
    ]
    
    // Only reset object states that are currently "yes" and not the detected object
    objectStates.each { objType, attrName ->
        def currentValue = device.currentValue(attrName)
        if (objType != objectType && currentValue == "yes") {
            // This object was detected before but isn't now - reset it
            sendEvent(name: attrName, value: "no", isStateChange: true)
        }
    }
    
    // Set the detected object to "yes" (only if it's not already "yes")
    if (objectStates.containsKey(objectType)) {
        def attrName = objectStates[objectType]
        def currentValue = device.currentValue(attrName)
        if (currentValue != "yes") {
            sendEvent(name: attrName, value: "yes", isStateChange: true)
            log.info "Frigate Camera Device: ${objectType} detected on ${device.label} with confidence ${numericConfidence} (v${driverVersion()})"
        }
        
        if (debugLogging) {
            log.debug "Frigate Camera Device: Set ${objectType} detection state to 'yes' (v${driverVersion()})"
        }
    } else {
        if (debugLogging) {
            log.debug "Frigate Camera Device: Object type '${objectType}' not in supported list: ${objectStates.keySet()} (v${driverVersion()})"
        }
    }
    
    // Update motion state if confidence is above threshold
    def threshold = confidenceThreshold ?: 0.5
    if (debugLogging) {
        log.debug "Frigate Camera Device: Checking confidence ${numericConfidence} against threshold ${threshold} (v${driverVersion()})"
    }
    
    def thresholdValue = 0.0G
    try {
        thresholdValue = new BigDecimal(threshold.toString())
    } catch (Exception ignored) {
        thresholdValue = 0.5G
    }

    if (numericConfidence >= thresholdValue) {
        if (debugLogging) {
            log.debug "Frigate Camera Device: Confidence ${numericConfidence} >= threshold ${thresholdValue}, updating motion to active (v${driverVersion()})"
        }
        updateMotionState("active")
    }
    
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateObjectDetection() completed (v${driverVersion()})"
    }
}

// No motion timeout logic; motion goes inactive on 'end' events from Frigate

def clearDetections() {
    if (debugLogging) {
        log.debug "Frigate Camera Device: clearDetections() called for ${device.label} (v${driverVersion()})"
    }
    ["personDetected", "carDetected", "dogDetected", "catDetected"].each { attr ->
        sendEvent(name: attr, value: "no")
    }
    sendEvent(name: "objectType", value: "none")
    sendEvent(name: "confidence", value: 0.0)
    updateMotionState("inactive")
}

def getStats() {
    log.info "Frigate Camera Device: Requesting stats for ${device.label} (v${driverVersion()})"
    parent?.getCameraStats(device.deviceNetworkId)
}

// Helper method to get camera name from device
def getCameraName() {
    return device.currentValue("cameraName") ?: device.label.replace("Frigate ", "").replace(" ", "_").toLowerCase()
}

// Parent calls this to update the latest snapshot URL (latest.jpg)
def updateLatestSnapshotUrl(String imageUrl) {
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateLatestSnapshotUrl() called for ${device.label} - url: ${imageUrl} (v${driverVersion()})"
    }
    if (imageUrl) {
        sendEvent(name: "latestSnapshotUrl", value: imageUrl)
    }
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}

// Parent calls this on motion start to store the event snapshot URL and reflect it in the image tile
def updateLastMotionSnapshotUrl(String imageUrl) {
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateLastMotionSnapshotUrl() called for ${device.label} - url: ${imageUrl} (v${driverVersion()})"
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
        log.debug "Frigate Camera Device: updateEventMetadata() called with data: ${data} (v${driverVersion()})"
    }
    def nowTs = new Date().format("yyyy-MM-dd HH:mm:ss")
    
    // Only send events when values actually change to reduce event queue pressure
    if (data.cameraName) {
        def current = device.currentValue("cameraName")
        if (current != data.cameraName) {
            sendEvent(name: "cameraName", value: data.cameraName, isStateChange: true)
        }
    }
    if (data.zoneName) {
        def current = device.currentValue("zoneName")
        if (current != data.zoneName) {
            sendEvent(name: "zoneName", value: data.zoneName, isStateChange: true)
        }
    }
    if (data.currentZones != null) {
        def value = data.currentZones instanceof Collection ? data.currentZones.join(",") : data.currentZones.toString()
        def finalValue = value ?: "none"
        def current = device.currentValue("currentZones")
        if (current != finalValue) {
            sendEvent(name: "currentZones", value: finalValue, isStateChange: true)
        }
    }
    if (data.enteredZones != null) {
        def value = data.enteredZones instanceof Collection ? data.enteredZones.join(",") : data.enteredZones.toString()
        def finalValue = value ?: "none"
        def current = device.currentValue("enteredZones")
        if (current != finalValue) {
            sendEvent(name: "enteredZones", value: finalValue, isStateChange: true)
        }
    }
    if (data.previousZones != null) {
        def value = data.previousZones instanceof Collection ? data.previousZones.join(",") : data.previousZones.toString()
        def finalValue = value ?: "none"
        def current = device.currentValue("previousZones")
        if (current != finalValue) {
            sendEvent(name: "previousZones", value: finalValue, isStateChange: true)
        }
    }
    if (data.eventId) {
        def current = device.currentValue("lastEventId")
        if (current != data.eventId) {
            sendEvent(name: "lastEventId", value: data.eventId, isStateChange: true)
        }
    }
    if (data.label != null) {
        def current = device.currentValue("lastEventLabel")
        if (current != data.label) {
            sendEvent(name: "lastEventLabel", value: data.label, isStateChange: true)
        }
    }
    if (data.confidence != null) {
        try {
            def newConfidence = new BigDecimal(data.confidence.toString())
            def current = device.currentValue("lastEventConfidence")
            if (current != newConfidence) {
                sendEvent(name: "lastEventConfidence", value: newConfidence, isStateChange: true)
            }
        } catch (Exception ignored) {
            def current = device.currentValue("lastEventConfidence")
            if (current != 0.0) {
                sendEvent(name: "lastEventConfidence", value: 0.0, isStateChange: true)
            }
        }
    }
    if (data.startTime != null) {
        // Ensure timestamp is formatted (handle both formatted strings and unix timestamps)
        def startTimeValue = data.startTime.toString()
        try {
            // Try to parse as number - if it's a large number (> 1000000000), it's likely a unix timestamp
            double seconds = startTimeValue.toDouble()
            if (seconds > 1000000000) {
                // It's a unix timestamp, format it
                long millis = (long)(seconds * 1000)
                def date = new Date(millis)
                def tz = location?.timeZone ?: TimeZone.getDefault()
                startTimeValue = date.format("yyyy-MM-dd HH:mm:ss", tz)
            }
        } catch (Exception ignored) {
            // Not a number, assume it's already formatted
        }
        def current = device.currentValue("lastEventStart")
        if (current != startTimeValue) {
            sendEvent(name: "lastEventStart", value: startTimeValue, isStateChange: true)
        }
    }
    if (data.endTime != null) {
        // Ensure timestamp is formatted (handle both formatted strings and unix timestamps)
        def endTimeValue = data.endTime.toString()
        try {
            // Try to parse as number - if it's a large number (> 1000000000), it's likely a unix timestamp
            double seconds = endTimeValue.toDouble()
            if (seconds > 1000000000) {
                // It's a unix timestamp, format it
                long millis = (long)(seconds * 1000)
                def date = new Date(millis)
                def tz = location?.timeZone ?: TimeZone.getDefault()
                endTimeValue = date.format("yyyy-MM-dd HH:mm:ss", tz)
            }
        } catch (Exception ignored) {
            // Not a number, assume it's already formatted
        }
        def current = device.currentValue("lastEventEnd")
        if (current != endTimeValue) {
            sendEvent(name: "lastEventEnd", value: endTimeValue, isStateChange: true)
        }
    }
    if (data.motionScore != null) {
        try {
            def newScore = new BigDecimal(data.motionScore.toString())
            def current = device.currentValue("motionScore")
            if (current != newScore) {
                sendEvent(name: "motionScore", value: newScore, isStateChange: true)
            }
        } catch (Exception ignored) {
            def current = device.currentValue("motionScore")
            if (current != 0.0) {
                sendEvent(name: "motionScore", value: 0.0, isStateChange: true)
            }
        }
    }
    if (data.trackId != null) {
        def current = device.currentValue("trackId")
        def newTrackId = data.trackId.toString()
        if (current != newTrackId) {
            sendEvent(name: "trackId", value: newTrackId, isStateChange: true)
        }
    }
    
    if (data.hasSnapshot != null) {
        def newValue = data.hasSnapshot ? "yes" : "no"
        def current = device.currentValue("hasSnapshot")
        if (current != newValue) {
            sendEvent(name: "hasSnapshot", value: newValue, isStateChange: true)
        }
    }
    if (data.hasClip != null) {
        def newValue = data.hasClip ? "yes" : "no"
        def current = device.currentValue("hasClip")
        if (current != newValue) {
            sendEvent(name: "hasClip", value: newValue, isStateChange: true)
        }
    }
    if (data.snapshotUrl) {
        def current = device.currentValue("lastSnapshotUrl")
        if (current != data.snapshotUrl) {
            sendEvent(name: "lastSnapshotUrl", value: data.snapshotUrl, isStateChange: true)
            sendEvent(name: "latestSnapshotUrl", value: data.snapshotUrl, isStateChange: true)
            sendEvent(name: "image", value: data.snapshotUrl, isStateChange: true)
        }
    }
    if (data.clipUrl) {
        def current = device.currentValue("lastClipUrl")
        if (current != data.clipUrl) {
            sendEvent(name: "lastClipUrl", value: data.clipUrl, isStateChange: true)
        }
    }
    if (data.lastMotionSnapshotUrl) {
        updateLastMotionSnapshotUrl(data.lastMotionSnapshotUrl)
    }
    
    // Always update lastUpdate timestamp (but use isStateChange: false to reduce queue pressure)
    sendEvent(name: "lastUpdate", value: nowTs, isStateChange: false)
}


