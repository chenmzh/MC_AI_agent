package com.mcaibot.companion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PlanSkillLibrary {
    static final String PREPARE_BASIC_TOOLS = "prepare_basic_tools";
    static final String PREPARE_BUILD_MATERIALS = "prepare_build_materials";
    static final String GATHER_MATERIALS = "gather_materials";
    static final String GATHER_WOOD = "gather_wood";
    static final String GATHER_STONE = "gather_stone";
    static final String MINE_RESOURCES = "mine_resources";
    static final String COLLECT_DROPS = "collect_drops";
    static final String CRAFT_AXE = "craft_axe";
    static final String CRAFT_PICKAXE = "craft_pickaxe";
    static final String CRAFT_PLANKS = "craft_planks";
    static final String CRAFT_STICKS = "craft_sticks";
    static final String CRAFT_STONE_AXE = "craft_stone_axe";
    static final String CRAFT_STONE_PICKAXE = "craft_stone_pickaxe";
    static final String BUILD_BASIC_SHELTER = "build_basic_shelter";
    static final String BUILD_LARGE_HOUSE = "build_large_house";
    static final String REPAIR_STRUCTURE = "repair_structure";
    static final String EQUIP_GEAR = "equip_gear";
    static final String DEPOSIT_STORAGE = "deposit_storage";
    static final String PROTECT_PLAYER = "protect_player";
    static final String CREATE_INSPECT = "create_inspect";
    static final String CREATE_WRENCH = "create_wrench";

    private PlanSkillLibrary() {
    }

    static PlanBlueprint blueprintFor(String requestedGoal, String message) {
        String goal = normalize(firstNonBlank(requestedGoal, inferGoal(message)));
        return switch (goal) {
            case "tools", "basic_tools", "prepare_tools", PREPARE_BASIC_TOOLS ->
                    new PlanBlueprint(PREPARE_BASIC_TOOLS, List.of(PREPARE_BASIC_TOOLS));
            case "build_materials", "prepare_materials", PREPARE_BUILD_MATERIALS ->
                    new PlanBlueprint(PREPARE_BUILD_MATERIALS, List.of(PREPARE_BUILD_MATERIALS));
            case "materials", "resources", "basic_resources", "collect_materials", GATHER_MATERIALS ->
                    new PlanBlueprint(GATHER_MATERIALS, List.of(PREPARE_BASIC_TOOLS, GATHER_WOOD, COLLECT_DROPS, CRAFT_PICKAXE, GATHER_STONE, COLLECT_DROPS));
            case "wood", "logs", "tree", "trees", "harvest_logs", GATHER_WOOD ->
                    new PlanBlueprint(GATHER_WOOD, List.of(PREPARE_BASIC_TOOLS, GATHER_WOOD, COLLECT_DROPS));
            case "stone", "cobblestone", "cobble", "mine_stone", "gather_cobblestone", GATHER_STONE ->
                    new PlanBlueprint(GATHER_STONE, List.of(PREPARE_BASIC_TOOLS, GATHER_STONE, COLLECT_DROPS));
            case "mine", "mining", "ore", "ores", "coal", "iron", "mine_ore", "mine_nearby_ore", MINE_RESOURCES ->
                    new PlanBlueprint(MINE_RESOURCES, List.of(PREPARE_BASIC_TOOLS, MINE_RESOURCES, COLLECT_DROPS));
            case "axe", "craft_axe", "make_axe", "wood_axe", "wooden_axe" ->
                    new PlanBlueprint(CRAFT_AXE, List.of(CRAFT_AXE));
            case "pickaxe", "craft_pickaxe", "make_pickaxe", "wood_pickaxe", "wooden_pickaxe" ->
                    new PlanBlueprint(CRAFT_PICKAXE, List.of(CRAFT_PICKAXE));
            case "planks", "craft_planks", "make_planks", "wood_planks" ->
                    new PlanBlueprint(CRAFT_PLANKS, List.of(CRAFT_PLANKS));
            case "sticks", "craft_sticks", "make_sticks" ->
                    new PlanBlueprint(CRAFT_STICKS, List.of(CRAFT_STICKS));
            case "stone_axe", "craft_stone_axe", "make_stone_axe", "stone_tool", "stone_tools" ->
                    new PlanBlueprint(CRAFT_STONE_AXE, List.of(PREPARE_BASIC_TOOLS, GATHER_STONE, COLLECT_DROPS, CRAFT_STONE_AXE));
            case "stone_pickaxe", "craft_stone_pickaxe", "make_stone_pickaxe" ->
                    new PlanBlueprint(CRAFT_STONE_PICKAXE, List.of(PREPARE_BASIC_TOOLS, GATHER_STONE, COLLECT_DROPS, CRAFT_STONE_PICKAXE));
            case "house", "shelter", "basic_house", "build_basic_house", BUILD_BASIC_SHELTER ->
                    new PlanBlueprint(BUILD_BASIC_SHELTER, List.of(PREPARE_BASIC_TOOLS, GATHER_WOOD, COLLECT_DROPS, PREPARE_BUILD_MATERIALS, BUILD_BASIC_SHELTER));
            case "large_house", "big_house", "larger_house", BUILD_LARGE_HOUSE ->
                    new PlanBlueprint(BUILD_LARGE_HOUSE, List.of(PREPARE_BASIC_TOOLS, GATHER_WOOD, COLLECT_DROPS, BUILD_LARGE_HOUSE));
            case "repair", "repair_house", "patch_house", "fix_house", "repair_wall", "repair_door", "wall", "door", REPAIR_STRUCTURE ->
                    new PlanBlueprint(REPAIR_STRUCTURE, List.of(REPAIR_STRUCTURE));
            case "gear", "equip", "gear_up", "equip_best_gear", EQUIP_GEAR ->
                    new PlanBlueprint(EQUIP_GEAR, List.of(EQUIP_GEAR));
            case "storage", "deposit", "store_items", "organize_storage", "organize_inventory", DEPOSIT_STORAGE ->
                    new PlanBlueprint(DEPOSIT_STORAGE, List.of(DEPOSIT_STORAGE));
            case "protect", "guard", "guard_player", PROTECT_PLAYER ->
                    new PlanBlueprint(PROTECT_PLAYER, List.of(EQUIP_GEAR, PROTECT_PLAYER));
            case "create", "create_report", "modded_report", "report_modded_nearby", "inspect_mod_block", CREATE_INSPECT ->
                    new PlanBlueprint(CREATE_INSPECT, List.of(CREATE_INSPECT));
            case "use_mod_wrench", "wrench", CREATE_WRENCH ->
                    new PlanBlueprint(CREATE_WRENCH, List.of(CREATE_WRENCH));
            default -> new PlanBlueprint(goal.isBlank() ? "general_task" : goal, List.of());
        };
    }

    static StageAssessment assess(ServerPlayer player, PlanManager.PlanSnapshot plan, String stage) {
        if (!hasNpc(player)) {
            return StageAssessment.blocked("NO_NPC", "No companion NPC is spawned. Spawn one with /mcai npc spawn, then continue the plan.");
        }

        return switch (stage) {
            case PREPARE_BASIC_TOOLS -> assessBasicTools(player, plan);
            case PREPARE_BUILD_MATERIALS -> assessBuildMaterials(player, plan);
            case GATHER_WOOD -> StageAssessment.ready("Find logs, use or craft a real axe if possible, harvest reachable logs, and keep scanning briefly.");
            case GATHER_STONE -> StageAssessment.ready("Find reachable stone/cobblestone-like blocks, use or craft a real pickaxe if possible, mine a bounded count, then collect drops.");
            case MINE_RESOURCES -> StageAssessment.ready("Mine reachable exposed ore/resource blocks with a real pickaxe, then collect drops.");
            case COLLECT_DROPS -> StageAssessment.ready("Collect nearby dropped items before checking build materials.");
            case CRAFT_AXE -> assessCraftTarget(player, "axe");
            case CRAFT_PICKAXE -> assessCraftTarget(player, "pickaxe");
            case CRAFT_PLANKS -> assessCraftTarget(player, "planks");
            case CRAFT_STICKS -> assessCraftTarget(player, "sticks");
            case CRAFT_STONE_AXE -> assessStoneTool(player, "axe", ResourceAssessment.snapshot(player).canCraftStoneAxe());
            case CRAFT_STONE_PICKAXE -> assessStoneTool(player, "pickaxe", ResourceAssessment.snapshot(player).canCraftStonePickaxe());
            case BUILD_BASIC_SHELTER -> assessShelter(player, plan);
            case BUILD_LARGE_HOUSE -> StageAssessment.ready("Start the large 7x7 house builder near the chosen/player-relative anchor; it will report material/site blockers.");
            case REPAIR_STRUCTURE -> StageAssessment.ready("Scan the nearby building shell, infer wall material and door opening, craft a door if needed, then repair missing wall/door blocks.");
            case EQUIP_GEAR -> StageAssessment.ready("Equip the best available armor, shield, weapon, or task tool from NPC storage.");
            case DEPOSIT_STORAGE -> StageAssessment.ready("Move NPC storage items into nearby accessible containers; equipped items are not deposited.");
            case PROTECT_PLAYER -> StageAssessment.ready("Start guard mode around the player; hostile mobs can be attacked, player entities are ignored.");
            case CREATE_INSPECT -> StageAssessment.ready("Scan nearby Create-family blocks or inspect the requested coordinates.");
            case CREATE_WRENCH -> assessCreateWrench(plan);
            default -> StageAssessment.blocked("UNKNOWN_STAGE", "No execution skill exists for stage '" + stage + "'.");
        };
    }

    static String stageNextStep(String stage) {
        return switch (stage) {
            case PREPARE_BASIC_TOOLS -> "Check NPC storage and approved nearby containers for axe/pickaxe materials, then craft missing basic tools if possible.";
            case PREPARE_BUILD_MATERIALS -> "Check the shelter blueprint requirements against NPC storage and approved nearby container blocks.";
            case GATHER_WOOD -> "Gather logs near the player; if logs are scarce, follow briefly and keep scanning.";
            case GATHER_STONE -> "Gather reachable stone/cobblestone-like blocks near the player using a real pickaxe.";
            case MINE_RESOURCES -> "Mine reachable exposed ore/resource blocks near the player using a real pickaxe.";
            case COLLECT_DROPS -> "Pick up nearby drops from recent gathering into NPC storage or nearby containers.";
            case CRAFT_AXE -> "Craft one basic axe from NPC storage or approved nearby containers.";
            case CRAFT_PICKAXE -> "Craft one basic pickaxe from NPC storage or approved nearby containers.";
            case CRAFT_PLANKS -> "Convert available logs in NPC storage into planks.";
            case CRAFT_STICKS -> "Craft sticks from available planks in NPC storage.";
            case CRAFT_STONE_AXE -> "Craft one stone axe from NPC storage after collecting stone and sticks.";
            case CRAFT_STONE_PICKAXE -> "Craft one stone pickaxe from NPC storage after collecting stone and sticks.";
            case BUILD_BASIC_SHELTER -> "Place a small safe shelter using NPC storage, gathered blocks, or approved nearby container blocks.";
            case BUILD_LARGE_HOUSE -> "Build a larger 7x7 house using NPC storage, gathered blocks, or approved nearby container blocks.";
            case REPAIR_STRUCTURE -> "Repair the nearby existing structure by scanning its shell, matching wall material, and crafting/placing a door if a clear doorway is missing.";
            case EQUIP_GEAR -> "Equip the best available gear/tool from NPC storage.";
            case DEPOSIT_STORAGE -> "Deposit NPC storage into a nearby accessible chest/container.";
            case PROTECT_PLAYER -> "Start protection mode around the player.";
            case CREATE_INSPECT -> "Report nearby Create/Create Aeronautics blocks or inspect the target block.";
            case CREATE_WRENCH -> "Use a Create wrench on the exact target block.";
            default -> "Ask the player for a clearer next step.";
        };
    }

    static String stageDisplayName(String stage) {
        return switch (stage) {
            case PREPARE_BASIC_TOOLS -> "prepare basic tools";
            case PREPARE_BUILD_MATERIALS -> "prepare build materials";
            case GATHER_MATERIALS -> "gather materials";
            case GATHER_WOOD -> "gather wood";
            case GATHER_STONE -> "gather stone";
            case MINE_RESOURCES -> "mine resources";
            case COLLECT_DROPS -> "collect dropped items";
            case CRAFT_AXE -> "craft axe";
            case CRAFT_PICKAXE -> "craft pickaxe";
            case CRAFT_PLANKS -> "craft planks";
            case CRAFT_STICKS -> "craft sticks";
            case CRAFT_STONE_AXE -> "craft stone axe";
            case CRAFT_STONE_PICKAXE -> "craft stone pickaxe";
            case BUILD_BASIC_SHELTER -> "build basic shelter";
            case BUILD_LARGE_HOUSE -> "build large house";
            case REPAIR_STRUCTURE -> "repair nearby structure";
            case EQUIP_GEAR -> "equip gear";
            case DEPOSIT_STORAGE -> "deposit storage";
            case PROTECT_PLAYER -> "protect player";
            case CREATE_INSPECT -> "inspect Create/Aeronautics machines";
            case CREATE_WRENCH -> "use Create wrench";
            default -> stage;
        };
    }

    static String supportedSkillsSummary() {
        return "prepare_basic_tools, prepare_build_materials, gather_materials, gather_wood, gather_stone, mine_resources, collect_drops, craft_axe, craft_pickaxe, craft_planks, craft_sticks, craft_stone_axe, craft_stone_pickaxe, build_basic_shelter, build_large_house, build_structure, preview_structure, repair_structure, equip_gear, deposit_storage, protect_player, create_inspect, create_wrench";
    }

    private static StageAssessment assessBasicTools(ServerPlayer player, PlanManager.PlanSnapshot plan) {
        ResourceAssessment.Snapshot resources = ResourceAssessment.snapshot(player);
        boolean needsAxe = plan.goal().equals(GATHER_WOOD)
                || plan.goal().equals(GATHER_MATERIALS)
                || plan.goal().equals(BUILD_BASIC_SHELTER)
                || plan.goal().equals(BUILD_LARGE_HOUSE);
        boolean needsPickaxe = plan.goal().equals(GATHER_STONE)
                || plan.goal().equals(MINE_RESOURCES)
                || plan.goal().equals(CRAFT_STONE_AXE)
                || plan.goal().equals(CRAFT_STONE_PICKAXE);
        if (needsAxe && !resources.canUseOrCraftAxe()) {
            return StageAssessment.blocked("NEED_AXE_OR_MATERIALS",
                    "I need an axe or enough wood/stone materials in NPC storage or approved nearby containers to craft one before gathering wood.");
        }
        if (needsPickaxe && !resources.canUseOrCraftPickaxe()) {
            return StageAssessment.blocked("NEED_PICKAXE_OR_MATERIALS",
                    "I need a pickaxe or enough wood/stone materials in NPC storage or approved nearby containers to craft one before gathering stone.");
        }
        if (!needsAxe && !resources.canUseOrCraftAxe() && !resources.canUseOrCraftPickaxe()) {
            return StageAssessment.blocked("NEED_BASIC_TOOL_MATERIALS",
                    "I do not have or cannot craft a basic axe/pickaxe from NPC storage or approved nearby container materials.");
        }
        return StageAssessment.ready("Basic tool preparation can proceed from NPC storage or approved nearby container materials.");
    }

    private static StageAssessment assessBuildMaterials(ServerPlayer player, PlanManager.PlanSnapshot plan) {
        ResourceAssessment.Snapshot resources = ResourceAssessment.snapshot(player);
        int requiredBlocks = remainingShelterBlocks(player, plan);
        if (resources.placeableBlocksAfterPlankConversion() >= requiredBlocks) {
            return StageAssessment.ready("Basic shelter materials ready: " + resources.placeableBlocks()
                    + " placeable blocks now, " + resources.placeableBlocksAfterPlankConversion()
                    + " after converting available logs to planks; remaining blueprint blocks=" + requiredBlocks + ".");
        }

        return StageAssessment.blocked("NEED_BUILDING_BLOCKS", "The basic shelter blueprint needs "
                + requiredBlocks + " placeable blocks; available="
                + resources.placeableBlocks() + ", convertibleAvailable=" + resources.placeableBlocksAfterPlankConversion()
                + ", missing=" + Math.max(0, requiredBlocks - resources.placeableBlocksAfterPlankConversion())
                + ". I should gather wood first; nearby container blocks require your approval.");
    }

    private static StageAssessment assessShelter(ServerPlayer player, PlanManager.PlanSnapshot plan) {
        return assessBuildMaterials(player, plan);
    }

    private static StageAssessment assessStoneTool(ServerPlayer player, String toolName, boolean craftable) {
        if (craftable) {
            return StageAssessment.ready("Stone " + toolName + " materials are ready in NPC storage or approved nearby containers.");
        }
        return StageAssessment.blocked("NEED_STONE_TOOL_MATERIALS",
                "I need 3 cobblestone-like blocks and 2 sticks in NPC storage or approved nearby containers before crafting a stone " + toolName + ".");
    }

    private static StageAssessment assessCraftTarget(ServerPlayer player, String itemName) {
        if (NpcManager.isSupportedCraftItem(itemName)) {
            return StageAssessment.ready("Primitive crafting target is supported: " + itemName + ".");
        }
        return StageAssessment.blocked("UNSUPPORTED_CRAFT", "No primitive crafting recipe exists for " + itemName + ".");
    }

    private static int remainingShelterBlocks(ServerPlayer player, PlanManager.PlanSnapshot plan) {
        if (plan.hasBuildAnchor()) {
            BlockPos center = new BlockPos(plan.buildCenterX(), plan.buildCenterY(), plan.buildCenterZ());
            return NpcManager.countMissingBasicHouseBlocks(player, center, directionFromName(plan.buildForwardName()));
        }
        return NpcManager.countMissingBasicHouseBlocks(player, null, null);
    }

    private static Direction directionFromName(String name) {
        String normalized = firstNonBlank(name, Direction.NORTH.getName()).toLowerCase(Locale.ROOT);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (direction.getName().equals(normalized)) {
                return direction;
            }
        }
        return Direction.NORTH;
    }

    private static StageAssessment assessCreateWrench(PlanManager.PlanSnapshot plan) {
        if (!plan.hasTargetPosition()) {
            return StageAssessment.blocked("NEED_TARGET_BLOCK", "I need exact x y z coordinates before using a Create wrench.");
        }
        return StageAssessment.ready("Right-click the target Create-family block with a wrench if one is available.");
    }

    private static boolean hasNpc(ServerPlayer player) {
        return boolValue(NpcManager.describeFor(player), "spawned");
    }

    private static boolean boolValue(com.google.gson.JsonObject json, String key) {
        return json != null && json.has(key) && !json.get(key).isJsonNull() && json.get(key).getAsBoolean();
    }

    private static String inferGoal(String message) {
        String text = firstNonBlank(message, "").toLowerCase(Locale.ROOT);
        if (containsAny(text, "stone axe", "stone_axe", "\u77f3\u65a7", "\u77f3\u5934\u65a7", "\u77f3\u6597\u65a7")) {
            return CRAFT_STONE_AXE;
        }
        if (containsAny(text, "stone pickaxe", "stone_pickaxe", "\u77f3\u9550", "\u77f3\u5934\u9550", "\u77f3\u9550\u5b50")) {
            return CRAFT_STONE_PICKAXE;
        }
        if (containsAny(text, "craft planks", "make planks", "planks", "\u6728\u677f")) {
            return CRAFT_PLANKS;
        }
        if (containsAny(text, "craft sticks", "make sticks", "sticks", "\u6728\u68cd")) {
            return CRAFT_STICKS;
        }
        if (containsAny(text, "craft pickaxe", "make pickaxe", "pickaxe", "\u9550\u5b50", "\u7a3f\u5b50", "\u641e\u5b50")) {
            return CRAFT_PICKAXE;
        }
        if (containsAny(text, "craft axe", "make axe", "axe", "hatchet", "\u65a7\u5b50", "\u65a7")) {
            return CRAFT_AXE;
        }
        if (containsAny(text, "wood", "log", "tree", "\u6728", "\u6811", "\u780d\u6811", "\u539f\u6728")) {
            return GATHER_WOOD;
        }
        if (containsAny(text, "gather materials", "collect materials", "materials", "resources", "\u6536\u96c6\u6750\u6599", "\u91c7\u96c6\u6750\u6599", "\u8d44\u6e90")) {
            return GATHER_MATERIALS;
        }
        if (containsAny(text, "cobblestone", "cobble", "stone", "\u5706\u77f3", "\u77f3\u5934")) {
            return GATHER_STONE;
        }
        if (containsAny(text, "mine", "ore", "coal", "iron", "\u6316\u77ff", "\u7164\u77ff", "\u94c1\u77ff", "\u77ff\u7269")) {
            return MINE_RESOURCES;
        }
        if (containsAny(text, "large house", "big house", "bigger house", "\u5927\u623f\u5b50", "\u5927\u5c4b", "\u5927\u4e00\u70b9\u7684\u623f\u5b50")) {
            return BUILD_LARGE_HOUSE;
        }
        if (containsAny(text, "repair", "patch", "fix wall", "fix door", "repair wall", "repair door", "\u4fee\u8865", "\u4fee\u95e8", "\u8865\u95e8", "\u4fee\u5899", "\u8865\u5899", "\u5899\u6d1e")) {
            return REPAIR_STRUCTURE;
        }
        if (containsAny(text, "shelter", "house", "base", "\u623f", "\u5c4b", "\u57fa\u5730", "\u907f\u96be\u6240")) {
            return BUILD_BASIC_SHELTER;
        }
        if (containsAny(text, "gear", "equip", "armor", "\u88c5\u5907", "\u7a7f\u4e0a", "\u62ff\u4e0a\u6700\u597d")) {
            return EQUIP_GEAR;
        }
        if (containsAny(text, "deposit", "storage", "chest", "\u5b58\u7bb1\u5b50", "\u6574\u7406", "\u653e\u7bb1\u5b50")) {
            return DEPOSIT_STORAGE;
        }
        if (containsAny(text, "protect", "guard", "\u4fdd\u62a4", "\u5b88\u62a4", "\u62a4\u536b")) {
            return PROTECT_PLAYER;
        }
        if (containsAny(text, "wrench", "\u6273\u624b", "\u65cb\u8f6c", "\u8c03\u6574")) {
            return CREATE_WRENCH;
        }
        if (containsAny(text, "create", "aeronautics", "\u673a\u5668", "\u673a\u68b0", "\u52a8\u529b", "\u98de\u884c\u5668")) {
            return CREATE_INSPECT;
        }
        return "";
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return firstNonBlank(value, "").trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    record PlanBlueprint(String goal, List<String> stages) {
        PlanBlueprint {
            stages = List.copyOf(new ArrayList<>(stages));
        }
    }

    record StageAssessment(boolean ready, String code, String message) {
        static StageAssessment ready(String message) {
            return new StageAssessment(true, "READY", message);
        }

        static StageAssessment blocked(String code, String message) {
            return new StageAssessment(false, code, message);
        }
    }
}
