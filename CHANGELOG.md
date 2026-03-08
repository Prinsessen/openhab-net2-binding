# Changelog

All notable changes to the Paxton Net2 Binding are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [5.2.0] - 2026-03-07

### Added
- **Activity Report** (`activityReport` channel)
  - New bridge channel that generates an HTML activity report on each refresh cycle
  - Fetches last 24 hours of access events from Net2 `/events` API endpoint
  - Groups events by door with color-coded results (granted/denied/unknown)
  - Summary stats bar showing total events, access granted, access denied, unique users
  - Mobile-friendly responsive HTML output at `/static/net2_activity.html`
  - Auto-refreshes on each polling interval (default: every 10 minutes)
  - Channel state shows last report update timestamp (dd.MM.yyyy HH:mm:ss)
  - Replaces external `net2_user_activity_daemon.py` — no separate Python service needed
  - Embeddable in sitemaps via `Webview url="/static/net2_activity.html"`
  - New `Net2ActivityReportGenerator` class (~320 lines) and `getEvents()` API method

### Fixed
- **CRITICAL: Garage door toggling on startup** — During OpenHAB startup, the framework sends `REFRESH` commands to all linked channels. The `controlTimed` channel handler (`handleDoorControlTimed()`) did not filter `RefreshType`, so the `"REFRESH"` string fell through to the numeric parser, threw `NumberFormatException`, and defaulted to a **1-second TimedOpen**. For doors with momentary relays (e.g. garage port), this pulsed the relay and **toggled the physical door on every restart**. Fix: added `RefreshType` guard at the top of `handleCommand()` to silently ignore `REFRESH` before any channel dispatch. Discovered from log evidence:
  ```
  2026-03-04 14:52:25.248 Sending advanced door control payload:
    {"DoorId":7242929,"RelayFunction":{"RelayId":"Relay1","RelayAction":"TimedOpen","RelayOpenTime":1000},"LedFlash":3}
  ```
- **DoorHandler logging** - Changed TEST LOG message from `logger.error` to `logger.debug` in `Net2DoorHandler.handleDoorControlTimed()` to prevent ERROR-level log pollution on every timed door command

### Changed
- Updated JAR to latest build

## [5.2.0] - 2026-02-06

### Added
- **Building Lockdown Control** (`lockdown` channel)
  - New bridge channel for emergency lockdown control
  - Switch channel (ON=enable lockdown, OFF=disable lockdown)
  - Triggers Net2 lockdown trigger/action rules via API endpoint `/commands/controlLockdown`
  - Fire-and-forget command execution for immediate response
  - Requires pre-configured lockdown triggers and actions in Net2 software
  - Enables emergency lockdown from OpenHAB rules, UI, or external systems
  - Use cases: Fire alarms, security alerts, after-hours automation
  - Example integrations: Scheduled lockdowns, security system integration, manual control
  - Python reference implementation available in scripts/

## [5.2.0] - 2026-01-16

### Added
- **Access Denied Detection** (`accessDenied` channel)
  - New door channel for detecting unauthorized access attempts
  - Captures eventType 23 (Access Denied) from Net2 SignalR LiveEvents
  - Returns JSON-formatted denied access data: tokenNumber, doorName, timestamp, doorId
  - Enables security alerts via rules (email/SMS notifications)
  - Real-time detection of invalid cards/tokens presented to readers
  - Format example: `{"tokenNumber":"1234567","doorName":"Front Door","timestamp":"2026-01-16T17:26:50","doorId":6612642}`
  - Integration examples provided for email/SMS alerting
  - Timestamp comparison logic for multi-door deployments

## [5.2.0] - 2026-01-10

### Added
- **Entry Logging Channel** (`entryLog`)
  - New door channel for tracking physical badge access events
  - Returns JSON-formatted entry data: firstName, lastName, doorName, timestamp, doorId
  - Only logs physical badge access (not remote UI commands)
  - Integrates with InfluxDB/Grafana for access analytics
  - Includes JavaScript transform for user-friendly UI display
  - Format example: `{"firstName":"Nanna","lastName":"Agesen","doorName":"Front Door","timestamp":"2026-01-10T18:48:34","doorId":6612642}`
- **User Query Channel** (`listUsers`)
  - New bridge channel to query all users in the Net2 system
  - Returns full JSON payload via `GET /api/v1/users`
  - Logs complete user details to openhab.log
  - Usage: Send any command (ON/REFRESH) to trigger
  - Output includes: Id, FirstName, LastName, PIN, Telephone, AccessLevel, ExpiryDate, etc.
  - Useful for auditing, reporting, and user management automation

### Documentation
- **Entry Log Dashboard Guide** (2026-01-11)
  - Added comprehensive step-by-step guide in EXAMPLES.md
  - Complete InfluxDB persistence configuration
  - Working Flux queries for single and combined door dashboards
  - All Grafana transformation steps with exact settings
  - Troubleshooting section for common issues
  - Advanced features: time range variables, door filters, performance tips
- **Updated ENTRY_LOGGING.md**
  - Added reference to comprehensive dashboard guide
  - Simplified Grafana section with working examples
  - Removed outdated JSON parsing approaches
- **Updated README.md**
  - Added "Additional Documentation" section
  - Cross-references to all documentation files
  - Direct link to Entry Log Dashboard guide

### Previous Release - 2026-01-09

### Added
- **Hybrid Synchronization System**
  - SignalR real-time event subscription for each door
  - Door-specific LiveEvents subscriptions for instant notifications
  - API polling fallback for guaranteed state accuracy (every 30s by default)
  - Both `action` and `status` channels now sync with Net2 server state
  - Synchronization works regardless of control method (OpenHAB, Net2 UI, physical access)
