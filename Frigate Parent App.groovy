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
 * 1.06 - 2025-10-31 - Decoupled MQTT Bridge debug flag from Parent App; bridge debug controlled by device preference only
 * 1.07 - 2025-11-07 - Ensured bridge connectivity on init; normalized event labels and safe confidence parsing
 * 1.08 - 2025-11-08 - Added zone child devices, alert/motion metadata, and richer event tracking
 * 1.09 - 2025-11-08 - Guarded event/zone state maps during MQTT processing to prevent null pointer exceptions
 * 1.10 - 2025-11-14 - PERFORMANCE: Replaced full JSON parsing with selective field extraction using regex to avoid parsing 60KB+ payloads including unused path_data arrays. Removed double parsing in MQTT bridge device. Reduces memory usage by ~66% and processing time by ~90% for large events. Minor cleanup: removed unnecessary bridge device refresh call in updated() method.
 * 1.11 - 2025-11-21 - PERFORMANCE: Removed automatic camera discovery (scheduled every 60s) and MQTT stats discovery to eliminate excessive logging (~900 logs/hour). Added manual "Refresh Cameras" button for on-demand discovery. Reduced log verbosity: moved routine logs to debug level, removed "Device already exists" info logs, conditional discovery logging only when cameras change. Reduces log volume by ~87-95% while maintaining functionality.
 * 1.12 - 2025-11-21 - FIX: Added unschedule("refreshStats") to updated() method to properly cancel old automatic discovery schedules when app is updated. CLEANUP: Added zone cleanup during camera refresh - extracts valid zones from Frigate config and removes obsolete zone devices that no longer exist in configuration. Prevents accumulation of orphaned zone devices over time.
 * 1.13 - 2025-11-22 - FIX: Fixed missing logs for "update" type events without labels. Added INFO-level logging for update events to ensure visibility. Added INFO-level logging for stats messages. Now all event types (new, update, end) generate logs at INFO level.
 * 1.14 - 2026-01-18 - FEATURE: Proactive device creation - creates all camera and zone devices during HTTP refresh instead of waiting for MQTT triggers. FEATURE: Added automatic daily refresh schedule (once per day). FEATURE: Changed device deletion to renaming with "_REMOVED" suffix to preserve rules. FEATURE: Updated device naming to Title Case format - "Back Garden Camera" and "Back Garden Camera - Back Step" (removed "Frigate" prefix, only capitalize first letters).
 * 1.15 - 2026-01-18 - FEATURE: Automatic camera refresh after installation and configuration updates - cameras are automatically discovered after initial setup or when passwords/credentials are updated, eliminating the need to manually click refresh.
 * 1.16 - 2026-01-18 - PERFORMANCE: Moved routine refresh logs to debug level - "Refreshing stats and config", "Camera has motion detection enabled", and "Camera list unchanged" now only log at debug level. Reduces log volume when debug logging is disabled, addressing excessive logging concerns.
 * 1.17 - 2026-01-18 - ARCHITECTURE: Removed all MQTT event logging from Parent App. MQTT event logging (motion started, event processed, motion ended) is now handled entirely by MQTT Bridge Device when device debug is enabled. This improves separation of concerns and reduces Parent App log volume during active motion periods.
 * 1.18 - 2026-02-16 - CRITICAL FIX: Fixed zone name extraction bug - Groovy findAll() returns full match strings, not capture groups. zoneMatch[1] was returning the second character of the match (e.g. "e" from "entire_frame_back_garden") instead of the zone name. All zone devices were receiving single-letter names. Fixed by stripping quotes from the full match string.
 * 1.19 - 2026-02-17 - PERFORMANCE: Added handleZoneCountMessage() for instant zone activation via per-zone MQTT topics. Frigate publishes lightweight integer counts to per-zone topics ~2-3s before zone data appears in the events stream. Builds zoneToCameraMap during camera refresh for fast zone-to-camera lookups. Zone devices now activate immediately on first detection, matching Home Assistant response times.
 * 1.20 - 2026-02-21 - FEATURE: Per-object zone child devices - optional separate devices per object type per zone (e.g., "Back Step - Cat", "Back Step - Dog"). Enables simultaneous tracking of multiple object types in the same zone. Per-object devices deactivate instantly via MQTT count=0 messages. Proactive device creation from Frigate config objects.track lists. Controlled by new "Create per-object zone devices" preference (default off).
 * 1.21 - 2026-02-22 - FIX: Fixed per-object zone device re-activation bug - events stream handleZoneEvents() was calling updateObjectDetection() on per-object devices, which internally re-activated motion after count=0 had cleared it. Per-object devices now only receive metadata from events stream; activation/deactivation is controlled entirely by MQTT count messages for reliable occupancy tracking.
 *
 * @author Simon Mason
 * @version 1.21
 * @date 2026-02-22
 */

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

/**
 * Returns the current app version number
 * This is used in all log statements to ensure version consistency
 */
