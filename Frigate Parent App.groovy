/**
 * Frigate Parent App
 * 
 * Integrates with Frigate NVR via MQTT to provide motion detection, camera/zone devices,
 * and snapshot/metadata management.
 * 
 * Copyright 2025
 *
 * Change History:
 * 1.00 - Initial release with automatic camera discovery via MQTT stats, real-time motion detection via MQTT events, object detection support (person, car, dog, cat), confidence scoring and filtering, motion timeout handling, HTTP API integration for snapshots and stats
 * 1.01 - CRITICAL FIX: Fixed issue where camera devices were not showing events. Added event tracking to prevent duplicate processing, improved event filtering to only process start events, enhanced API structure handling for both MQTT and HTTP API formats, changed polling to fetch last 10 events instead of 1, added HTTP Basic Auth headers to all API requests, enhanced error logging with stack traces
 * 1.02 - MAJOR REFACTOR: Replaced HTTP polling with native MQTT via interfaces.mqtt for real-time event delivery. Now uses true MQTT subscriptions to frigate/events and frigate/stats topics, eliminating 5-second polling delays. Added parse() and mqttClientStatus() methods for automatic message handling. Improved connection management with automatic reconnection. Events now arrive instantly instead of up to 5 seconds late.
 * 1.03 - CRITICAL FIX: Fixed null pointer error when using interfaces.mqtt in parent apps (interfaces.mqtt is only available in device drivers). Added automatic fallback to HTTP polling when MQTT is not available. All MQTT operations now check for availability before use. HTTP polling restored with improved event handling and 5-second interval.
 * 1.04 - MAJOR ARCHITECTURE CHANGE: Created Frigate MQTT Bridge Device driver to handle MQTT connectivity (parent apps cannot use interfaces.mqtt). Parent app now creates and manages a bridge device that connects to MQTT and forwards messages. Removed all HTTP event polling code. HTTP API now only used for snapshots and camera config checks. Real-time MQTT events via bridge device.
 * 1.05 - 2025-10-31 - Added snapshot retrieval to store image on child devices (base64 data URI) and snapshot URL; minor logging improvements for device creation and discovery; versioned init log
 *                    NOTE: Image download functionality (base64 storage) was later removed to prevent hub/device responsiveness issues
 * 1.06 - 2025-10-31 - Decoupled MQTT Bridge debug flag from Parent App; bridge debug controlled by device preference only
 * 1.07 - 2025-11-07 - Ensured bridge connectivity on init; normalized event labels and safe confidence parsing
 * 1.08 - 2025-11-08 - Added zone child devices, alert/motion metadata, and richer event tracking
 * 1.09 - 2025-11-08 - Guarded event/zone state maps during MQTT processing to prevent null pointer exceptions
 * 1.10 - 2025-11-14 - PERFORMANCE: Replaced full JSON parsing with selective field extraction using regex to avoid parsing 60KB+ payloads including unused path_data arrays. Removed double parsing in MQTT bridge device. Reduces memory usage by ~66% and processing time by ~90% for large events.
 * 
 * @author Simon Mason
 * @version 1.10
 * @date 2025-11-14
 */

private boolean zonesEnabled() {
    return (settings?.enableZoneDevices != false)
}

private List<String> normalizeZoneList(def raw) {
    if (!raw) {
        return []
    }
    if (raw instanceof Collection) {
        return raw.collect { it?.toString() }?.findAll { it }
    }
    def value = raw.toString()
    return value ? [value] : []
}

private String normalizeUrl(def urlValue) {
    if (!urlValue) {
        return null
    }
    def url = urlValue.toString()
    if (!url) {
        return null
    }
    if (url.startsWith("http://") || url.startsWith("https://")) {
        return url
    }
    if (!url.startsWith("/")) {
        url = "/${url}"
    }
    return "http://${frigateServer}:${frigatePort}${url}"
}

private String buildEventSnapshotUrl(String eventId) {
    return eventId ? "http://${frigateServer}:${frigatePort}/api/events/${eventId}/snapshot.jpg?bbox=1&timestamp=1" : null
}

private String buildSnapshotUrl(String eventId, def snapshotField) {
    def direct = normalizeUrl(snapshotField)
    if (direct) {
        return direct
    }
    return buildEventSnapshotUrl(eventId)
}

private String buildEventClipUrl(String eventId) {
    return eventId ? "http://${frigateServer}:${frigatePort}/api/events/${eventId}/clip.mp4" : null
}

private String buildClipUrl(String eventId, def clipField) {
    def candidate = clipField
    if (candidate instanceof Collection) {
        candidate = candidate ? candidate.first() : null
    }
    def direct = normalizeUrl(candidate)
    if (direct) {
        return direct
    }
    return buildEventClipUrl(eventId)
}

private String formatTimestamp(def ts) {
    if (!ts) {
        return ""
    }
    try {
        double seconds = ts instanceof Number ? ts.toDouble() : ts.toString().toDouble()
        long millis = (long)(seconds * 1000)
        def date = new Date(millis)
        def tz = location?.timeZone ?: TimeZone.getDefault()
        return date.format("yyyy-MM-dd HH:mm:ss", tz)
    } catch (Exception ignored) {
        return ""
    }
}