- **Enhanced Event Handling**
  - EventType-based state tracking (20, 28, 46 for door open; 47 for door close)
  - Proper handling of `doorRelayOpen` field from API status endpoint
  - Door state updates from both SignalR events and API polling
- **Improved SignalR Integration**
  - Connection callback mechanism to notify door handlers when SignalR ready
  - Automatic door-specific subscriptions after SignalR connection established
  - Fixed race condition in SignalR client initialization
  - Subscriptions logged for debugging: "Subscribed to door events for door ID"
- **Debug Logging**
  - Comprehensive logging for synchronization events
  - `refreshDoorStatus` logs show API polling activity
  - `updateFromApiResponse` logs show door state updates from API
  - SignalR event logs show real-time door events with eventType

### Changed
- `action` channel now maintains persistent state until door physically closes (via API polling detection)
- `status` channel now mirrors actual door relay state from Net2 server
- Both channels update from API polling to ensure accurate synchronization
- Door handlers notified via callback when SignalR connection ready (not during connect)
- **Removed 5-second auto-off timer** - SignalR now reliably handles door close detection via eventType 47

### Fixed
- Door state not synchronizing when closed physically or via Net2 UI
- SignalR client race condition where callback fired before client was assigned
- Incorrect API field parsing (`state` → `status.doorRelayOpen`)
- EventType 47 (door closed) unreliability addressed by API polling fallback
- Missing state updates for `action` channel during API refresh
- False door status OFF after 5 seconds while door remained open (timer removed)

### Technical Details
- API response structure: `{"id": doorId, "status": {"doorRelayOpen": boolean}}`
- SignalR Classic mode protocol with LiveEvents hub
- Net2 API does not implement documented `doorEvents` or `doorStatusEvents` hubs
- SignalR reliably handles both door opens (EventType 20/28/46) and closes (EventType 47)
- Default refresh interval: 30 seconds (configurable) provides redundant verification
- **Door state tracked by SignalR events** (instant) with API polling backup

## [5.1.0] - 2026-01-07

### Added
- Initial release for openHAB 5.1
- Timed door control channel (`controlTimed`) for advanced server-side timed open
- **Bridge Channels (User Management)**
  - `createUser` channel: Create users with access level assignment
  - `deleteUser` channel: Remove users by ID
  - `listAccessLevels` channel: Enumerate available access levels
- **Door Control**
  - `status` channel: Monitor door lock/unlock status
  - `action` channel: Control door (hold open/close)
  - `lastAccessUser` channel: Track last user who accessed door
  - `lastAccessTime` channel: Timestamp of last access
- **Authentication & Security**
  - OAuth2 JWT token handling with automatic refresh
  - Configurable TLS certificate verification
  - Secure credential storage in openHAB
- **Real-time Updates**
  - SignalR 2 event support for live door status
  - Configurable polling interval (default 30s)
  - Automatic door discovery
- **API Integration**
  - Full support for Net2 Local API v1
  - User management (create, delete)
  - Access level assignment to doors
  - Door permission set management
- **Documentation**
  - Comprehensive README with examples
  - EXAMPLES.md with detailed usage for timed and default door control
  - Contributing guidelines
  - Troubleshooting section
  - Configuration examples for text and UI
## Author

**Nanna Agesen** (@Prinsessen)
- Email: nanna@agesen.dk
- GitHub: https://github.com/Prinsessen

### Fixed
- Token refresh handling to prevent authentication timeouts
- Proper resource cleanup on binding shutdown
- Concurrent access token management

### Changed
- Bridge configuration requires explicit API credentials
- Improved error logging for API failures
- Enhanced SignalR connection resilience

### Security
- Token expiry checking with 5-minute buffer
- TLS verification enabled by default
- Credentials never persisted to disk
- Secure memory handling for tokens

## [Unreleased]

### Planned Features
- Event-driven door status updates without polling
- Integration with openHAB's native permissions system
- Mobile app support
- SMS/email notifications for access events
- Advanced scheduling for temporary access

### Under Investigation
- Integration with Home Assistant
- Support for additional access control systems
- Native openHAB UI dashboard templates

---

## Versioning

This binding follows semantic versioning:
- **MAJOR** (5.x.0): Compatibility with openHAB major versions
- **MINOR** (5.1.x): New features, backward compatible
- **PATCH** (5.1.0): Bug fixes, backward compatible

## Release Support

| Version | openHAB | Java | Status | Support Until |
|---------|---------|------|--------|----------------|
| 5.1.0   | 5.1+    | 21+  | Active | 2027-01-07   |

## Contributors

- **Nanna Agesen** (@Prinsessen) - Lead Developer, Maintainer
  - Email: nanna@agesen.dk
  - GitHub: https://github.com/Prinsessen

## License

All changes are licensed under the Eclipse Public License 2.0 (EPL-2.0).

## Notes

### Breaking Changes
None in current version.

### Migration Guide
First-time users: See README.md for complete setup instructions.

### Known Limitations
- SignalR events may be delayed on high-latency networks
- User creation requires valid access level in Net2 system
- Maximum 100 doors per Net2 instance (API limitation)

### Compatibility Matrix

| Feature | Net2 Version | openHAB Version |
|---------|--------------|-----------------|
| Basic Door Control | 6.6 SR5+ | 5.0+ |
| User Management | 6.6 SR5+ | 5.0+ |
| SignalR Events | 6.6 SR5+ | 5.0+ |
| Auto-Discovery | 6.6 SR5+ | 5.0+ |

---

## How to Contribute

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on:
- Reporting bugs
- Requesting features
- Submitting code changes
- Code style requirements
- Testing procedures

## Contact & Support

- **Issues**: https://github.com/Prinsessen/openhab-net2-binding/issues
- **Email**: nanna@agesen.dk
- **Community**: https://community.openhab.org/
