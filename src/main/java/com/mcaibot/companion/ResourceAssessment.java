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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Locale;

public final class ResourceAssessment {
    public static final int BASIC_SHELTER_BLOCKS = 94;
    public static final int LARGE_HOUSE_BLOCKS = 184;

    private static final int MAX_CONTAINER_SUMMARIES = 12;
    private static final int MAX_CONTAINER_ITEMS = 8;

    private ResourceAssessment() {
    }

    public static Snapshot snapshot(ServerPlayer player) {
        Counts counts = new Counts();
        Counts pendingChestCounts = new Counts();
        JsonArray containerSummaries = new JsonArray();
        boolean chestApproved = NpcManager.isChestMaterialUseApproved(player);

        AiNpcEntity npc = NpcManager.activeAiNpc(player.getServer());
        if (npc != null) {
            JsonObject npcStorageJson = new JsonObject();
            npcStorageJson.addProperty("source", "npc_storage");
            npcStorageJson.addProperty("approvedForMaterialUse", true);
            countContainer(npc.inventory(), counts, true, null, 0.0D, npcStorageJson);
            if (containerSummaries.size() < MAX_CONTAINER_SUMMARIES) {
                containerSummaries.add(npcStorageJson);
            }
        }

        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        for (BlockPos pos : nearbyContainerPositions(player)) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof Container container)) {
                continue;
            }

            JsonObject containerJson = new JsonObject();
            double distance = distance(center, pos);
            containerJson.addProperty("source", "nearby_container");
            containerJson.addProperty("approvedForMaterialUse", chestApproved);
            countContainer(container, chestApproved ? counts : pendingChestCounts, false, pos, distance, containerJson);
            counts.nearbyContainers++;

            if (containerSummaries.size() < MAX_CONTAINER_SUMMARIES) {
                containerSummaries.add(containerJson);
            }
        }

        ToolSummary.ToolMatch axe = ToolSummary.bestTool(player, ToolSummary.ToolKind.AXE);
        ToolSummary.ToolMatch pickaxe = ToolSummary.bestTool(player, ToolSummary.ToolKind.PICKAXE);
        boolean hasFreeToolSlot = counts.freeInventorySlots > 0
                || (npc != null && npc.inventory().canAddItem(new ItemStack(Items.WOODEN_AXE)));
        boolean canCraftWoodenTool = hasFreeToolSlot && hasToolSticksAndPlanks(counts, 3);
        boolean canCraftStoneTool = hasFreeToolSlot && counts.cobblestoneLike >= 3 && hasToolSticksAndPlanks(counts, 0);

        return new Snapshot(
                counts.inventoryPlaceableBlocks,
                counts.containerPlaceableBlocks,
                counts.inventoryScaffoldBlocks,
                counts.containerScaffoldBlocks,
                counts.logs,
                counts.planks,
                counts.sticks,
                counts.cobblestoneLike,
                counts.wrenches,
                pendingChestCounts.containerPlaceableBlocks,
                pendingChestCounts.containerScaffoldBlocks,
                pendingChestCounts.logs,
                pendingChestCounts.planks,
                pendingChestCounts.sticks,
                pendingChestCounts.cobblestoneLike,
                counts.nearbyContainers,
                counts.freeInventorySlots,
                chestApproved,
                axe.available(),
                axe.available() ? axe.describe() : "",
                pickaxe.available(),
                pickaxe.available() ? pickaxe.describe() : "",
                counts.wrenches > 0,
                canCraftWoodenTool,
                canCraftStoneTool,
                canCraftWoodenTool,
                canCraftStoneTool,
                containerSummaries
        );
    }

    public static JsonObject snapshotFor(ServerPlayer player) {
        return snapshot(player).toJson();
    }

    public static JsonObject blueprintsFor(ServerPlayer player) {
        Snapshot snapshot = snapshot(player);
        JsonObject root = new JsonObject();
        JsonObject basicShelter = new JsonObject();
        BlockPos defaultCenter = player.blockPosition().relative(player.getDirection(), 5);

        basicShelter.addProperty("name", "basic_5x5_shelter");
        basicShelter.addProperty("goal", "build_basic_shelter");
        basicShelter.addProperty("footprint", "5x5");
        basicShelter.addProperty("height", 5);
        basicShelter.addProperty("requiredPlaceableBlocks", BASIC_SHELTER_BLOCKS);
        basicShelter.addProperty("availablePlaceableBlocks", snapshot.placeableBlocks());
        basicShelter.addProperty("availableAfterConvertingLogsToPlanks", snapshot.placeableBlocksAfterPlankConversion());
        basicShelter.addProperty("missingPlaceableBlocks", snapshot.basicShelterMissingBlocks());
        basicShelter.addProperty("ready", snapshot.basicShelterReady());
        basicShelter.addProperty("defaultCenterX", defaultCenter.getX());
        basicShelter.addProperty("defaultCenterY", defaultCenter.getY());
        basicShelter.addProperty("defaultCenterZ", defaultCenter.getZ());
        basicShelter.addProperty("materialPolicy", "uses NPC storage and gathered blocks first; nearby containers only after approval; player inventory is excluded");
        basicShelter.addProperty("phases", "prepare_basic_tools -> gather_wood -> collect_drops -> prepare_build_materials -> build_basic_shelter");
        basicShelter.addProperty("checkpointPolicy", "world-state checkpoint: placed blocks are skipped when rebuilding the queue");
        root.add("basic_5x5_shelter", basicShelter);

        JsonObject largeHouse = new JsonObject();
        BlockPos largeDefaultCenter = player.blockPosition().relative(player.getDirection(), 8);
        largeHouse.addProperty("name", "large_7x7_house");
        largeHouse.addProperty("goal", "build_large_house");
        largeHouse.addProperty("footprint", "7x7");
        largeHouse.addProperty("height", 6);
        largeHouse.addProperty("requiredPlaceableBlocks", LARGE_HOUSE_BLOCKS);
        largeHouse.addProperty("availablePlaceableBlocks", snapshot.placeableBlocks());
        largeHouse.addProperty("availableAfterConvertingLogsToPlanks", snapshot.placeableBlocksAfterPlankConversion());
        largeHouse.addProperty("missingPlaceableBlocks", snapshot.largeHouseMissingBlocks());
        largeHouse.addProperty("ready", snapshot.largeHouseReady());
        largeHouse.addProperty("defaultCenterX", largeDefaultCenter.getX());
        largeHouse.addProperty("defaultCenterY", largeDefaultCenter.getY());
        largeHouse.addProperty("defaultCenterZ", largeDefaultCenter.getZ());
        largeHouse.addProperty("materialPolicy", "uses NPC storage and gathered blocks first; if short, build_large_house gathers logs/crafts planks; nearby containers require approval; player inventory is excluded");
        largeHouse.addProperty("phases", "build_large_house -> bounded gather_logs/craft_planks loops when material is short -> resume same blueprint");
        largeHouse.addProperty("checkpointPolicy", "world-state checkpoint: placed blocks are skipped when rebuilding the queue");
        root.add("large_7x7_house", largeHouse);
        root.add("structureTemplates", BlueprintTemplateRegistry.catalogJson());
        root.add("machineTemplates", MachineTemplateRegistry.catalogJson());
        return root;
    }

    public static void report(ServerPlayer player) {
        Snapshot snapshot = snapshot(player);
        say(player, "Resources: placeable=" + snapshot.placeableBlocks()
                + ", afterLogToPlank=" + snapshot.placeableBlocksAfterPlankConversion()
                + " (npcStorage=" + snapshot.inventoryPlaceableBlocks()
                + ", approvedContainers=" + snapshot.containerPlaceableBlocks()
                + ", unapprovedChestBlocks=" + snapshot.pendingApprovalContainerPlaceableBlocks()
                + "), logs=" + snapshot.logs()
                + ", planks=" + snapshot.planks()
                + ", sticks=" + snapshot.sticks()
                + ", stone=" + snapshot.cobblestoneLike()
                + ", axe=" + readiness(snapshot.canUseOrCraftAxe())
                + ", pickaxe=" + readiness(snapshot.canUseOrCraftPickaxe())
                + ", shelter=" + (snapshot.basicShelterReady() ? "ready" : "missing " + snapshot.basicShelterMissingBlocks() + " blocks")
                + ", largeHouse=" + (snapshot.largeHouseReady() ? "ready" : "missing " + snapshot.largeHouseMissingBlocks() + " blocks")
                + ", chestApproved=" + readiness(snapshot.chestMaterialUseApproved())
                + ".");
    }

    private static void countContainer(Container container, Counts counts, boolean inventory, BlockPos pos, double distance, JsonObject containerJson) {
        int occupiedSlots = 0;
        int placeableBlocks = 0;
        int scaffoldBlocks = 0;
        JsonArray sampleItems = new JsonArray();

        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                if (inventory) {
                    counts.freeInventorySlots++;
                }
                continue;
            }

            occupiedSlots++;
            if (sampleItems.size() < MAX_CONTAINER_ITEMS) {
                JsonObject item = new JsonObject();
                item.addProperty("item", itemId(stack));
                item.addProperty("count", stack.getCount());
                sampleItems.add(item);
            }

            if (isPlaceableBlock(stack)) {
                placeableBlocks += stack.getCount();
                if (isScaffoldBlock(stack)) {
                    scaffoldBlocks += stack.getCount();
                }
            }
            if (stack.is(Items.STICK)) {
                counts.sticks += stack.getCount();
            } else if (isPlank(stack)) {
                counts.planks += stack.getCount();
            } else if (isCraftingLog(stack)) {
                counts.logs += stack.getCount();
            } else if (isStoneToolHeadMaterial(stack)) {
                counts.cobblestoneLike += stack.getCount();
            }
            if (isWrench(stack)) {
                counts.wrenches += stack.getCount();
            }
        }

        if (containerJson != null) {
            if (pos != null) {
                containerJson.addProperty("x", pos.getX());
                containerJson.addProperty("y", pos.getY());
                containerJson.addProperty("z", pos.getZ());
                containerJson.addProperty("distance", round(distance));
            }
            containerJson.addProperty("occupiedSlots", occupiedSlots);
            containerJson.addProperty("placeableBlocks", placeableBlocks);
            containerJson.addProperty("scaffoldBlocks", scaffoldBlocks);
            containerJson.add("sampleItems", sampleItems);
        }

        if (inventory) {
            counts.inventoryPlaceableBlocks += placeableBlocks;
            counts.inventoryScaffoldBlocks += scaffoldBlocks;
            return;
        }

        counts.containerPlaceableBlocks += placeableBlocks;
        counts.containerScaffoldBlocks += scaffoldBlocks;
    }

    private static Iterable<BlockPos> nearbyContainerPositions(ServerPlayer player) {
        BlockPos center = player.blockPosition();
        int radius = Math.min(Math.max(McAiConfig.NPC_TASK_RADIUS.get(), 12), 16);
        int verticalRadius = 6;
        return BlockPos.betweenClosed(
                center.getX() - radius,
                center.getY() - verticalRadius,
                center.getZ() - radius,
                center.getX() + radius,
                center.getY() + verticalRadius,
                center.getZ() + radius
        );
    }

    private static boolean hasToolSticksAndPlanks(Counts counts, int headPlanks) {
        int existingSticksUsed = Math.min(counts.sticks, 2);
        int missingSticks = 2 - existingSticksUsed;
        int planksForSticks = divideCeil(missingSticks, 4) * 2;
        int plankUnitsNeeded = headPlanks + planksForSticks;
        return counts.planks + counts.logs * 4 >= plankUnitsNeeded;
    }

    private static boolean isPlaceableBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem)) {
            return false;
        }

        String id = itemId(stack);
        return !id.equals("minecraft:air")
                && !id.contains("water")
                && !id.contains("lava")
                && !id.contains("button")
                && !id.contains("door")
                && !id.contains("sign")
                && !id.contains("torch")
                && !id.contains("rail")
                && !id.contains("carpet")
                && !id.contains("pane");
    }

    private static boolean isScaffoldBlock(ItemStack stack) {
        if (!isPlaceableBlock(stack)) {
            return false;
        }

        String id = itemId(stack);
        return !id.contains("button")
                && !id.contains("door")
                && !id.contains("sign")
                && !id.contains("torch")
                && !id.contains("rail")
                && !id.contains("carpet")
                && !id.contains("pane");
    }

    private static boolean isPlank(ItemStack stack) {
        return itemId(stack).endsWith("_planks");
    }

    private static boolean isCraftingLog(ItemStack stack) {
        String id = itemId(stack);
        return stack.is(ItemTags.LOGS)
                || id.endsWith("_log")
                || id.endsWith("_stem")
                || id.endsWith("_hyphae");
    }

    private static boolean isStoneToolHeadMaterial(ItemStack stack) {
        return stack.is(Items.COBBLESTONE)
                || stack.is(Items.COBBLED_DEEPSLATE)
                || stack.is(Items.BLACKSTONE);
    }

    private static boolean isWrench(ItemStack stack) {
        String id = itemId(stack);
        return id.equals("create:wrench") || id.endsWith(":wrench") || id.endsWith("_wrench") || id.contains("wrench");
    }

    private static String readiness(boolean ready) {
        return ready ? "ready" : "missing";
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
    }

    private static int divideCeil(int value, int divisor) {
        if (value <= 0) {
            return 0;
        }
        return (value + divisor - 1) / divisor;
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static void say(ServerPlayer player, String message) {
        NpcChat.say(player, message);
    }

    public record Snapshot(
            int inventoryPlaceableBlocks,
            int containerPlaceableBlocks,
            int inventoryScaffoldBlocks,
            int containerScaffoldBlocks,
            int logs,
            int planks,
            int sticks,
            int cobblestoneLike,
            int wrenches,
            int pendingApprovalContainerPlaceableBlocks,
            int pendingApprovalContainerScaffoldBlocks,
            int pendingApprovalLogs,
            int pendingApprovalPlanks,
            int pendingApprovalSticks,
            int pendingApprovalCobblestoneLike,
            int nearbyContainers,
            int freeInventorySlots,
            boolean chestMaterialUseApproved,
            boolean hasAxe,
            String bestAxe,
            boolean hasPickaxe,
            String bestPickaxe,
            boolean hasWrench,
            boolean canCraftWoodenAxe,
            boolean canCraftStoneAxe,
            boolean canCraftWoodenPickaxe,
            boolean canCraftStonePickaxe,
            JsonArray nearbyContainerSummaries
    ) {
        public int placeableBlocks() {
            return inventoryPlaceableBlocks + containerPlaceableBlocks;
        }

        public int scaffoldBlocks() {
            return inventoryScaffoldBlocks + containerScaffoldBlocks;
        }

        public int plankPotential() {
            return planks + logs * 4;
        }

        public int placeableBlocksAfterPlankConversion() {
            return placeableBlocks() + logs * 3;
        }

        public boolean canUseOrCraftAxe() {
            return hasAxe || canCraftWoodenAxe || canCraftStoneAxe;
        }

        public boolean canUseOrCraftPickaxe() {
            return hasPickaxe || canCraftWoodenPickaxe || canCraftStonePickaxe;
        }

        public boolean basicShelterReady() {
            return placeableBlocksAfterPlankConversion() >= BASIC_SHELTER_BLOCKS;
        }

        public int basicShelterMissingBlocks() {
            return Math.max(0, BASIC_SHELTER_BLOCKS - placeableBlocksAfterPlankConversion());
        }

        public boolean largeHouseReady() {
            return placeableBlocksAfterPlankConversion() >= LARGE_HOUSE_BLOCKS;
        }

        public int largeHouseMissingBlocks() {
            return Math.max(0, LARGE_HOUSE_BLOCKS - placeableBlocksAfterPlankConversion());
        }

        public JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("sources", "npc_storage_and_approved_nearby_containers");
            root.addProperty("materialPolicy", "player inventory is excluded; nearby container materials require explicit approval");
            root.addProperty("chestMaterialUseApproved", chestMaterialUseApproved);
            root.addProperty("nearbyContainers", nearbyContainers);
            root.addProperty("freeInventorySlots", freeInventorySlots);
            root.addProperty("freeNpcStorageSlots", freeInventorySlots);

            JsonObject materials = new JsonObject();
            materials.addProperty("logs", logs);
            materials.addProperty("planks", planks);
            materials.addProperty("plankPotential", plankPotential());
            materials.addProperty("sticks", sticks);
            materials.addProperty("cobblestoneLike", cobblestoneLike);
            materials.addProperty("wrenches", wrenches);
            materials.addProperty("pendingApprovalChestLogs", pendingApprovalLogs);
            materials.addProperty("pendingApprovalChestPlanks", pendingApprovalPlanks);
            materials.addProperty("pendingApprovalChestSticks", pendingApprovalSticks);
            materials.addProperty("pendingApprovalChestCobblestoneLike", pendingApprovalCobblestoneLike);
            root.add("materials", materials);

            JsonObject blocks = new JsonObject();
            blocks.addProperty("placeableTotal", placeableBlocks());
            blocks.addProperty("placeableAfterConvertingLogsToPlanks", placeableBlocksAfterPlankConversion());
            blocks.addProperty("placeableNpcStorage", inventoryPlaceableBlocks);
            blocks.addProperty("placeableApprovedContainers", containerPlaceableBlocks);
            blocks.addProperty("placeablePendingApprovalContainers", pendingApprovalContainerPlaceableBlocks);
            blocks.addProperty("scaffoldTotal", scaffoldBlocks());
            blocks.addProperty("scaffoldNpcStorage", inventoryScaffoldBlocks);
            blocks.addProperty("scaffoldApprovedContainers", containerScaffoldBlocks);
            blocks.addProperty("scaffoldPendingApprovalContainers", pendingApprovalContainerScaffoldBlocks);
            root.add("blocks", blocks);

            JsonObject tools = new JsonObject();
            tools.addProperty("hasAxe", hasAxe);
            tools.addProperty("bestAxe", bestAxe);
            tools.addProperty("canCraftWoodenAxe", canCraftWoodenAxe);
            tools.addProperty("canCraftStoneAxe", canCraftStoneAxe);
            tools.addProperty("canUseOrCraftAxe", canUseOrCraftAxe());
            tools.addProperty("hasPickaxe", hasPickaxe);
            tools.addProperty("bestPickaxe", bestPickaxe);
            tools.addProperty("canCraftWoodenPickaxe", canCraftWoodenPickaxe);
            tools.addProperty("canCraftStonePickaxe", canCraftStonePickaxe);
            tools.addProperty("canUseOrCraftPickaxe", canUseOrCraftPickaxe());
            tools.addProperty("hasWrench", hasWrench);
            root.add("tools", tools);

            JsonObject basicShelter = new JsonObject();
            basicShelter.addProperty("name", "basic_5x5_shelter");
            basicShelter.addProperty("requiredPlaceableBlocks", BASIC_SHELTER_BLOCKS);
            basicShelter.addProperty("availableAfterConvertingLogsToPlanks", placeableBlocksAfterPlankConversion());
            basicShelter.addProperty("ready", basicShelterReady());
            basicShelter.addProperty("missingPlaceableBlocks", basicShelterMissingBlocks());
            basicShelter.addProperty("summary", "5x5 footprint: floor 25, walls/window/door shell 44, roof 25.");
            root.add("blueprintBasicShelter", basicShelter);

            JsonObject largeHouse = new JsonObject();
            largeHouse.addProperty("name", "large_7x7_house");
            largeHouse.addProperty("requiredPlaceableBlocks", LARGE_HOUSE_BLOCKS);
            largeHouse.addProperty("availableAfterConvertingLogsToPlanks", placeableBlocksAfterPlankConversion());
            largeHouse.addProperty("ready", largeHouseReady());
            largeHouse.addProperty("missingPlaceableBlocks", largeHouseMissingBlocks());
            largeHouse.addProperty("summary", "7x7 footprint: floor 49, taller wall/window/door shell 86, roof 49; build_large_house can gather wood and resume automatically.");
            root.add("blueprintLargeHouse", largeHouse);

            root.add("nearbyContainerSummaries", nearbyContainerSummaries);
            return root;
        }
    }

    private static final class Counts {
        private int inventoryPlaceableBlocks;
        private int containerPlaceableBlocks;
        private int inventoryScaffoldBlocks;
        private int containerScaffoldBlocks;
        private int logs;
        private int planks;
        private int sticks;
        private int cobblestoneLike;
        private int wrenches;
        private int nearbyContainers;
        private int freeInventorySlots;
    }
}
