# Net2 Binding Examples

Configuration examples for the Net2 binding 5.2.2: things, items, sitemaps, rules, alerts, the ACU lockout,
the building lockdown, and an entry log dashboard in Grafana. Channel and parameter reference:
[README.md](README.md).

All examples use the bridge `net2` and the doors `frontdoor`, `backdoor` and `garage`. The rules are in
openHAB's DSL (`rules/*.rules`) unless marked as JS Scripting (`automation/js/*.js`, needs the JS Scripting
add-on). Rules that read JSON with `transform("JSONPATH", ...)` need the JSONPath transformation add-on.

## Contents

- [Things and items](#things-and-items)
- [Sitemap](#sitemap)
- [Keeping action in step with status](#keeping-action-in-step-with-status)
- [Door control](#door-control)
- [User management](#user-management)
- [Reacting to an entry](#reacting-to-an-entry)
- [Access denied alerts](#access-denied-alerts)
- [ACU lockout notification](#acu-lockout-notification)
- [Building lockdown](#building-lockdown)
- [Other rules](#other-rules)
- [Persistence](#persistence)
- [Entry log dashboard with InfluxDB and Grafana](#entry-log-dashboard-with-influxdb-and-grafana)
- [Controlling doors from outside openHAB](#controlling-doors-from-outside-openhab)

## Things and items

**things/net2.things**

```java
Bridge net2:net2server:net2 "Net2 Server" [ hostname="net2.example.com", port=8443, username="John Doe",
        password="secret", clientId="00000000-0000-0000-0000-000000000000", refreshInterval=30 ] {
    Thing door frontdoor "Front Door" [ doorId=1234567 ]
    Thing door backdoor  "Back Door"  [ doorId=1234568 ]
    Thing door garage    "Garage"     [ doorId=1234569 ]
}
```

The thing label ("Front Door") is the `doorName` in every event JSON.

**items/net2.items**

```java
Group gNet2Status   "Net2 door relays"
Group gNet2Denied   "Net2 unknown tokens"
Group gNet2Events   "Net2 access events"
Group gNet2Lockout  "Net2 ACU lockouts"
Group gNet2EntryLog "Net2 entry logs"

// Front Door
Switch   Front_Door_Action        "Front Door"                       <lock> { channel="net2:door:net2:frontdoor:action" }
Switch   Front_Door_Status        "Front Door relay [%s]"            <door> (gNet2Status)   { channel="net2:door:net2:frontdoor:status" }
Number   Front_Door_Timed         "Front Door timed open"            <lock> { channel="net2:door:net2:frontdoor:controlTimed" }
String   Front_Door_LastUser      "Last user [%s]"                          { channel="net2:door:net2:frontdoor:lastAccessUser" }
DateTime Front_Door_LastTime      "Last event [%1$td.%1$tm.%1$tY %1$tH:%1$tM:%1$tS]" { channel="net2:door:net2:frontdoor:lastAccessTime" }
String   Front_Door_EntryLog      "Entry log [%s]"                          (gNet2EntryLog) { channel="net2:door:net2:frontdoor:entryLog" }
String   Front_Door_AccessEvent   "Access event [%s]"                       (gNet2Events)   { channel="net2:door:net2:frontdoor:accessEvent" }
String   Front_Door_Denied        "Unknown token [%s]"                      (gNet2Denied)   { channel="net2:door:net2:frontdoor:accessDenied" }
Switch   Front_Door_Lockout       "Front Door lockout [%s]"                 (gNet2Lockout)  { channel="net2:door:net2:frontdoor:lockout" }
Switch   Front_Door_Reinstate     "Re-instate Front Door ACU"               { channel="net2:door:net2:frontdoor:reinstate" }
DateTime Front_Door_LastReinstate "Last re-instate [%1$tF %1$tR]"           { channel="net2:door:net2:frontdoor:lastReinstate" }

// Back Door and Garage: the same pattern, for example
Switch   Back_Door_Action         "Back Door"                        <lock> { channel="net2:door:net2:backdoor:action" }
Switch   Back_Door_Status         "Back Door relay [%s]"             <door> (gNet2Status)   { channel="net2:door:net2:backdoor:status" }
String   Back_Door_EntryLog       "Entry log [%s]"                          (gNet2EntryLog) { channel="net2:door:net2:backdoor:entryLog" }
String   Back_Door_AccessEvent    "Access event [%s]"                       (gNet2Events)   { channel="net2:door:net2:backdoor:accessEvent" }
String   Back_Door_Denied         "Unknown token [%s]"                      (gNet2Denied)   { channel="net2:door:net2:backdoor:accessDenied" }
Switch   Back_Door_Lockout        "Back Door lockout [%s]"                  (gNet2Lockout)  { channel="net2:door:net2:backdoor:lockout" }

// Bridge
String Net2_CreateUser       "Create user"          { channel="net2:net2server:net2:createUser" }
String Net2_DeleteUser       "Delete user"          { channel="net2:net2server:net2:deleteUser" }
String Net2_ListAccessLevels "List access levels"   { channel="net2:net2server:net2:listAccessLevels" }
String Net2_ListUsers        "Users"                { channel="net2:net2server:net2:listUsers" }
Switch Net2_Lockdown         "Building lockdown"    { channel="net2:net2server:net2:lockdown", autoupdate="false" }
String Net2_ActivityReport   "Activity report [%s]" { channel="net2:net2server:net2:activityReport" }
```

`autoupdate="false"` on `Net2_Lockdown` lets the item show what Net2 accepted: the binding sets the state only
after Net2 answered the command with success.

## Sitemap

**sitemaps/net2.sitemap**

```java
sitemap net2 label="Net2" {
    Frame label="Front Door" {
        Switch  item=Front_Door_Action mappings=[ON="Hold open", OFF="Close"]
        Switch  item=Front_Door_Timed  label="Open for 5 s" mappings=[5="Open"]
        Text    item=Front_Door_Status
        Text    item=Front_Door_LastUser
        Text    item=Front_Door_LastTime
        Text    item=Front_Door_EntryLog label="Last entry [JS(net2-entrylog.js):%s]" icon="log"
        Text    item=Front_Door_Lockout
        Switch  item=Front_Door_Reinstate mappings=[ON="Re-instate"]
        Text    item=Front_Door_LastReinstate
    }
    Frame label="Building" {
        Switch  item=Net2_Lockdown mappings=[ON="Lockdown on", OFF="Lockdown off"]
        Text    item=Net2_ActivityReport
        Webview url="/static/net2_activity.html" height=20
    }
}
```

**transform/net2-entrylog.js** (JS transformation, needs the JS Scripting add-on):

```javascript
(function (data) {
  // an item without a state can hand the transform the format pattern instead of a value
  if (!data || data === 'NULL' || data === 'UNDEF' || data.charAt(0) === '%') {
    return 'No entries yet';
  }
  try {
    const e = JSON.parse(data);
    return e.firstName + ' ' + e.lastName + ' at ' + e.doorName + ', ' + e.timestamp.substring(11, 19);
  } catch (err) {
    return 'Unreadable entry';
  }
})(input)
```

## Keeping action in step with status

`status` follows the relay live (SignalR `DoorStatusEvents`); `action` changes only on a command and on the
REST poll (every `refreshInterval` seconds). If the `action` switch in a UI should follow the door between
polls, copy the state across. One rule covers every door whose items follow the `<Door>_Status` /
`<Door>_Action` naming:

**rules/net2-sync.rules**

```java
rule "Net2 relay state to action switch"
when
    Member of gNet2Status changed
then
    val actionItem = triggeringItem.name.replace("_Status", "_Action")
    postUpdate(actionItem, triggeringItem.state.toString)
end
```

`postUpdate` changes only the item's state; it sends nothing to Net2.

## Door control

### Hold open and close (`action`)

- ON holds the door open (`POST /api/v1/commands/door/holdopen`) until it is closed again.
- OFF closes it (`POST /api/v1/commands/door/close`).

### Timed open (`controlTimed`)

The value is the open time in seconds. The binding sends:

```json
{"DoorId":1234567,"RelayFunction":{"RelayId":"Relay1","RelayAction":"TimedOpen","RelayOpenTime":5000},"LedFlash":3}
```

`RelayOpenTime` is the value times 1000 (milliseconds). Relay and LED flash count are fixed in the code. The
channel state becomes the value sent once Net2 accepted it (HTTP 200 or 202).

```java
rule "Open the front door for 10 seconds"
when
    Item Doorbell_Button received command ON
then
    Front_Door_Timed.sendCommand(10)
end
```

On a door with a momentary relay (a garage door) every command, timed or not, pulses the relay and toggles
the door. Make sure no rule sends one by accident.

## User management

### List users

```java
Net2_ListUsers.sendCommand("ON")
```

The state of `Net2_ListUsers` becomes Net2's user list as JSON, with every field whose name contains "pin"
removed. Read it with:

```bash
curl -s "http://localhost:8080/rest/items/Net2_ListUsers/state"
```

The field names are those of the Net2 API (for example `id`, `firstName`, `lastName`, expiry and activation
dates, telephone, access level). Check your server's answer rather than relying on a fixed list. The state
reaches `events.log` and persistence; leave `Net2_ListUsers` out of persistence unless you need it.

### List access levels

```java
Net2_ListAccessLevels.sendCommand("ON")
```

The result goes to `openhab.log` only:

```bash
grep "Access levels" /var/log/openhab/openhab.log | tail -1
# Access levels: [1:Public] [2:Staff] [3:Admin]
```

### Create a user

```java
// firstName,lastName,accessLevel,pin - the access level as ID or name
Net2_CreateUser.sendCommand("John,Doe,Staff,1234")
```

The log shows "User created successfully with ID: ..." and whether the access level was assigned. If the access
level is not found, the user is created without one and a WARN is logged.

### Delete a user

```java
Net2_DeleteUser.sendCommand("79")   // the user ID, from listUsers
```

### From the REST API or the console

The REST API takes the command as the request body of `POST /rest/items/{itemName}`. Add
`-H "Authorization: Bearer <API token>"` if your openHAB requires authentication.

```bash
curl -X POST "http://localhost:8080/rest/items/Net2_CreateUser" -H "Content-Type: text/plain" -d "John,Doe,3,1234"
curl -X POST "http://localhost:8080/rest/items/Net2_ListAccessLevels" -H "Content-Type: text/plain" -d "ON"
curl -X POST "http://localhost:8080/rest/items/Net2_DeleteUser" -H "Content-Type: text/plain" -d "79"
```

Karaf console (`openhab-cli console`): `openhab:send Net2_ListUsers ON`.

## Reacting to an entry

A rule that should react to someone **being let in** must trigger on `entryLog`, never on `lastAccessUser`.
`lastAccessUser` changes on every event that names a user, refusals included: a badge that Net2 refuses, an ACU
that refuses everyone, or a lockdown would all change it. A rule on `lastAccessUser` that disarms an alarm would
disarm it for a person who was refused at the door.

**rules/net2-alarm.rules**

```java
rule "Disarm the alarm when John Doe is let in at the front door"
when
    Item Front_Door_EntryLog changed
then
    val json = Front_Door_EntryLog.state.toString
    val first = transform("JSONPATH", "$.firstName", json)
    val last  = transform("JSONPATH", "$.lastName", json)
    if (first == "John" && last == "Doe") {
        Alarm_System.sendCommand("DISARM")
        logInfo("net2", "Alarm disarmed after entry by John Doe")
    }
end
```

Every entry carries its own timestamp, so `changed` fires for each one.

## Access denied alerts

Two channels report refusals:

| Channel | What | Contains |
|---------|------|----------|
| `accessDenied` | a token Net2 does not know (event type 23) | `tokenNumber`, `doorName`, `timestamp`, `doorId` |
| `accessEvent` | every event that names a user, granted or refused | names, door, time, `eventType`, `eventSubType`, names of the type when known |

A refusal of a known user is an `accessEvent` with `eventType` 16, 17, 24, 25, 27 or 112 (see the event type
table in the README). 16/17 with sub type 82 is also the normal step before a PIN; it is followed by a 26 within
seconds and is not worth an alert on its own.

The binding also logs every unknown token at WARN: `Access DENIED at door Front Door: Token 7654321 at ...`.

### Mail binding

The alerts below use the Mail binding. An SMTP thing, for example in **things/mail.things**:

```java
Thing mail:smtp:samplesmtp "SMTP" [ hostname="smtp.example.com", port="587", security="STARTTLS",
        sender="openhab@example.com", username="openhab@example.com", password="secret" ]
```

For an SMS, send the mail to your provider's e-mail-to-SMS address, if it has one.

### Unknown tokens, all doors in one rule

**rules/net2-denied.rules**

```java
rule "Net2 unknown token"
when
    Member of gNet2Denied changed
then
    val json = triggeringItem.state.toString
    if (json == "NULL" || json == "UNDEF" || !json.contains("tokenNumber")) {
        return
    }
    val door  = transform("JSONPATH", "$.doorName", json)
    val token = transform("JSONPATH", "$.tokenNumber", json)
    val time  = transform("JSONPATH", "$.timestamp", json)

    val text = "An unknown token was presented at " + door + ".\n" +
               "Token: " + token + "\n" +
               "Time: " + time
    logWarn("net2", "Unknown token " + token + " at " + door)

    val mail = getActions("mail", "mail:smtp:samplesmtp")
    mail.sendMail("security@example.com", "Net2: unknown token at " + door, text)
end
```

`triggeringItem` is the door's item that changed, so there is no need to compare timestamps across doors.

### Repeated unknown tokens

```java
var Timer net2RepeatTimer = null
var int net2Attempts = 0
var String net2LastToken = ""

rule "Net2 repeated unknown token"
when
    Member of gNet2Denied changed
then
    val json = triggeringItem.state.toString
    if (!json.contains("tokenNumber")) {
        return
    }
    val token = transform("JSONPATH", "$.tokenNumber", json)
    if (token == net2LastToken) {
        net2Attempts = net2Attempts + 1
    } else {
        net2LastToken = token
        net2Attempts = 1
    }
    if (net2Attempts == 3) {
        val mail = getActions("mail", "mail:smtp:samplesmtp")
        mail.sendMail("security@example.com", "Net2: repeated attempts",
            "Token " + token + " was presented 3 times within 5 minutes.")
    }
    net2RepeatTimer?.cancel()
    net2RepeatTimer = createTimer(now.plusMinutes(5), [ |
        net2Attempts = 0
        net2LastToken = ""
    ])
end
```

### Refused known users (JS Scripting)

**automation/js/net2-refusals.js**

```javascript
const { rules, triggers, items } = require('openhab');

// Net2's refusal types; 16/17 with sub type 82 is the step before a PIN, not a refusal on its own
const REFUSALS = [16, 17, 24, 25, 27, 112];

rules.JSRule({
  name: 'Net2 refused known user',
  description: 'Log refusals of known users at any door',
  triggers: [triggers.GroupStateChangeTrigger('gNet2Events')],
  execute: (event) => {
    let e;
    try {
      e = JSON.parse(items.getItem(event.itemName).state);
    } catch (err) {
      return;
    }
    if (!REFUSALS.includes(e.eventType)) {
      return;
    }
    if ((e.eventType === 16 || e.eventType === 17) && e.eventSubType === 82) {
      return;
    }
    console.warn(`net2-refusals: ${e.firstName} ${e.lastName} refused at ${e.doorName} ` +
      `(type ${e.eventType}${e.eventDescription ? ' ' + e.eventDescription : ''})`);
  }
});
```

### Camera recording on an unknown token

```java
rule "Record the front door camera on an unknown token"
when
    Item Front_Door_Denied changed
then
    Front_Door_Camera_Record.sendCommand(ON)
end
```

## ACU lockout notification

The binding turns `lockout` ON when one door refuses known users `lockoutThreshold` times within
`lockoutWindow` minutes with no entry between (README: [ACU lockout and Re-Instate](README.md#acu-lockout-and-re-instate)).
This rule tells someone, for every door in `gNet2Lockout`. The notification needs the openHAB Cloud Connector;
replace it with the mail action or another channel if you do not use it.

**automation/js/net2-lockout.js**

```javascript
const { rules, triggers, items, actions } = require('openhab');

rules.JSRule({
  name: 'Net2 ACU lockout',
  description: 'Tell someone when a door refuses known users, and when it lets people in again',
  triggers: [
    triggers.GroupStateChangeTrigger('gNet2Lockout', 'OFF', 'ON'),    // OFF -> ON only: a restored ON arrives as NULL -> ON
    triggers.GroupStateChangeTrigger('gNet2Lockout', 'ON', 'OFF')
  ],
  execute: (event) => {
    const door = items.getItem(event.itemName).label;
    const text = event.newState === 'ON'
      ? `${door}: the Net2 ACU refuses known users. A Re-Instate usually cures it.`
      : `${door}: access granted again, the lockout is over.`;
    console.warn('net2-lockout: ' + text);
    actions.notificationBuilder(text).send();
  }
});
```

To re-instate by hand, send ON to the door's `reinstate` item (from the sitemap above, or
`items.getItem('Front_Door_Reinstate').sendCommand('ON')`). With `autoReinstate=true` the binding does it
itself; do not also re-instate from a rule then, or the two will both send Re-Instates.

A manual Re-Instate does not turn `lockout` OFF; the next entry does. `Front_Door_LastReinstate` shows when Net2
last accepted one.

## Building lockdown

The `lockdown` bridge channel switches the Net2 lockdown on (ON) and off (OFF) with
`POST /api/v1/commands/controlLockdown`. Which doors lock, and how, is configured in the Net2 software; the
binding only sends the command. The command waits for Net2's answer, and the channel state changes only when
Net2 accepted it. While a lockdown switched on through this channel is active, the lockout guard does not count
refusals (a lockdown refuses everyone on purpose, and a Re-Instate would lift it at the ACU).

Route every lockdown that openHAB starts through this channel. The binding does not see a lockdown started
elsewhere except through Net2's event name, and after an openHAB restart it does not know about a lockdown that
was already on.

### From an intruder alarm

Trigger a lockdown only on an actual alarm, never on an "armed" or "arming" state. In a real case an arming
state was taken for an alarm, and the lockdown locked every door for everyone with a valid token.

```java
rule "Net2 lockdown on intruder alarm"
when
    Item Intruder_Alarm_Active changed to ON     // the alarm itself, not the armed state
then
    Net2_Lockdown.sendCommand(ON)
    logWarn("net2", "Lockdown requested: intruder alarm")
end

rule "Net2 lockdown off"
when
    Item Lockdown_Reset_Button received command ON
then
    Net2_Lockdown.sendCommand(OFF)
    logInfo("net2", "Lockdown off requested")
end
```

There is no example here for a fire alarm. Fire usually means doors must release, not lock; how doors behave
in a fire is a matter for the fire installation and the Net2 configuration, not for an openHAB rule.

A lockdown is not a schedule. For doors that should be locked at night, use the Net2 software's own time
settings, or the `action` channel (see [Other rules](#other-rules)).

### Logging lockdown changes

```java
rule "Net2 lockdown state"
when
    Item Net2_Lockdown changed
then
    if (Net2_Lockdown.state == ON) {
        logWarn("net2", "Lockdown is ON (accepted by Net2)")
    } else {
        logInfo("net2", "Lockdown is OFF (accepted by Net2)")
    }
end
```

With `autoupdate="false"` on the item, this rule fires only for states Net2 accepted.

### From the REST API

```bash
curl -X POST "http://localhost:8080/rest/items/Net2_Lockdown" -H "Content-Type: text/plain" -d "ON"
curl -X POST "http://localhost:8080/rest/items/Net2_Lockdown" -H "Content-Type: text/plain" -d "OFF"
grep -i lockdown /var/log/openhab/openhab.log | tail -20
```

Test a lockdown in a controlled situation first, and check that people can still leave the building.

## Other rules

### Lock the front door at night

```java
rule "Close the front door at 22:00"
when
    Time cron "0 0 22 * * ?"
then
    Front_Door_Action.sendCommand(OFF)
end

rule "Hold the front door open on weekday mornings"
when
    Time cron "0 0 7 ? * MON-FRI"
then
    Front_Door_Action.sendCommand(ON)
end
```

openHAB cron expressions have a seconds field, and either day-of-month or day-of-week must be `?`.

### Emergency unlock

```java
rule "Emergency: hold all doors open"
when
    Item Emergency_Button received command ON
then
    Front_Door_Action.sendCommand(ON)
    Back_Door_Action.sendCommand(ON)
    logWarn("net2", "All doors held open by the emergency button")
end
```

Leave doors with a momentary relay (garage doors) out of such a rule: a command toggles them.

### Log every event at a door

```java
rule "Front door event"
when
    Item Front_Door_LastTime changed
then
    logInfo("net2", "Front door event, last user: " + Front_Door_LastUser.state)
end
```

This fires for entries and refusals alike.

## Persistence

**persistence/influxdb.persist**

```java
Strategies {
    default = everyChange
}

Items {
    gNet2EntryLog*, gNet2Events*, gNet2Denied* : strategy = everyChange
    gNet2Status*                               : strategy = everyChange
}
```

`everyChange` is built in; it needs no definition under `Strategies`. Each entry and access event carries its
own timestamp, so every event is a change and is stored. String items are stored in InfluxDB like any other
item.

Do not restore `lockout` items at start-up (no `restoreOnStartup` for them in any `.persist` file): the binding's
lockout guard starts empty after a restart, and a restored ON would be stale.

## Entry log dashboard with InfluxDB and Grafana

A Grafana table of all entries at all doors, newest first, from the `entryLog` items persisted to InfluxDB 2
(Flux). It uses the persistence file above.

openHAB's InfluxDB persistence writes each item as its own measurement (the item name) with the state in the
field `value`. Use your own bucket name below. With InfluxDB 1.8 and Flux, the bucket is written as
`database/retention`, for example `openhab/autogen`.

### Query

Panel type **Table**, query (Flux):

```flux
from(bucket: "openhab")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement =~ /_EntryLog$/ and r._field == "value")
  |> group()
  |> sort(columns: ["_time"], desc: true)
```

`group()` puts all doors into one table, so no Merge transformation is needed. For one door, filter on its
measurement instead: `r._measurement == "Front_Door_EntryLog"`. For refusals, use the `_AccessEvent`
measurements and filter `eventType` in Grafana after the field extraction.

### Transformations

1. **Extract fields**: source `_value`, format JSON. This gives `firstName`, `lastName`, `doorName`,
   `timestamp`, `doorId`, `eventType`, `eventSubType` and `eventDescription`.
2. **Organize fields**: hide `_value`, `_measurement`, `_field` and what you do not need (`doorId`,
   `eventSubType`); rename `firstName` to First Name, `lastName` to Last Name, `doorName` to Door,
   `timestamp` to Entry Time, `eventDescription` to How.

To show `timestamp` as a date, add a field override on it: Standard options -> Unit -> a date/time format.

### Result

| Entry Time | First Name | Last Name | Door | How |
|------------|------------|-----------|------|-----|
| 2026-10-27 10:17:35 | John | Doe | Front Door | Access permitted - token only |
| 2026-10-27 08:25:01 | Jane | Doe | Back Door | Access permitted - PIN only |

### Troubleshooting

- **No data:** check that the items reach openHAB (`grep EntryLog /var/log/openhab/events.log | tail -5`) and
  that the measurements exist in the bucket (InfluxDB UI, Data Explorer).
- **"Data is missing a number field":** use the Table panel type; the values are strings.
- **Wrong order:** keep `sort(columns: ["_time"], desc: true)` after `group()`, or add a Sort by
  transformation on `timestamp`.

### Retention

In InfluxDB 2 the retention is a property of the bucket, for example
`influx bucket update --id <bucket-id> --retention 90d`, or in the UI under Load Data -> Buckets. Entry logs
are personal data; keep them only as long as you need them.

## Controlling doors from outside openHAB

Any program can send a command to an item with `POST /rest/items/{itemName}` and the command as a plain-text
body:

```python
#!/usr/bin/env python3
import requests

OPENHAB = "http://localhost:8080"
TOKEN = ""   # an openHAB API token, if your openHAB requires authentication


def send(item: str, command: str) -> bool:
    headers = {"Content-Type": "text/plain"}
    if TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"
    r = requests.post(f"{OPENHAB}/rest/items/{item}", data=command, headers=headers, timeout=10)
    return r.ok


if __name__ == "__main__":
    send("Front_Door_Timed", "5")      # open the front door for 5 seconds
```

A success answer means openHAB accepted the command, not that Net2 carried it out; watch the item state (or
`openhab.log`) for that.
