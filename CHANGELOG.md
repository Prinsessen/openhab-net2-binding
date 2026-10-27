# Changelog

All notable changes to the Paxton Net2 Binding are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/). Versions follow the openHAB
release the binding is built for (5.2.x for openHAB 5.2).

## [5.2.2] - 2026-10-27

### Added
- **ACU lockout detection.** New door channel `lockout` (Switch, read-only): ON after `lockoutThreshold`
  refusals of known users within `lockoutWindow` minutes with no entry between; OFF at the next entry. The
  refusal types are 16, 17, 24, 25, 27 and 112. A refusal never counts while a lockdown switched on through the
  bridge's `lockdown` channel is active, nor when Net2 names the event a lockdown refusal (112 "Access Denied -
  Lockdown in Progress"). The state lives in memory: NULL until the first lockout, not restored after a restart.
- **Re-Instate.** New door channel `reinstate` (Switch): ON sends Net2's Re-Instate
  (`POST /api/v1/commands/door/reinstate` with `{"DoorId": ...}`) to the ACU of the door and falls back to OFF.
  A manual Re-Instate does not clear `lockout`. New door channel `lastReinstate` (DateTime, read-only): set when
  Net2 accepted a Re-Instate (HTTP 200).
- **New bridge parameters** `autoReinstate` (default `false`), `lockoutThreshold` (3), `lockoutWindow`
  (10 min) and `reinstateInterval` (30 min between automatic re-instates of one door while a lockout lasts).
  With `autoReinstate` on, the first detection re-instates at once.
- **New door channel `accessEvent`** (String, read-only): every LiveEvent that names a user, granted or
  refused, as JSON `{firstName, lastName, doorName, timestamp, doorId, eventType, eventSubType
  [, eventDescription][, eventSubTypeDescription]}`, without the token number.
- **Event names.** At start the bridge asks Net2 for `GET /api/v1/events/eventTypes` and `/eventSubTypes`.
  A Net2 v7.01 server answers HTTP 500; the bridge then learns the names from the last week of events
  (`eventDescription`) and logs the table. The names go into the `accessEvent` and `entryLog` JSON.
- **First JUnit tests:** `Net2DoorHandlerEventTest`, `Net2LockoutGuardTest`, `Net2EventTypesTest`,
  `Net2UserListTest`.
- `LICENSE` file with the EPL-2.0 text.

- `lockout` is posted by the binding when a door comes online and on every poll, so a state restored by
  persistence never sticks.

### Changed
- **`entryLog` carries granted access only**: event types 20 and 26, or an event with no `eventType` (older
  servers). Before 5.2.2 every LiveEvent with a `userName` was posted as an entry, refusals included. The JSON
  gains `eventType`, `eventSubType` and, when known, `eventDescription` and `eventSubTypeDescription`.
- **`listUsers`** (send ON) now puts the users JSON on its channel, with every field whose name contains "pin"
  removed at any depth. Before, the list went only to `openhab.log`.
- The activity report counts 16, 17 and 112 as denied (before, they were left out).
- `thing-types.xml`: defaults declared for `port` (8443), `tlsVerification` (true, SignalR only) and
  `refreshInterval` (30 s); the password is a password field; the door `name` parameter is marked unused;
  `status`, `lastAccessUser` and `accessDenied` are described as they behave.

### Fixed
- The bridge ignores REFRESH. Before, if an item was linked to `listUsers`, the list ran at every openHAB start
  (openHAB sends REFRESH to linked channels) and wrote every user with their PIN to `openhab.log`.
- Upgrade note: door things created in the UI may not show the new channels; if they are missing, delete the
  door thing and add it again from the Inbox. Things in `.things` files get them when the file is loaded.

### Security
- No PINs in `openhab.log`: the user list is no longer logged, and `createUser` logs only whether a PIN is set.
- Token numbers are no longer logged at INFO: the raw SignalR message (`Net2SignalRClient`) and the event
  payload in the door handler are logged at DEBUG.
- Known: in ASP.NET Core SignalR mode the access token is part of the WebSocket URI, which is logged at DEBUG.
- Users of earlier versions: old `openhab.log` files and backups may contain every user's PIN.

