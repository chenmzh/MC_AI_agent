package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

import java.util.Locale;

public final class StructureBuildController {
    private static final int MAX_BLOCKS_PER_DIRECT_BUILD = 512;

    private StructureBuildController() {
    }

    public static ActionResult previewStructure(ServerPlayer player, String templateId, BlockPos origin, Direction facing, String style) {
        StructureBlueprint blueprint = BlueprintTemplateRegistry.create(templateId, player, origin, facing, style);
        if ("projection_placeholder".equals(blueprint.id())) {
            String message = "Projection/Litematica interface is reserved, but this version does not read projection files. Use an internal template or import a future neutral JSON blueprint.";
            NpcChat.say(player, message);
            return ActionResult.blocked("PROJECTION_PROVIDER_NOT_IMPLEMENTED", message,
                            "Use starter_cabin_7x7, storage_shed_5x7, bridge_3w, watchtower_5x5, farm_fence_9x9, or path_lights.")
                    .withObservation("blueprint", blueprint.toJson(false))
                    .withObservation("blueprintCatalog", BlueprintTemplateRegistry.catalogJson());
        }

        JsonObject site = validateSite(player, blueprint);
        JsonObject materialPlan = BlockPaletteResolver.materialPlan(player, blueprint);
        String message = "Blueprint preview: " + blueprint.label()
                + " at " + blueprint.origin().toShortString()
                + ", facing " + blueprint.facing().getName()
                + ", required=" + blueprint.requiredPlacements()
                + ", optional=" + blueprint.optionalPlacements()
                + ", missingBlocks=" + materialPlan.get("missingRequiredPlaceableBlocks").getAsInt()
                + ", blockedRequired=" + site.get("blockedRequired").getAsInt() + ".";
        NpcChat.say(player, message);
        return ActionResult.success("STRUCTURE_PREVIEW_READY", message)
                .withObservation("blueprint", blueprint.toJson(true))
                .withObservation("siteCheck", site)
                .withObservation("materialPlan", materialPlan)
                .withObservation("travelPolicy", TravelController.policyJson());
    }

    public static ActionResult buildStructure(ServerPlayer player, String templateId, BlockPos origin, Direction facing, String style, boolean autoGather) {
        Mob npc = NpcManager.activeNpcMob(player.getServer());
        if (npc == null) {
            return ActionResult.blocked("NO_NPC", "build_structure needs a spawned companion NPC.", "Spawn an NPC with /mcai npc spawn.");
        }

        StructureBlueprint blueprint = BlueprintTemplateRegistry.create(templateId, player, origin, facing, style);
        if ("projection_placeholder".equals(blueprint.id())) {
            String message = "Projection/Litematica interface is reserved, but this version does not read projection files. I can build built-in templates or future neutral JSON blueprints.";
            NpcChat.say(player, message);
            return ActionResult.blocked("PROJECTION_PROVIDER_NOT_IMPLEMENTED", message,
                            "Choose a built-in template for now: starter cabin, storage shed, bridge, watchtower, farm fence, or path lights.")
                    .withObservation("blueprint", blueprint.toJson(false));
        }

        JsonObject site = validateSite(player, blueprint);
        if (site.get("blockedRequired").getAsInt() > 0) {
            String message = "Build site is blocked by protected or occupied blocks. I will not clear chests, beds, furnaces, redstone, modded machines, or obvious base blocks.";
            NpcChat.say(player, message);
            TaskFeedback.warn(player, npc, "build_structure", "BUILD_SITE_BLOCKED", message);
            return ActionResult.blocked("BUILD_SITE_BLOCKED", message,
                            "Pick a clearer spot, preview the structure, or manually clear safe space.")
                    .withObservation("blueprint", blueprint.toJson(true))
                    .withObservation("siteCheck", site)
                    .withObservation("materialPlan", BlockPaletteResolver.materialPlan(player, blueprint));
        }

        JsonObject materialPlan = BlockPaletteResolver.materialPlan(player, blueprint);
        int missingBlocks = materialPlan.get("missingRequiredPlaceableBlocks").getAsInt();
        if (missingBlocks > 0 && autoGather) {
            ActionResult gather = MaterialGatherer.gatherMaterials(player, "placeable_blocks", blueprint.requiredPlacements());
            String message = "Structure materials are short by " + missingBlocks + " placeable blocks. I started material gathering; retry build_structure after the gather/collect loop finishes.";
            TaskFeedback.warn(player, npc, "build_structure", "BUILD_MATERIALS_GATHERING", message);
            return ActionResult.started("BUILD_MATERIALS_GATHERING", message)
                    .withObservation("blueprint", blueprint.toJson(false))
                    .withObservation("siteCheck", site)
                    .withObservation("materialPlan", materialPlan)
                    .withObservation("gatherResult", gather.toJson())
                    .withEffect("missing", missingBlocks)
                    .withEffect("nextRepair", "poll task feedback, collect drops, then call build_structure again");
        }
        if (missingBlocks > 0) {
            String message = "Need " + missingBlocks + " more placeable blocks for " + blueprint.label() + ".";
            NpcChat.say(player, message);
            return ActionResult.blocked("BUILD_MATERIALS_MISSING", message,
                            "Run gather_materials for placeable_blocks, approve a material chest, or choose a smaller template.")
                    .withObservation("blueprint", blueprint.toJson(false))
                    .withObservation("siteCheck", site)
                    .withObservation("materialPlan", materialPlan);
        }

        BuildExecution execution = executePlacementQueue(player, npc, blueprint);
        JsonObject resultJson = execution.toJson();
        if (execution.requiredMissingMaterials() > 0 || execution.requiredBlocked() > 0) {
            String message = "Structure build stopped: placed=" + execution.placed()
                    + ", requiredMissingMaterials=" + execution.requiredMissingMaterials()
                    + ", requiredBlocked=" + execution.requiredBlocked() + ".";
            NpcChat.say(player, message);
            TaskFeedback.warn(player, npc, "build_structure", "BUILD_PARTIAL_BLOCKED", message);
            return ActionResult.blocked("BUILD_PARTIAL_BLOCKED", message,
                            "Gather more matching blocks or choose a clearer site, then retry build_structure.")
                    .withObservation("blueprint", blueprint.toJson(false))
                    .withObservation("buildExecution", resultJson);
        }

        String message = "Built " + blueprint.label()
                + ": placed=" + execution.placed()
                + ", skippedExisting=" + execution.skippedExisting()
                + ", skippedOptional=" + execution.optionalSkipped()
                + ".";
        NpcChat.say(player, message);
        TaskFeedback.info(player, npc, "build_structure", "TASK_COMPLETE", message);
        return ActionResult.success("STRUCTURE_BUILT", message)
                .withObservation("blueprint", blueprint.toJson(false))
                .withObservation("buildExecution", resultJson)
                .withEffect("template", blueprint.id())
                .withEffect("placed", execution.placed())
                .withEffect("skippedOptional", execution.optionalSkipped());
    }

