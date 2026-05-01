package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public record ActionResult(
        String status,
        String code,
        String message,
        JsonObject effects,
        JsonObject observations,
        JsonArray blockers,
        boolean retryable,
        JsonArray suggestedRepairs
) {
    public ActionResult {
        status = textOrDefault(status, "failed");
        code = textOrDefault(code, "ACTION_RESULT");
        message = textOrDefault(message, "");
        effects = effects == null ? new JsonObject() : effects.deepCopy();
        observations = observations == null ? new JsonObject() : observations.deepCopy();
        blockers = blockers == null ? new JsonArray() : blockers.deepCopy();
        suggestedRepairs = suggestedRepairs == null ? new JsonArray() : suggestedRepairs.deepCopy();
    }

    public static ActionResult success(String code, String message) {
        return new ActionResult("success", code, message, new JsonObject(), new JsonObject(), new JsonArray(), false, new JsonArray());
    }

    public static ActionResult started(String code, String message) {
        return new ActionResult("started", code, message, new JsonObject(), new JsonObject(), new JsonArray(), true, new JsonArray());
    }

    public static ActionResult blocked(String code, String message, String repair) {
        JsonArray blockers = AgentJson.strings(code);
        JsonArray repairs = AgentJson.strings(repair);
        return new ActionResult("blocked", code, message, new JsonObject(), new JsonObject(), blockers, true, repairs);
    }

    public static ActionResult failed(String code, String message) {
        JsonArray blockers = AgentJson.strings(code);
        return new ActionResult("failed", code, message, new JsonObject(), new JsonObject(), blockers, false, new JsonArray());
    }

    public ActionResult withEffect(String key, String value) {
        JsonObject copy = effects.deepCopy();
        copy.addProperty(key, value == null ? "" : value);
        return new ActionResult(status, code, message, copy, observations, blockers, retryable, suggestedRepairs);
    }

    public ActionResult withEffect(String key, boolean value) {
        JsonObject copy = effects.deepCopy();
        copy.addProperty(key, value);
        return new ActionResult(status, code, message, copy, observations, blockers, retryable, suggestedRepairs);
    }

    public ActionResult withEffect(String key, Number value) {
        JsonObject copy = effects.deepCopy();
        copy.addProperty(key, value);
        return new ActionResult(status, code, message, copy, observations, blockers, retryable, suggestedRepairs);
    }

    public ActionResult withObservation(String key, String value) {
        JsonObject copy = observations.deepCopy();
        copy.addProperty(key, value == null ? "" : value);
        return new ActionResult(status, code, message, effects, copy, blockers, retryable, suggestedRepairs);
    }

    public ActionResult withObservation(String key, boolean value) {
        JsonObject copy = observations.deepCopy();
        copy.addProperty(key, value);
        return new ActionResult(status, code, message, effects, copy, blockers, retryable, suggestedRepairs);
    }

    public ActionResult withObservation(String key, Number value) {
        JsonObject copy = observations.deepCopy();
        copy.addProperty(key, value);
        return new ActionResult(status, code, message, effects, copy, blockers, retryable, suggestedRepairs);
    }

    public ActionResult withObservation(String key, JsonElement value) {
        JsonObject copy = observations.deepCopy();
        copy.add(key, value == null ? new JsonObject() : value.deepCopy());
        return new ActionResult(status, code, message, effects, copy, blockers, retryable, suggestedRepairs);
    }

    public ActionResult withObservations(JsonObject values) {
        JsonObject copy = observations.deepCopy();
        if (values != null) {
            for (String key : values.keySet()) {
                copy.add(key, values.get(key).deepCopy());
            }
        }
        return new ActionResult(status, code, message, effects, copy, blockers, retryable, suggestedRepairs);
    }

    public ActionResult withSuggestedRepair(String repair) {
        JsonArray repairs = suggestedRepairs.deepCopy();
        if (repair != null && !repair.isBlank()) {
            repairs.add(repair);
        }
        return new ActionResult(status, code, message, effects, observations, blockers, retryable, repairs);
    }

    public boolean isSuccess() {
        return "success".equals(status);
    }

    public boolean isStarted() {
        return "started".equals(status);
    }

    public boolean isBlocked() {
        return "blocked".equals(status);
    }

    public boolean isFailed() {
        return "failed".equals(status);
    }

    public boolean isTerminal() {
        return isSuccess() || isBlocked() || isFailed();
    }

    public boolean requiresReplan() {
        return isBlocked() || isFailed();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", "mc-agent-action-result-v1");
        json.addProperty("status", status);
        json.addProperty("code", code);
        json.addProperty("message", message);
        json.add("effects", effects.deepCopy());
        json.add("observations", observations.deepCopy());
        json.add("blockers", blockers.deepCopy());
        json.addProperty("retryable", retryable);
        json.add("suggestedRepairs", suggestedRepairs.deepCopy());
        json.addProperty("terminal", isTerminal());
        json.addProperty("requiresReplan", requiresReplan());
        json.addProperty("suggestedNextAction", suggestedNextAction());
        return json;
    }

    private String suggestedNextAction() {
        if (isSuccess()) {
            return "advance_task_graph";
        }
        if (isStarted()) {
            return "poll_task_feedback";
        }
        if (isBlocked()) {
            return retryable ? "repair_or_replan" : "ask_player_or_replan";
        }
        return "replan_or_stop";
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
