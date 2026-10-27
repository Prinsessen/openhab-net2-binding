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

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.net2.Net2BindingConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * WebSocket client for Net2 SignalR real-time events
 * Enables live door open/close status updates without polling.
 *
 * @author OpenHAB Community - Initial contribution
 */
@NonNullByDefault
public class Net2SignalRClient implements Listener {

    private static final String RECORD_SEPARATOR = "\u001e";

    private final Logger logger = LoggerFactory.getLogger(Net2SignalRClient.class);

    private final URI hubBaseUri;
    private final String accessToken;
    private final boolean tlsVerification;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private enum Mode {
        CORE,
        CLASSIC
    }

    private Mode mode = Mode.CORE;

    private @Nullable WebSocket webSocket;
    private @Nullable BiConsumer<String, JsonObject> eventConsumer;
    private @Nullable Runnable onConnectedCallback;
    private @Nullable Runnable onDisconnectedCallback;

    public Net2SignalRClient(URI serverRoot, String accessToken, boolean tlsVerification) {
        this.hubBaseUri = serverRoot.resolve(Net2BindingConstants.SIGNALR_HUB_PATH);
        this.accessToken = accessToken;
        this.tlsVerification = tlsVerification;
    }

    public void setEventConsumer(BiConsumer<String, JsonObject> eventConsumer) {
        this.eventConsumer = eventConsumer;
    }

    public void setOnConnectedCallback(Runnable callback) {
        this.onConnectedCallback = callback;
    }

    public void setOnDisconnectedCallback(Runnable callback) {
        this.onDisconnectedCallback = callback;
    }

    /**
     * Connect, negotiate, and open the WebSocket to the SignalR hub.
     */
    public void connect() {
        try {
            HttpClient httpClient = createHttpClient();

            // Try ASP.NET Core SignalR first
            @Nullable
            HttpResponse<String> coreResp = coreNegotiate(httpClient);
            if (coreResp != null && coreResp.statusCode() == 200) {
                mode = Mode.CORE;
                JsonObject negotiate = JsonParser.parseString(coreResp.body()).getAsJsonObject();
                String connectionId = negotiate.has("connectionId") ? negotiate.get("connectionId").getAsString() : "";
                String wsAccessToken = negotiate.has("accessToken") ? negotiate.get("accessToken").getAsString()
                        : accessToken;
                URI wsUri = buildWebSocketUri(negotiate, connectionId, wsAccessToken);
                logger.debug("Opening SignalR (Core) WebSocket to {}", wsUri);
                webSocket = httpClient.newWebSocketBuilder().buildAsync(wsUri, this).join();
                sendHandshake();
                connected.set(true);
                return;
            }

            // Fallback to classic ASP.NET SignalR
            @Nullable
            HttpResponse<String> classicResp = classicNegotiate(httpClient);
            if (classicResp == null || classicResp.statusCode() != 200) {
                int status = classicResp != null ? classicResp.statusCode() : -1;
                String body = classicResp != null ? classicResp.body() : "";
                logger.warn("SignalR negotiate failed: status {} body: {}", status, body);
                return;
            }

            mode = Mode.CLASSIC;
            JsonObject classic = JsonParser.parseString(classicResp.body()).getAsJsonObject();
            String connectionToken = classic.has("ConnectionToken") ? classic.get("ConnectionToken").getAsString() : "";
            URI wsUri = buildClassicWebSocketUri(connectionToken);
            logger.debug("Opening SignalR (Classic) WebSocket to {}", wsUri);
            webSocket = httpClient.newWebSocketBuilder().header("Authorization", "Bearer " + accessToken)
                    .buildAsync(wsUri, this).join();
            logger.info("SignalR WebSocket connected successfully");
            connected.set(true);
            // Some classic SignalR servers require a start call after connect
            classicStart(httpClient, connectionToken);
            logger.info("SignalR classic start completed, subscribing to events...");
        } catch (Exception e) {
            logger.warn("Failed to connect to SignalR hub", e);
            connected.set(false);
        }
    }

