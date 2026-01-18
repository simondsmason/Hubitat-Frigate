/**
 * Frigate MQTT Bridge Device
 * 
 * Device driver that provides MQTT connectivity for Frigate Parent App
 * Since parent apps cannot use interfaces.mqtt, this bridge device handles
 * all MQTT communication and forwards messages to the parent app
 * 
 * Copyright 2025
 *
 * Change History:
 * 1.00 - Initial release - MQTT bridge device for parent app communication
 * 1.01 - 2025-10-31 - Added device-level Debug preference; removed Parent App debug influence; logging now controlled solely by device setting
 * 1.02 - 2025-11-07 - Added Initialize capability and automatic reconnection on hub start; connection health check
 * 1.03 - 2025-11-14 - PERFORMANCE: Removed full JSON parsing for event summary logging; now uses regex to extract only type/camera/id fields. Eliminates double parsing of 60KB+ payloads.
 * 1.04 - 2025-11-14 - SECURITY: Moved MQTT password from state variables to dataValue to prevent password exposure in device state display
 * 1.05 - 2025-11-16 - Added has_snapshot field to event summary logging for snapshot tracking
 *
 * @author Simon Mason
 * @version 1.05
 * @date 2025-11-16
 */

metadata {
    definition (
        name: "Frigate MQTT Bridge Device",
        namespace: "simonmason",
        author: "Simon Mason",
        description: "MQTT bridge device for Frigate Parent App",
        category: "Network",
        iconUrl: "",
        iconX2Url: "",
        iconX3Url: "",
        importUrl: ""
    ) {
        capability "Refresh"
        capability "Initialize"
        
        attribute "connectionStatus", "string"
        attribute "lastMessage", "string"
        attribute "version", "string"
        
        command "connect"
        command "disconnect"
        command "refresh"
        command "configure", [[name: "broker", type: "STRING"], [name: "port", type: "NUMBER"], [name: "username", type: "STRING"], [name: "password", type: "STRING"], [name: "topicPrefix", type: "STRING"]]
    }
}

preferences {
    section("Debug") {
        input "debugLogging", "bool", title: "Enable Debug Logging", required: true, defaultValue: false
    }
}

def installed() {
    log.info "Frigate MQTT Bridge: Device installed (v1.05)"
    sendEvent(name: "connectionStatus", value: "disconnected")
    sendEvent(name: "version", value: "1.05")
    // Wait briefly for parent to send configuration, then initialise
    runIn(2, "initialize")
}

def updated() {
    log.info "Frigate MQTT Bridge: Device updated (v1.05)"
    unschedule()
    disconnect()
    initialize()
}

def initialize() {
    log.info "Frigate MQTT Bridge: Initializing (v1.05)"
    unschedule()
    runEvery5Minutes("ensureConnected")
    // Migrate password from state to dataValue if it exists in state (one-time migration)
    if (state.mqttPassword && !device.getDataValue("mqttPassword")) {
        updateDataValue("mqttPassword", state.mqttPassword)
        state.mqttPassword = null // Remove from state
    }
    if (state.mqttBroker) {
        connect()
    } else {
        log.info "Frigate MQTT Bridge: Awaiting configuration from parent before connecting (v1.05)"
    }
}

def configure(String broker, Number port, String username, String password, String topicPrefix) {
    log.info "Frigate MQTT Bridge: Configuration received from parent app (v1.05)"
    
    // Store configuration in device state (non-sensitive data)
    state.mqttBroker = broker
    state.mqttPort = port
    state.mqttUsername = username
    state.topicPrefix = topicPrefix
    
    // Store password in dataValue (not state) to prevent it from appearing in state variables display
    updateDataValue("mqttPassword", password)
    
    // Connect after configuration
    runIn(1, "connect")
}

private Boolean isDebug() { return (settings?.debugLogging == true) }

