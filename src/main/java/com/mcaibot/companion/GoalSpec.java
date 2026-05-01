package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public record GoalSpec(
        String intent,
        JsonArray successCriteria,
        JsonObject constraints,
        JsonObject permissions,
        JsonObject participants,
        int priority,
        boolean clarificationNeeded,
        String clarificationQuestion,
        String rawRequest
) {
    public static GoalSpec empty(String rawRequest) {
        return new GoalSpec(
                "",
                new JsonArray(),
                new JsonObject(),
                new JsonObject(),
                new JsonObject(),
                5,
                false,
                "",
                rawRequest == null ? "" : rawRequest
        );
    }

    public static GoalSpec fromJson(JsonObject json, String rawRequest) {
        if (json == null || json.isEmpty()) {
            return empty(rawRequest);
        }
        return new GoalSpec(
                AgentJson.string(json, "intent", ""),
                AgentJson.array(json, "successCriteria"),
                AgentJson.object(json, "constraints"),
                AgentJson.object(json, "permissions"),
                AgentJson.object(json, "participants"),
                AgentJson.integer(json, "priority", 5),
                AgentJson.bool(json, "clarificationNeeded", false),
                AgentJson.string(json, "clarificationQuestion", ""),
                AgentJson.string(json, "rawRequest", rawRequest == null ? "" : rawRequest)
        );
    }

    public boolean isPresent() {
        return !intent.isBlank() || !rawRequest.isBlank();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("intent", intent);
        json.add("successCriteria", successCriteria.deepCopy());
        json.add("constraints", constraints.deepCopy());
        json.add("permissions", permissions.deepCopy());
        json.add("participants", participants.deepCopy());
        json.addProperty("priority", priority);
        json.addProperty("clarificationNeeded", clarificationNeeded);
        json.addProperty("clarificationQuestion", clarificationQuestion);
        json.addProperty("rawRequest", rawRequest);
        return json;
    }
}
