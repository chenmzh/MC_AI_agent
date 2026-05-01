package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Locale;
import java.util.Set;

public final class TargetResolver {
    private static final double LOOK_RANGE = 8.0D;
    private static final int NEAR_BLOCK_RADIUS = 8;
    private static final Set<String> TARGET_SPEC_ACTIONS = Set.of(
            "inspect_block",
            "break_block",
            "place_block",
            "use_mod_wrench",
            "inspect_mod_block",
            "repair_structure",
            "salvage_nearby_wood_structure",
            "preview_structure",
            "build_structure",
            "build_basic_house",
            "build_large_house",
            "preview_machine",
            "authorize_machine_plan",
            "build_machine",
            "test_machine",
            "build_redstone_template",
            "withdraw_from_chest",
            "deposit_to_chest",
            "deposit_item_to_chest"
    );

    private TargetResolver() {
    }

    public static boolean supportsTargetSpec(String actionName) {
        return TARGET_SPEC_ACTIONS.contains(normalize(actionName));
    }

    public static JsonObject contractJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", "mc-agent-target-resolver-v1");
        json.addProperty("coordinatePolicy", "coordinates are supported as explicit/debug input, but natural targets must use targetSpec first");
        json.add("sources", AgentJson.strings("current_position", "looking_at", "near_player", "inside_current_structure", "known_place", "resource_hint", "explicit_position"));
        json.add("supportedActions", AgentJson.strings(TARGET_SPEC_ACTIONS.toArray(String[]::new)));
        json.addProperty("confirmationPolicy", "high-confidence unique safe targets may execute; ambiguous, unsafe, or protected targets return NEEDS_CONFIRMATION/UNSAFE_TARGET");
        return json;
    }

    public static JsonObject contextSnapshot(ServerPlayer player) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", "mc-agent-target-grounding-v1");
        json.addProperty("playerFacing", horizontal(player.getDirection()).getName());
        json.add("playerPosition", blockPosJson(player.blockPosition()));
        json.add("feetBlock", describeBlock(player, player.blockPosition()));
        json.add("standingOn", describeBlock(player, player.blockPosition().below()));

        BlockHitResult hit = lookingAt(player);
        if (hit != null) {
            JsonObject look = describeBlock(player, hit.getBlockPos());
            look.addProperty("face", hit.getDirection().getName());
            look.addProperty("distance", round(Math.sqrt(player.blockPosition().distSqr(hit.getBlockPos()))));
            json.add("lookingAt", look);
        } else {
            json.add("lookingAt", new JsonObject());
        }

        json.add("currentStructureCandidate", currentStructureCandidate(player, McAiConfig.SCAN_RADIUS.get()));
        json.addProperty("plannerHint", "Use targetSpec instead of asking for coordinates when the player says this, here, what I am looking at, the house I am standing in, or nearby.");
        return json;
    }

    public static ResolvedTarget resolveBlockTarget(ServerPlayer player, JsonObject args, String actionName, boolean destructive) {
        TargetSpec spec = TargetSpec.fromArgs(args);
        BlockPos explicit = explicitPosition(args, spec);
        if (explicit != null) {
            return safetyCheck(player, ResolvedTarget.success("EXPLICIT_POSITION", "Using explicit target coordinates.", "block", explicit, null, 1.0D, spec), destructive, actionName);
        }

        if (!spec.isPresent()) {
            spec = new TargetSpec("looking_at", "block", "implicit crosshair target for " + actionName, "", "", null, null, new JsonObject());
        }

        BlockHitResult hit = lookingAt(player);
        if (spec.isSource("looking_at")) {
            if (hit == null) {
                return ResolvedTarget.blocked("TARGET_NOT_FOUND",
                        "I could not see a target block under the crosshair. Look at the block and repeat the command.",
                        spec);
            }
            return safetyCheck(player, ResolvedTarget.success("TARGET_RESOLVED_LOOKING_AT",
                    "Resolved target from the block the player is looking at.",
                    "block",
                    hit.getBlockPos(),
                    hit.getDirection(),
                    0.94D,
                    spec), destructive, actionName);
        }

        if (spec.isSource("current_position") || spec.isSource("near_player") || spec.isSource("inside_current_structure")) {
            BlockPos nearby = nearestMatchingBlock(player, spec, actionName);
            if (nearby != null) {
                return safetyCheck(player, ResolvedTarget.success("TARGET_RESOLVED_NEAR_PLAYER",
                        "Resolved target from a nearby object matching the request.",
                        "block",
                        nearby,
                        null,
                        0.78D,
                        spec), destructive, actionName);
            }
            return ResolvedTarget.blocked("TARGET_NOT_FOUND",
                    "I could not find a clear nearby target for that request. Look at the object or stand closer to it.",
                    spec);
        }

        if (spec.isSource("resource_hint")) {
            WorldKnowledge.KnownPosition hint = WorldKnowledge.nearestResourceHint(player,
                    spec.resourceCategory().isBlank() ? "logs" : spec.resourceCategory(),
                    TravelController.KNOWN_RESOURCE_MAX_DISTANCE,
                    20L * 60L * 30L);
            if (hint != null) {
                return safetyCheck(player, ResolvedTarget.success("TARGET_RESOLVED_RESOURCE_HINT",
                        "Resolved target from remembered resource hints.",
                        "resource",
                        hint.blockPos(),
                        null,
                        0.72D,
                        spec), destructive, actionName);
            }
            return ResolvedTarget.blocked("TARGET_NOT_FOUND",
                    "I do not have a known resource hint for that target yet.",
                    spec);
        }

        return ResolvedTarget.blocked("TARGET_NOT_FOUND",
                "I could not ground that target description in the current observation.",
                spec);
    }

    public static ResolvedTarget resolvePlacementTarget(ServerPlayer player, JsonObject args) {
        TargetSpec spec = TargetSpec.fromArgs(args);
        BlockPos explicit = explicitPosition(args, spec);
        if (explicit != null) {
            return safetyCheck(player, ResolvedTarget.success("EXPLICIT_POSITION", "Using explicit placement coordinates.", "placement", explicit, null, 1.0D, spec), false, "place_block");
        }

        if (!spec.isPresent()) {
            spec = new TargetSpec("looking_at", "placement", "implicit crosshair placement target", "", "", null, null, new JsonObject());
        }

        if (spec.isSource("looking_at")) {
            BlockHitResult hit = lookingAt(player);
            if (hit == null) {
                return ResolvedTarget.blocked("TARGET_NOT_FOUND",
                        "I could not see a placement surface. Look at a block face or say to place it here.",
                        spec);
            }
            BlockPos pos = hit.getBlockPos().relative(hit.getDirection());
            return safetyCheck(player, ResolvedTarget.success("TARGET_RESOLVED_LOOKING_AT",
                    "Resolved placement against the face the player is looking at.",
                    "placement",
                    pos,
                    hit.getDirection(),
                    0.92D,
                    spec), false, "place_block");
        }

        if (spec.isSource("current_position") || spec.isSource("near_player")) {
            Direction forward = horizontal(player.getDirection());
            BlockPos pos = firstReplaceable(player.serverLevel(),
                    player.blockPosition().relative(forward),
                    player.blockPosition().relative(forward).above(),
                    player.blockPosition().above());
            if (pos != null) {
                return safetyCheck(player, ResolvedTarget.success("TARGET_RESOLVED_CURRENT_POSITION",
                        "Resolved placement near the player's current position and facing.",
                        "placement",
                        pos,
                        forward,
                        0.82D,
                        spec), false, "place_block");
            }
        }

        return ResolvedTarget.blocked("TARGET_NOT_FOUND",
                "I could not find a safe nearby air block to place into. Look at a target face or move to a clearer spot.",
                spec);
    }

    public static ResolvedTarget resolveBuildAnchor(ServerPlayer player, JsonObject args, String templateId, Direction facing, boolean machine) {
        TargetSpec spec = TargetSpec.fromArgs(args);
        BlockPos explicit = explicitPosition(args, spec);
        if (explicit != null) {
            return ResolvedTarget.success("EXPLICIT_POSITION", "Using explicit build anchor.", "build_anchor", explicit, null, 1.0D, spec);
        }
        if (!spec.isPresent()) {
            return ResolvedTarget.success("DEFAULT_PLAYER_RELATIVE_ANCHOR",
                    "Using the normal player-facing build anchor.",
                    "build_anchor",
                    defaultBuildAnchor(player, templateId, facing, machine),
                    horizontal(facing),
                    0.82D,
                    spec);
        }
        if (spec.isSource("looking_at")) {
            BlockHitResult hit = lookingAt(player);
            if (hit == null) {
                return ResolvedTarget.blocked("TARGET_NOT_FOUND",
                        "I could not see a build anchor under the crosshair. Look at a clear anchor block or use here/current location.",
                        spec);
            }
            return ResolvedTarget.success("TARGET_RESOLVED_LOOKING_AT",
                    "Using the block the player is looking at as the build anchor.",
                    "build_anchor",
                    hit.getBlockPos(),
                    hit.getDirection(),
                    0.9D,
                    spec);
        }
        if (spec.isSource("current_position") || spec.isSource("near_player")) {
            return ResolvedTarget.success("TARGET_RESOLVED_PLAYER_RELATIVE",
                    "Using a player-facing anchor near the current position.",
                    "build_anchor",
                    defaultBuildAnchor(player, templateId, facing, machine),
                    horizontal(facing),
                    0.86D,
                    spec);
        }
        if (spec.isSource("inside_current_structure")) {
            return ResolvedTarget.success("TARGET_RESOLVED_CURRENT_STRUCTURE",
                    "Using the player's current structure position as the target anchor.",
                    "build_anchor",
                    player.blockPosition(),
                    horizontal(facing),
                    0.78D,
                    spec);
        }
        return ResolvedTarget.blocked("TARGET_NOT_FOUND",
                "I could not ground that build target. Stand near it, look at it, or use here/current location.",
                spec);
    }

    public static JsonObject targetSpecForSource(String source, String kind, String description) {
        JsonObject spec = new JsonObject();
        spec.addProperty("source", source);
        spec.addProperty("kind", kind);
        spec.addProperty("description", description);
        return spec;
    }

    private static BlockPos explicitPosition(JsonObject args, TargetSpec spec) {
        BlockPos fromSpec = spec == null ? null : spec.position();
        if (fromSpec != null) {
            return fromSpec;
        }
        if (args == null) {
            return null;
        }
        JsonObject position = args.has("position") && args.get("position").isJsonObject()
                ? args.getAsJsonObject("position")
                : args;
        Integer x = integer(position, "x");
        Integer y = integer(position, "y");
        Integer z = integer(position, "z");
        if (x == null || y == null || z == null) {
            return null;
        }
        return new BlockPos(x, y, z);
    }

    private static ResolvedTarget safetyCheck(ServerPlayer player, ResolvedTarget target, boolean destructive, String actionName) {
        if (!target.resolved() || target.position() == null) {
            return target;
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = target.position();
        JsonObject safety = new JsonObject();
        safety.addProperty("withinWorldBorder", level.getWorldBorder().isWithinBounds(pos));
        safety.addProperty("withinPrimitiveRange", Math.sqrt(player.blockPosition().distSqr(pos)) <= Math.max(McAiConfig.NPC_TASK_RADIUS.get(), LOOK_RANGE + 1.0D));
        BlockState state = level.getBlockState(pos);
        String blockId = blockId(state);
        safety.addProperty("block", blockId);
        safety.addProperty("air", state.isAir());
        safety.addProperty("hasBlockEntity", level.getBlockEntity(pos) != null || state.hasBlockEntity());
        safety.addProperty("protectedBlock", isProtectedBlock(level, pos, state));
        safety.addProperty("destructive", destructive);
        safety.addProperty("action", normalize(actionName));

        boolean unsafe = !safety.get("withinWorldBorder").getAsBoolean()
                || (destructive && (safety.get("hasBlockEntity").getAsBoolean() || safety.get("protectedBlock").getAsBoolean()));
        if (unsafe) {
            return new ResolvedTarget(
                    false,
                    "UNSAFE_TARGET",
                    "I resolved the target, but it is protected or unsafe for this action: " + blockId + " at " + pos.toShortString() + ".",
                    target.targetType(),
                    pos,
                    target.face(),
                    target.confidence(),
                    true,
                    false,
                    safety,
                    target.candidates(),
                    target.spec()
            );
        }
        return target.withSafety(safety, false);
    }

    private static BlockHitResult lookingAt(ServerPlayer player) {
        HitResult hit = player.pick(LOOK_RANGE, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit;
        }
        return null;
    }

    private static BlockPos nearestMatchingBlock(ServerPlayer player, TargetSpec spec, String actionName) {
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        String kind = (spec.kind() + " " + spec.description() + " " + actionName).toLowerCase(Locale.ROOT);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - NEAR_BLOCK_RADIUS,
                center.getY() - 4,
                center.getZ() - NEAR_BLOCK_RADIUS,
                center.getX() + NEAR_BLOCK_RADIUS,
                center.getY() + 5,
                center.getZ() + NEAR_BLOCK_RADIUS
        )) {
            BlockPos candidate = pos.immutable();
            BlockState state = level.getBlockState(candidate);
            if (!matchesKind(level, candidate, state, kind)) {
                continue;
            }
            double distance = center.distSqr(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static boolean matchesKind(ServerLevel level, BlockPos pos, BlockState state, String kind) {
        if (state.isAir()) {
            return false;
        }
        String id = blockId(state);
        if (kind.contains("chest") || kind.contains("container") || kind.contains("箱")) {
            return level.getBlockEntity(pos) instanceof Container || id.contains("chest") || id.contains("barrel") || id.contains("shulker");
        }
        if (kind.contains("door") || kind.contains("门")) {
            return state.getBlock() instanceof DoorBlock || id.endsWith("_door");
        }
        if (kind.contains("create") || kind.contains("wrench") || kind.contains("mod") || kind.contains("machine")) {
            return isSupportedModdedNamespace(namespace(id));
        }
        if (kind.contains("log") || kind.contains("wood") || kind.contains("tree") || kind.contains("木")) {
            return state.is(BlockTags.LOGS) || id.contains("log") || id.contains("wood") || id.contains("planks");
        }
        if (kind.contains("stone") || kind.contains("石")) {
            return id.contains("stone") || id.contains("cobblestone");
        }
        return true;
    }

    private static JsonObject currentStructureCandidate(ServerPlayer player, int requestedRadius) {
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        int radius = Math.max(6, Math.min(16, requestedRadius));
        int structureBlocks = 0;
        int protectedBlocks = 0;
        JsonArray samples = new JsonArray();
        for (BlockPos pos : BlockPos.betweenClosed(center.getX() - radius, center.getY() - 4, center.getZ() - radius,
                center.getX() + radius, center.getY() + 6, center.getZ() + radius)) {
            BlockPos candidate = pos.immutable();
            BlockState state = level.getBlockState(candidate);
            if (isStructureBlock(level, candidate, state)) {
                structureBlocks++;
                if (isProtectedBlock(level, candidate, state)) {
                    protectedBlocks++;
                }
                if (samples.size() < 8) {
                    samples.add(describeBlock(player, candidate));
                }
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("present", structureBlocks >= 24);
        json.addProperty("confidence", structureBlocks >= 48 ? 0.8D : structureBlocks >= 24 ? 0.62D : 0.25D);
        json.add("anchor", blockPosJson(center));
        json.addProperty("structureBlockCount", structureBlocks);
        json.addProperty("protectedBlockCount", protectedBlocks);
        json.addProperty("policyHint", "destructive structure actions must skip protected blocks and block entities");
        json.add("samples", samples);
        return json;
    }

    private static boolean isStructureBlock(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return false;
        }
        String id = blockId(state);
        return state.isSolidRender(level, pos)
                && (state.is(BlockTags.PLANKS)
                || state.is(BlockTags.LOGS)
                || id.contains("brick")
                || id.contains("plank")
                || id.contains("log")
                || id.contains("stone")
                || id.contains("cobblestone")
                || id.contains("glass")
                || id.contains("wall"));
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

    private static BlockPos defaultBuildAnchor(ServerPlayer player, String templateId, Direction facing, boolean machine) {
        Direction buildFacing = horizontal(facing);
        return machine
                ? MachineTemplateRegistry.defaultOrigin(templateId, player, buildFacing)
                : BlueprintTemplateRegistry.defaultOrigin(templateId, player, buildFacing);
    }

    private static BlockPos firstReplaceable(ServerLevel level, BlockPos... positions) {
        for (BlockPos pos : positions) {
            BlockState state = level.getBlockState(pos);
            if ((state.isAir() || state.is(Blocks.WATER)) && level.getBlockEntity(pos) == null) {
                return pos.immutable();
            }
        }
        return null;
    }

    private static JsonObject describeBlock(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        BlockState state = level.getBlockState(pos);
        JsonObject json = new JsonObject();
        json.add("position", blockPosJson(pos));
        json.addProperty("block", blockId(state));
        json.addProperty("air", state.isAir());
        json.addProperty("hasBlockEntity", level.getBlockEntity(pos) != null || state.hasBlockEntity());
        json.addProperty("protected", isProtectedBlock(level, pos, state));
        json.addProperty("distance", round(Math.sqrt(player.blockPosition().distSqr(pos))));
        return json;
    }

    private static Direction horizontal(Direction direction) {
        if (direction == null || direction.getAxis().isVertical()) {
            return Direction.NORTH;
        }
        return direction;
    }

    private static Integer integer(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return (int) Math.floor(object.get(key).getAsDouble());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().toLowerCase(Locale.ROOT);
    }

    private static String namespace(String id) {
        int split = id.indexOf(':');
        return split <= 0 ? "minecraft" : id.substring(0, split);
    }

    private static boolean isSupportedModdedNamespace(String namespace) {
        return namespace.equals("create")
                || namespace.equals("aeronautics")
                || namespace.equals("createaddition")
                || namespace.equals("create_connected")
                || namespace.equals("create_central_kitchen")
                || namespace.equals("create_enchantment_industry")
                || namespace.equals("create_stuff_additions")
                || namespace.equals("createdragonsplus")
                || namespace.contains("create");
    }

    private static JsonObject blockPosJson(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
