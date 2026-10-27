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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Generates HTML activity reports from Net2 access events.
 * Replaces the external net2_user_activity_daemon.py script.
 *
 * @author OpenHAB Community - Initial contribution
 */
@NonNullByDefault
public class Net2ActivityReportGenerator {

    private final Logger logger = LoggerFactory.getLogger(Net2ActivityReportGenerator.class);

    private static final String OUTPUT_DIR = "/etc/openhab/html";
    private static final String OUTPUT_FILE = "net2_activity.html";
    private static final int HOURS_TO_RETRIEVE = 24;

    /** Event type codes for door-related events */
    private static final Set<Integer> DOOR_EVENT_TYPES = Set.of(16, 17, 20, 23, 24, 25, 26, 27, 28, 29, 46, 47, 93,
            112);
    private static final Set<Integer> ACCESS_GRANTED_TYPES = Set.of(20, 26);
    // 16/17 "Access denied", 112 "Access Denied - Lockdown in Progress" (Net2 v7.01's names)
    private static final Set<Integer> ACCESS_DENIED_TYPES = Set.of(16, 17, 23, 24, 25, 27, 112);

    /**
     * Fetch events from the API and generate the HTML report file.
     *
     * @param apiClient authenticated API client
     * @return true if report was generated successfully
     */
    public boolean generateReport(Net2ApiClient apiClient) {
        try {
            String eventsJson = apiClient.getEvents(HOURS_TO_RETRIEVE);
            if (eventsJson == null || eventsJson.isEmpty()) {
                logger.debug("No events data returned from API");
                return false;
            }

            List<JsonObject> events = parseEvents(eventsJson);
            if (events.isEmpty()) {
                logger.debug("No events parsed from API response");
                return false;
            }

            // Process events grouped by door
            Map<String, List<EventRecord>> doorActivity = processEventsByDoor(events);

            // Count summary stats
            int totalEvents = 0;
            int accessGranted = 0;
            int accessDenied = 0;
            java.util.Set<String> uniqueUsers = new java.util.HashSet<>();

            for (List<EventRecord> doorEvents : doorActivity.values()) {
                for (EventRecord rec : doorEvents) {
                    totalEvents++;
                    uniqueUsers.add(rec.userName);
                    if ("Access Granted".equals(rec.result)) {
                        accessGranted++;
                    } else if ("Access Denied".equals(rec.result)) {
                        accessDenied++;
                    }
                }
            }

            // Generate HTML
            String html = buildHtml(doorActivity, totalEvents, accessGranted, accessDenied, uniqueUsers.size());

            // Write to file
            Path outputPath = Paths.get(OUTPUT_DIR, OUTPUT_FILE);
            Files.writeString(outputPath, html);
            logger.debug("Activity report written to {}", outputPath);
            return true;

        } catch (IOException | InterruptedException e) {
            logger.warn("Failed to generate activity report: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            logger.warn("Unexpected error generating activity report", e);
            return false;
        }
    }

    /**
     * Parse raw JSON response into a list of event objects.
     */
    private List<JsonObject> parseEvents(String json) {
        List<JsonObject> result = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonArray array;

            if (root.isJsonArray()) {
                array = root.getAsJsonArray();
            } else if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                // API may wrap events in a sub-field
                if (obj.has("events") && obj.get("events").isJsonArray()) {
                    array = obj.getAsJsonArray("events");
                } else if (obj.has("data") && obj.get("data").isJsonArray()) {
                    array = obj.getAsJsonArray("data");
                } else if (obj.has("results") && obj.get("results").isJsonArray()) {
                    array = obj.getAsJsonArray("results");
                } else {
                    return result;
                }
            } else {
                return result;
            }

            for (JsonElement el : array) {
                if (el.isJsonObject()) {
                    result.add(el.getAsJsonObject());
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to parse events JSON", e);
        }
        return result;
    }

    /**
     * Process events and group by door (device) name.
     */
    private Map<String, List<EventRecord>> processEventsByDoor(List<JsonObject> events) {
        Map<String, List<EventRecord>> doorActivity = new TreeMap<>();

        for (JsonObject event : events) {
            int eventType = getInt(event, "eventType", 0);
            if (!DOOR_EVENT_TYPES.contains(eventType)) {
                continue;
            }

            String deviceName = getString(event, "deviceName", "Unknown Location");
            if (deviceName.isEmpty() || "Unknown Location".equals(deviceName)) {
                continue;
            }

            String firstName = getString(event, "firstName", "");
            String middleName = getString(event, "middleName", "");
            String surname = getString(event, "surname", "");
            String userName = buildName(firstName, middleName, surname);

            String eventDescription = getString(event, "eventDescription", "Unknown");
            String eventDetails = getString(event, "eventDetails", "");
            String timestamp = getString(event, "eventTime", "");

            String result;
            if (ACCESS_GRANTED_TYPES.contains(eventType)) {
                result = "Access Granted";
            } else if (ACCESS_DENIED_TYPES.contains(eventType)) {
                result = "Access Denied";
            } else if (eventType == 28 || eventType == 46) {
                result = "Door Opened";
            } else if (eventType == 29 || eventType == 47) {
                result = "Door Closed";
            } else if (eventType == 93) {
                result = "Door Held Open";
            } else {
                result = eventDescription;
            }

            EventRecord rec = new EventRecord(userName, timestamp, eventDescription, result, eventDetails);
            doorActivity.computeIfAbsent(deviceName, k -> new ArrayList<>()).add(rec);
        }

        // Sort each door's events by timestamp descending
        for (List<EventRecord> list : doorActivity.values()) {
            list.sort((a, b) -> b.timestamp.compareTo(a.timestamp));
        }

        return doorActivity;
    }

    /**
     * Build the full HTML report.
     */
    private String buildHtml(Map<String, List<EventRecord>> doorActivity, int totalEvents, int accessGranted,
            int accessDenied, int uniqueUsers) {

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("    <meta charset=\"UTF-8\">\n");
        sb.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("    <meta http-equiv=\"refresh\" content=\"1800\">\n");
        sb.append(
                "    <meta http-equiv=\"Cache-Control\" content=\"no-store, no-cache, must-revalidate, max-age=0\">\n");
        sb.append("    <meta http-equiv=\"Pragma\" content=\"no-cache\">\n");
        sb.append("    <meta http-equiv=\"Expires\" content=\"0\">\n");
        sb.append("    <title>Paxton Net2 Activity</title>\n");
        sb.append("    <style>\n");
        sb.append("        * { margin: 0; padding: 0; box-sizing: border-box; }\n");
        sb.append(
                "        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background: #f0f2f5; padding: 0; margin: 0; overflow-x: hidden; }\n");
        sb.append("        .container { width: 100%; margin: 0; background: white; overflow: hidden; }\n");
        sb.append(
                "        .header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 15px; text-align: center; }\n");
        sb.append(
                "        .header h1 { font-size: 1.2em; margin-bottom: 5px; word-wrap: break-word; line-height: 1.3; }\n");
        sb.append("        .header p { font-size: 0.75em; opacity: 0.9; }\n");
        sb.append(
                "        .stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; padding: 10px; background: #f8f9fa; }\n");
        sb.append(
                "        .stat-card { background: white; padding: 10px; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.1); text-align: center; }\n");
        sb.append("        .stat-value { font-size: 1.5em; font-weight: bold; color: #667eea; margin: 4px 0; }\n");
        sb.append(
                "        .stat-label { color: #666; font-size: 0.65em; text-transform: uppercase; letter-spacing: 1px; }\n");
        sb.append(
                "        .door-section { margin: 8px; background: #f8f9fa; border-radius: 8px; overflow: hidden; }\n");
        sb.append(
                "        .door-header { background: #667eea; color: white; padding: 8px 12px; font-size: 0.9em; font-weight: bold; }\n");
        sb.append("        .door-content { padding: 6px; }\n");
        sb.append(
                "        .event-card { background: #fff; border-left: 4px solid #667eea; margin-bottom: 6px; padding: 8px; border-radius: 4px; word-wrap: break-word; }\n");
        sb.append(
                "        .event-user { font-weight: bold; font-size: 0.85em; color: #333; margin-bottom: 3px; word-break: break-word; }\n");
        sb.append("        .event-time { font-size: 0.7em; color: #666; margin-bottom: 3px; }\n");
        sb.append(
                "        .event-type { font-size: 0.75em; color: #555; margin-bottom: 3px; word-break: break-word; }\n");
        sb.append(
                "        .event-result { padding: 2px 8px; border-radius: 4px; font-weight: bold; font-size: 0.7em; display: inline-block; }\n");
        sb.append("        .result-granted { background: #d4edda; color: #155724; }\n");
        sb.append("        .result-denied { background: #f8d7da; color: #721c24; }\n");
        sb.append("        .result-unknown { background: #e2e3e5; color: #383d41; }\n");
        sb.append(
                "        .footer { background: #f8f9fa; padding: 8px; text-align: center; color: #666; font-size: 0.65em; line-height: 1.4; }\n");
        sb.append("    </style>\n</head>\n<body>\n");

        // Header
        sb.append("    <div class=\"container\">\n");
        sb.append("        <div class=\"header\">\n");
        sb.append("            <h1>\uD83D\uDD10 Paxton Net2 Activity</h1>\n");
        sb.append("            <p>Last 24 hours of access activity</p>\n");
        sb.append("            <p style=\"margin-top: 5px;\">Updated: ").append(now).append("</p>\n");
        sb.append("        </div>\n");

        // Stats
        sb.append("        <div class=\"stats\">\n");
        appendStatCard(sb, "Events", totalEvents);
        appendStatCard(sb, "Granted", accessGranted);
        appendStatCard(sb, "Denied", accessDenied);
        appendStatCard(sb, "Users", uniqueUsers);
        sb.append("        </div>\n");

        // Door sections
        for (Map.Entry<String, List<EventRecord>> entry : doorActivity.entrySet()) {
            String doorName = entry.getKey();
            List<EventRecord> events = entry.getValue();
            if (events.isEmpty()) {
                continue;
            }

            sb.append("        <div class=\"door-section\">\n");
            sb.append("            <div class=\"door-header\">\uD83D\uDEAA ").append(escapeHtml(doorName))
                    .append("</div>\n");
            sb.append("            <div class=\"door-content\">\n");

            int limit = Math.min(events.size(), 25);
            for (int i = 0; i < limit; i++) {
                EventRecord rec = events.get(i);
                String resultClass = getResultClass(rec.result);
                String displayType = rec.eventDetails.isEmpty() ? rec.eventType
                        : rec.eventType + " - " + rec.eventDetails;

                sb.append("                <div class=\"event-card\">\n");
                sb.append("                    <div class=\"event-user\">\uD83D\uDC64 ")
                        .append(escapeHtml(rec.userName)).append("</div>\n");
                sb.append("                    <div class=\"event-time\">\uD83D\uDD50 ")
                        .append(formatTimestamp(rec.timestamp)).append("</div>\n");
                sb.append("                    <div class=\"event-type\">").append(escapeHtml(displayType))
                        .append("</div>\n");
                sb.append("                    <span class=\"event-result ").append(resultClass).append("\">")
                        .append(escapeHtml(rec.result)).append("</span>\n");
                sb.append("                </div>\n");
            }

            sb.append("            </div>\n");
            sb.append("        </div>\n");
        }

        // Footer
        sb.append("        <div class=\"footer\">\n");
        sb.append("            Auto-refresh every 30 minutes | OpenHAB Net2 Binding\n");
        sb.append("        </div>\n");
        sb.append("    </div>\n</body>\n</html>\n");

        return sb.toString();
    }

    private void appendStatCard(StringBuilder sb, String label, int value) {
        sb.append("            <div class=\"stat-card\">\n");
        sb.append("                <div class=\"stat-label\">").append(label).append("</div>\n");
        sb.append("                <div class=\"stat-value\">").append(value).append("</div>\n");
        sb.append("            </div>\n");
    }

    private String getResultClass(String result) {
        String lower = result.toLowerCase();
        if (lower.contains("grant") || lower.contains("success")) {
            return "result-granted";
        } else if (lower.contains("deni") || lower.contains("fail")) {
            return "result-denied";
        }
        return "result-unknown";
    }

    private String formatTimestamp(@Nullable String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return "Unknown";
        }
        try {
            // Handle ISO format: 2026-01-07T05:21:08 or 2026-01-07T05:21:08Z or with offset
            String clean = timestamp.replace("Z", "").replaceAll("\\+.*$", "");
            if (clean.contains("T")) {
                LocalDateTime dt = LocalDateTime.parse(clean, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return dt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));
            }
            return timestamp;
        } catch (Exception e) {
            return timestamp;
        }
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // --- Helpers ---

    private String getString(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }

    private int getInt(JsonObject obj, String key, int defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsInt();
        }
        return defaultValue;
    }

    private String buildName(String firstName, String middleName, String surname) {
        StringBuilder sb = new StringBuilder();
        if (!firstName.isEmpty()) {
            sb.append(firstName);
        }
        if (!middleName.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(middleName);
        }
        if (!surname.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(surname);
        }
        return sb.length() > 0 ? sb.toString() : "Unknown User";
    }

    /**
     * Simple record for a processed event.
     */
    private static class EventRecord {
        final String userName;
        final String timestamp;
        final String eventType;
        final String result;
        final String eventDetails;

        EventRecord(String userName, String timestamp, String eventType, String result, String eventDetails) {
            this.userName = userName;
            this.timestamp = timestamp;
            this.eventType = eventType;
            this.result = result;
            this.eventDetails = eventDetails;
        }
    }
}
