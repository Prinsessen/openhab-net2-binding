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

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Net2 documents the answer of /events/eventTypes only as "object": the reader takes the shapes
 * such an answer can have, and nothing breaks when it cannot.
 */
@NonNullByDefault
class Net2EventTypesTest {

    @Test
    void aListOfObjects() {
        Map<Integer, String> t = Net2EventTypes
                .parse("[{\"id\":20,\"description\":\"Access permitted\"},{\"id\":112,\"description\":\"X\"}]");
        assertEquals("Access permitted", t.get(20));
        assertEquals("X", t.get(112));
    }

    @Test
    void otherKeyNames() {
        Map<Integer, String> t = Net2EventTypes.parse("[{\"eventTypeId\":26,\"name\":\"PIN\"}]");
        assertEquals("PIN", t.get(26));
    }

    @Test
    void anObjectOfIdToName() {
        Map<Integer, String> t = Net2EventTypes.parse("{\"20\":\"Access permitted\",\"23\":{\"description\":\"Denied\"}}");
        assertEquals("Access permitted", t.get(20));
        assertEquals("Denied", t.get(23));
    }

    @Test
    void aListUnderOneProperty() {
        Map<Integer, String> t = Net2EventTypes.parse("{\"eventTypes\":[{\"id\":47,\"description\":\"Door closed\"}]}");
        assertEquals("Door closed", t.get(47));
    }

    @Test
    void garbageGivesAnEmptyTableAndAShape() {
        assertTrue(Net2EventTypes.parse("not json").isEmpty());
        assertTrue(Net2EventTypes.parse("").isEmpty());
        assertEquals("not JSON", Net2EventTypes.shape("not json"));
        assertEquals("object, keys [a]", Net2EventTypes.shape("{\"a\":1}"));
    }
}