### Corrections to earlier entries and documentation
- **Polling interval:** the default `refreshInterval` is 30 seconds. Entries and documents that said
  "every 10 minutes" or "default 600" were wrong; 600 is used only when the value is 0 or less.
- **"Door state via eventType 47" (and 20/28/46):** the LiveEvents branch that mapped these types to the door
  state never runs. The live door state comes only from `DoorStatusEvents` (`doorRelayOpen`, `status` channel)
  and the REST poll (`status` and `action`).
- **"Fire-and-forget lockdown":** the lockdown command waits for Net2's HTTP answer, and the channel state is
  updated only when Net2 accepted it.
- **TLS verification:** `tlsVerification` affects only the SignalR connection. REST calls always verify the
  certificate against the Java trust store.
- **"Credentials never persisted to disk":** the password is stored in openHAB's thing configuration like any
  other parameter; only the access and refresh tokens are kept in memory only.
- **SignalR protocol:** the binding tries ASP.NET Core SignalR (`/eventHubLocal`) first and falls back to
  classic SignalR (`/signalr`); it is not "SignalR 2 Classic only".
- **`lastAccessUser` / `lastAccessTime`:** they change on every event that names a user or carries a time,
  refusals included, not only on access.
- **Door `name` parameter:** never used; events name the door by the thing label.
- **v5.2.1 release asset:** the JAR attached to the v5.2.1 GitHub release was built after the v5.2.1 tag and
  also contains the two fixes listed under 5.2.1 as "connection verification" and "forced re-authentication"
  (commit b92e0a2). The tag itself has only the auto-reconnect fix.

## [5.2.1] - 2026-04-20

### Fixed
- **SignalR auto-reconnect after Net2 server restart or network outage.** If the Net2 server restarted or the
  network dropped, the SignalR WebSocket died silently and nothing reconnected; the REST poll kept working but
  live events were lost until the binding was restarted. The fix adds:
  - `onDisconnectedCallback` in `Net2SignalRClient`, fired on WebSocket close/error (only on the
    connected-to-disconnected transition, to avoid duplicates)
  - `onSignalRDisconnected()` in `Net2ServerHandler`, which schedules a reconnect with a growing delay
    (30 s, 60 s, 120 s, 240 s, then 300 s)
  - cleanup of the old SignalR client before a new connection, and a backoff reset after a successful one
  - a safety net in `refreshDoorStatus()`: if a poll finds SignalR down with no reconnect scheduled, it starts one
  - all door subscriptions are re-established after a reconnect
- **Connection verification** (in the release asset, after the tag; see Corrections under 5.2.2):
  `startSignalR()` logged "connected and subscribed successfully" and reset the backoff even when `connect()`
  failed (for example HTTP 502 while Net2 starts). It now checks `isConnected()` and schedules a retry.
- **Forced re-authentication on reconnect** (in the release asset, after the tag): after a Net2 restart the old
  OAuth token was invalid but not yet expired by time, so reconnects failed with 401. The binding now logs in
  again before each SignalR reconnect attempt.

## [5.2.0] - 2026-03-07

5.2.0 collects the development from 2026-01-09 to 2026-03-07; the dates below are when each part was added.

### Added
- **Activity report** (`activityReport` bridge channel, 2026-03-07)
  - Generates an HTML report on each poll from the last 24 hours of events (`GET /api/v1/events`)
  - Groups events by door with colour-coded results (granted/denied/other) and a summary (total, granted,
    denied, unique users)
  - Written to `/etc/openhab/html/net2_activity.html`, served at `/static/net2_activity.html`
  - Channel state: time of the last report (`dd.MM.yyyy HH:mm:ss`)
  - New `Net2ActivityReportGenerator` class and `getEvents()` API method; replaces an earlier external Python
    service
- **Building lockdown** (`lockdown` bridge channel, 2026-02-06)
  - Switch: ON switches the Net2 lockdown on, OFF switches it off, via `POST /api/v1/commands/controlLockdown`
  - Which doors lock is configured in the Net2 software