    public static ActionResult cancelStructure(ServerPlayer player) {
        NpcManager.stop(player);
        String message = "Cancelled active structure/build-related runtime task if one was running.";
        return ActionResult.success("STRUCTURE_CANCELLED", message);
    }

    private static BuildExecution executePlacementQueue(ServerPlayer player, Mob npc, StructureBlueprint blueprint) {
        ServerLevel level = player.serverLevel();
        int placed = 0;
        int skippedExisting = 0;
        int optionalSkipped = 0;
        int requiredMissingMaterials = 0;
        int requiredBlocked = 0;
        JsonArray samples = new JsonArray();
        for (BlueprintPlacement placement : blueprint.placements()) {
            if (placed >= MAX_BLOCKS_PER_DIRECT_BUILD) {
                requiredBlocked++;
                break;
            }

            BlockState existing = level.getBlockState(placement.pos());
            if (isAlreadyOccupiedByBuild(existing, placement)) {
                skippedExisting++;
                continue;
            }
            if (!canReplaceForBlueprint(level, placement.pos())) {
                if (placement.optional()) {
                    optionalSkipped++;
                } else {
                    requiredBlocked++;
                    addSample(samples, placement, "blocked_site");
                }
                continue;
            }

            BlockPaletteResolver.ResolvedBlock block = BlockPaletteResolver.consumeForPlacement(player, placement);
            if (block == null) {
                if (placement.optional()) {
                    optionalSkipped++;
                } else {
                    requiredMissingMaterials++;
                    addSample(samples, placement, "missing_material");
                }
                continue;
            }

            if (!placeResolvedBlock(level, npc, placement, block.state(), blueprint.facing())) {
                if (placement.optional()) {
                    optionalSkipped++;
                } else {
                    requiredBlocked++;
                    addSample(samples, placement, "cannot_survive_or_collision");
                }
                continue;
            }
            placed++;
            if (samples.size() < 12) {
                JsonObject sample = placement.toJson();
                sample.addProperty("placedItem", block.itemId());
                sample.addProperty("source", block.source());
                samples.add(sample);
            }
        }
        return new BuildExecution(placed, skippedExisting, optionalSkipped, requiredMissingMaterials, requiredBlocked, samples);
    }

    private static boolean placeResolvedBlock(ServerLevel level, Entity npc, BlueprintPlacement placement, BlockState baseState, Direction facing) {
        BlockPos pos = placement.pos();
        BlockState state = orient(baseState, placement.role(), facing);
        if (state.getBlock() instanceof DoorBlock) {
            BlockState lower = state;
            if (lower.hasProperty(DoorBlock.FACING)) {
                lower = lower.setValue(DoorBlock.FACING, facing.getOpposite());
            }
            if (lower.hasProperty(DoorBlock.HALF)) {
                lower = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            }
            BlockState upper = lower.hasProperty(DoorBlock.HALF)
                    ? lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
                    : lower;
            if (!canReplaceForBlueprint(level, pos) || !canReplaceForBlueprint(level, pos.above())) {
                return false;
            }
            if (!lower.canSurvive(level, pos)) {
                return false;
            }
            level.setBlock(pos, lower, 3);
            level.setBlock(pos.above(), upper, 3);
            return true;
        }

        if (!state.canSurvive(level, pos) && !placement.role().equals("foundation") && !placement.role().equals("path")) {
            return false;
        }
        if (!level.noCollision(npc, new AABB(pos)) && !placement.role().equals("foundation") && !placement.role().equals("path")) {
            return false;
        }
        level.setBlock(pos, state, 3);
        return true;
    }