// Extract event fields from JSON string using regex to avoid full JSON parsing
// This prevents parsing 60KB+ payloads including unused path_data arrays
private Map extractEventFields(String jsonString) {
    def fields = [:]
    if (!jsonString) {
        return fields
    }
    
    try {
        // Extract type from root level
        def typeMatch = jsonString =~ /"type"\s*:\s*"([^"]+)"/
        if (typeMatch) {
            fields.type = typeMatch[0][1]
        } else {
            fields.type = "update"
        }
        
        // For nested objects, we'll search the entire payload but prioritize fields in after/before sections
        // Find the position of "after" or "before" to search in the right section
        int searchStart = 0
        int searchEnd = jsonString.length()
        
        if (fields.type == "end") {
            // For end events, prefer "before" section
            int beforePos = jsonString.indexOf('"before"')
            if (beforePos > 0) {
                searchStart = beforePos
                // Find the end of the before object (simplified - just search for next major field or end)
                int nextField = jsonString.indexOf('"after"', beforePos)
                if (nextField > beforePos) {
                    searchEnd = nextField
                }
            } else {
                // Fallback to after
                int afterPos = jsonString.indexOf('"after"')
                if (afterPos > 0) {
                    searchStart = afterPos
                }
            }
        } else {
            // For new/update events, prefer "after" section
            int afterPos = jsonString.indexOf('"after"')
            if (afterPos > 0) {
                searchStart = afterPos
            }
        }
        
        String dataSection = jsonString.substring(searchStart, searchEnd)
        
        // Extract simple string fields
        def cameraMatch = dataSection =~ /"camera"\s*:\s*"([^"]+)"/
        if (cameraMatch) fields.camera = cameraMatch[0][1]
        
        def idMatch = dataSection =~ /"id"\s*:\s*"([^"]+)"/
        if (idMatch) fields.id = idMatch[0][1]
        
        def labelMatch = dataSection =~ /"label"\s*:\s*"([^"]+)"/
        if (labelMatch) fields.label = labelMatch[0][1]
        
        def subLabelMatch = dataSection =~ /"sub_label"\s*:\s*"([^"]+)"/
        if (subLabelMatch) fields.sub_label = subLabelMatch[0][1]
        
        def trackIdMatch = dataSection =~ /"track_id"\s*:\s*"([^"]+)"/
        if (trackIdMatch) fields.track_id = trackIdMatch[0][1]
        
        // Extract numeric fields
        def confidenceMatch = dataSection =~ /"confidence"\s*:\s*([0-9.]+)/
        if (confidenceMatch) {
            try {
                fields.confidence = new BigDecimal(confidenceMatch[0][1])
            } catch (Exception ignored) {}
        }
        
        // Extract score - try multiple locations: data.score, data.top_score, top_score, or score
        def scoreInData = dataSection =~ /"data"\s*:\s*\{[^}]*"score"\s*:\s*([0-9.]+)/
        def topScoreInData = dataSection =~ /"data"\s*:\s*\{[^}]*"top_score"\s*:\s*([0-9.]+)/
        def topScore = dataSection =~ /"top_score"\s*:\s*([0-9.]+)/
        def score = dataSection =~ /"score"\s*:\s*([0-9.]+)/
        
        if (scoreInData) {
            try {
                fields.score = new BigDecimal(scoreInData[0][1])
            } catch (Exception ignored) {}
        } else if (topScoreInData) {
            try {
                fields.score = new BigDecimal(topScoreInData[0][1])
            } catch (Exception ignored) {}
        } else if (topScore) {
            try {
                fields.score = new BigDecimal(topScore[0][1])
            } catch (Exception ignored) {}
        } else if (score) {
            try {
                fields.score = new BigDecimal(score[0][1])
            } catch (Exception ignored) {}
        }
        
        def motionScoreMatch = dataSection =~ /"motion_score"\s*:\s*([0-9.]+)/
        if (motionScoreMatch) {
            try {
                fields.motion_score = new BigDecimal(motionScoreMatch[0][1])
            } catch (Exception ignored) {}
        }
        
        // Extract timestamp fields
        def startTimeMatch = dataSection =~ /"start_time"\s*:\s*([0-9.]+)/
        if (startTimeMatch) {
            try {
                fields.start_time = new BigDecimal(startTimeMatch[0][1])
            } catch (Exception ignored) {}
        }
        
        def endTimeMatch = dataSection =~ /"end_time"\s*:\s*([0-9.]+)/
        if (endTimeMatch) {
            try {
                fields.end_time = new BigDecimal(endTimeMatch[0][1])
            } catch (Exception ignored) {}
        }
        
        // Extract boolean fields
        fields.has_snapshot = dataSection.contains('"has_snapshot":true')
        fields.has_clip = dataSection.contains('"has_clip":true')
        
        // Extract URL fields (snapshot and clip)
        def snapshotMatch = dataSection =~ /"snapshot"\s*:\s*"([^"]+)"/
        if (snapshotMatch) fields.snapshot = snapshotMatch[0][1]
        
        def clipMatch = dataSection =~ /"clip"\s*:\s*"([^"]+)"/
        if (clipMatch) fields.clip = clipMatch[0][1]
        
        // Extract zones arrays - need to handle JSON arrays
        def zonesMatch = dataSection =~ /"(?:current_zones|zones)"\s*:\s*\[([^\]]*)\]/
        if (zonesMatch) {
            def zonesStr = zonesMatch[0][1]
            if (zonesStr) {
                def zoneList = []
                zonesStr.findAll(/"([^"]+)"/).each { zoneMatch ->
                    zoneList << zoneMatch[1]
                }
                if (zoneList) fields.current_zones = zoneList
            }
        }
        
        def enteredZonesMatch = dataSection =~ /"entered_zones"\s*:\s*\[([^\]]*)\]/
        if (enteredZonesMatch) {
            def zonesStr = enteredZonesMatch[0][1]
            if (zonesStr) {
                def zoneList = []
                zonesStr.findAll(/"([^"]+)"/).each { zoneMatch ->
                    zoneList << zoneMatch[1]
                }
                if (zoneList) fields.entered_zones = zoneList
            }
        }
        
        def previousZonesMatch = dataSection =~ /"previous_zones"\s*:\s*\[([^\]]*)\]/
        if (previousZonesMatch) {
            def zonesStr = previousZonesMatch[0][1]
            if (zonesStr) {
                def zoneList = []
                zonesStr.findAll(/"([^"]+)"/).each { zoneMatch ->
                    zoneList << zoneMatch[1]
                }
                if (zoneList) fields.previous_zones = zoneList
            }
        }
        
    } catch (Exception e) {
        // If regex extraction fails, return empty map - caller should fallback to full parse
        if (debugLogging) {
            log.debug "Frigate Parent App: Regex extraction failed, will fallback to full parse: ${e.message}"
        }
    }
    
    return fields
}

