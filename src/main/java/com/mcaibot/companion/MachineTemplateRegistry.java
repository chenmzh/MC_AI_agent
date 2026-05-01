package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class MachineTemplateRegistry {
    private static final Map<String, MachineTemplateSpec> TEMPLATES = createTemplates();

    private MachineTemplateRegistry() {
    }

    public static Optional<MachineTemplateSpec> find(String templateId) {
        String normalized = normalizeTemplate(templateId);
        MachineTemplateSpec spec = TEMPLATES.get(normalized);
        if (spec != null) {
            return Optional.of(spec);
        }
        return Optional.ofNullable(TEMPLATES.get(alias(normalized)));
    }

    public static JsonObject catalogJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", "mc-agent-machine-catalog-v1");
        root.addProperty("provider", "builtin_machine_templates");
        root.addProperty("createMachineBuildsAvailable", false);
        root.addProperty("authorizationPolicy", "high_risk_templates_require_saved_plan_authorization");
        root.addProperty("placementMode", "deterministic_direct_world_placement_consumes_npc_or_approved_container_materials");
        JsonArray templates = new JsonArray();
        for (MachineTemplateSpec spec : TEMPLATES.values()) {
            templates.add(spec.toJson());
        }
        root.add("templates", templates);
        return root;
    }

    public static MachineBlueprint create(String templateId, net.minecraft.server.level.ServerPlayer player, BlockPos origin, Direction facing) {
        MachineTemplateSpec spec = find(templateId).orElse(TEMPLATES.get("pressure_door"));
        Direction buildFacing = horizontal(facing);
        BlockPos anchor = origin == null ? defaultOrigin(spec.id(), player, buildFacing) : origin.immutable();
        List<MachinePlacement> placements = new ArrayList<>();
        switch (spec.id()) {
            case "button_door" -> addButtonDoor(placements, anchor, buildFacing);
            case "lever_door" -> addLeverDoor(placements, anchor, buildFacing);
            case "simple_lamp_switch" -> addSimpleLampSwitch(placements, anchor, buildFacing);
            case "mob_drop_tower_v1" -> addMobDropTower(placements, anchor, buildFacing);
            case "iron_farm_v1" -> addIronFarm(placements, anchor, buildFacing);
            case "villager_breeder_v1" -> addVillagerBreeder(placements, anchor, buildFacing);
            case "trading_hall_v1" -> addTradingHall(placements, anchor, buildFacing);
            default -> addPressureDoor(placements, anchor, buildFacing);
        }
        return new MachineBlueprint(
                spec.id(),
                spec.label(),
                spec.category(),
                spec.riskLevel(),
                spec.footprint(),
                spec.height(),
                anchor,
                buildFacing,
                placements,
                spec.materialBudget(),
                spec.entityRequirements(),
                spec.fluidPaths(),
                spec.redstonePaths(),
                spec.maintenanceAccess(),
                spec.testProcedure()
        );
    }

    public static BlockPos defaultOrigin(String templateId, net.minecraft.server.level.ServerPlayer player, Direction facing) {
        Direction buildFacing = horizontal(facing);
        BlockPos playerPos = player == null ? BlockPos.ZERO : player.blockPosition();
        return switch (alias(normalizeTemplate(templateId))) {
            case "mob_drop_tower_v1" -> playerPos.relative(buildFacing, 14);
            case "iron_farm_v1", "villager_breeder_v1", "trading_hall_v1" -> playerPos.relative(buildFacing, 9);
            case "simple_lamp_switch" -> playerPos.relative(buildFacing, 3);
            default -> playerPos.relative(buildFacing, 3);
        };
    }

    public static String normalizeTemplate(String value) {
        if (value == null || value.isBlank()) {
            return "pressure_door";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    public static String inferTemplate(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "mob", "drop tower", "mob farm", "monster", "\u5237\u602a\u5854", "\u5237\u602a\u573a")) {
            return "mob_drop_tower_v1";
        }
        if (containsAny(normalized, "iron", "golem", "\u94c1\u5080\u5121", "\u5237\u94c1", "\u94c1\u519c\u573a")) {
            return "iron_farm_v1";
        }
        if (containsAny(normalized, "breeder", "villager breeder", "\u6751\u6c11\u7e41\u6b96", "\u7e41\u6b96\u673a")) {
            return "villager_breeder_v1";
        }
        if (containsAny(normalized, "trading", "trading hall", "\u4ea4\u6613\u5385", "\u6751\u6c11\u4ea4\u6613")) {
            return "trading_hall_v1";
        }
        if (containsAny(normalized, "button door", "\u6309\u94ae\u95e8")) {
            return "button_door";
        }
        if (containsAny(normalized, "lever door", "\u62c9\u6746\u95e8")) {
            return "lever_door";
        }
        if (containsAny(normalized, "lamp", "light switch", "\u7ea2\u77f3\u706f", "\u706f\u5f00\u5173")) {
            return "simple_lamp_switch";
        }
        return "pressure_door";
    }

    public static boolean isHighRiskTemplate(String templateId) {
        return find(templateId).map(spec -> "high".equals(spec.riskLevel())).orElse(false);
    }

    private static void addPressureDoor(List<MachinePlacement> placements, BlockPos center, Direction facing) {
        addDoor(placements, center, facing, 0, 0, 0);
        add(placements, center, facing, 0, 0, -1, "redstone_input", Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), false);
        add(placements, center, facing, 0, 0, 1, "redstone_input", Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), false);
    }

    private static void addButtonDoor(List<MachinePlacement> placements, BlockPos center, Direction facing) {
        addDoor(placements, center, facing, 0, 0, 0);
        add(placements, center, facing, 1, 0, -1, "support", Blocks.COBBLESTONE.defaultBlockState(), false);
        add(placements, center, facing, 1, 1, -1, "redstone_input", Blocks.STONE_BUTTON.defaultBlockState(), false);
    }

    private static void addLeverDoor(List<MachinePlacement> placements, BlockPos center, Direction facing) {
        addDoor(placements, center, facing, 0, 0, 0);
        add(placements, center, facing, 1, 0, -1, "support", Blocks.COBBLESTONE.defaultBlockState(), false);
        add(placements, center, facing, 1, 1, -1, "redstone_input", Blocks.LEVER.defaultBlockState(), false);
    }

    private static void addSimpleLampSwitch(List<MachinePlacement> placements, BlockPos center, Direction facing) {
        add(placements, center, facing, 0, 0, 0, "support", Blocks.COBBLESTONE.defaultBlockState(), false);
        add(placements, center, facing, 0, 1, 0, "lamp", Blocks.REDSTONE_LAMP.defaultBlockState(), false);
        add(placements, center, facing, -1, 0, 0, "redstone_line", Blocks.REDSTONE_WIRE.defaultBlockState(), false);
        add(placements, center, facing, -2, 0, 0, "support", Blocks.COBBLESTONE.defaultBlockState(), false);
        add(placements, center, facing, -2, 1, 0, "redstone_input", Blocks.LEVER.defaultBlockState(), false);
    }

    private static void addMobDropTower(List<MachinePlacement> placements, BlockPos center, Direction facing) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                add(placements, center, facing, x, 0, z, "collection_floor", Blocks.COBBLESTONE.defaultBlockState(), false);
            }
        }
        add(placements, center, facing, 0, 0, 0, "collection", Blocks.HOPPER.defaultBlockState(), false);
        add(placements, center, facing, 0, 0, 2, "collection", Blocks.CHEST.defaultBlockState(), false);
        for (int y = 1; y <= 22; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0) {
                        continue;
                    }
                    add(placements, center, facing, x, y, z, "drop_shaft", Blocks.COBBLESTONE.defaultBlockState(), false);
                }
            }
        }
        int platformY = 23;
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
                    continue;
                }
                add(placements, center, facing, x, platformY, z, "spawn_platform", Blocks.COBBLESTONE.defaultBlockState(), false);
            }
        }
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.abs(x) == 4 || Math.abs(z) == 4) {
                    add(placements, center, facing, x, platformY + 1, z, "wall", Blocks.COBBLESTONE.defaultBlockState(), false);
                    add(placements, center, facing, x, platformY + 2, z, "wall", Blocks.COBBLESTONE.defaultBlockState(), false);
                }
                add(placements, center, facing, x, platformY + 3, z, "roof", Blocks.COBBLESTONE.defaultBlockState(), false);
            }
        }
        add(placements, center, facing, 0, platformY + 1, -3, "water_path", Blocks.WATER.defaultBlockState(), false);
        add(placements, center, facing, 0, platformY + 1, 3, "water_path", Blocks.WATER.defaultBlockState(), false);
        add(placements, center, facing, -3, platformY + 1, 0, "water_path", Blocks.WATER.defaultBlockState(), false);
        add(placements, center, facing, 3, platformY + 1, 0, "water_path", Blocks.WATER.defaultBlockState(), false);
        addLadderColumn(placements, center, facing, 2, 1, 2, 22);
    }

    private static void addIronFarm(List<MachinePlacement> placements, BlockPos center, Direction facing) {
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                add(placements, center, facing, x, 4, z, "golem_platform", Blocks.COBBLESTONE.defaultBlockState(), false);
                if (Math.abs(x) == 4 || Math.abs(z) == 4) {
                    add(placements, center, facing, x, 5, z, "platform_wall", Blocks.GLASS.defaultBlockState(), false);
                }
            }
        }
        add(placements, center, facing, 0, 5, 0, "lava_kill", Blocks.LAVA.defaultBlockState(), false);
        for (int x = -1; x <= 1; x++) {
            add(placements, center, facing, x, 1, -3, "villager_floor", Blocks.COBBLESTONE.defaultBlockState(), false);
            add(placements, center, facing, x, 2, -3, "bed", bedState(facing, x == 0 ? BedPart.HEAD : BedPart.FOOT), false);
            add(placements, center, facing, x, 2, -2, "glass", Blocks.GLASS.defaultBlockState(), false);
        }
        add(placements, center, facing, 0, 1, 3, "zombie_cage", Blocks.COBBLESTONE.defaultBlockState(), false);
        add(placements, center, facing, 0, 2, 3, "zombie_cage", Blocks.GLASS.defaultBlockState(), false);
        add(placements, center, facing, 0, 0, 0, "collection", Blocks.HOPPER.defaultBlockState(), false);
        add(placements, center, facing, 0, 0, 1, "collection", Blocks.CHEST.defaultBlockState(), false);
        add(placements, center, facing, -3, 5, -3, "water_path", Blocks.WATER.defaultBlockState(), false);
        add(placements, center, facing, 3, 5, 3, "water_path", Blocks.WATER.defaultBlockState(), false);
    }

    private static void addVillagerBreeder(List<MachinePlacement> placements, BlockPos center, Direction facing) {
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                add(placements, center, facing, x, 0, z, "floor", Blocks.OAK_PLANKS.defaultBlockState(), false);
                if (Math.abs(x) == 4 || Math.abs(z) == 4) {
                    add(placements, center, facing, x, 1, z, "fence", Blocks.OAK_FENCE.defaultBlockState(), false);
                }
            }
        }
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                add(placements, center, facing, x, 1, z, "farmland", Blocks.FARMLAND.defaultBlockState(), false);
            }
        }
        add(placements, center, facing, 0, 1, 0, "water_path", Blocks.WATER.defaultBlockState(), false);
        for (int i = -2; i <= 2; i += 2) {
            add(placements, center, facing, i, 1, -4, "bed", bedState(facing, BedPart.FOOT), false);
            add(placements, center, facing, i, 1, -3, "bed", bedState(facing, BedPart.HEAD), false);
        }
        add(placements, center, facing, -3, 1, 3, "workstation", Blocks.COMPOSTER.defaultBlockState(), false);
        add(placements, center, facing, 3, 1, 3, "workstation", Blocks.COMPOSTER.defaultBlockState(), false);
        add(placements, center, facing, 0, 1, 4, "gate", Blocks.OAK_FENCE_GATE.defaultBlockState(), false);
    }

    private static void addTradingHall(List<MachinePlacement> placements, BlockPos center, Direction facing) {
        for (int x = -5; x <= 5; x++) {
            for (int z = -2; z <= 2; z++) {
                add(placements, center, facing, x, 0, z, "floor", Blocks.OAK_PLANKS.defaultBlockState(), false);
                if (Math.abs(z) == 2) {
                    add(placements, center, facing, x, 2, z, "wall", Blocks.GLASS.defaultBlockState(), false);
                }
                add(placements, center, facing, x, 4, z, "roof", Blocks.OAK_SLAB.defaultBlockState(), false);
            }
        }
        for (int x = -4; x <= 4; x += 2) {
            add(placements, center, facing, x, 1, -2, "workstation", Blocks.LECTERN.defaultBlockState(), false);
            add(placements, center, facing, x, 1, -1, "villager_cell", Blocks.OAK_TRAPDOOR.defaultBlockState(), false);
            add(placements, center, facing, x, 1, 2, "workstation", Blocks.COMPOSTER.defaultBlockState(), false);
            add(placements, center, facing, x, 1, 1, "villager_cell", Blocks.OAK_TRAPDOOR.defaultBlockState(), false);
        }
        add(placements, center, facing, 0, 1, 0, "light", Blocks.TORCH.defaultBlockState(), true);
    }

    private static void addDoor(List<MachinePlacement> placements, BlockPos center, Direction facing, int dx, int dy, int dz) {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, horizontal(facing))
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        add(placements, center, facing, dx, dy, dz, "door", lower, false);
        add(placements, center, facing, dx, dy + 1, dz, "door", lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), false);
    }

    private static BlockState bedState(Direction facing, BedPart part) {
        return Blocks.WHITE_BED.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, horizontal(facing))
                .setValue(BlockStateProperties.BED_PART, part);
    }

    private static void addLadderColumn(List<MachinePlacement> placements, BlockPos center, Direction facing, int dx, int yStart, int dz, int height) {
        for (int y = yStart; y <= height; y++) {
            add(placements, center, facing, dx, y, dz, "maintenance_access", Blocks.LADDER.defaultBlockState(), true);
        }
    }

    private static void add(List<MachinePlacement> placements, BlockPos center, Direction facing, int dx, int dy, int dz, String role, BlockState state, boolean optional) {
        placements.add(new MachinePlacement(worldPos(center, facing, dx, dy, dz), role, state, optional));
    }

    private static BlockPos worldPos(BlockPos center, Direction facing, int dx, int dy, int dz) {
        Direction forward = horizontal(facing);
        Direction right = forward.getClockWise();
        return center.relative(right, dx).relative(forward, dz).above(dy);
    }

    private static Direction horizontal(Direction direction) {
        if (direction == null || direction.getAxis().isVertical()) {
            return Direction.NORTH;
        }
        return direction;
    }

    private static Map<String, MachineTemplateSpec> createTemplates() {
        Map<String, MachineTemplateSpec> templates = new LinkedHashMap<>();
        put(templates, "pressure_door", "Pressure plate door", "redstone", "low", "1x3", 2,
                budget("doors", 1, "pressure_plates", 2), strings(), strings(), strings("pressure plates open door"), strings("walk through both sides"), strings("door opens from both plates"));
        put(templates, "button_door", "Button door", "redstone", "low", "2x2", 2,
                budget("doors", 1, "buttons", 1, "large_placeable_blocks", 1), strings(), strings(), strings("button opens door"), strings("button side access"), strings("button opens the door"));
        put(templates, "lever_door", "Lever door", "redstone", "low", "2x2", 2,
                budget("doors", 1, "levers", 1, "large_placeable_blocks", 1), strings(), strings(), strings("lever toggles door"), strings("lever side access"), strings("lever toggles the door"));
        put(templates, "simple_lamp_switch", "Simple redstone lamp switch", "redstone", "low", "3x1", 2,
                budget("redstone_lamps", 1, "levers", 1, "redstone_components", 1, "large_placeable_blocks", 2), strings(), strings(), strings("lever powers lamp through redstone dust"), strings("lever side access"), strings("lamp receives redstone power"));
        put(templates, "mob_drop_tower_v1", "Mob drop tower v1", "vanilla_survival_machine", "high", "9x9", 27,
                budget("large_placeable_blocks", 360, "hoppers", 1, "chests", 1, "water_buckets", 4, "ladders", 16), strings(), strings("four water sources push mobs to center drop shaft"), strings(), strings("ladder column and collection chest"), strings("shaft clear", "collection chest present", "water paths present"));
        put(templates, "iron_farm_v1", "Iron farm v1", "vanilla_survival_machine", "high", "9x9", 6,
                budget("large_placeable_blocks", 100, "glass_like", 16, "beds", 3, "hoppers", 1, "chests", 1, "water_buckets", 2, "lava_buckets", 1), entityReq("minecraft:villager", 3, "minecraft:zombie", 1), strings("water moves golems to lava cell", "lava kill cell isolated"), strings(), strings("collection chest and cage access"), strings("golem platform present", "lava isolated", "villager/zombie entities present or waiting"));
        put(templates, "villager_breeder_v1", "Villager breeder v1", "vanilla_survival_machine", "high", "9x9", 3,
                budget("large_placeable_blocks", 95, "beds", 3, "workstations", 2, "water_buckets", 1, "trapdoors", 1), entityReq("minecraft:villager", 2), strings("center water irrigates starter crop area"), strings(), strings("gate access"), strings("beds and farm area present", "villagers present or waiting"));
        put(templates, "trading_hall_v1", "Villager trading hall v1", "vanilla_survival_machine", "high", "11x5", 5,
                budget("large_placeable_blocks", 95, "glass_like", 22, "workstations", 10, "trapdoors", 10), entityReq("minecraft:villager", 1), strings(), strings(), strings("central aisle access"), strings("cells and workstations present", "villagers present or waiting"));
        return Map.copyOf(templates);
    }

    private static void put(Map<String, MachineTemplateSpec> templates, String id, String label, String category, String riskLevel, String footprint, int height,
                            Map<String, Integer> budget, JsonArray entityRequirements, JsonArray fluidPaths, JsonArray redstonePaths, JsonArray maintenanceAccess, JsonArray testProcedure) {
        templates.put(id, new MachineTemplateSpec(id, label, category, riskLevel, footprint, height, budget, entityRequirements, fluidPaths, redstonePaths, maintenanceAccess, testProcedure));
    }

    private static Map<String, Integer> budget(Object... entries) {
        Map<String, Integer> budget = new LinkedHashMap<>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            budget.put(String.valueOf(entries[index]), ((Number) entries[index + 1]).intValue());
        }
        return budget;
    }

    private static JsonArray entityReq(Object... entries) {
        JsonArray array = new JsonArray();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            JsonObject req = new JsonObject();
            req.addProperty("entity", String.valueOf(entries[index]));
            req.addProperty("count", ((Number) entries[index + 1]).intValue());
            array.add(req);
        }
        return array;
    }

    private static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static String alias(String normalized) {
        return switch (normalized) {
            case "auto_door", "automatic_door", "redstone_door", "pressure_plate_door", "\u81ea\u52a8\u95e8", "\u538b\u529b\u677f\u95e8" -> "pressure_door";
            case "button", "button_redstone_door", "\u6309\u94ae\u95e8" -> "button_door";
            case "lever", "lever_redstone_door", "\u62c9\u6746\u95e8" -> "lever_door";
            case "lamp", "redstone_lamp", "lamp_switch", "\u7ea2\u77f3\u706f" -> "simple_lamp_switch";
            case "mob_farm", "mob_tower", "drop_tower", "spawner", "\u5237\u602a\u5854" -> "mob_drop_tower_v1";
            case "iron_farm", "golem_farm", "\u5237\u94c1\u673a", "\u94c1\u5080\u5121\u519c\u573a" -> "iron_farm_v1";
            case "villager_breeder", "breeder", "\u6751\u6c11\u7e41\u6b96\u673a" -> "villager_breeder_v1";
            case "trading_hall", "villager_trading", "\u4ea4\u6613\u5385" -> "trading_hall_v1";
            default -> normalized;
        };
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public record MachineTemplateSpec(
            String id,
            String label,
            String category,
            String riskLevel,
            String footprint,
            int height,
            Map<String, Integer> materialBudget,
            JsonArray entityRequirements,
            JsonArray fluidPaths,
            JsonArray redstonePaths,
            JsonArray maintenanceAccess,
            JsonArray testProcedure
    ) {
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("label", label);
            json.addProperty("category", category);
            json.addProperty("riskLevel", riskLevel);
            json.addProperty("footprint", footprint);
            json.addProperty("height", height);
            JsonObject budgetJson = new JsonObject();
            for (Map.Entry<String, Integer> entry : materialBudget.entrySet()) {
                budgetJson.addProperty(entry.getKey(), entry.getValue());
            }
            json.add("materialBudget", budgetJson);
            json.add("entityRequirements", entityRequirements.deepCopy());
            json.add("fluidPaths", fluidPaths.deepCopy());
            json.add("redstonePaths", redstonePaths.deepCopy());
            json.add("maintenanceAccess", maintenanceAccess.deepCopy());
            json.add("testProcedure", testProcedure.deepCopy());
            return json;
        }
    }
}
