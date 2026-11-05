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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A Net2 server that does not answer must not hold a door command for ever.
 *
 * 2026-11-05 06:53 the company front door was asked to open on the owner's arrival; the
 * Net2 server never answered the hold-open, the binding waited without a word (no timeout
 * on any call) and the door stayed shut until her tag opened it. A second try a few
 * minutes later was answered in 0.09 s.
 *
 * These tests use the binding's own timeouts against a local fake server, so on the code
 * before the fix they fail by waiting, not by not compiling.
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
class Net2ApiClientTimeoutTest {

    private @Nullable HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stop() {
        release.countDown();
        HttpServer s = server;
        if (s != null) {
            s.stop(0);
        }
    }

    /** A fake Net2: tokens always, hold-open and close answered from the given attempt on. */
    private Net2ApiClient client(AtomicInteger calls, int answerFromAttempt) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.setExecutor(Executors.newCachedThreadPool());
        s.createContext("/api/v1/authorization/tokens", ex -> reply(ex, 200,
                "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":3600}"));
        s.createContext("/api/v1/commands/door/", ex -> {
            int n = calls.incrementAndGet();
            if (answerFromAttempt == 0 || n < answerFromAttempt) {
                try {
                    release.await(); // never answers while the test runs
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            reply(ex, 200, "{}");
        });
        s.start();
        server = s;
        Net2ServerConfiguration config = new Net2ServerConfiguration();
        config.hostname = "http://127.0.0.1:" + s.getAddress().getPort() + "/api/v1";
        config.username = "u";
        config.password = "p";
        config.clientId = "c";
        config.tlsVerification = false;
        Net2ApiClient c = new Net2ApiClient(config);
        try {
            assertTrue(c.authenticate());
        } catch (InterruptedException e) {
            fail(e);
        }
        return c;
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    @Test
    void holdOpenUnansweredOnceIsTriedAgainAndOpens() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Net2ApiClient c = client(calls, 2);
        boolean opened = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> c.holdDoorOpen(6203980),
                "a hold-open the server never answers must not wait for ever");
        assertTrue(opened, "the second attempt was answered: the door opens");
        assertEquals(2, calls.get(), "one attempt more, not more than that");
    }

    @Test
    void holdOpenNeverAnsweredGivesUpWithFalseNotAnException() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Net2ApiClient c = client(calls, 0);
        boolean opened = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> c.holdDoorOpen(6203980),
                "two unanswered attempts end in time");
        assertFalse(opened, "false, so the handler logs 'Failed to open door' - and the door thing stays ONLINE");
        assertEquals(2, calls.get());
    }

    @Test
    void closeUnansweredOnceIsTriedAgain() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Net2ApiClient c = client(calls, 2);
        boolean closed = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> c.closeDoor(6203980));
        assertTrue(closed);
        assertEquals(2, calls.get());
    }

    @Test
    void anAnsweredHoldOpenIsOneCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Net2ApiClient c = client(calls, 1);
        assertTrue(c.holdDoorOpen(6203980));
        assertEquals(1, calls.get(), "a door that answers is asked once");
    }
}
