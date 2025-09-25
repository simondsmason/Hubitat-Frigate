# Hubitat Frigate Integration

A comprehensive Hubitat integration for Frigate NVR that provides automatic camera discovery, motion detection, and object recognition capabilities.

## Features

- **Automatic Camera Discovery** - Discovers cameras from Frigate stats via MQTT
- **Real-time Motion Detection** - Motion and object detection via MQTT events
- **Object Recognition** - Person, car, dog, cat detection with confidence scores
- **Existing MQTT Integration** - Works with your existing MQTT infrastructure
- **HTTP API Access** - Snapshot and stats retrieval from Frigate
- **Configurable Thresholds** - Confidence and motion timeout settings

## Requirements

- Hubitat Elevation Hub
- Frigate NVR running on your network
- Existing MQTT app on Hubitat (MQTT Link, MQTT Bridge, etc.)
- MQTT broker accessible to both Hubitat and Frigate

## Installation

1. **Install the Apps**:
   - Install "Frigate Parent App" in Hubitat
   - Install "Frigate Motion Device" in Hubitat

2. **Configure the Parent App**:
   - Select your existing MQTT app
   - Set Frigate server IP and port (default: 192.168.2.110:5000)
   - Set topic prefix to "frigate"
   - Enable auto discovery

3. **Automatic Setup**:
   - App will automatically discover your cameras
   - Child devices will be created for each camera
   - Motion detection will begin automatically

## Configuration

### Parent App Settings

- **MQTT App**: Select your existing MQTT app
- **Topic Prefix**: "frigate" (matches Frigate config)
- **Frigate Server**: IP address of your Frigate server
- **Frigate Port**: Port of your Frigate server (default: 5000)
- **Auto Discovery**: Enable automatic camera discovery
- **Refresh Interval**: Stats refresh interval in seconds

### Motion Device Settings

- **Motion Timeout**: Seconds before motion resets to inactive
- **Confidence Threshold**: Minimum confidence for object detection (0.0-1.0)
- **Debug Logging**: Enable detailed logging

## Supported Cameras

The integration automatically discovers and supports all cameras configured in Frigate, including:

- Driveway
- Back Garden
- Front Door
- Porch
- Front Garden
- Garage
- Patio

## Object Detection

Supports detection of:
- **Person** - All cameras
- **Car** - Driveway and garage cameras
- **Dog** - All cameras
- **Cat** - All cameras

## Device States

Each camera device provides:
- `motion` - "active" or "inactive"
- `personDetected` - "yes" or "no"
- `carDetected` - "yes" or "no"
- `dogDetected` - "yes" or "no"
- `catDetected` - "yes" or "no"
- `confidence` - Detection confidence (0.0-1.0)
- `objectType` - Type of detected object
- `lastDetection` - Timestamp of last detection
- `lastUpdate` - Last device update

## Commands

- `refresh()` - Manual refresh of device state
- `getSnapshot()` - Request latest snapshot from Frigate
- `getStats()` - Get camera statistics from Frigate

## Automation Examples

### Motion Detection
```
When Frigate Driveway motion becomes active
Then turn on driveway lights
```

### Person Detection
```
When Frigate Front Door personDetected becomes "yes"
Then send notification "Person at front door"
```

### High Confidence Detection
```
When Frigate Garage confidence is greater than 0.8
Then activate security mode
```

## Troubleshooting

### No Cameras Discovered
- Verify MQTT connection is working
- Check that Frigate is publishing to `frigate/stats`
- Ensure topic prefix matches Frigate configuration

### No Motion Detection
- Verify MQTT events are being received
- Check that Frigate is publishing to `frigate/events`
- Enable debug logging to see MQTT messages

### Connection Issues
- Verify Frigate server IP and port are correct
- Check that Frigate HTTP API is accessible
- Ensure MQTT app is properly configured

## Support

For issues and feature requests, please create an issue in the GitHub repository.

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Author

Simon Mason - simonmason