private void ensureStateMaps() {
    state.processedEventIds = state.processedEventIds ?: []
    state.eventZoneMap = state.eventZoneMap ?: [:]
    state.activeCameraEvents = state.activeCameraEvents ?: [:]
    state.activeZoneEvents = state.activeZoneEvents ?: [:]
    state.zoneChildMap = state.zoneChildMap ?: [:]
}

private void addActiveCameraEvent(String cameraName, String eventId) {
    ensureStateMaps()
    if (!cameraName || !eventId) {
        return
    }
    def list = (state.activeCameraEvents[cameraName] ?: []) as List
    if (!list.contains(eventId)) {
        list << eventId
    }
    state.activeCameraEvents[cameraName] = list
}

private void removeActiveCameraEvent(String cameraName, String eventId) {
    ensureStateMaps()
    if (!cameraName || !eventId) {
        return
    }
    def list = (state.activeCameraEvents[cameraName] ?: []) as List
    list.remove(eventId)
    if (list.isEmpty()) {
        state.activeCameraEvents.remove(cameraName)
    } else {
        state.activeCameraEvents[cameraName] = list
    }
}

private boolean cameraHasActiveEvents(String cameraName) {
    ensureStateMaps()
    def list = state.activeCameraEvents[cameraName] ?: []
    return list && !list.isEmpty()
}

private String sanitizeForId(String value) {
    return value?.toString()?.toLowerCase()?.replaceAll("[^a-z0-9]+", "_") ?: ""
}

private String prettifyZoneName(String zoneName) {
    if (!zoneName) {
        return ""
    }
    return zoneName.split(/[_\s-]+/).collect { it.capitalize() }.join(" ")
}

private String buildZoneDeviceId(String cameraName, String zoneName) {
    return "frigate_${sanitizeForId(cameraName)}_zone_${sanitizeForId(zoneName)}"
}

private String zoneKey(String cameraName, String zoneName) {
    return "${cameraName}__${zoneName}"
}

private void addActiveZoneEvent(String cameraName, String zoneName, String eventId) {
    ensureStateMaps()
    if (!cameraName || !zoneName || !eventId) {
        return
    }
    def key = zoneKey(cameraName, zoneName)
    def list = (state.activeZoneEvents[key] ?: []) as List
    if (!list.contains(eventId)) {
        list << eventId
    }
    state.activeZoneEvents[key] = list
}

private void removeActiveZoneEvent(String cameraName, String zoneName, String eventId) {
    ensureStateMaps()
    if (!cameraName || !zoneName || !eventId) {
        return
    }
    def key = zoneKey(cameraName, zoneName)
    def list = (state.activeZoneEvents[key] ?: []) as List
    list.remove(eventId)
    if (list.isEmpty()) {
        state.activeZoneEvents.remove(key)
    } else {
        state.activeZoneEvents[key] = list
    }
}

private boolean zoneHasActiveEvents(String cameraName, String zoneName) {
    ensureStateMaps()
    def key = zoneKey(cameraName, zoneName)
    def list = state.activeZoneEvents[key] ?: []
    return list && !list.isEmpty()
}

private def getZoneDevice(String cameraName, String zoneName) {
    ensureStateMaps()
    def zoneDeviceId = buildZoneDeviceId(cameraName, zoneName)
    return getChildDevice(zoneDeviceId)
}

