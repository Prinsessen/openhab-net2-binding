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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Which LiveEvents are entries, and what the entryLog / accessEvent JSON carries. On 2026-10-27 an
 * ACU refused every token and PIN with eventType 112 until it was re-instated, and each refusal was
 * posted as an entry.
 */
@NonNullByDefault
class Net2DoorHandlerEventTest {

    private static JsonObject event(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static final String REFUSED = "{\"eventTime\":\"2026-10-27T07:34:20\",\"eventType\":112,\"eventSubType\":0,"
            + "\"tokenType\":\"Token\",\"tokenNumber\":1234567,\"userId\":9,\"userName\":\"Doe John\","
            + "\"deviceId\":6203980,\"notificationType\":\"EventsReportEvent\"}";

    @Test
    void aRefusalIsNotAnEntry() {
        assertFalse(Net2DoorHandler.isEntry(event(REFUSED)));
    }

    @Test
    void tokenAndPinAccessAreEntries() {
        assertTrue(Net2DoorHandler.isEntry(event("{\"eventType\":20,\"userName\":\"Doe John\"}")));
        assertTrue(Net2DoorHandler.isEntry(event("{\"eventType\":26,\"userName\":\"Doe John\"}")));
    }

    @Test
    void theStepBeforeAPinIsNotAnEntry() {
        // 17/82 arrives a second before the 26 of the same user
        assertFalse(Net2DoorHandler.isEntry(event("{\"eventType\":17,\"eventSubType\":82,\"userName\":\"Doe John\"}")));
    }

    @Test
    void anEventWithoutATypeStillCountsAsAnEntry() {
        assertTrue(Net2DoorHandler.isEntry(event("{\"userName\":\"Doe John\"}")));
    }

    @Test
    void theEventJsonCarriesTheTypeAndNotTheToken() {
        JsonObject json = Net2DoorHandler.buildAccessEvent(event(REFUSED), "Front Door", 6203980, null, null);
        assertEquals("John", json.get("firstName").getAsString());
        assertEquals("Doe", json.get("lastName").getAsString());
        assertEquals("Front Door", json.get("doorName").getAsString());
        assertEquals("2026-10-27T07:34:20", json.get("timestamp").getAsString());
        assertEquals(6203980, json.get("doorId").getAsInt());
        assertEquals(112, json.get("eventType").getAsInt());
        assertEquals(0, json.get("eventSubType").getAsInt());
        assertFalse(json.has("tokenNumber"));
    }

    @Test
    void netTwosNamesGoIntoTheJsonWhenKnown() {
        JsonObject json = Net2DoorHandler.buildAccessEvent(event(REFUSED), "Front Door", 6203980,
                "Access denied - example", "None");
        assertEquals("Access denied - example", json.get("eventDescription").getAsString());
        assertEquals("None", json.get("eventSubTypeDescription").getAsString());
        JsonObject bare = Net2DoorHandler.buildAccessEvent(event(REFUSED), "Front Door", 6203980, null, null);
        assertFalse(bare.has("eventDescription"));
    }
}
