package com.mcaibot.companion;

import com.google.gson.JsonObject;

public record SocialEvent(
        long timeMillis,
        String type,
        String source,
        String valence,
        int importance,
        String playerName,
        String playerUuid,
        String npcName,
        String npcUuid,
        String summary
) {
    public SocialEvent {
        type = textOrDefault(type, "event");
        source = textOrDefault(source, "java");
        valence = textOrDefault(valence, "neutral");
        importance = Math.max(0, Math.min(100, importance));
        playerName = textOrDefault(playerName, "");
        playerUuid = textOrDefault(playerUuid, "");
        npcName = textOrDefault(npcName, "");
        npcUuid = textOrDefault(npcUuid, "");
        summary = textOrDefault(summary, "");
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", "mc-agent-social-event-v1");
        json.addProperty("timeMillis", timeMillis);
        json.addProperty("type", type);
        json.addProperty("source", source);
        json.addProperty("valence", valence);
        json.addProperty("importance", importance);
        if (!playerName.isBlank()) {
            json.addProperty("playerName", playerName);
        }
        if (!playerUuid.isBlank()) {
            json.addProperty("playerUuid", playerUuid);
        }
        if (!npcName.isBlank()) {
            json.addProperty("npcName", npcName);
        }
        if (!npcUuid.isBlank()) {
            json.addProperty("npcUuid", npcUuid);
        }
        json.addProperty("summary", summary);
        return json;
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
