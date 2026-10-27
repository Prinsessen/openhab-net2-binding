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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Net2's own names for its event types and sub types, read once from
 * {@code GET /api/v1/events/eventTypes} and {@code /eventSubTypes} - or, where the server answers
 * those with an error (HTTP 500 from a Net2 v7.01 server, 2026-10-27), learnt from the last week of
 * events - so an event can say what it is ("Access denied ...") instead of a bare number such as 112. The API documents the answer
 * only as "object", so the reader accepts a list of objects, an object of id to name, or such a
 * list under one property, with the usual id and name keys.
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
public class Net2EventTypes {

    private static final String[] ID_KEYS = { "id", "eventTypeId", "eventSubTypeId", "eventType", "eventSubType",
            "typeId", "value", "key" };
    private static final String[] NAME_KEYS = { "description", "eventTypeDescription", "eventSubTypeDescription",
            "eventDescription", "name", "text", "displayName" };

    private Net2EventTypes() {
    }

    /** The id-to-name table in a Net2 answer; empty when it cannot be read. */
    public static Map<Integer, String> parse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return read(JsonParser.parseString(json));
        } catch (RuntimeException e) {
            return Collections.emptyMap();
        }
    }

    /**
     * The names Net2 put on the events themselves ({@code GET /api/v1/events}: eventType with
     * eventDescription, or eventTypeId with eventTypeDescription) - for a server that answers the
     * eventTypes list with an error. {@code subTypes} picks the sub type names instead.
     */
    public static Map<Integer, String> fromEvents(@Nullable String json, boolean subTypes) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonArray events = null;
            if (root.isJsonArray()) {
                events = root.getAsJsonArray();
            } else if (root.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                    if (e.getValue().isJsonArray()) {
                        events = e.getValue().getAsJsonArray();
                        break;
                    }
                }
            }
            if (events == null) {
                return Collections.emptyMap();
            }
            String[] idKeys = subTypes ? new String[] { "eventSubType", "eventSubTypeId" }
                    : new String[] { "eventType", "eventTypeId" };
            String[] nameKeys = subTypes ? new String[] { "eventSubTypeDescription" }
                    : new String[] { "eventDescription", "eventTypeDescription" };
            Map<Integer, String> table = new HashMap<>();
            for (JsonElement item : events) {
                if (item.isJsonObject()) {
                    Integer id = firstInt(item.getAsJsonObject(), idKeys);
                    String name = firstString(item.getAsJsonObject(), nameKeys);
                    if (id != null && name != null) {
                        table.putIfAbsent(id, name);
                    }
                }
            }
            return table;
        } catch (RuntimeException e) {
            return Collections.emptyMap();
        }
    }

    /** What the answer looked like, without any values: for the log when nothing could be read. */
    public static String shape(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return "empty";
        }
        try {
            JsonElement e = JsonParser.parseString(json);
            if (e.isJsonArray()) {
                JsonArray a = e.getAsJsonArray();
                return "array of " + a.size()
                        + (a.size() > 0 && a.get(0).isJsonObject() ? ", keys " + a.get(0).getAsJsonObject().keySet()
                                : "");
            }
            if (e.isJsonObject()) {
                return "object, keys " + e.getAsJsonObject().keySet();
            }
            return "a single value";
        } catch (RuntimeException ex) {
            return "not JSON";
        }
    }

    private static Map<Integer, String> read(JsonElement element) {
        Map<Integer, String> table = new HashMap<>();
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    JsonObject o = item.getAsJsonObject();
                    Integer id = firstInt(o, ID_KEYS);
                    String name = firstString(o, NAME_KEYS);
                    if (id != null && name != null) {
                        table.put(id, name);
                    }
                }
            }
            return table;
        }
        if (element.isJsonObject()) {
            JsonObject o = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
                JsonElement v = entry.getValue();
                Integer id = toInt(entry.getKey());
                if (id != null && v.isJsonPrimitive()) {
                    table.put(id, v.getAsString());
                } else if (id != null && v.isJsonObject()) {
                    String name = firstString(v.getAsJsonObject(), NAME_KEYS);
                    if (name != null) {
                        table.put(id, name);
                    }
                } else if (v.isJsonArray() && table.isEmpty()) {
                    table.putAll(read(v));
                }
            }
        }
        return table;
    }

    private static @Nullable Integer firstInt(JsonObject o, String[] keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) {
                Integer v = toInt(o.get(k).getAsString());
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    private static @Nullable String firstString(JsonObject o, String[] keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) {
                String v = o.get(k).getAsString().trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        return null;
    }

    private static @Nullable Integer toInt(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
