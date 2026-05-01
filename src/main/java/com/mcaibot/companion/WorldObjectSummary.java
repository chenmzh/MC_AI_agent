package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class WorldObjectSummary {
    private static final int MAX_SCAN_RADIUS = 24;
    private static final int MAX_VERTICAL_RADIUS = 8;
    private static final int MAX_OUTPUT_OBJECTS = 16;

    private WorldObjectSummary() {
    }

    public static JsonObject snapshotFor(ServerPlayer player, JsonObject context) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", "mc-agent-world-objects-v1");
        root.addProperty("source", "live_scan_and_world_knowledge");

        int radius = Math.min(Math.max(McAiConfig.SCAN_RADIUS.get(), 16), MAX_SCAN_RADIUS);
        int verticalRadius = Math.min(MAX_VERTICAL_RADIUS, Math.max(5, radius / 3));
        root.addProperty("scanRadius", radius);
        root.addProperty("verticalRadius", verticalRadius);

        Scan scan = scan(player, radius, verticalRadius);
        root.add("structures", structuresJson(player, scan));
        root.add("doors", scan.doors);
        root.add("walls", wallsJson(scan));
        root.add("workstations", scan.workstations);
        root.add("containers", containersJson(player, scan, context));
        root.add("resourceClusters", resourceClustersJson(context));
        root.add("hazards", hazardsJson(player, radius));
        root.add("moddedObjects", moddedObjectsJson(context));
        return root;
    }

    private static Scan scan(ServerPlayer player, int radius, int verticalRadius) {
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        Scan scan = new Scan();

        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - radius,
                center.getY() - verticalRadius,
                center.getZ() - radius,
                center.getX() + radius,
                center.getY() + verticalRadius,
                center.getZ() + radius
        )) {
            BlockState state = level.getBlockState(pos);
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            double distance = distance(center, pos);

            if (state.getBlock() instanceof DoorBlock) {
                JsonObject door = positionedObject("door", blockId, pos, distance);
                scan.doors.add(door);
            }

            if (isWorkstation(state)) {
                JsonObject workstation = positionedObject("workstation", blockId, pos, distance);
                workstation.addProperty("kind", workstationKind(blockId));
                scan.workstations.add(workstation);
            }

            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Container container) {
                JsonObject containerJson = positionedObject("container", blockId, pos, distance);
                containerJson.addProperty("occupiedSlots", occupiedSlots(container));
                containerJson.addProperty("freeSlots", Math.max(0, container.getContainerSize() - occupiedSlots(container)));
                scan.containers.add(containerJson);
            }

            if (isLikelyStructureBlock(level, pos, state, blockId)) {
                scan.structureBlockCount++;
                scan.materialCounts.merge(blockId, 1, Integer::sum);
            }
        }

        return scan;
    }

    private static JsonArray structuresJson(ServerPlayer player, Scan scan) {
        JsonArray structures = new JsonArray();
        boolean hasStructureSignals = scan.structureBlockCount >= 24 || scan.doors.size() > 0 || scan.workstations.size() > 0;
        if (!hasStructureSignals) {
            return structures;
        }

        JsonObject structure = new JsonObject();
        structure.addProperty("type", "nearby_structure_candidate");
        structure.addProperty("status", "candidate");
        structure.addProperty("confidence", structureConfidence(scan));
        structure.add("anchor", blockPosJson(player.blockPosition()));
        structure.addProperty("structureBlockCount", scan.structureBlockCount);
        structure.addProperty("doorCount", scan.doors.size());
        structure.addProperty("workstationCount", scan.workstations.size());
        structure.add("dominantMaterials", dominantMaterialsJson(scan.materialCounts, 8));
        structure.addProperty("plannerHint", "If the player asks to repair/build around this object, inspect the structure first and ask for target clarification if multiple candidates exist.");
        structures.add(structure);
        return structures;
    }

    private static JsonArray wallsJson(Scan scan) {
        JsonArray walls = new JsonArray();
        if (scan.materialCounts.isEmpty()) {
            return walls;
        }

        JsonObject wall = new JsonObject();
        wall.addProperty("type", "wall_material_candidate");
        wall.addProperty("status", "material_summary");
        wall.add("dominantMaterials", dominantMaterialsJson(scan.materialCounts, 6));
        wall.addProperty("plannerHint", "Use dominant wall materials when repairing nearby wall gaps; verify exact placement with the repair_structure skill.");
        walls.add(wall);
        return walls;
    }

    private static JsonArray containersJson(ServerPlayer player, Scan scan, JsonObject context) {
        JsonArray existing = AgentJson.array(context, "nearbyContainers");
        if (existing.size() > 0) {
            return existing;
        }
        return limitArray(scan.containers, MAX_OUTPUT_OBJECTS);
    }

    private static JsonArray resourceClustersJson(JsonObject context) {
        JsonArray nearbyBlocks = AgentJson.array(context, "nearbyBlocks");
        JsonArray clusters = new JsonArray();
        for (JsonElement element : nearbyBlocks) {
            if (clusters.size() >= MAX_OUTPUT_OBJECTS) {
                break;
            }
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            String category = AgentJson.string(block, "category", "");
            if (!isResourceCategory(category)) {
                continue;
            }
            JsonObject cluster = block.deepCopy();
            cluster.addProperty("type", "resource_cluster");
            cluster.addProperty("plannerHint", "Use as a bounded target for gather or mine skills, then verify current reachability.");
            clusters.add(cluster);
        }
        return clusters;
    }

    private static JsonArray hazardsJson(ServerPlayer player, int radius) {
        JsonArray hazards = new JsonArray();
        AABB box = player.getBoundingBox().inflate(Math.max(radius, 32));
        for (Entity entity : player.serverLevel().getEntities(player, box, entity -> entity.isAlive()
                && entity.getType().getCategory() == MobCategory.MONSTER)) {
            if (hazards.size() >= MAX_OUTPUT_OBJECTS) {
                break;
            }
            JsonObject hazard = new JsonObject();
            hazard.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            hazard.addProperty("name", entity.getName().getString());
            hazard.addProperty("distance", round(entity.distanceTo(player)));
            hazard.add("position", blockPosJson(entity.blockPosition()));
            hazard.addProperty("plannerHint", "Protection may engage hostile mobs; never target player entities.");
            hazards.add(hazard);
        }
        return hazards;
    }

    private static JsonArray moddedObjectsJson(JsonObject context) {
        JsonObject modded = AgentJson.object(context, "modded");
        JsonArray blocks = AgentJson.array(modded, "nearbyBlocks");
        return limitArray(blocks, MAX_OUTPUT_OBJECTS);
    }

    private static boolean isLikelyStructureBlock(ServerLevel level, BlockPos pos, BlockState state, String blockId) {
        if (state.isAir() || state.getBlock() instanceof DoorBlock || isWorkstation(state)) {
            return false;
        }
        if (!state.isSolidRender(level, pos)) {
            return false;
        }
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS) || state.is(BlockTags.STONE_BRICKS)) {
            return true;
        }
        String id = blockId.toLowerCase(Locale.ROOT);
        return id.contains("brick")
                || id.contains("plank")
                || id.contains("log")
                || id.contains("stone")
                || id.contains("cobblestone")
                || id.contains("concrete")
                || id.contains("terracotta")
                || id.contains("glass")
                || id.contains("wall");
    }

    private static boolean isWorkstation(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.CRAFTING_TABLE
                || block == Blocks.FURNACE
                || block == Blocks.BLAST_FURNACE
                || block == Blocks.SMOKER
                || block == Blocks.STONECUTTER
                || block == Blocks.CARTOGRAPHY_TABLE
                || block == Blocks.FLETCHING_TABLE
                || block == Blocks.SMITHING_TABLE
                || block == Blocks.GRINDSTONE
                || block == Blocks.LOOM
                || block == Blocks.BREWING_STAND
                || block == Blocks.ENCHANTING_TABLE
                || block == Blocks.ANVIL
                || block == Blocks.CHIPPED_ANVIL
                || block == Blocks.DAMAGED_ANVIL;
    }

    private static String workstationKind(String blockId) {
        String id = blockId.toLowerCase(Locale.ROOT);
        if (id.contains("crafting_table")) {
            return "crafting";
        }
        if (id.contains("furnace") || id.contains("smoker")) {
            return "smelting";
        }
        return "utility";
    }

    private static boolean isResourceCategory(String category) {
        return switch (category) {
            case "logs", "ores", "stone", "block_entity" -> true;
            default -> false;
        };
    }

    private static JsonObject positionedObject(String type, String blockId, BlockPos pos, double distance) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        object.addProperty("block", blockId);
        object.add("position", blockPosJson(pos));
        object.addProperty("distance", round(distance));
        return object;
    }

    private static JsonArray dominantMaterialsJson(Map<String, Integer> counts, int limit) {
        JsonArray materials = new JsonArray();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("block", entry.getKey());
                    item.addProperty("count", entry.getValue());
                    materials.add(item);
                });
        return materials;
    }

    private static JsonArray limitArray(JsonArray input, int limit) {
        JsonArray output = new JsonArray();
        for (JsonElement element : input) {
            if (output.size() >= limit) {
                break;
            }
            output.add(element.deepCopy());
        }
        return output;
    }

    private static int occupiedSlots(Container container) {
        int occupied = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.getItem(slot).isEmpty()) {
                occupied++;
            }
        }
        return occupied;
    }

    private static double structureConfidence(Scan scan) {
        double confidence = 0.25;
        if (scan.structureBlockCount >= 48) {
            confidence += 0.35;
        } else if (scan.structureBlockCount >= 24) {
            confidence += 0.2;
        }
        if (scan.doors.size() > 0) {
            confidence += 0.25;
        }
        if (scan.workstations.size() > 0) {
            confidence += 0.15;
        }
        return Math.min(0.95, round(confidence));
    }

    private static JsonObject blockPosJson(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    private static double distance(BlockPos from, BlockPos to) {
        return Math.sqrt(from.distSqr(to));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static final class Scan {
        private int structureBlockCount;
        private final LinkedHashMap<String, Integer> materialCounts = new LinkedHashMap<>();
        private final JsonArray doors = new JsonArray();
        private final JsonArray workstations = new JsonArray();
        private final JsonArray containers = new JsonArray();
    }
}