def connect() {
    if (state.connecting == true) {
        if (isDebug()) {
            log.debug "Frigate MQTT Bridge: Connection attempt already in progress (v1.05)"
        }
        return
    }

    // Get configuration from state (set by parent app via configure command)
    def broker = state.mqttBroker
    def port = state.mqttPort ?: 1883
    def username = state.mqttUsername
    // Get password from dataValue (not state) to keep it out of state variables display
    def password = device.getDataValue("mqttPassword") ?: state.mqttPassword // Fallback to state for migration
    def topicPrefix = state.topicPrefix ?: "frigate"
    def debugLogging = isDebug()
    
    if (!broker) {
        log.error "Frigate MQTT Bridge: No broker configured. Waiting for parent app to configure. (v1.05)"
        sendEvent(name: "connectionStatus", value: "not configured")
        return
    }
    
    log.info "Frigate MQTT Bridge: Connecting to MQTT broker ${broker}:${port} (v1.05)"
    sendEvent(name: "connectionStatus", value: "connecting")
    state.connecting = true
    
    try {
        // Disconnect if already connected
        if (interfaces.mqtt.isConnected()) {
            interfaces.mqtt.disconnect()
            if (debugLogging) {
                log.debug "Frigate MQTT Bridge: Disconnected existing connection (v1.05)"
            }
        }
        
        // Generate unique client ID
        def clientId = "hubitat_frigate_bridge_${device.deviceNetworkId}_${now()}"
        
        // Build MQTT URI
        def mqttUri = "tcp://${broker}:${port}"
        
        // Store topic prefix for use in subscribe
        state.topicPrefix = topicPrefix
        
        if (debugLogging) {
            log.debug "Frigate MQTT Bridge: MQTT URI: ${mqttUri} (v1.05)"
            log.debug "Frigate MQTT Bridge: Client ID: ${clientId} (v1.05)"
            log.debug "Frigate MQTT Bridge: Topic Prefix: ${topicPrefix} (v1.05)"
        }
        
        // Connect to MQTT broker
        interfaces.mqtt.connect(mqttUri, clientId, username, password, cleanSession: 1)
        
        // Small delay to allow connection to establish
        runIn(2, "subscribeToTopics")
        
    } catch (Exception e) {
        log.error "Frigate MQTT Bridge: Failed to connect: ${e.message} (v1.05)"
        sendEvent(name: "connectionStatus", value: "error")
        state.connecting = false
        state.lastError = e.message
    }
}

def disconnect() {
    log.info "Frigate MQTT Bridge: Disconnecting from MQTT (v1.05)"
    try {
        if (interfaces.mqtt.isConnected()) {
            interfaces.mqtt.disconnect()
        }
        sendEvent(name: "connectionStatus", value: "disconnected")
        state.connecting = false
    } catch (Exception e) {
        log.error "Frigate MQTT Bridge: Error disconnecting: ${e.message} (v1.05)"
        state.lastError = e.message
    }
}

def ensureConnected() {
    def broker = state.mqttBroker
    if (!broker) {
        return
    }

    if (!interfaces.mqtt.isConnected() && state.connecting != true) {
        log.warn "Frigate MQTT Bridge: MQTT connection lost, attempting to reconnect (v1.05)"
        connect()
    }
}

def subscribeToTopics() {
    if (!interfaces.mqtt.isConnected()) {
        log.error "Frigate MQTT Bridge: Not connected, attempting reconnect (v1.05)"
        state.connecting = false
        runIn(5, "connect")
        return
    }
    
    def topicPrefix = state.topicPrefix ?: "frigate"
    def debugLogging = isDebug()
    
    try {
        // Subscribe to Frigate stats for camera discovery
        def statsTopic = "${topicPrefix}/stats"
        interfaces.mqtt.subscribe(statsTopic, 1)
        log.info "Frigate MQTT Bridge: Subscribed to ${statsTopic} (v1.05)"
        
        // Subscribe to Frigate events for motion detection
        def eventsTopic = "${topicPrefix}/events"
        interfaces.mqtt.subscribe(eventsTopic, 1)
        log.info "Frigate MQTT Bridge: Subscribed to ${eventsTopic} (v1.05)"
        
        sendEvent(name: "connectionStatus", value: "connected")
        log.info "Frigate MQTT Bridge: MQTT connection established and subscriptions active (v1.05)"
        state.connecting = false
        state.lastError = null
        
    } catch (Exception e) {
        log.error "Frigate MQTT Bridge: Failed to subscribe: ${e.message} (v1.05)"
        sendEvent(name: "connectionStatus", value: "error")
    }
}

