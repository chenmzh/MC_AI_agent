package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.List;

public record BlueprintPlacement(
        BlockPos pos,
        String role,
        List<String> candidates,
        boolean optional
) {
    public BlueprintPlacement {
        pos = pos == null ? BlockPos.ZERO : pos.immutable();
        role = role == null || role.isBlank() ? "block" : role.trim();
        candidates = candidates == null ? List.of("minecraft:oak_planks") : List.copyOf(candidates);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        json.addProperty("role", role);
        json.addProperty("optional", optional);
        JsonArray blocks = new JsonArray();
        for (String candidate : candidates) {
            blocks.add(candidate);
        }
        json.add("candidates", blocks);
        return json;
    }
}