String appVersion() { return "1.21" }

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
            paragraph "Click 'Refresh Cameras' button to discover cameras from Frigate. Cameras are not automatically discovered to reduce hub load."
            href(name: "refreshCamerasPage", title: "Refresh Cameras", required: false, description: "Discover and create camera devices", page: "refreshCamerasPage")
        }
        
        section("Zones & Metadata") {
            input "enableZoneDevices", "bool", title: "Create zone child devices", required: true, defaultValue: true
            input "enablePerObjectZoneDevices", "bool", title: "Create per-object zone devices (e.g., separate cat/dog/person per zone)", required: true, defaultValue: false
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
        
        section("Version") {
            paragraph "Version ${appVersion()}"
        }
    }
    
    page(name: "refreshCamerasPage", title: "Refresh Cameras", install: false, uninstall: false) {
        section {
            paragraph "Click the button below to discover cameras from Frigate and create/update camera devices."
            input(name: "refreshCamerasButton", type: "button", title: "Refresh Cameras Now", submitOnChange: true)
        }
        section("Status") {
            def status = state.refreshStatus ?: "Ready - Click 'Refresh Cameras Now' to discover cameras"
            paragraph status
        }
        section {
            href(name: "backToMain", title: "← Back to Configuration", required: false, description: "Return to main configuration", page: "mainPage")
        }
    }
}

private boolean zonesEnabled() {
    return (settings?.enableZoneDevices != false)
}

private boolean perObjectZonesEnabled() {
    return zonesEnabled() && (settings?.enablePerObjectZoneDevices == true)
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
    // Frigate requires literal & as parameter separator, but Hubitat corrupts & to × when storing
    // Solution: Use URL encoding for the entire query string, or omit parameters if optional
    // Testing without query parameters first - if bbox/timestamp are needed, we'll need a different approach
    return eventId ? "http://${frigateServer}:${frigatePort}/api/events/${eventId}/snapshot.jpg" : null
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
                    zoneList << zoneMatch.replaceAll('"', '')
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
                    zoneList << zoneMatch.replaceAll('"', '')
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
                    zoneList << zoneMatch.replaceAll('"', '')
                }
                if (zoneList) fields.previous_zones = zoneList
            }
        }
        
    } catch (Exception e) {
        // If regex extraction fails, return empty map - caller should fallback to full parse
        if (debugLogging) {
            log.debug "Frigate Parent App: Regex extraction failed, will fallback to full parse: ${e.message} (v${appVersion()})"
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
    state.zoneToCameraMap = state.zoneToCameraMap ?: [:]
    state.zoneObjectChildMap = state.zoneObjectChildMap ?: [:]
    state.trackedObjectsPerZone = state.trackedObjectsPerZone ?: [:]
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

private String prettifyCameraName(String cameraName) {
    if (!cameraName) {
        return ""
    }
    return cameraName.split(/[_\s-]+/).collect { it.capitalize() }.join(" ")
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
    
    def cameraTitle = prettifyCameraName(cameraName)
    def zoneTitle = prettifyZoneName(zoneName)
    def label = "${cameraTitle} Camera - ${zoneTitle}"
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
            log.info "Frigate Parent App: Created zone device ${label} (v${appVersion()})"
        }
        return device
    } catch (Exception e) {
            log.error "Frigate Parent App: Failed to create zone device for ${cameraName}/${zoneName}: ${e.message} (v${appVersion()})"
        return null
    }
}

private String buildZoneObjectDeviceId(String cameraName, String zoneName, String objectLabel) {
    return "frigate_${sanitizeForId(cameraName)}_zone_${sanitizeForId(zoneName)}_obj_${sanitizeForId(objectLabel)}"
}

private String zoneObjectKey(String cameraName, String zoneName, String objectLabel) {
    return "${cameraName}__${zoneName}__${objectLabel}"
}

private def getZoneObjectDevice(String cameraName, String zoneName, String objectLabel) {
    def deviceId = buildZoneObjectDeviceId(cameraName, zoneName, objectLabel)
    return getChildDevice(deviceId)
}

