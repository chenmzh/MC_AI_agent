package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Locale;

public final class ToolSummary {
    private static final int MAX_CONTAINER_SUMMARIES = 12;
    private static final int MAX_TOOLS_PER_SOURCE = 12;

    private ToolSummary() {
    }

    public static JsonObject snapshotFor(ServerPlayer player) {
        JsonObject root = new JsonObject();
        AiNpcEntity npc = NpcManager.activeAiNpc(player.getServer());
        SourceSummary npcInventory = npc == null ? null : summarizeContainer(npc.inventory(), "npc_storage", null, 0.0D);
        SourceSummary inventory = summarizeContainer(player.getInventory(), "inventory", null, 0.0D);
        JsonArray nearbyContainers = nearbyContainerSummaries(player);

        root.addProperty("materialPolicy", "tool/build/craft availability excludes player inventory; nearby containers count only after chest material approval");
        root.addProperty("chestMaterialUseApproved", NpcManager.isChestMaterialUseApproved(player));
        JsonObject availability = new JsonObject();
        availability.add("pickaxe", bestToolJson(player, ToolKind.PICKAXE));
        availability.add("axe", bestToolJson(player, ToolKind.AXE));
        availability.add("weapon", bestToolJson(player, ToolKind.WEAPON));
        availability.addProperty("placeableBlocks", countPlaceableBlocks(player));

        if (npc != null) {
            root.add("npcEquipment", npc.equipmentSummaryJson());
            root.add("npcStorage", npcInventory.toJson());
        }
        root.add("playerInventory", inventory.toJson());
        root.addProperty("playerInventoryPolicy", "visible for context only; NPC tasks do not consume it");
        root.add("nearbyContainers", nearbyContainers);
        root.add("availability", availability);
        return root;
    }