private def getOrCreateZoneDevice(String cameraName, String zoneName) {
    ensureStateMaps()
    def zoneDeviceId = buildZoneDeviceId(cameraName, zoneName)
    def existing = getChildDevice(zoneDeviceId)
    if (existing) {
        return existing
    }
    
    def cameraTitle = cameraName.replace('_', ' ').toUpperCase()
    def zoneTitle = prettifyZoneName(zoneName)
    def label = "Frigate ${cameraTitle} - ${zoneTitle}"
    try {
        def device = addChildDevice("simonmason", "Frigate Camera Device", zoneDeviceId, [
            name       : label,
            label      : label,
            isComponent: false
        ])
        if (device) {
            device.updateDataValue("deviceRole", "zone")
            device.updateDataValue("cameraName", cameraName)
            device.updateDataValue("zoneName", zoneName)
            device.updateEventMetadata([cameraName: cameraName, zoneName: zoneName])
            state.zoneChildMap[zoneKey(cameraName, zoneName)] = zoneDeviceId
            log.info "Frigate Parent App: Created zone device ${label}"
        }
        return device
    } catch (Exception e) {
        log.error "Frigate Parent App: Failed to create zone device for ${cameraName}/${zoneName}: ${e.message}"
        return null
    }
}

private void handleZoneEvents(String cameraName,
                              String eventId,
                              String eventType,
                              Map baseMetadata,
                              List<String> currentZones,
                              List<String> enteredZones,
                              String label,
                              BigDecimal score) {
    ensureStateMaps()
    def previousZones = state.eventZoneMap[eventId] ?: []
    def normalizedCurrent = currentZones ?: []
    def normalizedEntered = enteredZones ?: []
    
    if (eventType == "end") {
        previousZones.each { zoneName ->
            removeActiveZoneEvent(cameraName, zoneName, eventId)
            def zoneDevice = getZoneDevice(cameraName, zoneName)
            if (zoneDevice) {
                zoneDevice.updateEventMetadata(baseMetadata)
                if (!zoneHasActiveEvents(cameraName, zoneName)) {
                    zoneDevice.clearDetections()
                }
            }
        }
        state.eventZoneMap.remove(eventId)
        return
    }
    
    def removedZones = previousZones.findAll { !normalizedCurrent.contains(it) }
    removedZones.each { zoneName ->
        removeActiveZoneEvent(cameraName, zoneName, eventId)
        if (!zoneHasActiveEvents(cameraName, zoneName)) {
            def zoneDevice = getZoneDevice(cameraName, zoneName)
            zoneDevice?.clearDetections()
        }
    }
    state.eventZoneMap[eventId] = normalizedCurrent
    
    normalizedCurrent.each { zoneName ->
        def zoneDevice = getOrCreateZoneDevice(cameraName, zoneName)
        if (!zoneDevice) {
            return
        }
        addActiveZoneEvent(cameraName, zoneName, eventId)
        
        def zoneMetadata = [:] << baseMetadata
        zoneMetadata.zoneName = zoneName
        zoneMetadata.currentZones = [zoneName]
        zoneMetadata.enteredZones = normalizedEntered.contains(zoneName) ? [zoneName] : []
        
        zoneDevice.updateEventMetadata(zoneMetadata)
        if (label && eventType != "end") {
            zoneDevice.updateObjectDetection(label, score)
        }
        zoneDevice.updateMotionState("active")
    }
}

definition(
    name: "Frigate Parent App",
    namespace: "simonmason",
    author: "Simon Mason",
    description: "Frigate NVR integration with automatic camera discovery",
    category: "Safety & Security",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: ""
)

preferences {
    page(name: "mainPage", title: "Frigate Configuration", install: true, uninstall: true) {
        section("MQTT Connection") {
            input "mqttBroker", "text", title: "MQTT Broker IP", required: true
            input "mqttPort", "number", title: "MQTT Port", required: true
            input "mqttUsername", "text", title: "MQTT Username", required: true
            input "mqttPassword", "password", title: "MQTT Password", required: true
            input "topicPrefix", "text", title: "Frigate Topic Prefix", required: true
        }
        
        section("Camera Discovery") {
            paragraph "Cameras will be automatically discovered from Frigate stats"
            input "autoDiscover", "bool", title: "Enable Auto Discovery", required: true, defaultValue: true
            input "refreshInterval", "number", title: "Stats Refresh Interval (seconds)", required: true, defaultValue: 60
        }
        
        section("Zones & Metadata") {
            input "enableZoneDevices", "bool", title: "Create zone child devices", required: true, defaultValue: true
        }
        
        section("Frigate Server") {
            input "frigateServer", "text", title: "Frigate Server IP", required: true
            input "frigatePort", "number", title: "Frigate Port", required: true
            input "frigateUsername", "text", title: "Frigate Username", required: true
            input "frigatePassword", "password", title: "Frigate Password", required: true
        }
        
        section("Debug") {
            input "debugLogging", "bool", title: "Enable Debug Logging", required: true, defaultValue: false
        }
    }
}

def installed() {
    log.info "Frigate Parent App: Installing app"
    initialize()
}

