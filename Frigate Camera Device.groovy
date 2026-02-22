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
 * 1.10 - 2026-02-22 - PERFORMANCE: Moved informational attributes (event IDs, zones, scores, timestamps) to state variables instead of sendEvent() to dramatically reduce hub event volume. Removed redundant lastDetection attribute. Consolidated lastUpdate to only fire on motion state changes. Optimized clearDetections() to check current values before sending. Reduces sendEvent calls per message from ~19 to ~5.
 *
 * @author Simon Mason
 * @version 1.10
 * @date 2026-02-22
 */

/**
 * Returns the current driver version number
 * This is used in all log statements to ensure version consistency
 */
String driverVersion() { return "1.10" }

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

        // Automation-relevant attributes (drive rules and dashboards)
        attribute "personDetected", "string"
        attribute "carDetected", "string"
        attribute "dogDetected", "string"
        attribute "catDetected", "string"
        attribute "confidence", "number"
        attribute "objectType", "string"
        attribute "cameraName", "string"
        attribute "lastUpdate", "string"
        attribute "latestSnapshotUrl", "string"
        attribute "lastMotionSnapshotUrl", "string"
        attribute "lastSnapshotUrl", "string"
        attribute "image", "string"
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

    // Set initial states for automation-relevant attributes
    sendEvent(name: "motion", value: "inactive")
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "personDetected", value: "no")
    sendEvent(name: "carDetected", value: "no")
    sendEvent(name: "dogDetected", value: "no")
    sendEvent(name: "catDetected", value: "no")
    sendEvent(name: "confidence", value: 0.0)
    sendEvent(name: "objectType", value: "none")
    sendEvent(name: "cameraName", value: device.label.replace("Frigate ", "").replace(" ", "_").toLowerCase())
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    sendEvent(name: "lastSnapshotUrl", value: "")
    sendEvent(name: "version", value: driverVersion())

    // Initialize informational metadata in state (not sendEvent)
    state.lastEventId = ""
    state.lastEventLabel = ""
    state.lastEventConfidence = 0.0
    state.lastEventStart = ""
    state.lastEventEnd = ""
    state.motionScore = 0.0
    state.trackId = ""
    state.currentZones = "none"
    state.enteredZones = "none"
    state.previousZones = "none"
    state.hasSnapshot = false
    state.hasClip = false
    state.lastClipUrl = ""
}

def refresh() {
    log.info "Frigate Camera Device: Refreshing device state (v${driverVersion()})"
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}

def updateMotionState(String motionState) {
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateMotionState() called with state: ${motionState} for device: ${device.label} (v${driverVersion()})"
    }

    def currentMotion = device.currentValue("motion")

    if (currentMotion != motionState) {
        sendEvent(name: "motion", value: motionState)
        sendEvent(name: "switch", value: (motionState == "active") ? "on" : "off")
        sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))

        if (motionState == "active") {
            log.info "Frigate Camera Device: Motion detected on ${device.label} (v${driverVersion()})"
        }
    }
}

def on() {
    updateMotionState("active")
}

def off() {
    updateMotionState("inactive")
}

def updateObjectDetection(String objectType, Number confidence) {
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateObjectDetection() called - Object: ${objectType}, Confidence: ${confidence} on device: ${device.label} (v${driverVersion()})"
    }

    state.lastDetectionTime = now()

    BigDecimal numericConfidence = 0.0G
    try {
        if (confidence != null) {
            numericConfidence = new BigDecimal(confidence.toString())
        }
    } catch (Exception ignored) {
        numericConfidence = 0.0G
    }

    // Update confidence and objectType (only if changed)
    def currentConfidence = device.currentValue("confidence")
    if (currentConfidence != numericConfidence) {
        sendEvent(name: "confidence", value: numericConfidence)
    }

    def currentObjectType = device.currentValue("objectType")
    if (currentObjectType != objectType) {
        sendEvent(name: "objectType", value: objectType)
    }

    // Update per-object detection flags (only send events for changes)
    def objectStates = [
        "person": "personDetected",
        "car": "carDetected",
        "dog": "dogDetected",
        "cat": "catDetected"
    ]

    objectStates.each { objType, attrName ->
        def currentValue = device.currentValue(attrName)
        if (objType != objectType && currentValue == "yes") {
            sendEvent(name: attrName, value: "no")
        }
    }

    if (objectStates.containsKey(objectType)) {
        def attrName = objectStates[objectType]
        def currentValue = device.currentValue(attrName)
        if (currentValue != "yes") {
            sendEvent(name: attrName, value: "yes")
            log.info "Frigate Camera Device: ${objectType} detected on ${device.label} with confidence ${numericConfidence} (v${driverVersion()})"
        }
    }

    // Update motion state if confidence is above threshold
    def threshold = confidenceThreshold ?: 0.5
    def thresholdValue = 0.0G
    try {
        thresholdValue = new BigDecimal(threshold.toString())
    } catch (Exception ignored) {
        thresholdValue = 0.5G
    }

    if (numericConfidence >= thresholdValue) {
        updateMotionState("active")
    }
}