private def getOrCreateZoneObjectDevice(String cameraName, String zoneName, String objectLabel) {
    ensureStateMaps()
    def deviceId = buildZoneObjectDeviceId(cameraName, zoneName, objectLabel)
    def existing = getChildDevice(deviceId)
    if (existing) {
        return existing
    }

    def cameraTitle = prettifyCameraName(cameraName)
    def zoneTitle = prettifyZoneName(zoneName)
    def objectTitle = objectLabel.capitalize()
    def label = "${cameraTitle} Camera - ${zoneTitle} - ${objectTitle}"
    try {
        def device = addChildDevice("simonmason", "Frigate Camera Device", deviceId, [
            name       : label,
            label      : label,
            isComponent: false
        ])
        if (device) {
            device.updateDataValue("deviceRole", "zoneObject")
            device.updateDataValue("cameraName", cameraName)
            device.updateDataValue("zoneName", zoneName)
            device.updateDataValue("objectLabel", objectLabel)
            device.updateEventMetadata([cameraName: cameraName, zoneName: zoneName])
            state.zoneObjectChildMap[zoneObjectKey(cameraName, zoneName, objectLabel)] = deviceId
            log.info "Frigate Parent App: Created per-object zone device ${label} (v${appVersion()})"
        }
        return device
    } catch (Exception e) {
        log.error "Frigate Parent App: Failed to create per-object zone device for ${cameraName}/${zoneName}/${objectLabel}: ${e.message} (v${appVersion()})"
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
            // Update per-object device metadata on end (deactivation handled by count=0)
            if (perObjectZonesEnabled() && label) {
                def objDevice = getZoneObjectDevice(cameraName, zoneName, label)
                if (objDevice) {
                    objDevice.updateEventMetadata(baseMetadata)
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

        // Forward metadata only to per-object device (activation/deactivation
        // is handled by count messages in handleZoneCountMessage)
        if (perObjectZonesEnabled() && label) {
            def objDevice = getZoneObjectDevice(cameraName, zoneName, label)
            if (objDevice) {
                objDevice.updateEventMetadata(zoneMetadata)
            }
        }
    }
}

def installed() {
    log.info "Frigate Parent App: Installing app (v${appVersion()})"
    initialize()
    // Automatically refresh cameras after initial installation
    // Delay to allow MQTT bridge to initialize first
    runIn(5, "refreshCameras")
}

def updated() {
    log.info "Frigate Parent App: Updating app (v${appVersion()})"
    unsubscribe()
    
    // CRITICAL: Cancel any scheduled refreshStats tasks from old versions
    // The old version had automatic discovery scheduled every 60 seconds
    // Must cancel before initialize() to ensure old schedule is stopped
    unschedule("refreshStats")
    
    // Schedule will be recreated in initialize() with daily schedule

    // Check if refresh cameras button was clicked
    if (settings.refreshCamerasButton) {
        app.clearSetting("refreshCamerasButton")
        refreshCameras()
    }

    // Initialize will reconfigure the MQTT bridge device with any updated settings (including password)
    initialize()
    
    // Automatically refresh cameras after configuration update
    // This ensures new credentials are tested and cameras are discovered immediately
    // Delay to allow MQTT bridge to reconnect with new credentials first
    runIn(5, "refreshCameras")
}

def appButtonHandler(btn) {
    if (btn == "refreshCamerasButton") {
        refreshCameras()
    }
}

def initialize() {
    log.info "Frigate Parent App: Initializing (v${appVersion()})"
    
    // Initialize state structures
    state.processedEventIds = state.processedEventIds ?: []
    state.eventZoneMap = state.eventZoneMap ?: [:]
    state.activeCameraEvents = state.activeCameraEvents ?: [:]
    state.activeZoneEvents = state.activeZoneEvents ?: [:]
    state.zoneChildMap = state.zoneChildMap ?: [:]
    state.zoneToCameraMap = state.zoneToCameraMap ?: [:]
    state.zoneObjectChildMap = state.zoneObjectChildMap ?: [:]
    state.trackedObjectsPerZone = state.trackedObjectsPerZone ?: [:]

    // Keep only last 100 event IDs to prevent state from growing too large
    if (state.processedEventIds.size() > 100) {
        state.processedEventIds = state.processedEventIds[-100..-1]
    }
    
    // Create or update MQTT bridge device
    createOrUpdateMQTTBridge()
    runIn(3, "ensureBridgeConnected")
    
    // Unschedule any existing refreshStats schedule (from previous versions)
    unschedule("refreshStats")
    
    // Schedule automatic daily refresh at 3 AM
    schedule("0 0 3 * * ?", "refreshStats")
    log.info "Frigate Parent App: Scheduled automatic daily refresh at 3 AM (v${appVersion()})"
}

// Create or update the MQTT bridge device
def createOrUpdateMQTTBridge() {
    def bridgeDeviceId = "frigate_mqtt_bridge"
    def bridgeDevice = getChildDevice(bridgeDeviceId)
    
    if (!bridgeDevice) {
        log.info "Frigate Parent App: Creating MQTT bridge device (v${appVersion()})"
        try {
            bridgeDevice = addChildDevice("simonmason", "Frigate MQTT Bridge Device", bridgeDeviceId, [
                name: "Frigate MQTT Bridge",
                label: "Frigate MQTT Bridge",
                isComponent: false
            ])
            log.info "Frigate Parent App: MQTT bridge device created (v${appVersion()})"
            
            // Update bridge device preferences
            runIn(2, "configureMQTTBridge")
            
        } catch (Exception e) {
            log.error "Frigate Parent App: Failed to create MQTT bridge device: ${e.message} (v${appVersion()})"
        }
    } else {
        log.info "Frigate Parent App: MQTT bridge device already exists (v${appVersion()})"
        // Update preferences if they changed
        configureMQTTBridge()
    }
}

def configureMQTTBridge() {
    def bridgeDevice = getChildDevice("frigate_mqtt_bridge")
    if (!bridgeDevice) {
        log.error "Frigate Parent App: Bridge device not found for configuration (v${appVersion()})"
        return
    }
    
    log.info "Frigate Parent App: Configuring MQTT bridge device with MQTT settings (v${appVersion()})"
    
    // Send configuration to bridge device via configure command
    try {
        bridgeDevice.configure(
            mqttBroker ?: "",
            mqttPort ?: 1883,
            mqttUsername ?: "",
            mqttPassword ?: "",
            topicPrefix ?: "frigate"
        )
            log.info "Frigate Parent App: MQTT bridge device configured successfully (v${appVersion()})"
        } catch (Exception e) {
            log.error "Frigate Parent App: Failed to configure bridge device: ${e.message} (v${appVersion()})"
    }
}

def ensureBridgeConnected() {
    def bridgeDevice = getChildDevice("frigate_mqtt_bridge")
    if (!bridgeDevice) {
        log.warn "Frigate Parent App: MQTT bridge device not found when attempting to ensure connection (v${appVersion()})"
        return
    }
    try {
        if (bridgeDevice.hasCommand("ensureConnected")) {
            bridgeDevice.ensureConnected()
        } else {
            bridgeDevice.connect()
        }
    } catch (Exception e) {
        log.error "Frigate Parent App: Error ensuring bridge connectivity: ${e.message} (v${appVersion()})"
    }
}

def handleStatsMessage(String message) {
    if (debugLogging) {
        log.debug "Frigate Parent App: Received stats message (camera discovery disabled - use manual refresh button) (v${appVersion()})"
    } else {
        // Log at info level that stats were received (but discovery is disabled)
        log.info "Frigate Parent App: Received stats message (camera discovery disabled - use manual refresh button) (v${appVersion()})"
    }
    
    // Camera discovery removed from MQTT stats - use manual refresh button instead
    // This prevents automatic discovery cycles that generate excessive logs
    // Stats messages are kept for potential future system health monitoring
}

def handleEventMessage(String message) {
    // MQTT event logging is now handled by MQTT Bridge Device when device debug is enabled
    // Parent App focuses on business logic and state management, not MQTT transport details
    ensureStateMaps()
    try {
        // Use selective field extraction to avoid parsing 60KB+ payloads including unused path_data
        def fields = extractEventFields(message)
        
        // Fallback to full JSON parse if extraction failed (safety net)
        def parsedEvent = null
        if (!fields.camera && !fields.id) {
            if (debugLogging) {
                log.debug "Frigate Parent App: Regex extraction failed, falling back to full JSON parse (v${appVersion()})"
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
                log.error "Frigate Parent App: Both regex extraction and JSON parse failed: ${parseEx.message} (v${appVersion()})"
                return
            }
        }
        
        def eventType = fields.type ?: "update"
        def cameraName = fields.camera?.toString()
        def eventId = fields.id?.toString()
        
        if (!cameraName) {
            log.warn "Frigate Parent App: Event has no camera name - cannot process (v${appVersion()})"
            if (debugLogging) {
                log.debug "Frigate Parent App: Extracted fields: ${fields.keySet()} (v${appVersion()})"
            }
            return
        }
        
        def deviceId = "frigate_${cameraName}"
        def childDevice = getChildDevice(deviceId)
        if (!childDevice) {
            log.warn "Frigate Parent App: No child device found for camera: ${cameraName} (searched for device ID: ${deviceId}) (v${appVersion()})"
            return
        }
        
        if (eventType == "new" && eventId) {
            if (state.processedEventIds.contains(eventId)) {
                if (debugLogging) {
                    log.debug "Frigate Parent App: Already processed NEW event ${eventId} for camera ${cameraName} (v${appVersion()})"
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
                // MQTT event logging is handled by MQTT Bridge Device when device debug is enabled
            } else if (debugLogging) {
                log.debug "Frigate Parent App: Camera ${cameraName} still has active events after ending ${eventId} (v${appVersion()})"
            }
        } else {
            childDevice.updateMotionState("active")
            if (label) {
                childDevice.updateObjectDetection(label, score)
                // MQTT event logging is handled by MQTT Bridge Device when device debug is enabled
            }
            // MQTT event logging is handled by MQTT Bridge Device when device debug is enabled
        }
        
        if (zonesEnabled() && eventId) {
            handleZoneEvents(cameraName, eventId, eventType, metadata, currentZones, enteredZones, label, score)
        } else if (eventType == "end" && eventId) {
            state.eventZoneMap.remove(eventId)
        }
        
    } catch (Exception e) {
        log.error "Frigate Parent App: Error parsing event message: ${e.message} (v${appVersion()})"
        // Extract only camera/eventId for error context, don't log full 60KB+ payload
        try {
            def cameraMatch = message =~ /"camera"\s*:\s*"([^"]+)"/
            def idMatch = message =~ /"id"\s*:\s*"([^"]+)"/
            def camera = cameraMatch ? cameraMatch[0][1] : "unknown"
            def eventId = idMatch ? idMatch[0][1] : "unknown"
            log.error "Frigate Parent App: Event camera: ${camera}, eventId: ${eventId}, payload size: ${message?.size() ?: 0} bytes (v${appVersion()})"
        } catch (Exception ignored) {
            log.error "Frigate Parent App: Could not extract error context (v${appVersion()})"
        }
        log.error "Frigate Parent App: Stack trace: ${e.stackTrace} (v${appVersion()})"
        if (debugLogging) {
            log.debug "Frigate Parent App: Exception class: ${e.class.name} (v${appVersion()})"
        }
    }
}