    /**
     * Subscribe to the live/door events that carry access updates.
     */
    public void subscribeToEvents() {
        if (!connected.get() || webSocket == null) {
            logger.debug("SignalR not connected; skipping subscription");
            return;
        }

        logger.info("Subscribing to SignalR events (mode: {})", mode);
        if (mode == Mode.CORE) {
            sendInvocationCore("subscribeToLiveEvents");
            sendInvocationCore("subscribeToDoorEvents");
        } else {
            // Classic: start with liveEvents (no parameter required)
            sendInvocationClassic("eventHubLocal", "subscribeToLiveEvents");
        }
        logger.info("Event subscription sent");

        // Notify server handler that connection is ready for door-specific subscriptions
        if (onConnectedCallback != null) {
            onConnectedCallback.run();
        }
    }

    @Override
    public CompletionStage<?> onText(@Nullable WebSocket socket, @Nullable CharSequence data, boolean last) {
        if (data == null) {
            return Listener.super.onText(socket, data, last);
        }

        String payload = data.toString();
        logger.debug("SignalR message received: {}", payload); // DEBUG: names and token numbers
        try {
            if (mode == Mode.CORE) {
                for (String frame : payload.split(RECORD_SEPARATOR)) {
                    if (frame.isEmpty()) {
                        continue;
                    }
                    JsonObject json = JsonParser.parseString(frame).getAsJsonObject();
                    dispatchCore(json);
                }
            } else {
                // Classic: messages are plain JSON
                JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
                dispatchClassic(json);
            }
        } catch (Exception e) {
            logger.debug("Error processing SignalR message", e);
        }
        return Listener.super.onText(socket, data, last);
    }

    @Override
    public CompletionStage<?> onClose(@Nullable WebSocket socket, int statusCode, @Nullable String reason) {
        boolean wasConnected = connected.getAndSet(false);
        logger.info("SignalR connection closed: {} - {}", statusCode, reason);
        if (wasConnected) {
            notifyDisconnected();
        }
        return Listener.super.onClose(socket, statusCode, reason);
    }

    @Override
    public void onError(@Nullable WebSocket socket, @Nullable Throwable error) {
        boolean wasConnected = connected.getAndSet(false);
        logger.warn("SignalR connection error", error);
        if (wasConnected) {
            notifyDisconnected();
        }
        Listener.super.onError(socket, error);
    }

    private void notifyDisconnected() {
        Runnable callback = onDisconnectedCallback;
        if (callback != null) {
            try {
                callback.run();
            } catch (Exception e) {
                logger.debug("Error in disconnect callback", e);
            }
        }
    }

