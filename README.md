# Hubitat Frigate Integration

A comprehensive Hubitat integration for Frigate NVR that provides automatic camera discovery, real-time motion/object detection via MQTT, and optional snapshots via the Frigate HTTP API.

## Features

- **Automatic Camera Discovery** - Discovers cameras and zones from Frigate config (HTTP API)
- **Real-time Motion Detection** - Motion activation/deactivation driven by MQTT count topics for instant response
- **Object Recognition** - Person, car, dog, cat detection with confidence scores
- **Zone Devices** - Optional child devices per zone for zone-specific automation
- **Per-Object Zone Devices** - Optional separate devices per object type per zone (e.g., "Back Step - Cat")
- **Lightweight Architecture** - Count topics drive all state; events stream used only for metadata/snapshots
- **HTTP API Access** - Snapshot and stats retrieval from Frigate
- **Existing MQTT Integration** - Works with your existing MQTT infrastructure

## Requirements

- Hubitat Elevation Hub
- Frigate NVR running on your network
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
- **Refresh Interval**: Daily at 3 AM (automatic schedule)

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
- `switch` - "on" or "off" (mirrors motion for rule compatibility)
- `personDetected` - "yes" or "no"
- `carDetected` - "yes" or "no"
- `dogDetected` - "yes" or "no"
- `catDetected` - "yes" or "no"
- `confidence` - Detection confidence (0.0-1.0)
- `objectType` - Type of detected object
- `lastUpdate` - Last motion state change timestamp
- `latestSnapshotUrl` - URL to most recent event snapshot
- `lastMotionSnapshotUrl` - URL to snapshot from last motion event
- `lastSnapshotUrl` - URL to last snapshot (any source)
- `image` - Value used by Dashboard Image tile (URL)

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