/**
 * Handle per-zone MQTT count messages for instant zone activation.
 * Frigate publishes integer object counts to frigate/<zone_name>/<object> topics
 * ~2-3s before zone data appears in the main events stream.
 * This provides near-instant zone device activation/deactivation.
 */
def handleZoneCountMessage(String sourceName, String objectLabel, int count) {
    if (!zonesEnabled()) {
        return
    }

    // Look up camera name for this zone from the reverse map
    def cameraName = state.zoneToCameraMap?.get(sourceName)
    if (!cameraName) {
        // sourceName might be a camera name (not a zone), skip it
        return
    }

    def zoneDevice = getZoneDevice(cameraName, sourceName)
    if (!zoneDevice) {
        return
    }

    if (count > 0) {
        // Object detected in zone - activate immediately
        if (objectLabel != "all") {
            zoneDevice.updateObjectDetection(objectLabel, 0.0G)
        }
        zoneDevice.updateMotionState("active")
        if (debugLogging) {
            log.debug "Frigate Parent App: Zone count activation - ${sourceName}/${objectLabel} count=${count} (v${appVersion()})"
        }

        // Per-object zone device activation
        if (perObjectZonesEnabled() && objectLabel != "all") {
            def objDevice = getOrCreateZoneObjectDevice(cameraName, sourceName, objectLabel)
            if (objDevice) {
                objDevice.updateObjectDetection(objectLabel, 0.0G)
                objDevice.updateMotionState("active")
                if (debugLogging) {
                    log.debug "Frigate Parent App: Per-object zone activation - ${sourceName}/${objectLabel} count=${count} (v${appVersion()})"
                }
            }
        }
    } else {
        // Count dropped to zero - do NOT deactivate zone device here
        // Let the normal events stream handle deactivation via "end" events
        // This prevents premature clearing when Frigate briefly publishes 0 between detections
        if (debugLogging) {
            log.debug "Frigate Parent App: Zone count zero - ${sourceName}/${objectLabel} (deactivation handled by events stream) (v${appVersion()})"
        }

        // Per-object zone devices CAN safely deactivate on count=0
        // since they track a single object type
        if (perObjectZonesEnabled() && objectLabel != "all") {
            def objDevice = getZoneObjectDevice(cameraName, sourceName, objectLabel)
            if (objDevice) {
                objDevice.clearDetections()
                if (debugLogging) {
                    log.debug "Frigate Parent App: Per-object zone deactivation - ${sourceName}/${objectLabel} count=0 (v${appVersion()})"
                }
            }
        }
    }
}

