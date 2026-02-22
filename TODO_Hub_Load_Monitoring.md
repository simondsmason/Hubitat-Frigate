# TODO: Hub Load Monitoring

## Status: Deployed and Working (2026-02-22)

All three files deployed to C8-2 and verified:
- **Parent App v1.22** - Camera motion via count topics, events demoted to metadata-only
- **MQTT Bridge v1.11** - Drops all "update" events, only forwards "new"/"end"
- **Camera Device v1.10** - Informational attrs moved to state variables, ~70% fewer sendEvents

## Verified

- [x] Full lifecycle MQTT test (count topics + new/end events) works correctly
- [x] Camera activation/deactivation via count topics
- [x] Zone device activation/deactivation
- [x] Snapshot URLs populated from "new" events
- [x] ~13 events per motion cycle (was 30+)
- [x] No spammyDevices warnings since deployment
- [x] Memory: 215MB free, CPU: 0.57 avg

## Next Steps

- [ ] Monitor for spammyDevices warnings over 24-48 hours - should see NONE
- [ ] Check device busy % after extended operation (was 61.7% on MQTT Bridge pre-fix)
- [ ] Verify no regressions with real Frigate motion (cats, delivery drivers, etc.)
- [ ] Consider cleaning up `_REMOVED` devices (7 devices with `_REMOVED` suffix from old zone naming)
- [ ] The `activeCameraEvents` map now clears on initialize() - verify "end" events still properly clean up during normal operation
- [ ] Consider adding periodic stale event cleanup (events older than X hours)

## Architecture Notes (for reference)

**Count Topics** (drive all device state):
- `frigate/<camera>/all` - total object count on camera (activates/deactivates camera)
- `frigate/<camera>/<object>` - per-object count on camera
- `frigate/<zone>/<object>` - per-object count in zone (activates/deactivates zone devices)

**Events Stream** (metadata only):
- `frigate/events` - only "new" and "end" forwarded (Bridge drops "update")
- "new": populates snapshot URLs, event metadata stored in state variables
- "end": triggers final metadata update, cleanup of active event tracking

**Key State Maps**:
- `state.cameraNames` - list of camera names, built during refreshStats()
- `state.activeCameraEvents` - tracks active events per camera, cleared on initialize()
- `state.activeZoneEvents` - tracks active events per zone, cleared on initialize()