    public static ToolMatch bestTool(ServerPlayer player, ToolKind kind) {
        ToolMatch best = ToolMatch.missing(kind);
        AiNpcEntity npc = NpcManager.activeAiNpc(player.getServer());
        if (npc != null) {
            best = better(best, bestInEquipment(npc, kind));
            best = better(best, bestInContainer(npc.inventory(), kind, "npc_storage"));
        }

        if (NpcManager.isChestMaterialUseApproved(player)) {
            ServerLevel level = player.serverLevel();
            for (BlockPos pos : nearbyContainerPositions(player)) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity instanceof Container container) {
                    String source = "approved_container@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
                    best = better(best, bestInContainer(container, kind, source));
                }
            }
        }
        return best;
    }

    public static int countPlaceableBlocks(ServerPlayer player) {
        int count = 0;
        AiNpcEntity npc = NpcManager.activeAiNpc(player.getServer());
        if (npc != null) {
            count += countPlaceableBlocks(npc.inventory());
        }
        if (NpcManager.isChestMaterialUseApproved(player)) {
            ServerLevel level = player.serverLevel();
            for (BlockPos pos : nearbyContainerPositions(player)) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity instanceof Container container) {
                    count += countPlaceableBlocks(container);
                }
            }
        }
        return count;
    }

    public static void recordAvailabilityFeedback(
            ServerPlayer player,
            Entity npc,
            String taskName,
            ToolKind kind,
            String availableCode,
            String missingCode,
            String fallback
    ) {
        ToolMatch match = bestTool(player, kind);
        if (match.available()) {
            TaskFeedback.info(player, npc, taskName, availableCode, "Available " + kind.label() + ": " + match.describe() + ".");
            return;
        }

        TaskFeedback.warn(player, npc, taskName, missingCode, "No " + kind.label() + " found in NPC storage/equipment or approved nearby containers. " + fallback);
    }

    public static String describeAvailability(ServerPlayer player, ToolKind kind) {
        ToolMatch match = bestTool(player, kind);
        if (match.available()) {
            return "available " + kind.label() + ": " + match.describe();
        }
        return "no " + kind.label() + " found in NPC storage/equipment or approved nearby containers";
    }

    private static JsonObject bestToolJson(ServerPlayer player, ToolKind kind) {
        return bestTool(player, kind).toJson();
    }

    private static ToolMatch bestInContainer(Container container, ToolKind kind, String source) {
        ToolMatch best = ToolMatch.missing(kind);
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !matches(stack, kind)) {
                continue;
            }
            best = better(best, ToolMatch.fromStack(kind, stack, source, slot));
        }
        return best;
    }

    private static ToolMatch bestInEquipment(AiNpcEntity npc, ToolKind kind) {
        ToolMatch best = ToolMatch.missing(kind);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = npc.getItemBySlot(slot);
            if (stack.isEmpty() || !matches(stack, kind)) {
                continue;
            }
            best = better(best, ToolMatch.fromStack(kind, stack, "npc_equipment:" + slot.getName(), -1));
        }
        return best;
    }

    private static ToolMatch better(ToolMatch current, ToolMatch candidate) {
        if (!candidate.available()) {
            return current;
        }
        if (!current.available() || candidate.score() > current.score()) {
            return candidate;
        }
        return current;
    }

    private static JsonArray nearbyContainerSummaries(ServerPlayer player) {
        JsonArray summaries = new JsonArray();
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();

        for (BlockPos pos : nearbyContainerPositions(player)) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof Container container)) {
                continue;
            }

            SourceSummary summary = summarizeContainer(container, "container", pos, distance(center, pos));
            if (summary.tools().size() == 0 && summary.placeableBlocks() == 0) {
                continue;
            }
            summaries.add(summary.toJson());
            if (summaries.size() >= MAX_CONTAINER_SUMMARIES) {
                break;
            }
        }
        return summaries;
    }

    private static Iterable<BlockPos> nearbyContainerPositions(ServerPlayer player) {
        BlockPos center = player.blockPosition();
        int radius = Math.min(McAiConfig.NPC_TASK_RADIUS.get(), 12);
        int verticalRadius = 5;
        return BlockPos.betweenClosed(
                center.getX() - radius,
                center.getY() - verticalRadius,
                center.getZ() - radius,
                center.getX() + radius,
                center.getY() + verticalRadius,
                center.getZ() + radius
        );
    }

    private static SourceSummary summarizeContainer(Container container, String source, BlockPos pos, double distance) {
        JsonArray tools = new JsonArray();
        int placeableBlocks = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            if (stack.getItem() instanceof BlockItem) {
                placeableBlocks += stack.getCount();
            }

            ToolKind kind = classify(stack);
            if (kind == null || tools.size() >= MAX_TOOLS_PER_SOURCE) {
                continue;
            }
            tools.add(ToolMatch.fromStack(kind, stack, source, slot).toJson());
        }
        return new SourceSummary(source, pos, distance, tools, placeableBlocks);
    }

    private static int countPlaceableBlocks(Container container) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.getItem() instanceof BlockItem) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static ToolKind classify(ItemStack stack) {
        if (matches(stack, ToolKind.PICKAXE)) {
            return ToolKind.PICKAXE;
        }
        if (matches(stack, ToolKind.AXE)) {
            return ToolKind.AXE;
        }
        if (matches(stack, ToolKind.WEAPON)) {
            return ToolKind.WEAPON;
        }
        return null;
    }

    private static boolean matches(ItemStack stack, ToolKind kind) {
        return switch (kind) {
            case PICKAXE -> stack.is(ItemTags.PICKAXES);
            case AXE -> stack.is(ItemTags.AXES);
            case WEAPON -> stack.is(ItemTags.SWORDS)
                    || stack.is(ItemTags.AXES)
                    || stack.is(Items.BOW)
                    || stack.is(Items.CROSSBOW)
                    || stack.is(Items.TRIDENT);
        };
    }

    private static int score(ItemStack stack) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
        int score = 0;
        if (itemId.contains("netherite")) {
            score += 6000;
        } else if (itemId.contains("diamond")) {
            score += 5000;
        } else if (itemId.contains("iron")) {
            score += 4000;
        } else if (itemId.contains("stone")) {
            score += 3000;
        } else if (itemId.contains("gold")) {
            score += 2500;
        } else if (itemId.contains("wood")) {
            score += 1000;
        }

        if (stack.isDamageableItem()) {
            score += Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
        } else {
            score += stack.getCount();
        }
        return score;
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public enum ToolKind {
        PICKAXE("pickaxe"),
        AXE("axe"),
        WEAPON("weapon");

        private final String label;

        ToolKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record ToolMatch(
            ToolKind kind,
            boolean available,
            String itemId,
            String source,
            int slot,
            int count,
            int remainingDurability,
            int maxDurability,
            int score
    ) {
        public static ToolMatch missing(ToolKind kind) {
            return new ToolMatch(kind, false, "", "", -1, 0, 0, 0, 0);
        }

        public static ToolMatch fromStack(ToolKind kind, ItemStack stack, String source, int slot) {
            int maxDurability = stack.isDamageableItem() ? stack.getMaxDamage() : 0;
            int remainingDurability = stack.isDamageableItem() ? Math.max(0, maxDurability - stack.getDamageValue()) : 0;
            return new ToolMatch(
                    kind,
                    true,
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    source,
                    slot,
                    stack.getCount(),
                    remainingDurability,
                    maxDurability,
                    ToolSummary.score(stack)
            );
        }

        public String describe() {
            String durability = maxDurability <= 0 ? "" : " durability=" + remainingDurability + "/" + maxDurability;
            return itemId + " from " + source + " slot " + slot + durability;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("kind", kind.label());
            json.addProperty("available", available);
            if (!available) {
                return json;
            }
            json.addProperty("item", itemId);
            json.addProperty("source", source);
            json.addProperty("slot", slot);
            json.addProperty("count", count);
            if (maxDurability > 0) {
                json.addProperty("remainingDurability", remainingDurability);
                json.addProperty("maxDurability", maxDurability);
            }
            return json;
        }
    }

    private record SourceSummary(String source, BlockPos pos, double distance, JsonArray tools, int placeableBlocks) {
        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("source", source);
            if (pos != null) {
                json.addProperty("x", pos.getX());
                json.addProperty("y", pos.getY());
                json.addProperty("z", pos.getZ());
                json.addProperty("distance", round(distance));
            }
            json.addProperty("placeableBlocks", placeableBlocks);
            json.add("tools", tools);
            return json;
        }
    }
}
