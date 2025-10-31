# Installation Guide

## Prerequisites

Before installing the Hubitat Frigate integration, ensure you have:

1. **Hubitat Elevation Hub** - Running and accessible
2. **Frigate NVR** - Running on your network with MQTT enabled
3. **MQTT Broker** - Accessible to both Hubitat and Frigate
4. **MQTT App** - Existing MQTT app on Hubitat (MQTT Link, MQTT Bridge, etc.)

## Step 1: Install the App and Drivers

### Install Frigate Parent App
1. Open Hubitat Hub Manager
2. Go to **Apps** → **Add User App**
3. Copy and paste the contents of `Frigate Parent App.groovy`
4. Click **Save**

### Install Frigate Camera Device
1. Go to **Drivers** → **Add User Driver**
2. Copy and paste the contents of `Frigate Camera Device.groovy`
3. Click **Save**

### Install Frigate MQTT Bridge Device
1. Go to **Drivers** → **Add User Driver**
2. Copy and paste the contents of `Frigate MQTT Bridge Device.groovy`
3. Click **Save**

## Step 2: Configure the Parent App

1. Go to **Apps** → **User Apps**
2. Find "Frigate Parent App" and click **Configure**
3. Fill in the configuration:

### MQTT Connection
- **Broker IP/Port**: Your Unraid MQTT broker (e.g., 192.168.2.110 / 1883)
- **Username/Password**: e.g., `hubitat` / your password
- **Topic Prefix**: Enter `frigate` (must match your Frigate configuration)

### Camera Discovery
- **Enable Auto Discovery**: Check this box
- **Stats Refresh Interval**: 60 seconds (default)

### Frigate Server (HTTP API)
- **Frigate Server IP**: Enter your Frigate server IP (e.g., 192.168.2.110)
- **Frigate Port**: Enter your Frigate port (default: 5000)
- **Frigate Username/Password**: If your API requires auth

### Debug
- **Enable Debug Logging**: Check if you want detailed logging

4. Click **Done**

## Step 3: Verify Installation

### Check Camera Discovery
1. Go to **Devices** → **User Devices**
2. Look for devices named "Frigate [CAMERA_NAME]"
3. You should see devices for each camera in your Frigate setup

### Test MQTT Connection
1. Check the Hubitat logs (Parent App)
2. Look for: "Creating MQTT bridge device", then "MQTT bridge device configured successfully"
3. If you see errors, verify broker IP/port/user/pass and topic prefix

### Test Motion Detection
1. Walk in front of a camera
2. Check the device state for that camera
3. The `motion` state should change to "active"
4. The `personDetected` state should change to "yes"

### Test Snapshot Rendering
1. Open a camera device → run `take()` or `getSnapshot()`
2. Confirm attributes update: `image`, `snapshotUrl`, `snapshotImage`
3. Add a Dashboard Image tile → Source: Attribute → pick the device and `image`

## Step 4: Configure Automation

### Basic Motion Detection
1. Go to **Rules** → **Rule Machine**
2. Create a new rule
3. Set trigger: "When Frigate [CAMERA] motion becomes active"
4. Set action: "Turn on [LIGHT]"

### Person Detection
1. Create a new rule
2. Set trigger: "When Frigate [CAMERA] personDetected becomes 'yes'"
3. Set action: "Send notification 'Person detected at [CAMERA]'"

## Troubleshooting

### No Cameras Discovered
- Verify Parent App logs show discovery and list of cameras
- Ensure Frigate API `/api/config` and `/api/stats` are reachable (auth if required)
- Ensure topic prefix matches Frigate configuration
- Enable debug logging to see MQTT messages

### No Motion Detection
- Verify MQTT bridge device exists and is configured
- Check Parent App logs for `handleEventMessage` entries
- Ensure Frigate is publishing to `frigate/events`
- Ensure motion detection is enabled in Frigate
- Check confidence threshold settings

### Connection Issues
- Verify MQTT broker settings and credentials
- Verify Frigate server IP/port and HTTP API access
- Ensure MQTT bridge device shows configured
- Test MQTT connection independently if needed

### Device States Not Updating
- Check MQTT message parsing in logs
- Verify Frigate event format matches expected format
- Ensure motion timeout is not too short
- Check confidence threshold is not too high

## Configuration Files

### Frigate Configuration
Ensure your Frigate `config.yml` has MQTT enabled (replace placeholders with your values):
```yaml
mqtt:
  host: <MQTT_BROKER_IP>
  port: 1883
  topic_prefix: frigate
  client_id: frigate
  user: <MQTT_USERNAME>
  password: <MQTT_PASSWORD>
  stats_interval: 60
```
Note: Do not share your actual credentials publicly or commit them to source control.

### MQTT App Configuration
Ensure your MQTT app is configured to connect to the same broker as Frigate.

## Support

If you encounter issues:
1. Check the Hubitat logs for error messages
2. Enable debug logging in the app settings
3. Verify MQTT connection is working
4. Test Frigate HTTP API accessibility
5. Create an issue in the GitHub repository

## Next Steps

After successful installation:
1. Configure automation rules for motion detection
2. Set up notifications for person detection
3. Customize confidence thresholds
4. Explore advanced features like snapshots and stats
