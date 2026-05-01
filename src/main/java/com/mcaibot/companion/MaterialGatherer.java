package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

public final class MaterialGatherer {
    private static final int DEFAULT_REQUEST_COUNT = 64;
    private static final int LOCAL_NATURAL_SCAN_RADIUS = 32;
    private static final int LOCAL_VERTICAL_SCAN_RADIUS = 8;

    private MaterialGatherer() {
    }

    public static ActionResult gatherMaterials(ServerPlayer player, String material, int requestedCount) {
        String category = normalizeMaterial(material);
        int target = requestedCount <= 0 ? DEFAULT_REQUEST_COUNT : Math.min(requestedCount, 2304);
        int available = BlockPaletteResolver.countCategory(player, category);
        int missing = Math.max(0, target - available);
        JsonObject plan = basePlan(player, category, target, available, missing);
        if (missing <= 0) {
            String message = "Material request satisfied from NPC storage and approved containers: " + category + "=" + available + "/" + target + ".";
            NpcChat.say(player, message);
            return ActionResult.success("MATERIALS_READY", message)
                    .withObservation("materialPlan", plan)
                    .withEffect("collected", available)
                    .withEffect("missing", 0)
                    .withEffect("source", "npc_storage_or_approved_container");
        }

        return switch (category) {
            case "logs", "placeable_blocks", "large_placeable_blocks" -> gatherLogs(player, category, target, available, missing, plan);
            case "stone" -> gatherStone(player, target, available, missing, plan);
            case "sand" -> gatherNatural(player, category, target, available, missing, plan);
            case "dirt" -> gatherNatural(player, category, target, available, missing, plan);
            case "glass_like" -> gatherGlassLike(player, target, available, missing, plan);
            case "redstone_components", "hoppers", "chests", "water_buckets", "lava_buckets", "beds", "workstations", "trapdoors", "slabs" ->
                    ActionResult.blocked("MACHINE_MATERIAL_SOURCE_REQUIRED",
                                    "Machine material category '" + category + "' is short by " + missing + ". This stage reports the gap but does not deep-mine or run complex autocrafting chains.",
                                    "Provide the item in NPC storage, approve a nearby material chest, or ask for a smaller/safer machine template.")
                            .withObservation("materialPlan", plan)
                            .withEffect("material", category)
                            .withEffect("target", target)
                            .withEffect("collected", available)
                            .withEffect("missing", missing)
                            .withEffect("source", "npc_storage_or_approved_container_required");
            default -> ActionResult.blocked("UNKNOWN_MATERIAL_CATEGORY",
                            "Unsupported material category '" + material + "'. Supported: logs, stone, sand, dirt, glass_like, placeable_blocks, redstone_components, hoppers, chests, water_buckets, lava_buckets, beds, workstations, trapdoors, slabs, large_placeable_blocks.",
                            "Ask for a supported material category or a specific blueprint template.")
                    .withObservation("materialPlan", plan);
        };
    }

    private static ActionResult gatherLogs(ServerPlayer player, String category, int target, int available, int missing, JsonObject plan) {
        int seconds = TravelController.DEFAULT_TIME_BUDGET_SECONDS;
        int radius = Math.min(TravelController.SCOUT_RING_MAX_RADIUS, Math.max(McAiConfig.NPC_TASK_RADIUS.get(), 32));
        NpcManager.harvestLogs(player, radius, seconds);
        String message = "Need " + missing + " more " + category + ". Starting bounded wood gathering: known resource hints up to "
                + TravelController.KNOWN_RESOURCE_MAX_DISTANCE + " blocks, then medium scouting up to "
                + TravelController.SCOUT_RING_MAX_RADIUS + " blocks for " + seconds + " seconds.";
        NpcChat.say(player, message);
        return ActionResult.started("GATHER_MATERIALS_STARTED", message)
                .withObservation("materialPlan", plan)
                .withEffect("material", category)
                .withEffect("target", target)
                .withEffect("collected", available)
                .withEffect("missing", missing)
                .withEffect("source", "known_resource_hints_then_bounded_scouting")
                .withEffect("nextRepair", "poll task feedback, collect drops, then retry build_structure or gather_materials");
    }

