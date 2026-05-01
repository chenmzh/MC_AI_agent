package com.mcaibot.companion;

import com.google.gson.JsonObject;

public record ActionCall(
        String name,
        JsonObject args,
        String targetNpc,
        String scope,
        String reason,
        String expectedEffect,
        String safetyLevel
) {
    public static ActionCall empty() {
        return new ActionCall("", new JsonObject(), "", "active", "", "", "normal");
    }

    public static ActionCall fromJson(JsonObject json) {
        if (json == null || json.isEmpty()) {
            return empty();
        }
        return new ActionCall(
                AgentJson.string(json, "name", ""),
                AgentJson.object(json, "args"),
                AgentJson.string(json, "targetNpc", ""),
                AgentJson.string(json, "scope", "active"),
                AgentJson.string(json, "reason", ""),
                AgentJson.string(json, "expectedEffect", ""),
                AgentJson.string(json, "safetyLevel", "normal")
        );
    }

    public boolean isPresent() {
        return !name.isBlank();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.add("args", args.deepCopy());
        json.addProperty("targetNpc", targetNpc);
        json.addProperty("scope", scope);
        json.addProperty("reason", reason);
        json.addProperty("expectedEffect", expectedEffect);
        json.addProperty("safetyLevel", safetyLevel);
        return json;
    }
}
