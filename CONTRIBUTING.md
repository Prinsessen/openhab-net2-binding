# Contributing to the Paxton Net2 Binding

Thank you for your interest in improving the Net2 binding for openHAB.

## Getting Started

### Prerequisites
- JDK 21
- Apache Maven 3.9 (or the `mvnw` wrapper of openhab-addons)
- Git
- An openhab-addons checkout for openHAB 5.2: the binding builds only inside the add-ons reactor

### Setting up

1. Fork https://github.com/Prinsessen/openhab-net2-binding
2. Clone your fork into the `bundles/` folder of an openhab-addons checkout:
   ```bash
   git clone https://github.com/openhab/openhab-addons.git
   git clone https://github.com/YOUR_USERNAME/openhab-net2-binding.git \
       openhab-addons/bundles/org.openhab.binding.net2
   cd openhab-addons/bundles/org.openhab.binding.net2
   git remote add upstream https://github.com/Prinsessen/openhab-net2-binding.git
   ```
3. Register the module and build, as described in [DEVELOPMENT.md](DEVELOPMENT.md#building):
   ```bash
   cd ../..        # the openhab-addons root
   mvn -pl bundles/org.openhab.binding.net2 clean install
   ```

## Development Guidelines

### Code style
- openHAB's code style; run `mvn -pl bundles/org.openhab.binding.net2 spotless:apply` before committing
- 4-space indentation, no tabs; lines up to 120 characters
- `@NonNullByDefault` on classes, `@Nullable` where a value may be null
- Javadoc on public classes and methods

### Tests
- New behaviour and every fix come with a JUnit test in `src/test/java/org/openhab/binding/net2/handler/`.
- A fix's test should fail on the code before the fix.
- Run them with `mvn -pl bundles/org.openhab.binding.net2 test`.
- Test against a real Net2 server where the change touches the API, SignalR, Re-Instate or the lockdown, and
  say in the pull request what you tested and on which Net2 version.

### Privacy
- Never log PINs, passwords or tokens at INFO or above. Token numbers and user lists do not belong in INFO
  either.
- Use made-up data in tests, examples and issues: John Doe, Front Door, doorId 1234567.

### Git workflow

1. Create a branch: `git checkout -b feature/your-feature-name`
2. Commit: `git commit -m "feat: brief description"`
3. Push: `git push origin feature/your-feature-name`
4. Open a pull request on GitHub

### Commit messages
Conventional commits:
- `feat:` new feature
- `fix:` bug fix
- `docs:` documentation
- `test:` tests
- `refactor:` restructuring without a change in behaviour
- `chore:` build and housekeeping

## Pull Request Process

1. A clear title, what the change solves, and any related issue.
2. Build and tests pass in the reactor; the code is formatted.
3. Documentation updated: README.md (channels, parameters, behaviour) and CHANGELOG.md.
4. The steps you used to test it.

## Reporting Issues

### Bug reports
Include:
- openHAB version, binding version, Net2 version
- Steps to reproduce; expected and actual behaviour
- Log lines, with names, token numbers, hostnames and tokens removed

### Feature requests
Include the use case, the proposed behaviour, and any change it would make to existing channels.

## Code Structure

```
src/main/java/org/openhab/binding/net2/
├── Net2BindingConstants.java              binding, thing and channel IDs
├── discovery/
│   └── Net2DoorDiscoveryService.java      door discovery
└── handler/
    ├── Net2HandlerFactory.java            thing handler factory
    ├── Net2ServerHandler.java             bridge handler
    ├── Net2ServerConfiguration.java       bridge parameters
    ├── Net2DoorHandler.java               door handler
    ├── Net2DoorConfiguration.java         door parameters
    ├── Net2ApiClient.java                 Net2 REST API and tokens
    ├── Net2SignalRClient.java             SignalR live events
    ├── Net2LockoutGuard.java              ACU lockout detection
    ├── Net2EventTypes.java                Net2 event type names
    └── Net2ActivityReportGenerator.java   HTML activity report

src/main/resources/
├── OH-INF/addon/addon.xml                 add-on metadata
├── OH-INF/thing/thing-types.xml           things, channels, parameters
└── OSGI-INF/                              OSGi component registration

src/test/java/org/openhab/binding/net2/handler/   JUnit tests
```

## Net2ApiClient Methods

### Authentication
- `authenticate()` - log in (password grant with the client ID)
- `isAuthenticated()` - a token is held and not yet expired
- `getValidAccessToken()` - the access token, refreshed first if it expires within 5 minutes

### Doors
- `getDoorStatus()` - `GET /doors/status`
- `listDoors()` - `GET /doors`
- `holdDoorOpen(doorId)` - `POST /commands/door/holdopen`
- `closeDoor(doorId)` - `POST /commands/door/close`
- `controlDoorFireAndForget(payload)` - `POST /commands/door/control` (timed open)
- `reinstate(doorId)` - `POST /commands/door/reinstate`
- `controlLockdown(enable)` - `POST /commands/controlLockdown`

### Users and access levels
- `addUser(firstName, lastName, middleName, pin, expireTime)` - create a user, returns the ID or -1
- `deleteUser(userIdentifier)` - `DELETE /users/{id}`
- `listUsers()` - `GET /users` (raw JSON)
- `listAccessLevels()` - `GET /accesslevels` as ID-to-name map
- `resolveAccessLevelId(input)` - an access level ID from an ID or a name
- `addUserToken(userId, tokenNumber, tokenType)` - add a token to a user (not used by any channel)

### Permissions
- `getUserDoorPermissionSet(userId)` - not used by any channel
- `replaceUserDoorPermissionSet(userId, json)`
- `assignAccessLevels(userId, accessLevelIds...)`

### Events
- `getEvents(hours)` - `GET /events` for the last hours (up to 1000)
- `getEventTypeList(path)` - `GET /events/eventTypes` or `/events/eventSubTypes`

## Release Process

The release version is not in `pom.xml` (which keeps the reactor's parent version). It lives in:

1. CHANGELOG.md - a new `## [x.y.z] - date` section
2. the git tag - `vx.y.z`
3. the release asset name - `org.openhab.binding.net2-x.y.z.jar`

Steps: update the changelog and docs, build and test, rename the JAR, tag and push the tag, create the GitHub
release with the JAR attached, check the attached asset's checksum, update the download link in
MARKETPLACE_POST.md. See [DEVELOPMENT.md](DEVELOPMENT.md#releasing).

## Questions

Open an issue on GitHub or ask on the openHAB community forum. Maintainer: Nanna Agesen (GitHub: Prinsessen).

## Code of Conduct

This project follows the openHAB community's code of conduct. Be respectful and constructive.

## License

By contributing, you agree that your contributions are licensed under the Eclipse Public License 2.0
([LICENSE](LICENSE)).