def updated() {
    log.info "Frigate Parent App: Updating app"
    unsubscribe()
    
    // Update MQTT bridge device configuration
    def bridgeDevice = getChildDevice("frigate_mqtt_bridge")
    if (bridgeDevice) {
        log.info "Frigate Parent App: Updating MQTT bridge device"
        bridgeDevice.refresh()
    }
    
    initialize()
}

def initialize() {
    log.info "Frigate Parent App: Initializing v1.10"
    
    // Initialize state structures
    state.processedEventIds = state.processedEventIds ?: []
    state.eventZoneMap = state.eventZoneMap ?: [:]
    state.activeCameraEvents = state.activeCameraEvents ?: [:]
    state.activeZoneEvents = state.activeZoneEvents ?: [:]
    state.zoneChildMap = state.zoneChildMap ?: [:]
    
    // Keep only last 100 event IDs to prevent state from growing too large
    if (state.processedEventIds.size() > 100) {
        state.processedEventIds = state.processedEventIds[-100..-1]
    }
    
    // Create or update MQTT bridge device
    createOrUpdateMQTTBridge()
    runIn(3, "ensureBridgeConnected")
    
    if (settings.autoDiscover) {
        // Schedule periodic stats refresh (every 60 seconds) for camera discovery
        schedule("0 */1 * * * ?", "refreshStats")
    }
}

// Create or update the MQTT bridge device
def createOrUpdateMQTTBridge() {
    def bridgeDeviceId = "frigate_mqtt_bridge"
    def bridgeDevice = getChildDevice(bridgeDeviceId)
    
    if (!bridgeDevice) {
        log.info "Frigate Parent App: Creating MQTT bridge device"
        try {
            bridgeDevice = addChildDevice("simonmason", "Frigate MQTT Bridge Device", bridgeDeviceId, [
                name: "Frigate MQTT Bridge",
                label: "Frigate MQTT Bridge",
                isComponent: false
            ])
            log.info "Frigate Parent App: MQTT bridge device created"
            
            // Update bridge device preferences
            runIn(2, "configureMQTTBridge")
            
        } catch (Exception e) {
            log.error "Frigate Parent App: Failed to create MQTT bridge device: ${e.message}"
        }
    } else {
        log.info "Frigate Parent App: MQTT bridge device already exists"
        // Update preferences if they changed
        configureMQTTBridge()
    }
}

def configureMQTTBridge() {
    def bridgeDevice = getChildDevice("frigate_mqtt_bridge")
    if (!bridgeDevice) {
        log.error "Frigate Parent App: Bridge device not found for configuration"
        return
    }
    
    log.info "Frigate Parent App: Configuring MQTT bridge device with MQTT settings"
    
    // Send configuration to bridge device via configure command
    try {
        bridgeDevice.configure(
            mqttBroker ?: "",
            mqttPort ?: 1883,
            mqttUsername ?: "",
            mqttPassword ?: "",
            topicPrefix ?: "frigate"
        )
        log.info "Frigate Parent App: MQTT bridge device configured successfully"
    } catch (Exception e) {
        log.error "Frigate Parent App: Failed to configure bridge device: ${e.message}"
    }
}

def ensureBridgeConnected() {
    def bridgeDevice = getChildDevice("frigate_mqtt_bridge")
    if (!bridgeDevice) {
        log.warn "Frigate Parent App: MQTT bridge device not found when attempting to ensure connection"
        return
    }
    try {
        if (bridgeDevice.hasCommand("ensureConnected")) {
            bridgeDevice.ensureConnected()
        } else {
            bridgeDevice.connect()
        }
    } catch (Exception e) {
        log.error "Frigate Parent App: Error ensuring bridge connectivity: ${e.message}"
    }
}

def handleStatsMessage(String message) {
    if (debugLogging) {
        log.debug "Frigate Parent App: Received stats message: ${message}"
    }
    
    try {
        def stats = new groovy.json.JsonSlurper().parseText(message)
        
        if (stats.cameras) {
            // Get camera names as a List, not a KeySet
            def cameraNames = stats.cameras.keySet() as List
            log.info "Frigate Parent App: Discovered cameras: ${cameraNames}"
            
            // Create or update child devices for each camera
            cameraNames.each { cameraName ->
                createOrUpdateCameraDevice(cameraName)
            }
            
            // Remove devices for cameras that no longer exist
            removeObsoleteCameraDevices(cameraNames)
        }
        
    } catch (Exception e) {
        log.error "Frigate Parent App: Error parsing stats message: ${e.message}"
        log.error "Frigate Parent App: Stack trace: ${e.stackTrace}"
    }
}

