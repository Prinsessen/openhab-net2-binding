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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * The user list goes to the listUsers item, and so into events.log and persistence: no PIN may be
 * in it. Before 5.2.2 the list went to openhab.log with every PIN, at every start.
 */
@NonNullByDefault
class Net2UserListTest {

    @Test
    void everyPinIsLeftOut() {
        String users = "[{\"Id\":1,\"FirstName\":\"John\",\"LastName\":\"Doe\",\"PIN\":\"1234\","
                + "\"Tokens\":[{\"Number\":\"55\",\"pinCode\":\"99\"}]},{\"Id\":2,\"Pin\":\"0000\"}]";
        String out = Net2ServerHandler.withoutPins(users);
        assertFalse(out.contains("1234"));
        assertFalse(out.contains("0000"));
        assertFalse(out.contains("99"));
        assertFalse(out.toLowerCase().contains("pin"));
        assertTrue(out.contains("\"FirstName\":\"John\""));
        assertTrue(out.contains("\"Number\":\"55\""));
    }

    @Test
    void notJsonGivesAnEmptyList() {
        assertEquals("[]", Net2ServerHandler.withoutPins("oops"));
    }
}
