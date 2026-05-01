package com.mcaibot.companion;

import com.google.gson.JsonObject;

import java.util.UUID;

public record TaskResult(
        long timeMillis,
        String status,
        String severity,
        String taskName,
        String code,
        String message,
        String ownerName,
        UUID ownerUuid,
        UUID npcUuid,
        String npcName,
        JsonObject actionResult
) {
    public TaskResult {
        actionResult = actionResult == null ? new JsonObject() : actionResult.deepCopy();
    }

    public boolean isCompletion() {
        return "complete".equals(status);
    }

    public boolean isProblem() {
        return "failed".equals(status) || "blocked".equals(status);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("timeMillis", timeMillis);
        json.addProperty("status", status);
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
        if (!actionResult.isEmpty()) {
            json.add("actionResult", actionResult.deepCopy());
        }
        return json;
    }
}