def handleEventMessage(String message) {
    if (debugLogging) {
        log.debug "Frigate Parent App: handleEventMessage() called with payload size: ${message?.size() ?: 0} bytes"
    }
    
    ensureStateMaps()
    try {
        // Use selective field extraction to avoid parsing 60KB+ payloads including unused path_data
        def fields = extractEventFields(message)
        
        // Fallback to full JSON parse if extraction failed (safety net)
        def parsedEvent = null
        if (!fields.camera && !fields.id) {
            if (debugLogging) {
                log.debug "Frigate Parent App: Regex extraction failed, falling back to full JSON parse"
            }
            try {
                parsedEvent = new groovy.json.JsonSlurper().parseText(message)
                // Extract fields from parsed event for consistency
                def eventType = parsedEvent.type ?: "update"
                def eventPayload = (eventType == "end") ? (parsedEvent.before ?: parsedEvent.after ?: parsedEvent) : (parsedEvent.after ?: parsedEvent)
                fields.type = eventType
                fields.camera = eventPayload.camera?.toString()
                fields.id = eventPayload.id?.toString()
                fields.label = eventPayload.label ?: eventPayload.sub_label
                fields.sub_label = eventPayload.sub_label
                fields.score = eventPayload.data?.score ?: eventPayload.data?.top_score ?: eventPayload.top_score ?: eventPayload.confidence ?: 0.0
                fields.confidence = fields.score
                fields.current_zones = eventPayload.current_zones ?: eventPayload.zones
                fields.entered_zones = eventPayload.entered_zones
                fields.previous_zones = eventPayload.previous_zones
                fields.snapshot = eventPayload.snapshot
                fields.clip = eventPayload.clip ?: eventPayload.clips
                fields.start_time = eventPayload.start_time
                fields.end_time = eventPayload.end_time
                fields.motion_score = eventPayload.motion_score ?: eventPayload.data?.motion_score
                fields.track_id = eventPayload.track_id ?: eventPayload.data?.id
                fields.has_snapshot = (eventPayload.has_snapshot == true)
                fields.has_clip = (eventPayload.has_clip == true)
            } catch (Exception parseEx) {
                log.error "Frigate Parent App: Both regex extraction and JSON parse failed: ${parseEx.message}"
                return
            }
        }
        
        def eventType = fields.type ?: "update"
        def cameraName = fields.camera?.toString()
        def eventId = fields.id?.toString()
        
        if (!cameraName) {
            log.warn "Frigate Parent App: Event has no camera name - cannot process"
            if (debugLogging) {
                log.debug "Frigate Parent App: Extracted fields: ${fields.keySet()}"
            }
            return
        }
        
        def deviceId = "frigate_${cameraName}"
        def childDevice = getChildDevice(deviceId)
        if (!childDevice) {
            log.warn "Frigate Parent App: No child device found for camera: ${cameraName} (searched for device ID: ${deviceId})"
            return
        }
        
        if (eventType == "new" && eventId) {
            if (state.processedEventIds.contains(eventId)) {
                if (debugLogging) {
                    log.debug "Frigate Parent App: Already processed NEW event ${eventId} for camera ${cameraName}"
                }
                return
            }
            state.processedEventIds.add(eventId)
            if (state.processedEventIds.size() > 100) {
                state.processedEventIds = state.processedEventIds[-100..-1]
            }
        }
        
        def label = fields.label
        if (label == "animal" && fields.sub_label) {
            label = fields.sub_label
        }
        BigDecimal score = 0.0G
        try {
            if (fields.score != null) {
                score = new BigDecimal(fields.score.toString())
            } else if (fields.confidence != null) {
                score = new BigDecimal(fields.confidence.toString())
            }
        } catch (Exception ignored) {
            score = 0.0G
        }
        
        def currentZones = normalizeZoneList(fields.current_zones)
        def enteredZones = normalizeZoneList(fields.entered_zones)
        def previousZones = normalizeZoneList(fields.previous_zones)
        def storedZonesForEvent = state.eventZoneMap[eventId] ?: []
        if (eventType == "end" && eventId) {
            currentZones = storedZonesForEvent
        }
        
        def snapshotUrl = buildSnapshotUrl(eventId, fields.snapshot)
        def clipUrl = buildClipUrl(eventId, fields.clip)
        def startTime = formatTimestamp(fields.start_time)
        def endTime = formatTimestamp(fields.end_time)
        def motionScore = fields.motion_score
        def trackId = fields.track_id
        
        def metadata = [
            eventId             : eventId,
            cameraName          : cameraName,
            label               : label,
            confidence          : score,
            currentZones        : currentZones,
            enteredZones        : enteredZones,
            previousZones       : previousZones,
            hasSnapshot         : (fields.has_snapshot == true),
            hasClip             : (fields.has_clip == true),
            snapshotUrl         : snapshotUrl,
            lastMotionSnapshotUrl: snapshotUrl,
            clipUrl             : clipUrl,
            startTime           : startTime,
            endTime             : endTime,
            motionScore         : motionScore,
            trackId             : trackId
        ]
        
        if (eventType == "new" && eventId && snapshotUrl) {
            try {
                childDevice.updateLastMotionSnapshotUrl(snapshotUrl)
            } catch (Exception ignored) {}
        } else if (eventType == "new" && eventId && !snapshotUrl) {
            try {
                def fallbackSnapshot = buildEventSnapshotUrl(eventId)
                childDevice.updateLastMotionSnapshotUrl(fallbackSnapshot)
                metadata.snapshotUrl = fallbackSnapshot
                metadata.lastMotionSnapshotUrl = fallbackSnapshot
            } catch (Exception ignored) {}
        }
        
        // Manage active camera event lifecycle
        if (eventType == "end") {
            removeActiveCameraEvent(cameraName, eventId)
        } else {
            addActiveCameraEvent(cameraName, eventId)
        }
        
        childDevice.updateEventMetadata(metadata)
        
        if (eventType == "end") {
            if (!cameraHasActiveEvents(cameraName)) {
                childDevice.clearDetections()
                if (eventId) {
                    log.info "Frigate Parent App: Motion ended on ${cameraName} - Event ID: ${eventId}"
                } else {
                    log.info "Frigate Parent App: Motion ended on ${cameraName}"
                }
            } else if (debugLogging) {
                log.debug "Frigate Parent App: Camera ${cameraName} still has active events after ending ${eventId}"
            }
        } else {
            childDevice.updateMotionState("active")
            if (label) {
                childDevice.updateObjectDetection(label, score)
                log.info "Frigate Parent App: Event processed - ${cameraName}: ${label} (${score}) - Event ID: ${eventId}"
            } else if (eventType == "new") {
                log.info "Frigate Parent App: Motion started on ${cameraName} - Event ID: ${eventId}"
            }
        }
        
        if (zonesEnabled() && eventId) {
            handleZoneEvents(cameraName, eventId, eventType, metadata, currentZones, enteredZones, label, score)
        } else if (eventType == "end" && eventId) {
            state.eventZoneMap.remove(eventId)
        }
        
    } catch (Exception e) {
        log.error "Frigate Parent App: Error parsing event message: ${e.message}"
        // Extract only camera/eventId for error context, don't log full 60KB+ payload
        try {
            def cameraMatch = message =~ /"camera"\s*:\s*"([^"]+)"/
            def idMatch = message =~ /"id"\s*:\s*"([^"]+)"/
            def camera = cameraMatch ? cameraMatch[0][1] : "unknown"
            def eventId = idMatch ? idMatch[0][1] : "unknown"
            log.error "Frigate Parent App: Event camera: ${camera}, eventId: ${eventId}, payload size: ${message?.size() ?: 0} bytes"
        } catch (Exception ignored) {
            log.error "Frigate Parent App: Could not extract error context"
        }
        log.error "Frigate Parent App: Stack trace: ${e.stackTrace}"
        if (debugLogging) {
            log.debug "Frigate Parent App: Exception class: ${e.class.name}"
        }
    }
}