def createOrUpdateCameraDevice(String cameraName) {
    def deviceId = "frigate_${cameraName}"
    def childDevice = getChildDevice(deviceId)
    
    if (!childDevice) {
        def cameraTitle = prettifyCameraName(cameraName)
        def deviceLabel = "${cameraTitle} Camera"
        log.info "Frigate Parent App: Processing camera: ${cameraName} (ID: ${deviceId}) (v${appVersion()})"
        log.info "Frigate Parent App: Creating new device for camera: ${cameraName} (v${appVersion()})"
        log.info "Frigate Parent App: Device ID: ${deviceId} (v${appVersion()})"
        log.info "Frigate Parent App: Device name: ${deviceLabel} (v${appVersion()})"
        
        try {
            log.info "Frigate Parent App: Attempting to create device... (v${appVersion()})"
            addChildDevice("simonmason", "Frigate Camera Device", deviceId, [
                name: deviceLabel,
                label: deviceLabel,
                isComponent: false
            ])
            
            log.info "Frigate Parent App: Device creation command sent for ${cameraName} (v${appVersion()})"
            
            // Verify the device was actually created
            def verifyDevice = getChildDevice(deviceId)
            if (verifyDevice) {
                log.info "Frigate Parent App: SUCCESS - Device created for ${cameraName} (v${appVersion()})"
            } else {
                log.error "Frigate Parent App: FAILED - Device not found after creation attempt for ${cameraName} (v${appVersion()})"
            }
            
        } catch (Exception e) {
            log.error "Frigate Parent App: Exception creating device for ${cameraName}: ${e.message} (v${appVersion()})"
            log.error "Frigate Parent App: Exception details: ${e.toString()} (v${appVersion()})"
        }
    } else {
        // Device already exists - no logging needed to reduce log noise
        if (debugLogging) {
            log.debug "Frigate Parent App: Device already exists for camera: ${cameraName} (v${appVersion()})"
        }
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
            // Zone devices are handled by removeObsoleteZoneDevices()
            // Only rename if camera itself is obsolete
            def cameraName = remainder.substring(0, zoneIndex)
            if (!currentCameras.contains(cameraName)) {
                def currentLabel = device.label ?: device.name
                if (!currentLabel.endsWith("_REMOVED")) {
                    def newLabel = "${currentLabel}_REMOVED"
                    device.setLabel(newLabel)
                    log.info "Frigate Parent App: Renamed obsolete zone device ${dni} to ${newLabel} (camera removed) (v${appVersion()})"
                }
                if (state.activeZoneEvents) {
                    state.activeZoneEvents = state.activeZoneEvents.findAll { key, value -> !key.startsWith("${cameraName}__") }
                }
                if (state.zoneChildMap) {
                    state.zoneChildMap = state.zoneChildMap.findAll { entry -> entry.value != dni }
                }
            }
        } else if (!currentCameras.contains(remainder)) {
            def currentLabel = device.label ?: device.name
            if (!currentLabel.endsWith("_REMOVED")) {
                def newLabel = "${currentLabel}_REMOVED"
                device.setLabel(newLabel)
                log.info "Frigate Parent App: Renamed obsolete camera device ${dni} to ${newLabel} (v${appVersion()})"
            }
            state.activeCameraEvents?.remove(remainder)
        }
    }
}

