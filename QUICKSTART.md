# Net2 Binding Quick Start

From download to a working door in openHAB 5.2. Full reference: [README.md](README.md).

## 1. Check the prerequisites

- openHAB 5.2
- A Paxton Net2 server with the Local API licensed and enabled, and the API licence's client ID
- A Net2 operator account for openHAB ("First Name Last Name" and password)
- Net2 Plus or Net2 Classic ACUs. Net2 Nano, Paxlock and Paxlock Pro do not work with the Local API.
- The openHAB host can reach the Net2 server on the API port (default 8443).
- The Net2 server's certificate is trusted by the Java that runs openHAB. The REST calls always check it; the
  `tlsVerification` parameter only covers the SignalR connection. See README, Troubleshooting.

## 2. Install

1. Download `org.openhab.binding.net2-5.2.2.jar` from the
   [releases](https://github.com/Prinsessen/openhab-net2-binding/releases).
2. Copy it into the addons folder: `/usr/share/openhab/addons/` on an apt/deb installation,
   `$OPENHAB_HOME/addons/` otherwise. Remove an older Net2 JAR from that folder first.
3. openHAB loads it by itself; no restart. Check in the Karaf console (`openhab-cli console`):
   `bundle:list | grep -i net2` should show the bundle as Active.

To build the JAR yourself, see README, "Building from Source".

## 3. Add the bridge and the doors

Either in the Main UI (Settings -> Things -> + -> Paxton Net2 Binding -> Net2 Server; the doors then appear in
the Inbox), or in a file:

**things/net2.things**

```java
Bridge net2:net2server:net2 "Net2 Server" [ hostname="net2.example.com", port=8443, username="John Doe",
        password="secret", clientId="00000000-0000-0000-0000-000000000000" ] {
    Thing door frontdoor "Front Door" [ doorId=1234567 ]
}
```

- `doorId` is the Net2 door ID (on a Net2 Plus, the ACU serial number). Discovery shows it.
- The thing label ("Front Door") names the door in every event.
- Defaults: door status poll every 30 seconds, automatic Re-Instate off.

## 4. Add items

**items/net2.items**

```java
Switch   Front_Door_Action     "Front Door"                 <lock> { channel="net2:door:net2:frontdoor:action" }
Switch   Front_Door_Status     "Front Door relay [%s]"      <door> { channel="net2:door:net2:frontdoor:status" }
Number   Front_Door_Timed      "Front Door timed open"      <lock> { channel="net2:door:net2:frontdoor:controlTimed" }
String   Front_Door_LastUser   "Last user [%s]"                    { channel="net2:door:net2:frontdoor:lastAccessUser" }
DateTime Front_Door_LastTime   "Last event [%1$tF %1$tT]"          { channel="net2:door:net2:frontdoor:lastAccessTime" }
String   Front_Door_EntryLog   "Entry log [%s]"                    { channel="net2:door:net2:frontdoor:entryLog" }
String   Front_Door_AccessEvent "Access event [%s]"                { channel="net2:door:net2:frontdoor:accessEvent" }
Switch   Front_Door_Lockout    "Lockout [%s]"                      { channel="net2:door:net2:frontdoor:lockout" }
Switch   Front_Door_Reinstate  "Re-instate ACU"                    { channel="net2:door:net2:frontdoor:reinstate" }
```

## 5. Check that it works

- The bridge goes ONLINE; `openhab.log` shows "SignalR connected and subscribed successfully" and the event type
  table ("Net2 event types loaded: ...").
- Present a valid token: `Front_Door_Status` turns ON while the relay is released, `Front_Door_EntryLog` gets
  a JSON entry.
- Send `5` to `Front_Door_Timed`: the door opens for 5 seconds.

```bash
curl -X POST "http://localhost:8080/rest/items/Front_Door_Timed" -H "Content-Type: text/plain" -d "5"
```

On a door with a momentary relay (a garage door), every command toggles the door. Test with care.

## 6. Next steps

- Lockout detection and Re-Instate: README, "ACU lockout and Re-Instate"
- Rules, alerts, lockdown, Grafana: [EXAMPLES.md](EXAMPLES.md)
- Entry log details: [ENTRY_LOGGING.md](ENTRY_LOGGING.md)
- What the binding logs: README, "Privacy and logging"

## Troubleshooting

| Symptom | Check |
|---------|-------|
| Binding not listed | JAR in the right addons folder; `bundle:list` in the console; `openhab.log` for errors |
| Bridge OFFLINE "Failed to authenticate" | operator name, password, client ID; Local API enabled and licensed |
| Bridge OFFLINE with a certificate error | the Net2 certificate is not trusted by openHAB's Java (README, Troubleshooting) |
| Door commands do nothing | `doorId`; the operator's rights; the bridge must be ONLINE |
| No live updates | "SignalR ..." lines in `openhab.log`; without SignalR the state still follows the 30 s poll |

More detail in the log: `log:set DEBUG org.openhab.binding.net2` in the console, back with
`log:set DEFAULT org.openhab.binding.net2`. DEBUG output contains token numbers and, in one SignalR mode, the
access token; do not post it unedited.

Professional installation: see README, "Professional Installation Services".
