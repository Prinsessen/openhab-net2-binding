# Contributing to Paxton Net2 Binding

Thank you for your interest in contributing to the Paxton Net2 binding for openHAB!

## Getting Started

### Prerequisites
- Java Development Kit (JDK) 21 or later
- Apache Maven 3.6 or later
- Git

### Setting Up Development Environment

1. Fork the repository: https://github.com/Prinsessen/openhab-net2-binding
2. Clone your fork:
   ```bash
   git clone https://github.com/YOUR_USERNAME/openhab-net2-binding.git
   cd openhab-net2-binding
   ```
3. Add upstream remote:
   ```bash
   git remote add upstream https://github.com/Prinsessen/openhab-net2-binding.git
   ```

### Building

```bash
# Build the binding
mvn clean install

# Build without running tests (faster)
mvn clean install -DskipTests

# Build with code quality checks
mvn clean verify
```

## Development Guidelines

### Code Style
- Follow openHAB code style guidelines
- Use 4-space indentation (no tabs)
- Maximum line length: 120 characters
- Use meaningful variable and method names

### Java Standards
- Minimum Java version: 21
- Use appropriate annotations (`@NonNullByDefault`, `@Nullable`, etc.)
- Include Javadoc for public methods and classes
- Write descriptive commit messages

### Testing
- Write unit tests for new features
- Ensure tests pass: `mvn test`
- Aim for at least 70% code coverage
- Test both success and failure scenarios

### Git Workflow

1. Create a feature branch:
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. Make your changes and commit:
   ```bash
   git commit -m "feat: Brief description of changes"
   ```

3. Push to your fork:
   ```bash
   git push origin feature/your-feature-name
   ```

4. Create a Pull Request on GitHub

### Commit Message Format
Follow conventional commits:
- `feat:` for new features
- `fix:` for bug fixes
- `docs:` for documentation
- `test:` for test changes
- `refactor:` for code refactoring
- `chore:` for build/dependency changes

Example: `feat: add access level caching`

## Pull Request Process

1. **Title & Description:**
   - Use clear, descriptive title
   - Explain what problem it solves
   - Reference any related issues

2. **Code Quality:**
   - Run `mvn verify` to check style and tests
   - Ensure code follows openHAB guidelines
   - Update documentation if needed

3. **Testing:**
   - Include unit tests for new code
   - Test the feature manually in openHAB
   - Include test steps in PR description

4. **Documentation:**
   - Update README.md if adding features
   - Document API changes
   - Update changelog

## Reporting Issues

### Bug Reports
Include:
- openHAB version
- Binding version
- Paxton Net2 version
- Step-by-step reproduction
- Expected vs actual behavior
- Relevant logs (with sensitive info removed)

### Feature Requests
Include:
- Clear description of feature
- Use case/benefit
- Proposed implementation (if applicable)
- Any potential breaking changes

## Code Structure

```
src/main/java/org/openhab/binding/net2/
├── Net2BindingConstants.java      # Binding constants
├── discovery/
│   └── Net2DoorDiscoveryService.java  # Auto-discovery
├── handler/
│   ├── Net2HandlerFactory.java        # Thing handler factory
│   ├── Net2ServerHandler.java         # Bridge handler
│   ├── Net2ServerConfiguration.java   # Bridge config
│   ├── Net2DoorHandler.java          # Door thing handler
│   ├── Net2DoorConfiguration.java    # Door config
│   ├── Net2ApiClient.java            # Net2 API communication
│   └── Net2SignalRClient.java        # SignalR events
└── internal/
    └── Net2Utils.java                # Utility functions

src/main/resources/
├── OH-INF/
│   ├── binding/
│   │   └── binding.xml              # Binding metadata
│   └── thing/
│       └── thing-types.xml          # Thing types definition
└── OSGI-INF/
    └── *.xml                        # OSGi service registrations
```

## API Documentation

### Net2ApiClient Methods

#### Authentication
- `authenticate()` - Authenticate with Net2 API
- `isAuthenticated()` - Check authentication status
- `getAccessToken()` - Get current JWT token

#### Door Operations
- `getDoorStatus()` - Get status of all doors
- `listDoors()` - List all available doors
- `holdDoorOpen(doorId)` - Hold a door open
- `closeDoor(doorId)` - Close a door

#### User Management
- `addUser(firstName, lastName, middleName, pin, expireTime)` - Create user
- `deleteUser(userIdentifier)` - Delete user
- `listAccessLevels()` - Get available access levels
- `resolveAccessLevelId(String)` - Validate access level

#### Permissions
- `getUserDoorPermissionSet(userId)` - Get user permissions
- `replaceUserDoorPermissionSet(userId, json)` - Update permissions
- `assignAccessLevels(userId, accessLevelIds)` - Assign access levels

## Code Review Process

All PRs require:
1. Code review by maintainer
2. All tests passing
3. No style violations
4. Appropriate documentation

## Documentation Standards

### Code Documentation
```java
/**
 * Brief description of what method does.
 *
 * @param param1 description of param1
 * @param param2 description of param2
 * @return description of return value
 * @throws ExceptionType when this exception is thrown
 */
public ReturnType methodName(Type1 param1, Type2 param2) {
    // implementation
}
```

### README Updates
- Keep examples current and working
- Document all channels and configuration
- Include troubleshooting section
- Maintain changelog

## Release Process

1. Update version in pom.xml
2. Update CHANGELOG.md
3. Commit and tag release
4. Build final artifact
5. Create GitHub release

## Questions?

- Create an issue on GitHub
- Contact maintainer: nanna@agesen.dk
- Check existing issues for answers

## Code of Conduct

This project adheres to the openHAB Community Code of Conduct. Be respectful and constructive in all interactions.

## License

By contributing, you agree that your contributions will be licensed under the Eclipse Public License 2.0.

---

**Thank you for contributing to openHAB!**
