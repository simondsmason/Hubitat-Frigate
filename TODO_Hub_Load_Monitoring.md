# TODO: Hub Load Monitoring

## Status: Deployed and Working (2026-02-22)

All three files deployed to C8-2 and verified:
- **Parent App v1.23** - Count topics drive all device state; "all" topic drives motion, per-object topics drive object detection (no duplicates)
- **MQTT Bridge v1.12** - Drops "update" events, removed stats subscription, QoS 0, fast-path integer parse
- **Camera Device v1.10** - Informational attrs moved to state variables, ~70% fewer sendEvents

## Verified

- [x] Full lifecycle MQTT test (count topics + new/end events) works correctly
- [x] Camera activation/deactivation via count topics
- [x] Zone device activation/deactivation
- [x] Snapshot URLs populated from "new" events
- [x] ~13 events per motion cycle (was 30+)
- [x] No spammyDevices warnings since deployment
- [x] Real Frigate motion verified (cats, dogs, people, cars all detected correctly in logs)
- [x] Memory stable: 410MB free, CPU 0.06 avg (3hrs post-reboot)
- [x] MQTT Bridge busy % trending down: 61.5% (pre-fix) → 48.5% (2hrs) → 45.3% (3hrs)
- [x] Duplicate motion activation fix verified (v1.23 - "all" drives motion, per-object drives detection)
- [x] Deploy script rewritten to use direct HTTP POST (~1s, no browser dependency)

## Next Steps

- [ ] Monitor for spammyDevices warnings over 24-48 hours - should see NONE
- [ ] Check MQTT Bridge busy % after 24h (currently 45.3% and trending down)
- [ ] Consider cleaning up `_REMOVED` devices (7 devices with `_REMOVED` suffix from old zone naming)
- [ ] Verify "end" events properly clean up `activeCameraEvents` during normal operation
- [ ] Consider adding periodic stale event cleanup (events older than X hours)

## Architecture Notes (for reference)

**Count Topics** (drive all device state):
- `frigate/<camera>/all` - total object count; drives camera motion active/inactive
- `frigate/<camera>/<object>` - per-object count; updates object detection only (no motion state)
- `frigate/<zone>/all` - total zone count; drives zone motion active/inactive
- `frigate/<zone>/<object>` - per-object zone count; updates zone object detection + per-object device motion

**Events Stream** (metadata only):
- `frigate/events` - only "new" and "end" forwarded (Bridge drops "update")
- "new": populates snapshot URLs, event metadata stored in state variables
- "end": triggers final metadata update, cleanup of active event tracking

**MQTT Subscriptions** (v1.12):
- `frigate/events` at QoS 0 (matching HA integration)
- `frigate/+/+` at QoS 0 (count topics; non-integer payloads like motion ON/OFF rejected by fast-path)
- NO `frigate/stats` subscription (removed - unused, HA uses HTTP polling for stats)

**Key State Maps**:
- `state.cameraNames` - list of camera names, built during refreshStats()
- `state.activeCameraEvents` - tracks active events per camera, cleared on initialize()
- `state.activeZoneEvents` - tracks active events per zone, cleared on initialize()

**Deploy** (direct HTTP, no browser):
- `python3 deploy_app.py <file.groovy>` — auto-detects app vs driver, resolves type ID, POSTs to hub
- Endpoints: `/hub2/userAppTypes`, `/hub2/userDeviceTypes`, `/app/saveOrUpdateJson`, `/driver/saveOrUpdateJson`
