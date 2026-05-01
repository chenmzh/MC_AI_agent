package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record StructureBlueprint(
        String id,
        String label,
        String footprint,
        int height,
        BlockPos origin,
        Direction facing,
        List<BlueprintPlacement> placements,
        String provider,
        String style
) {
    public StructureBlueprint {
        id = textOrDefault(id, "starter_cabin_7x7");
        label = textOrDefault(label, id);
        footprint = textOrDefault(footprint, "");
        origin = origin == null ? BlockPos.ZERO : origin.immutable();
        facing = facing == null || facing.getAxis().isVertical() ? Direction.NORTH : facing;
        placements = placements == null ? List.of() : List.copyOf(placements);
        provider = textOrDefault(provider, "builtin_templates");
        style = textOrDefault(style, "rustic");
    }

    public int requiredPlacements() {
        int count = 0;
        for (BlueprintPlacement placement : placements) {
            if (!placement.optional()) {
                count++;
            }
        }
        return count;
    }

    public int optionalPlacements() {
        return placements.size() - requiredPlacements();
    }

    public JsonObject roleCountsJson() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BlueprintPlacement placement : placements) {
            counts.merge(placement.role(), 1, Integer::sum);
        }
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        return json;
    }

    public JsonObject materialBudgetJson() {
        Map<String, Integer> required = new LinkedHashMap<>();
        Map<String, Integer> optional = new LinkedHashMap<>();
        for (BlueprintPlacement placement : placements) {
            List<String> candidates = placement.candidates();
            String key = candidates.isEmpty() ? placement.role() : candidates.get(0);
            if (placement.optional()) {
                optional.merge(key, 1, Integer::sum);
            } else {
                required.merge(key, 1, Integer::sum);
            }
        }
        JsonObject root = new JsonObject();
        root.add("required", countsObject(required));
        root.add("optional", countsObject(optional));
        return root;
    }

    public JsonObject toJson(boolean includePlacements) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("label", label);
        json.addProperty("footprint", footprint);
        json.addProperty("height", height);
        json.addProperty("provider", provider);
        json.addProperty("style", style);
        json.addProperty("facing", facing.getName());
        json.addProperty("originX", origin.getX());
        json.addProperty("originY", origin.getY());
        json.addProperty("originZ", origin.getZ());
        json.addProperty("placements", placements.size());
        json.addProperty("requiredPlacements", requiredPlacements());
        json.addProperty("optionalPlacements", optionalPlacements());
        json.add("roles", roleCountsJson());
        json.add("materialBudget", materialBudgetJson());
        if (includePlacements) {
            JsonArray array = new JsonArray();
            for (BlueprintPlacement placement : placements) {
                array.add(placement.toJson());
            }
            json.add("placementQueue", array);
        }
        return json;
    }

    private static JsonObject countsObject(Map<String, Integer> counts) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        return json;
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
