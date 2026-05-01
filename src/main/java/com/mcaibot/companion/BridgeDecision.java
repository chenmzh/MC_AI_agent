package com.mcaibot.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public record BridgeDecision(String reply, Action action, GoalSpec goalSpec, ActionCall actionCall, TaskGraph taskGraph) {
    public BridgeDecision(String reply, Action action) {
        this(reply, action, GoalSpec.empty(firstNonBlank(action == null ? null : action.message(), reply, "")), ActionCall.empty(), TaskGraph.empty(""));
    }

    public static BridgeDecision fromJson(JsonObject json) {
        String reply = stringOrNull(json.get("reply"));
        JsonObject actionJson = json.has("action") && json.get("action").isJsonObject()
                ? json.getAsJsonObject("action")
                : new JsonObject();

        Action action = new Action(
                stringOrDefault(actionJson.get("name"), "none"),
                stringOrNull(actionJson.get("player")),
                stringOrNull(actionJson.get("message")),
                positionOrNull(actionJson.get("position")),
                numberOrNull(actionJson.get("range")),
                numberOrNull(actionJson.get("radius")),
                numberOrNull(actionJson.get("durationSeconds")),
                stringOrNull(actionJson.get("key")),
                stringOrNull(actionJson.get("value")),
                firstString(actionJson, "profileId", "profile", "npcId"),
                firstString(actionJson, "npcName", "name", "displayName"),
                firstString(actionJson, "personality", "persona"),
                firstString(actionJson, "style", "behaviorStyle"),
                firstString(actionJson, "defaultRole", "role"),
                firstString(actionJson, "behaviorPreference", "behavior", "preference"),
                firstString(actionJson, "item", "itemName", "targetItem"),
                firstString(actionJson, "block", "blockName", "targetBlock"),
                firstString(actionJson, "targetScope", "scope", "teamScope"),
                objectOrNull(actionJson.get("targetSpec")),
                numberOrNull(actionJson.get("count"))
        );
        String rawRequest = firstNonBlank(action.message(), action.value(), action.key(), reply, "");
        GoalSpec goalSpec = GoalSpec.fromJson(objectOrNull(json.get("goalSpec")), rawRequest);
        ActionCall actionCall = ActionCall.fromJson(objectOrNull(json.get("actionCall")));
        TaskGraph taskGraph = TaskGraph.fromJson(objectOrNull(json.get("taskGraph")));
        return new BridgeDecision(reply, action, goalSpec, actionCall, taskGraph);
    }

    private static String stringOrNull(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsString();
    }

    private static String stringOrDefault(JsonElement element, String fallback) {
        String value = stringOrNull(element);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Double numberOrNull(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsDouble();
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = stringOrNull(object.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static JsonObject objectOrNull(JsonElement element) {
        return element != null && !element.isJsonNull() && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static Position positionOrNull(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }

        JsonObject object = element.getAsJsonObject();
        Double x = numberOrNull(object.get("x"));
        Double y = numberOrNull(object.get("y"));
        Double z = numberOrNull(object.get("z"));
        if (x == null || y == null || z == null) {
            return null;
        }
        return new Position(x, y, z);
    }

    public record Action(
            String name,
            String player,
            String message,
            Position position,
            Double range,
            Double radius,
            Double durationSeconds,
            String key,
            String value,
            String profileId,
            String npcName,
            String personality,
            String style,
            String defaultRole,
            String behaviorPreference,
            String item,
            String block,
            String targetScope,
            JsonObject targetSpec,
            Double count
    ) {
    }

    public record Position(double x, double y, double z) {
    }
}
