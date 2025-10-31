/**
 * Frigate Parent App
 * 
 * Integrates with Frigate NVR via MQTT to provide motion detection and camera management
 * Automatically discovers cameras from Frigate stats
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
 * 
 * @author Simon Mason
 * @version 1.06
 * @date 2025-10-31
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
    log.info "Frigate Parent App: Initializing v1.06"
    
    // Initialize state for tracking processed events
    if (!state.processedEventIds) {
        state.processedEventIds = []
    }
    // Keep only last 100 event IDs to prevent state from growing too large
    if (state.processedEventIds.size() > 100) {
        state.processedEventIds = state.processedEventIds[-100..-1]
    }
    
    // Create or update MQTT bridge device
    createOrUpdateMQTTBridge()
    
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
        log.debug "Frigate Parent App: handleEventMessage() called with message: ${message}"
    }
    
    try {
        def event = new groovy.json.JsonSlurper().parseText(message)
        
        if (debugLogging) {
            log.debug "Frigate Parent App: Parsed event object keys: ${event.keySet()}"
        }
        
        // Frigate MQTT events format: {type, before, after}
        // The "after" object contains the actual event data with camera, id, label, etc.
        // The "before" object contains the previous state (for "update" events)
        
        def eventType = event.type  // "new", "update", or "end"
        // Frigate uses 'after' for new/update and 'before' for end
        def eventData = (eventType == "end") ? (event.before ?: event) : (event.after ?: event)
        
        // Extract camera name and event ID
        def cameraName = eventData.camera
        def eventId = eventData.id
        
        if (debugLogging) {
            log.debug "Frigate Parent App: Event details - Type: ${eventType}, Camera: ${cameraName}, ID: ${eventId}"
        }
        
        // Process lifecycle:
        // - new: set motion active, update detection
        // - update: keep motion active, refresh detection if provided
        // - end: set motion inactive
        
        // Deduplicate only 'new' events so we still process updates and end
        if (eventType == "new") {
            if (eventId && state.processedEventIds?.contains(eventId)) {
                if (debugLogging) {
                    log.debug "Frigate Parent App: Already processed NEW event ${eventId} for camera ${cameraName}"
                }
                return
            }
            if (eventId) {
                state.processedEventIds = state.processedEventIds ?: []
                state.processedEventIds.add(eventId)
                if (state.processedEventIds.size() > 100) {
                    state.processedEventIds = state.processedEventIds[-100..-1]
                }
            }
        }
        
        // Use eventData instead of event for the rest of the processing
        event = eventData
        
        if (cameraName) {
            def deviceId = "frigate_${cameraName}"
            if (debugLogging) {
                log.debug "Frigate Parent App: Looking for child device with ID: ${deviceId}"
            }
            
            def childDevice = getChildDevice(deviceId)
            
            if (childDevice) {
                if (debugLogging) {
                    log.debug "Frigate Parent App: Found child device: ${childDevice.label} (${deviceId})"
                }
                
                // Get label and score - handle MQTT format
                // Frigate MQTT events have label directly, and score in data object
                def label = event.label ?: event.sub_label
                def score = event.data?.score ?: event.data?.top_score ?: event.top_score ?: 0.0
                
                if (debugLogging) {
                    log.debug "Frigate Parent App: Detection - Label: ${label}, Score: ${score}, Event Type: ${eventType}"
                }
                
                // Motion lifecycle
                if (eventType == "end") {
                    if (debugLogging) {
                        log.debug "Frigate Parent App: Calling updateMotionState('inactive') on device ${deviceId} (end event)"
                    }
                    childDevice.updateMotionState("inactive")
                } else {
                    // new or update
                    if (debugLogging) {
                        log.debug "Frigate Parent App: Calling updateMotionState('active') on device ${deviceId} (${eventType ?: 'implicit'})"
                    }
                    childDevice.updateMotionState("active")
                }
                
                // Update specific object detection on new/update
                if (label && eventType != "end") {
                    if (debugLogging) {
                        log.debug "Frigate Parent App: Calling updateObjectDetection('${label}', ${score}) on device ${deviceId}"
                    }
                    childDevice.updateObjectDetection(label, score)
                    log.info "Frigate Parent App: Event processed - ${cameraName}: ${label} (${score}) - Event ID: ${eventId}"
                } else {
                    // If no label provided, we already updated motion above
                    if (debugLogging) {
                        log.debug "Frigate Parent App: No label update for device ${deviceId} (type: ${eventType})"
                    }
                    if (eventType == "new") {
                        log.info "Frigate Parent App: Motion started on ${cameraName} - Event ID: ${eventId}"
                    } else if (eventType == "end") {
                        log.info "Frigate Parent App: Motion ended on ${cameraName} - Event ID: ${eventId}"
                    }
                }
                
                if (debugLogging) {
                    log.debug "Frigate Parent App: Successfully updated device ${deviceId} - ${label ?: 'motion'} detected with confidence ${score}"
                }
            } else {
                log.warn "Frigate Parent App: No child device found for camera: ${cameraName} (searched for device ID: ${deviceId})"
                if (debugLogging) {
                    def allDevices = getChildDevices()
                    log.debug "Frigate Parent App: Available child devices: ${allDevices.collect { it.deviceNetworkId }}"
                }
            }
        } else {
            log.warn "Frigate Parent App: Event has no camera name - cannot process"
            if (debugLogging) {
                log.debug "Frigate Parent App: Event structure: ${groovy.json.JsonOutput.toJson(event)}"
            }
        }
        
    } catch (Exception e) {
        log.error "Frigate Parent App: Error parsing event message: ${e.message}"
        log.error "Frigate Parent App: Event data: ${message}"
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
        def deviceName = device.deviceNetworkId.replace("frigate_", "")
        
        // Skip the MQTT bridge device - it's not a camera
        if (deviceName == "mqtt_bridge") {
            return
        }
        
        if (!currentCameras.contains(deviceName)) {
            log.info "Frigate Parent App: Removing obsolete device for camera: ${deviceName}"
            deleteChildDevice(device.deviceNetworkId)
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

def getCameraSnapshot(String cameraName) {
    log.info "Frigate Parent App: Setting snapshot URL for camera: ${cameraName}"
    try {
        def normalizedCamera = cameraName?.toString()?.replaceFirst(/^frigate_/, "")
        def url = "http://${frigateServer}:${frigatePort}/api/${normalizedCamera}/latest.jpg"
        def childId = "frigate_${normalizedCamera}"
        def child = getChildDevice(childId)
        if (child) {
            // Store URL-only to avoid large base64 attributes
            child.updateSnapshot(null, url)
            log.info "Frigate Parent App: Snapshot URL stored on device ${child.label}"
        } else {
            log.warn "Frigate Parent App: Child device not found for camera ${normalizedCamera} when storing snapshot URL"
        }
    } catch (Exception e) {
        log.error "Frigate Parent App: Error setting snapshot URL for ${cameraName}: ${e.message}"
    }
}

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
