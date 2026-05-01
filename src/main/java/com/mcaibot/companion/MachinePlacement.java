package com.mcaibot.companion;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

public record MachinePlacement(
        BlockPos pos,
        String role,
        BlockState state,
        boolean optional
) {
    public MachinePlacement {
        pos = pos == null ? BlockPos.ZERO : pos.immutable();
        role = role == null || role.isBlank() ? "machine_block" : role.trim();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        json.addProperty("role", role);
        json.addProperty("optional", optional);
        json.addProperty("block", blockId());
        return json;
    }

    public String blockId() {
        return state == null
                ? "minecraft:air"
                : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
