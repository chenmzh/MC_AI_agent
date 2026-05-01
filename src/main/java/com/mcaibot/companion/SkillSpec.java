package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public record SkillSpec(
        String name,
        String type,
        String description,
        JsonArray preconditions,
        JsonArray effects,
        JsonArray requiredContext,
        JsonArray permissions,
        String executor,
        String verifier,
        JsonArray repairStrategies,
        boolean parallelSafe,
        String safetyLevel
) {
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("type", type);
        json.addProperty("description", description);
        json.add("preconditions", preconditions.deepCopy());
        json.add("effects", effects.deepCopy());
        json.add("requiredContext", requiredContext.deepCopy());
        json.add("permissions", permissions.deepCopy());
        json.addProperty("executor", executor);
        json.addProperty("verifier", verifier);
        json.add("repairStrategies", repairStrategies.deepCopy());
        json.addProperty("parallelSafe", parallelSafe);
        json.addProperty("safetyLevel", safetyLevel);
        return json;
    }
}
