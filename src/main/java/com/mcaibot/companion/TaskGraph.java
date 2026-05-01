package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.UUID;

public record TaskGraph(
        String id,
        String goal,
        String status,
        JsonArray nodes,
        String currentNodeId,
        String summary
) {
    public static TaskGraph empty(String goal) {
        return new TaskGraph("taskgraph-" + UUID.randomUUID(), goal == null ? "" : goal, "draft", new JsonArray(), "", "");
    }

    public static TaskGraph fromJson(JsonObject json) {
        if (json == null || json.isEmpty()) {
            return empty("");
        }
        return new TaskGraph(
                AgentJson.string(json, "id", "taskgraph-" + UUID.randomUUID()),
                AgentJson.string(json, "goal", ""),
                AgentJson.string(json, "status", "draft"),
                AgentJson.array(json, "nodes"),
                AgentJson.string(json, "currentNodeId", ""),
                AgentJson.string(json, "summary", "")
        );
    }

    public boolean isPresent() {
        return !goal.isBlank() || !nodes.isEmpty();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("goal", goal);
        json.addProperty("status", status);
        json.add("nodes", nodes.deepCopy());
        json.addProperty("currentNodeId", currentNodeId);
        json.addProperty("summary", summary);
        return json;
    }

    public static JsonObject node(String id, String skill, String action, String status, JsonArray dependsOn, String repairFor) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("skill", skill);
        json.addProperty("action", action);
        json.addProperty("status", status);
        json.add("dependsOn", dependsOn == null ? new JsonArray() : dependsOn.deepCopy());
        json.addProperty("repairFor", repairFor == null ? "" : repairFor);
        return json;
    }
}
