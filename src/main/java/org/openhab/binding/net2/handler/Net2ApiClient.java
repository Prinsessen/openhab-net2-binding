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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link Net2ApiClient} handles all HTTP communication with the Paxton Net2 API.
 *
 * @author OpenHAB Community - Initial contribution
 */
@NonNullByDefault
public class Net2ApiClient {

    private final Logger logger = LoggerFactory.getLogger(Net2ApiClient.class);

    private final String baseUrl;
    private final String username;
    private final String password;
    private final String clientId;
    private final boolean tlsVerification;
    private final HttpClient httpClient;
    private final URI serverRootUri;

    private String accessToken = "";
    private String refreshToken = "";
    private ZonedDateTime tokenExpiry = ZonedDateTime.now(ZoneOffset.UTC);
    private final ReentrantLock tokenLock = new ReentrantLock();

    public Net2ApiClient(Net2ServerConfiguration config) {
        String host = config.hostname;
        if (host.startsWith("http://") || host.startsWith("https://")) {
            // Allow full base URL in hostname param (compat with external config)
            this.baseUrl = host.replaceAll("/+$", "");
        } else {
            this.baseUrl = String.format("https://%s:%d/api/%s", host, config.port != null ? config.port : 8443, "v1");
        }
        this.username = config.username;
        this.password = config.password;
        this.clientId = config.clientId;
        this.tlsVerification = config.tlsVerification != null ? config.tlsVerification : true;

        // Create HTTP client
        HttpClient.Builder builder = HttpClient.newBuilder();
        this.httpClient = builder.build();

        try {
            URI apiUri = URI.create(this.baseUrl);
            this.serverRootUri = new URI(apiUri.getScheme(), null, apiUri.getHost(), apiUri.getPort(), null, null,
                    null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid base URL for Net2 API", e);
        }
    }

    /**
     * Authenticate with Net2 API
     */
    public boolean authenticate() throws IOException, InterruptedException {
        tokenLock.lock();
        try {
            Map<String, String> formData = new HashMap<>();
            formData.put("username", username);
            formData.put("password", password);
            formData.put("grant_type", "password");
            formData.put("client_id", clientId);
            formData.put("scope", "offline_access");

            String body = encodeFormData(formData);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/authorization/tokens"))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8").build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                accessToken = jsonResponse.get("access_token").getAsString();
                refreshToken = jsonResponse.get("refresh_token").getAsString();
                int expiresIn = jsonResponse.get("expires_in").getAsInt();
                tokenExpiry = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(expiresIn);

                logger.debug("Successfully authenticated with Net2 API. Token expires in {} seconds", expiresIn);
                return true;
            } else {
                logger.error("Authentication failed. Status: {}, Response: {}", response.statusCode(), response.body());
                return false;
            }
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Refresh access token using refresh token
     */
    private boolean refreshAccessToken() throws IOException, InterruptedException {
        tokenLock.lock();
        try {
            if (refreshToken == null) {
                return authenticate();
            }

            Map<String, String> formData = new HashMap<>();
            formData.put("refresh_token", refreshToken);
            formData.put("grant_type", "refresh_token");
            formData.put("client_id", clientId);
            formData.put("scope", "offline_access");

            String body = encodeFormData(formData);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/authorization/tokens"))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8").build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                accessToken = jsonResponse.get("access_token").getAsString();
                if (jsonResponse.has("refresh_token")) {
                    refreshToken = jsonResponse.get("refresh_token").getAsString();
                }
                int expiresIn = jsonResponse.get("expires_in").getAsInt();
                tokenExpiry = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(expiresIn);

                logger.debug("Token refreshed successfully");
                return true;
            } else {
                logger.error("Token refresh failed. Status: {}", response.statusCode());
                return false;
            }
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Check and refresh token if needed
     */
    private void ensureTokenValid() throws IOException, InterruptedException {
        tokenLock.lock();
        try {
            if (accessToken == null) {
                throw new IllegalStateException("Not authenticated");
            }

            if (tokenExpiry != null && ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(300).isAfter(tokenExpiry)) {
                logger.debug("Token expiring soon, refreshing...");
                refreshAccessToken();
            }
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Get door status
     */
    public String getDoorStatus() throws IOException, InterruptedException {
        ensureTokenValid();

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/doors/status")).GET()
                .header("Authorization", "Bearer " + accessToken).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return response.body();
        } else {
            throw new IOException("Failed to get door status. Status: " + response.statusCode());
        }
    }

    /**
     * Get list of all doors
     */
    public String listDoors() throws IOException, InterruptedException {
        ensureTokenValid();

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/doors")).GET()
                .header("Authorization", "Bearer " + accessToken).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return response.body();
        } else {
            throw new IOException("Failed to list doors. Status: " + response.statusCode());
        }
    }

    /**
     * Hold door open
     */
    public boolean holdDoorOpen(int doorId) throws IOException, InterruptedException {
        ensureTokenValid();

        JsonObject body = new JsonObject();
        body.addProperty("DoorId", doorId);

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/commands/door/holdopen"))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json").build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200;
    }

