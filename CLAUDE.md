# Hubitat-Frigate Developer Notes

> Reference parent NOTES_TO_MYSELF.md for general coding standards, hub IPs, version conventions, and all other guidelines.

## Installation

### Prerequisites

1. Hubitat Elevation Hub — running and accessible
2. Frigate NVR — running with MQTT enabled
3. MQTT Broker — accessible to both Hubitat and Frigate
4. MQTT App on Hubitat — (MQTT Link, MQTT Bridge, etc.)

### Install Steps

1. **Frigate Parent App:** Apps → Add User App → paste `Frigate Parent App.groovy` → Save
2. **Frigate Motion Device:** Drivers → Add User Driver → paste `Frigate Motion Device.groovy` → Save

### Configure Parent App

Apps → User Apps → Frigate Parent App → Configure:

- **MQTT App:** Select your existing MQTT app
- **Topic Prefix:** `frigate`
- **Enable Auto Discovery:** checked
- **Stats Refresh Interval:** 60 seconds
- **Frigate Server IP:** your Frigate server IP
- **Frigate Port:** 5000 (default)

### Frigate config.yml (MQTT section)

```yaml
mqtt:
  host: <mqtt-broker-ip>
  port: 1883
  topic_prefix: frigate
  client_id: frigate
  stats_interval: 60
```

## Troubleshooting

- **No cameras discovered:** Verify MQTT, check Frigate publishes to `frigate/stats`, ensure topic prefix matches.
- **No motion detection:** Check `frigate/events` publishing, verify motion detection enabled in Frigate.
- **Connection issues:** Verify Frigate server IP/port, check MQTT app configuration.
- **Device states not updating:** Check MQTT message parsing in logs, verify confidence threshold isn't too high.
