package com.mcaibot.companion;

import com.google.gson.JsonObject;

public record TaskRuntimeSnapshot(
        String taskName,
        boolean active,
        boolean paused,
        int stepsDone,
        int pauseSeconds,
        int searchRemainingSeconds,
        String ownerUuid,
        String targetBlock,
        String targetItemUuid,
        String taskId,
        String controllerName,
        String status,
        String phase,
        String blockerCode,
        String blockerReason
) {
    public TaskRuntimeSnapshot {
        taskName = textOrDefault(taskName, "idle");
        ownerUuid = textOrDefault(ownerUuid, "");
        targetBlock = textOrDefault(targetBlock, "");
        targetItemUuid = textOrDefault(targetItemUuid, "");
        taskId = textOrDefault(taskId, "");
        controllerName = textOrDefault(controllerName, active ? taskName : "");
        status = textOrDefault(status, active ? (paused ? "paused" : "running") : "idle");
        phase = textOrDefault(phase, active ? "executing" : "idle");
        blockerCode = textOrDefault(blockerCode, "");
        blockerReason = textOrDefault(blockerReason, "");
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", taskName);
        json.addProperty("taskName", taskName);
        json.addProperty("status", status);
        json.addProperty("active", active);
        json.addProperty("paused", paused);
        json.addProperty("stepsDone", stepsDone);
        json.addProperty("pauseSeconds", pauseSeconds);
        json.addProperty("searchRemainingSeconds", searchRemainingSeconds);
        if (!taskId.isBlank()) {
            json.addProperty("taskId", taskId);
        }
        if (!controllerName.isBlank()) {
            json.addProperty("controllerName", controllerName);
        }
        if (!phase.isBlank()) {
            json.addProperty("phase", phase);
        }
        if (!ownerUuid.isBlank()) {
            json.addProperty("ownerUuid", ownerUuid);
        }
        if (!targetBlock.isBlank()) {
            json.addProperty("targetBlock", targetBlock);
        }
        if (!targetItemUuid.isBlank()) {
            json.addProperty("targetItemUuid", targetItemUuid);
        }
        if (!blockerCode.isBlank()) {
            json.addProperty("blockerCode", blockerCode);
        }
        if (!blockerReason.isBlank()) {
            json.addProperty("blockerReason", blockerReason);
        }
        return json;
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