// Called automatically by Hubitat when MQTT messages are received
def parse(String message) {
    def debugLogging = isDebug()
    
    if (debugLogging) {
        log.debug "Frigate MQTT Bridge: Received MQTT message (v1.05)"
    }
    
    try {
        def topic = interfaces.mqtt.parseMessage(message)
        def topicPath = topic.topic
        def payload = topic.payload
        def topicPrefix = state.topicPrefix ?: "frigate"
        
        if (debugLogging) {
            log.debug "Frigate MQTT Bridge: Topic: ${topicPath}, Payload length: ${payload?.size() ?: 0} (v1.05)"
        }
        
        sendEvent(name: "lastMessage", value: "${topicPath}: ${payload?.size() ?: 0} bytes")
        
        // Forward message to parent app based on topic
        if (topicPath == "${topicPrefix}/stats") {
            if (debugLogging) {
                log.debug "Frigate MQTT Bridge: Forwarding stats message to parent app (v1.05)"
            }
            // Call parent app method to handle stats
            parent?.handleStatsMessage(payload)
        } else if (topicPath == "${topicPrefix}/events") {
            if (debugLogging) {
                log.debug "Frigate MQTT Bridge: Forwarding event message to parent app (v1.05)"
            }
            // Extract concise summary using regex to avoid full JSON parsing of 60KB+ payloads
            try {
                def evtType = "unknown"
                def cam = "unknown"
                def evtId = "unknown"
                
                // Extract type from root level
                def typeMatch = payload =~ /"type"\s*:\s*"([^"]+)"/
                if (typeMatch) {
                    evtType = typeMatch[0][1]
                }
                
                // Extract camera - try to find in "after" or "before" sections first, then root
                def cameraInAfter = payload =~ /"after"\s*:\s*\{[^}]*"camera"\s*:\s*"([^"]+)"/
                def cameraInBefore = payload =~ /"before"\s*:\s*\{[^}]*"camera"\s*:\s*"([^"]+)"/
                def cameraInRoot = payload =~ /"camera"\s*:\s*"([^"]+)"/
                
                if (cameraInAfter) {
                    cam = cameraInAfter[0][1]
                } else if (cameraInBefore) {
                    cam = cameraInBefore[0][1]
                } else if (cameraInRoot) {
                    cam = cameraInRoot[0][1]
                }
                
                // Extract id - try to find in "after" or "before" sections first, then root
                def idInAfter = payload =~ /"after"\s*:\s*\{[^}]*"id"\s*:\s*"([^"]+)"/
                def idInBefore = payload =~ /"before"\s*:\s*\{[^}]*"id"\s*:\s*"([^"]+)"/
                def idInRoot = payload =~ /"id"\s*:\s*"([^"]+)"/
                
                if (idInAfter) {
                    evtId = idInAfter[0][1]
                } else if (idInBefore) {
                    evtId = idInBefore[0][1]
                } else if (idInRoot) {
                    evtId = idInRoot[0][1]
                }
                
                // Extract has_snapshot
                def hasSnapshot = payload.contains('"has_snapshot":true')
                
                if (debugLogging) {
                    log.debug "Frigate MQTT Bridge: Event summary - type=${evtType}, camera=${cam}, id=${evtId}, has_snapshot=${hasSnapshot} (${payload?.size() ?: 0} bytes) (v1.05)"
                }
            } catch (Exception ex) {
                if (debugLogging) {
                    log.debug "Frigate MQTT Bridge: Event summary extraction error: ${ex.message} (v1.05)"
                }
            }
            // Call parent app method to handle events
            parent?.handleEventMessage(payload)
        } else {
            if (debugLogging) {
                log.debug "Frigate MQTT Bridge: Unhandled topic: ${topicPath} (v1.05)"
            }
        }
        
    } catch (Exception e) {
        log.error "Frigate MQTT Bridge: Error parsing MQTT message: ${e.message} (v1.05)"
        log.error "Frigate MQTT Bridge: Stack trace: ${e.stackTrace} (v1.05)"
    }
}

// Called automatically by Hubitat for MQTT connection status updates
def mqttClientStatus(String message) {
    def debugLogging = isDebug()
    
    log.info "Frigate MQTT Bridge: mqttClientStatus() called with: ${message} (v1.05)"
    
    if (message.startsWith("Error:") || message.contains("failed") || message.contains("disconnected")) {
        log.error "Frigate MQTT Bridge: MQTT Error: ${message} (v1.05)"
        sendEvent(name: "connectionStatus", value: "error")
        state.connecting = false
        state.lastError = message
        
        // Attempt to reconnect after a delay
        log.info "Frigate MQTT Bridge: Attempting reconnect in 10 seconds (v1.05)"
        runIn(10, "connect")
        
    } else if (message.contains("connected") || message.contains("Connected")) {
        log.info "Frigate MQTT Bridge: MQTT connection established via status callback (v1.05)"
        sendEvent(name: "connectionStatus", value: "connected")
        state.connecting = false
        state.lastError = null
        // Subscribe to topics after connection
        runIn(1, "subscribeToTopics")
    } else {
        log.info "Frigate MQTT Bridge: MQTT status update: ${message} (v1.05)"
        if (debugLogging) {
            log.debug "Frigate MQTT Bridge: Full status message: ${message} (v1.05)"
        }
    }
}

def refresh() {
    log.info "Frigate MQTT Bridge: Refresh requested (v1.05)"
    if (interfaces.mqtt.isConnected()) {
        log.info "Frigate MQTT Bridge: Currently connected (v1.05)"
    } else {
        log.info "Frigate MQTT Bridge: Not connected, attempting to connect (v1.05)"
        connect()
    }
}