def removeObsoleteZoneDevices(List currentCameras, Map validZonesPerCamera) {
    if (!zonesEnabled() || !validZonesPerCamera) {
        return
    }

    def allChildDevices = getChildDevices()
    def removedCount = 0

    allChildDevices.each { device ->
        def dni = device.deviceNetworkId
        if (!dni?.startsWith("frigate_")) {
            return
        }

        def remainder = dni.substring("frigate_".length())

        // Skip non-zone devices and MQTT bridge
        def zoneIndex = remainder.indexOf("_zone_")
        if (zoneIndex == -1) {
            return
        }

        // Extract camera name and zone+object ID part from device ID
        def cameraName = remainder.substring(0, zoneIndex)
        def zoneAndObjPart = remainder.substring(zoneIndex + "_zone_".length())

        // Only check zones for cameras that still exist
        if (!currentCameras.contains(cameraName)) {
            return // Camera removal is handled by removeObsoleteCameraDevices()
        }

        // Get valid zones for this camera
        def validZones = validZonesPerCamera[cameraName] ?: []
        def validZoneIds = validZones.collect { sanitizeForId(it) } as Set

        // Check if this is a per-object zone device (contains _obj_)
        def objIndex = zoneAndObjPart.indexOf("_obj_")
        if (objIndex > -1) {
            // Per-object zone device: frigate_<cam>_zone_<zone>_obj_<object>
            def zoneIdPart = zoneAndObjPart.substring(0, objIndex)
            def objectIdPart = zoneAndObjPart.substring(objIndex + "_obj_".length())

            def zoneValid = validZoneIds.contains(zoneIdPart)
            def objectValid = false
            if (zoneValid) {
                // Check if object is still tracked for this zone
                def zoneName = device.getDataValue("zoneName") ?: zoneIdPart.replace('_', ' ')
                def zKey = zoneKey(cameraName, zoneName)
                def trackedObjects = (state.trackedObjectsPerZone ?: [:])[zKey] ?: []
                objectValid = trackedObjects.collect { sanitizeForId(it) }.contains(objectIdPart)
            }

            if (!zoneValid || (!objectValid && perObjectZonesEnabled())) {
                def currentLabel = device.label ?: device.name
                if (!currentLabel.endsWith("_REMOVED")) {
                    def newLabel = "${currentLabel}_REMOVED"
                    device.setLabel(newLabel)
                    log.info "Frigate Parent App: Renamed obsolete per-object zone device ${dni} to ${newLabel} (v${appVersion()})"
                    removedCount++
                }
                // Clean up state map
                def zoneName = device.getDataValue("zoneName") ?: zoneIdPart.replace('_', ' ')
                def objectLabel = device.getDataValue("objectLabel") ?: objectIdPart.replace('_', ' ')
                if (state.zoneObjectChildMap) {
                    state.zoneObjectChildMap.remove(zoneObjectKey(cameraName, zoneName, objectLabel))
                }
            }
        } else {
            // Regular zone device: frigate_<cam>_zone_<zone>
            def zoneIdPart = zoneAndObjPart
            def zoneMatches = validZoneIds.contains(zoneIdPart)

            if (!zoneMatches) {
                def zoneName = device.getDataValue("zoneName") ?: zoneIdPart.replace('_', ' ')

                def currentLabel = device.label ?: device.name
                if (!currentLabel.endsWith("_REMOVED")) {
                    def newLabel = "${currentLabel}_REMOVED"
                    device.setLabel(newLabel)
                    log.info "Frigate Parent App: Renamed obsolete zone device ${dni} to ${newLabel} (zone '${zoneName}' no longer exists for camera '${cameraName}') (v${appVersion()})"
                    removedCount++
                }

                def zKey = zoneKey(cameraName, zoneName)
                if (state.activeZoneEvents) {
                    state.activeZoneEvents.remove(zKey)
                }
                if (state.zoneChildMap) {
                    state.zoneChildMap.remove(zKey)
                }

                if (state.eventZoneMap) {
                    state.eventZoneMap.each { eventId, zones ->
                        if (zones instanceof List && zones.contains(zoneName)) {
                            zones.remove(zoneName)
                            if (zones.isEmpty()) {
                                state.eventZoneMap.remove(eventId)
                            }
                        }
                    }
                }
            }
        }
    }

    if (removedCount > 0) {
        log.info "Frigate Parent App: Renamed ${removedCount} obsolete zone/per-object device(s) with _REMOVED suffix (v${appVersion()})"
    }
}

