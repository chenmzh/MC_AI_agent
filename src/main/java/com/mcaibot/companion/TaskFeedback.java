package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Lightweight task feedback bus for bridge context snapshots.
 *
 * Future NpcManager integration points: call info/warn/failure when task code
 * detects NO_PATH, TARGET_LOST, NEED_BLOCKS, DEATH_PAUSED, or RESUMED.
 */
public final class TaskFeedback {
    private static final int MAX_STORED_EVENTS = 64;
    private static final int MAX_STORED_RESULTS = 32;
    private static final int MAX_CONTEXT_EVENTS = 16;
    private static final int MAX_CONTEXT_RESULTS = 8;
    private static final int MAX_NPCS_IN_CONTEXT = 32;
    private static final Object LOCK = new Object();
    private static final Deque<TaskEvent> RECENT_EVENTS = new ArrayDeque<>();
    private static final Deque<TaskResult> RECENT_RESULTS = new ArrayDeque<>();

    private TaskFeedback() {
    }

    public static void info(String taskName, String code, String message) {
        record("info", taskName, code, message);
    }

    public static void info(ServerPlayer owner, Entity npc, String taskName, String code, String message) {
        record(owner, npc, "info", taskName, code, message);
    }

    public static void warn(String taskName, String code, String message) {
        record("warn", taskName, code, message);
    }

    public static void warn(ServerPlayer owner, Entity npc, String taskName, String code, String message) {
        record(owner, npc, "warn", taskName, code, message);
    }

    public static void failure(String taskName, String code, String message) {
        record("failure", taskName, code, message);
    }

    public static void failure(ServerPlayer owner, Entity npc, String taskName, String code, String message) {
        record(owner, npc, "failure", taskName, code, message);
    }

    public static void record(String severity, String taskName, String code, String message) {
        record(null, null, severity, taskName, code, message);
    }

    public static void record(ServerPlayer owner, Entity npc, String severity, String taskName, String code, String message) {
        TaskEvent event = new TaskEvent(
                System.currentTimeMillis(),
                normalizeSeverity(severity),
                textOrDefault(taskName, "unknown"),
                textOrDefault(code, "TASK_EVENT"),
                textOrDefault(message, ""),
                owner == null ? "" : owner.getGameProfile().getName(),
                owner == null ? null : owner.getUUID(),
                npc == null ? null : npc.getUUID(),
                npc == null ? "" : npc.getName().getString()
        );

        TaskResult socialResult;
        synchronized (LOCK) {
            RECENT_EVENTS.addLast(event);
            while (RECENT_EVENTS.size() > MAX_STORED_EVENTS) {
                RECENT_EVENTS.removeFirst();
            }

            TaskResult result = resultFromEvent(event);
            socialResult = result;
            if (result != null) {
                RECENT_RESULTS.addLast(result);
                while (RECENT_RESULTS.size() > MAX_STORED_RESULTS) {
                    RECENT_RESULTS.removeFirst();
                }
            }
        }
        if (socialResult != null) {
            SocialMemory.recordTaskResult(owner, npc, socialResult);
        }
    }

    public static void recordActionResult(ServerPlayer owner, Entity npc, String taskName, ActionResult result) {
        if (result == null) {
            record(owner, npc, "failure", taskName, "ACTION_RESULT_MISSING", "ActionResult was null.");
            return;
        }

        String severity = severityForActionResult(result);
        TaskEvent event = new TaskEvent(
                System.currentTimeMillis(),
                severity,
                textOrDefault(taskName, "unknown"),
                textOrDefault(result.code(), "ACTION_RESULT"),
                textOrDefault(result.message(), result.status()),
                owner == null ? "" : owner.getGameProfile().getName(),
                owner == null ? null : owner.getUUID(),
                npc == null ? null : npc.getUUID(),
                npc == null ? "" : npc.getName().getString()
        );

        TaskResult socialResult;
        synchronized (LOCK) {
            RECENT_EVENTS.addLast(event);
            while (RECENT_EVENTS.size() > MAX_STORED_EVENTS) {
                RECENT_EVENTS.removeFirst();
            }

            socialResult = resultFromActionResult(event, result);
            RECENT_RESULTS.addLast(socialResult);
            while (RECENT_RESULTS.size() > MAX_STORED_RESULTS) {
                RECENT_RESULTS.removeFirst();
            }
        }
        SocialMemory.recordTaskResult(owner, npc, socialResult);
    }