    public void disconnect() {
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.sendClose(1000, "Normal close");
        }
        connected.set(false);
    }

    public boolean isConnected() {
        return connected.get();
    }

    private void dispatchCore(JsonObject json) {
        if (!json.has("type")) {
            return;
        }

        int type = json.get("type").getAsInt();
        switch (type) {
            case 1: // Invocation
                handleInvocationCore(json);
                break;
            case 6: // Ping
                break;
            default:
                logger.trace("Unhandled SignalR message type {}", type);
        }
    }

    private void handleInvocationCore(JsonObject json) {
        if (!json.has("target") || !json.has("arguments")) {
            return;
        }
        String target = json.get("target").getAsString();
        try {
            JsonObject args = json.getAsJsonArray("arguments").get(0).getAsJsonObject();
            BiConsumer<String, JsonObject> consumer = eventConsumer;
            if (consumer != null) {
                consumer.accept(target, args);
            }
        } catch (Exception e) {
            logger.debug("Unable to dispatch SignalR invocation", e);
        }
    }

    private void dispatchClassic(JsonObject json) {
        if (!json.has("M")) {
            return;
        }
        try {
            var messages = json.getAsJsonArray("M");
            BiConsumer<String, JsonObject> consumer = eventConsumer;
            for (int i = 0; i < messages.size(); i++) {
                JsonObject m = messages.get(i).getAsJsonObject();
                if (!m.has("M") || !m.has("A")) {
                    continue;
                }
                String target = m.get("M").getAsString();

                // Classic SignalR sometimes wraps arguments in an extra array: [ [ {event...} ] ]
                var argsArray = m.getAsJsonArray("A");
                if (argsArray.isEmpty()) {
                    continue;
                }

                // Unwrap first level
                var firstArg = argsArray.get(0);
                if (firstArg.isJsonObject()) {
                    if (consumer != null) {
                        consumer.accept(target, firstArg.getAsJsonObject());
                    }
                } else if (firstArg.isJsonArray()) {
                    var inner = firstArg.getAsJsonArray();
                    for (int j = 0; j < inner.size(); j++) {
                        if (inner.get(j).isJsonObject() && consumer != null) {
                            consumer.accept(target, inner.get(j).getAsJsonObject());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Unable to dispatch classic SignalR messages", e);
        }
    }

    private void sendHandshake() {
        WebSocket socket = webSocket;
        if (socket == null) {
            return;
        }
        if (mode == Mode.CORE) {
            String handshake = "{\"protocol\":\"json\",\"version\":1}" + RECORD_SEPARATOR;
            socket.sendText(handshake, true);
        }
    }

    private void sendInvocationCore(String target) {
        WebSocket socket = webSocket;
        if (socket == null) {
            return;
        }
        String invocation = String.format("{\"type\":1,\"target\":\"%s\",\"arguments\":[]}%s", target,
                RECORD_SEPARATOR);
        socket.sendText(invocation, true);
    }

    private void sendInvocationCoreWithParam(String target, int param) {
        WebSocket socket = webSocket;
        if (socket == null) {
            return;
        }
        String invocation = String.format("{\"type\":1,\"target\":\"%s\",\"arguments\":[%d]}%s", target, param,
                RECORD_SEPARATOR);
        socket.sendText(invocation, true);
    }

    private void sendInvocationClassic(String hub, String method) {
        WebSocket socket = webSocket;
        if (socket == null) {
            return;
        }
        String invocation = String.format("{\"H\":\"%s\",\"M\":\"%s\",\"A\":[],\"I\":0}", hub, method);
        socket.sendText(invocation, true);
    }

    private void sendInvocationClassicWithParam(String hub, String method, int param) {
        WebSocket socket = webSocket;
        if (socket == null) {
            return;
        }
        String invocation = String.format("{\"H\":\"%s\",\"M\":\"%s\",\"A\":[%d],\"I\":0}", hub, method, param);
        socket.sendText(invocation, true);
    }

    /**
     * Subscribe to door-specific events for a given door ID (serial number)
     */
    public void subscribeToDoorEvents(int doorId) {
        if (!connected.get() || webSocket == null) {
            logger.debug("SignalR not connected; skipping door subscription");
            return;
        }

        logger.info("Subscribing to door events for door ID: {}", doorId);
        if (mode == Mode.CORE) {
            sendInvocationCoreWithParam("subscribeToDoorEvents", doorId);
            sendInvocationCoreWithParam("subscribeToDoorStatusEvents", doorId);
        } else {
            sendInvocationClassicWithParam("eventHubLocal", "subscribeToDoorEvents", doorId);
            sendInvocationClassicWithParam("eventHubLocal", "subscribeToDoorStatusEvents", doorId);
        }
        logger.info("Door-specific event subscription sent for door {}", doorId);
    }

    private HttpClient createHttpClient() throws NoSuchAlgorithmException, KeyManagementException {
        HttpClient.Builder builder = HttpClient.newBuilder();
        if (!tlsVerification) {
            builder.sslContext(buildInsecureSslContext());
            javax.net.ssl.SSLParameters params = new javax.net.ssl.SSLParameters();
            params.setEndpointIdentificationAlgorithm("");
            builder.sslParameters(params);
        }
        return builder.build();
    }

    private SSLContext buildInsecureSslContext() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAll = new TrustManager[] { new PermissiveTrustManager() };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAll, new SecureRandom());
        return sslContext;
    }

    @NonNullByDefault({})
    private static final class PermissiveTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // trust all
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // trust all
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private URI buildWebSocketUri(JsonObject negotiate, String connectionId, String token) throws URISyntaxException {
        if (negotiate.has("url")) {
            URI negotiatedUri = URI.create(negotiate.get("url").getAsString());
            return appendQueryToken(negotiatedUri, connectionId, token);
        }
        URI defaultWs = new URI("wss", null, hubBaseUri.getHost(), hubBaseUri.getPort(), hubBaseUri.getPath(), null,
                null);
        return appendQueryToken(defaultWs, connectionId, token);
    }

    private URI appendQueryToken(URI wsUri, String connectionId, String token) throws URISyntaxException {
        StringBuilder query = new StringBuilder();
        if (!connectionId.isEmpty()) {
            query.append("id=").append(connectionId);
        }
        if (token != null && !token.isEmpty()) {
            if (query.length() > 0) {
                query.append("&");
            }
            query.append("access_token=")
                    .append(java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8));
        }
        return new URI(wsUri.getScheme(), null, wsUri.getHost(), wsUri.getPort(), wsUri.getPath(), query.toString(),
                null);
    }

    private @Nullable HttpResponse<String> coreNegotiate(HttpClient httpClient) {
        try {
            URI negotiateUri = URI.create(hubBaseUri.toString() + "/negotiate?negotiateVersion=1");
            HttpRequest request = HttpRequest.newBuilder(negotiateUri).header("Authorization", "Bearer " + accessToken)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.debug("Core negotiate failed", e);
            return null;
        }
    }

    private @Nullable HttpResponse<String> classicNegotiate(HttpClient httpClient) {
        try {
            // Build query string manually - URI constructor expects pre-encoded query
            String connectionData = java.net.URLEncoder.encode("[{\"name\":\"eventHubLocal\"}]",
                    java.nio.charset.StandardCharsets.UTF_8);
            String query = "clientProtocol=1.5&connectionData=" + connectionData + "&_=" + System.currentTimeMillis();

            // Build full URL string instead of using URI constructor (which would double-encode)
            String urlString = hubBaseUri.getScheme() + "://" + hubBaseUri.getHost() + ":" + hubBaseUri.getPort()
                    + "/signalr/negotiate?" + query;
            URI classicUri = URI.create(urlString);

            logger.debug("Classic negotiate URI: {}", classicUri);
            logger.debug("Classic negotiate with token: {} (length: {})",
                    accessToken == null ? "NULL" : accessToken.substring(0, Math.min(20, accessToken.length())) + "...",
                    accessToken == null ? 0 : accessToken.length());
            HttpRequest request = HttpRequest.newBuilder(classicUri).header("Authorization", "Bearer " + accessToken)
                    .GET().build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.debug("Classic negotiate failed", e);
            return null;
        }
    }

    private URI buildClassicWebSocketUri(String connectionToken) throws URISyntaxException {
        String connTokenParam = java.net.URLEncoder.encode(connectionToken, java.nio.charset.StandardCharsets.UTF_8);
        String connectionData = java.net.URLEncoder.encode("[{\"name\":\"eventHubLocal\"}]",
                java.nio.charset.StandardCharsets.UTF_8);
        String query = "transport=webSockets&clientProtocol=1.5&connectionToken=" + connTokenParam + "&connectionData="
                + connectionData;
        // Build full URI string to avoid double-encoding
        String uriString = "wss://" + hubBaseUri.getHost() + ":" + hubBaseUri.getPort() + "/signalr/connect?" + query;
        return URI.create(uriString);
    }

    private void classicStart(HttpClient httpClient, String connectionToken) {
        try {
            String connTokenParam = java.net.URLEncoder.encode(connectionToken,
                    java.nio.charset.StandardCharsets.UTF_8);
            String connectionData = java.net.URLEncoder.encode("[{\"name\":\"eventHubLocal\"}]",
                    java.nio.charset.StandardCharsets.UTF_8);
            String query = "transport=webSockets&clientProtocol=1.5&connectionToken=" + connTokenParam
                    + "&connectionData=" + connectionData + "&_=" + System.currentTimeMillis();
            // Build full URL to avoid double-encoding
            String urlString = hubBaseUri.getScheme() + "://" + hubBaseUri.getHost() + ":" + hubBaseUri.getPort()
                    + "/signalr/start?" + query;
            URI startUri = URI.create(urlString);
            HttpRequest request = HttpRequest.newBuilder(startUri).header("Authorization", "Bearer " + accessToken)
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.debug("Classic start returned status {}", resp.statusCode());
            }
        } catch (Exception e) {
            logger.debug("Classic start failed", e);
        }
    }
}