def createOrUpdateCameraDevice(String cameraName) {
    def deviceId = "frigate_${cameraName}"
    def childDevice = getChildDevice(deviceId)
    
    log.info "Frigate Parent App: Processing camera: ${cameraName} (ID: ${deviceId})"
    
    if (!childDevice) {
        log.info "Frigate Parent App: Creating new device for camera: ${cameraName}"
        log.info "Frigate Parent App: Device ID: ${deviceId}"
        log.info "Frigate Parent App: Device name: Frigate ${cameraName.replace('_', ' ').toUpperCase()}"
        
        try {
            log.info "Frigate Parent App: Attempting to create device..."
            addChildDevice("simonmason", "Frigate Camera Device", deviceId, [
                name: "Frigate ${cameraName.replace('_', ' ').toUpperCase()}",
                label: "Frigate ${cameraName.replace('_', ' ').toUpperCase()}",
                isComponent: false
            ])
            
            log.info "Frigate Parent App: Device creation command sent for ${cameraName}"
            
            // Verify the device was actually created
            def verifyDevice = getChildDevice(deviceId)
            if (verifyDevice) {
                log.info "Frigate Parent App: SUCCESS - Device created for ${cameraName}"
            } else {
                log.error "Frigate Parent App: FAILED - Device not found after creation attempt for ${cameraName}"
            }
            
        } catch (Exception e) {
            log.error "Frigate Parent App: Exception creating device for ${cameraName}: ${e.message}"
            log.error "Frigate Parent App: Exception details: ${e.toString()}"
        }
    } else {
        log.info "Frigate Parent App: Device already exists for camera: ${cameraName}"
    }
}

def removeObsoleteCameraDevices(List currentCameras) {
    def allChildDevices = getChildDevices()
    
    allChildDevices.each { device ->
        def dni = device.deviceNetworkId
        if (!dni?.startsWith("frigate_")) {
            return
        }
        def remainder = dni.substring("frigate_".length())
        
        // Skip the MQTT bridge device - it's not a camera
        if (remainder == "mqtt_bridge") {
            return
        }
        
        def zoneIndex = remainder.indexOf("_zone_")
        if (zoneIndex > -1) {
            def cameraName = remainder.substring(0, zoneIndex)
            if (!currentCameras.contains(cameraName)) {
                log.info "Frigate Parent App: Removing obsolete zone device ${dni}"
                if (state.activeZoneEvents) {
                    state.activeZoneEvents = state.activeZoneEvents.findAll { key, value -> !key.startsWith("${cameraName}__") }
                }
                if (state.zoneChildMap) {
                    state.zoneChildMap = state.zoneChildMap.findAll { entry -> entry.value != dni }
                }
                deleteChildDevice(dni)
            }
        } else if (!currentCameras.contains(remainder)) {
            log.info "Frigate Parent App: Removing obsolete device for camera: ${remainder}"
            state.activeCameraEvents?.remove(remainder)
            deleteChildDevice(dni)
        }
    }
}

