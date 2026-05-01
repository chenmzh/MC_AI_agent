package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcaibot.companion.tasks.TaskControllerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record BridgeContext(ServerPlayer player, String message, JsonObject payload) {
    public static BridgeContext fromPlayer(ServerPlayer player, String message) {
        return fromPlayer(player, message, null);
    }

    public static BridgeContext fromPlayer(ServerPlayer player, String message, NpcProfileStore profileStore) {
        return fromPlayer(player, message, profileStore, false);
    }

    public static BridgeContext fromAutonomy(ServerPlayer player, String message, NpcProfileStore profileStore) {
        return fromPlayer(player, message, profileStore, true);
    }

    public static BridgeContext fromAutonomy(ServerPlayer player, String message, NpcProfileStore profileStore, String style, int cooldownMs, String guidance) {
        return fromAutonomy(player, message, profileStore, style, cooldownMs, guidance, "");
    }

    public static BridgeContext fromAutonomy(ServerPlayer player, String message, NpcProfileStore profileStore, String style, int cooldownMs, String guidance, String reason) {
        BridgeContext context = fromPlayer(player, message, profileStore, true);
        JsonObject rootContext = context.payload().getAsJsonObject("context");
        JsonObject autonomy = rootContext.getAsJsonObject("autonomy");
        autonomy.addProperty("trigger", "autonomy_tick");
        autonomy.addProperty("style", style);
        autonomy.addProperty("cooldownMs", cooldownMs);
        autonomy.addProperty("guidance", guidance);
        if (reason != null && !reason.isBlank()) {
            autonomy.addProperty("reason", reason);
        }
        refreshObservationFrame(context);
        return context;
    }

    public static BridgeContext fromCompanionLoop(
            ServerPlayer player,
            String message,
            NpcProfileStore profileStore,
            String style,
            int cooldownMs,
            String guidance,
            String trigger,
            String reason
    ) {
        BridgeContext context = fromPlayer(player, message, profileStore, true);
        JsonObject rootContext = context.payload().getAsJsonObject("context");
        JsonObject autonomy = rootContext.getAsJsonObject("autonomy");
        autonomy.addProperty("trigger", "companion_loop");
        autonomy.addProperty("style", style);
        autonomy.addProperty("cooldownMs", cooldownMs);
        autonomy.addProperty("guidance", guidance);
        autonomy.addProperty("reason", reason);

        JsonObject companion = rootContext.getAsJsonObject("companion");
        companion.addProperty("activeTrigger", trigger);
        companion.addProperty("activeReason", reason);
        companion.addProperty("cooldownMs", cooldownMs);
        companion.addProperty("style", style);
        refreshObservationFrame(context);
        return context;
    }

    private static BridgeContext fromPlayer(ServerPlayer player, String message, NpcProfileStore profileStore, boolean autonomous) {
        JsonObject root = new JsonObject();
        root.addProperty("player", player.getGameProfile().getName());
        root.addProperty("message", message);
        root.addProperty("requestType", autonomous ? "autonomy" : "player");

        JsonObject context = new JsonObject();
        JsonObject server = new JsonObject();
        server.addProperty("version", player.getServer().getServerVersion());
        server.addProperty("loader", "NeoForge");
        server.addProperty("loaderVersion", "21.1.227");
        context.add("server", server);
        context.add("capabilities", capabilities());

        JsonObject npcJson = NpcManager.describeFor(player);
        context.add("npc", npcJson);
        if (profileStore != null) {
            String profileId = npcJson.has("profileId") ? npcJson.get("profileId").getAsString() : NpcProfile.DEFAULT_ID;
            context.add("persona", profileStore.currentPersonaJson(profileId));
            context.add("availablePersonas", profileStore.enabledProfilesJson());
        }

        JsonObject playerJson = new JsonObject();
        playerJson.addProperty("name", player.getGameProfile().getName());
        playerJson.addProperty("dimension", player.level().dimension().location().toString());
        playerJson.addProperty("x", round(player.getX()));
        playerJson.addProperty("y", round(player.getY()));
        playerJson.addProperty("z", round(player.getZ()));
        playerJson.addProperty("health", round(player.getHealth()));
        context.add("player", playerJson);

        JsonArray nearbyEntities = new JsonArray();
        AABB box = player.getBoundingBox().inflate(McAiConfig.SCAN_RADIUS.get());
        for (Entity entity : player.serverLevel().getEntities(player, box, Entity::isAlive)) {
            JsonObject entityJson = new JsonObject();
            entityJson.addProperty("name", entity.getName().getString());
            entityJson.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            entityJson.addProperty("distance", round(entity.distanceTo(player)));
            nearbyEntities.add(entityJson);
            if (nearbyEntities.size() >= 40) {
                break;
            }
        }
        context.add("nearbyEntities", nearbyEntities);
        context.add("inventory", inventorySummary(player));
        context.add("nearbyBlocks", nearbyBlockSummary(player));
        context.add("nearbyContainers", nearbyContainerSummary(player));
        context.add("tools", ToolSummary.snapshotFor(player));
        context.add("crafting", craftingSummary(player));
        context.add("resources", ResourceAssessment.snapshotFor(player));
        context.add("blueprints", ResourceAssessment.blueprintsFor(player));
        context.add("structureBlueprints", BlueprintTemplateRegistry.catalogJson());
        context.add("machineTemplates", MachineBuildController.catalogJson(player));
        context.add("travelPolicy", TravelController.policyJson());
        context.add("targetGrounding", TargetResolver.contextSnapshot(player));
        context.add("targetResolver", TargetResolver.contractJson());
        context.add("survivalEnvironment", SurvivalEnvironment.snapshotFor(player));
        context.add("modded", ModInteractionManager.snapshotFor(player));
        JsonObject executionFeedback = TaskFeedback.snapshotJson(player, npcJson);
        context.add("executionFeedback", executionFeedback);
        if (executionFeedback.has("latestResults") && executionFeedback.get("latestResults").isJsonArray()) {
            context.add("latestTaskResults", executionFeedback.getAsJsonArray("latestResults"));
        }
        context.add("capabilityGaps", CapabilityGap.fromExecutionFeedback(executionFeedback));
        context.add("complexPlan", PlanManager.snapshotFor(player));
        context.add("planFeedback", PlanManager.feedbackFor(player));
        context.add("worldKnowledge", WorldKnowledge.snapshotFor(player));
        context.add("objects", WorldObjectSummary.snapshotFor(player, context));
        JsonObject social = SocialMemory.snapshotFor(player, npcJson);
        JsonObject relationship = relationshipContext(player, npcJson, social, profileStore);
        JsonObject companionLoop = CompanionLoop.snapshotFor(player, npcJson);
        context.add("social", social);
        context.add("relationship", relationship);
        context.add("companion", companionLoop.deepCopy());
        context.add("companionLoop", companionLoop);
        if (autonomous) {
            context.add("autonomy", autonomyContext());
        }
        context.add("skillRegistry", SkillRegistry.catalogJson());
        context.add("actionPrimitives", SkillRegistry.actionPrimitivesJson());
        context.add("agentLoop", AgentLoop.contractJson());
        JsonObject observationFrame = ObservationFrame.fromContext(context);
        context.add("observationFrame", observationFrame);
        root.add("observationFrame", observationFrame.deepCopy());
        root.add("context", context);
        return new BridgeContext(player, message, root);
    }

    private static JsonObject autonomyContext() {
        JsonObject autonomy = new JsonObject();
        autonomy.addProperty("enabled", true);
        autonomy.addProperty("safeActionsOnly", true);
        autonomy.addProperty("mustNotInterruptTasks", true);
        autonomy.addProperty("maxResponseStyle", "one_brief_message_or_none");
        return autonomy;
    }

    private static JsonObject capabilities() {
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("agentContracts", true);
        capabilities.addProperty("observationFrame", true);
        capabilities.addProperty("goalSpec", true);
        capabilities.addProperty("actionCall", true);
        capabilities.addProperty("actionResult", true);
        capabilities.addProperty("skillSpecRegistry", true);
        capabilities.addProperty("taskGraphPlanning", true);
        capabilities.addProperty("agentLoop", true);
        capabilities.addProperty("multiNpcProfiles", true);
        capabilities.addProperty("multipleSpawnedNpcs", true);
        capabilities.addProperty("targetScope", true);
        capabilities.addProperty("perNpcRuntimeSnapshots", true);
        capabilities.addProperty("perNpcRuntimeRegistry", true);
        capabilities.addProperty("singleTaskRuntime", true);
        capabilities.addProperty("groupWorkMode", "delegate_one_worker_then_follow_standby");
        capabilities.addProperty("parallelNpcWork", false);
        capabilities.addProperty("playerInventoryMaterials", false);
        capabilities.addProperty("chestMaterialsRequireApproval", true);
        capabilities.addProperty("timedBlockBreaking", true);
        capabilities.addProperty("toolAwareHarvesting", true);
        capabilities.addProperty("structuredClarificationRecovery", true);
        capabilities.addProperty("worldKnowledgeShortTermMemory", true);
        capabilities.addProperty("worldKnowledgeLongTermMap", true);
        capabilities.addProperty("socialMemory", true);
        capabilities.addProperty("relationshipState", true);
        capabilities.addProperty("relationshipContext", true);
        capabilities.addProperty("relationshipPreferences", true);
        capabilities.addProperty("socialEvents", true);
        capabilities.addProperty("companionLoop", true);
        capabilities.addProperty("proactiveCompanionTriggers", true);
        capabilities.addProperty("boundedAutonomousResourceSearch", true);
        capabilities.addProperty("survivalEnvironment", true);
        capabilities.addProperty("highAutonomySafetyPolicy", true);
        capabilities.addProperty("farmingActions", true);
        capabilities.addProperty("animalCareActions", true);
        capabilities.addProperty("safeHuntingActions", true);
        capabilities.addProperty("redstoneTemplateActions", true);
        capabilities.addProperty("materialGatheringAction", true);
        capabilities.addProperty("structureBlueprintTemplates", true);
        capabilities.addProperty("structurePreviewAction", true);
        capabilities.addProperty("structureBuildAction", true);
        capabilities.addProperty("machineBlueprintTemplates", true);
        capabilities.addProperty("machinePreviewAction", true);
        capabilities.addProperty("machineBuildAction", true);
        capabilities.addProperty("machinePlanAuthorization", true);
        capabilities.addProperty("createMachineBuilds", false);
        capabilities.addProperty("travelController", true);
        capabilities.addProperty("litematicaBlueprintProvider", false);
        capabilities.addProperty("taskControllerRuntime", false);
        capabilities.addProperty("collectItemsControllerRuntime", true);
        capabilities.add("taskControllerCatalog", TaskControllerRegistry.catalogJson());
        capabilities.addProperty("createFamilyInspection", true);
        capabilities.addProperty("createFamilySafeWrench", true);
        capabilities.addProperty("aeronauticsAutopilot", false);
        capabilities.addProperty("defaultProtection", true);
        capabilities.addProperty("neverAttackPlayers", true);
        return capabilities;
    }

    private static JsonObject relationshipContext(ServerPlayer player, JsonObject npcJson, JsonObject social, NpcProfileStore profileStore) {
        JsonObject relationship = AgentJson.object(social, "relationship");
        relationship.add("events", AgentJson.array(social, "recentEvents"));
        relationship.add("preferences", relationshipPreferences(npcJson, profileStore));
        if (!relationship.has("playerName")) {
            relationship.addProperty("playerName", player.getGameProfile().getName());
        }
        if (!relationship.has("playerUuid")) {
            relationship.addProperty("playerUuid", player.getUUID().toString());
        }
        if (!relationship.has("profileId")) {
            relationship.addProperty("profileId", AgentJson.string(npcJson, "profileId", NpcProfile.DEFAULT_ID));
        }
        return relationship;
    }

    private static JsonArray relationshipPreferences(JsonObject npcJson, NpcProfileStore profileStore) {
        JsonArray preferences = new JsonArray();
        if (profileStore == null) {
            return preferences;
        }

        String profileId = AgentJson.string(npcJson, "profileId", NpcProfile.DEFAULT_ID);
        NpcProfile profile = profileStore.findEnabled(profileId).orElseGet(profileStore::defaultProfile);
        addRelationshipPreference(preferences, "relationship.default_role", profile.defaultRole());
        addRelationshipPreference(preferences, "relationship.speaking_style", profile.style());
        addRelationshipPreference(preferences, "relationship.personality", profile.personality());
        addRelationshipPreference(preferences, "relationship.skin", profile.skin());
        if (!profile.owner().isBlank()) {
            addRelationshipPreference(preferences, "relationship.owner", profile.owner());
        }
        return preferences;
    }

    private static void addRelationshipPreference(JsonArray preferences, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        JsonObject preference = new JsonObject();
        preference.addProperty("key", key);
        preference.addProperty("value", value);
        preference.addProperty("source", "npc_profile");
        preferences.add(preference);
    }

    private static JsonArray inventorySummary(ServerPlayer player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(itemId, stack.getCount(), Integer::sum);
        }

        JsonArray items = new JsonArray();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("item", entry.getKey());
            item.addProperty("count", entry.getValue());
            items.add(item);
            if (items.size() >= 60) {
                break;
            }
        }
        return items;
    }

    private static JsonArray nearbyBlockSummary(ServerPlayer player) {
        int radius = Math.min(Math.max(McAiConfig.SCAN_RADIUS.get(), 16), 24);
        int verticalRadius = 8;
        BlockPos center = player.blockPosition();
        Map<String, JsonObject> summaries = new LinkedHashMap<>();

        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - radius,
                center.getY() - verticalRadius,
                center.getZ() - radius,
                center.getX() + radius,
                center.getY() + verticalRadius,
                center.getZ() + radius
        )) {
            BlockState state = player.serverLevel().getBlockState(pos);
            String category = usefulBlockCategory(state);
            if (category == null) {
                continue;
            }

            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            JsonObject summary = summaries.computeIfAbsent(blockId, key -> {
                JsonObject value = new JsonObject();
                value.addProperty("block", key);
                value.addProperty("category", category);
                value.addProperty("count", 0);
                value.addProperty("nearestDistance", Double.MAX_VALUE);
                return value;
            });

            summary.addProperty("count", summary.get("count").getAsInt() + 1);
            double distance = distance(center, pos);
            if (distance < summary.get("nearestDistance").getAsDouble()) {
                summary.addProperty("nearestDistance", round(distance));
            }
        }

        JsonArray blocks = new JsonArray();
        for (JsonObject summary : summaries.values()) {
            blocks.add(summary);
            if (blocks.size() >= 80) {
                break;
            }
        }
        return blocks;
    }

    private static JsonArray nearbyContainerSummary(ServerPlayer player) {
        int radius = Math.min(Math.max(McAiConfig.SCAN_RADIUS.get(), 16), 24);
        int verticalRadius = 6;
        BlockPos center = player.blockPosition();
        JsonArray containers = new JsonArray();

        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - radius,
                center.getY() - verticalRadius,
                center.getZ() - radius,
                center.getX() + radius,
                center.getY() + verticalRadius,
                center.getZ() + radius
        )) {
            BlockEntity blockEntity = player.serverLevel().getBlockEntity(pos);
            if (!(blockEntity instanceof Container container)) {
                continue;
            }

            JsonObject containerJson = new JsonObject();
            containerJson.addProperty("block", BuiltInRegistries.BLOCK.getKey(player.serverLevel().getBlockState(pos).getBlock()).toString());
            containerJson.addProperty("x", pos.getX());
            containerJson.addProperty("y", pos.getY());
            containerJson.addProperty("z", pos.getZ());
            containerJson.addProperty("distance", round(distance(center, pos)));
            containerJson.addProperty("approvedForMaterialUse", NpcManager.isChestMaterialUseApproved(player));

            JsonArray items = new JsonArray();
            int occupiedSlots = 0;
            int totalItems = 0;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }

                occupiedSlots++;
                totalItems += stack.getCount();
                if (items.size() < 12) {
                    JsonObject item = new JsonObject();
                    item.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                    item.addProperty("count", stack.getCount());
                    items.add(item);
                }
            }

            containerJson.addProperty("occupiedSlots", occupiedSlots);
            containerJson.addProperty("totalItems", totalItems);
            containerJson.add("sampleItems", items);
            containers.add(containerJson);
            if (containers.size() >= 12) {
                break;
            }
        }

        return containers;
    }

    private static JsonObject craftingSummary(ServerPlayer player) {
        CraftingMaterialCounts counts = countCraftingMaterials(player);
        AiNpcEntity npc = NpcManager.activeAiNpc(player.getServer());
        boolean hasFreeToolSlot = npc != null && npc.inventory().canAddItem(new ItemStack(Items.WOODEN_AXE));

        JsonObject root = new JsonObject();
        root.addProperty("basicToolAutoCrafting", true);
        root.addProperty("sources", "npc_storage_and_approved_nearby_containers");
        root.addProperty("materialPolicy", "player inventory is excluded; nearby container materials require explicit approval");
        root.addProperty("chestMaterialUseApproved", NpcManager.isChestMaterialUseApproved(player));
        root.addProperty("freeInventorySlot", hasFreeToolSlot);
        root.addProperty("freeNpcStorageSlot", hasFreeToolSlot);
        root.addProperty("requiresManualCrafting", false);

        JsonObject materials = new JsonObject();
        materials.addProperty("sticks", counts.sticks);
        materials.addProperty("planks", counts.planks);
        materials.addProperty("logs", counts.logs);
        materials.addProperty("cobblestoneLike", counts.stoneHeadMaterials);
        root.add("materials", materials);

        boolean canCraftWoodenTool = hasFreeToolSlot && hasToolSticksAndPlanks(counts, 3);
        boolean canCraftStoneTool = hasFreeToolSlot && counts.stoneHeadMaterials >= 3 && hasToolSticksAndPlanks(counts, 0);

        JsonObject craftable = new JsonObject();
        craftable.addProperty("wooden_axe", canCraftWoodenTool);
        craftable.addProperty("wooden_pickaxe", canCraftWoodenTool);
        craftable.addProperty("stone_axe", canCraftStoneTool);
        craftable.addProperty("stone_pickaxe", canCraftStoneTool);
        root.add("craftable", craftable);
        return root;
    }

    private static CraftingMaterialCounts countCraftingMaterials(ServerPlayer player) {
        CraftingMaterialCounts counts = new CraftingMaterialCounts();
        AiNpcEntity npc = NpcManager.activeAiNpc(player.getServer());
        if (npc != null) {
            countCraftingMaterials(npc.inventory(), counts);
        }
        if (!NpcManager.isChestMaterialUseApproved(player)) {
            return counts;
        }

        int radius = Math.min(McAiConfig.SCAN_RADIUS.get(), 12);
        int verticalRadius = 5;
        BlockPos center = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - radius,
                center.getY() - verticalRadius,
                center.getZ() - radius,
                center.getX() + radius,
                center.getY() + verticalRadius,
                center.getZ() + radius
        )) {
            BlockEntity blockEntity = player.serverLevel().getBlockEntity(pos);
            if (blockEntity instanceof Container container) {
                countCraftingMaterials(container, counts);
            }
        }
        return counts;
    }

    private static void countCraftingMaterials(Container container, CraftingMaterialCounts counts) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(Items.STICK)) {
                counts.sticks += stack.getCount();
            } else if (isPlank(stack)) {
                counts.planks += stack.getCount();
            } else if (isCraftingLog(stack)) {
                counts.logs += stack.getCount();
            } else if (isStoneToolHeadMaterial(stack)) {
                counts.stoneHeadMaterials += stack.getCount();
            }
        }
    }

    private static boolean hasToolSticksAndPlanks(CraftingMaterialCounts counts, int headPlanks) {
        int existingSticksUsed = Math.min(counts.sticks, 2);
        int missingSticks = 2 - existingSticksUsed;
        int planksForSticks = divideCeil(missingSticks, 4) * 2;
        int plankUnitsNeeded = headPlanks + planksForSticks;
        return counts.planks + counts.logs * 4 >= plankUnitsNeeded;
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

    private static String usefulBlockCategory(BlockState state) {
        if (state.isAir()) {
            return null;
        }
        if (state.is(BlockTags.LOGS)) {
            return "logs";
        }
        if (state.is(Blocks.CRAFTING_TABLE)) {
            return "crafting_table";
        }
        if (isOre(state)) {
            return "ores";
        }
        if (state.hasBlockEntity()) {
            return "block_entity";
        }
        return null;
    }

    private static boolean isOre(BlockState state) {
        return state.is(BlockTags.COAL_ORES)
                || state.is(BlockTags.COPPER_ORES)
                || state.is(BlockTags.IRON_ORES)
                || state.is(BlockTags.GOLD_ORES)
                || state.is(BlockTags.REDSTONE_ORES)
                || state.is(BlockTags.LAPIS_ORES)
                || state.is(BlockTags.EMERALD_ORES)
                || state.is(BlockTags.DIAMOND_ORES);
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

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
    }

    private static int divideCeil(int value, int divisor) {
        if (value <= 0) {
            return 0;
        }
        return (value + divisor - 1) / divisor;
    }

    private static void refreshObservationFrame(BridgeContext bridgeContext) {
        JsonObject context = bridgeContext.payload().getAsJsonObject("context");
        JsonObject observationFrame = ObservationFrame.fromContext(context);
        context.add("observationFrame", observationFrame);
        bridgeContext.payload().add("observationFrame", observationFrame.deepCopy());
    }

    private static final class CraftingMaterialCounts {
        private int sticks;
        private int planks;
        private int logs;
        private int stoneHeadMaterials;
    }
}
