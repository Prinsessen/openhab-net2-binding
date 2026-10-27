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

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.net2.Net2BindingConstants;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The {@link Net2DoorHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class Net2DoorHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(Net2DoorHandler.class);

    private @Nullable Net2ServerHandler bridgeHandler;
    private int doorId;
    private final Net2LockoutGuard lockoutGuard = new Net2LockoutGuard();

    public Net2DoorHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        logger.debug("Initializing Net2 Door handler for door {}", getThing().getUID());

        // Get bridge handler
        var bridge = getBridge();
        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "No bridge configured");
            return;
        }
        var handler = bridge.getHandler();
        if (!(handler instanceof Net2ServerHandler)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Bridge handler not available");
            return;
        }
        bridgeHandler = (Net2ServerHandler) handler;
        if (bridgeHandler == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Bridge handler not available");
            return;
        }

        // Get configuration
        Net2DoorConfiguration config = getConfigAs(Net2DoorConfiguration.class);

        if (config.doorId == null || config.doorId <= 0) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Valid Door ID is required");
            return;
        }

        this.doorId = config.doorId;
        updateStatus(ThingStatus.ONLINE);
        publishLockoutState();

        // Subscribe to door-specific SignalR events
        Net2SignalRClient signalRClient = bridgeHandler.getSignalRClient();
        if (signalRClient != null && signalRClient.isConnected()) {
            signalRClient.subscribeToDoorEvents(doorId);
            logger.info("Subscribed to door events for door ID: {}", doorId);
        } else {
            logger.debug("SignalR client not available yet, will subscribe later");
        }
    }

    /**
     * Called by server handler when SignalR connection is established
     */
    public void subscribeToSignalREvents() {
        logger.info("subscribeToSignalREvents() called for door {}", doorId);
        Net2ServerHandler bridge = bridgeHandler;
        if (bridge == null) {
            logger.warn("Bridge is null, cannot subscribe for door {}", doorId);
            return;
        }

        Net2SignalRClient signalRClient = bridge.getSignalRClient();
        logger.info("SignalR client for door {}: {}, connected: {}", doorId,
                (signalRClient != null ? "exists" : "null"),
                (signalRClient != null ? signalRClient.isConnected() : "N/A"));

        if (signalRClient != null && signalRClient.isConnected()) {
            logger.info("Subscribing to door events for door ID: {}", doorId);
            signalRClient.subscribeToDoorEvents(doorId);
            logger.info("Door-specific subscription completed for door ID: {}", doorId);
        } else {
            logger.warn("SignalR client not available or not connected for door {}", doorId);
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Ignore REFRESH commands - they are sent during startup and must NOT trigger
        // physical door actions. This is critical for doors with momentary relays (e.g. garage)
        // where any command would toggle the door state.
        if (command instanceof RefreshType) {
            logger.debug("Ignoring REFRESH command for door {} channel {}", doorId, channelUID.getId());
            return;
        }

        Net2ServerHandler bridge = bridgeHandler;
        if (command == null || bridge == null || !bridge.isOnline()) {
            return;
        }

        try {
            switch (channelUID.getId()) {
                case Net2BindingConstants.CHANNEL_DOOR_ACTION:
                    handleDoorAction(command);
                    break;
                case Net2BindingConstants.CHANNEL_DOOR_CONTROL_TIMED:
                    handleDoorControlTimed(command);
                    break;
                case Net2BindingConstants.CHANNEL_REINSTATE:
                    handleReinstate(command);
                    break;
                default:
                    logger.debug("Unsupported channel: {}", channelUID.getId());
            }
        } catch (Exception e) {
            logger.error("Error handling command for channel {}", channelUID.getId(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Error: " + e.getMessage());
        }
    }

    private void handleDoorControlTimed(Command command) throws Exception {
        logger.debug("TEST LOG Net2DoorHandler: handleDoorControlTimed triggered with command: {}", command);
        Net2ServerHandler bridge = bridgeHandler;
        if (bridge == null) {
            logger.error("Bridge handler not available");
            return;
        }
        Net2ApiClient apiClient = bridge.getApiClient();
        if (apiClient == null) {
            logger.error("API client not available");
            return;
        }
        String payload;
        try {
            // If command is a JSON string, use it directly
            String cmdStr = command.toString();
            if (cmdStr.trim().startsWith("{")) {
                payload = cmdStr;
            } else {
                // Otherwise, interpret command as seconds and convert to milliseconds
                int openTimeSeconds = 1; // Default to 1 second
                try {
                    if (command instanceof DecimalType) {
                        openTimeSeconds = ((DecimalType) command).intValue();
                    } else {
                        openTimeSeconds = Integer.parseInt(cmdStr);
                    }
                } catch (NumberFormatException e) {
                    logger.warn("Invalid timed command value '{}', using default 1 second", cmdStr);
                }
                int openTimeMs = openTimeSeconds * 1000; // Convert seconds to milliseconds

                JsonObject relayFunction = new JsonObject();
                relayFunction.addProperty("RelayId", "Relay1");
                relayFunction.addProperty("RelayAction", "TimedOpen");
                relayFunction.addProperty("RelayOpenTime", openTimeMs);
                JsonObject body = new JsonObject();
                body.addProperty("DoorId", doorId);
                body.add("RelayFunction", relayFunction);
                body.addProperty("LedFlash", 3);
                payload = body.toString();
            }
        } catch (Exception e) {
            logger.error("Failed to build JSON payload for controlTimed: {}", e.getMessage());
            return;
        }
        logger.debug("Sending advanced door control payload: {}", payload);
        if (apiClient.controlDoorFireAndForget(payload)) {
            // Command was accepted - update state to reflect the action
            if (command instanceof State) {
                updateState(Net2BindingConstants.CHANNEL_DOOR_CONTROL_TIMED, (State) command);
            }
        } else {
            logger.error("Failed to trigger fire-and-forget control for door {}", doorId);
        }
    }

    /**
     * The guard's own state on the lockout channel, at start and on every poll: the guard lives in
     * memory, and a persistence service that restores items (mapdb by default) would otherwise bring
     * back an old ON that nothing turns off.
     */
    private void publishLockoutState() {
        updateState(Net2BindingConstants.CHANNEL_LOCKOUT, lockoutGuard.isLockedOut() ? OnOffType.ON : OnOffType.OFF);
    }

    private void applyLockoutResult(Net2LockoutGuard.Result result, String doorName) {
        switch (result) {
            case LOCKOUT:
                updateState(Net2BindingConstants.CHANNEL_LOCKOUT, OnOffType.ON);
                logger.warn("{}: known users are being refused - the ACU looks locked out (Re-Instate cures it)",
                        doorName);
                break;
            case REINSTATE:
                updateState(Net2BindingConstants.CHANNEL_LOCKOUT, OnOffType.ON);
                logger.warn("{}: known users are being refused - re-instating the ACU", doorName);
                try {
                    reinstate();
                } catch (Exception e) {
                    logger.error("Re-instate of the ACU of door {} failed", doorId, e);
                }
                break;
            case CLEARED:
                updateState(Net2BindingConstants.CHANNEL_LOCKOUT, OnOffType.OFF);
                logger.info("{}: access granted again - the lockout is over", doorName);
                break;
            default:
                break;
        }
    }

    private void reinstate() throws Exception {
        Net2ServerHandler bridge = bridgeHandler;
        Net2ApiClient apiClient = bridge != null ? bridge.getApiClient() : null;
        if (apiClient == null) {
            logger.error("API client not available");
            return;
        }
        if (apiClient.reinstate(doorId)) {
            updateState(Net2BindingConstants.CHANNEL_LAST_REINSTATE, new DateTimeType());
            logger.info("Re-instate sent to the ACU of door {}", doorId);
        } else {
            logger.error("Re-instate of the ACU of door {} was not accepted", doorId);
        }
    }

    private void handleReinstate(Command command) throws Exception {
        if (command != OnOffType.ON) {
            return;
        }
        reinstate();
        updateState(Net2BindingConstants.CHANNEL_REINSTATE, OnOffType.OFF);
    }

    private void handleDoorAction(Command command) throws Exception {
        Net2ServerHandler bridge = bridgeHandler;
        if (bridge == null) {
            logger.error("Bridge handler not available");
            return;
        }
        Net2ApiClient apiClient = bridge.getApiClient();
        if (apiClient == null) {
            logger.error("API client not available");
            return;
        }

        if (command == OnOffType.ON) {
            // Hold door open
            logger.debug("Opening door {}", doorId);
            if (apiClient.holdDoorOpen(doorId)) {
                updateState(Net2BindingConstants.CHANNEL_DOOR_ACTION, OnOffType.ON);
                updateState(Net2BindingConstants.CHANNEL_DOOR_STATUS, OnOffType.ON);
            } else {
                logger.error("Failed to open door {}", doorId);
            }
        } else if (command == OnOffType.OFF) {
            // Close door
            logger.debug("Closing door {}", doorId);
            if (apiClient.closeDoor(doorId)) {
                updateState(Net2BindingConstants.CHANNEL_DOOR_ACTION, OnOffType.OFF);
                updateState(Net2BindingConstants.CHANNEL_DOOR_STATUS, OnOffType.OFF);
            } else {
                logger.error("Failed to close door {}", doorId);
            }
        }
    }

    /** Net2 event types that let the user in: 20 = access permitted (token), 26 = access permitted (PIN). */
    static final Set<Integer> ENTRY_EVENT_TYPES = Set.of(20, 26);

    /**
     * True when a LiveEvent is an entry. An event without an eventType counts as an entry, as it did
     * before the type was read (older Net2 servers).
     */
    static boolean isEntry(JsonObject payload) {
        if (!payload.has("eventType") || payload.get("eventType").isJsonNull()) {
            return true;
        }
        return ENTRY_EVENT_TYPES.contains(payload.get("eventType").getAsInt());
    }

    /**
     * The JSON for the entryLog and accessEvent channels: firstName, lastName, doorName, timestamp,
     * doorId, eventType, eventSubType and, when Net2 lists them, eventDescription and
     * eventSubTypeDescription. The token number is left out: the user is already named.
     */
    static JsonObject buildAccessEvent(JsonObject payload, String doorName, int doorId, @Nullable String typeName,
            @Nullable String subTypeName) {
        String fullName = payload.get("userName").getAsString();
        String[] nameParts = fullName.split(" ", 2);
        String lastName = nameParts.length > 0 ? nameParts[0] : "";
        String firstName = nameParts.length > 1 ? nameParts[1] : "";
        String timestamp = payload.has("eventTime") && !payload.get("eventTime").isJsonNull()
                ? payload.get("eventTime").getAsString()
                : "";

        JsonObject event = new JsonObject();
        event.addProperty("firstName", firstName);
        event.addProperty("lastName", lastName);
        event.addProperty("doorName", doorName);
        event.addProperty("timestamp", timestamp);
        event.addProperty("doorId", doorId);
        if (payload.has("eventType") && !payload.get("eventType").isJsonNull()) {
            event.addProperty("eventType", payload.get("eventType").getAsInt());
        }
        if (payload.has("eventSubType") && !payload.get("eventSubType").isJsonNull()) {
            event.addProperty("eventSubType", payload.get("eventSubType").getAsInt());
        }
        if (typeName != null) {
            event.addProperty("eventDescription", typeName);
        }
        if (subTypeName != null) {
            event.addProperty("eventSubTypeDescription", subTypeName);
        }
        return event;
    }

    public int getDoorId() {
        return doorId;
    }

    public void applyEvent(JsonObject payload, String target) {
        // DEBUG: the payload carries token numbers
        logger.debug("Applying SignalR event: target={}, payload={}", target, payload);
        try {
            // Update last access time
            if (payload.has("eventTime") && !payload.get("eventTime").isJsonNull()) {
                updateState(Net2BindingConstants.CHANNEL_LAST_ACCESS_TIME,
                        new DateTimeType(payload.get("eventTime").getAsString()));
            }

            // Update last access user
            if (payload.has("userName") && !payload.get("userName").isJsonNull()) {
                updateState(Net2BindingConstants.CHANNEL_LAST_ACCESS_USER,
                        new StringType(payload.get("userName").getAsString()));
            }

            // Every LiveEvent that names a user goes to accessEvent with its event type, so a rule can
            // tell a refusal from an entry. Only the granted types become an entryLog entry: a refused
            // badge is not an entry, and Net2 can refuse every user at once until the ACU is re-instated
            // in the Net2 software (seen 2026-10-27 as eventType 112 for every token and PIN).
            if (("LiveEvents".equalsIgnoreCase(target) || "liveEvents".equalsIgnoreCase(target))
                    && payload.has("userName") && !payload.get("userName").isJsonNull()) {

                String label = getThing().getLabel();
                String doorName = label != null ? label : "Door " + doorId;
                Net2ServerHandler server = bridgeHandler;
                Integer type = payload.has("eventType") && !payload.get("eventType").isJsonNull()
                        ? payload.get("eventType").getAsInt()
                        : null;
                Integer subType = payload.has("eventSubType") && !payload.get("eventSubType").isJsonNull()
                        ? payload.get("eventSubType").getAsInt()
                        : null;
                String typeName = server != null && type != null ? server.describeEventType(type) : null;
                String subTypeName = server != null && subType != null ? server.describeEventSubType(subType) : null;
                JsonObject accessEvent = buildAccessEvent(payload, doorName, doorId, typeName, subTypeName);
                updateState(Net2BindingConstants.CHANNEL_ACCESS_EVENT, new StringType(accessEvent.toString()));

                if (isEntry(payload)) {
                    updateState(Net2BindingConstants.CHANNEL_ENTRY_LOG, new StringType(accessEvent.toString()));
                    logger.info("Entry log: {}", accessEvent.toString());
                    applyLockoutResult(lockoutGuard.onEntry(), doorName);
                } else {
                    logger.info("Access event (not an entry): {}", accessEvent.toString());
                    boolean lockdown = server != null && server.isLockdownActive();
                    if (Net2LockoutGuard.isRefusal(type) && !Net2LockoutGuard.countsTowardsLockout(type, typeName,
                            lockdown)) {
                        logger.info("{}: refusal during a lockdown ({}) - not counted as an ACU lockout", doorName,
                                typeName != null ? typeName : "lockdown on");
                    } else if (Net2LockoutGuard.countsTowardsLockout(type, typeName, lockdown)) {
                        Net2ServerConfiguration config = server != null ? server.getServerConfiguration()
                                : new Net2ServerConfiguration();
                        applyLockoutResult(lockoutGuard.onRefusal(Instant.now(), config.lockoutThreshold,
                                Duration.ofMinutes(config.lockoutWindow), config.autoReinstate,
                                Duration.ofMinutes(config.reinstateInterval)), doorName);
                    }
                }
            }

            // Generate access denied event for eventType 23 (Access Denied)
            if (("LiveEvents".equalsIgnoreCase(target) || "liveEvents".equalsIgnoreCase(target))
                    && payload.has("eventType") && !payload.get("eventType").isJsonNull()) {

                int eventType = payload.get("eventType").getAsInt();

                // eventType 23 = Access denied (unauthorized card/token)
                if (eventType == 23) {
                    String doorName = getThing().getLabel() != null ? getThing().getLabel() : "Door " + doorId;
                    String timestamp = payload.has("eventTime") ? payload.get("eventTime").getAsString() : "";
                    String tokenNumber = payload.has("tokenNumber")
                            ? String.valueOf(payload.get("tokenNumber").getAsLong())
                            : "unknown";

                    // Build JSON access denied log
                    JsonObject accessDenied = new JsonObject();
                    accessDenied.addProperty("tokenNumber", tokenNumber);
                    accessDenied.addProperty("doorName", doorName);
                    accessDenied.addProperty("timestamp", timestamp);
                    accessDenied.addProperty("doorId", doorId);

                    updateState(Net2BindingConstants.CHANNEL_ACCESS_DENIED, new StringType(accessDenied.toString()));
                    logger.warn("Access DENIED at door {}: Token {} at {}", doorName, tokenNumber, timestamp);
                }
            }

            // Handle DoorStatusEvents - These provide real-time door relay status via doorRelayOpen
            if ("DoorStatusEvents".equalsIgnoreCase(target) || "DoorStatusEvent".equalsIgnoreCase(target)) {
                if (payload.has("status") && payload.get("status").isJsonObject()) {
                    JsonObject statusObj = payload.getAsJsonObject("status");

                    // Check doorRelayOpen field - this indicates door open/closed state
                    if (statusObj.has("doorRelayOpen")) {
                        boolean doorRelayOpen = statusObj.get("doorRelayOpen").getAsBoolean();
                        OnOffType doorStatus = doorRelayOpen ? OnOffType.ON : OnOffType.OFF;

                        updateState(Net2BindingConstants.CHANNEL_DOOR_STATUS, doorStatus);
                        logger.info("Door {} status updated from doorRelayOpen to: {} (relay={})", doorId, doorStatus,
                                doorRelayOpen);
                    }

                    // Also check doorContactClosed if available (physical door sensor)
                    if (statusObj.has("doorContactClosed")) {
                        boolean doorContactClosed = statusObj.get("doorContactClosed").getAsBoolean();
                        logger.debug("Door {} doorContactClosed: {}", doorId, doorContactClosed);
                        // Note: doorContactClosed might not be available on all doors
                        // We prioritize doorRelayOpen as it's more reliably reported
                    }
                }
            }

            // Handle DoorEvents - Door open/closed events (legacy format if still used)
            else if ("DoorEvents".equalsIgnoreCase(target) || "DoorEvent".equalsIgnoreCase(target)) {
                // Check for locked field
                if (payload.has("locked")) {
                    boolean locked = payload.get("locked").getAsBoolean();
                    OnOffType doorStatus = locked ? OnOffType.OFF : OnOffType.ON;
                    updateState(Net2BindingConstants.CHANNEL_DOOR_STATUS, doorStatus);
                    logger.info("Door {} status updated from locked field to: {}", doorId, doorStatus);
                }
                // Check for state field (legacy)
                else if (payload.has("state") && !payload.get("state").isJsonNull()) {
                    String state = payload.get("state").getAsString().toLowerCase();
                    OnOffType doorStatus;

                    if ("open".equals(state) || "opened".equals(state)) {
                        doorStatus = OnOffType.ON;
                    } else if ("closed".equals(state)) {
                        doorStatus = OnOffType.OFF;
                    } else {
                        logger.debug("Unknown door event state: {}", state);
                        return;
                    }

                    updateState(Net2BindingConstants.CHANNEL_DOOR_STATUS, doorStatus);
                    logger.info("Door {} physical state changed to: {}", doorId, doorStatus);
                }
            }

            // Handle liveEvents - Generic access events
            else if ("liveEvents".equalsIgnoreCase(target) || "liveEvent".equalsIgnoreCase(target)) {
            }

            // Handle liveEvents - Access events with eventType-based state tracking
            else if ("LiveEvents".equalsIgnoreCase(target) || "liveEvents".equalsIgnoreCase(target)) {
                if (payload.has("eventType") && !payload.get("eventType").isJsonNull()) {
                    int eventType = payload.get("eventType").getAsInt();

                    // Log ALL eventTypes for debugging and identifying access denied codes
                    logger.warn("Door {} received eventType: {} - Full payload: {}", doorId, eventType, payload);

                    // eventType 28 = Door relay opened (timed)
                    // eventType 46 = Door forced/held open
                    // eventType 47 = Door closed/secured
                    // eventType 20 = Access granted
                    // eventType 21 = Access denied (to be confirmed by testing)

                    if (eventType == 47) {
                        // Door closed - update both channels to OFF
                        updateState(Net2BindingConstants.CHANNEL_DOOR_ACTION, OnOffType.OFF);
                        updateState(Net2BindingConstants.CHANNEL_DOOR_STATUS, OnOffType.OFF);
                        logger.info("Door {} closed (eventType 47)", doorId);
                    } else if (eventType == 28 || eventType == 46 || eventType == 20) {
                        // Door opened/accessed
                        // Update both channels to ON
                        updateState(Net2BindingConstants.CHANNEL_DOOR_STATUS, OnOffType.ON);
                        updateState(Net2BindingConstants.CHANNEL_DOOR_ACTION, OnOffType.ON);

                        // No timer - API polling will detect door close and set to OFF
                        // SignalR provides instant open detection, API polling handles close detection

                        logger.info("Door {} opened (eventType {})", doorId, eventType);
                    }
                }
            }

        } catch (Exception e) {
            logger.debug("Failed to apply SignalR event", e);
        }
    }

    /**
     * Update state from API response
     */
    public void updateFromApiResponse(JsonArray doorStatusArray) {
        publishLockoutState();
        try {
            for (int i = 0; i < doorStatusArray.size(); i++) {
                JsonObject doorStatus = doorStatusArray.get(i).getAsJsonObject();

                if (doorStatus.has("id") && doorStatus.get("id").getAsInt() == doorId) {
                    // Update door status from API poll
                    if (doorStatus.has("status") && doorStatus.get("status").isJsonObject()) {
                        JsonObject statusObj = doorStatus.get("status").getAsJsonObject();

                        // Check doorRelayOpen field - true means door is open
                        boolean doorRelayOpen = statusObj.has("doorRelayOpen")
                                && statusObj.get("doorRelayOpen").getAsBoolean();
                        OnOffType status = doorRelayOpen ? OnOffType.ON : OnOffType.OFF;

                        logger.info("updateFromApiResponse: Door {} doorRelayOpen={} -> {}", doorId, doorRelayOpen,
                                status);

                        // Update both door-action and door-status channels to sync with actual state
                        updateState(Net2BindingConstants.CHANNEL_DOOR_ACTION, status);
                        updateState(Net2BindingConstants.CHANNEL_DOOR_STATUS, status);
                    }

                    // Update last access user
                    if (doorStatus.has("lastAccessUser") && !doorStatus.get("lastAccessUser").isJsonNull()) {
                        updateState(Net2BindingConstants.CHANNEL_LAST_ACCESS_USER,
                                new StringType(doorStatus.get("lastAccessUser").getAsString()));
                    }

                    // Update last access time
                    if (doorStatus.has("lastAccessTime") && !doorStatus.get("lastAccessTime").isJsonNull()) {
                        updateState(Net2BindingConstants.CHANNEL_LAST_ACCESS_TIME,
                                new DateTimeType(doorStatus.get("lastAccessTime").getAsString()));
                    }

                    return;
                }
            }
        } catch (Exception e) {
            logger.debug("Error updating from API response", e);
        }
    }

    @Override
    public void bridgeStatusChanged(org.openhab.core.thing.ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            updateStatus(ThingStatus.ONLINE);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }
}
