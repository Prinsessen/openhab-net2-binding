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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A server handler that is disposed while it is still connecting must leave nothing running.
 *
 * initialize() authenticates in the background and only then opens SignalR and schedules the door poll.
 * On 2026-11-16 12:07 a JAR replace disposed the handler during that time: dispose() found no poll job
 * yet, the background task then opened a SECOND SignalR connection and started a 10-minute poll for a
 * handler that no longer existed - "tried updating the thing status although the handler was already
 * disposed" every 10 minutes until openHAB was restarted (253 times by 11-18 06:13).
 *
 * A local fake Net2 holds the token request until the test has disposed the handler, then answers.
 * On 5.2.4 the disposed handler goes on to negotiate SignalR and poll /doors/status.
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
class Net2ServerHandlerDisposeTest {

    private @Nullable HttpServer server;
    private @Nullable Net2ServerHandler handler;
    private final CountDownLatch tokenAsked = new CountDownLatch(1);
    private final CountDownLatch answerToken = new CountDownLatch(1);
    private final AtomicInteger doorPolls = new AtomicInteger();
    private final AtomicInteger signalRNegotiations = new AtomicInteger();

    @AfterEach
    void stop() {
        answerToken.countDown();
        Net2ServerHandler h = handler;
        if (h != null) {
            h.dispose();
        }
        HttpServer s = server;
        if (s != null) {
            s.stop(0);
        }
    }

    private Net2ServerHandler startHandler(boolean holdToken) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.setExecutor(Executors.newCachedThreadPool());
        s.createContext("/api/v1/authorization/tokens", ex -> {
            tokenAsked.countDown();
            if (holdToken) {
                try {
                    answerToken.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            reply(ex, 200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":3600}");
        });
        s.createContext("/api/v1/doors/status", ex -> {
            doorPolls.incrementAndGet();
            reply(ex, 200, "[]");
        });
        s.createContext("/", ex -> {
            if (ex.getRequestURI().getPath().contains("negotiate")) {
                signalRNegotiations.incrementAndGet();
            }
            reply(ex, 404, "");
        });
        s.start();
        server = s;

        Configuration config = new Configuration(Map.of("hostname",
                "http://127.0.0.1:" + s.getAddress().getPort() + "/api/v1", "username", "u", "password", "p",
                "clientId", "c", "tlsVerification", false, "refreshInterval", 600));
        Bridge bridge = BridgeBuilder.create(new ThingTypeUID("net2", "net2server"), "server")
                .withConfiguration(config).build();
        Net2ServerHandler h = new Net2ServerHandler(bridge);
        h.setCallback(mock(ThingHandlerCallback.class));
        handler = h;
        return h;
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        } else {
            ex.close();
        }
    }

    @Test
    void disposedWhileConnectingStartsNoPollAndNoSignalR() throws Exception {
        Net2ServerHandler h = startHandler(true);
        h.initialize();
        assertTrue(tokenAsked.await(10, TimeUnit.SECONDS), "the background task asks for a token");

        // what openHAB does on a JAR replace or a things-file reload: dispose, then drop the callback
        h.dispose();
        h.setCallback(null);
        handler = null;
        answerToken.countDown(); // Net2 answers the token after the handler is gone

        Thread.sleep(3000);
        assertEquals(0, signalRNegotiations.get(), "a disposed handler opens no SignalR connection");
        assertEquals(0, doorPolls.get(), "a disposed handler polls no doors");
    }

    @Test
    void aHandlerThatIsNotDisposedConnectsAndPolls() throws Exception {
        Net2ServerHandler h = startHandler(false);
        h.initialize();
        long until = System.currentTimeMillis() + 10000;
        while (doorPolls.get() == 0 && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertEquals(1, doorPolls.get(), "the first door poll runs at once");
        assertTrue(signalRNegotiations.get() > 0, "and SignalR is tried");
    }

    @Test
    void afterDisposeTheFirstHandlersPollStops() throws Exception {
        Net2ServerHandler h = startHandler(false);
        h.initialize();
        long until = System.currentTimeMillis() + 10000;
        while (doorPolls.get() == 0 && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        h.dispose();
        h.setCallback(null);
        handler = null;
        int polls = doorPolls.get();
        int negotiations = signalRNegotiations.get();
        Thread.sleep(2000);
        assertEquals(polls, doorPolls.get());
        assertEquals(negotiations, signalRNegotiations.get());
    }
}