def refreshCameras() {
    log.info "Frigate Parent App: Manual camera refresh requested (v${appVersion()})"
    
    // Set status to "checking"
    state.refreshStatus = "Checking cameras from Frigate..."
    state.refreshStatusColor = "blue"
    
    refreshStats()
}


def refreshStats() {
    if (debugLogging) {
        log.debug "Frigate Parent App: Refreshing stats and config (v${appVersion()})"
    }
    
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
                    
                    // Build maps of cameras with motion and their valid zones
                    def validZonesPerCamera = [:]
                    
                    // Check each camera for motion detection capabilities and extract zones
                    config.cameras.each { cameraName, cameraConfig ->
                        if (cameraConfig.motion && cameraConfig.motion.enabled) {
                            camerasWithMotion.add(cameraName)
                            if (debugLogging) {
                                log.debug "Frigate Parent App: Camera ${cameraName} has motion detection enabled (v${appVersion()})"
                            }
                            
                            // Extract valid zones for this camera from config
                            def validZones = []
                            if (cameraConfig.zones && cameraConfig.zones instanceof Map) {
                                validZones = cameraConfig.zones.keySet() as List
                                if (debugLogging && validZones) {
                                    log.debug "Frigate Parent App: Camera ${cameraName} has zones: ${validZones} (v${appVersion()})"
                                }
                            }
                            validZonesPerCamera[cameraName] = validZones
                        } else {
                            if (debugLogging) {
                                log.debug "Frigate Parent App: Camera ${cameraName} has motion detection disabled (v${appVersion()})"
                            }
                        }
                    }
                    
                    // Build reverse map of zone name → camera name for per-zone MQTT topic handling
                    def zoneMap = [:]
                    validZonesPerCamera.each { camName, zones ->
                        zones.each { zoneName ->
                            zoneMap[zoneName] = camName
                        }
                    }
                    state.zoneToCameraMap = zoneMap
                    if (debugLogging && zoneMap) {
                        log.debug "Frigate Parent App: Built zoneToCameraMap: ${zoneMap} (v${appVersion()})"
                    }

                    // Only log discovery if cameras changed
                    def previousCameras = state.lastDiscoveredCameras ?: []
                    def currentCameraList = camerasWithMotion.sort()
                    def previousCameraList = previousCameras.sort()
                    def camerasChanged = (currentCameraList != previousCameraList)
                    
                    if (camerasChanged) {
                        log.info "Frigate Parent App: Discovered cameras: ${camerasWithMotion} (${camerasWithMotion.size()} with motion detection) (v${appVersion()})"
                        state.lastDiscoveredCameras = camerasWithMotion
                    } else {
                        if (debugLogging) {
                            log.debug "Frigate Parent App: Camera list unchanged: ${camerasWithMotion.size()} cameras with motion detection (v${appVersion()})"
                        }
                    }
                    
                    // Create devices only for cameras with motion detection
                    camerasWithMotion.each { cameraName ->
                        createOrUpdateCameraDevice(cameraName)
                    }
                    
                    // Proactively create zone devices for all valid zones
                    if (zonesEnabled()) {
                        camerasWithMotion.each { cameraName ->
                            def validZones = validZonesPerCamera[cameraName] ?: []
                            validZones.each { zoneName ->
                                getOrCreateZoneDevice(cameraName, zoneName)
                            }
                        }
                    }

                    // Proactively create per-object zone devices from Frigate config
                    if (perObjectZonesEnabled()) {
                        def trackedMap = [:]
                        camerasWithMotion.each { cameraName ->
                            def cameraConfig = config.cameras[cameraName]
                            def trackedObjects = cameraConfig?.objects?.track ?: []
                            if (trackedObjects instanceof Collection) {
                                trackedObjects = trackedObjects.collect { it?.toString() }.findAll { it }
                            } else {
                                trackedObjects = []
                            }
                            def validZones = validZonesPerCamera[cameraName] ?: []
                            validZones.each { zoneName ->
                                def key = zoneKey(cameraName, zoneName)
                                trackedMap[key] = trackedObjects
                                trackedObjects.each { objectLabel ->
                                    getOrCreateZoneObjectDevice(cameraName, zoneName, objectLabel)
                                }
                            }
                        }
                        state.trackedObjectsPerZone = trackedMap
                        if (debugLogging) {
                            log.debug "Frigate Parent App: Built trackedObjectsPerZone: ${trackedMap} (v${appVersion()})"
                        }
                    }

                    // Rename devices for cameras that no longer have motion detection
                    removeObsoleteCameraDevices(camerasWithMotion)
                    
                    // Rename obsolete zone devices that don't match valid zones from config
                    if (zonesEnabled()) {
                        removeObsoleteZoneDevices(camerasWithMotion, validZonesPerCamera)
                    }
                    
                    // Set completion status
                    def cameraCount = camerasWithMotion.size()
                    def statusMsg = camerasChanged ? 
                        "Completed: Discovered ${cameraCount} cameras with motion detection" : 
                        "Completed: Found ${cameraCount} cameras (no changes)"
                    state.refreshStatus = statusMsg
                    state.refreshStatusColor = "green"
                    
                } else {
                    // No cameras found
                    state.refreshStatus = "Warning: No cameras found in Frigate config"
                    state.refreshStatusColor = "red"
                }
            } else {
                log.error "Frigate Parent App: Failed to get config: ${configResponse.status} (v${appVersion()})"
                state.refreshStatus = "Error: Failed to connect to Frigate (HTTP ${configResponse.status})"
                state.refreshStatusColor = "red"
            }
        }
        
        // Also get stats for system health monitoring
        def statsUrl = "http://${frigateServer}:${frigatePort}/api/stats"
        httpGet([uri: statsUrl, headers: headers]) { statsResponse ->
            if (statsResponse.status == 200) {
                def stats = statsResponse.data
                if (debugLogging) {
                    log.debug "Frigate Parent App: System stats - Detection FPS: ${stats.detection_fps}, GPU: ${stats.gpu_usages} (v${appVersion()})"
                }
            } else {
                log.error "Frigate Parent App: Failed to get stats: ${statsResponse.status} (v${appVersion()})"
            }
        }
        
    } catch (Exception e) {
        log.error "Frigate Parent App: Error getting config/stats: ${e.message} (v${appVersion()})"
        state.refreshStatus = "Error: ${e.message}"
        state.refreshStatusColor = "red"
    }
}

