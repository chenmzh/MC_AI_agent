package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public record RelationshipState(
        String playerUuid,
        String playerName,
        String npcUuid,
        String npcName,
        int familiarity,
        int trust,
        String mood,
        long lastInteractionMillis,
        long lastPlayerMessageMillis,
        long lastCompanionMessageMillis,
        long lastTaskOutcomeMillis,
        List<SocialEvent> recentEvents
) {
    public RelationshipState {
        playerUuid = textOrDefault(playerUuid, "");
        playerName = textOrDefault(playerName, "");
        npcUuid = textOrDefault(npcUuid, "");
        npcName = textOrDefault(npcName, "");
        familiarity = clamp(familiarity);
        trust = clamp(trust);
        mood = textOrDefault(mood, "neutral");
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", "mc-agent-relationship-state-v1");
        json.addProperty("playerUuid", playerUuid);
        json.addProperty("playerName", playerName);
        json.addProperty("npcUuid", npcUuid);
        json.addProperty("npcName", npcName);
        json.addProperty("familiarity", familiarity);
        json.addProperty("trust", trust);
        json.addProperty("mood", mood);
        json.addProperty("lastInteractionMillis", lastInteractionMillis);
        json.addProperty("lastPlayerMessageMillis", lastPlayerMessageMillis);
        json.addProperty("lastCompanionMessageMillis", lastCompanionMessageMillis);
        json.addProperty("lastTaskOutcomeMillis", lastTaskOutcomeMillis);

        JsonArray events = new JsonArray();
        for (SocialEvent event : recentEvents) {
            events.add(event.toJson());
        }
        json.add("recentEvents", events);
        return json;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
