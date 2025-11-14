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
 *
 * @author Simon Mason
 * @version 1.03
 * @date 2025-11-14
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
    log.info "Frigate MQTT Bridge: Device installed"
    sendEvent(name: "connectionStatus", value: "disconnected")
    // Wait briefly for parent to send configuration, then initialise
    runIn(2, "initialize")
}

def updated() {
    log.info "Frigate MQTT Bridge: Device updated"
    unschedule()
    disconnect()
    initialize()
}

def initialize() {
    log.info "Frigate MQTT Bridge: Initializing"
    unschedule()
    runEvery5Minutes("ensureConnected")
    if (state.mqttBroker) {
        connect()
    } else {
        log.info "Frigate MQTT Bridge: Awaiting configuration from parent before connecting"
    }
}

def configure(String broker, Number port, String username, String password, String topicPrefix) {
    log.info "Frigate MQTT Bridge: Configuration received from parent app"
    
    // Store configuration in device state
    state.mqttBroker = broker
    state.mqttPort = port
    state.mqttUsername = username
    state.mqttPassword = password
    state.topicPrefix = topicPrefix
    
    // Connect after configuration
    runIn(1, "connect")
}

private Boolean isDebug() { return (settings?.debugLogging == true) }

def connect() {
    if (state.connecting == true) {
        if (isDebug()) {
            log.debug "Frigate MQTT Bridge: Connection attempt already in progress"
        }
        return
    }

    // Get configuration from state (set by parent app via configure command)
    def broker = state.mqttBroker
    def port = state.mqttPort ?: 1883
    def username = state.mqttUsername
    def password = state.mqttPassword
    def topicPrefix = state.topicPrefix ?: "frigate"
    def debugLogging = isDebug()
    
    if (!broker) {
        log.error "Frigate MQTT Bridge: No broker configured. Waiting for parent app to configure."
        sendEvent(name: "connectionStatus", value: "not configured")
        return
    }
    
    log.info "Frigate MQTT Bridge: Connecting to MQTT broker ${broker}:${port}"
    sendEvent(name: "connectionStatus", value: "connecting")
    state.connecting = true
    
    try {
        // Disconnect if already connected
        if (interfaces.mqtt.isConnected()) {
            interfaces.mqtt.disconnect()
            if (debugLogging) {
                log.debug "Frigate MQTT Bridge: Disconnected existing connection"
            }
        }
        
        // Generate unique client ID
        def clientId = "hubitat_frigate_bridge_${device.deviceNetworkId}_${now()}"
        
        // Build MQTT URI
        def mqttUri = "tcp://${broker}:${port}"
        
        // Store topic prefix for use in subscribe
        state.topicPrefix = topicPrefix
        
        if (debugLogging) {
            log.debug "Frigate MQTT Bridge: MQTT URI: ${mqttUri}"
            log.debug "Frigate MQTT Bridge: Client ID: ${clientId}"
            log.debug "Frigate MQTT Bridge: Topic Prefix: ${topicPrefix}"
        }
        
        // Connect to MQTT broker
        interfaces.mqtt.connect(mqttUri, clientId, username, password, cleanSession: 1)
        
        // Small delay to allow connection to establish
        runIn(2, "subscribeToTopics")
        
    } catch (Exception e) {
        log.error "Frigate MQTT Bridge: Failed to connect: ${e.message}"
        sendEvent(name: "connectionStatus", value: "error")
        state.connecting = false
        state.lastError = e.message
    }
}

def disconnect() {
    log.info "Frigate MQTT Bridge: Disconnecting from MQTT"
    try {
        if (interfaces.mqtt.isConnected()) {
            interfaces.mqtt.disconnect()
        }
        sendEvent(name: "connectionStatus", value: "disconnected")
        state.connecting = false
    } catch (Exception e) {
        log.error "Frigate MQTT Bridge: Error disconnecting: ${e.message}"
        state.lastError = e.message
    }
}

def ensureConnected() {
    def broker = state.mqttBroker
    if (!broker) {
        return
    }

    if (!interfaces.mqtt.isConnected() && state.connecting != true) {
        log.warn "Frigate MQTT Bridge: MQTT connection lost, attempting to reconnect"
        connect()
    }
}

