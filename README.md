# Paxton Net2 Access Control Binding

This binding connects openHAB to a Paxton Net2 access control server through the Net2 Local API (REST plus
SignalR live events). It controls doors, reports door state and access events, manages users, switches the
Net2 building lockdown, and detects an ACU that has started to refuse every user.

Current release: **5.2.2** (2026-10-27). See [CHANGELOG.md](CHANGELOG.md).

## Features

- **Door control**: hold a door open, close it, or open it for a number of seconds (timed open on the Net2 side)
- **Live door state**: the door relay state from SignalR `DoorStatusEvents`, with a REST poll as the safety net
- **Access logging**: last user and time per door; a JSON entry log for granted access only (`entryLog`)
- **All access events**: every event that names a user, granted or refused, with Net2's event type (`accessEvent`)
- **Unknown tokens**: a JSON event for tokens Net2 does not know (`accessDenied`, event type 23)
- **ACU lockout detection and Re-Instate**: notices when an ACU refuses known users and can re-instate it,
  by command or automatically (new in 5.2.2)
- **Event names**: the bridge loads Net2's names for its event types and adds them to the event JSON
- **User management**: create and delete users, assign an access level, list users and access levels
- **Building lockdown**: switch the Net2 lockdown on and off
- **Activity report**: an HTML page with the last 24 hours of door events, regenerated on every poll
- **Discovery**: doors are found from the Net2 server
- **Token handling**: OAuth2 token refresh before expiry, and a fresh login before every SignalR reconnect

## Requirements

- openHAB 5.2 (the binding is built against the openHAB 5.2 add-ons reactor) and Java 21
- A Paxton Net2 server with the Net2 Local API licensed and enabled, and its API client ID
- A Net2 operator account for the binding
- Net2 Plus or Net2 Classic ACUs (see below)

The 5.2.2 features were developed and tested against a Net2 v7.01 server. Earlier documentation of this
binding named Net2 6.6 SR5 as the minimum version; that has not been re-checked for 5.2.2.

## Hardware Compatibility

### Supported controllers

