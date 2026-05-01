package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MachineBlueprint(
        String id,
        String label,
        String category,
        String riskLevel,
        String footprint,
        int height,
        BlockPos origin,
        Direction facing,
        List<MachinePlacement> placements,
        Map<String, Integer> materialBudget,
        JsonArray entityRequirements,
        JsonArray fluidPaths,
        JsonArray redstonePaths,
        JsonArray maintenanceAccess,
        JsonArray testProcedure
) {
    public MachineBlueprint {
        id = textOrDefault(id, "pressure_door");
        label = textOrDefault(label, id);
        category = textOrDefault(category, "redstone");
        riskLevel = textOrDefault(riskLevel, "low");
        footprint = textOrDefault(footprint, "");
        origin = origin == null ? BlockPos.ZERO : origin.immutable();
        facing = facing == null || facing.getAxis().isVertical() ? Direction.NORTH : facing;
        placements = placements == null ? List.of() : List.copyOf(placements);
        materialBudget = materialBudget == null ? Map.of() : Map.copyOf(materialBudget);
        entityRequirements = entityRequirements == null ? new JsonArray() : entityRequirements.deepCopy();
        fluidPaths = fluidPaths == null ? new JsonArray() : fluidPaths.deepCopy();
        redstonePaths = redstonePaths == null ? new JsonArray() : redstonePaths.deepCopy();
        maintenanceAccess = maintenanceAccess == null ? new JsonArray() : maintenanceAccess.deepCopy();
        testProcedure = testProcedure == null ? new JsonArray() : testProcedure.deepCopy();
    }

    public boolean highRisk() {
        return "high".equalsIgnoreCase(riskLevel);
    }

    public int requiredPlacements() {
        int count = 0;
        for (MachinePlacement placement : placements) {
            if (!placement.optional()) {
                count++;
            }
        }
        return count;
    }

    public JsonObject materialBudgetJson() {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Integer> entry : new LinkedHashMap<>(materialBudget).entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        return json;
    }

    public JsonObject toJson(boolean includePlacements) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("label", label);
        json.addProperty("category", category);
        json.addProperty("riskLevel", riskLevel);
        json.addProperty("footprint", footprint);
        json.addProperty("height", height);
        json.addProperty("facing", facing.getName());
        json.addProperty("originX", origin.getX());
        json.addProperty("originY", origin.getY());
        json.addProperty("originZ", origin.getZ());
        json.addProperty("placements", placements.size());
        json.addProperty("requiredPlacements", requiredPlacements());
        json.add("materialBudget", materialBudgetJson());
        json.add("entityRequirements", entityRequirements.deepCopy());
        json.add("fluidPaths", fluidPaths.deepCopy());
        json.add("redstonePaths", redstonePaths.deepCopy());
        json.add("maintenanceAccess", maintenanceAccess.deepCopy());
        json.add("testProcedure", testProcedure.deepCopy());
        if (includePlacements) {
            JsonArray queue = new JsonArray();
            for (MachinePlacement placement : placements) {
                queue.add(placement.toJson());
            }
            json.add("placementQueue", queue);
        }
        return json;
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