def subscribeToTopics() {
    if (!interfaces.mqtt.isConnected()) {
        log.error "Frigate MQTT Bridge: Not connected, attempting reconnect"
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
        log.info "Frigate MQTT Bridge: Subscribed to ${statsTopic}"
        
        // Subscribe to Frigate events for motion detection
        def eventsTopic = "${topicPrefix}/events"
        interfaces.mqtt.subscribe(eventsTopic, 1)
        log.info "Frigate MQTT Bridge: Subscribed to ${eventsTopic}"
        
        sendEvent(name: "connectionStatus", value: "connected")
        log.info "Frigate MQTT Bridge: MQTT connection established and subscriptions active"
        state.connecting = false
        state.lastError = null
        
    } catch (Exception e) {
        log.error "Frigate MQTT Bridge: Failed to subscribe: ${e.message}"
        sendEvent(name: "connectionStatus", value: "error")
    }
}

// Called automatically by Hubitat when MQTT messages are received
def parse(String message) {
    def debugLogging = isDebug()
    
    if (debugLogging) {
        log.debug "Frigate MQTT Bridge: Received MQTT message"
    }
    
    try {
        def topic = interfaces.mqtt.parseMessage(message)
        def topicPath = topic.topic
        def payload = topic.payload
        def topicPrefix = state.topicPrefix ?: "frigate"
        
        if (debugLogging) {
            log.debug "Frigate MQTT Bridge: Topic: ${topicPath}, Payload length: ${payload?.size() ?: 0}"
        }
        
        sendEvent(name: "lastMessage", value: "${topicPath}: ${payload?.size() ?: 0} bytes")
        
        // Forward message to parent app based on topic
        if (topicPath == "${topicPrefix}/stats") {
            if (debugLogging) {
                log.debug "Frigate MQTT Bridge: Forwarding stats message to parent app"
            }
            // Call parent app method to handle stats
            parent?.handleStatsMessage(payload)
        } else if (topicPath == "${topicPrefix}/events") {
            if (debugLogging) {
                log.debug "Frigate MQTT Bridge: Forwarding event message to parent app"
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
                
                if (debugLogging) {
                    log.debug "Frigate MQTT Bridge: Event summary - type=${evtType}, camera=${cam}, id=${evtId} (${payload?.size() ?: 0} bytes)"
                }
            } catch (Exception ex) {
                if (debugLogging) {
                    log.debug "Frigate MQTT Bridge: Event summary extraction error: ${ex.message}"
                }
            }
            // Call parent app method to handle events
            parent?.handleEventMessage(payload)
        } else {
            if (debugLogging) {
                log.debug "Frigate MQTT Bridge: Unhandled topic: ${topicPath}"
            }
        }
        
    } catch (Exception e) {
        log.error "Frigate MQTT Bridge: Error parsing MQTT message: ${e.message}"
        log.error "Frigate MQTT Bridge: Stack trace: ${e.stackTrace}"
    }
}

// Called automatically by Hubitat for MQTT connection status updates
def mqttClientStatus(String message) {
    def debugLogging = isDebug()
    
    log.info "Frigate MQTT Bridge: mqttClientStatus() called with: ${message}"
    
    if (message.startsWith("Error:") || message.contains("failed") || message.contains("disconnected")) {
        log.error "Frigate MQTT Bridge: MQTT Error: ${message}"
        sendEvent(name: "connectionStatus", value: "error")
        state.connecting = false
        state.lastError = message
        
        // Attempt to reconnect after a delay
        log.info "Frigate MQTT Bridge: Attempting reconnect in 10 seconds"
        runIn(10, "connect")
        
    } else if (message.contains("connected") || message.contains("Connected")) {
        log.info "Frigate MQTT Bridge: MQTT connection established via status callback"
        sendEvent(name: "connectionStatus", value: "connected")
        state.connecting = false
        state.lastError = null
        // Subscribe to topics after connection
        runIn(1, "subscribeToTopics")
    } else {
        log.info "Frigate MQTT Bridge: MQTT status update: ${message}"
        if (debugLogging) {
            log.debug "Frigate MQTT Bridge: Full status message: ${message}"
        }
    }
}

def refresh() {
    log.info "Frigate MQTT Bridge: Refresh requested"
    if (interfaces.mqtt.isConnected()) {
        log.info "Frigate MQTT Bridge: Currently connected"
    } else {
        log.info "Frigate MQTT Bridge: Not connected, attempting to connect"
        connect()
    }
}

