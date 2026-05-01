package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

public final class MachineSafetyChecker {
    private MachineSafetyChecker() {
    }

    public static JsonObject validateSite(ServerPlayer player, MachineBlueprint blueprint) {
        ServerLevel level = player.serverLevel();
        JsonObject json = new JsonObject();
        JsonArray samples = new JsonArray();
        int blockedRequired = 0;
        int blockedOptional = 0;
        int protectedTargets = 0;
        int outsideWorldBorder = 0;
        for (MachinePlacement placement : blueprint.placements()) {
            BlockPos pos = placement.pos();
            if (!level.getWorldBorder().isWithinBounds(pos)) {
                outsideWorldBorder++;
                if (!placement.optional()) {
                    blockedRequired++;
                }
                addSample(samples, placement, "outside_world_border");
                continue;
            }
            BlockState existing = level.getBlockState(pos);
            if (sameBlock(existing, placement.state())) {
                continue;
            }
            if (isProtectedTarget(level, pos, existing)) {
                protectedTargets++;
                if (!placement.optional()) {
                    blockedRequired++;
                } else {
                    blockedOptional++;
                }
                addSample(samples, placement, "protected_target");
                continue;
            }
            if (!existing.isAir()) {
                if (placement.optional()) {
                    blockedOptional++;
                } else {
                    blockedRequired++;
                }
                addSample(samples, placement, "occupied");
            }
        }
        int nearbyProtected = blueprint.highRisk() ? nearbyProtectedBlocks(player, blueprint) : 0;
        if (nearbyProtected > 0) {
            blockedRequired += nearbyProtected;
        }
        json.addProperty("ok", blockedRequired == 0);
        json.addProperty("blockedRequired", blockedRequired);
        json.addProperty("blockedOptional", blockedOptional);
        json.addProperty("protectedTargets", protectedTargets);
        json.addProperty("nearbyProtectedBlocks", nearbyProtected);
        json.addProperty("outsideWorldBorder", outsideWorldBorder);
        json.addProperty("policy", "does not clear containers, beds, furnaces, existing redstone, modded machines, or nearby base-core blocks for high-risk machines");
        json.add("samples", samples);
        return json;
    }

    public static boolean canPlace(ServerLevel level, MachinePlacement placement) {
        BlockState existing = level.getBlockState(placement.pos());
        return sameBlock(existing, placement.state()) || (existing.isAir() && !isProtectedTarget(level, placement.pos(), existing));
    }

    private static int nearbyProtectedBlocks(ServerPlayer player, MachineBlueprint blueprint) {
        if (blueprint.placements().isEmpty()) {
            return 0;
        }
        ServerLevel level = player.serverLevel();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (MachinePlacement placement : blueprint.placements()) {
            BlockPos pos = placement.pos();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1)) {
            if (level.getBlockState(pos).isAir()) {
                continue;
            }
            if (isProtectedTarget(level, pos, level.getBlockState(pos))) {
                count++;
                if (count >= 8) {
                    return count;
                }
            }
        }
        return count;
    }

    private static boolean isProtectedTarget(ServerLevel level, BlockPos pos, BlockState state) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity != null) {
            return true;
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().toLowerCase(Locale.ROOT);
        return id.contains("chest")
                || id.contains("barrel")
                || id.contains("shulker")
                || id.contains("bed")
                || id.contains("furnace")
                || id.contains("smoker")
                || id.contains("blast_furnace")
                || id.contains("redstone")
                || id.contains("hopper")
                || id.contains("create")
                || id.contains("machine")
                || id.contains("controller")
                || id.contains("vault");
    }

    private static boolean sameBlock(BlockState existing, BlockState wanted) {
        return wanted != null && existing.is(wanted.getBlock());
    }

    private static void addSample(JsonArray samples, MachinePlacement placement, String reason) {
        if (samples.size() >= 12) {
            return;
        }
        JsonObject sample = placement.toJson();
        sample.addProperty("reason", reason);
        samples.add(sample);
    }
}
