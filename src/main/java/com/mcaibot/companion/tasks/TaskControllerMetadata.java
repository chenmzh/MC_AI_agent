package com.mcaibot.companion.tasks;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.util.List;

public record TaskControllerMetadata(
        String name,
        boolean parallelSafe,
        boolean worldChanging,
        String description,
        List<String> requirements,
        List<String> resources,
        List<String> locks,
        List<String> safety,
        JsonObject targetScopePolicy,
        List<String> effects,
        boolean legacyBacked
) {
    public TaskControllerMetadata {
        name = requireText(name, "name");
        description = requireText(description, "description");
        requirements = List.copyOf(requirements == null ? List.of() : requirements);
        resources = List.copyOf(resources == null ? List.of() : resources);
        locks = List.copyOf(locks == null ? List.of() : locks);
        safety = List.copyOf(safety == null ? List.of() : safety);
        targetScopePolicy = targetScopePolicy == null ? new JsonObject() : targetScopePolicy.deepCopy();
        effects = List.copyOf(effects == null ? List.of() : effects);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("parallelSafe", parallelSafe);
        json.addProperty("worldChanging", worldChanging);
        json.addProperty("description", description);
        json.add("requirements", stringArray(requirements));
        json.add("resources", stringArray(resources));
        json.add("locks", stringArray(locks));
        json.add("safety", stringArray(safety));
        json.add("targetScopePolicy", targetScopePolicy.deepCopy());
        json.add("effects", stringArray(effects));
        json.addProperty("legacyBacked", legacyBacked);
        return json;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                array.add(value);
            }
        }
        return array;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