    public static TaskSnapshot snapshotFor(ServerPlayer player, JsonObject npcState) {
        return new TaskSnapshot(
                stringValue(npcState, "task", "idle"),
                booleanValue(npcState, "spawned", false),
                booleanValue(npcState, "following", false),
                booleanValue(npcState, "taskPaused", false),
                intValue(npcState, "taskStepsDone", 0),
                intValue(npcState, "taskPauseSeconds", 0),
                intValue(npcState, "taskSearchRemainingSeconds", 0),
                allNpcsJson(player),
                recentEventsJson(player),
                recentResultsJson(player)
        );
    }

    public static JsonObject snapshotJson(ServerPlayer player, JsonObject npcState) {
        return snapshotFor(player, npcState).toJson();
    }

    public static void clear() {
        synchronized (LOCK) {
            RECENT_EVENTS.clear();
            RECENT_RESULTS.clear();
        }
    }

    public static TaskResult latestResultForTask(ServerPlayer player, String taskName, long sinceMillis) {
        List<TaskResult> results;
        synchronized (LOCK) {
            results = new ArrayList<>(RECENT_RESULTS);
        }

        UUID playerUuid = player == null ? null : player.getUUID();
        for (int index = results.size() - 1; index >= 0; index--) {
            TaskResult result = results.get(index);
            if (playerUuid != null && result.ownerUuid() != null && !playerUuid.equals(result.ownerUuid())) {
                continue;
            }
            if (!textOrDefault(taskName, "").equals(result.taskName())) {
                continue;
            }
            if (sinceMillis > 0L && result.timeMillis() > 0L && result.timeMillis() < sinceMillis) {
                continue;
            }
            return result;
        }
        return null;
    }

    public static TaskResult latestResultFor(ServerPlayer player) {
        List<TaskResult> results;
        synchronized (LOCK) {
            results = new ArrayList<>(RECENT_RESULTS);
        }

        UUID playerUuid = player == null ? null : player.getUUID();
        for (int index = results.size() - 1; index >= 0; index--) {
            TaskResult result = results.get(index);
            if (playerUuid != null && result.ownerUuid() != null && !playerUuid.equals(result.ownerUuid())) {
                continue;
            }
            return result;
        }
        return null;
    }