1. **Paxton Net2 Plus ACU** -
   [Installation Manual](https://www.paxton-access.com/wp-content/uploads/2019/03/ins-20006.pdf)
2. **Paxton Net2 Classic ACU** (discontinued)

### Not compatible

The following products do **not** work with the Net2 Local API and therefore not with this binding:

1. **Net2 Nano 1 Door Controller** -
   [Product Information](https://www.paxton-access.com/wp-content/uploads/2019/03/ins-30075.pdf)
2. **Paxton Paxlock** -
   [Product Information](https://www.paxton-access.com/wp-content/uploads/2019/03/ins-20000.pdf)
3. **Paxton Paxlock Pro** -
   [Product Information](https://www.paxton-access.com/wp-content/uploads/2022/05/ins-30229.pdf)

## Installation

This is a custom binding, not part of the openHAB distribution.

1. Download `org.openhab.binding.net2-5.2.2.jar` from the
   [GitHub releases](https://github.com/Prinsessen/openhab-net2-binding/releases).
2. Copy it into the openHAB `addons` folder: `/usr/share/openhab/addons/` on an apt/deb installation,
   `$OPENHAB_HOME/addons/` otherwise. When upgrading, remove the previous Net2 JAR from that folder first, so
   only one version is installed.
3. openHAB picks the JAR up by itself (hot deployment); no restart is needed. The binding then appears in
   the Main UI under Settings -> Things -> Add (Paxton Net2 Binding).

Check that it started with `bundle:list | grep -i net2` in the Karaf console (`openhab-cli console`).

### Upgrading from 5.2.1 or earlier

- `entryLog` now carries granted access only. Rules that used it to see refusals must use `accessEvent`.
- New door channels: `accessEvent`, `lockout`, `reinstate`, `lastReinstate`. Door things defined in `.things`
  files get them when the file is loaded. Door things created in the UI may not show them; if they are missing,
  delete the door thing, add it again from the Inbox and link the items again.
- Earlier versions, with an item linked to `listUsers`, wrote every user with their PIN to `openhab.log` at
  each start. Check old log files and backups.

## Configuration

### Bridge: Net2 Server (`net2server`)

| Parameter | Type | Default | Required | Description |
|-----------|------|---------|----------|-------------|
| `hostname` | text | - | yes | Net2 server hostname or IP address. A full base URL (`https://host:port/api/v1`) is also accepted. |
| `port` | integer | `8443` | no | HTTPS port of the Net2 Local API |
| `username` | text | - | yes | Net2 operator name, "First Name Last Name" |
| `password` | text | - | yes | Password of that operator |
| `clientId` | text | - | yes | Client ID from the Net2 API licence |
| `tlsVerification` | boolean | `true` | no | Verify the server certificate on the **SignalR** connection only. REST calls always verify against the Java trust store (see Troubleshooting). |
| `refreshInterval` | integer (s) | `30` | no | Seconds between REST polls of the door status. A value of 0 or less falls back to 600. The activity report is regenerated on every poll. |
| `autoReinstate` | boolean | `false` | no | Re-instate an ACU automatically when it refuses known users. Off: the `lockout` channel still reports it. |
| `lockoutThreshold` | integer | `3` | no (advanced) | Refusals of known users at one door, with no entry between, that mean a lockout |
| `lockoutWindow` | integer (min) | `10` | no (advanced) | Minutes within which those refusals must fall |
| `reinstateInterval` | integer (min) | `30` | no (advanced) | Least minutes between two automatic re-instates of the same door while the lockout lasts |

### Thing: Net2 Door (`door`)

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `doorId` | integer | yes | Net2 door ID (the ACU serial number for a Net2 Plus) |
| `name` | text | no | **Not used.** Events name the door by the thing label, so give every door thing a label. |

### Discovery

Once the bridge is ONLINE, the binding lists the doors from `GET /api/v1/doors` and offers them in the
Inbox, labelled with their Net2 name and with `doorId` as the representation property.

## Channels

### Door channels

| Channel | Item type | Access | Description |
|---------|-----------|--------|-------------|
| `status` | Switch | read-only | Door relay as Net2 reports it: ON = released/open, OFF = locked. Updated by SignalR `DoorStatusEvents` (`doorRelayOpen`), by the REST poll and after a successful `action` command. |
| `action` | Switch | command | ON holds the door open (`POST /commands/door/holdopen`), OFF closes it (`POST /commands/door/close`). The state follows a successful command and the REST poll; it is **not** updated by SignalR events. |
| `controlTimed` | Number | command | Opens the door for the given number of seconds (`POST /commands/door/control`, TimedOpen on Relay1, LED flash 3). An invalid value opens for 1 second. |
| `lastAccessUser` | String | read-only | The user named by the last event at the door - an entry **or a refusal** |
| `lastAccessTime` | DateTime | read-only | Time of the last event at the door that carries a time - an entry, a refusal or a door event |
| `entryLog` | String | read-only | JSON for **granted** access only (event types 20 and 26) |
| `accessEvent` | String | read-only | JSON for every event that names a user, granted or refused (new in 5.2.2) |
| `accessDenied` | String | read-only | JSON for a token Net2 does not know (event type 23), including the token number |
| `lockout` | Switch | read-only | ON when the ACU refuses known users (see [ACU lockout and Re-Instate](#acu-lockout-and-re-instate)); OFF at the next entry (new in 5.2.2) |
| `reinstate` | Switch | command | ON sends Net2's Re-Instate to the ACU of this door; falls back to OFF when the request has completed (new in 5.2.2) |
| `lastReinstate` | DateTime | read-only | When Net2 last accepted a Re-Instate for this door (HTTP 200), manual or automatic (new in 5.2.2) |

### Bridge channels

| Channel | Item type | Access | Description |
|---------|-----------|--------|-------------|
| `createUser` | String | command | Create a user: `firstName,lastName,accessLevel,pin`. The access level may be an ID or a name; it is assigned after the user is created. All four fields are expected; with fewer the command is refused and logged. |
| `deleteUser` | String | command | Delete a user by ID (`DELETE /api/v1/users/{id}`) |
| `listAccessLevels` | String | command | Any command lists the access levels in `openhab.log` (INFO): `Access levels: [1:Public] [2:Staff] ...` |
| `listUsers` | String | command + state | Send ON: the channel then holds the users as JSON (`GET /api/v1/users`) with every PIN field removed |
| `lockdown` | Switch | command + state | ON switches the Net2 lockdown on, OFF switches it off (`POST /api/v1/commands/controlLockdown`). The binding waits for Net2's answer and updates the state only when Net2 accepted the command. |
| `activityReport` | String | read-only | Time of the last activity report (`dd.MM.yyyy HH:mm:ss`) |

REFRESH commands are ignored on all channels, bridge and door. Before 5.2.2 a REFRESH on `listUsers` at start-up
ran the user list; since 5.2.0 a REFRESH on a door could not open it.

### JSON formats

`entryLog` and `accessEvent` carry the same object. The binding splits Net2's `userName` at the first space:
the first word becomes `lastName`, the rest `firstName` (Net2 sends "Doe John"). The token number is left out.
`eventDescription` and `eventSubTypeDescription` are present only when the bridge knows Net2's names for them.

```json
{"firstName":"John","lastName":"Doe","doorName":"Front Door","timestamp":"2026-10-27T07:34:20",
 "doorId":1234567,"eventType":20,"eventSubType":0,"eventDescription":"Access permitted - token only"}
```

`accessDenied` (event type 23, a token Net2 does not know):

```json
{"tokenNumber":"7654321","doorName":"Front Door","timestamp":"2026-01-16T17:09:14","doorId":1234567}
```

An event without an `eventType` (older servers) still counts as an entry, as it did before 5.2.2.

## Real-time Synchronization

The binding combines two sources:

- **SignalR live events.** The bridge first tries ASP.NET Core SignalR (`/eventHubLocal`), then classic
  SignalR (`/signalr`). It subscribes to live events, and per door to door events and door status events.
  `DoorStatusEvents` carry `doorRelayOpen` and update the `status` channel within about a second. `LiveEvents`
  (access events) feed `lastAccessUser`, `lastAccessTime`, `entryLog`, `accessEvent`, `accessDenied` and the
  lockout guard. LiveEvents do not change the door state.
- **REST poll.** Every `refreshInterval` seconds (default 30) the bridge reads `GET /api/v1/doors/status` and
  sets `status` and `action` from `doorRelayOpen`. The same cycle regenerates the activity report.

If the SignalR connection drops (Net2 restart, network outage), the bridge reconnects with a fresh login and a
growing delay of 30, 60, 120, 240 and then 300 seconds, and subscribes every door again. A connection attempt
counts only when the WebSocket is actually open. If a poll finds SignalR down with no reconnect scheduled, it
starts one at once.

## Event Types

At start the bridge asks Net2 for its event names (`GET /api/v1/events/eventTypes` and `/eventSubTypes`). A
Net2 v7.01 server answers these with HTTP 500; the bridge then reads the last week of events
(`GET /api/v1/events`, up to 1000 events) and takes the name each event carries (`eventDescription`). It logs
the resulting table at INFO. Types that did not occur in that week stay without a name.

Names reported by one Net2 v7.01 server, and what the binding does with them:

| Type | Net2 name (v7.01) | Use in the binding |
|------|-------------------|--------------------|
| 1 | Control unit reset | - |
| 16 | Access denied | `accessEvent`; counts towards a lockout; denied in the activity report |
| 17 | Access denied | as 16 |
| 20 | Access permitted - token only | `entryLog` and `accessEvent`; ends a lockout |
| 23 | (unknown token) | `accessDenied`; denied in the activity report; does not count towards a lockout (no known user) |
| 24, 25, 27 | (not seen on that server) | refusals: `accessEvent`; count towards a lockout; denied in the activity report |
| 26 | Access permitted - PIN only | `entryLog` and `accessEvent`; ends a lockout |
| 28 | Door opened | activity report |
| 29 | Door relock | activity report |
| 46 | Door opened | activity report |
| 47 | Door closed | activity report |
| 65 | Trigger and action rule was run | - |
| 67 | Relay 2 opened | - |
| 76 | Intruder alarm on partition | - |
| 93 | Door left open | activity report |
| 112 | Access Denied - Lockdown in Progress | `accessEvent`; denied in the activity report; never counts towards a lockout when Net2 names it so |
| 120 | Door Lockdown Activated | - |
| 300 | Lockdown Activated | - |
| 301 | Lockdown Reset | - |
| 500 | ACU not responding | - |
| 501 | ACU online | - |
| 550 | Operator | - |
| 551 | Alarm actioned | - |
| 552 | Backup succeeded | - |
| 573 | ACU configuration | - |
| 590 | Lockdown activated by user with network instruction | - |
| 591 | Lockdown reset by user with network instruction | - |
| 723 | Battery critically low | - |

Notes:

- 16/17 with sub type 82 is also the step just before a PIN entry: a 26 follows within seconds. That entry
  clears the count, so a PIN user does not cause a lockout.
- Any event that names a user goes to `accessEvent`, whatever its type.
- Other Net2 versions may name the types differently; the binding uses whatever names the server gives.

## ACU Lockout and Re-Instate

### Background

Net2 can make an ACU refuse every token and PIN, or run its time schedules wrongly, until the ACU is
re-instated in the Net2 software. The maintainer, a Paxton installer, has seen this at several sites and on
several Net2 versions; Paxton has not acknowledged it as a fault. From the outside it looks like a door that
refuses everyone, including users who were let in a minute earlier.

A Net2 **lockdown** also refuses everyone (type 112), on purpose. A Re-Instate lifts a lockdown at the ACU,
so the binding must not re-instate during a lockdown.

### What the binding detects

One guard per door thing, in memory:

- A **refusal** is an event of type 16, 17, 24, 25, 27 or 112 that names a known user.
- When `lockoutThreshold` refusals (default 3) fall within `lockoutWindow` minutes (default 10) with no entry
  between them, `lockout` turns ON and a WARN line is logged.
- An entry (20 or 26) clears the count; after a lockout it turns `lockout` OFF.
- A refusal never counts while a lockdown switched on through the bridge's `lockdown` channel is active, nor
  when Net2 names the event a lockdown refusal (its name contains "Lockdown", as 112 "Access Denied - Lockdown
  in Progress"). The binding logs such a refusal at INFO as "refusal during a lockdown".
- The guard lives in memory and starts empty. The binding posts its state on `lockout` when the door comes
  online and on every poll, so a value restored by persistence (mapdb restores every item by default) is
  replaced within one `refreshInterval`. A rule that notifies on `lockout` should react to OFF -> ON only: a
  restored ON arrives as NULL -> ON at start.

### Re-Instate

- `reinstate` ON sends `POST /api/v1/commands/door/reinstate` with `{"DoorId": <doorId>}`; Net2 sends the ACU
  its configuration again. `lastReinstate` is set when Net2 answers HTTP 200.
- A manual Re-Instate does **not** clear `lockout`; only the next entry does.
- With `autoReinstate=true`, the first detection re-instates at once. While the lockout lasts, further
  refusals re-instate again at most once every `reinstateInterval` minutes (default 30).
- With `autoReinstate=false` (the default) the binding only reports; a person or a rule decides.

### Lockdown interplay - recommendations

- If openHAB, or anything else, can trigger the Net2 lockdown, route that through the binding's `lockdown`
  channel. Then the guard knows that a lockdown is on, even on a server whose event names it could not load.
  The binding does not see a lockdown switched on elsewhere (Net2 software, a hard-wired input) except through
  the event name, and after an openHAB restart it does not know about a lockdown that was already on.
- Never use an alarm system's "armed" or "arming" state as a lockdown trigger; trigger only on an actual
  alarm. In a real case an arming state was read as an alarm, the lockdown locked every door, and nobody with
  a valid token got in.

### Example

```java
// things/net2.things
Bridge net2:net2server:net2 "Net2 Server" [ hostname="net2.example.com", username="John Doe",
        password="secret", clientId="00000000-0000-0000-0000-000000000000",
        autoReinstate=false, lockoutThreshold=3, lockoutWindow=10, reinstateInterval=30 ] {
    Thing door frontdoor "Front Door" [ doorId=1234567 ]
}
```

```java
// items/net2.items
Switch   Front_Door_Lockout       "Front Door lockout [%s]"        { channel="net2:door:net2:frontdoor:lockout" }
Switch   Front_Door_Reinstate     "Re-instate Front Door ACU"      { channel="net2:door:net2:frontdoor:reinstate" }
DateTime Front_Door_LastReinstate "Last re-instate [%1$tF %1$tR]"  { channel="net2:door:net2:frontdoor:lastReinstate" }
String   Front_Door_AccessEvent   "Access event [%s]"              { channel="net2:door:net2:frontdoor:accessEvent" }
Switch   Net2_Lockdown            "Building lockdown"              { channel="net2:net2server:net2:lockdown", autoupdate="false" }
```

A notification rule in JS Scripting (`automation/js/net2-lockout.js`). The notification line needs the
openHAB Cloud Connector; replace it with your own channel (mail binding, Telegram, ...):

```javascript
const { rules, triggers, items, actions } = require('openhab');

rules.JSRule({
  name: 'Net2 Front Door lockout',
  description: 'Tell someone when the Front Door ACU refuses known users',
  triggers: [triggers.ItemStateChangeTrigger('Front_Door_Lockout', 'OFF', 'ON')],
  execute: () => {
    let who = '';
    try {
      const e = JSON.parse(items.getItem('Front_Door_AccessEvent').state);
      who = ` (last: ${e.firstName} ${e.lastName}, type ${e.eventType} ${e.eventDescription || ''})`;
    } catch (err) {
      // no access event yet
    }
    const text = `Front Door: the Net2 ACU refuses known users${who}. A Re-Instate usually cures it.`;
    console.warn('net2-lockout: ' + text);
    actions.notificationBuilder(text).send();
  }
});
```

See [EXAMPLES.md](EXAMPLES.md#acu-lockout-notification) for a version that covers several doors.

## Example Configuration

### Things (.things)

```java
Bridge net2:net2server:net2 "Net2 Server" [ hostname="net2.example.com", port=8443, username="John Doe",
        password="secret", clientId="00000000-0000-0000-0000-000000000000" ] {
    Thing door frontdoor "Front Door" [ doorId=1234567 ]
    Thing door backdoor  "Back Door"  [ doorId=1234568 ]
}
```

### Items (.items)

```java
Switch   Front_Door_Action     "Front Door"                  <lock> { channel="net2:door:net2:frontdoor:action" }
Switch   Front_Door_Status     "Front Door relay [%s]"       <door> { channel="net2:door:net2:frontdoor:status" }
Number   Front_Door_Timed      "Front Door timed open"       <lock> { channel="net2:door:net2:frontdoor:controlTimed" }
String   Front_Door_LastUser   "Last user [%s]"                     { channel="net2:door:net2:frontdoor:lastAccessUser" }
DateTime Front_Door_LastTime   "Last event [%1$td.%1$tm.%1$tY %1$tH:%1$tM:%1$tS]" { channel="net2:door:net2:frontdoor:lastAccessTime" }
String   Front_Door_EntryLog   "Entry log [%s]"                     { channel="net2:door:net2:frontdoor:entryLog" }
String   Front_Door_AccessEvent "Access event [%s]"                 { channel="net2:door:net2:frontdoor:accessEvent" }
String   Front_Door_Denied     "Unknown token [%s]"                 { channel="net2:door:net2:frontdoor:accessDenied" }
Switch   Front_Door_Lockout    "Lockout [%s]"                       { channel="net2:door:net2:frontdoor:lockout" }
Switch   Front_Door_Reinstate  "Re-instate ACU"                     { channel="net2:door:net2:frontdoor:reinstate" }
DateTime Front_Door_LastReinstate "Last re-instate [%1$tF %1$tR]"   { channel="net2:door:net2:frontdoor:lastReinstate" }

// Bridge channels
String Net2_CreateUser       "Create user"        { channel="net2:net2server:net2:createUser" }
String Net2_DeleteUser       "Delete user"        { channel="net2:net2server:net2:deleteUser" }
String Net2_ListAccessLevels "List access levels" { channel="net2:net2server:net2:listAccessLevels" }
String Net2_ListUsers        "Users"              { channel="net2:net2server:net2:listUsers" }
Switch Net2_Lockdown         "Building lockdown"  { channel="net2:net2server:net2:lockdown", autoupdate="false" }
String Net2_ActivityReport   "Activity report [%s]" { channel="net2:net2server:net2:activityReport" }
```

`autoupdate="false"` on the lockdown item makes it show what Net2 accepted, not what was sent.

### Rules (DSL)

```java
rule "Front door relay released"
when
    Item Front_Door_Status changed to ON
then
    logInfo("net2", "Front door relay released")
end

rule "Open front door for 5 seconds"
when
    Item Doorbell_Button received command ON
then
    Front_Door_Timed.sendCommand(5)
end
```

Use `entryLog`, never `lastAccessUser`, when a rule must react to someone being let in: `lastAccessUser` also
changes on a refusal. See [EXAMPLES.md](EXAMPLES.md).

## User Management

```java
// firstName,lastName,accessLevel,pin - access level as ID or name
Net2_CreateUser.sendCommand("John,Doe,3,1234")
Net2_DeleteUser.sendCommand("79")          // user ID
Net2_ListAccessLevels.sendCommand("ON")    // result in openhab.log: "Access levels: [1:Public] ..."
Net2_ListUsers.sendCommand("ON")           // result: the state of Net2_ListUsers
```

The same from the REST API (the command is the request body; add `-H "Authorization: Bearer <API token>"` if
your openHAB requires authentication):

```bash
curl -X POST "http://localhost:8080/rest/items/Net2_ListUsers" -H "Content-Type: text/plain" -d "ON"
curl -s "http://localhost:8080/rest/items/Net2_ListUsers/state"
```

Or from the Karaf console (`openhab-cli console`): `openhab:send Net2_ListUsers ON`.

`listUsers` returns Net2's user objects as Net2 sends them (for example id, names, expiry and activation
dates, telephone, access level), with every field whose name contains "pin" removed at any depth. The state
reaches `events.log` and any persistence that stores the item, so do not persist it unless you need to.

## Building Lockdown

`Net2_Lockdown.sendCommand(ON)` switches the Net2 lockdown on, `OFF` switches it off. Which doors lock, and
how, is configured in the Net2 software; the binding only sends the command. The command waits for Net2's
answer; the channel state changes only when Net2 accepted it, and only then does the lockout guard stand down.

Test a lockdown in a controlled situation first, and make sure people can still leave. See the lockdown
recommendations in [ACU lockout and Re-Instate](#lockdown-interplay---recommendations).

## Activity Report

On every poll (`refreshInterval`, default 30 s) the bridge reads the last 24 hours of events
(`GET /api/v1/events`, up to 1000) and writes an HTML page to `/etc/openhab/html/net2_activity.html`, served by
openHAB at `/static/net2_activity.html`. Events are grouped by door: granted (20, 26), denied (16, 17, 23, 24,
25, 27, 112), door opened (28, 46), door closed (29, 47) and door left open (93), with totals and the number of
distinct users.

The output path is fixed in the code. On an installation whose configuration folder is not `/etc/openhab`, the
write fails and the bridge logs a WARN on every poll; the rest of the binding is unaffected.

```java
Webview url="/static/net2_activity.html" height=20
```

The page lists names; see [Privacy and logging](#privacy-and-logging).

## Privacy and Logging

Logger: `org.openhab.binding.net2`. Change the level in the Karaf console:
`log:set DEBUG org.openhab.binding.net2`, back with `log:set DEFAULT org.openhab.binding.net2`.

**INFO** (on by default):

- Every entry ("Entry log: {...}") and every other access event ("Access event (not an entry): {...}") with
  names, door and time.
- `createUser`: names, access level and whether a PIN is set (not the PIN); the new user ID. `deleteUser`: the ID.
- `listAccessLevels`: the access level names. The event type table at start. Lockout and re-instate lines.

**WARN**: an unknown token (type 23): door, token number and time.

**DEBUG**: every raw SignalR message and the event payload as the door handler receives it (names and token
numbers); the raw REST door status answer;
in ASP.NET Core SignalR mode the WebSocket URI, which **contains the access token**; in classic mode the first
20 characters of the access token.

**ERROR**: a failed login logs the HTTP status and the server's answer.

Not logged since 5.2.2: the user list and PINs. Before 5.2.2, with an item linked to `listUsers`, every start
wrote all users, with their PINs, to `openhab.log` at INFO. If you ran an earlier version, check old `openhab.log` files and backups.

Item states also leave the binding: `entryLog`, `accessEvent`, `lastAccessUser`, `accessDenied` (with the token
number) and `listUsers` go to `events.log` and to persistence. The activity report page lists names and is
served by openHAB at `/static/` without authentication; do not expose `/static` to the internet.

## API Details

- **Authentication**: `POST /api/v1/authorization/tokens` (password grant with the client ID). The token is
  refreshed 5 minutes before its `expires_in`; before every SignalR reconnect the binding logs in again. Tokens
  are kept in memory only.
- **Door control**: `POST /api/v1/commands/door/holdopen`, `/commands/door/close`, `/commands/door/control`
  (timed), `/commands/door/reinstate`
- **Lockdown**: `POST /api/v1/commands/controlLockdown` with `{"lockdown": true|false}`
- **Door status**: `GET /api/v1/doors/status`; **doors**: `GET /api/v1/doors` (discovery)
- **Users**: `GET /api/v1/users`, `POST /api/v1/users`, `DELETE /api/v1/users/{id}`,
  `PUT /api/v1/users/{id}/doorpermissionset`; **access levels**: `GET /api/v1/accesslevels`
- **Events**: `GET /api/v1/events?startDate=&endDate=&pageSize=1000`, `GET /api/v1/events/eventTypes`,
  `GET /api/v1/events/eventSubTypes`
- **SignalR**: `/eventHubLocal` (ASP.NET Core), then `/signalr` (classic)

## Security Considerations

1. Keep `tlsVerification=true` unless the Net2 certificate cannot be verified on the SignalR connection.
2. Give the binding its own Net2 operator with only the rights it needs.
3. Restrict access to the Net2 API to trusted networks.
4. Treat `openhab.log`, `events.log`, persistence and `/static/net2_activity.html` as personal data
   (see [Privacy and logging](#privacy-and-logging)).
5. Think twice before `autoReinstate=true` or any rule that opens doors or switches the lockdown, and test it.

## Troubleshooting

### Bridge does not go ONLINE

- Check hostname, port, the operator name ("First Name Last Name"), password and client ID.
- Make sure the Local API is enabled and licensed on the Net2 server.
- A certificate error on the REST calls (for example "PKIX path building failed"): REST always verifies the
  certificate against the Java trust store, whatever `tlsVerification` says. Use a certificate the JVM trusts,
  or import the Net2 server certificate into the trust store of the Java that runs openHAB (`keytool
  -importcert`), then restart openHAB.
- Test from the openHAB host: `curl -k https://net2.example.com:8443/api/v1/doors`. Any HTTP answer, even an
  authorization error without a token, shows that the API is reachable.

### Door commands do nothing

- Check `doorId` against the Net2 software, and that the operator may control doors.
- Commands are ignored while the bridge is not ONLINE.
- Look for "Failed to open door" / "Failed to close door" in `openhab.log`.

### Status does not update

- Check that the bridge is ONLINE and that "SignalR connected and subscribed successfully" appears after start.
- Without SignalR, `status` and `action` still follow the REST poll every `refreshInterval` seconds.
- `action` changes only on a command and on the poll; use `status` for the live relay state.

### Event names are missing

- Look for "Net2 event types loaded" or "Net2 event types could not be read" after start. Without names the
  JSON carries only the numbers, and a type 112 refusal is held off only while the `lockdown` channel is ON.

### Momentary relay doors (garage doors)

On doors with a momentary or pulse relay, `holdopen`, `close` and the timed open all **pulse** the relay and
toggle the door; there is no idempotent open or close. Any unintended command toggles the door. Up to 5.2.0 a
REFRESH at start-up reached the `controlTimed` handler and pulsed the relay on every openHAB start; since 5.2.0
REFRESH is ignored before any channel is handled.

## Building from Source

The binding cannot be built on its own: its `pom.xml` has the openHAB add-ons reactor
(`org.openhab.addons.reactor.bundles`, `5.2.0-SNAPSHOT`) as parent, so `mvn clean install` in this repository
fails. Build it inside an openhab-addons checkout:

```bash
git clone https://github.com/openhab/openhab-addons.git
git clone https://github.com/Prinsessen/openhab-net2-binding.git \
    openhab-addons/bundles/org.openhab.binding.net2
cd openhab-addons
# 1. set <parent><version> in bundles/org.openhab.binding.net2/pom.xml to the version in openhab-addons/pom.xml
#    (unless it is 5.2.0-SNAPSHOT already); use a 5.2 version of openhab-addons for openHAB 5.2
# 2. add <module>org.openhab.binding.net2</module> to the <modules> list in bundles/pom.xml
mvn -pl bundles/org.openhab.binding.net2 clean install
```

The JAR is in `bundles/org.openhab.binding.net2/target/`. Details, tests and code formatting:
[DEVELOPMENT.md](DEVELOPMENT.md).

## Documentation

- [QUICKSTART.md](QUICKSTART.md) - install and configure in a few steps
- [EXAMPLES.md](EXAMPLES.md) - items, sitemaps, rules, alerts, lockdown, Grafana entry log dashboard
- [ENTRY_LOGGING.md](ENTRY_LOGGING.md) - the `entryLog` and `accessEvent` channels in detail
- [DEVELOPMENT.md](DEVELOPMENT.md) - building, tests, project layout
- [CONTRIBUTING.md](CONTRIBUTING.md) - how to contribute
- [CHANGELOG.md](CHANGELOG.md) - version history
- [MARKETPLACE_POST.md](MARKETPLACE_POST.md) - the openHAB community marketplace text

## Support

For issues related to:
- **This binding**: [GitHub issues](https://github.com/Prinsessen/openhab-net2-binding/issues) or the openHAB
  community forum; include the openHAB, binding and Net2 versions and log lines with names and token numbers
  removed
- **Net2 API**: Paxton support
- **Door hardware**: your door system installer

### Professional Installation Services

If you need a **certified Paxton hardware installer**:

**Agesen El-Teknik**  
Terndrupvej 81  
9460 Brovst, Denmark  
📞 +45 98 23 20 10  
📧 Nanna@agesen.dk  

*Specializing in Paxton access control systems with 25+ years of experience.*

## Maintainer

Nanna Agesen (GitHub: [Prinsessen](https://github.com/Prinsessen))

## License

Eclipse Public License 2.0, see [LICENSE](LICENSE).
