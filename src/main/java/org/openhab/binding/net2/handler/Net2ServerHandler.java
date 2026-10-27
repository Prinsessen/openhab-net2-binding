/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.net2.handler;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.net2.Net2BindingConstants;
import org.openhab.binding.net2.discovery.Net2DoorDiscoveryService;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link Net2ServerHandler} is responsible for handling communication
 * with the Paxton Net2 API server.
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class Net2ServerHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(Net2ServerHandler.class);

    private @Nullable ScheduledFuture<?> refreshJob;
    private @Nullable ScheduledFuture<?> signalRReconnectJob;
    private @Nullable Net2ApiClient apiClient;
    private @Nullable Net2SignalRClient signalRClient;
    private final Net2ActivityReportGenerator activityReportGenerator = new Net2ActivityReportGenerator();
    private int signalRReconnectAttempts = 0;
    private volatile Map<Integer, String> eventTypes = Collections.emptyMap();
    private volatile Map<Integer, String> eventSubTypes = Collections.emptyMap();
    private volatile boolean lockdownActive = false;
    /** How far back the events are read for their names when the eventTypes list fails: one week. */
    private static final int EVENT_NAME_HOURS = 168;
    private static final int SIGNALR_MAX_RECONNECT_DELAY_SECONDS = 300;
    private static final int SIGNALR_INITIAL_RECONNECT_DELAY_SECONDS = 30;

    public Net2ServerHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // REFRESH arrives for every linked channel at start-up and must not run a command: it ran
        // listUsers at every start, and that wrote every user with their PIN into openhab.log
        if (command instanceof RefreshType) {
            return;
        }
        logger.debug("handleCommand received for channel: {} with command: {}", channelUID.getId(), command);
        if (command == null) {
            logger.warn("Command is null");
            return;
        }
        if (!isOnline()) {
            logger.warn("Bridge handler is not online, ignoring command");
            return;
        }

        try {
            switch (channelUID.getId()) {
                case Net2BindingConstants.CHANNEL_CREATE_USER:
                    handleCreateUser(command);
                    break;
                case Net2BindingConstants.CHANNEL_DELETE_USER:
                    handleDeleteUser(command);
                    break;
                case Net2BindingConstants.CHANNEL_LIST_ACCESS_LEVELS:
                    handleListAccessLevels(command);
                    break;
                case Net2BindingConstants.CHANNEL_LIST_USERS:
                    handleListUsers(command);
                    break;
                case Net2BindingConstants.CHANNEL_LOCKDOWN:
                    handleLockdown(command);
                    break;
                default:
            }
        } catch (Exception e) {
            logger.error("Error handling command for channel {}", channelUID.getId(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Error: " + e.getMessage());
        }
    }

    @Override
    public void initialize() {
        logger.debug("Initializing Net2 Server handler");

        Net2ServerConfiguration config = getConfigAs(Net2ServerConfiguration.class);

        // Validate configuration
        if (config.hostname == null || config.hostname.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Hostname is required");
            return;
        }
        if (config.username == null || config.username.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Username is required");
            return;
        }
        if (config.password == null || config.password.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Password is required");
            return;
        }
        if (config.clientId == null || config.clientId.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Client ID is required");
            return;
        }

        // Set status to UNKNOWN during initialization
        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.CONFIGURATION_PENDING, "Connecting to Net2 server...");

        // Create API client and authenticate in background to avoid blocking openHAB startup
        scheduler.execute(() -> {
            try {
                apiClient = new Net2ApiClient(config);

                // Test authentication
                if (!apiClient.authenticate()) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Failed to authenticate with Net2 server");
                    return;
                }

                updateStatus(ThingStatus.ONLINE);

                Net2ApiClient client = apiClient;
                if (client != null) {
                    loadEventTypes(client);
                    startSignalR(client, config);
                }

                // Schedule periodic refresh (now 10 minutes with SignalR providing instant updates)
                int refreshInterval = config.refreshInterval > 0 ? config.refreshInterval : 600;
                refreshJob = scheduler.scheduleWithFixedDelay(this::refreshDoorStatus, 0, refreshInterval,
                        TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.error("Failed to initialize Net2 API client", e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Error initializing API: " + e.getMessage());
            }
        });
    }

    @Override
    public void dispose() {
        logger.debug("Disposing Net2 Server handler");

        if (refreshJob != null) {
            refreshJob.cancel(true);
            refreshJob = null;
        }

        cancelSignalRReconnect();

        if (apiClient != null) {
            apiClient.close();
        }

        if (signalRClient != null) {
            signalRClient.disconnect();
            signalRClient = null;
        }
    }

    /** Net2's names for its event types, for accessEvent and the lockout guard. Read once per start. */
    private void loadEventTypes(Net2ApiClient client) {
        try {
            String types = client.getEventTypeList("eventTypes");
            eventTypes = Net2EventTypes.parse(types);
            String subTypes = client.getEventTypeList("eventSubTypes");
            eventSubTypes = Net2EventTypes.parse(subTypes);
            if (eventTypes.isEmpty()) {
                // Some servers answer the lists with an error; the events carry their own names
                String events = client.getEvents(EVENT_NAME_HOURS);
                eventTypes = Net2EventTypes.fromEvents(events, false);
                if (eventSubTypes.isEmpty()) {
                    eventSubTypes = Net2EventTypes.fromEvents(events, true);
                }
            }
            if (eventTypes.isEmpty()) {
                logger.warn("Net2 event types could not be read ({}); events carry only their numbers",
                        Net2EventTypes.shape(types));
            } else {
                logger.info("Net2 event types loaded: {} types, {} sub types; {}", eventTypes.size(),
                        eventSubTypes.size(), new java.util.TreeMap<>(eventTypes));
            }
        } catch (Exception e) {
            logger.warn("Net2 event types could not be read: {}", e.getMessage());
        }
    }

    /** Net2's name for an event type, or null when unknown. */
    public @Nullable String describeEventType(int type) {
        return eventTypes.get(type);
    }

    /** Net2's name for an event sub type, or null when unknown. */
    public @Nullable String describeEventSubType(int subType) {
        return eventSubTypes.get(subType);
    }

    /**
     * True while a lockdown that openHAB switched on is active. A lockdown refuses tokens and PINs on
     * purpose, and a re-instate would lift it at the ACU, so the lockout guard stands down.
     */
    public boolean isLockdownActive() {
        return lockdownActive;
    }

    private void startSignalR(Net2ApiClient client, Net2ServerConfiguration config) {
        if (signalRClient != null && signalRClient.isConnected()) {
            return;
        }

        // Clean up previous client if any
        if (signalRClient != null) {
            try {
                signalRClient.disconnect();
            } catch (Exception e) {
                logger.debug("Error disconnecting old SignalR client", e);
            }
            signalRClient = null;
        }

        try {
            String token = client.getValidAccessToken();
            boolean verify = config.tlsVerification != null ? config.tlsVerification : true;
            Net2SignalRClient newClient = new Net2SignalRClient(client.getServerRootUri(), token, verify);
            newClient.setEventConsumer(this::handleSignalREvent);
            newClient.setOnConnectedCallback(this::onSignalRConnected);
            newClient.setOnDisconnectedCallback(this::onSignalRDisconnected);

            // Assign before connecting so callback can access it
            signalRClient = newClient;

            newClient.connect();

            // Verify connection actually succeeded before declaring victory
            if (!newClient.isConnected()) {
                logger.warn("SignalR connection failed (server may still be starting) — scheduling retry");
                scheduleSignalRReconnect(config);
                return;
            }

            newClient.subscribeToEvents();

            // Reset reconnect attempts on successful connection
            signalRReconnectAttempts = 0;
            cancelSignalRReconnect();
            logger.info("SignalR connected and subscribed successfully");
        } catch (Exception e) {
            logger.warn("SignalR startup failed — scheduling retry", e);
            scheduleSignalRReconnect(config);
        }
    }

    /**
     * Called when SignalR WebSocket disconnects (server restart, network outage, etc.).
     * Schedules an automatic reconnection with exponential backoff.
     */
    private void onSignalRDisconnected() {
        logger.warn("SignalR disconnected — scheduling automatic reconnection");
        Net2ServerConfiguration config = getConfigAs(Net2ServerConfiguration.class);
        scheduleSignalRReconnect(config);
    }

    /**
     * Schedule a SignalR reconnection attempt with exponential backoff.
     * Delay: 30s, 60s, 120s, 240s, 300s (capped at 5 minutes).
     */
    private void scheduleSignalRReconnect(Net2ServerConfiguration config) {
        cancelSignalRReconnect();

        int delay = Math.min(
                SIGNALR_INITIAL_RECONNECT_DELAY_SECONDS * (1 << signalRReconnectAttempts),
                SIGNALR_MAX_RECONNECT_DELAY_SECONDS);
        signalRReconnectAttempts++;

        logger.info("SignalR reconnect attempt {} scheduled in {} seconds", signalRReconnectAttempts, delay);

        signalRReconnectJob = scheduler.schedule(() -> {
            Net2ApiClient client = apiClient;
            if (client == null) {
                logger.debug("API client not initialized for SignalR reconnect, will retry");
                scheduleSignalRReconnect(config);
                return;
            }
            // Force re-authentication to get a fresh token (server restart invalidates old tokens)
            try {
                if (!client.authenticate()) {
                    logger.warn("Re-authentication failed during SignalR reconnect — scheduling retry");
                    scheduleSignalRReconnect(config);
                    return;
                }
            } catch (Exception e) {
                logger.warn("Re-authentication error during SignalR reconnect — scheduling retry", e);
                scheduleSignalRReconnect(config);
                return;
            }
            logger.info("Attempting SignalR reconnection (attempt {})", signalRReconnectAttempts);
            startSignalR(client, config);
        }, delay, TimeUnit.SECONDS);
    }

    private void cancelSignalRReconnect() {
        ScheduledFuture<?> job = signalRReconnectJob;
        if (job != null) {
            job.cancel(false);
            signalRReconnectJob = null;
        }
    }

    private void handleSignalREvent(String target, JsonObject payload) {
        // Support both deviceId and doorId (different event types use different field names)
        if (!payload.has("deviceId") && !payload.has("doorId")) {
            return;
        }

        int eventDoorId = payload.has("doorId") ? payload.get("doorId").getAsInt() : payload.get("deviceId").getAsInt();
        getThing().getThings().forEach(childThing -> {
            if (childThing.getHandler() instanceof Net2DoorHandler handler && handler.getDoorId() == eventDoorId) {
                handler.applyEvent(payload, target);
            }
        });
    }

    /**
     * Refresh door status for all child things
     */
    private void refreshDoorStatus() {
        logger.debug("refreshDoorStatus: Starting API poll");
        Net2ApiClient client = apiClient;
        if (client == null || !client.isAuthenticated()) {
            try {
                if (client == null) {
                    logger.error("API client not initialized");
                    return;
                }
                if (!client.authenticate()) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Failed to re-authenticate");
                    return;
                }
            } catch (Exception e) {
                logger.error("Authentication error during refresh", e);
                return;
            }
        }

        try {
            String statusResponse = client.getDoorStatus();
            logger.debug("refreshDoorStatus: Got API response: {}", statusResponse);

            // Safety net: check if SignalR is down and no reconnect is scheduled
            Net2SignalRClient sr = signalRClient;
            if ((sr == null || !sr.isConnected()) && signalRReconnectJob == null) {
                logger.info("SignalR found disconnected during API poll — triggering reconnect");
                Net2ServerConfiguration config = getConfigAs(Net2ServerConfiguration.class);
                startSignalR(client, config);
            }

            JsonElement element = JsonParser.parseString(statusResponse);

            if (element.isJsonArray()) {
                logger.debug("refreshDoorStatus: Parsed array with {} doors", element.getAsJsonArray().size());
                // Notify child handlers of status update
                getThing().getThings().forEach(childThing -> {
                    Net2DoorHandler handler = (Net2DoorHandler) childThing.getHandler();
                    if (handler != null) {
                        logger.debug("refreshDoorStatus: Calling updateFromApiResponse on handler");
                        handler.updateFromApiResponse(element.getAsJsonArray());
                    }
                });
            } else {
                logger.warn("refreshDoorStatus: Response is not a JSON array");
            }

            if (getThing().getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.ONLINE);
            }

            // Generate activity report HTML
            try {
                if (activityReportGenerator.generateReport(client)) {
                    logger.debug("Activity report updated successfully");
                    updateState(Net2BindingConstants.CHANNEL_ACTIVITY_REPORT, new StringType(java.time.LocalDateTime
                            .now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))));
                }
            } catch (Exception re) {
                logger.debug("Activity report generation failed", re);
            }
        } catch (Exception e) {
            logger.debug("Error refreshing door status", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Error refreshing: " + e.getMessage());
        }
    }

    /**
     * Get the API client for child door handlers
     */
    public Net2ServerConfiguration getServerConfiguration() {
        return getConfigAs(Net2ServerConfiguration.class);
    }

    public @Nullable Net2ApiClient getApiClient() {
        return apiClient;
    }

    /**
     * Get the SignalR client for child door handlers
     */
    public @Nullable Net2SignalRClient getSignalRClient() {
        return signalRClient;
    }

    /**
     * Called when SignalR connection is established, notifies all door handlers to subscribe
     */
    public void onSignalRConnected() {
        logger.debug("SignalR connected, notifying door handlers");
        getThing().getThings().forEach(childThing -> {
            if (childThing.getHandler() instanceof Net2DoorHandler doorHandler) {
                doorHandler.subscribeToSignalREvents();
            }
        });
    }

    /**
     * Check if bridge is online
     */
    public boolean isOnline() {
        Net2ApiClient client = apiClient;
        return getThing().getStatus() == ThingStatus.ONLINE && client != null && client.isAuthenticated();
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(Net2DoorDiscoveryService.class);
    }

    private void handleCreateUser(Command command) throws Exception {
        Net2ApiClient client = apiClient;
        if (client == null) {
            logger.error("API client not available");
            return;
        }

        if (command instanceof StringType) {
            String userData = command.toString();
            // Expected format: firstName,lastName,accessLevel,pin
            String[] parts = userData.split(",");
            if (parts.length >= 4) {
                String firstName = parts[0].trim();
                String lastName = parts[1].trim();
                String accessLevelStr = parts[2].trim();
                String pin = parts[3].trim();

                logger.info("Creating user: {} {}, accessLevel: {}, pin: {}", firstName, lastName, accessLevelStr,
                        pin.isEmpty() ? "none" : "set");

                // Resolve access level to an ID if possible (validate it exists in the system)
                Integer accessLevelId = null;
                try {
                    accessLevelId = client.resolveAccessLevelId(accessLevelStr);
                } catch (Exception ex) {
                    logger.warn("Unable to resolve access level '{}': {}", accessLevelStr, ex.getMessage());
                }
                if (accessLevelId == null) {
                    logger.warn(
                            "Access level '{}' not found among system access levels; proceeding without assignment.",
                            accessLevelStr);
                } else {
                    logger.info("Resolved access level '{}' to ID {}", accessLevelStr, accessLevelId);
                }

                int userId = client.addUser(firstName, lastName, "", pin, "");
                if (userId > 0) {
                    logger.info("User created successfully with ID: {}", userId);

                    // Assign the access level to the created user
                    if (accessLevelId == null) {
                        logger.warn("User {} created without assigning access level (no valid ID resolved)", userId);
                    } else {
                        try {
                            boolean assigned = client.assignAccessLevels(userId, accessLevelId);
                            if (assigned) {
                                logger.info("Access level {} assigned successfully to user {}", accessLevelId, userId);
                            } else {
                                logger.error("Failed to assign access level {} to user {}", accessLevelId, userId);
                            }
                        } catch (Exception ex) {
                            logger.error("Error assigning access level {} to user {}: {}", accessLevelId, userId,
                                    ex.getMessage());
                        }
                    }
                } else {
                    logger.error("Failed to create user");
                }
            } else {
                logger.error("Invalid user data format. Expected: firstName,lastName,accessLevel,pin");
            }
        }
    }

    private void handleDeleteUser(Command command) throws Exception {
        Net2ApiClient client = apiClient;
        if (client == null) {
            logger.error("API client not available");
            return;
        }

        if (command instanceof StringType) {
            String userIdentifier = command.toString();
            logger.info("Deleting user: {}", userIdentifier);
            if (client.deleteUser(userIdentifier)) {
                logger.info("User deleted successfully");
            } else {
                logger.error("Failed to delete user");
            }
        }
    }

    private void handleListAccessLevels(Command command) throws Exception {
        Net2ApiClient client = apiClient;
        if (client == null) {
            logger.error("API client not available");
            return;
        }
        Map<Integer, String> levels = client.listAccessLevels();
        if (levels.isEmpty()) {
            logger.warn("No access levels returned by API");
            return;
        }
        StringBuilder sb = new StringBuilder("Access levels: ");
        levels.forEach((id, name) -> sb.append("[" + id + ":" + name + "] "));
        logger.info(sb.toString());
    }

    private void handleListUsers(Command command) throws Exception {
        Net2ApiClient client = apiClient;
        if (client == null) {
            logger.error("API client not available");
            return;
        }
        String usersJson = client.listUsers();
        if (usersJson == null || usersJson.isEmpty()) {
            logger.warn("No users returned by API");
            return;
        }
        logger.debug("Users JSON payload ({} characters, holds PINs - not logged)", usersJson.length());
    }

    private void handleLockdown(Command command) throws Exception {
        Net2ApiClient client = apiClient;
        if (client == null) {
            logger.error("API client not available");
            return;
        }

        if (command instanceof org.openhab.core.library.types.OnOffType) {
            boolean enable = command == org.openhab.core.library.types.OnOffType.ON;
            logger.info("Lockdown command received: {}", enable ? "ENABLE" : "DISABLE");

            boolean success = client.controlLockdown(enable);
            if (success) {
                logger.info("Lockdown {} successfully", enable ? "enabled" : "disabled");
                lockdownActive = enable;
                // Update channel state to reflect current status
                updateState(Net2BindingConstants.CHANNEL_LOCKDOWN, (org.openhab.core.library.types.OnOffType) command);
            } else {
                logger.error("Failed to {} lockdown", enable ? "enable" : "disable");
            }
        } else {
            logger.warn("Invalid command type for lockdown channel: {}", command.getClass().getName());
        }
    }
}
