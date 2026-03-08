# Paxton Net2 Access Control Binding

This binding provides integration with the Paxton Net2 Access Control system, enabling remote door control and real-time status monitoring through openHAB. It communicates with the Net2 Local API over secure HTTPS connections and receives live door events via SignalR 2.

## Hardware Compatibility

### ✅ Compatible Controllers

- **Paxton Net2 Plus ACU** ([Manual](https://www.paxton-access.com/wp-content/uploads/2019/03/ins-20006.pdf))
- **Paxton Net2 Classic ACU** (Discontinued - legacy support)

### ❌ Not Compatible

The following controllers lack Net2 Local API support:
- **Net2 Nano 1 Door Controller** ([Info](https://www.paxton-access.com/wp-content/uploads/2019/03/ins-30075.pdf))
- **Paxton Paxlock** ([Info](https://www.paxton-access.com/wp-content/uploads/2019/03/ins-20000.pdf))
- **Paxton Paxlock Pro** ([Info](https://www.paxton-access.com/wp-content/uploads/2022/05/ins-30229.pdf))

## Supported Things

### Bridge: `net2server`
Represents the Paxton Net2 API server connection.

**Configuration Parameters:**
- `hostname` (required) - Net2 server hostname or IP address
- `port` (optional, default: 8443) - HTTPS port
- `username` (required) - Net2 API operator username (format: "First Name Last Name")
- `password` (required) - Net2 API operator password
- `clientId` (required) - Net2 API license Client ID
- `tlsVerification` (optional, default: true) - Enable/disable TLS certificate verification
- `refreshInterval` (optional, default: 30) - Door status polling interval in seconds

### Thing: `door`
Represents a Paxton Net2 access control door.

**Configuration Parameters:**
- `doorId` (required) - Net2 door ID (typically a 7-digit number)
- `name` (optional) - User-friendly name for the door

## Channels

### Bridge Channels (User Management)
| Channel | Type | Access | Description |
|---------|------|--------|-------------|
| `createUser` | String | Write-only | Create a user: format `firstName,lastName,accessLevel,pin` (e.g., `John,Doe,3,1234`). Access level can be ID or name. |
| `deleteUser` | String | Write-only | Delete a user by ID (numeric). |
| `listAccessLevels` | String | Write-only | Send any command to log all available access levels in openHAB logs. |

### Door Channels
| Channel | Type | Access | Description |
|---------|------|--------|-------------|
| `status` | Switch | Read-only | Current door status (ON=Open/Unlocked, OFF=Closed/Locked) |
| `action` | Switch | Read-write | Control door: send ON to hold open, OFF to close |
| `lastAccessUser` | String | Read-only | Name of the last user who accessed the door |
| `lastAccessTime` | DateTime | Read-only | Timestamp of the last door access |
| `entryLog` | String | Read-only | JSON entry event with firstName, lastName, doorName, timestamp (physical badge access only) |

## Thing Discovery

The binding includes automatic discovery service to scan available doors on the Net2 server. Use the inbox to add discovered doors.

## Configuration Examples

### Text Configuration (.things file)

```
Bridge net2:net2server:myserver [
    hostname="net2.example.com",
    port=8443,
    username="API Operator",
    password="secure_password",
    clientId="your_client_id",
    tlsVerification=true,
    refreshInterval=30
] {
    Thing door front_door [doorId=6203980, name="Front Door"]
    Thing door back_door [doorId=6203981, name="Back Door"]
    Thing door garage [doorId=6203982, name="Garage Door"]
}
```

### Items Configuration

```
// Door control
Switch Front_Door_Lock "Front Door Lock" <lock> { channel="net2:door:myserver:front_door:action" }

// Door status monitoring
Switch Front_Door_Status "Front Door Status" <door> { channel="net2:door:myserver:front_door:status" }
String Front_Door_LastUser "Last Access [%s]" { channel="net2:door:myserver:front_door:lastAccessUser" }
DateTime Front_Door_LastTime "Last Time [%1$td.%1$tm.%1$tY %1$tH:%1$tM]" { channel="net2:door:myserver:front_door:lastAccessTime" }
String Front_Door_EntryLog "Entry Log [%s]" { channel="net2:door:myserver:front_door:entryLog" }

// User management (bridge channels)
String Net2_CreateUser "Create User" { channel="net2:net2server:myserver:createUser" }
String Net2_DeleteUser "Delete User" { channel="net2:net2server:myserver:deleteUser" }
String Net2_ListAccessLevels "List Access Levels" { channel="net2:net2server:myserver:listAccessLevels" }
```

### Automation Rules

```java
// Unlock door
rule "Unlock front door"
when
    Item Security_Request received command ON
then
    Front_Door_Lock.sendCommand(ON)
    logInfo("Security", "Front door unlocked")
end

// Create new access level user
rule "Add temporary user"
when
    Item Add_Temp_User received command ON
then
    // Format: firstName,lastName,accessLevel,pin
    Net2_CreateUser.sendCommand("Visitor,Smith,3,5678")
    logInfo("Access", "Visitor account created with access level 3")
end

// Log access events
rule "Log door access"
when
    Item Front_Door_LastUser changed
then
    logInfo("Access Log", "Door accessed by: " + Front_Door_LastUser.state.toString())
    sendNotification("admin@example.com", "Front door accessed by " + Front_Door_LastUser.state.toString())
end
```

## API Details

### Authentication
- Uses OAuth2 JWT tokens with 30-minute expiry
- Automatic token refresh via refresh token
- Credentials securely stored in openHAB configuration

### Endpoints
- **Authentication:** `POST /api/v1/authorization/tokens`
- **Door Status:** `GET /api/v1/doors/status`
- **Door List:** `GET /api/v1/doors`
- **Door Control:** `POST /api/v1/commands/door/holdopen`, `POST /api/v1/commands/door/close`
- **User Management:** `POST /api/v1/users`, `DELETE /api/v1/users/{id}`
- **Access Levels:** `GET /api/v1/accesslevels`
- **Door Permissions:** `GET /api/v1/users/{id}/doorpermissionset`, `PUT /api/v1/users/{id}/doorpermissionset`

## Requirements

- openHAB 5.0 or later
- Paxton Net2 6.6 SR5 or later
- Net2 Local API enabled and licensed
- Valid API operator credentials with appropriate permissions

## Security Considerations

1. **TLS Verification:** Always enable TLS verification in production (`tlsVerification=true`)
2. **Strong Credentials:** Use unique, strong passwords for API operators
3. **Network Security:** Restrict Net2 API access to trusted networks only
4. **Token Management:** Tokens are stored in memory and never persisted to disk
5. **API Access Control:** Implement role-based access control for door permissions in Net2

## Troubleshooting

### Bridge won't connect
- Verify hostname and port are correct and accessible
- Confirm username is in correct format: "First Name Last Name"
- Ensure API is enabled in Net2 Configuration Utility
- Check that API license is installed and valid
- Test connectivity: `curl -k https://your-net2-host:8443/api/v1/doors`

### Door commands not working
- Verify door ID matches Net2 system
- Confirm API operator has door control permissions
- Check bridge is ONLINE in openHAB UI
- Review openHAB logs for authentication errors

### Status not updating
- Verify refreshInterval is set to appropriate value
- Check network connectivity between openHAB and Net2 server
- Review system logs for API errors

### User creation fails
- Verify access level exists in your Net2 system
- Use `listAccessLevels` channel to see available levels
- Confirm API operator has user management permissions

## Building from Source

### Prerequisites
- Java Development Kit (JDK) 21 or later
- Apache Maven 3.6 or later
- Git

### Build Instructions

```bash
# Clone the openHAB add-ons repository
git clone https://github.com/openhab/openhab-addons.git
cd openhab-addons

# Build only the Net2 binding
mvn clean install -pl bundles/org.openhab.binding.net2 -DskipTests

# Output JAR
target/org.openhab.binding.net2-5.1.0.jar
```

### Installation

1. Copy the JAR to openHAB's addons directory:
   ```bash
   cp target/org.openhab.binding.net2-5.1.0.jar /opt/openhab/addons/
   ```

2. Restart openHAB:
   ```bash
   systemctl restart openhab
   ```

3. Verify binding is loaded:
   ```bash
   grep "net2" /var/log/openhab/openhab.log
   ```

## Changelog

### Version 5.1.0
- Initial release for openHAB 5.1
- Full door control and status monitoring
- User management with access level assignment
- Real-time SignalR event support
- Automatic token refresh
- Automatic door discovery

## Support

For issues or questions:
1. Check [openHAB Community](https://community.openhab.org/)
2. Review logs: `/var/log/openhab/openhab.log`
3. Contact Paxton support for Net2 API-related issues

## Contributing

Contributions are welcome! Please:
1. Fork the [GitHub repository](https://github.com/Prinsessen/openhab-net2-binding)
2. Create a feature branch
3. Follow openHAB code style guidelines
4. Include unit tests for new features
5. Submit a pull request with detailed description

## License

This binding is part of the openHAB project and is licensed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/).

## Professional Installation Services

Need a **certified Paxton hardware installer**?

**Agesen El-Teknik**  
Terndrupvej 81, 9460 Brovst, Denmark  
📞 +45 98 23 20 10 | 📧 Nanna@agesen.dk | 🌐 [www.agesen.dk](https://www.agesen.dk)

*25+ years of experience with Paxton access control systems.*

## Author

**Nanna Agesen** (@Prinsessen)
- Email: nanna@agesen.dk
- GitHub: https://github.com/Prinsessen
- Binding Repository: https://github.com/Prinsessen/openhab-net2-binding

## Maintainer Information

| Field | Value |
|-------|-------|
| Author | Nanna Agesen |
| Email | nanna@agesen.dk |
| GitHub | @Prinsessen |
| Repository | https://github.com/Prinsessen/openhab-net2-binding |
| License | EPL-2.0 |
| openHAB Minimum | 5.0.0 |
| Java Minimum | 21 |
