# Paxton Net2 Access Control Binding

This binding connects openHAB to a Paxton Net2 access control server through the Net2 Local API (REST plus
SignalR live events): door control, live door state, access logging, user management, building lockdown, and
detection of an ACU that has started to refuse every user.

## What is new in 5.2.2

- **ACU lockout detection and Re-Instate.** Net2 can make an ACU refuse every token and PIN, or run its time
  schedules wrongly, until the ACU is re-instated in the Net2 software. The binding notices when one door
  refuses known users several times with no entry between (`lockout` channel), and can send Net2's Re-Instate
  by command (`reinstate`) or automatically (`autoReinstate`). It stands down during a Net2 lockdown.
- **`accessEvent` channel**: every event that names a user, granted or refused, with Net2's event type and name.
- **`entryLog` carries granted access only** (types 20 and 26). Before, refusals were posted as entries too.
- **Event names** from the Net2 server on the event JSON.
- **Privacy fixes**: no PINs in `openhab.log` any more (earlier versions, with an item linked to `listUsers`,
  logged every user with PIN at every start); `listUsers` puts the user list on its channel without PINs; token
  numbers are logged at DEBUG only.

## Features

- **Door control**: hold open, close, timed open (timing on the Net2 side)
- **Live door state**: SignalR `DoorStatusEvents`, with a REST poll every 30 s (default) as the safety net
- **SignalR auto-reconnect** with a fresh login after a Net2 restart or network outage
- **Access logging**: last user and time per door, JSON entry log, JSON access events
- **Unknown tokens**: JSON event for a token Net2 does not know (type 23)
- **ACU lockout detection and Re-Instate** (new in 5.2.2)
- **User management**: create and delete users, assign an access level, list users and access levels
- **Building lockdown**: switch the Net2 lockdown on and off
- **Activity report**: HTML page with the last 24 hours of door events, for a sitemap Webview
- **Door discovery** from the Net2 server

## Requirements

- openHAB 5.2, Java 21
- Paxton Net2 server with the Local API licensed and enabled, its API client ID, and a Net2 operator account
- Net2 Plus or Net2 Classic ACUs (Net2 Nano and Paxlock are not supported)
- Tested against Net2 v7.01

## Supported Things

| Thing type | Description |
|------------|-------------|
| `net2server` (Bridge) | The Net2 server, REST API plus SignalR |
| `door` | One Net2 door |

## Channels

### Door channels

| Channel | Type | Access | Description |
|---------|------|--------|-------------|
| `status` | Switch | RO | Door relay: ON = released/open, OFF = locked (SignalR and poll) |
| `action` | Switch | RW | ON = hold open, OFF = close; state follows commands and the poll |
| `controlTimed` | Number | RW | Open for N seconds |
| `lastAccessUser` | String | RO | User named by the last event (entry or refusal) |
| `lastAccessTime` | DateTime | RO | Time of the last event |
| `entryLog` | String | RO | JSON, granted access only (types 20, 26) |
| `accessEvent` | String | RO | JSON, every event that names a user, granted or refused |
| `accessDenied` | String | RO | JSON, unknown token (type 23) |
| `lockout` | Switch | RO | ON when the ACU refuses known users; OFF at the next entry |
| `reinstate` | Switch | RW | ON sends Net2's Re-Instate to the door's ACU |
| `lastReinstate` | DateTime | RO | When Net2 last accepted a Re-Instate |

### Bridge channels

| Channel | Type | Access | Description |
|---------|------|--------|-------------|
| `createUser` | String | WO | `firstName,lastName,accessLevel,pin` |
| `deleteUser` | String | WO | User ID |
| `listAccessLevels` | String | WO | Lists access levels in `openhab.log` |
| `listUsers` | String | RW | Send ON; the state becomes the users JSON without PINs |
| `lockdown` | Switch | RW | Net2 lockdown on/off; state set when Net2 accepted |
| `activityReport` | String | RO | Time of the last activity report |

