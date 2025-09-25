# Changelog

All notable changes to this project will be documented in this file.

## [1.00] - 2025-01-15

### Added
- Initial release of Hubitat Frigate integration
- Automatic camera discovery via MQTT stats
- Real-time motion detection via MQTT events
- Object detection support (person, car, dog, cat)
- Confidence scoring and filtering
- Motion timeout handling
- HTTP API integration for snapshots and stats
- Existing MQTT infrastructure integration
- Configurable thresholds and settings
- Debug logging capabilities
- Support for 7 camera setup (driveway, back_garden, front_door, porch, front_garden, garage, patio)

### Features
- **Parent App**: Manages MQTT connection and camera discovery
- **Motion Device**: Individual camera motion detection
- **MQTT Integration**: Works with existing MQTT apps
- **HTTP API**: Snapshot and stats retrieval from Frigate
- **Automation Ready**: Compatible with Hubitat Rule Machine

### Technical Details
- Uses Generic Device type for maximum flexibility
- MQTT topics: `frigate/stats`, `frigate/events`
- HTTP endpoints: `/api/{camera}/latest.jpg`, `/api/stats`
- Motion timeout: 30 seconds (configurable)
- Confidence threshold: 0.5 (configurable)
- Stats refresh: 60 seconds (configurable)

### Installation
- Install "Frigate Parent App" in Hubitat
- Install "Frigate Motion Device" in Hubitat
- Configure MQTT app selection
- Set Frigate server IP and port
- Enable auto discovery

### Configuration
- MQTT App: Select existing MQTT app
- Topic Prefix: "frigate"
- Frigate Server: IP address (default: 192.168.2.110)
- Frigate Port: Port number (default: 5000)
- Auto Discovery: Enable/disable
- Refresh Interval: Stats refresh interval

### Device States
- `motion`: "active" or "inactive"
- `personDetected`: "yes" or "no"
- `carDetected`: "yes" or "no"
- `dogDetected`: "yes" or "no"
- `catDetected`: "yes" or "no"
- `confidence`: Detection confidence (0.0-1.0)
- `objectType`: Type of detected object
- `lastDetection`: Timestamp of last detection
- `lastUpdate`: Last device update

### Commands
- `refresh()`: Manual refresh of device state
- `getSnapshot()`: Request latest snapshot from Frigate
- `getStats()`: Get camera statistics from Frigate

### Requirements
- Hubitat Elevation Hub
- Frigate NVR running on network
- Existing MQTT app on Hubitat
- MQTT broker accessible to both Hubitat and Frigate

### Known Issues
- None at this time

### Future Enhancements
- Media browser integration
- Recording control
- Detection sensitivity controls
- Multi-camera management
- Advanced automation triggers