    private static BlockState orient(BlockState state, String role, Direction facing) {
        if (state.getBlock() instanceof FenceGateBlock && state.hasProperty(FenceGateBlock.FACING)) {
            return state.setValue(FenceGateBlock.FACING, facing.getOpposite());
        }
        if (state.getBlock() instanceof HorizontalDirectionalBlock && state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return state.setValue(HorizontalDirectionalBlock.FACING, facing.getOpposite());
        }
        return state;
    }

    private static JsonObject validateSite(ServerPlayer player, StructureBlueprint blueprint) {
        ServerLevel level = player.serverLevel();
        int blockedRequired = 0;
        int blockedOptional = 0;
        int replaceable = 0;
        int existing = 0;
        JsonArray samples = new JsonArray();
        for (BlueprintPlacement placement : blueprint.placements()) {
            BlockState state = level.getBlockState(placement.pos());
            if (isAlreadyOccupiedByBuild(state, placement)) {
                existing++;
                continue;
            }
            if (canReplaceForBlueprint(level, placement.pos())) {
                replaceable++;
                continue;
            }
            if (placement.optional()) {
                blockedOptional++;
            } else {
                blockedRequired++;
            }
            if (samples.size() < 12) {
                JsonObject sample = placement.toJson();
                sample.addProperty("block", blockId(state));
                sample.addProperty("protected", isProtectedBlock(level, placement.pos(), state));
                samples.add(sample);
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("replaceableOrWater", replaceable);
        json.addProperty("alreadyOccupied", existing);
        json.addProperty("blockedRequired", blockedRequired);
        json.addProperty("blockedOptional", blockedOptional);
        json.addProperty("safeToBuildRequired", blockedRequired == 0);
        json.addProperty("policy", "does not clear containers, beds, furnaces, redstone, modded machines, or non-air site blocks");
        json.add("samples", samples);
        return json;
    }

    private static boolean canReplaceForBlueprint(ServerLevel level, BlockPos pos) {
        if (pos == null || !level.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(Blocks.WATER)) {
            return level.getBlockEntity(pos) == null;
        }
        return false;
    }

    private static boolean isAlreadyOccupiedByBuild(BlockState state, BlueprintPlacement placement) {
        if (state == null || state.isAir() || state.is(Blocks.WATER)) {
            return false;
        }
        String id = blockId(state);
        for (String candidate : placement.candidates()) {
            String normalized = candidate == null ? "" : candidate.toLowerCase(Locale.ROOT);
            if (id.equals(normalized)) {
                return true;
            }
            if (normalized.endsWith("_planks") && id.endsWith("_planks")) {
                return true;
            }
            if (normalized.endsWith("_log") && id.endsWith("_log")) {
                return true;
            }
            if (normalized.endsWith("_fence") && id.endsWith("_fence")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProtectedBlock(ServerLevel level, BlockPos pos, BlockState state) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity != null) {
            return true;
        }
        String id = blockId(state);
        return id.contains("chest")
                || id.contains("barrel")
                || id.contains("shulker")
                || id.contains("furnace")
                || id.contains("smoker")
                || id.contains("blast_furnace")
                || id.contains("bed")
                || id.contains("redstone")
                || id.contains("repeater")
                || id.contains("comparator")
                || id.contains("hopper")
                || id.contains("dispenser")
                || id.contains("dropper")
                || id.contains("crafter")
                || (!id.startsWith("minecraft:") && !id.equals("minecraft:air"));
    }

    private static void addSample(JsonArray samples, BlueprintPlacement placement, String reason) {
        if (samples.size() >= 12) {
            return;
        }
        JsonObject sample = placement.toJson();
        sample.addProperty("reason", reason);
        samples.add(sample);
    }

    private static String blockId(BlockState state) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().toLowerCase(Locale.ROOT);
    }

    private record BuildExecution(
            int placed,
            int skippedExisting,
            int optionalSkipped,
            int requiredMissingMaterials,
            int requiredBlocked,
            JsonArray samples
    ) {
        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("placed", placed);
            json.addProperty("skippedExisting", skippedExisting);
            json.addProperty("optionalSkipped", optionalSkipped);
            json.addProperty("requiredMissingMaterials", requiredMissingMaterials);
            json.addProperty("requiredBlocked", requiredBlocked);
            json.add("samples", samples);
            return json;
        }
    }
}
