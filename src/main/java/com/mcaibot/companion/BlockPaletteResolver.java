package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BlockPaletteResolver {
    private static final int CONTAINER_SCAN_RADIUS = 16;
    private static final int CONTAINER_SCAN_VERTICAL_RADIUS = 6;

    private BlockPaletteResolver() {
    }

    public static MaterialSnapshot snapshot(ServerPlayer player) {
        int npcPlaceable = 0;
        int chestPlaceable = 0;
        int logs = 0;
        int stone = 0;
        int sand = 0;
        int dirt = 0;
        int glassLike = 0;
        int lights = 0;
        int doors = 0;
        int fences = 0;

        for (Source source : sources(player, false)) {
            Container container = source.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                int count = stack.getCount();
                if (isPlaceable(stack)) {
                    if (source.npcStorage()) {
                        npcPlaceable += count;
                    } else {
                        chestPlaceable += count;
                    }
                }
                if (isLog(stack)) {
                    logs += count;
                }
                if (isStoneLike(stack)) {
                    stone += count;
                }
                if (isSand(stack)) {
                    sand += count;
                }
                if (isDirt(stack)) {
                    dirt += count;
                }
                if (isGlassLike(stack)) {
                    glassLike += count;
                }
                if (isLight(stack)) {
                    lights += count;
                }
                if (itemId(stack).endsWith("_door")) {
                    doors += count;
                }
                if (itemId(stack).endsWith("_fence") || itemId(stack).endsWith("_fence_gate")) {
                    fences += count;
                }
            }
        }

        return new MaterialSnapshot(npcPlaceable, chestPlaceable, logs, stone, sand, dirt, glassLike, lights, doors, fences, NpcManager.isChestMaterialUseApproved(player));
    }

    public static JsonObject materialPlan(ServerPlayer player, StructureBlueprint blueprint) {
        MaterialSnapshot snapshot = snapshot(player);
        int placeableNeeded = 0;
        int optionalNeeded = 0;
        for (BlueprintPlacement placement : blueprint.placements()) {
            if (placement.optional()) {
                optionalNeeded++;
            } else {
                placeableNeeded++;
            }
        }

        JsonObject json = snapshot.toJson();
        json.addProperty("requiredPlacements", placeableNeeded);
        json.addProperty("optionalPlacements", optionalNeeded);
        json.addProperty("availablePlaceableBlocks", snapshot.availablePlaceableBlocks());
        json.addProperty("availableAfterLogPlankPotential", snapshot.availableAfterLogPlankPotential());
        json.addProperty("missingRequiredPlaceableBlocks", Math.max(0, placeableNeeded - snapshot.availableAfterLogPlankPotential()));
        json.addProperty("decorationsMayBeSkipped", true);
        json.addProperty("sourcePriority", "NPC storage -> approved nearby containers -> gather_materials known resource/scout loop");
        return json;
    }

    public static ResolvedBlock consumeForPlacement(ServerPlayer player, BlueprintPlacement placement) {
        for (String candidate : placement.candidates()) {
            ResolvedBlock exact = consumeMatching(player, stack -> matchesCandidate(stack, candidate), candidate);
            if (exact != null) {
                return exact;
            }
        }

        ResolvedBlock roleFallback = consumeMatching(player, stack -> matchesRoleFallback(stack, placement.role()), placement.role());
        if (roleFallback != null) {
            return roleFallback;
        }
        return null;
    }

    public static int countCategory(ServerPlayer player, String category) {
        MaterialSnapshot snapshot = snapshot(player);
        return switch (normalize(category)) {
            case "logs", "wood", "log" -> snapshot.logs();
            case "stone", "cobblestone", "cobblestone_like" -> snapshot.stoneLike();
            case "sand" -> snapshot.sand();
            case "dirt", "soil" -> snapshot.dirt();
            case "glass_like", "glass", "window" -> snapshot.glassLike();
            case "placeable_blocks", "blocks", "building_blocks" -> snapshot.availableAfterLogPlankPotential();
            default -> snapshot.availablePlaceableBlocks();
        };
    }

    private static ResolvedBlock consumeMatching(ServerPlayer player, StackMatcher matcher, String reason) {
        for (Source source : sources(player, true)) {
            Container container = source.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem) || !matcher.matches(stack)) {
                    continue;
                }
                Block block = blockItem.getBlock();
                BlockState state = block.defaultBlockState();
                ItemStack consumed = stack.copy();
                consumed.setCount(1);
                stack.shrink(1);
                container.setChanged();
                return new ResolvedBlock(state, itemId(consumed), source.label(), reason);
            }
        }
        return null;
    }

    private static List<Source> sources(ServerPlayer player, boolean consumeOrder) {
        List<Source> result = new ArrayList<>();
        if (player == null || player.getServer() == null) {
            return result;
        }
        AiNpcEntity npc = NpcManager.activeAiNpc(player.getServer());
        if (npc != null) {
            result.add(new Source(npc.inventory(), "npc_storage", true));
        }
        if (!NpcManager.isChestMaterialUseApproved(player)) {
            return result;
        }

        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - CONTAINER_SCAN_RADIUS,
                center.getY() - CONTAINER_SCAN_VERTICAL_RADIUS,
                center.getZ() - CONTAINER_SCAN_RADIUS,
                center.getX() + CONTAINER_SCAN_RADIUS,
                center.getY() + CONTAINER_SCAN_VERTICAL_RADIUS,
                center.getZ() + CONTAINER_SCAN_RADIUS
        )) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof Container container) {
                result.add(new Source(container, "approved_container@" + pos.toShortString(), false));
            }
        }
        return result;
    }

    private static boolean matchesCandidate(ItemStack stack, String candidate) {
        String target = normalizeItemId(candidate);
        String id = itemId(stack);
        if (id.equals(target)) {
            return true;
        }
        if (target.endsWith("_planks")) {
            return id.endsWith("_planks");
        }
        if (target.endsWith("_log")) {
            return isLog(stack);
        }
        if (target.endsWith("_slab")) {
            return id.endsWith("_slab") || id.endsWith("_planks");
        }
        if (target.endsWith("_fence")) {
            return id.endsWith("_fence") || id.endsWith("_fence_gate") || id.endsWith("_planks");
        }
        if (target.contains("glass")) {
            return isGlassLike(stack);
        }
        return false;
    }

    private static boolean matchesRoleFallback(ItemStack stack, String role) {
        String normalized = normalize(role);
        if (!isPlaceable(stack)) {
            return false;
        }
        return switch (normalized) {
            case "corner_log" -> isLog(stack) || itemId(stack).endsWith("_planks");
            case "foundation", "path" -> isStoneLike(stack) || itemId(stack).endsWith("_planks") || isDirt(stack);
            case "wall", "roof" -> itemId(stack).endsWith("_planks") || isStoneLike(stack) || isLog(stack);
            case "window" -> isGlassLike(stack) || itemId(stack).endsWith("_fence");
            case "door" -> itemId(stack).endsWith("_door") || itemId(stack).endsWith("_fence_gate");
            case "light" -> isLight(stack);
            case "decoration" -> itemId(stack).endsWith("_fence") || itemId(stack).endsWith("_planks") || isLog(stack);
            default -> true;
        };
    }

    static boolean isPlaceable(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem)) {
            return false;
        }
        String id = itemId(stack);
        return !id.equals("minecraft:air")
                && !id.contains("water")
                && !id.contains("lava");
    }

    static boolean isLog(ItemStack stack) {
        String id = itemId(stack);
        return stack.is(ItemTags.LOGS)
                || id.endsWith("_log")
                || id.endsWith("_stem")
                || id.endsWith("_hyphae");
    }

    static boolean isStoneLike(ItemStack stack) {
        String id = itemId(stack);
        return id.equals("minecraft:cobblestone")
                || id.equals("minecraft:cobbled_deepslate")
                || id.equals("minecraft:blackstone")
                || id.equals("minecraft:stone")
                || id.endsWith("_stone")
                || id.contains("cobble");
    }

    static boolean isSand(ItemStack stack) {
        String id = itemId(stack);
        return id.equals("minecraft:sand") || id.equals("minecraft:red_sand");
    }

    static boolean isDirt(ItemStack stack) {
        String id = itemId(stack);
        return id.equals("minecraft:dirt")
                || id.equals("minecraft:coarse_dirt")
                || id.equals("minecraft:rooted_dirt")
                || id.equals("minecraft:grass_block")
                || id.equals("minecraft:podzol");
    }

    static boolean isGlassLike(ItemStack stack) {
        String id = itemId(stack);
        return id.contains("glass") || id.endsWith("_pane");
    }

    static boolean isLight(ItemStack stack) {
        String id = itemId(stack);
        return id.equals("minecraft:torch")
                || id.equals("minecraft:lantern")
                || id.equals("minecraft:soul_torch")
                || id.equals("minecraft:glowstone")
                || id.equals("minecraft:sea_lantern");
    }

    static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
    }

    private static String normalizeItemId(String id) {
        String value = normalize(id);
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    public record MaterialSnapshot(
            int npcPlaceableBlocks,
            int approvedContainerPlaceableBlocks,
            int logs,
            int stoneLike,
            int sand,
            int dirt,
            int glassLike,
            int lights,
            int doors,
            int fences,
            boolean chestMaterialsApproved
    ) {
        public int availablePlaceableBlocks() {
            return npcPlaceableBlocks + approvedContainerPlaceableBlocks;
        }

        public int availableAfterLogPlankPotential() {
            return availablePlaceableBlocks() + logs * 3;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("npcPlaceableBlocks", npcPlaceableBlocks);
            json.addProperty("approvedContainerPlaceableBlocks", approvedContainerPlaceableBlocks);
            json.addProperty("availablePlaceableBlocks", availablePlaceableBlocks());
            json.addProperty("availableAfterLogPlankPotential", availableAfterLogPlankPotential());
            json.addProperty("logs", logs);
            json.addProperty("stoneLike", stoneLike);
            json.addProperty("sand", sand);
            json.addProperty("dirt", dirt);
            json.addProperty("glassLike", glassLike);
            json.addProperty("lights", lights);
            json.addProperty("doors", doors);
            json.addProperty("fences", fences);
            json.addProperty("chestMaterialsApproved", chestMaterialsApproved);
            return json;
        }
    }

    public record ResolvedBlock(BlockState state, String itemId, String source, String reason) {
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("item", itemId);
            json.addProperty("source", source);
            json.addProperty("reason", reason);
            return json;
        }
    }

    private record Source(Container container, String label, boolean npcStorage) {
    }

    @FunctionalInterface
    private interface StackMatcher {
        boolean matches(ItemStack stack);
    }
}
