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
package org.openhab.binding.net2;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link Net2BindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author OpenHAB Community - Initial contribution
 */
@NonNullByDefault
public class Net2BindingConstants {

    public static final String BINDING_ID = "net2";

    // List of all Bridge Thing Type UIDs
    public static final String THING_TYPE_NET2SERVER = "net2server";

    // List of all Thing Type UIDs
    public static final String THING_TYPE_DOOR = "door";

    // List of all Channel ids
    public static final String CHANNEL_DOOR_STATUS = "status";
    public static final String CHANNEL_DOOR_ACTION = "action";
    public static final String CHANNEL_LAST_ACCESS_USER = "lastAccessUser";
    public static final String CHANNEL_LAST_ACCESS_TIME = "lastAccessTime";
    public static final String CHANNEL_ENTRY_LOG = "entryLog";
    public static final String CHANNEL_ACCESS_DENIED = "accessDenied";
    public static final String CHANNEL_ACCESS_EVENT = "accessEvent";
    public static final String CHANNEL_REINSTATE = "reinstate";
    public static final String CHANNEL_LOCKOUT = "lockout";
    public static final String CHANNEL_LAST_REINSTATE = "lastReinstate";

    // User management channels
    public static final String CHANNEL_CREATE_USER = "createUser";
    public static final String CHANNEL_DELETE_USER = "deleteUser";
    public static final String CHANNEL_LIST_ACCESS_LEVELS = "listAccessLevels";
    public static final String CHANNEL_LIST_USERS = "listUsers";

    // Security channels
    public static final String CHANNEL_LOCKDOWN = "lockdown";

    // Activity report channel (on bridge)
    public static final String CHANNEL_ACTIVITY_REPORT = "activityReport";

    public static final String CHANNEL_DOOR_CONTROL_TIMED = "controlTimed";
    // SignalR hub
    public static final String SIGNALR_HUB_PATH = "/eventHubLocal";

    // API Configuration
    public static final String API_VERSION = "v1";
    public static final int DEFAULT_PORT = 8443;
    public static final int DEFAULT_REFRESH_INTERVAL = 30;
    public static final int TOKEN_EXPIRY_BUFFER_SECONDS = 300; // Refresh token 5 mins before expiry
}
