package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class BlueprintTemplateRegistry {
    private static final Map<String, TemplateSpec> TEMPLATES = createTemplates();

    private BlueprintTemplateRegistry() {
    }

    public static List<String> templateIds() {
        return List.copyOf(TEMPLATES.keySet());
    }

    public static Optional<TemplateSpec> find(String templateId) {
        String normalized = normalizeTemplate(templateId);
        TemplateSpec spec = TEMPLATES.get(normalized);
        if (spec != null) {
            return Optional.of(spec);
        }
        return Optional.ofNullable(TEMPLATES.get(alias(normalized)));
    }

    public static JsonObject catalogJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", "mc-agent-blueprint-catalog-v1");
        root.addProperty("provider", "builtin_templates");
        root.addProperty("litematicaProviderAvailable", false);
        root.addProperty("projectionImportStatus", "reserved_interface_only");
        root.addProperty("projectionFeedback", "Projection/Litematica reading is not implemented yet; use built-in templates or future neutral JSON blueprints.");
        root.add("travelPolicy", TravelController.policyJson());
        JsonArray templates = new JsonArray();
        for (TemplateSpec spec : TEMPLATES.values()) {
            templates.add(spec.catalogJson());
        }
        root.add("templates", templates);
        return root;
    }

    public static StructureBlueprint create(String templateId, ServerPlayer player, BlockPos origin, Direction facing, String style) {
        String normalized = normalizeTemplate(templateId);
        if (normalized.contains("litematica") || normalized.contains("projection") || normalized.contains("schematic")) {
            return placeholderProjectionBlueprint(player, origin, facing, style);
        }

        TemplateSpec spec = find(normalized).orElse(TEMPLATES.get("starter_cabin_7x7"));
        Direction buildFacing = horizontal(facing);
        BlockPos center = origin == null ? defaultOrigin(spec.id(), player, buildFacing) : origin.immutable();
        String resolvedStyle = style == null || style.isBlank() ? "rustic" : style.trim();
        List<BlueprintPlacement> placements = new ArrayList<>();
        switch (spec.id()) {
            case "storage_shed_5x7" -> addStorageShed(placements, center, buildFacing);
            case "bridge_3w" -> addBridge(placements, center, buildFacing);
            case "watchtower_5x5" -> addWatchtower(placements, center, buildFacing);
            case "farm_fence_9x9" -> addFarmFence(placements, center, buildFacing);
            case "path_lights" -> addPathLights(placements, center, buildFacing);
            default -> addStarterCabin(placements, center, buildFacing);
        }
        return new StructureBlueprint(spec.id(), spec.label(), spec.footprint(), spec.height(), center, buildFacing, placements, "builtin_templates", resolvedStyle);
    }

    public static BlockPos defaultOrigin(String templateId, ServerPlayer player, Direction facing) {
        Direction buildFacing = horizontal(facing);
        BlockPos playerPos = player == null ? BlockPos.ZERO : player.blockPosition();
        String normalized = normalizeTemplate(templateId);
        return switch (alias(normalized)) {
            case "bridge_3w" -> playerPos.relative(buildFacing, 3);
            case "path_lights" -> playerPos.relative(buildFacing, 2);
            case "farm_fence_9x9" -> playerPos.relative(buildFacing, 6);
            case "watchtower_5x5" -> playerPos.relative(buildFacing, 6);
            case "storage_shed_5x7" -> playerPos.relative(buildFacing, 6);
            default -> playerPos.relative(buildFacing, 7);
        };
    }

    public static String normalizeTemplate(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return "starter_cabin_7x7";
        }
        return templateId.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    public static String inferTemplate(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "projection", "litematica", "schematic", "投影")) {
            return "projection_placeholder";
        }
        if (containsAny(normalized, "bridge", "桥")) {
            return "bridge_3w";
        }
        if (containsAny(normalized, "tower", "watchtower", "塔", "哨塔", "瞭望")) {
            return "watchtower_5x5";
        }
        if (containsAny(normalized, "fence", "pen", "farm fence", "围栏", "圈", "围农田")) {
            return "farm_fence_9x9";
        }
        if (containsAny(normalized, "path light", "lights", "torch path", "路灯", "铺路灯", "照明")) {
            return "path_lights";
        }
        if (containsAny(normalized, "storage", "shed", "仓库", "棚")) {
            return "storage_shed_5x7";
        }
        return "starter_cabin_7x7";
    }

    private static StructureBlueprint placeholderProjectionBlueprint(ServerPlayer player, BlockPos origin, Direction facing, String style) {
        Direction buildFacing = horizontal(facing);
        BlockPos center = origin == null ? defaultOrigin("starter_cabin_7x7", player, buildFacing) : origin.immutable();
        return new StructureBlueprint(
                "projection_placeholder",
                "Projection/Litematica placeholder",
                "external",
                0,
                center,
                buildFacing,
                List.of(),
                "litematica_reserved",
                style == null || style.isBlank() ? "external" : style
        );
    }

    private static void addStarterCabin(List<BlueprintPlacement> placements, BlockPos center, Direction facing) {
        int half = 3;
        int front = -half;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                add(placements, center, facing, dx, 0, dz, "foundation", foundation(), false);
            }
        }

        for (int y = 1; y <= 4; y++) {
            add(placements, center, facing, -half, y, -half, "corner_log", logs(), false);
            add(placements, center, facing, half, y, -half, "corner_log", logs(), false);
            add(placements, center, facing, -half, y, half, "corner_log", logs(), false);
            add(placements, center, facing, half, y, half, "corner_log", logs(), false);
        }

        for (int y = 1; y <= 3; y++) {
            for (int dx = -half + 1; dx <= half - 1; dx++) {
                boolean door = dx == 0 && y <= 2;
                boolean window = Math.abs(dx) == 2 && y == 2;
                addWallOrOpening(placements, center, facing, dx, y, front, door, window);
                addWallOrOpening(placements, center, facing, dx, y, half, false, window);
            }
            for (int dz = -half + 1; dz <= half - 1; dz++) {
                boolean window = y == 2 && Math.abs(dz) == 1;
                addWallOrOpening(placements, center, facing, -half, y, dz, false, window);
                addWallOrOpening(placements, center, facing, half, y, dz, false, window);
            }
        }

        add(placements, center, facing, 0, 1, front, "door", doors(), true);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.abs(dx) == 4 || Math.abs(dz) == 4 || Math.abs(dx) + Math.abs(dz) > 5) {
                    add(placements, center, facing, dx, 4, dz, "roof", roof(), false);
                } else {
                    add(placements, center, facing, dx, 5, dz, "roof", roof(), false);
                }
            }
        }
        add(placements, center, facing, -2, 3, front - 1, "light", lights(), true);
        add(placements, center, facing, 2, 3, front - 1, "light", lights(), true);
        for (int dz = front - 1; dz >= front - 4; dz--) {
            add(placements, center, facing, 0, 0, dz, "path", pathBlocks(), true);
        }
    }

    private static void addStorageShed(List<BlueprintPlacement> placements, BlockPos center, Direction facing) {
        int halfW = 2;
        int halfD = 3;
        int front = -halfD;
        for (int dx = -halfW; dx <= halfW; dx++) {
            for (int dz = -halfD; dz <= halfD; dz++) {
                add(placements, center, facing, dx, 0, dz, "foundation", foundation(), false);
            }
        }
        for (int y = 1; y <= 3; y++) {
            for (int dx = -halfW; dx <= halfW; dx++) {
                boolean corner = Math.abs(dx) == halfW;
                boolean door = dx == 0 && y <= 2;
                add(placements, center, facing, dx, y, front, door ? "door_opening" : corner ? "corner_log" : "wall", door ? List.of() : corner ? logs() : wall(), door);
                add(placements, center, facing, dx, y, halfD, corner ? "corner_log" : "wall", corner ? logs() : wall(), false);
            }
            for (int dz = -halfD + 1; dz <= halfD - 1; dz++) {
                add(placements, center, facing, -halfW, y, dz, "wall", y == 2 ? windows() : wall(), y == 2);
                add(placements, center, facing, halfW, y, dz, "wall", y == 2 ? windows() : wall(), y == 2);
            }
        }
        add(placements, center, facing, 0, 1, front, "door", doors(), true);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                add(placements, center, facing, dx, 4, dz, "roof", roof(), false);
            }
        }
        add(placements, center, facing, 0, 3, front - 1, "light", lights(), true);
    }

    private static void addBridge(List<BlueprintPlacement> placements, BlockPos center, Direction facing) {
        for (int dz = -3; dz <= 7; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                add(placements, center, facing, dx, 0, dz, "path", bridgeDeck(), false);
            }
            add(placements, center, facing, -2, 1, dz, "decoration", fences(), true);
            add(placements, center, facing, 2, 1, dz, "decoration", fences(), true);
        }
        for (int dz : new int[]{-3, 0, 3, 6}) {
            add(placements, center, facing, -2, 2, dz, "light", lights(), true);
            add(placements, center, facing, 2, 2, dz, "light", lights(), true);
        }
    }

    private static void addWatchtower(List<BlueprintPlacement> placements, BlockPos center, Direction facing) {
        int half = 2;
        for (int y = 0; y <= 6; y++) {
            add(placements, center, facing, -half, y, -half, "corner_log", logs(), false);
            add(placements, center, facing, half, y, -half, "corner_log", logs(), false);
            add(placements, center, facing, -half, y, half, "corner_log", logs(), false);
            add(placements, center, facing, half, y, half, "corner_log", logs(), false);
        }
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                add(placements, center, facing, dx, 6, dz, "foundation", bridgeDeck(), false);
                add(placements, center, facing, dx, 8, dz, "roof", roof(), false);
            }
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) == 3 || Math.abs(dz) == 3) {
                    add(placements, center, facing, dx, 7, dz, "decoration", fences(), true);
                }
            }
        }
        add(placements, center, facing, 0, 7, -3, "light", lights(), true);
        add(placements, center, facing, 0, 1, -2, "decoration", List.of("minecraft:ladder", "minecraft:oak_planks"), true);
        add(placements, center, facing, 0, 2, -2, "decoration", List.of("minecraft:ladder", "minecraft:oak_planks"), true);
        add(placements, center, facing, 0, 3, -2, "decoration", List.of("minecraft:ladder", "minecraft:oak_planks"), true);
    }

    private static void addFarmFence(List<BlueprintPlacement> placements, BlockPos center, Direction facing) {
        int half = 4;
        for (int dx = -half; dx <= half; dx++) {
            add(placements, center, facing, dx, 1, -half, "decoration", fences(), false);
            add(placements, center, facing, dx, 1, half, "decoration", fences(), false);
        }
        for (int dz = -half + 1; dz <= half - 1; dz++) {
            add(placements, center, facing, -half, 1, dz, "decoration", fences(), false);
            add(placements, center, facing, half, 1, dz, "decoration", fences(), false);
        }
        add(placements, center, facing, 0, 1, -half, "door", List.of("minecraft:oak_fence_gate", "minecraft:oak_fence"), true);
        for (int dx : new int[]{-half, half}) {
            for (int dz : new int[]{-half, half}) {
                add(placements, center, facing, dx, 2, dz, "light", lights(), true);
            }
        }
    }

    private static void addPathLights(List<BlueprintPlacement> placements, BlockPos center, Direction facing) {
        for (int dz = 0; dz <= 14; dz++) {
            add(placements, center, facing, 0, 0, dz, "path", pathBlocks(), true);
            if (dz % 4 == 0) {
                add(placements, center, facing, -2, 1, dz, "corner_log", logs(), true);
                add(placements, center, facing, -2, 2, dz, "light", lights(), true);
                add(placements, center, facing, 2, 1, dz, "corner_log", logs(), true);
                add(placements, center, facing, 2, 2, dz, "light", lights(), true);
            }
        }
    }

    private static void addWallOrOpening(List<BlueprintPlacement> placements, BlockPos center, Direction facing, int dx, int y, int dz, boolean door, boolean window) {
        if (door) {
            return;
        }
        if (window) {
            add(placements, center, facing, dx, y, dz, "window", windows(), true);
            return;
        }
        add(placements, center, facing, dx, y, dz, "wall", wall(), false);
    }

    private static void add(List<BlueprintPlacement> placements, BlockPos center, Direction facing, int dx, int dy, int dz, String role, List<String> candidates, boolean optional) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        BlockPos pos = worldPos(center, facing, dx, dy, dz);
        placements.add(new BlueprintPlacement(pos, role, candidates, optional));
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

    private static List<String> foundation() {
        return List.of("minecraft:cobblestone", "minecraft:stone", "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks");
    }

    private static List<String> wall() {
        return List.of("minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:cobblestone", "minecraft:stone");
    }

    private static List<String> logs() {
        return List.of("minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log", "minecraft:dark_oak_log", "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:mangrove_log", "minecraft:cherry_log", "minecraft:oak_planks");
    }

    private static List<String> roof() {
        return List.of("minecraft:oak_slab", "minecraft:spruce_slab", "minecraft:birch_slab", "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:cobblestone");
    }

    private static List<String> windows() {
        return List.of("minecraft:glass_pane", "minecraft:glass", "minecraft:oak_fence", "minecraft:spruce_fence");
    }

    private static List<String> doors() {
        return List.of("minecraft:oak_door", "minecraft:spruce_door", "minecraft:birch_door");
    }

    private static List<String> lights() {
        return List.of("minecraft:torch", "minecraft:lantern", "minecraft:glowstone");
    }

    private static List<String> pathBlocks() {
        return List.of("minecraft:gravel", "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:spruce_planks");
    }

    private static List<String> bridgeDeck() {
        return List.of("minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:cobblestone");
    }

    private static List<String> fences() {
        return List.of("minecraft:oak_fence", "minecraft:spruce_fence", "minecraft:birch_fence", "minecraft:oak_planks");
    }

    private static Map<String, TemplateSpec> createTemplates() {
        Map<String, TemplateSpec> templates = new LinkedHashMap<>();
        addTemplate(templates, "starter_cabin_7x7", "Starter cabin 7x7", "7x7", 6, "Small rustic cabin with floor, corner logs, walls, pitched roof, windows, door, light, and entry path.");
        addTemplate(templates, "storage_shed_5x7", "Storage shed 5x7", "5x7", 5, "Compact storage shed with wood frame, simple roof, door, side window openings, and light.");
        addTemplate(templates, "bridge_3w", "Bridge 3 wide", "3x11", 3, "Three-wide bridge deck with optional fence rails and lights.");
        addTemplate(templates, "watchtower_5x5", "Watchtower 5x5", "5x5", 9, "Tall lookout tower with log legs, platform, rails, roof, and lighting.");
        addTemplate(templates, "farm_fence_9x9", "Farm fence 9x9", "9x9", 3, "Farm or animal pen perimeter with gate placeholder and corner lighting.");
        addTemplate(templates, "path_lights", "Path lights", "1x15", 3, "Short path strip with paired log-and-light posts.");
        return Map.copyOf(templates);
    }

    private static void addTemplate(Map<String, TemplateSpec> templates, String id, String label, String footprint, int height, String description) {
        templates.put(id, new TemplateSpec(id, label, footprint, height, description));
    }

    private static String alias(String normalized) {
        return switch (normalized) {
            case "basic_5x5_shelter", "basic_house", "build_basic_house", "house", "cabin", "wood_house", "wooden_house", "漂亮木屋", "木屋" -> "starter_cabin_7x7";
            case "large_7x7_house", "large_house", "big_house", "build_large_house" -> "starter_cabin_7x7";
            case "storage", "shed", "warehouse", "仓库" -> "storage_shed_5x7";
            case "bridge", "river_bridge", "桥" -> "bridge_3w";
            case "tower", "watchtower", "塔" -> "watchtower_5x5";
            case "fence", "farm_fence", "pen", "animal_pen", "围栏" -> "farm_fence_9x9";
            case "path", "lights", "path_light", "torch_path", "路灯" -> "path_lights";
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

    public record TemplateSpec(String id, String label, String footprint, int height, String description) {
        public JsonObject catalogJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("label", label);
            json.addProperty("footprint", footprint);
            json.addProperty("height", height);
            json.addProperty("description", description);
            json.addProperty("provider", "builtin_templates");
            json.addProperty("usesDeterministicPlacementQueue", true);
            json.addProperty("supportsCreativePaletteChoice", true);
            json.addProperty("requiresExplicitBuildPermission", true);
            json.addProperty("materialPolicy", "NPC storage first, approved containers second, then gather_materials; player inventory is excluded.");
            return json;
        }
    }
}
