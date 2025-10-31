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
 * 
 * @author Simon Mason
 * @version 1.04
 * @date 2025-10-31
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
        capability "Refresh"
        capability "Image Capture"
        
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
        attribute "snapshotImage", "string"
        attribute "snapshotUrl", "string"
        
        // Commands
        command "refresh"
        command "updateMotionState", ["string"]
        command "updateObjectDetection", ["string", "number"]
        command "getSnapshot"
        command "getStats"
        command "updateSnapshot", ["string", "string"]
        command "take"
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
    
    // Schedule motion timeout
    runIn(motionTimeout ?: 30, "motionTimeoutHandler")
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
        sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
        
        if (state == "active") {
            log.info "Frigate Camera Device: Motion detected on ${device.label}"
            
            if (debugLogging) {
                log.debug "Frigate Camera Device: Scheduling motion timeout in ${motionTimeout ?: 30} seconds"
            }
            
            // Track detection time
            state.lastDetectionTime = now()
            
            // Cancel any existing timeout and schedule a new one
            unschedule("motionTimeoutHandler")
            runIn(motionTimeout ?: 30, "motionTimeoutHandler")
        } else if (state == "inactive") {
            // Cancel timeout if motion is set to inactive manually
            unschedule("motionTimeoutHandler")
            
            if (debugLogging) {
                log.debug "Frigate Camera Device: Motion set to inactive, canceled timeout"
            }
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

def updateObjectDetection(String objectType, Number confidence) {
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateObjectDetection() called - Object: ${objectType}, Confidence: ${confidence} on device: ${device.label}"
    }
    
    // Track when we last got a detection - used for timeout logic
    state.lastDetectionTime = now()
    
    // Update confidence
    sendEvent(name: "confidence", value: confidence)
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
        log.info "Frigate Camera Device: ${objectType} detected on ${device.label} with confidence ${confidence}"
        
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
        log.debug "Frigate Camera Device: Checking confidence ${confidence} against threshold ${threshold}"
    }
    
    if (confidence >= threshold) {
        if (debugLogging) {
            log.debug "Frigate Camera Device: Confidence ${confidence} >= threshold ${threshold}, updating motion to active"
        }
        updateMotionState("active")
    } else {
        if (debugLogging) {
            log.debug "Frigate Camera Device: Confidence ${confidence} < threshold ${threshold}, not updating motion state"
        }
    }
    
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateObjectDetection() completed"
    }
}

def motionTimeoutHandler() {
    log.info "Frigate Camera Device: Motion timeout handler called for ${device.label}"
    
    if (debugLogging) {
        log.debug "Frigate Camera Device: Checking if timeout should fire - last detection: ${state.lastDetectionTime ? new Date(state.lastDetectionTime).format('HH:mm:ss') : 'never'}"
    }
    
    // Check if we've received a detection since this timeout was scheduled
    def timeSinceLastDetection = state.lastDetectionTime ? (now() - state.lastDetectionTime) / 1000 : 9999
    def timeoutSeconds = motionTimeout ?: 30
    
    if (debugLogging) {
        log.debug "Frigate Camera Device: Time since last detection: ${timeSinceLastDetection}s, timeout: ${timeoutSeconds}s"
    }
    
    // Only reset if no detection in the timeout period
    if (timeSinceLastDetection >= timeoutSeconds) {
        log.info "Frigate Camera Device: Motion timeout reached (${timeSinceLastDetection}s since last detection) - resetting states for ${device.label}"
        
        // Only reset if motion is still active
        def currentMotion = device.currentValue("motion")
        if (currentMotion == "active") {
            sendEvent(name: "motion", value: "inactive")
            
            // Reset all object detection states when motion times out
            sendEvent(name: "personDetected", value: "no")
            sendEvent(name: "carDetected", value: "no")
            sendEvent(name: "dogDetected", value: "no")
            sendEvent(name: "catDetected", value: "no")
            sendEvent(name: "objectType", value: "none")
            sendEvent(name: "confidence", value: 0.0)
            
            sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
            
            log.info "Frigate Camera Device: Reset motion and object detection states for ${device.label}"
            
            if (debugLogging) {
                log.debug "Frigate Camera Device: Reset all object detection states to 'no'"
            }
        } else {
            if (debugLogging) {
                log.debug "Frigate Camera Device: Motion already inactive (${currentMotion}), skipping reset"
            }
        }
    } else {
        // Detection came in during timeout - reschedule
        def remainingTime = timeoutSeconds - timeSinceLastDetection
        if (debugLogging) {
            log.debug "Frigate Camera Device: Recent detection found (${timeSinceLastDetection}s ago), rescheduling timeout in ${remainingTime}s"
        }
        runIn((int)remainingTime, "motionTimeoutHandler")
    }
}

def getSnapshot() {
    log.info "Frigate Camera Device: Requesting snapshot for ${device.label}"
    parent?.getCameraSnapshot(getCameraName())
}

def getStats() {
    log.info "Frigate Camera Device: Requesting stats for ${device.label}"
    parent?.getCameraStats(device.deviceNetworkId)
}

// Standard Image Capture capability command
def take() {
    if (debugLogging) {
        log.debug "Frigate Camera Device: take() called - requesting snapshot"
    }
    getSnapshot()
}

// Helper method to get camera name from device
def getCameraName() {
    return device.currentValue("cameraName") ?: device.label.replace("Frigate ", "").replace(" ", "_").toLowerCase()
}

// Parent calls this to update the snapshot image and URL on the device
def updateSnapshot(String imageDataUri, String imageUrl) {
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateSnapshot() called for ${device.label} - url: ${imageUrl}"
    }
    if (imageDataUri) {
        sendEvent(name: "snapshotImage", value: imageDataUri)
    }
    if (imageUrl) {
        sendEvent(name: "snapshotUrl", value: imageUrl)
    }
    // Standard tile rendering
    def imageForTile = imageUrl ?: imageDataUri
    if (imageForTile) {
        sendEvent(name: "image", value: imageForTile)
    }
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}


