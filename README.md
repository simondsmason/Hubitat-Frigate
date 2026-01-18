# Hubitat Frigate Integration

A comprehensive Hubitat integration for Frigate NVR that provides automatic camera discovery, real-time motion/object detection via MQTT, and optional snapshots via the Frigate HTTP API.

## Features

- **Automatic Camera Discovery** - Discovers cameras from Frigate config/stats (HTTP API)
- **Real-time Motion Detection** - Motion and object detection via MQTT events (through an MQTT bridge device)
- **Object Recognition** - Person, car, dog, cat detection with confidence scores
- **Existing MQTT Integration** - Works with your existing MQTT infrastructure
- **HTTP API Access** - Snapshot and stats retrieval from Frigate
- **Configurable Thresholds** - Confidence and motion timeout settings

## Requirements

- Hubitat Elevation Hub
- Frigate NVR running on your network
- MQTT broker accessible to both Hubitat and Frigate
- MQTT broker accessible to both Hubitat and Frigate

## Installation

1. **Install the App and Drivers**:
   - Apps → Add User App → paste `Frigate Parent App.groovy`
   - Drivers → Add User Driver → paste `Frigate Camera Device.groovy`
   - Drivers → Add User Driver → paste `Frigate MQTT Bridge Device.groovy`

2. **Configure the Parent App**:
   - Enter MQTT broker IP, port, username, password, topic prefix (e.g., `frigate`)
   - Enter Frigate server IP/port (e.g., 192.168.2.110:5000)
   - Optionally enter Frigate credentials if your API requires auth
   - Ensure Auto Discovery is enabled

3. **Automatic Setup**:
   - App will automatically discover your cameras
   - Child devices will be created for each camera
   - Motion detection will begin automatically

## Configuration

### Parent App Settings

- **MQTT Broker IP/Port/User/Pass**: Your Unraid MQTT details
- **Topic Prefix**: `frigate` (must match Frigate config)
- **Frigate Server IP/Port**: For HTTP API (config/stats/snapshots)
- **Frigate Username/Password**: If API auth is required
- **Auto Discovery**: Enabled
- **Refresh Interval**: Every 60s (fixed schedule)

### Camera Device Settings

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
- `snapshotUrl` - Direct URL to latest snapshot
- `snapshotImage` - Base64 data URI of latest snapshot (for attribute tiles)
- `image` - Value used by Dashboard Image tile (URL preferred)

## Commands

- `refresh()` - Manual refresh of device state
- `getSnapshot()` / `take()` - Fetch and store latest snapshot (updates `image`, `snapshotUrl`, `snapshotImage`)
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
- Verify parent log shows "Refreshing stats and config" and lists cameras
- Ensure Frigate API `/api/config` is reachable from Hubitat (auth if required)
- Ensure topic prefix matches Frigate configuration

### No Motion Detection
- Verify MQTT bridge created and configured (logs in Parent App)
- Check parent logs for `handleEventMessage` entries
- Ensure Frigate is publishing to `frigate/events`

### Connection Issues
- Verify MQTT settings (broker IP/port/user/pass) are correct
- Verify Frigate server IP/port are correct, and API accessible
- Check that the MQTT bridge device exists and was configured successfully

## Change History

- **1.11** - 2025-11-21 - Parent App: PERFORMANCE - Removed automatic camera discovery (scheduled every 60s) and MQTT stats discovery to eliminate excessive logging (~900 logs/hour). Added manual "Refresh Cameras" button for on-demand discovery. Reduced log verbosity: moved routine logs to debug level, removed "Device already exists" info logs, conditional discovery logging only when cameras change. Reduces log volume by ~87-95% while maintaining functionality.
- **1.12** - 2025-11-21 - Parent App: FIX - Added unschedule("refreshStats") to updated() method to properly cancel old automatic discovery schedules when app is updated. CLEANUP - Added zone cleanup during camera refresh; extracts valid zones from Frigate config and removes obsolete zone devices that no longer exist in configuration. Prevents accumulation of orphaned zone devices over time.
- **1.10** - 2025-11-14 - Parent App: PERFORMANCE - Replaced full JSON parsing with selective field extraction using regex to avoid parsing 60KB+ payloads. Reduces memory usage by ~66% and processing time by ~90% for large events. Minor cleanup in updated() method.
- **1.09** - 2025-11-08 - Parent App: Guarded MQTT event/zone state maps to prevent null pointer errors during processing
- **1.08** - 2025-11-08 - Parent App: Zone child devices, richer event metadata, HTTP snapshot URL handling
- **1.07** - 2025-11-07 - Parent App: Added automatic MQTT bridge reconnection, improved event normalization, and safe confidence parsing
- **1.08** - Camera Device: CRITICAL PERFORMANCE FIX - Optimized event sending to only send events when values actually change, preventing LimitExceededException errors. Reduced event queue pressure by ~70%.
- **1.07** - Camera Device: Added version attribute to device state; fixed unix timestamp formatting for lastEventStart and lastEventEnd to display as readable dates
- **1.06** - MQTT Bridge driver: Debug decoupled from Parent App; independent debug toggles
- **1.06** - Camera Device: Added switch capability, zone-aware metadata, and support for event clips/snapshots
- **1.05** - Parent App: Snapshot storage on devices; improved discovery logs
- **1.05** - MQTT Bridge Device: Added has_snapshot field to event summary logging for snapshot tracking
- **1.04** - Parent App: Switched to MQTT bridge device; HTTP API used for config/stats/snapshots only
- **1.04** - MQTT Bridge Device: SECURITY - Moved MQTT password from state variables to dataValue to prevent password exposure in device state display
- **1.03** - Camera Device: Image Capture support (take()), snapshot attributes, renders on dashboard image tile
- **1.00–1.02** - Initial releases, event handling improvements

## Support

For issues and feature requests, please create an issue in the GitHub repository.

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Author

Simon Mason - simonmason
