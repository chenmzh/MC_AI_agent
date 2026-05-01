package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SocialMemory {
    private static final int MAX_RELATIONSHIPS = 128;
    private static final int MAX_EVENTS_PER_RELATIONSHIP = 24;
    private static final Object LOCK = new Object();
    private static final Map<String, MutableRelationship> RELATIONSHIPS = new LinkedHashMap<>();

    private SocialMemory() {
    }

    public static void recordPlayerMessage(ServerPlayer player, Entity npc, String message) {
        record(player, npc, "player_message", "player", "positive", 12, limit("Player said: " + textOrDefault(message, ""), 220));
    }

    public static void recordCompanionMessage(ServerPlayer player, Entity npc, String message) {
        record(player, npc, "companion_message", "companion", "neutral", 10, limit("Companion said: " + textOrDefault(message, ""), 220));
    }

    public static void recordTaskResult(ServerPlayer player, Entity npc, TaskResult result) {
        if (result == null) {
            return;
        }

        String type = result.isCompletion() ? "task_complete" : result.isProblem() ? "task_problem" : "task_update";
        String valence = result.isCompletion() ? "positive" : result.isProblem() ? "negative" : "neutral";
        int importance = result.isCompletion() ? 45 : result.isProblem() ? 55 : 20;
        String summary = result.taskName() + " " + result.status() + " (" + result.code() + "): " + result.message();
        record(player, npc, type, "task_feedback", valence, importance, limit(summary, 260));
    }

    public static void recordTrigger(ServerPlayer player, Entity npc, String trigger, String summary) {
        record(player, npc, "companion_trigger:" + textOrDefault(trigger, "unknown"), "companion_loop", "neutral", 35, limit(summary, 240));
    }

    public static RelationshipState stateFor(ServerPlayer player, JsonObject npcState) {
        String key = key(playerUuid(player), npcUuid(npcState));
        synchronized (LOCK) {
            MutableRelationship state = RELATIONSHIPS.get(key);
            if (state == null) {
                return emptyState(player, npcState);
            }
            return state.snapshot();
        }
    }

    public static JsonObject snapshotFor(ServerPlayer player, JsonObject npcState) {
        RelationshipState relationship = stateFor(player, npcState);

        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", "mc-agent-social-memory-v1");
        json.addProperty("runtimeOnly", true);
        json.add("relationship", relationship.toJson());

        JsonObject memory = new JsonObject();
        memory.addProperty("summary", summary(relationship));
        memory.addProperty("lastInteractionMillis", relationship.lastInteractionMillis());
        memory.addProperty("lastPlayerMessageMillis", relationship.lastPlayerMessageMillis());
        memory.addProperty("lastCompanionMessageMillis", relationship.lastCompanionMessageMillis());
        memory.addProperty("lastTaskOutcomeMillis", relationship.lastTaskOutcomeMillis());
        json.add("memory", memory);

        JsonArray events = new JsonArray();
        for (SocialEvent event : relationship.recentEvents()) {
            events.add(event.toJson());
        }
        json.add("recentEvents", events);
        return json;
    }

    public static long lastInteractionMillis(ServerPlayer player, JsonObject npcState) {
        return stateFor(player, npcState).lastInteractionMillis();
    }

    private static void record(
            ServerPlayer player,
            Entity npc,
            String type,
            String source,
            String valence,
            int importance,
            String summary
    ) {
        if (player == null) {
            return;
        }

        long now = System.currentTimeMillis();
        String playerUuid = player.getUUID().toString();
        String playerName = player.getGameProfile().getName();
        String npcUuid = npc == null ? "" : npc.getUUID().toString();
        String npcName = npc == null ? NpcManager.activeDisplayName(player.getServer()) : npc.getName().getString();
        SocialEvent event = new SocialEvent(now, type, source, valence, importance, playerName, playerUuid, npcName, npcUuid, summary);

        synchronized (LOCK) {
            MutableRelationship state = RELATIONSHIPS.computeIfAbsent(
                    key(playerUuid, npcUuid),
                    ignored -> new MutableRelationship(playerUuid, playerName, npcUuid, npcName)
            );
            state.updateIdentity(playerName, npcUuid, npcName);
            state.apply(event);
            trimRelationshipCount();
        }
    }

    private static RelationshipState emptyState(ServerPlayer player, JsonObject npcState) {
        return new RelationshipState(
                playerUuid(player),
                player == null ? "" : player.getGameProfile().getName(),
                npcUuid(npcState),
                npcName(npcState),
                0,
                50,
                "neutral",
                0L,
                0L,
                0L,
                0L,
                List.of()
        );
    }

    private static String summary(RelationshipState state) {
        if (state.lastInteractionMillis() <= 0L) {
            return "No direct social history yet.";
        }
        return "Familiarity " + state.familiarity()
                + "/100, trust " + state.trust()
                + "/100, current mood " + state.mood() + ".";
    }

    private static void trimRelationshipCount() {
        while (RELATIONSHIPS.size() > MAX_RELATIONSHIPS) {
            String firstKey = RELATIONSHIPS.keySet().iterator().next();
            RELATIONSHIPS.remove(firstKey);
        }
    }

    private static String key(String playerUuid, String npcUuid) {
        return textOrDefault(playerUuid, "unknown-player") + ":" + textOrDefault(npcUuid, "active-npc");
    }

    private static String playerUuid(ServerPlayer player) {
        return player == null ? "" : player.getUUID().toString();
    }

    private static String npcUuid(JsonObject npcState) {
        return AgentJson.string(npcState, "uuid", "");
    }

    private static String npcName(JsonObject npcState) {
        return AgentJson.string(npcState, "name", "");
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String limit(String value, int maxLength) {
        String text = textOrDefault(value, "");
        return text.length() <= maxLength ? text : text.substring(0, maxLength).trim();
    }

    private static final class MutableRelationship {
        private final String playerUuid;
        private String playerName;
        private String npcUuid;
        private String npcName;
        private int familiarity;
        private int trust = 50;
        private String mood = "neutral";
        private long lastInteractionMillis;
        private long lastPlayerMessageMillis;
        private long lastCompanionMessageMillis;
        private long lastTaskOutcomeMillis;
        private final Deque<SocialEvent> events = new ArrayDeque<>();

        private MutableRelationship(String playerUuid, String playerName, String npcUuid, String npcName) {
            this.playerUuid = playerUuid;
            this.playerName = textOrDefault(playerName, "");
            this.npcUuid = textOrDefault(npcUuid, "");
            this.npcName = textOrDefault(npcName, "");
        }

        private void updateIdentity(String playerName, String npcUuid, String npcName) {
            this.playerName = textOrDefault(playerName, this.playerName);
            this.npcUuid = textOrDefault(npcUuid, this.npcUuid);
            this.npcName = textOrDefault(npcName, this.npcName);
        }

        private void apply(SocialEvent event) {
            events.addFirst(event);
            while (events.size() > MAX_EVENTS_PER_RELATIONSHIP) {
                events.removeLast();
            }

            familiarity = clamp(familiarity + Math.max(1, event.importance() / 12));
            if ("positive".equals(event.valence())) {
                trust = clamp(trust + Math.max(1, event.importance() / 15));
                mood = "warm";
            } else if ("negative".equals(event.valence())) {
                trust = clamp(trust - Math.max(1, event.importance() / 12));
                mood = "concerned";
            } else if (event.type().contains("low_health")) {
                mood = "concerned";
            }

            lastInteractionMillis = event.timeMillis();
            if ("player_message".equals(event.type())) {
                lastPlayerMessageMillis = event.timeMillis();
            }
            if ("companion_message".equals(event.type())) {
                lastCompanionMessageMillis = event.timeMillis();
            }
            if (event.type().startsWith("task_")) {
                lastTaskOutcomeMillis = event.timeMillis();
            }
        }

        private RelationshipState snapshot() {
            return new RelationshipState(
                    playerUuid,
                    playerName,
                    npcUuid,
                    npcName,
                    familiarity,
                    trust,
                    mood,
                    lastInteractionMillis,
                    lastPlayerMessageMillis,
                    lastCompanionMessageMillis,
                    lastTaskOutcomeMillis,
                    new ArrayList<>(events)
            );
        }

        private int clamp(int value) {
            return Math.max(0, Math.min(100, value));
        }
    }
}
