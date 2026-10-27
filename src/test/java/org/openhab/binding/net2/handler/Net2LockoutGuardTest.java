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
import static org.openhab.binding.net2.handler.Net2LockoutGuard.Result.*;

import java.time.Duration;
import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * The lockout guard against the morning of 2026-10-27 at one door: refusals (eventType 112) at
 * 07:34:20, 07:34:27, 07:34:37, 07:42:39, 07:54:54, 07:55:30, 07:55:37, 07:55:39, 07:55:43, the ACU
 * re-instated by hand, the next entry 08:02:23.
 */
@NonNullByDefault
class Net2LockoutGuardTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Duration INTERVAL = Duration.ofMinutes(30);

    private static Instant at(String hhmmss) {
        return Instant.parse("2026-10-27T" + hhmmss + "Z");
    }

    private static Net2LockoutGuard.Result refuse(Net2LockoutGuard g, String hhmmss, boolean auto) {
        return g.onRefusal(at(hhmmss), 3, WINDOW, auto, INTERVAL);
    }

    @Test
    void thatMorningWithAutoReinstate() {
        Net2LockoutGuard g = new Net2LockoutGuard();
        assertEquals(NONE, refuse(g, "07:34:20", true));
        assertEquals(NONE, refuse(g, "07:34:27", true));
        assertEquals(REINSTATE, refuse(g, "07:34:37", true), "the third refusal re-instates, 20 min before the key");
        assertTrue(g.isLockedOut());
        assertEquals(NONE, refuse(g, "07:42:39", true));
        assertEquals(NONE, refuse(g, "07:54:54", true), "within 30 min of the last re-instate");
        assertEquals(NONE, refuse(g, "07:55:30", true));
        assertEquals(NONE, refuse(g, "07:55:43", true));
        assertEquals(CLEARED, g.onEntry());
        assertFalse(g.isLockedOut());
    }

    @Test
    void aLockoutThatOutlastsTheIntervalIsReinstatedAgain() {
        Net2LockoutGuard g = new Net2LockoutGuard();
        refuse(g, "07:34:20", true);
        refuse(g, "07:34:27", true);
        assertEquals(REINSTATE, refuse(g, "07:34:37", true));
        assertEquals(NONE, refuse(g, "08:04:30", true), "29 min 53 s later");
        assertEquals(REINSTATE, refuse(g, "08:04:40", true), "30 min later, still refused");
    }

    @Test
    void withoutAutoReinstateItOnlyReports() {
        Net2LockoutGuard g = new Net2LockoutGuard();
        refuse(g, "07:34:20", false);
        refuse(g, "07:34:27", false);
        assertEquals(LOCKOUT, refuse(g, "07:34:37", false));
        assertEquals(NONE, refuse(g, "07:42:39", false), "reported once");
        assertEquals(CLEARED, g.onEntry());
    }

    @Test
    void refusalsSpreadBeyondTheWindowAreNoLockout() {
        Net2LockoutGuard g = new Net2LockoutGuard();
        assertEquals(NONE, refuse(g, "07:00:00", true));
        assertEquals(NONE, refuse(g, "07:11:00", true));
        assertEquals(NONE, refuse(g, "07:22:00", true));
    }

    @Test
    void anEntryBetweenRefusalsStartsTheCountAgain() {
        Net2LockoutGuard g = new Net2LockoutGuard();
        refuse(g, "07:00:00", true);
        refuse(g, "07:00:10", true);
        assertEquals(NONE, g.onEntry(), "no lockout to clear");
        assertEquals(NONE, refuse(g, "07:00:20", true));
    }

    @Test
    void onlyRefusalTypesCount() {
        assertTrue(Net2LockoutGuard.isRefusal(112));
        assertTrue(Net2LockoutGuard.isRefusal(24));
        assertFalse(Net2LockoutGuard.isRefusal(17), "the step before a PIN");
        assertFalse(Net2LockoutGuard.isRefusal(20));
        assertFalse(Net2LockoutGuard.isRefusal(23), "an unknown token is no lockout of known users");
        assertFalse(Net2LockoutGuard.isRefusal(null));
    }
}