    private static ActionResult gatherStone(ServerPlayer player, int target, int available, int missing, JsonObject plan) {
        int count = Math.min(Math.max(missing, 3), McAiConfig.NPC_MAX_TASK_STEPS.get());
        NpcManager.gatherStone(player, Math.min(McAiConfig.NPC_TASK_RADIUS.get(), 32), count);
        String message = "Need " + missing + " more stone-like blocks. Starting nearby exposed stone gathering; first version will not deep-mine caves.";
        NpcChat.say(player, message);
        return ActionResult.started("GATHER_MATERIALS_STARTED", message)
                .withObservation("materialPlan", plan)
                .withEffect("material", "stone")
                .withEffect("target", target)
                .withEffect("collected", available)
                .withEffect("missing", missing)
                .withEffect("source", "nearby_exposed_stone")
                .withEffect("nextRepair", "poll task feedback, collect drops, then retry");
    }

    private static ActionResult gatherNatural(ServerPlayer player, String category, int target, int available, int missing, JsonObject plan) {
        Mob npc = NpcManager.activeNpcMob(player.getServer());
        if (npc == null) {
            return ActionResult.blocked("NO_NPC", "gather_materials needs a spawned companion NPC.", "Spawn an NPC with /mcai npc spawn.")
                    .withObservation("materialPlan", plan);
        }

        ServerLevel level = player.serverLevel();
        int broken = 0;
        JsonArray visited = new JsonArray();
        BlockPos center = player.blockPosition();
        int cap = Math.min(missing, McAiConfig.NPC_MAX_TASK_STEPS.get());
        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - LOCAL_NATURAL_SCAN_RADIUS,
                center.getY() - LOCAL_VERTICAL_SCAN_RADIUS,
                center.getZ() - LOCAL_NATURAL_SCAN_RADIUS,
                center.getX() + LOCAL_NATURAL_SCAN_RADIUS,
                center.getY() + LOCAL_VERTICAL_SCAN_RADIUS,
                center.getZ() + LOCAL_NATURAL_SCAN_RADIUS
        )) {
            if (broken >= cap) {
                break;
            }
            BlockState state = level.getBlockState(pos);
            if (!matchesNaturalCategory(category, state) || level.getBlockEntity(pos) != null || !level.getWorldBorder().isWithinBounds(pos)) {
                continue;
            }
            if (pos.distSqr(center) > LOCAL_NATURAL_SCAN_RADIUS * LOCAL_NATURAL_SCAN_RADIUS) {
                continue;
            }
            if (level.destroyBlock(pos, true, npc)) {
                broken++;
                if (visited.size() < 12) {
                    JsonObject sample = new JsonObject();
                    sample.addProperty("x", pos.getX());
                    sample.addProperty("y", pos.getY());
                    sample.addProperty("z", pos.getZ());
                    visited.add(sample);
                }
            }
        }

        plan.add("visitedTargets", visited);
        if (broken <= 0) {
            String message = "I did not find safe nearby " + category + " within " + LOCAL_NATURAL_SCAN_RADIUS + " blocks.";
            NpcChat.say(player, message);
            return ActionResult.blocked("MATERIAL_NOT_FOUND_NEARBY", message,
                            "Move near exposed " + category + ", approve a container with the material, or ask for a different build palette.")
                    .withObservation("materialPlan", plan)
                    .withEffect("material", category)
                    .withEffect("target", target)
                    .withEffect("collected", available)
                    .withEffect("missing", missing)
                    .withEffect("source", "local_scan_exhausted");
        }

        NpcManager.collectItems(player, Math.min(McAiConfig.NPC_TASK_RADIUS.get(), 32));
        String message = "Broke " + broken + " safe nearby " + category + " block(s) and started collecting drops.";
        NpcChat.say(player, message);
        return ActionResult.started("GATHER_MATERIALS_STARTED", message)
                .withObservation("materialPlan", plan)
                .withEffect("material", category)
                .withEffect("target", target)
                .withEffect("collected", available + broken)
                .withEffect("missing", Math.max(0, target - available - broken))
                .withEffect("source", "nearby_safe_natural_blocks")
                .withEffect("nextRepair", "poll collection feedback, then retry");
    }

    private static ActionResult gatherGlassLike(ServerPlayer player, int target, int available, int missing, JsonObject plan) {
        JsonObject glassPlan = plan.deepCopy();
        glassPlan.addProperty("blocker", "glass_like requires existing glass/panes or sand smelting; smelting is not automated in this stage");
        ActionResult sandResult = gatherMaterials(player, "sand", missing);
        if (sandResult.isStarted()) {
            return sandResult.withObservation("glassLikePlan", glassPlan)
                    .withSuggestedRepair("Smelt gathered sand into glass manually or add an approved container with glass/panes.");
        }
        return ActionResult.blocked("GLASS_SMELTING_NOT_AUTOMATED",
                        "Glass-like material is short by " + missing + ". I can gather sand, but this stage does not automate smelting glass yet.",
                        "Provide glass/panes in NPC storage or approved containers, or use a template that can skip windows.")
                .withObservation("materialPlan", glassPlan)
                .withEffect("material", "glass_like")
                .withEffect("target", target)
                .withEffect("collected", available)
                .withEffect("missing", missing);
    }

    private static JsonObject basePlan(ServerPlayer player, String category, int target, int available, int missing) {
        JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", "mc-agent-material-plan-v1");
        plan.addProperty("material", category);
        plan.addProperty("target", target);
        plan.addProperty("collected", available);
        plan.addProperty("missing", missing);
        plan.addProperty("sourcePriority", "NPC inventory -> approved containers -> WorldKnowledge.resourceHints -> bounded scouting");
        plan.addProperty("maxKnownResourceTravelBlocks", TravelController.KNOWN_RESOURCE_MAX_DISTANCE);
        plan.addProperty("maxScoutRadiusBlocks", TravelController.SCOUT_RING_MAX_RADIUS);
        plan.addProperty("timeBudgetSeconds", TravelController.DEFAULT_TIME_BUDGET_SECONDS);
        plan.addProperty("sameDimensionOnly", true);
        plan.add("inventorySnapshot", BlockPaletteResolver.snapshot(player).toJson());
        plan.add("travel", TravelController.travelState(player, player.blockPosition(), "gather_" + category));
        return plan;
    }

    private static boolean matchesNaturalCategory(String category, BlockState state) {
        return switch (category) {
            case "sand" -> state.is(Blocks.SAND) || state.is(Blocks.RED_SAND);
            case "dirt" -> state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL);
            default -> false;
        };
    }

    public static String normalizeMaterial(String material) {
        String normalized = material == null || material.isBlank()
                ? "placeable_blocks"
                : material.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "wood", "log", "logs", "木头", "原木" -> "logs";
            case "stone", "cobble", "cobblestone", "石头", "圆石" -> "stone";
            case "sand", "沙子" -> "sand";
            case "dirt", "soil", "土", "泥土" -> "dirt";
            case "glass", "glass_like", "window", "玻璃" -> "glass_like";
            case "blocks", "placeable", "placeable_blocks", "building_blocks", "材料", "建筑材料" -> "placeable_blocks";
            case "large_placeable", "large_placeable_blocks", "machine_blocks" -> "large_placeable_blocks";
            case "redstone", "redstone_components", "redstone_dust" -> "redstone_components";
            case "hopper", "hoppers" -> "hoppers";
            case "chest", "chests", "barrel", "barrels" -> "chests";
            case "water_bucket", "water_buckets" -> "water_buckets";
            case "lava_bucket", "lava_buckets" -> "lava_buckets";
            case "bed", "beds" -> "beds";
            case "workstation", "workstations", "job_site", "job_sites" -> "workstations";
            case "trapdoor", "trapdoors" -> "trapdoors";
            case "slab", "slabs" -> "slabs";
            default -> normalized;
        };
    }
}