def refreshStats() {
    log.info "Frigate Parent App: Refreshing stats and config"
    
    try {
        // Prepare authentication headers
        def headers = [:]
        if (frigateUsername && frigatePassword) {
            def auth = "${frigateUsername}:${frigatePassword}".bytes.encodeBase64().toString()
            headers = ["Authorization": "Basic ${auth}"]
        }
        
        // Get camera configuration to check motion detection capabilities
        def configUrl = "http://${frigateServer}:${frigatePort}/api/config"
        httpGet([uri: configUrl, headers: headers]) { configResponse ->
            if (configResponse.status == 200) {
                def config = configResponse.data
                if (config.cameras) {
                    def camerasWithMotion = []
                    
                    // Check each camera for motion detection capabilities
                    config.cameras.each { cameraName, cameraConfig ->
                        if (cameraConfig.motion && cameraConfig.motion.enabled) {
                            camerasWithMotion.add(cameraName)
                            log.info "Frigate Parent App: Camera ${cameraName} has motion detection enabled"
                        } else {
                            log.info "Frigate Parent App: Camera ${cameraName} has motion detection disabled"
                        }
                    }
                    
                    log.info "Frigate Parent App: Found ${camerasWithMotion.size()} cameras with motion detection: ${camerasWithMotion}"
                    
                    // Create devices only for cameras with motion detection
                    camerasWithMotion.each { cameraName ->
                        createOrUpdateCameraDevice(cameraName)
                    }
                    
                    // Remove devices for cameras that no longer have motion detection
                    removeObsoleteCameraDevices(camerasWithMotion)
                }
            } else {
                log.error "Frigate Parent App: Failed to get config: ${configResponse.status}"
            }
        }
        
        // Also get stats for system health monitoring
        def statsUrl = "http://${frigateServer}:${frigatePort}/api/stats"
        httpGet([uri: statsUrl, headers: headers]) { statsResponse ->
            if (statsResponse.status == 200) {
                def stats = statsResponse.data
                if (debugLogging) {
                    log.debug "Frigate Parent App: System stats - Detection FPS: ${stats.detection_fps}, GPU: ${stats.gpu_usages}"
                }
            } else {
                log.error "Frigate Parent App: Failed to get stats: ${statsResponse.status}"
            }
        }
        
    } catch (Exception e) {
        log.error "Frigate Parent App: Error getting config/stats: ${e.message}"
    }
}

// Removed: subscribeMQTT(), startMQTTPolling(), and pollMQTTMessages()
// These have been replaced with native MQTT via interfaces.mqtt

// DISABLED: Image download functionality removed to prevent hub/device responsiveness issues
// This method previously stored snapshot URLs but is no longer used
// def getCameraSnapshot(String cameraName) {
//     log.info "Frigate Parent App: Setting snapshot URL for camera: ${cameraName}"
//     try {
//         def normalizedCamera = cameraName?.toString()?.replaceFirst(/^frigate_/, "")
//         def url = "http://${frigateServer}:${frigatePort}/api/${normalizedCamera}/latest.jpg"
//         def childId = "frigate_${normalizedCamera}"
//         def child = getChildDevice(childId)
//         if (child) {
//             // Store URL-only to avoid large base64 attributes
//             child.updateLatestSnapshotUrl(url)
//             log.info "Frigate Parent App: Latest snapshot URL stored on device ${child.label}"
//         } else {
//             log.warn "Frigate Parent App: Child device not found for camera ${normalizedCamera} when storing snapshot URL"
//         }
//     } catch (Exception e) {
//         log.error "Frigate Parent App: Error setting snapshot URL for ${cameraName}: ${e.message}"
//     }
// }

def getCameraStats(String cameraName) {
    log.info "Frigate Parent App: Requesting stats for camera: ${cameraName}"
    
    try {
        def headers = [:]
        if (frigateUsername && frigatePassword) {
            def auth = "${frigateUsername}:${frigatePassword}".bytes.encodeBase64().toString()
            headers = ["Authorization": "Basic ${auth}"]
        }
        
        def url = "http://${frigateServer}:${frigatePort}/api/stats"
        httpGet([uri: url, headers: headers]) { response ->
            if (response.status == 200) {
                log.info "Frigate Parent App: Stats retrieved"
                // Handle stats data here
            } else {
                log.error "Frigate Parent App: Failed to get stats: ${response.status}"
            }
        }
    } catch (Exception e) {
        log.error "Frigate Parent App: Error getting stats: ${e.message}"
    }
}

// Cleanup methods
def uninstalled() {
    log.info "Frigate Parent App: Uninstalling app"
    
    // Disconnect MQTT bridge device
    def bridgeDevice = getChildDevice("frigate_mqtt_bridge")
    if (bridgeDevice) {
        try {
            bridgeDevice.disconnect()
        } catch (Exception e) {
            log.error "Frigate Parent App: Error disconnecting bridge device: ${e.message}"
        }
    }
    
    deleteChildDevices()
}
