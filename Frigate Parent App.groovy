/**
 * Frigate Parent App
 * 
 * Integrates with Frigate NVR via MQTT to provide motion detection and camera management
 * Automatically discovers cameras from Frigate stats
 * 
 * @author Simon Mason
 * @version 1.00
 * @date 2025-01-15
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
            input "mqttApp", "capability.mqtt", title: "Select MQTT App", required: true, multiple: false
            input "topicPrefix", "text", title: "Frigate Topic Prefix", required: true, defaultValue: "frigate"
        }
        
        section("Camera Discovery") {
            paragraph "Cameras will be automatically discovered from Frigate stats"
            input "autoDiscover", "bool", title: "Enable Auto Discovery", required: true, defaultValue: true
            input "refreshInterval", "number", title: "Stats Refresh Interval (seconds)", required: true, defaultValue: 60
        }
        
        section("Frigate Server") {
            input "frigateServer", "text", title: "Frigate Server IP", required: true, defaultValue: "192.168.2.110"
            input "frigatePort", "number", title: "Frigate Port", required: true, defaultValue: 5000
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
    initialize()
}

def initialize() {
    log.info "Frigate Parent App: Initializing"
    
    if (settings.autoDiscover) {
        startMQTTConnection()
    }
    
    // Schedule periodic stats refresh
    schedule("0 */${refreshInterval ?: 60} * * * ?", "refreshStats")
}

def startMQTTConnection() {
    log.info "Frigate Parent App: Starting MQTT subscriptions via existing MQTT app"
    
    try {
        if (mqttApp) {
            // Subscribe to Frigate stats for camera discovery
            mqttApp.subscribe("${topicPrefix}/stats", "handleStatsMessage")
            
            // Subscribe to Frigate events for motion detection
            mqttApp.subscribe("${topicPrefix}/events", "handleEventMessage")
            
            log.info "Frigate Parent App: MQTT subscriptions created via ${mqttApp.label}"
        } else {
            log.error "Frigate Parent App: No MQTT app selected"
        }
        
    } catch (Exception e) {
        log.error "Frigate Parent App: Failed to start MQTT connection: ${e.message}"
    }
}

def handleStatsMessage(String message) {
    if (debugLogging) {
        log.debug "Frigate Parent App: Received stats message: ${message}"
    }
    
    try {
        def stats = new groovy.json.JsonSlurper().parseText(message)
        
        if (stats.cameras) {
            def cameraNames = stats.cameras.keySet()
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
    }
}

def handleEventMessage(String message) {
    if (debugLogging) {
        log.debug "Frigate Parent App: Received event message: ${message}"
    }
    
    try {
        def event = new groovy.json.JsonSlurper().parseText(message)
        
        if (event.before && event.before.camera) {
            def cameraName = event.before.camera
            def childDevice = getChildDevice("frigate_${cameraName}")
            
            if (childDevice) {
                // Update motion state
                childDevice.updateMotionState("active")
                
                // Update specific object detection
                if (event.before.label) {
                    childDevice.updateObjectDetection(event.before.label, event.before.score ?: 0.0)
                }
                
                if (debugLogging) {
                    log.debug "Frigate Parent App: Updated ${cameraName} - ${event.before.label} detected with confidence ${event.before.score}"
                }
            }
        }
        
    } catch (Exception e) {
        log.error "Frigate Parent App: Error parsing event message: ${e.message}"
    }
}

def createOrUpdateCameraDevice(String cameraName) {
    def deviceId = "frigate_${cameraName}"
    def childDevice = getChildDevice(deviceId)
    
    if (!childDevice) {
        log.info "Frigate Parent App: Creating new device for camera: ${cameraName}"
        
        try {
            addChildDevice("simonmason", "Frigate Motion Device", deviceId, [
                name: "Frigate ${cameraName.replace('_', ' ').toUpperCase()}",
                label: "Frigate ${cameraName.replace('_', ' ').toUpperCase()}",
                isComponent: false
            ])
            
            log.info "Frigate Parent App: Created device for ${cameraName}"
            
        } catch (Exception e) {
            log.error "Frigate Parent App: Failed to create device for ${cameraName}: ${e.message}"
        }
    }
}

def removeObsoleteCameraDevices(List currentCameras) {
    def allChildDevices = getChildDevices()
    
    allChildDevices.each { device ->
        def deviceName = device.deviceNetworkId.replace("frigate_", "")
        
        if (!currentCameras.contains(deviceName)) {
            log.info "Frigate Parent App: Removing obsolete device for camera: ${deviceName}"
            deleteChildDevice(device.deviceNetworkId)
        }
    }
}

def refreshStats() {
    log.info "Frigate Parent App: Refreshing stats"
    // MQTT stats are published automatically by Frigate
    // This method can be used for manual refresh if needed
}

// MQTT message handlers are called by the MQTT app
// The MQTT app will call these methods when messages are received

def getCameraSnapshot(String cameraName) {
    log.info "Frigate Parent App: Requesting snapshot for camera: ${cameraName}"
    
    try {
        def url = "http://${frigateServer}:${frigatePort}/api/${cameraName}/latest.jpg"
        httpGet(url) { response ->
            if (response.status == 200) {
                log.info "Frigate Parent App: Snapshot retrieved for ${cameraName}"
                // Handle snapshot data here
            } else {
                log.error "Frigate Parent App: Failed to get snapshot for ${cameraName}: ${response.status}"
            }
        }
    } catch (Exception e) {
        log.error "Frigate Parent App: Error getting snapshot for ${cameraName}: ${e.message}"
    }
}

def getCameraStats(String cameraName) {
    log.info "Frigate Parent App: Requesting stats for camera: ${cameraName}"
    
    try {
        def url = "http://${frigateServer}:${frigatePort}/api/stats"
        httpGet(url) { response ->
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
    deleteChildDevices()
}