## Bridge Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `hostname` | - | Net2 server host or IP (required) |
| `port` | 8443 | Local API HTTPS port |
| `username` | - | Net2 operator, "First Name Last Name" (required) |
| `password` | - | Operator password (required) |
| `clientId` | - | API licence client ID (required) |
| `tlsVerification` | true | Certificate check on the SignalR connection (REST always verifies) |
| `refreshInterval` | 30 | Seconds between door status polls |
| `autoReinstate` | false | Re-instate an ACU automatically on a lockout |
| `lockoutThreshold` | 3 | Refusals that mean a lockout |
| `lockoutWindow` | 10 | Minutes within which they must fall |
| `reinstateInterval` | 30 | Least minutes between automatic re-instates of one door |

Door parameter: `doorId` (required). The door is named by its thing label.

## Quick Start

### net2.things

```java
Bridge net2:net2server:net2 "Net2 Server" [ hostname="net2.example.com", port=8443, username="John Doe",
        password="secret", clientId="00000000-0000-0000-0000-000000000000" ] {
    Thing door frontdoor "Front Door" [ doorId=1234567 ]
    Thing door backdoor  "Back Door"  [ doorId=1234568 ]
}
```

### net2.items

```java
Switch   Front_Door_Action      "Front Door"            <lock> { channel="net2:door:net2:frontdoor:action" }
Switch   Front_Door_Status      "Front Door relay [%s]" <door> { channel="net2:door:net2:frontdoor:status" }
String   Front_Door_EntryLog    "Entry log [%s]"               { channel="net2:door:net2:frontdoor:entryLog" }
String   Front_Door_AccessEvent "Access event [%s]"            { channel="net2:door:net2:frontdoor:accessEvent" }
String   Front_Door_Denied      "Unknown token [%s]"           { channel="net2:door:net2:frontdoor:accessDenied" }
Switch   Front_Door_Lockout     "Lockout [%s]"                 { channel="net2:door:net2:frontdoor:lockout" }
Switch   Front_Door_Reinstate   "Re-instate ACU"               { channel="net2:door:net2:frontdoor:reinstate" }
Switch   Net2_Lockdown          "Building lockdown"            { channel="net2:net2server:net2:lockdown", autoupdate="false" }
```

### Rule (DSL)

```java
rule "Front door lockout"
when
    Item Front_Door_Lockout changed to ON
then
    logWarn("net2", "Front Door refuses known users - the ACU may need a Re-Instate")
end
```

Use `entryLog` for anything that should react to someone being let in; `lastAccessUser` also changes on a
refusal.

## Resources

- **Download JAR (v5.2.2):** [org.openhab.binding.net2-5.2.2.jar](https://github.com/Prinsessen/openhab-net2-binding/releases/download/v5.2.2/org.openhab.binding.net2-5.2.2.jar) (SIZE_TBD)
- **Install:** copy the JAR into the openHAB `addons` folder (`/usr/share/openhab/addons/` on apt installs,
  `$OPENHAB_HOME/addons/` otherwise); it is picked up without a restart. Remove an older Net2 JAR first.
- **Source code:** [github.com/Prinsessen/openhab-net2-binding](https://github.com/Prinsessen/openhab-net2-binding)
- **Documentation:** [README.md](https://github.com/Prinsessen/openhab-net2-binding/blob/main/README.md)
- **Quick start:** [QUICKSTART.md](https://github.com/Prinsessen/openhab-net2-binding/blob/main/QUICKSTART.md)
- **Examples:** [EXAMPLES.md](https://github.com/Prinsessen/openhab-net2-binding/blob/main/EXAMPLES.md)
- **Entry logging:** [ENTRY_LOGGING.md](https://github.com/Prinsessen/openhab-net2-binding/blob/main/ENTRY_LOGGING.md)
- **Changelog:** [CHANGELOG.md](https://github.com/Prinsessen/openhab-net2-binding/blob/main/CHANGELOG.md)
- **License:** EPL-2.0
- **Maintainer:** Nanna Agesen (GitHub: Prinsessen)
