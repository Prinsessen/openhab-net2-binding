# Net2 Binding - Build and Development Guide

## Prerequisites

- JDK 21 ([Temurin](https://adoptium.net/temurin/) or the distribution's OpenJDK 21)
- Apache Maven 3.9 or the `mvnw` wrapper of the openhab-addons checkout
- Git
- An openhab-addons checkout for openHAB 5.2 (see below)

## Why the binding needs the add-ons reactor

`pom.xml` has only a parent and an artifact ID:

```xml
<parent>
  <groupId>org.openhab.addons.bundles</groupId>
  <artifactId>org.openhab.addons.reactor.bundles</artifactId>
  <version>5.2.0-SNAPSHOT</version>
</parent>
<artifactId>org.openhab.binding.net2</artifactId>
```

All dependencies, plugins and checks come from the openhab-addons reactor. A `mvn clean install` in this
repository on its own fails, because that parent cannot be resolved. Build inside `bundles/` of an
openhab-addons checkout.

## Building

```bash
git clone https://github.com/openhab/openhab-addons.git
cd openhab-addons
# check out a 5.2 version (a 5.2.x branch or tag) for a JAR that runs on openHAB 5.2
git clone https://github.com/Prinsessen/openhab-net2-binding.git bundles/org.openhab.binding.net2
```

Then:

1. If the reactor's version (`<version>` in `openhab-addons/pom.xml`) is not `5.2.0-SNAPSHOT`, set
   `<parent><version>` in `bundles/org.openhab.binding.net2/pom.xml` to it. Do not commit that change.
2. Add `<module>org.openhab.binding.net2</module>` to the `<modules>` list in `bundles/pom.xml`.
3. Build from the checkout's root:

```bash
mvn -pl bundles/org.openhab.binding.net2 clean install                 # with tests and checks
mvn -pl bundles/org.openhab.binding.net2 clean install -DskipChecks    # without the static analysis
mvn -pl bundles/org.openhab.binding.net2 spotless:apply                # format the code the openHAB way
```

The JAR is `bundles/org.openhab.binding.net2/target/org.openhab.binding.net2-<reactor version>.jar`.

A practical layout is to keep the git clone somewhere else and copy `src/` and `pom.xml` into
`bundles/org.openhab.binding.net2/` before each build, so the add-ons folder is a build directory only.

## Tests

JUnit 5 tests in `src/test/java/org/openhab/binding/net2/handler/`:

| Test | What it holds |
|------|---------------|
| `Net2DoorHandlerEventTest` | which events are entries (20, 26, no type) and which are not (refusals, the PIN step 16/17 sub type 82); the event JSON carries the type and names and no token number |
| `Net2LockoutGuardTest` | the lockout guard: threshold, window, an entry between refusals, re-instate interval, automatic re-instate on and off, the PIN step cleared by the PIN, no lockout during a lockdown or for events Net2 names a lockdown refusal |
| `Net2EventTypesTest` | reading Net2's event type lists in the shapes the API may answer, and learning names from the events themselves |
| `Net2UserListTest` | every PIN field removed from the user list, at any depth; non-JSON gives an empty list |

```bash
mvn -pl bundles/org.openhab.binding.net2 test
mvn -pl bundles/org.openhab.binding.net2 test -Dtest=Net2LockoutGuardTest
```

The tests cover the pure logic. Behaviour against a real Net2 server (SignalR, Re-Instate, lockdown) has to be
checked on a server; read the binding's log lines after a change.

## Project Structure

```
openhab-net2-binding/
├── pom.xml
├── LICENSE
├── README.md, QUICKSTART.md, EXAMPLES.md, ENTRY_LOGGING.md,
│   DEVELOPMENT.md, CONTRIBUTING.md, CHANGELOG.md, MARKETPLACE_POST.md
└── src/
    ├── main/
    │   ├── feature/feature.xml
    │   ├── history/dependencies.xml
    │   ├── java/org/openhab/binding/net2/
    │   │   ├── Net2BindingConstants.java
    │   │   ├── discovery/
    │   │   │   └── Net2DoorDiscoveryService.java      door discovery from GET /doors
    │   │   └── handler/
    │   │       ├── Net2HandlerFactory.java
    │   │       ├── Net2ServerHandler.java             bridge: login, poll, SignalR, user channels, lockdown, event names
    │   │       ├── Net2ServerConfiguration.java
    │   │       ├── Net2DoorHandler.java               door: commands, events, entry filter, lockout guard use
    │   │       ├── Net2DoorConfiguration.java
    │   │       ├── Net2ApiClient.java                 REST calls and token handling
    │   │       ├── Net2SignalRClient.java             ASP.NET Core / classic SignalR client
    │   │       ├── Net2LockoutGuard.java              ACU lockout detection (one per door)
    │   │       ├── Net2EventTypes.java                Net2's event type names
    │   │       └── Net2ActivityReportGenerator.java   HTML activity report
    │   └── resources/
    │       ├── feature.xml
    │       ├── OH-INF/addon/addon.xml
    │       ├── OH-INF/thing/thing-types.xml
    │       └── OSGI-INF/org.openhab.binding.net2.handler.Net2HandlerFactory.xml
    └── test/java/org/openhab/binding/net2/handler/
        ├── Net2DoorHandlerEventTest.java
        ├── Net2EventTypesTest.java
        ├── Net2LockoutGuardTest.java
        └── Net2UserListTest.java
```

## Deploying a test build

Copy the JAR into the addons folder of a test openHAB (`/usr/share/openhab/addons/` on apt installs,
`$OPENHAB_HOME/addons/` otherwise) after removing the previous Net2 JAR. openHAB installs it without a restart.
Check with `bundle:list | grep -i net2` in the Karaf console.

## Logging

openHAB logs through log4j2. For a session, in the Karaf console (`openhab-cli console`):

```
log:set DEBUG org.openhab.binding.net2
log:tail org.openhab.binding.net2
log:set DEFAULT org.openhab.binding.net2
```

To make it permanent, add a logger for `org.openhab.binding.net2` to `log4j2.xml` in the userdata `etc` folder
(`/var/lib/openhab/etc/log4j2.xml` on apt installs). DEBUG output contains token numbers and, in ASP.NET Core
SignalR mode, the access token in the WebSocket URI; see README, "Privacy and logging". Do not paste it
unedited into an issue.

## Debugging

`start_debug.sh` in the openHAB runtime folder (`/usr/share/openhab/` on apt installs) starts openHAB with a
Java debug port (5005). Attach your IDE as a remote JVM debugger.

## Common Tasks

### Adding a channel

1. `thing-types.xml`: add `<channel id="..." typeId="..."/>` to the thing or bridge, and a `<channel-type>`.
2. `Net2BindingConstants.java`: add the channel ID constant.
3. The handler: handle the command in `handleCommand()` (remember that REFRESH is ignored at the top) or update
   the state with `updateState()`.
4. Document it in README.md (channel table) and CHANGELOG.md.

Existing things defined in `.things` files pick up new channels when the thing is recreated; things created in
the UI may need to be recreated to show them.

### Adding a configuration parameter

1. `thing-types.xml`: add the parameter with its type and `<default>`.
2. Add the field, with the same default, to `Net2ServerConfiguration` or `Net2DoorConfiguration`.
3. Read it with `getConfigAs(...)`.
4. Document it in the README parameter table.

### Bridge user channels

- `createUser` parses `firstName,lastName,accessLevel,pin`, resolves the access level with
  `Net2ApiClient.resolveAccessLevelId()`, calls `addUser()` and then `assignAccessLevels()`.
- `deleteUser` calls `deleteUser(id)`.
- `listAccessLevels` calls `listAccessLevels()` and logs the result.
- `listUsers` calls `listUsers()` and puts the result, without PIN fields (`Net2ServerHandler.withoutPins()`),
  on the channel.

## Releasing

The version of a release lives in the git tag (`v5.2.2`), in CHANGELOG.md and in the name of the release asset.
`pom.xml` keeps the reactor's parent version; the JAR built by the reactor is named after it.

1. Update CHANGELOG.md and the documentation.
2. Build and test in the reactor.
3. Copy the JAR to `org.openhab.binding.net2-<version>.jar`.
4. Tag (`git tag v<version>`), push the tag, create the GitHub release and attach the renamed JAR.
5. Check that the attached asset is the one you built (compare a checksum), and update the download link in
   MARKETPLACE_POST.md.

## Resources

- openHAB developer documentation: https://www.openhab.org/docs/developer/
- openHAB community: https://community.openhab.org/
- Net2 Local API documentation: on your Net2 server (the API help pages), or from Paxton
