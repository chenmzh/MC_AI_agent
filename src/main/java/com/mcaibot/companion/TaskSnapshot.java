package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public record TaskSnapshot(
        String activeTaskName,
        boolean spawned,
        boolean following,
        boolean paused,
        int taskStepsDone,
        int taskPauseSeconds,
        int taskSearchRemainingSeconds,
        JsonArray allNpcs,
        JsonArray recentEvents,
        JsonArray latestResults
) {
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("activeTaskName", activeTaskName);
        json.addProperty("spawned", spawned);
        json.addProperty("following", following);
        json.addProperty("paused", paused);
        json.addProperty("taskStepsDone", taskStepsDone);
        json.addProperty("taskPauseSeconds", taskPauseSeconds);
        json.addProperty("taskSearchRemainingSeconds", taskSearchRemainingSeconds);
        json.add("allNpcs", allNpcs);
        json.add("recentEvents", recentEvents);
        json.add("latestResults", latestResults);
        return json;
    }
}