// No motion timeout logic; motion goes inactive on count=0 messages from Frigate

def clearDetections() {
    if (debugLogging) {
        log.debug "Frigate Camera Device: clearDetections() called for ${device.label} (v${driverVersion()})"
    }
    // Only send events for values that actually need to change
    ["personDetected", "carDetected", "dogDetected", "catDetected"].each { attr ->
        if (device.currentValue(attr) != "no") {
            sendEvent(name: attr, value: "no")
        }
    }
    if (device.currentValue("objectType") != "none") {
        sendEvent(name: "objectType", value: "none")
    }
    if (device.currentValue("confidence") != 0.0) {
        sendEvent(name: "confidence", value: 0.0)
    }
    updateMotionState("inactive")
}

def getStats() {
    log.info "Frigate Camera Device: Requesting stats for ${device.label} (v${driverVersion()})"
    parent?.getCameraStats(device.deviceNetworkId)
}

def getCameraName() {
    return device.currentValue("cameraName") ?: device.label.replace("Frigate ", "").replace(" ", "_").toLowerCase()
}

def updateLatestSnapshotUrl(String imageUrl) {
    if (imageUrl) {
        def current = device.currentValue("latestSnapshotUrl")
        if (current != imageUrl) {
            sendEvent(name: "latestSnapshotUrl", value: imageUrl)
        }
    }
}

def updateLastMotionSnapshotUrl(String imageUrl) {
    if (imageUrl) {
        def current = device.currentValue("lastMotionSnapshotUrl")
        if (current != imageUrl) {
            sendEvent(name: "lastMotionSnapshotUrl", value: imageUrl)
            sendEvent(name: "image", value: imageUrl)
        }
    }
}

def updateEventMetadata(Map data) {
    if (!data) {
        return
    }
    if (debugLogging) {
        log.debug "Frigate Camera Device: updateEventMetadata() called for ${device.label} (v${driverVersion()})"
    }

    // Store informational metadata in state variables (no sendEvent overhead)
    if (data.cameraName) {
        def current = device.currentValue("cameraName")
        if (current != data.cameraName) {
            sendEvent(name: "cameraName", value: data.cameraName)
        }
    }
    if (data.eventId) { state.lastEventId = data.eventId }
    if (data.label != null) { state.lastEventLabel = data.label }
    if (data.confidence != null) {
        try { state.lastEventConfidence = new BigDecimal(data.confidence.toString()) }
        catch (Exception ignored) { state.lastEventConfidence = 0.0 }
    }
    if (data.startTime != null) { state.lastEventStart = formatTimestamp(data.startTime) }
    if (data.endTime != null) { state.lastEventEnd = formatTimestamp(data.endTime) }
    if (data.motionScore != null) {
        try { state.motionScore = new BigDecimal(data.motionScore.toString()) }
        catch (Exception ignored) { state.motionScore = 0.0 }
    }
    if (data.trackId != null) { state.trackId = data.trackId.toString() }
    if (data.currentZones != null) {
        state.currentZones = data.currentZones instanceof Collection ? data.currentZones.join(",") : (data.currentZones.toString() ?: "none")
    }
    if (data.enteredZones != null) {
        state.enteredZones = data.enteredZones instanceof Collection ? data.enteredZones.join(",") : (data.enteredZones.toString() ?: "none")
    }
    if (data.previousZones != null) {
        state.previousZones = data.previousZones instanceof Collection ? data.previousZones.join(",") : (data.previousZones.toString() ?: "none")
    }
    if (data.hasSnapshot != null) { state.hasSnapshot = (data.hasSnapshot == true) }
    if (data.hasClip != null) { state.hasClip = (data.hasClip == true) }
    if (data.clipUrl) { state.lastClipUrl = data.clipUrl }

    // Snapshot URLs are kept as sendEvent (used by dashboard tiles)
    if (data.snapshotUrl) {
        def current = device.currentValue("lastSnapshotUrl")
        if (current != data.snapshotUrl) {
            sendEvent(name: "lastSnapshotUrl", value: data.snapshotUrl)
            sendEvent(name: "latestSnapshotUrl", value: data.snapshotUrl)
            sendEvent(name: "image", value: data.snapshotUrl)
        }
    }
    if (data.lastMotionSnapshotUrl) {
        updateLastMotionSnapshotUrl(data.lastMotionSnapshotUrl)
    }
}

private String formatTimestamp(def value) {
    if (value == null) return ""
    def strValue = value.toString()
    try {
        double seconds = strValue.toDouble()
        if (seconds > 1000000000) {
            long millis = (long)(seconds * 1000)
            def date = new Date(millis)
            def tz = location?.timeZone ?: TimeZone.getDefault()
            return date.format("yyyy-MM-dd HH:mm:ss", tz)
        }
    } catch (Exception ignored) {}
    return strValue
}