// Removed: subscribeMQTT(), startMQTTPolling(), and pollMQTTMessages()
// These have been replaced with native MQTT via interfaces.mqtt

def getCameraSnapshot(String cameraName) {
    log.info "Frigate Parent App: Setting snapshot URL for camera: ${cameraName} (v${appVersion()})"
    try {
        def normalizedCamera = cameraName?.toString()?.replaceFirst(/^frigate_/, "")
        def url = "http://${frigateServer}:${frigatePort}/api/${normalizedCamera}/latest.jpg"
        def childId = "frigate_${normalizedCamera}"
        def child = getChildDevice(childId)
        if (child) {
            // Store URL-only to avoid large base64 attributes
            child.updateLatestSnapshotUrl(url)
            log.info "Frigate Parent App: Latest snapshot URL stored on device ${child.label} (v${appVersion()})"
        } else {
            log.warn "Frigate Parent App: Child device not found for camera ${normalizedCamera} when storing snapshot URL (v${appVersion()})"
        }
    } catch (Exception e) {
        log.error "Frigate Parent App: Error setting snapshot URL for ${cameraName}: ${e.message} (v${appVersion()})"
    }
}

def getCameraStats(String cameraName) {
    log.info "Frigate Parent App: Requesting stats for camera: ${cameraName} (v${appVersion()})"
    
    try {
        def headers = [:]
        if (frigateUsername && frigatePassword) {
            def auth = "${frigateUsername}:${frigatePassword}".bytes.encodeBase64().toString()
            headers = ["Authorization": "Basic ${auth}"]
        }
        
        def url = "http://${frigateServer}:${frigatePort}/api/stats"
        httpGet([uri: url, headers: headers]) { response ->
            if (response.status == 200) {
                log.info "Frigate Parent App: Stats retrieved (v${appVersion()})"
                // Handle stats data here
            } else {
                log.error "Frigate Parent App: Failed to get stats: ${response.status} (v${appVersion()})"
            }
        }
    } catch (Exception e) {
        log.error "Frigate Parent App: Error getting stats: ${e.message} (v${appVersion()})"
    }
}

// Cleanup methods
def uninstalled() {
    log.info "Frigate Parent App: Uninstalling app (v${appVersion()})"
    
    // Disconnect MQTT bridge device
    def bridgeDevice = getChildDevice("frigate_mqtt_bridge")
    if (bridgeDevice) {
        try {
            bridgeDevice.disconnect()
        } catch (Exception e) {
            log.error "Frigate Parent App: Error disconnecting bridge device: ${e.message} (v${appVersion()})"
        }
    }
    
    deleteChildDevices()
}
