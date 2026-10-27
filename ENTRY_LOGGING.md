# Entry Logging and Access Events

How the Net2 binding reports who came in (`entryLog`) and every access event that names a user
(`accessEvent`), as of release 5.2.2. Configuration examples, including a Grafana dashboard, are in
[EXAMPLES.md](EXAMPLES.md).

## The channels

| Channel | Item type | Posted for |
|---------|-----------|------------|
| `entryLog` | String (read-only) | Granted access only: Net2 event types 20 "Access permitted - token only" and 26 "Access permitted - PIN only", or an event without an `eventType` (older servers) |
| `accessEvent` | String (read-only) | Every LiveEvent that names a user, granted or refused (new in 5.2.2) |
| `accessDenied` | String (read-only) | A token Net2 does not know (event type 23); see the README |

Both `entryLog` and `accessEvent` need a LiveEvent with a user name. A door opened from openHAB, from the
Net2 software or by a timed open carries no user and produces neither.

**Change in 5.2.2:** up to 5.2.1 every LiveEvent with a user name was posted to `entryLog`, refusals
included, so a refused badge showed up as an entry. From 5.2.2 refusals go to `accessEvent` only. If you
persisted `entryLog` with an earlier version, older records can include refusals and carry no `eventType`.

## JSON format

Both channels carry the same object; `entryLog` simply gets only the granted ones.

```json
{
  "firstName": "John",
  "lastName": "Doe",
  "doorName": "Front Door",
  "timestamp": "2026-10-27T08:25:01",
  "doorId": 1234567,
  "eventType": 26,
  "eventSubType": 0,
  "eventDescription": "Access permitted - PIN only"
}
```

| Field | Type | Source | Present |
|-------|------|--------|---------|
| `firstName` | string | Net2 `userName`, after the first space | always |
| `lastName` | string | Net2 `userName`, up to the first space | always |
| `doorName` | string | the door thing's label | always |
| `timestamp` | string | Net2 `eventTime` as sent (local time, no zone); empty if missing | always |
| `doorId` | number | the door thing's `doorId` | always |
| `eventType` | number | Net2 `eventType` | when Net2 sent it |
| `eventSubType` | number | Net2 `eventSubType` | when Net2 sent it |
| `eventDescription` | string | Net2's name for the event type | when the bridge knows it |
| `eventSubTypeDescription` | string | Net2's name for the sub type | when the bridge knows it |

Notes:

- Net2 sends the user as "Doe John" (last name first). The binding splits at the first space: the first word
  is `lastName`, the rest `firstName`. A last name with a space in it ends up partly in `firstName`.
- The token number is never in this JSON; the user is already named.
- `doorName` comes from the thing label, not from the door's `name` parameter (which is unused). Give every
  door thing a label.
- Event names: at start the bridge reads Net2's event type list, or, where the server answers it with an error
  (HTTP 500 from Net2 v7.01), the last week of events. Types that did not occur in that week have no name until
  the next start. The README has the table of names seen on a v7.01 server.

## What else an event does

For each LiveEvent at a door the binding also:

- sets `lastAccessUser` (if the event names a user) and `lastAccessTime` (if it has a time) - refusals included;
- logs the JSON at INFO: "Entry log: {...}" for an entry, "Access event (not an entry): {...}" otherwise;
- feeds the ACU lockout guard: an entry clears the refusal count and ends a lockout; a refusal of type 16, 17,
  24, 25, 27 or 112 counts unless a lockdown is behind it (README, "ACU lockout and Re-Instate").

## Using it

**Items**

```java
String Front_Door_EntryLog    "Entry log [%s]"    { channel="net2:door:net2:frontdoor:entryLog" }
String Front_Door_AccessEvent "Access event [%s]" { channel="net2:door:net2:frontdoor:accessEvent" }
```

**Rules:** trigger on `entryLog` for anything that should follow someone being let in (lights, alarm, presence).
Never trigger such a rule on `lastAccessUser`, which also changes when Net2 refuses someone. Use
`accessEvent` to see refusals. Examples: [EXAMPLES.md - Reacting to an entry](EXAMPLES.md#reacting-to-an-entry)
and [Access denied alerts](EXAMPLES.md#access-denied-alerts).

**Display:** a JS transformation that turns the JSON into one line is in
[EXAMPLES.md - Sitemap](EXAMPLES.md#sitemap).

**Persistence:** with `everyChange` every event is stored, because each one has its own timestamp. A Flux
query and the Grafana table setup are in
[EXAMPLES.md - Entry log dashboard](EXAMPLES.md#entry-log-dashboard-with-influxdb-and-grafana).

## Privacy

Entry logs and access events are personal data: names, places and times. They reach `openhab.log` (INFO),
`events.log` (item state changes) and any persistence you configure. Keep them only as long as you need them
and limit who can read the logs and the database. See the README, "Privacy and logging".

## Testing

1. Present a valid token at a door.
2. `grep "Entry log" /var/log/openhab/openhab.log | tail -1` shows the JSON.
3. `curl -s http://localhost:8080/rest/items/Front_Door_EntryLog/state` shows the item state.
4. Present a token that the door refuses for that user (outside its access level): `entryLog` stays
   unchanged, and `accessEvent` gets the refusal with its `eventType`.
5. Open the door from openHAB: neither channel changes (no user in the event).

## Troubleshooting

- **No entries at all:** check that the bridge logged "SignalR connected and subscribed successfully" and
  a "Subscribing to door events for door ID ..." line for the door.
- **Entries without `eventDescription`:** look for "Net2 event types loaded" or "could not be read" at start.
- **First and last name swapped:** the binding expects Net2's "Last First" order; check what your server sends
  (`log:set DEBUG org.openhab.binding.net2` shows the event payload, which includes token numbers - turn it off
  again afterwards).