- **Access denied detection** (`accessDenied` door channel, 2026-01-16)
  - Event type 23 (a token Net2 does not know) from SignalR LiveEvents
  - JSON: `{"tokenNumber":"7654321","doorName":"Front Door","timestamp":"2026-01-16T17:26:50","doorId":1234567}`
- **Entry logging** (`entryLog` door channel, 2026-01-10)
  - JSON: `{"firstName":"John","lastName":"Doe","doorName":"Front Door","timestamp":"2026-01-10T18:48:34","doorId":1234567}`
  - Posted for LiveEvents that name a user (in 5.2.0 this included refusals; see 5.2.2)
- **User query** (`listUsers` bridge channel, 2026-01-10)
  - `GET /api/v1/users`; in 5.2.0 the full answer, PINs included, was logged to `openhab.log` (see 5.2.2)
- **Hybrid synchronization** (2026-01-09)
  - SignalR subscriptions per door; REST poll as fallback (default every 30 s)
  - `action` and `status` follow the Net2 state from the poll, whatever controlled the door
  - Connection callback so door handlers subscribe once SignalR is ready (fixes a race in the client start-up)
  - Debug logging for polls (`refreshDoorStatus`, `updateFromApiResponse`) and subscriptions

### Changed
- `binding.xml` migrated to `OH-INF/addon/addon.xml` (openHAB 5)
- `status` mirrors the relay state reported by Net2 (`status.doorRelayOpen` in the API answer)
- The 5-second auto-off timer on the door channels was removed; the door closing is seen in `DoorStatusEvents`
  and the REST poll (the original entry credited eventType 47 for this; see Corrections under 5.2.2)

### Fixed
- **Garage door toggling on startup (critical).** At start openHAB sends REFRESH to linked channels. The
  `controlTimed` handler did not filter it: "REFRESH" failed to parse as a number and fell back to a 1-second
  TimedOpen, which pulsed the relay of a momentary-relay door (garage door) on every openHAB start. REFRESH is
  now ignored at the top of `handleCommand()` before any channel is handled. The log showed:
  ```
  Sending advanced door control payload:
    {"DoorId":1234567,"RelayFunction":{"RelayId":"Relay1","RelayAction":"TimedOpen","RelayOpenTime":1000},"LedFlash":3}
  ```
- A test log line in `handleDoorControlTimed()` was logged at ERROR; now DEBUG
- Door state not synchronizing when the door was closed physically or from the Net2 software
- Wrong field parsed from the API answer (`state` instead of `status.doorRelayOpen`)
- `action` channel not updated by the poll
- False door status OFF after 5 seconds while the door was still open (timer removed)

### Documentation
- Entry log dashboard guide (InfluxDB and Grafana) in EXAMPLES.md (2026-01-11)

## [5.1.0] - 2026-01-07

### Added
- Initial release for openHAB 5.1
- Door channels `status`, `action`, `controlTimed` (timed open on the Net2 side), `lastAccessUser`,
  `lastAccessTime`
- Bridge channels `createUser` (with access level assignment), `deleteUser`, `listAccessLevels`
- OAuth2 token handling with automatic refresh (5 minutes before expiry)
- `tlsVerification` parameter (it applies to the SignalR connection only; see Corrections under 5.2.2)
- SignalR live events and a configurable polling interval (default 30 s)
- Door discovery
- Net2 Local API v1: user management, access level assignment, door permission sets
- README, EXAMPLES.md, contributing guidelines

### Fixed
- Token refresh to prevent authentication timeouts
- Resource cleanup on binding shutdown
- Concurrent access token management

### Changed
- Bridge configuration requires explicit API credentials
- Error logging for API failures
- SignalR connection resilience

---

## License

All changes are licensed under the Eclipse Public License 2.0 (EPL-2.0), see [LICENSE](LICENSE).

## Contributing and support

- See [CONTRIBUTING.md](CONTRIBUTING.md)
- Issues: https://github.com/Prinsessen/openhab-net2-binding/issues
- Maintainer: Nanna Agesen (GitHub: Prinsessen)