    private static JsonArray allNpcsJson(ServerPlayer player) {
        JsonArray npcs = new JsonArray();
        if (player == null || player.getServer() == null) {
            return npcs;
        }

        for (ServerLevel level : player.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!isKnownNpc(entity)) {
                    continue;
                }

                JsonObject npc = new JsonObject();
                npc.addProperty("uuid", entity.getUUID().toString());
                npc.addProperty("name", entity.getName().getString());
                npc.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                npc.addProperty("dimension", level.dimension().location().toString());
                npc.addProperty("x", round(entity.getX()));
                npc.addProperty("y", round(entity.getY()));
                npc.addProperty("z", round(entity.getZ()));
                npc.addProperty("alive", entity.isAlive());
                npcs.add(npc);

                if (npcs.size() >= MAX_NPCS_IN_CONTEXT) {
                    return npcs;
                }
            }
        }
        return npcs;
    }

    private static JsonArray recentEventsJson(ServerPlayer player) {
        List<TaskEvent> events;
        synchronized (LOCK) {
            events = new ArrayList<>(RECENT_EVENTS);
        }

        JsonArray recent = new JsonArray();
        UUID playerUuid = player == null ? null : player.getUUID();
        for (int index = events.size() - 1; index >= 0 && recent.size() < MAX_CONTEXT_EVENTS; index--) {
            TaskEvent event = events.get(index);
            if (event.ownerUuid() != null && playerUuid != null && !event.ownerUuid().equals(playerUuid)) {
                continue;
            }
            recent.add(event.toJson());
        }
        return recent;
    }

    private static JsonArray recentResultsJson(ServerPlayer player) {
        List<TaskResult> results;
        synchronized (LOCK) {
            results = new ArrayList<>(RECENT_RESULTS);
        }

        JsonArray recent = new JsonArray();
        UUID playerUuid = player == null ? null : player.getUUID();
        for (int index = results.size() - 1; index >= 0 && recent.size() < MAX_CONTEXT_RESULTS; index--) {
            TaskResult result = results.get(index);
            if (result.ownerUuid() != null && playerUuid != null && !result.ownerUuid().equals(playerUuid)) {
                continue;
            }
            recent.add(result.toJson());
        }
        return recent;
    }

    private static TaskResult resultFromEvent(TaskEvent event) {
        String status = resultStatus(event.severity(), event.code());
        if (status.isBlank()) {
            return null;
        }

        return new TaskResult(
                event.timeMillis(),
                status,
                event.severity(),
                event.taskName(),
                event.code(),
                event.message(),
                event.ownerName(),
                event.ownerUuid(),
                event.npcUuid(),
                event.npcName(),
                null
        );
    }

    private static TaskResult resultFromActionResult(TaskEvent event, ActionResult result) {
        return new TaskResult(
                event.timeMillis(),
                textOrDefault(result.status(), resultStatus(event.severity(), event.code())),
                event.severity(),
                event.taskName(),
                event.code(),
                event.message(),
                event.ownerName(),
                event.ownerUuid(),
                event.npcUuid(),
                event.npcName(),
                result.toJson()
        );
    }

    private static String resultStatus(String severity, String code) {
        if ("TASK_COMPLETE".equals(code)) {
            return "complete";
        }
        if ("failure".equals(severity)) {
            return "failed";
        }
        if (!"warn".equals(severity)) {
            return "";
        }

        return switch (code) {
            case "SEARCHING_WITH_OWNER", "NEED_SCAFFOLD_BLOCK", "UNREACHABLE_PLACE_TARGET", "CANNOT_PLACE_BLOCK" -> "";
            default -> "blocked";
        };
    }

    private static boolean isKnownNpc(Entity entity) {
        return NpcManager.isCompanionEntity(entity);
    }

    private static String normalizeSeverity(String severity) {
        String value = textOrDefault(severity, "info").toLowerCase(Locale.ROOT);
        return switch (value) {
            case "info", "warn", "failure" -> value;
            case "error" -> "failure";
            case "warning" -> "warn";
            default -> "info";
        };
    }

    private static String severityForActionResult(ActionResult result) {
        if (result.isFailed()) {
            return "failure";
        }
        if (result.isBlocked()) {
            return "warn";
        }
        return "info";
    }

    private static String stringValue(JsonObject json, String key, String fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean booleanValue(JsonObject json, String key, boolean fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int intValue(JsonObject json, String key, int fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String textOrDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record TaskEvent(
            long timeMillis,
            String severity,
            String taskName,
            String code,
            String message,
            String ownerName,
            UUID ownerUuid,
            UUID npcUuid,
            String npcName
    ) {
        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("timeMillis", timeMillis);
            json.addProperty("severity", severity);
            json.addProperty("taskName", taskName);
            json.addProperty("code", code);
            json.addProperty("message", message);
            if (!ownerName.isBlank()) {
                json.addProperty("ownerName", ownerName);
            }
            if (ownerUuid != null) {
                json.addProperty("ownerUuid", ownerUuid.toString());
            }
            if (npcUuid != null) {
                json.addProperty("npcUuid", npcUuid.toString());
            }
            if (!npcName.isBlank()) {
                json.addProperty("npcName", npcName);
            }
            return json;
        }
    }
}