- **1.23** - 2026-02-22 - Parent App: FIX - Eliminated duplicate motion activation events. Per-object count topics now only update object detection; motion state driven exclusively by "all" count topic. Fixes sendEvent() race condition causing 2x motion/switch/lastUpdate events per detection.
- **1.12** - 2026-02-22 - MQTT Bridge Device: PERFORMANCE - Removed `frigate/stats` MQTT subscription (unused; HA also uses HTTP polling for stats). Dropped events QoS from 1 to 0 (matching HA). Simplified count topic parse() with fast-path integer payload check — rejects non-count messages (motion ON/OFF, state topics) immediately without string splits or list lookups. Bridge busy % down from 48.5% to 45.3%.
- **1.22** - 2026-02-22 - Parent App: PERFORMANCE - Camera motion now driven by count topics (`frigate/<camera>/all`) instead of events stream. Dropped `updateObjectDetection()` and `updateMotionState()` calls from event handlers — count topics handle all device state. Events stream demoted to metadata-only for "new" and "end" events. Reduces sendEvent calls per message from ~32 to ~10 on camera devices.
- **1.11** - 2026-02-22 - MQTT Bridge Device: PERFORMANCE - Drop ALL "update" events; only forward "new" and "end" to parent app. Eliminates ~70-80% of forwarded event messages and associated CPU work (regex extraction, path_data stripping). `lastMessage` event only fires for new/end.
- **1.10** - 2026-02-22 - Camera Device: PERFORMANCE - Moved informational attributes (event IDs, zones, scores, timestamps) to state variables instead of sendEvent() to dramatically reduce hub event volume. Removed redundant lastDetection attribute. Consolidated lastUpdate to only fire on motion state changes. Optimized clearDetections() to check current values before sending. Reduces sendEvent calls per message from ~19 to ~5.
- **1.21** - 2026-02-22 - Parent App: FIX - Fixed per-object zone device re-activation bug where events stream was calling updateObjectDetection() on per-object devices, re-activating motion after count=0 had cleared it. Per-object devices now only receive metadata from events stream; activation/deactivation controlled entirely by MQTT count messages for reliable occupancy tracking.
- **1.20** - 2026-02-21 - Parent App: FEATURE - Per-object zone child devices - optional separate devices per object type per zone (e.g., "Back Step - Cat", "Back Step - Dog"). Enables simultaneous tracking of multiple object types in the same zone. Per-object devices deactivate instantly via MQTT count=0 messages. Proactive device creation from Frigate config objects.track lists. Controlled by new "Create per-object zone devices" preference (default off).
- **1.19** - 2026-02-17 - Parent App: PERFORMANCE - Added instant zone activation via per-zone MQTT topics. Frigate publishes lightweight integer counts to per-zone topics ~2-3s before zone data appears in the events stream. Zone devices now activate immediately on first detection, matching Home Assistant response times.
- **1.10** - 2026-02-17 - MQTT Bridge Device: PERFORMANCE - Subscribe to per-zone MQTT topics (frigate/+/+) for instant zone activation. Forwards lightweight integer count messages to parent app for immediate zone device activation.
- **1.18** - 2026-02-16 - Parent App: CRITICAL FIX - Fixed zone name extraction bug where Groovy findAll() returned full match strings instead of capture groups, causing all zone names to be truncated to a single letter. Zone devices now receive correct names from Frigate.
- **1.09** - 2026-02-16 - MQTT Bridge Device: FIXES - Fixed update filter to check for non-empty zone arrays (was a no-op). Strip path_data from payloads before forwarding to prevent unbounded growth. Reduced event churn by only updating lastMessage for events, not stats. Removed stats passthrough to parent app.
- **1.17** - 2026-01-18 - Parent App: ARCHITECTURE - Removed all MQTT event logging from Parent App. MQTT event logging (motion started, event processed, motion ended) is now handled entirely by MQTT Bridge Device when device debug is enabled. This improves separation of concerns and reduces Parent App log volume during active motion periods.
- **1.07** - 2026-01-18 - MQTT Bridge Device: ARCHITECTURE - Moved all MQTT event logging from Parent App to MQTT Bridge Device. Device now logs motion events (started, ended, processed, updated) at INFO level when device debug is enabled. Improved separation of concerns - MQTT transport logging handled by device, business logic logging handled by app.
- **1.16** - 2026-01-18 - Parent App: PERFORMANCE - Moved routine refresh logs to debug level - "Refreshing stats and config", "Camera has motion detection enabled", and "Camera list unchanged" now only log at debug level. Reduces log volume when debug logging is disabled, addressing excessive logging concerns.
- **1.15** - 2026-01-18 - Parent App: FEATURE - Automatic camera refresh after installation and configuration updates - cameras are automatically discovered after initial setup or when passwords/credentials are updated, eliminating the need to manually click refresh.
- **1.14** - 2026-01-18 - Parent App: FEATURE - Proactive device creation - creates all camera and zone devices during HTTP refresh instead of waiting for MQTT triggers. FEATURE - Added automatic daily refresh schedule (once per day at 3 AM). FEATURE - Changed device deletion to renaming with "_REMOVED" suffix to preserve rules and dashboards. FEATURE - Updated device naming to Title Case format - "Back Garden Camera" and "Back Garden Camera - Back Step" (removed "Frigate" prefix, only capitalize first letters).
- **1.13** - 2025-11-22 - Parent App: FIX - Fixed missing logs for "update" type events without labels. Added INFO-level logging for update events to ensure visibility. Added INFO-level logging for stats messages. Now all event types (new, update, end) generate logs at INFO level.
- **1.12** - 2025-11-21 - Parent App: FIX - Added unschedule("refreshStats") to updated() method to properly cancel old automatic discovery schedules when app is updated. CLEANUP - Added zone cleanup during camera refresh; extracts valid zones from Frigate config and removes obsolete zone devices that no longer exist in configuration. Prevents accumulation of orphaned zone devices over time.
- **1.11** - 2025-11-21 - Parent App: PERFORMANCE - Removed automatic camera discovery (scheduled every 60s) and MQTT stats discovery to eliminate excessive logging (~900 logs/hour). Added manual "Refresh Cameras" button for on-demand discovery. Reduced log verbosity: moved routine logs to debug level, removed "Device already exists" info logs, conditional discovery logging only when cameras change. Reduces log volume by ~87-95% while maintaining functionality.
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