    /**
     * Re-instate the ACU behind a door: Net2's "Re-Instate", which sends the ACU its configuration
     * again. It cures an ACU that suddenly refuses every token and PIN or runs its time schedules
     * wrongly.
     */
    public boolean reinstate(int doorId) throws IOException, InterruptedException {
        ensureTokenValid();

        JsonObject body = new JsonObject();
        body.addProperty("DoorId", doorId);

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/commands/door/reinstate"))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json").build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200;
    }

    /**
     * Close door
     */
    public boolean closeDoor(int doorId) throws IOException, InterruptedException {
        ensureTokenValid();

        JsonObject body = new JsonObject();
        body.addProperty("DoorId", doorId);

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/commands/door/close"))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json").build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200;
    }

    /**
     * Fire-and-forget advanced door control (uses Net2 server's programmed time)
     *
     * @param payload Full JSON payload as string (should include DoorId, RelayFunction, LedFlash)
     * @return true if command accepted
     */
    public boolean controlDoorFireAndForget(String payload) throws IOException, InterruptedException {
        ensureTokenValid();

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/commands/door/control"))
                .POST(HttpRequest.BodyPublishers.ofString(payload)).header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json").build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 || response.statusCode() == 202;
    }

    /**
     * Control building lockdown
     *
     * @param enable true to enable lockdown (lock all configured doors), false to disable
     * @return true if command accepted
     */
    public boolean controlLockdown(boolean enable) throws IOException, InterruptedException {
        ensureTokenValid();

        JsonObject payload = new JsonObject();
        payload.addProperty("lockdown", enable);

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/commands/controlLockdown"))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json").build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            logger.info("Lockdown {} successfully", enable ? "enabled" : "disabled");
            return true;
        } else {
            logger.warn("Lockdown control failed. Status: {}, Response: {}", response.statusCode(), response.body());
            return false;
        }
    }

    /**
     * Check if authenticated and token is valid
     */
    public boolean isAuthenticated() {
        tokenLock.lock();
        try {
            return accessToken != null
                    && (tokenExpiry == null || ZonedDateTime.now(ZoneOffset.UTC).isBefore(tokenExpiry));
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Returns a valid access token, refreshing if needed.
     */
    public String getValidAccessToken() throws IOException, InterruptedException {
        ensureTokenValid();
        return accessToken;
    }

    /**
     * Returns the root URI (scheme/host/port) for the server, without the API path.
     */
    public URI getServerRootUri() {
        return serverRootUri;
    }

    /**
     * Indicates whether TLS verification is enabled in the configuration.
     */
    public boolean isTlsVerificationEnabled() {
        return tlsVerification;
    }

    /**
     * Add a user to the Net2 system
     * Returns the new user ID on success, or -1 on failure
     */
    public int addUser(String firstName, String lastName, String middleName, String pin, String expireTime)
            throws IOException, InterruptedException {
        ensureTokenValid();

        JsonObject body = new JsonObject();
        body.addProperty("firstName", firstName);
        body.addProperty("lastName", lastName);
        if (middleName != null && !middleName.isEmpty()) {
            body.addProperty("middleName", middleName);
        }
        if (pin != null && !pin.isEmpty()) {
            body.addProperty("pin", pin);
        }
        if (expireTime != null && !expireTime.isEmpty()) {
            body.addProperty("expiryDate", expireTime);
        }

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/users"))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json").build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200 || response.statusCode() == 201) {
            JsonObject responseObj = JsonParser.parseString(response.body()).getAsJsonObject();
            int userId = responseObj.get("id").getAsInt();
            logger.info("User added successfully: {} {} (ID: {})", firstName, lastName, userId);
            return userId;
        } else {
            logger.error("Failed to add user. Status: {}, Response: {}", response.statusCode(), response.body());
            return -1;
        }
    }

    /**
     * Add a token (card) to a user
     */
    public boolean addUserToken(int userId, String tokenNumber, int tokenType)
            throws IOException, InterruptedException {
        ensureTokenValid();

        JsonObject body = new JsonObject();
        body.addProperty("tokenNumber", tokenNumber);
        body.addProperty("tokenType", tokenType);

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/users/" + userId + "/tokens"))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json").build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200 || response.statusCode() == 201) {
            logger.info("Token added to user {}: {}", userId, tokenNumber);
            return true;
        } else {
            logger.error("Failed to add token. Status: {}, Response: {}", response.statusCode(), response.body());
            return false;
        }
    }

    /**
     * Delete a user from the Net2 system
     */
    public boolean deleteUser(String userIdentifier) throws IOException, InterruptedException {
        ensureTokenValid();

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/users/" + userIdentifier)).DELETE()
                .header("Authorization", "Bearer " + accessToken).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200 || response.statusCode() == 204) {
            logger.info("User deleted successfully: {}", userIdentifier);
            return true;
        } else {
            logger.error("Failed to delete user. Status: {}, Response: {}", response.statusCode(), response.body());
            return false;
        }
    }

    /**
     * List all access levels available in the Net2 system.
     * Returns a map of id -> name.
     */
    public Map<Integer, String> listAccessLevels() throws IOException, InterruptedException {
        ensureTokenValid();

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/accesslevels")).GET()
                .header("Authorization", "Bearer " + accessToken).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            Map<Integer, String> map = new HashMap<>();
            JsonElement el = JsonParser.parseString(response.body());
            if (el.isJsonArray()) {
                JsonArray arr = el.getAsJsonArray();
                for (JsonElement e : arr) {
                    if (e.isJsonObject()) {
                        JsonObject obj = e.getAsJsonObject();
                        if (obj.has("id")) {
                            int id = obj.get("id").getAsInt();
                            String name = obj.has("name") ? obj.get("name").getAsString() : String.valueOf(id);
                            map.put(id, name);
                        }
                    }
                }
            }
            logger.debug("Fetched {} access levels", map.size());
            return map;
        } else {
            logger.error("Failed to list access levels. Status: {}, Response: {}", response.statusCode(),
                    response.body());
            throw new IOException("GET /accesslevels failed with status " + response.statusCode());
        }
    }

    /**
     * List all users in the Net2 system.
     * Returns the full JSON response as a string.
     */
    public String listUsers() throws IOException, InterruptedException {
        ensureTokenValid();

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/users")).GET()
                .header("Authorization", "Bearer " + accessToken).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            logger.debug("Fetched users list");
            return response.body();
        } else {
            logger.error("Failed to list users. Status: {}, Response: {}", response.statusCode(), response.body());
            throw new IOException("GET /users failed with status " + response.statusCode());
        }
    }

    /**
     * Resolve an access level input (ID or name) to a valid ID present in the system.
     * Returns null if it cannot be resolved.
     */
    public @Nullable Integer resolveAccessLevelId(String accessLevelInput) throws IOException, InterruptedException {
        Map<Integer, String> levels = listAccessLevels();

        // Try numeric ID first
        try {
            int id = Integer.parseInt(accessLevelInput);
            if (levels.containsKey(id)) {
                return id;
            }
        } catch (NumberFormatException ignore) {
            // Not a number, fall through to name match
        }

        // Try to match by name (case-insensitive)
        String wanted = accessLevelInput.trim().toLowerCase();
        for (Map.Entry<Integer, String> e : levels.entrySet()) {
            if (e.getValue() != null && e.getValue().trim().toLowerCase().equals(wanted)) {
                return e.getKey();
            }
        }

        return null;
    }

    /**
     * Retrieve a user's door permission composite set
     */
    public String getUserDoorPermissionSet(int userId) throws IOException, InterruptedException {
        ensureTokenValid();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/users/" + userId + "/doorpermissionset")).GET()
                .header("Authorization", "Bearer " + accessToken).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            logger.info("Fetched door permission set for user {}", userId);
            return response.body();
        } else {
            logger.error("Failed to fetch door permission set for user {}. Status: {}, Response: {}", userId,
                    response.statusCode(), response.body());
            throw new IOException("GET doorpermissionset failed with status " + response.statusCode());
        }
    }

    /**
     * Replace a user's door permission composite set
     */
    public boolean replaceUserDoorPermissionSet(int userId, String permissionSetJson)
            throws IOException, InterruptedException {
        ensureTokenValid();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/users/" + userId + "/doorpermissionset"))
                .PUT(HttpRequest.BodyPublishers.ofString(permissionSetJson))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json").build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200 || response.statusCode() == 204) {
            logger.info("Replaced door permission set for user {}", userId);
            return true;
        } else {
            logger.error("Failed to replace door permission set for user {}. Status: {}, Response: {}", userId,
                    response.statusCode(), response.body());
            return false;
        }
    }

    /**
     * Assign access levels to a user (replaces existing levels)
     */
    public boolean assignAccessLevels(int userId, Integer... accessLevelIds) throws IOException, InterruptedException {
        StringBuilder json = new StringBuilder("{\"accessLevels\":[");
        for (int i = 0; i < accessLevelIds.length; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append(accessLevelIds[i]);
        }
        json.append("],\"individualPermissions\":[]}");

        return replaceUserDoorPermissionSet(userId, json.toString());
    }

    /**
     * Net2's list of event types ({@code path} "eventTypes") or sub types ("eventSubTypes").
     *
     * @return the JSON body, or an empty string on failure
     */
    public String getEventTypeList(String path) throws IOException, InterruptedException {
        ensureTokenValid();

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/events/" + path)).GET()
                .header("Authorization", "Bearer " + accessToken).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return response.body();
        }
        logger.warn("Failed to read the Net2 {}. Status: {}", path, response.statusCode());
        return "";
    }

    /**
     * Retrieve access events from the Net2 API for the specified number of hours.
     *
     * @param hours number of hours to look back
     * @return JSON response body as string, or empty string on failure
     */
    public String getEvents(int hours) throws IOException, InterruptedException {
        ensureTokenValid();

        ZonedDateTime endTime = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime startTime = endTime.minusHours(hours);

        String startDate = startTime.format(DateTimeFormatter.ISO_INSTANT);
        String endDate = endTime.format(DateTimeFormatter.ISO_INSTANT);

        String query = "startDate=" + URLEncoder.encode(startDate, java.nio.charset.StandardCharsets.UTF_8)
                + "&endDate=" + URLEncoder.encode(endDate, java.nio.charset.StandardCharsets.UTF_8) + "&pageSize=1000";

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/events?" + query)).GET()
                .header("Authorization", "Bearer " + accessToken).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            logger.debug("Retrieved events for last {} hours", hours);
            return response.body();
        } else {
            logger.warn("Failed to retrieve events. Status: {}", response.statusCode());
            return "";
        }
    }

    /**
     * Close the HTTP client
     */
    public void close() {
        // HTTP client is managed by the JVM, no explicit close needed
    }

    /**
     * Encode form data for URL-encoded POST
     */
    private String encodeFormData(Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(URLEncoder.encode(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8)).append("=")
                    .append(URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
