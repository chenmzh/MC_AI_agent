package com.mcaibot.companion;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.mcaibot.companion.runtime.NpcRuntime;
import com.mcaibot.companion.runtime.NpcRuntimeRegistry;
import com.mcaibot.companion.runtime.NpcTaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class NpcManager {
    private static final String TAG_COMPANION = "mcai_npc";
    private static final String TAG_PROFILE_PREFIX = "mcai_profile_";
    private static final String CHEST_APPROVAL_DATA_NAME = "mc_ai_companion_chest_approvals";
    private static final SavedData.Factory<ChestApprovalSavedData> CHEST_APPROVAL_FACTORY = new SavedData.Factory<>(
            ChestApprovalSavedData::new,
            ChestApprovalSavedData::load
    );
    private static final int DEFAULT_HARVEST_FOLLOW_SECONDS = 90;
    private static final int MIN_HARVEST_FOLLOW_SECONDS = 20;
    private static final int MAX_HARVEST_FOLLOW_SECONDS = 300;
    private static final int TASK_OWNER_PAUSE_TICKS = 20 * 600;
    private static final int MIN_MINING_TOOL_DURABILITY = 4;
    private static final int MIN_HARVEST_TOOL_DURABILITY = 2;
    private static final int TOOL_STICK_COST = 2;
    private static final int TOOL_HEAD_MATERIAL_COST = 3;
    private static final int PLANKS_PER_LOG = 4;
    private static final int STICKS_PER_CRAFT = 4;
    private static final int PLANKS_PER_STICK_CRAFT = 2;
    private static final double HARVEST_HIGH_LOG_BONUS = 12.0D;
    private static final double HARVEST_SUPPORT_PENALTY = 18.0D;
    private static final double INTERACTION_REACH_SQR = 25.0D;
    private static final double NAVIGATION_TARGET_EPSILON_SQR = 1.0D;
    private static final int OVERHEAD_HARVEST_RADIUS = 1;
    private static final int OVERHEAD_HARVEST_HEIGHT = 7;
    private static final int AUTO_EQUIP_INTERVAL_TICKS = 40;
    private static final int AUTO_PICKUP_INTERVAL_TICKS = 10;
    private static final double AUTO_PICKUP_RADIUS = 7.0D;
    private static final double AUTO_PICKUP_OWNER_RADIUS = 10.0D;
    private static final double AUTO_PICKUP_REACH_SQR = 3.0D;
    private static final int NEARBY_CONTAINER_LIMIT = 12;
    private static final int NEARBY_CONTAINER_RADIUS = 12;
    private static final int NEARBY_CONTAINER_VERTICAL_RADIUS = 5;
    private static final int MAX_PENDING_BUILD_GATHER_ATTEMPTS = 6;
    private static final int PENDING_BUILD_HARVEST_SECONDS = 120;
    private static final int MAX_PENDING_REPAIR_GATHER_ATTEMPTS = 4;
    private static final int PENDING_REPAIR_HARVEST_SECONDS = 90;
    private static final double KNOWN_RESOURCE_TRAVEL_MAX_DISTANCE = TravelController.KNOWN_RESOURCE_MAX_DISTANCE;
    private static final int KNOWN_RESOURCE_MAX_AGE_TICKS = 20 * 60 * 30;
    private static final int KNOWN_RESOURCE_TRAVEL_TIMEOUT_TICKS = 20 * 90;
    private static final int KNOWN_RESOURCE_BLOCKED_TICKS = 20 * 90;
    private static final int HARVEST_SCOUT_RADIUS = TravelController.SCOUT_RING_MAX_RADIUS;
    private static final int HARVEST_SCOUT_LEG_TICKS = 20 * 5;
    private static final int NAVIGATION_STUCK_RETRY_TICKS = 80;
    private static final int NAVIGATION_BLOCKED_MEMORY_TICKS = 120;
    private static final int NAVIGATION_BLOCKED_TARGET_TICKS = 20 * 20;
    private static final double NAVIGATION_STUCK_MIN_PROGRESS_SQR = 0.04D;
    private static final double NAVIGATION_CLOSE_ENOUGH_SQR = 2.25D;
    private static final int HARVEST_NAVIGABLE_STAND_MAX_UP = 1;
    private static final int HARVEST_NAVIGABLE_STAND_MAX_DOWN = 4;
    private static final int MOBILITY_REPAIR_MAX_ATTEMPTS = 3;
    private static final int MOBILITY_FEEDBACK_COOLDOWN_TICKS = 100;
    private static final int CRAFTING_TABLE_RADIUS = 12;
    private static final int CRAFTING_TABLE_VERTICAL_RADIUS = 5;
    private static final int STRUCTURE_REPAIR_RADIUS = 12;
    private static final int STRUCTURE_REPAIR_VERTICAL_RADIUS = 6;
    private static final int STRUCTURE_REPAIR_MIN_BLOCKS = 8;
    private static final int STRUCTURE_REPAIR_MAX_PLACEMENTS = 96;
    private static final int STRUCTURE_REPAIR_EVIDENCE_PASSES = 4;
    private static final int DOORS_PER_CRAFT = 3;
    private static final int PLANKS_PER_DOOR_CRAFT = 6;
    private static final Direction[] HORIZONTAL_DIRECTIONS = new Direction[]{
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final Direction[] REPAIR_NEIGHBOR_DIRECTIONS = new Direction[]{
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP, Direction.DOWN
    };

    private static final NpcRuntimeRegistry NPC_RUNTIMES = new NpcRuntimeRegistry();

    private static UUID npcUuid;
    private static UUID followTargetUuid;
    private static UUID groupFollowTargetUuid;
    private static UUID taskOwnerUuid;
    private static UUID targetItemUuid;
    private static UUID autoPickupTargetUuid;
    private static TaskKind taskKind = TaskKind.NONE;
    private static String activeTaskId = "";
    private static CollectItemsTaskState collectItemsTask;
    private static BlockPos targetBlock;
    private static int tickCounter;
    private static int taskRadius;
    private static int taskTargetCount;
    private static int taskStepsDone;
    private static int taskIdleTicks;
    private static int taskSearchTimeoutTicks;
    private static int taskOwnerPauseTicks;
    private static int breakTicks;
    private static int placeTicks;
    private static int lastBreakStage = -1;
    private static float blockBreakProgress;
    private static boolean taskPauseAnnounced;
    private static BlockPos navigationTarget;
    private static int navigationStuckTicks;
    private static double lastNavigationDistanceSqr = Double.MAX_VALUE;
    private static BlockPos temporarilyBlockedStandPos;
    private static int temporarilyBlockedStandPosTicks;
    private static BlockPos temporarilyBlockedBreakTarget;
    private static int temporarilyBlockedBreakTargetTicks;
    private static int mobilityRepairAttempts;
    private static int lastMobilityFeedbackTick = -MOBILITY_FEEDBACK_COOLDOWN_TICKS;
    private static String primitivePlaceBlockPreference = "";
    private static String craftTableItemRequest = "";
    private static int craftTableRequestedCount;
    private static boolean craftTableAllowContainerMaterials;
    private static List<BlockPos> buildQueue = List.of();
    private static List<RepairPlacement> repairQueue = List.of();
    private static RepairPlan activeRepairPlan;
    private static BuildKind currentBuildKind = BuildKind.BASIC;
    private static BuildKind pendingBuildKind = BuildKind.NONE;
    private static BlockPos pendingBuildCenter;
    private static Direction pendingBuildForward = Direction.NORTH;
    private static int pendingBuildGatherAttempts;
    private static UUID repairPreviewOwnerUuid;
    private static RepairPlan repairPreviewPlan;
    private static int repairPreviewRadius;
    private static List<RepairPlacement> pendingRepairQueue = List.of();
    private static RepairPlan pendingRepairPlan;
    private static int pendingRepairRadius;
    private static int pendingRepairGatherAttempts;
    private static BlockPos taskKnownResourceTarget;
    private static int taskKnownResourceTravelTicks;
    private static BlockPos taskScoutTarget;
    private static int taskScoutLegTicks;
    private static int taskScoutLegIndex;
    private static BlockPos temporarilyBlockedKnownResourceTarget;
    private static int temporarilyBlockedKnownResourceTicks;
    private static UUID chestMaterialApprovalOwnerUuid;

    private enum BasicCraftTarget {
        BASIC_TOOLS,
        PICKAXE,
        AXE,
        PLANKS,
        STICKS,
        DOOR,
        UNKNOWN
    }

    private enum ToolMaterialPreference {
        ANY,
        WOOD,
        STONE
    }

    private enum RepairSurface {
        NONE,
        WALL,
        ROOF,
        FLOOR
    }

    private NpcManager() {
    }

    public static void spawnNear(ServerPlayer player) {
        spawnNear(player, NpcProfile.defaultProfile());
    }

    public static void spawnNear(ServerPlayer player, NpcProfile profile) {
        AiNpcEntity npc = ModEntities.AI_NPC.get().create(player.serverLevel());
        if (npc == null) {
            say(player, "Failed to create NPC.");
            return;
        }

        NpcProfile selectedProfile = profile == null ? NpcProfile.defaultProfile() : profile;
        npc.setPos(player.getX() + 1.5, player.getY(), player.getZ() + 1.5);
        npc.setCustomName(Component.literal(selectedProfile.name()));
        npc.setCustomNameVisible(true);
        npc.setSkin(selectedProfile.skin());
        npc.setPersistenceRequired();
        npc.setInvulnerable(McAiConfig.NPC_INVULNERABLE.get());
        npc.addTag(TAG_COMPANION);
        npc.addTag(TAG_PROFILE_PREFIX + selectedProfile.id());
        player.serverLevel().addFreshEntity(npc);
        npcUuid = npc.getUUID();
        clearTask();
        followTargetUuid = null;
        groupFollowTargetUuid = null;
        say(player, "NPC " + selectedProfile.name() + " spawned with profile " + selectedProfile.id() + ".");
        ProtectionManager.enableDefaultProtection(player);
    }

    public static void remove(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (isCompanionEntity(entity)) {
                    entity.discard();
                }
            }
        }
        npcUuid = null;
        followTargetUuid = null;
        groupFollowTargetUuid = null;
        NPC_RUNTIMES.clear();
        clearTask();
    }

    public static void removeWithMessage(ServerPlayer player) {
        remove(player.getServer());
        say(player, "NPC removed.");
    }

    public static void listNpcs(ServerPlayer player) {
        List<Entity> npcs = companionEntities(player.getServer());
        if (npcs.isEmpty()) {
            say(player, "No NPCs are spawned. Use /mcai npc spawn [profile].");
            return;
        }

        StringBuilder builder = new StringBuilder("Spawned NPCs: ");
        for (int index = 0; index < npcs.size(); index++) {
            Entity entity = npcs.get(index);
            if (index > 0) {
                builder.append("; ");
            }
            builder.append(entity.getUUID().equals(npcUuid) ? "*" : "")
                    .append(entity.getName().getString())
                    .append(" profile=").append(profileId(entity))
                    .append(" skin=").append(entity instanceof AiNpcEntity aiNpc ? aiNpc.skin() : "")
                    .append(" id=").append(entity.getUUID().toString(), 0, 8)
                    .append(" at ").append(Math.round(entity.getX())).append(" ")
                    .append(Math.round(entity.getY())).append(" ")
                    .append(Math.round(entity.getZ()));
        }
        say(player, builder.toString());
    }

    public static boolean selectActive(ServerPlayer player, String selector) {
        Entity selected = findCompanionEntity(player.getServer(), selector, player);
        if (selected == null) {
            say(player, "No spawned NPC matched '" + selector + "'. Use /mcai npc list.");
            return false;
        }

        Mob previous = activeNpcMob(player.getServer());
        if (previous != null && !previous.getUUID().equals(selected.getUUID())) {
            previous.getNavigation().stop();
        }
        npcUuid = selected.getUUID();
        followTargetUuid = null;
        clearTask();
        say(player, "Selected NPC " + selected.getName().getString() + " profile=" + profileId(selected)
                + " id=" + selected.getUUID().toString().substring(0, 8) + ".");
        return true;
    }

    public static void removeSelectedWithMessage(ServerPlayer player, String selector) {
        Entity selected = findCompanionEntity(player.getServer(), selector, player);
        if (selected == null) {
            say(player, "No spawned NPC matched '" + selector + "'.");
            return;
        }

        String name = selected.getName().getString();
        boolean wasActive = selected.getUUID().equals(npcUuid);
        selected.discard();
        NPC_RUNTIMES.remove(selected.getUUID());
        if (wasActive) {
            npcUuid = null;
            followTargetUuid = null;
            clearTask();
        }
        say(player, "Removed NPC " + name + ".");
    }

    public static void applyProfileToActive(ServerPlayer player, NpcProfile profile) {
        Entity entity = findNpc(player.getServer());
        if (entity == null || profile == null) {
            return;
        }
        applyProfile(entity, profile);
    }

    public static void applyProfileToSpawned(MinecraftServer server, NpcProfile profile) {
        if (server == null || profile == null) {
            return;
        }
        for (Entity entity : companionEntities(server)) {
            if (profile.id().equalsIgnoreCase(profileId(entity))) {
                applyProfile(entity, profile);
            }
        }
    }

    public static void comeTo(ServerPlayer player) {
        Mob npc = requireNpc(player);
        if (npc == null) {
            return;
        }

        clearTask();
        followTargetUuid = null;
        groupFollowTargetUuid = null;
        npc.getNavigation().moveTo(player.getX(), player.getY(), player.getZ(), McAiConfig.NPC_MOVE_SPEED.get());
        say(player, "Coming to you.");
    }

    public static void goTo(ServerPlayer player, double x, double y, double z) {
        Mob npc = requireNpc(player);
        if (npc == null) {
            return;
        }

        clearTask();
        followTargetUuid = null;
        groupFollowTargetUuid = null;
        npc.getNavigation().moveTo(x, y, z, McAiConfig.NPC_MOVE_SPEED.get());
        say(player, "Moving to " + Math.round(x) + " " + Math.round(y) + " " + Math.round(z) + ".");
    }

    public static void follow(ServerPlayer player) {
        Mob npc = requireNpc(player);
        if (npc == null) {
            return;
        }

        clearTask();
        followTargetUuid = player.getUUID();
        groupFollowTargetUuid = null;
        npc.getNavigation().moveTo(player, McAiConfig.NPC_MOVE_SPEED.get());
        say(player, "Following you.");
    }

    public static void collectItems(ServerPlayer player, int radius) {
        startCollectItemsTask(player, radius, "Collecting nearby dropped items.");
    }

    public static void mineOres(ServerPlayer player, int radius) {
        startTask(player, TaskKind.MINE_ORES, radius, "Mining nearby exposed ore blocks.");
    }

    public static void gatherStone(ServerPlayer player, int radius, int targetCount) {
        int clampedTarget = clamp(targetCount <= 0 ? TOOL_HEAD_MATERIAL_COST : targetCount, 1, McAiConfig.NPC_MAX_TASK_STEPS.get());
        startTask(player, TaskKind.GATHER_STONE, radius, 0, clampedTarget,
                "Gathering stone/cobblestone-like blocks for crafting. Target " + clampedTarget + " blocks.");
    }

    public static void harvestLogs(ServerPlayer player, int radius) {
        harvestLogs(player, radius, DEFAULT_HARVEST_FOLLOW_SECONDS);
    }

    public static void harvestLogs(ServerPlayer player, int radius, int durationSeconds) {
        int clampedSeconds = clamp(durationSeconds, MIN_HARVEST_FOLLOW_SECONDS, MAX_HARVEST_FOLLOW_SECONDS);
        startTask(player, TaskKind.HARVEST_LOGS, radius, clampedSeconds * 20,
                "Harvesting logs near you. If no logs are close, I will follow you and keep scanning for up to " + clampedSeconds + " seconds.");
    }

    public static void group(ServerPlayer player, String actionName) {
        group(player, actionName, McAiConfig.NPC_TASK_RADIUS.get(), DEFAULT_HARVEST_FOLLOW_SECONDS);
    }

    public static void group(ServerPlayer player, String actionName, int radius, int durationSeconds) {
        String action = normalizeGroupAction(actionName);
        if (action.equals("list")) {
            listNpcs(player);
            return;
        }

        List<Mob> npcs = sortedCompanionMobs(player);
        if (npcs.isEmpty()) {
            say(player, "No NPCs are spawned. Use /mcai npc spawn [profile].");
            return;
        }

        switch (action) {
            case "come" -> groupComeTo(player, npcs);
            case "follow" -> groupFollow(player, npcs);
            case "stop" -> groupStop(player, npcs);
            case "status" -> groupStatus(player, npcs);
            case "gear" -> groupGearStatus(player, npcs);
            case "equip_best_gear" -> groupAutoEquip(player, npcs);
            case "collect_items", "harvest_logs", "mine_nearby_ore", "gather_stone" -> groupWorkTask(player, npcs, action, radius, durationSeconds);
            default -> say(player, "Group action '" + actionName + "' is not supported. Supported: come, follow, stop, status, list, gear, equip_best_gear, collect_items, harvest_logs, mine_nearby_ore, gather_stone.");
        }
    }

    private static void groupComeTo(ServerPlayer player, List<Mob> npcs) {
        cancelActiveWorkForGroup(player.getServer());
        groupFollowTargetUuid = null;
        int moved = moveGroupTowardPlayer(player, npcs, null);
        if (moved <= 0) {
            say(player, "No spawned NPCs are currently in your dimension, so none can path to you.");
            return;
        }
        say(player, "All reachable NPCs are coming to you (" + moved + "/" + npcs.size() + ").");
    }

    private static void groupFollow(ServerPlayer player, List<Mob> npcs) {
        cancelActiveWorkForGroup(player.getServer());
        followTargetUuid = null;
        groupFollowTargetUuid = player.getUUID();
        int moved = moveGroupTowardPlayer(player, npcs, null);
        if (moved <= 0) {
            groupFollowTargetUuid = null;
            say(player, "No spawned NPCs are currently in your dimension, so group follow cannot start.");
            return;
        }
        say(player, "All reachable NPCs are following you (" + moved + "/" + npcs.size() + ").");
    }

    private static void groupStop(ServerPlayer player, List<Mob> npcs) {
        cancelActiveWorkForGroup(player.getServer());
        followTargetUuid = null;
        groupFollowTargetUuid = null;
        for (Mob npc : npcs) {
            npc.getNavigation().stop();
        }
        say(player, "Stopped all NPCs (" + npcs.size() + ").");
    }

    private static void groupStatus(ServerPlayer player, List<Mob> npcs) {
        StringBuilder builder = new StringBuilder("All NPCs: ");
        int shown = 0;
        for (Mob npc : npcs) {
            if (shown > 0) {
                builder.append("; ");
            }
            builder.append(npc.getUUID().equals(npcUuid) ? "*" : "")
                    .append(npc.getName().getString())
                    .append(" profile=").append(profileId(npc))
                    .append(" at ").append(Math.round(npc.getX())).append(" ")
                    .append(Math.round(npc.getY())).append(" ")
                    .append(Math.round(npc.getZ()))
                    .append(" in ").append(npc.level().dimension().location());
            shown++;
            if (shown >= 8 && npcs.size() > shown) {
                builder.append("; ...");
                break;
            }
        }
        builder.append(". Global task=").append(taskName());
        if (taskOwnerPauseTicks > 0) {
            builder.append(", paused=").append(taskOwnerPauseTicks / 20).append("s");
        }
        if (taskStepsDone > 0) {
            builder.append(", steps=").append(taskStepsDone);
        }
        if (groupFollowTargetUuid != null) {
            builder.append(", groupFollowing");
        }
        builder.append(".");
        say(player, builder.toString());
    }

    private static void groupGearStatus(ServerPlayer player, List<Mob> npcs) {
        StringBuilder builder = new StringBuilder("All NPC gear: ");
        int shown = 0;
        int withStorage = 0;
        for (Mob npc : npcs) {
            if (shown > 0) {
                builder.append("; ");
            }
            builder.append(npc.getName().getString()).append(": ");
            if (npc instanceof AiNpcEntity aiNpc) {
                withStorage++;
                builder.append("mainhand=").append(stackLabel(aiNpc.getMainHandItem()))
                        .append(", chest=").append(stackLabel(aiNpc.getItemBySlot(EquipmentSlot.CHEST)))
                        .append(", storage=").append(aiNpc.usedInventorySlots()).append("/").append(AiNpcEntity.INVENTORY_SIZE);
            } else {
                builder.append("legacy entity without NPC storage");
            }
            shown++;
            if (shown >= 8 && npcs.size() > shown) {
                builder.append("; ...");
                break;
            }
        }
        builder.append(". Storage-capable NPCs=").append(withStorage).append("/").append(npcs.size()).append(".");
        say(player, builder.toString());
    }

    private static void groupAutoEquip(ServerPlayer player, List<Mob> npcs) {
        int checked = 0;
        int changed = 0;
        for (Mob npc : npcs) {
            if (npc instanceof AiNpcEntity aiNpc) {
                checked++;
                if (autoEquipBest(aiNpc)) {
                    changed++;
                }
            }
        }
        if (checked <= 0) {
            say(player, "No storage-capable NPCs are available for auto-equip.");
            return;
        }
        say(player, "Auto-equip checked " + checked + " NPCs; changed " + changed + ".");
    }

    private static void groupWorkTask(ServerPlayer player, List<Mob> npcs, String action, int radius, int durationSeconds) {
        Mob delegate = selectGroupTaskDelegate(player, npcs);
        if (delegate == null) {
            say(player, "No NPC in your dimension can run this group work task.");
            return;
        }

        activateNpcForGroupTask(player.getServer(), delegate);
        followTargetUuid = null;
        groupFollowTargetUuid = player.getUUID();
        moveGroupTowardPlayer(player, npcs, delegate);

        String message = "\u5f53\u524d\u6267\u884c\u5c42\u4e00\u6b21\u53ea\u80fd\u8fd0\u884c\u4e00\u4e2a\u91c7\u96c6\u4efb\u52a1\uff0c\u5df2\u7531 "
                + delegate.getName().getString()
                + " \u6267\u884c\uff1b\u5176\u4ed6 NPC \u8fdb\u5165\u8ddf\u968f/\u5f85\u547d\u3002";
        say(player, message);
        TaskFeedback.info(player, delegate, action, "GROUP_TASK_DELEGATED", message);

        switch (action) {
            case "collect_items" -> collectItems(player, radius);
            case "harvest_logs" -> harvestLogs(player, radius, durationSeconds);
            case "mine_nearby_ore" -> mineOres(player, radius);
            case "gather_stone" -> gatherStone(player, radius, TOOL_HEAD_MATERIAL_COST);
            default -> {
            }
        }
    }

    private static String normalizeGroupAction(String actionName) {
        String normalized = firstNonBlank(actionName, "")
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (normalized) {
            case "come", "come_to_player", "look_at_player" -> "come";
            case "follow", "follow_player" -> "follow";
            case "stop", "stop_npc", "patrol_stop" -> "stop";
            case "status", "npc_status", "report_status", "report_task_status" -> "status";
            case "list", "list_npc", "list_npcs" -> "list";
            case "gear", "gear_status", "report_gear" -> "gear";
            case "equip_best", "equip_best_gear", "auto_equip" -> "equip_best_gear";
            case "collect", "collect_items", "collect_drops" -> "collect_items";
            case "wood", "harvest_logs", "gather_wood" -> "harvest_logs";
            case "mine", "mine_ore", "mine_nearby_ore" -> "mine_nearby_ore";
            case "stone", "gather_stone", "mine_stone", "gather_cobblestone", "collect_cobblestone" -> "gather_stone";
            default -> normalized;
        };
    }

    private static List<Mob> sortedCompanionMobs(ServerPlayer player) {
        List<Mob> mobs = companionMobs(player.getServer());
        mobs.sort(Comparator.comparingDouble(mob -> mob.level() == player.level() ? mob.distanceToSqr(player) : Double.MAX_VALUE));
        return mobs;
    }

    private static void cancelActiveWorkForGroup(MinecraftServer server) {
        Mob active = findNpcMob(server);
        if (active != null) {
            clearBreakProgress(active);
        }
        clearPendingBuild();
        clearPendingRepair();
        clearTask();
        followTargetUuid = null;
    }

    private static int moveGroupTowardPlayer(ServerPlayer player, List<Mob> npcs, Mob skipped) {
        int moved = 0;
        int formationIndex = 0;
        for (Mob npc : npcs) {
            if (skipped != null && npc.getUUID().equals(skipped.getUUID())) {
                continue;
            }
            if (npc.level() != player.level()) {
                npc.getNavigation().stop();
                continue;
            }

            moveNpcToPlayerFormation(npc, player, formationIndex++);
            moved++;
        }
        return moved;
    }

    private static void moveNpcToPlayerFormation(Mob npc, ServerPlayer player, int index) {
        double radius = 2.0D + (index / 8) * 1.5D;
        double angle = (Math.PI * 2.0D / 8.0D) * (index % 8);
        double x = player.getX() + Math.cos(angle) * radius;
        double z = player.getZ() + Math.sin(angle) * radius;
        npc.getNavigation().moveTo(x, player.getY(), z, McAiConfig.NPC_MOVE_SPEED.get());
    }

    private static Mob selectGroupTaskDelegate(ServerPlayer player, List<Mob> npcs) {
        Mob active = activeNpcMob(player.getServer());
        if (active != null && active.isAlive() && active.level() == player.level()) {
            return active;
        }

        return npcs.stream()
                .filter(npc -> npc.isAlive() && npc.level() == player.level())
                .min(Comparator.comparingDouble(npc -> npc.distanceToSqr(player)))
                .orElse(null);
    }

    private static void activateNpcForGroupTask(MinecraftServer server, Mob delegate) {
        Mob previous = findNpcMob(server);
        if (previous != null && !previous.getUUID().equals(delegate.getUUID())) {
            clearBreakProgress(previous);
            previous.getNavigation().stop();
        }
        npcUuid = delegate.getUUID();
        delegate.getNavigation().stop();
    }

    public static void buildBasicHouse(ServerPlayer player) {
        Direction forward = player.getDirection();
        buildBasicHouse(player, player.blockPosition().relative(forward, 5), forward);
    }

    public static void buildBasicHouse(ServerPlayer player, BlockPos center, Direction forward) {
        StructureBuildController.buildStructure(player, "starter_cabin_7x7", center, forward, "rustic", false);
    }

    public static void buildLargeHouse(ServerPlayer player) {
        Direction forward = player.getDirection();
        buildLargeHouse(player, player.blockPosition().relative(forward, 8), forward);
    }

    public static void buildLargeHouse(ServerPlayer player, BlockPos center, Direction forward) {
        StructureBuildController.buildStructure(player, "starter_cabin_7x7", center, forward, "rustic", true);
    }

    public static boolean repairNearbyStructure(ServerPlayer player) {
        return repairNearbyStructure(player, STRUCTURE_REPAIR_RADIUS);
    }

    public static boolean repairNearbyStructure(ServerPlayer player, int requestedRadius) {
        Mob npc = requireNpc(player);
        if (npc == null) {
            return false;
        }

        int radius = clamp(requestedRadius <= 0 ? STRUCTURE_REPAIR_RADIUS : requestedRadius, 4, McAiConfig.NPC_TASK_RADIUS.get());
        RepairPlan plan = createNearbyStructureRepairPlan(player, radius);
        if (plan.blocked()) {
            String message = plan.summary();
            say(player, message);
            TaskFeedback.warn(player, npc, "repair_structure", plan.blockerCode(), message);
            return false;
        }
        if (plan.placements().isEmpty()) {
            String message = "No obvious missing door or wall gaps found in the nearby building shell. Stand inside/near the damaged wall or give exact coordinates if the target is elsewhere.";
            say(player, message);
            TaskFeedback.info(player, npc, "repair_structure", "NO_REPAIRS_NEEDED", message);
            return false;
        }

        return startRepairPlan(player, npc, radius, plan);
    }

    public static boolean previewNearbyStructureRepair(ServerPlayer player) {
        return previewNearbyStructureRepair(player, STRUCTURE_REPAIR_RADIUS);
    }

    public static boolean previewNearbyStructureRepair(ServerPlayer player, int requestedRadius) {
        Mob npc = requireNpc(player);
        if (npc == null) {
            return false;
        }

        int radius = clamp(requestedRadius <= 0 ? STRUCTURE_REPAIR_RADIUS : requestedRadius, 4, McAiConfig.NPC_TASK_RADIUS.get());
        RepairPlan plan = createNearbyStructureRepairPlan(player, radius);
        if (plan.blocked()) {
            String message = plan.summary();
            say(player, message);
            TaskFeedback.warn(player, npc, "repair_structure", plan.blockerCode(), message);
            return false;
        }

        repairPreviewOwnerUuid = player.getUUID();
        repairPreviewPlan = plan;
        repairPreviewRadius = radius;
        String message = repairPreviewMessage(plan);
        say(player, message);
        TaskFeedback.info(player, npc, "repair_structure", "REPAIR_PREVIEW_READY", message);
        return !plan.placements().isEmpty();
    }

    public static boolean confirmRepairPreview(ServerPlayer player) {
        Mob npc = requireNpc(player);
        if (npc == null) {
            return false;
        }
        if (repairPreviewPlan == null || repairPreviewOwnerUuid == null || !repairPreviewOwnerUuid.equals(player.getUUID())) {
            say(player, "No saved repair preview for you. Use /mcai npc repair preview, or ask me to preview the repair first.");
            return false;
        }
        RepairPlan plan = repairPreviewPlan;
        int radius = repairPreviewRadius <= 0 ? STRUCTURE_REPAIR_RADIUS : repairPreviewRadius;
        clearRepairPreview();
        if (plan.blocked()) {
            String message = plan.summary();
            say(player, message);
            TaskFeedback.warn(player, npc, "repair_structure", plan.blockerCode(), message);
            return false;
        }
        if (plan.placements().isEmpty()) {
            String message = "The saved repair preview has no placements left.";
            say(player, message);
            TaskFeedback.info(player, npc, "repair_structure", "NO_REPAIRS_NEEDED", message);
            return false;
        }
        return startRepairPlan(player, npc, radius, plan);
    }

    private static boolean startRepairPlan(ServerPlayer player, Mob npc, int radius, RepairPlan plan) {
        followTargetUuid = null;
        taskOwnerUuid = player.getUUID();
        taskKind = TaskKind.REPAIR_STRUCTURE;
        activeTaskId = newTaskId(taskKind);
        taskRadius = radius;
        taskStepsDone = 0;
        taskIdleTicks = 0;
        taskSearchTimeoutTicks = 0;
        taskOwnerPauseTicks = 0;
        taskPauseAnnounced = false;
        targetBlock = null;
        targetItemUuid = null;
        autoPickupTargetUuid = null;
        breakTicks = 0;
        placeTicks = 0;
        blockBreakProgress = 0.0F;
        lastBreakStage = -1;
        navigationTarget = null;
        resetTaskSearchMemory();
        temporarilyBlockedStandPos = null;
        temporarilyBlockedStandPosTicks = 0;
        temporarilyBlockedBreakTarget = null;
        temporarilyBlockedBreakTargetTicks = 0;
        resetMobilityRecovery();
        activeRepairPlan = plan;
        repairQueue = new ArrayList<>(plan.placements());
        npc.getNavigation().stop();

        String message = "Repairing nearby structure: " + plan.summary()
                + " Queue=" + repairQueue.size()
                + " (walls=" + plan.wallPlacements()
                + ", roof=" + plan.roofPlacements()
                + ", floor=" + plan.floorPlacements()
                + ", doors=" + plan.doorPlacements()
                + "), material=" + plan.materialPreference()
                + ". Player inventory is excluded.";
        say(player, message);
        TaskFeedback.info(player, npc, taskName(), "TASK_STARTED", message);
        syncActiveTaskRuntime();
        return true;
    }

    private static String repairPreviewMessage(RepairPlan plan) {
        if (plan.placements().isEmpty()) {
            return "Repair preview: no obvious missing placements. " + plan.summary()
                    + ". Stand inside/near the damaged structure, or give exact coordinates if this is not the target.";
        }

        Map<String, Integer> materials = new LinkedHashMap<>();
        List<String> samples = new ArrayList<>();
        for (RepairPlacement placement : plan.placements()) {
            materials.merge(placement.materialPreference(), 1, Integer::sum);
            if (samples.size() < 8) {
                samples.add(positionText(placement.pos()));
            }
        }

        StringBuilder message = new StringBuilder("Repair preview: ");
        message.append(plan.placements().size()).append(" placement(s)")
                .append(" (walls=").append(plan.wallPlacements())
                .append(", roof=").append(plan.roofPlacements())
                .append(", floor=").append(plan.floorPlacements())
                .append(", doors=").append(plan.doorPlacements()).append("). ")
                .append(plan.summary()).append(". Materials=").append(materials)
                .append(". Sample targets=").append(samples)
                .append(". Use /mcai npc repair confirm to execute, or tell me what to change first.");
        return message.toString();
    }

    private static void clearRepairPreview() {
        repairPreviewOwnerUuid = null;
        repairPreviewPlan = null;
        repairPreviewRadius = 0;
    }

    private static void buildHouse(ServerPlayer player, BuildKind buildKind, BlockPos center, Direction forward, boolean gatherIfNeeded) {
        Mob npc = requireNpc(player);
        if (npc == null) {
            return;
        }

        Direction buildForward = horizontalDirection(forward);
        BlockPos buildCenter = center == null ? player.blockPosition().relative(buildForward, 5) : center.immutable();
        List<BlockPos> blueprint = createHouseBlueprint(buildKind, buildCenter, buildForward);
        List<BlockPos> queue = remainingBuildPositions(player.serverLevel(), blueprint);
        int skippedBlocks = blueprint.size() - queue.size();
        craftLogsIntoPlanksForBuild(player, npc, queue.size());
        int availableBlocks = countPlaceableBlocks(player);
        if (queue.isEmpty()) {
            String message = buildKind.label() + " is already complete at anchored blueprint center "
                    + buildCenter.getX() + " " + buildCenter.getY() + " " + buildCenter.getZ() + ".";
            say(player, message);
            TaskFeedback.info(player, npc, buildKind.taskName(), "TASK_COMPLETE", message);
            return;
        }
        if (availableBlocks < queue.size()) {
            if (gatherIfNeeded) {
                pendingBuildKind = buildKind;
                pendingBuildCenter = buildCenter;
                pendingBuildForward = buildForward;
                pendingBuildGatherAttempts = 1;
                String message = "I need " + queue.size() + " placeable blocks for a " + buildKind.label()
                        + ", but only have " + availableBlocks
                        + ". Gathering wood first, round " + pendingBuildGatherAttempts + "/" + MAX_PENDING_BUILD_GATHER_ATTEMPTS + ".";
                say(player, message);
                TaskFeedback.warn(player, npc, buildKind.taskName(), "NEED_BLOCKS_GATHERING_WOOD", message);
                harvestLogs(player, McAiConfig.NPC_TASK_RADIUS.get(), PENDING_BUILD_HARVEST_SECONDS);
                return;
            }
            String message = "I need " + queue.size() + " placeable blocks for a " + buildKind.label() + ", but only have " + availableBlocks
                    + " in NPC storage or approved nearby containers. Player inventory is excluded. Already placed/occupied blueprint blocks skipped: "
                    + skippedBlocks + "." + chestApprovalHint(player);
            say(player, message);
            TaskFeedback.warn(player, npc, buildKind.taskName(), "NEED_BLOCKS", message);
            return;
        }

        followTargetUuid = null;
        taskOwnerUuid = player.getUUID();
        taskKind = buildKind.taskKind();
        activeTaskId = newTaskId(taskKind);
        taskRadius = McAiConfig.NPC_TASK_RADIUS.get();
        taskStepsDone = skippedBlocks;
        taskIdleTicks = 0;
        taskSearchTimeoutTicks = 0;
        taskOwnerPauseTicks = 0;
        taskPauseAnnounced = false;
        targetBlock = null;
        targetItemUuid = null;
        autoPickupTargetUuid = null;
        breakTicks = 0;
        placeTicks = 0;
        blockBreakProgress = 0.0F;
        lastBreakStage = -1;
        navigationTarget = null;
        resetTaskSearchMemory();
        buildQueue = queue;
        currentBuildKind = buildKind;
        npc.getNavigation().stop();
        String message = "Building a " + buildKind.label() + " at " + buildCenter.getX() + " " + buildCenter.getY() + " " + buildCenter.getZ()
                + " facing " + buildForward.getName()
                + ". Remaining blocks needed: " + buildQueue.size()
                + ", already placed/occupied skipped: " + skippedBlocks + ".";
        say(player, message);
        TaskFeedback.info(player, npc, taskName(), "TASK_STARTED", message);
        TaskFeedback.info(player, npc, taskName(), "BUILD_BLOCKS_AVAILABLE", "Available placeable blocks: " + availableBlocks + ".");
    }

    private static RepairPlan createNearbyStructureRepairPlan(ServerPlayer player, int radius) {
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        int minScanY = Math.max(level.getMinBuildHeight(), center.getY() - 2);
        int maxScanY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + STRUCTURE_REPAIR_VERTICAL_RADIUS);
        List<BlockPos> structural = new ArrayList<>();
        List<BlockPos> doors = new ArrayList<>();
        Map<String, MaterialStats> materials = new LinkedHashMap<>();

        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - radius,
                minScanY,
                center.getZ() - radius,
                center.getX() + radius,
                maxScanY,
                center.getZ() + radius
        )) {
            BlockPos candidate = pos.immutable();
            BlockState state = level.getBlockState(candidate);
            if (isDoorBlock(state)) {
                doors.add(candidate);
            }
            if (!isRepairStructuralBlock(state)) {
                continue;
            }
            structural.add(candidate);
            String id = blockName(state);
            materials.computeIfAbsent(id, key -> new MaterialStats(id, state)).count++;
        }

        if (structural.size() < STRUCTURE_REPAIR_MIN_BLOCKS || materials.isEmpty()) {
            return RepairPlan.blocked("STRUCTURE_NOT_FOUND",
                    "I could not infer a nearby building shell. Stand inside or next to the damaged house, then ask again; for exact repairs, provide target coordinates/material.");
        }

        StructureBounds bounds = StructureBounds.from(structural);
        if (bounds.widthX() < 3 || bounds.widthZ() < 3) {
            return RepairPlan.blocked("STRUCTURE_TOO_SMALL",
                    "The nearby blocks do not form a clear house-sized shell. I need a clearer wall perimeter or exact repair coordinates.");
        }

        MaterialStats dominant = dominantMaterial(materials);
        BlockState wallState = dominant.state.getBlock().defaultBlockState();
        String materialPreference = dominant.id;
        int wallBaseY = clamp(center.getY(), bounds.minY(), Math.max(bounds.minY(), bounds.maxY()));
        int wallTopY = Math.min(level.getMaxBuildHeight() - 1, Math.max(wallBaseY + 2, Math.min(bounds.maxY(), wallBaseY + 3)));
        BlockPos doorLower = findExistingDoorLower(level, doors, bounds);
        boolean needsDoor = false;
        if (doorLower == null) {
            doorLower = findDoorOpening(level, bounds, wallBaseY, center);
            needsDoor = doorLower != null;
        } else {
            needsDoor = !isDoorBlock(level.getBlockState(doorLower)) || !isDoorBlock(level.getBlockState(doorLower.above()));
        }

        List<RepairPlacement> placements = new ArrayList<>();
        if (needsDoor && doorLower != null) {
            Direction facing = doorFacingFor(bounds, doorLower);
            placements.add(doorPlacement(doorLower, facing));
        }

        addEvidenceBasedRepairPlacements(level, bounds, wallBaseY, wallTopY, materials, dominant, doorLower, placements);

        int wallPlacements = 0;
        int doorPlacements = 0;
        int roofPlacements = 0;
        int floorPlacements = 0;
        for (RepairPlacement placement : placements) {
            if (placement.door()) {
                doorPlacements++;
            } else if (placement.surface() == RepairSurface.WALL) {
                wallPlacements++;
            } else if (placement.surface() == RepairSurface.ROOF) {
                roofPlacements++;
            } else if (placement.surface() == RepairSurface.FLOOR) {
                floorPlacements++;
            }
        }

        String summary = "bounds x=" + bounds.minX() + ".." + bounds.maxX()
                + ", y=" + wallBaseY + ".." + wallTopY
                + ", z=" + bounds.minZ() + ".." + bounds.maxZ()
                + ", inferred wall material=" + materialPreference
                + ", structural blocks=" + structural.size()
                + ", strategy=evidence_based";
        return new RepairPlan(false, "", summary, bounds, materialPreference, wallState, placements,
                wallPlacements, doorPlacements, roofPlacements, floorPlacements);
    }

    private static MaterialStats dominantMaterial(Map<String, MaterialStats> materials) {
        MaterialStats best = null;
        for (MaterialStats stats : materials.values()) {
            if (best == null || stats.count > best.count) {
                best = stats;
            }
        }
        return best;
    }

    private static boolean isRepairStructuralBlock(BlockState state) {
        if (state.isAir() || state.hasBlockEntity() || isDoorBlock(state)) {
            return false;
        }

        String id = blockName(state);
        if (id.contains("glass")
                || id.contains("leaves")
                || id.contains("sapling")
                || id.contains("torch")
                || id.contains("button")
                || id.contains("pressure_plate")
                || id.contains("trapdoor")
                || id.contains("fence")
                || id.contains("wall")
                || id.contains("slab")
                || id.contains("stairs")
                || id.contains("ladder")
                || id.contains("chest")
                || id.contains("barrel")
                || id.contains("crafting_table")) {
            return false;
        }

        return id.endsWith("_planks")
                || id.contains("cobblestone")
                || id.contains("stone_bricks")
                || id.endsWith(":bricks")
                || id.contains("deepslate_bricks")
                || id.contains("deepslate_tiles")
                || id.contains("blackstone")
                || id.contains("sandstone")
                || id.contains("terracotta")
                || id.contains("concrete")
                || id.contains("mud_bricks")
                || id.contains("tuff_bricks")
                || id.contains("andesite")
                || id.contains("diorite")
                || id.contains("granite");
    }

    private static boolean isDoorBlock(BlockState state) {
        return !state.isAir() && blockName(state).contains("_door");
    }

    private static BlockPos findExistingDoorLower(ServerLevel level, List<BlockPos> doors, StructureBounds bounds) {
        for (BlockPos pos : doors) {
            if (!bounds.containsHorizontal(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!isDoorBlock(state)) {
                continue;
            }
            if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
                return pos.below().immutable();
            }
            return pos.immutable();
        }
        return null;
    }

    private static BlockPos findDoorOpening(ServerLevel level, StructureBounds bounds, int wallBaseY, BlockPos playerPos) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                boolean perimeter = x == bounds.minX() || x == bounds.maxX() || z == bounds.minZ() || z == bounds.maxZ();
                boolean corner = (x == bounds.minX() || x == bounds.maxX()) && (z == bounds.minZ() || z == bounds.maxZ());
                if (!perimeter || corner) {
                    continue;
                }
                BlockPos lower = new BlockPos(x, wallBaseY, z);
                if (!isDoorOpeningCandidate(level, lower)) {
                    continue;
                }
                double score = lower.distSqr(playerPos);
                if (score < bestScore) {
                    bestScore = score;
                    best = lower;
                }
            }
        }
        return best == null ? null : best.immutable();
    }

    private static boolean isDoorOpeningCandidate(ServerLevel level, BlockPos lower) {
        return level.getBlockState(lower).isAir()
                && level.getBlockState(lower.above()).isAir()
                && isSolidSupport(level, lower.below());
    }

    private static Direction doorFacingFor(StructureBounds bounds, BlockPos lower) {
        if (lower.getZ() == bounds.minZ()) {
            return Direction.NORTH;
        }
        if (lower.getZ() == bounds.maxZ()) {
            return Direction.SOUTH;
        }
        if (lower.getX() == bounds.minX()) {
            return Direction.WEST;
        }
        if (lower.getX() == bounds.maxX()) {
            return Direction.EAST;
        }
        return Direction.NORTH;
    }

    private static RepairPlacement doorPlacement(BlockPos lower, Direction facing) {
        BlockState lowerState = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, horizontalDirection(facing))
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT)
                .setValue(BlockStateProperties.OPEN, false);
        BlockState upperState = lowerState.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        return new RepairPlacement(lower.immutable(), lowerState, upperState, "door", true, RepairSurface.WALL);
    }

    private static boolean isWithinStructureRepairBand(BlockPos pos, StructureBounds bounds, int wallBaseY, int wallTopY) {
        return pos.getY() >= wallBaseY
                && pos.getY() <= wallTopY
                && bounds.containsHorizontal(pos);
    }

    private static void addEvidenceBasedRepairPlacements(
            ServerLevel level,
            StructureBounds bounds,
            int wallBaseY,
            int wallTopY,
            Map<String, MaterialStats> materials,
            MaterialStats fallbackMaterial,
            BlockPos doorLower,
            List<RepairPlacement> placements
    ) {
        Map<BlockPos, String> plannedMaterials = new LinkedHashMap<>();
        BlockPos doorUpper = doorLower == null ? null : doorLower.above();
        int minY = Math.max(level.getMinBuildHeight(), bounds.minY());
        int maxY = Math.min(level.getMaxBuildHeight() - 1, bounds.maxY());

        for (int pass = 0; pass < STRUCTURE_REPAIR_EVIDENCE_PASSES && placements.size() < STRUCTURE_REPAIR_MAX_PLACEMENTS; pass++) {
            boolean addedThisPass = false;
            for (int y = minY; y <= maxY && placements.size() < STRUCTURE_REPAIR_MAX_PLACEMENTS; y++) {
                for (int x = bounds.minX(); x <= bounds.maxX() && placements.size() < STRUCTURE_REPAIR_MAX_PLACEMENTS; x++) {
                    for (int z = bounds.minZ(); z <= bounds.maxZ() && placements.size() < STRUCTURE_REPAIR_MAX_PLACEMENTS; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (plannedMaterials.containsKey(pos)
                                || pos.equals(doorLower)
                                || pos.equals(doorUpper)
                                || !level.getBlockState(pos).isAir()) {
                            continue;
                        }

                        RepairSurface surface = repairSurfaceFor(pos, bounds, wallBaseY, wallTopY);
                        if (surface == RepairSurface.NONE) {
                            continue;
                        }

                        MaterialStats material = localRepairMaterial(level, pos, materials, fallbackMaterial, plannedMaterials);
                        if (material == null || !hasRepairEvidence(level, pos, surface, material.id, plannedMaterials)) {
                            continue;
                        }

                        BlockState state = material.state.getBlock().defaultBlockState();
                        placements.add(new RepairPlacement(pos.immutable(), state, null, material.id, false, surface));
                        plannedMaterials.put(pos.immutable(), material.id);
                        addedThisPass = true;
                    }
                }
            }
            if (!addedThisPass) {
                return;
            }
        }
    }

    private static RepairSurface repairSurfaceFor(BlockPos pos, StructureBounds bounds, int wallBaseY, int wallTopY) {
        if (!bounds.containsHorizontal(pos)) {
            return RepairSurface.NONE;
        }
        boolean perimeter = pos.getX() == bounds.minX()
                || pos.getX() == bounds.maxX()
                || pos.getZ() == bounds.minZ()
                || pos.getZ() == bounds.maxZ();
        if (perimeter && pos.getY() >= wallBaseY && pos.getY() <= wallTopY) {
            return RepairSurface.WALL;
        }
        if (pos.getY() == bounds.maxY() && pos.getY() >= wallBaseY + 2) {
            return RepairSurface.ROOF;
        }
        if (pos.getY() == bounds.minY() && pos.getY() <= wallBaseY) {
            return RepairSurface.FLOOR;
        }
        return RepairSurface.NONE;
    }

    private static MaterialStats localRepairMaterial(
            ServerLevel level,
            BlockPos pos,
            Map<String, MaterialStats> materials,
            MaterialStats fallbackMaterial,
            Map<BlockPos, String> plannedMaterials
    ) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Direction direction : REPAIR_NEIGHBOR_DIRECTIONS) {
            countRepairMaterial(level, pos.relative(direction), plannedMaterials, counts);
            countRepairMaterial(level, pos.relative(direction, 2), plannedMaterials, counts);
        }

        String bestId = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestId = entry.getKey();
                bestCount = entry.getValue();
            }
        }

        if (bestId != null && materials.containsKey(bestId)) {
            return materials.get(bestId);
        }
        return fallbackMaterial;
    }

    private static void countRepairMaterial(
            ServerLevel level,
            BlockPos pos,
            Map<BlockPos, String> plannedMaterials,
            Map<String, Integer> counts
    ) {
        String planned = plannedMaterials.get(pos);
        if (planned != null) {
            counts.merge(planned, 1, Integer::sum);
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (isRepairStructuralBlock(state)) {
            counts.merge(blockName(state), 1, Integer::sum);
        }
    }

    private static boolean hasRepairEvidence(
            ServerLevel level,
            BlockPos pos,
            RepairSurface surface,
            String materialId,
            Map<BlockPos, String> plannedMaterials
    ) {
        int adjacent = countMatchingRepairNeighbors(level, pos, materialId, plannedMaterials, REPAIR_NEIGHBOR_DIRECTIONS);
        int horizontal = countMatchingRepairNeighbors(level, pos, materialId, plannedMaterials, HORIZONTAL_DIRECTIONS);
        boolean xOpposite = isMatchingRepairBlock(level, pos.west(), materialId, plannedMaterials)
                && isMatchingRepairBlock(level, pos.east(), materialId, plannedMaterials);
        boolean zOpposite = isMatchingRepairBlock(level, pos.north(), materialId, plannedMaterials)
                && isMatchingRepairBlock(level, pos.south(), materialId, plannedMaterials);
        boolean vertical = isMatchingRepairBlock(level, pos.above(), materialId, plannedMaterials)
                || isMatchingRepairBlock(level, pos.below(), materialId, plannedMaterials);

        return switch (surface) {
            case WALL -> adjacent >= 3
                    || ((xOpposite || zOpposite) && vertical)
                    || (adjacent >= 2 && isSolidSupport(level, pos.below()));
            case ROOF -> horizontal >= 3 || ((xOpposite || zOpposite) && horizontal >= 2);
            case FLOOR -> horizontal >= 3
                    || ((xOpposite || zOpposite) && horizontal >= 2)
                    || (horizontal >= 2 && isSolidSupport(level, pos.below()));
            case NONE -> false;
        };
    }

    private static int countMatchingRepairNeighbors(
            ServerLevel level,
            BlockPos pos,
            String materialId,
            Map<BlockPos, String> plannedMaterials,
            Direction[] directions
    ) {
        int count = 0;
        for (Direction direction : directions) {
            if (isMatchingRepairBlock(level, pos.relative(direction), materialId, plannedMaterials)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isMatchingRepairBlock(
            ServerLevel level,
            BlockPos pos,
            String materialId,
            Map<BlockPos, String> plannedMaterials
    ) {
        String planned = plannedMaterials.get(pos);
        if (planned != null) {
            return planned.equals(materialId);
        }
        BlockState state = level.getBlockState(pos);
        return isRepairStructuralBlock(state) && blockName(state).equals(materialId);
    }

    public static boolean prepareBasicTools(ServerPlayer player, boolean requireAxe, boolean requirePickaxe) {
        Mob npc = requireNpc(player);
        if (npc == null) {
            return false;
        }

        boolean axeReady = prepareBasicTool(player, npc, TaskKind.HARVEST_LOGS, ToolSummary.ToolKind.AXE, requireAxe);
        boolean pickaxeReady = prepareBasicTool(player, npc, TaskKind.MINE_ORES, ToolSummary.ToolKind.PICKAXE, requirePickaxe);
        ResourceAssessment.Snapshot resources = ResourceAssessment.snapshot(player);
        String message = "Tool prep: axe=" + readyText(axeReady)
                + ", pickaxe=" + readyText(pickaxeReady)
                + ", materials logs=" + resources.logs()
                + ", planks=" + resources.planks()
                + ", sticks=" + resources.sticks()
                + ", stone=" + resources.cobblestoneLike()
                + ", freeSlots=" + resources.freeInventorySlots()
                + ".";
        say(player, message);
        boolean requiredToolsReady = (!requireAxe || axeReady) && (!requirePickaxe || pickaxeReady);
        if (requiredToolsReady) {
            TaskFeedback.info(player, npc, "prepare_basic_tools", "TOOLS_READY", message);
            return true;
        }

        TaskFeedback.warn(player, npc, "prepare_basic_tools", "TOOLS_NOT_READY", message);
        return false;
    }

    private static boolean prepareBasicTool(ServerPlayer player, Mob npc, TaskKind kind, ToolSummary.ToolKind requiredTool, boolean required) {
        return prepareBasicTool(player, npc, kind, requiredTool, required, false);
    }

    private static boolean prepareBasicTool(ServerPlayer player, Mob npc, TaskKind kind, ToolSummary.ToolKind requiredTool, boolean required, boolean allowContainerMaterials) {
        ToolSummary.ToolMatch tool = ToolSummary.bestTool(player, requiredTool);
        if (isToolUsableForTask(tool, kind)) {
            return true;
        }

        craftBasicTool(player, npc, kind, requiredTool, allowContainerMaterials);
        tool = ToolSummary.bestTool(player, requiredTool);
        if (isToolUsableForTask(tool, kind)) {
            return true;
        }

        if (!required) {
            return false;
        }

        String message = "Required " + requiredTool.label()
                + " is not available and could not be crafted from NPC storage or approved nearby containers."
                + chestApprovalHint(player);
        TaskFeedback.failure(player, npc, taskNameFor(kind), "NEED_" + requiredTool.label().toUpperCase(), message);
        return false;
    }

    private static boolean isToolUsableForTask(ToolSummary.ToolMatch tool, TaskKind kind) {
        if (!tool.available()) {
            return false;
        }

        int minimumDurability = (kind == TaskKind.MINE_ORES || kind == TaskKind.GATHER_STONE) ? MIN_MINING_TOOL_DURABILITY : MIN_HARVEST_TOOL_DURABILITY;
        return tool.maxDurability() <= 0 || tool.remainingDurability() >= minimumDurability;
    }

    private static String readyText(boolean ready) {
        return ready ? "ready" : "missing";
    }

    public static void reportCrafting(ServerPlayer player) {
        ResourceAssessment.Snapshot resources = ResourceAssessment.snapshot(player);
        say(player, "Crafting: logs=" + resources.logs()
                + ", planks=" + resources.planks()
                + ", sticks=" + resources.sticks()
                + ", stone=" + resources.cobblestoneLike()
                + ", axe=" + ToolSummary.describeAvailability(player, ToolSummary.ToolKind.AXE)
                + ", pickaxe=" + ToolSummary.describeAvailability(player, ToolSummary.ToolKind.PICKAXE)
                + ", canCraftWoodenTool=" + readiness(resources.canCraftWoodenAxe() || resources.canCraftWoodenPickaxe())
                + ", canCraftStoneTool=" + readiness(resources.canCraftStoneAxe() || resources.canCraftStonePickaxe())
                + ".");
    }

    public static void autoEquipNow(ServerPlayer player) {
        AiNpcEntity npc = requireAiNpc(player);
        if (npc == null) {
            return;
        }

        boolean changed = autoEquipBest(npc);
        say(player, changed ? "Equipped the best available gear from NPC storage." : "Current gear is already the best available in NPC storage.");
    }

    public static void inspectBlock(ServerPlayer player, BlockPos pos) {
        if (pos == null) {
            say(player, "I need exact block coordinates to inspect.");
            return;
        }
        ServerLevel level = player.serverLevel();
        if (!isWithinPrimitiveRange(player, pos)) {
            say(player, "That block is too far away for a primitive inspection. Stay within " + McAiConfig.NPC_TASK_RADIUS.get() + " blocks or give me a nearer target.");
            return;
        }

        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        String blockEntityText = blockEntity == null
                ? "none"
                : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString();
        ToolUse tool = findGenericBreakTool(player, state);
        String toolText = tool == null ? "none" : toolLabel(tool);
        say(player, "Block " + blockName(state)
                + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + ": air=" + state.isAir()
                + ", hardness=" + state.getDestroySpeed(level, pos)
                + ", requiresCorrectTool=" + state.requiresCorrectToolForDrops()
                + ", blockEntity=" + blockEntityText
                + ", usableBreakTool=" + toolText
                + ".");
    }

    public static void breakBlockAt(ServerPlayer player, BlockPos pos) {
        Mob npc = requireNpc(player);
        if (npc == null) {
            return;
        }
        if (pos == null) {
            say(player, "I need exact block coordinates before breaking a block.");
            return;
        }
        if (!isWithinPrimitiveRange(player, pos)) {
            say(player, "That block is too far away. Primitive block breaking is limited to " + McAiConfig.NPC_TASK_RADIUS.get() + " blocks from you.");
            return;
        }

        ServerLevel level = player.serverLevel();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            say(player, "There is no block to break at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ".");
            return;
        }
        if (state.getDestroySpeed(level, pos) < 0.0F) {
            say(player, "I cannot break unbreakable block " + blockName(state) + ".");
            return;
        }
        if (state.hasBlockEntity()) {
            say(player, "I will not break block entity " + blockName(state) + " with primitive break_block. Use a more explicit plan if you want container or machine removal.");
            return;
        }

        ToolSummary.ToolKind preferredTool = preferredToolForState(state);
        if (preferredTool != null) {
            TaskKind prepKind = preferredTool == ToolSummary.ToolKind.AXE ? TaskKind.HARVEST_LOGS : TaskKind.MINE_ORES;
            prepareBasicTool(player, npc, prepKind, preferredTool, false);
        }
        ToolUse tool = findGenericBreakTool(player, state);
        if (tool == null) {
            say(player, "I do not have a valid tool for " + blockName(state) + ". I need the correct tool or craftable materials nearby.");
            TaskFeedback.failure(player, npc, "break_block", "NEED_TOOL", "No valid tool for " + blockName(state) + " at target coordinates.");
            return;
        }

        startPrimitiveTask(player, npc, TaskKind.BREAK_BLOCK, pos, "Breaking " + blockName(state) + " at "
                + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " with " + toolLabel(tool) + ".");
    }

    public static void placeBlockAt(ServerPlayer player, BlockPos pos, String blockPreference) {
        Mob npc = requireNpc(player);
        if (npc == null) {
            return;
        }
        if (pos == null) {
            say(player, "I need exact block coordinates before placing a block.");
            return;
        }
        if (!isWithinPrimitiveRange(player, pos)) {
            say(player, "That placement is too far away. Primitive block placement is limited to " + McAiConfig.NPC_TASK_RADIUS.get() + " blocks from you.");
            return;
        }

        ServerLevel level = player.serverLevel();
        if (!level.getBlockState(pos).isAir()) {
            say(player, "Cannot place at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " because the block is not air.");
            return;
        }

        PlaceableBlock block = findPlaceableBlock(player, blockPreference);
        if (block == null) {
            String requested = firstNonBlank(blockPreference, "any usable block");
            say(player, "No placeable block matching " + requested
                    + " is available in NPC storage or approved nearby containers." + chestApprovalHint(player));
            TaskFeedback.failure(player, npc, "place_block", "NEED_BLOCK", "No placeable block matching " + requested + ".");
            return;
        }
        if (!canPlaceBlock(level, pos, block.state())) {
            say(player, "Cannot safely place " + blockName(block.stack()) + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ".");
            return;
        }

        primitivePlaceBlockPreference = firstNonBlank(blockPreference, "");
        startPrimitiveTask(player, npc, TaskKind.PLACE_BLOCK, pos, "Placing " + blockName(block.stack()) + " at "
                + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ".");
    }

    public static String canonicalCraftItem(String itemName) {
        return switch (inferBasicCraftTarget(itemName)) {
            case BASIC_TOOLS -> "basic_tools";
            case PICKAXE -> "pickaxe";
            case AXE -> "axe";
            case PLANKS -> "planks";
            case STICKS -> "sticks";
            case DOOR -> "door";
            case UNKNOWN -> "";
        };
    }

    public static boolean isSupportedCraftItem(String itemName) {
        return !canonicalCraftItem(itemName).isBlank();
    }

    public static boolean craftItem(ServerPlayer player, String itemName, int requestedCount) {
        Mob npc = requireNpc(player);
        if (npc == null) {
            return false;
        }

        String request = normalizeCraftRequest(itemName);
        BasicCraftTarget target = inferBasicCraftTarget(request);
        int count = clamp(requestedCount <= 0 ? 1 : requestedCount, 1, 2304);
        if (request.isBlank()) {
            say(player, "I need an item name to craft. Supported primitive craft targets: axe, pickaxe, planks, sticks, door, basic_tools.");
            return false;
        }

        if (target == BasicCraftTarget.BASIC_TOOLS) {
            return prepareBasicTools(player, true, true);
        }
        if (target == BasicCraftTarget.PICKAXE) {
            return craftRequestedTool(player, npc, TaskKind.MINE_ORES, ToolSummary.ToolKind.PICKAXE, request, count, false);
        }
        if (target == BasicCraftTarget.AXE) {
            return craftRequestedTool(player, npc, TaskKind.HARVEST_LOGS, ToolSummary.ToolKind.AXE, request, count, false);
        }
        if (target == BasicCraftTarget.PLANKS) {
            return craftPlanks(player, npc, requestedCount <= 0 ? 0 : Math.max(4, count));
        }
        if (target == BasicCraftTarget.STICKS) {
            return craftSticks(player, npc, Math.max(4, count));
        }
        if (target == BasicCraftTarget.DOOR) {
            return craftDoors(player, npc, count, false);
        }

        if (request.contains("basic_tools") || request.contains("basic tools") || request.contains("工具")) {
            return prepareBasicTools(player, true, true);
        }
        if (request.contains("pickaxe") || request.contains("镐")) {
            return prepareBasicTool(player, npc, TaskKind.MINE_ORES, ToolSummary.ToolKind.PICKAXE, true);
        }
        if (request.contains("axe") || request.contains("斧")) {
            return prepareBasicTool(player, npc, TaskKind.HARVEST_LOGS, ToolSummary.ToolKind.AXE, true);
        }
        if (request.contains("plank") || request.contains("木板")) {
            return craftPlanks(player, npc, requestedCount <= 0 ? 0 : Math.max(4, count));
        }
        if (request.contains("stick") || request.contains("木棍")) {
            return craftSticks(player, npc, Math.max(4, count));
        }
        if (request.contains("door") || request.contains("门")) {
            return craftDoors(player, npc, count, false);
        }

        say(player, "Primitive craft_item currently supports axe, pickaxe, planks, sticks, door, and basic_tools. For " + itemName + ", I should save a plan first.");
        return false;
    }

    private static boolean craftRequestedTool(
            ServerPlayer player,
            Mob npc,
            TaskKind kind,
            ToolSummary.ToolKind requiredTool,
            String request,
            int requestedCount,
            boolean allowContainerMaterials
    ) {
        ToolMaterialPreference materialPreference = inferToolMaterialPreference(request);
        int count = clamp(requestedCount <= 0 ? 1 : requestedCount, 1, 16);
        boolean crafted = false;
        for (int i = 0; i < count; i++) {
            if (!craftBasicTool(player, npc, kind, requiredTool, allowContainerMaterials, materialPreference, true)) {
                return crafted;
            }
            crafted = true;
        }
        if (npc instanceof AiNpcEntity aiNpc) {
            autoEquipBest(aiNpc);
        }
        return crafted;
    }

    private static String normalizeCraftRequest(String itemName) {
        return firstNonBlank(itemName, "")
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static BasicCraftTarget inferBasicCraftTarget(String itemName) {
        String text = normalizeCraftRequest(itemName);
        String compact = text.replace(" ", "");
        if (compact.isBlank()) {
            return BasicCraftTarget.UNKNOWN;
        }
        if (compact.contains("basictools") || compact.contains("tools") || compact.contains("\u57fa\u7840\u5de5\u5177") || compact.contains("\u5de5\u5177\u5957")) {
            return BasicCraftTarget.BASIC_TOOLS;
        }
        if (compact.contains("pickaxe") || compact.contains("pickax") || compact.contains("\u9550\u5b50")
                || compact.contains("\u7a3f\u5b50") || compact.contains("\u641e\u5b50")) {
            return BasicCraftTarget.PICKAXE;
        }
        if (compact.contains("axe") || compact.contains("woodarx") || compact.contains("woodenarx")
                || containsCraftWord(text, "arx") || containsCraftWord(text, "ax") || compact.contains("hatchet")
                || compact.contains("\u65a7\u5b50")) {
            return BasicCraftTarget.AXE;
        }
        if (compact.contains("plank") || compact.contains("\u6728\u677f")) {
            return BasicCraftTarget.PLANKS;
        }
        if (compact.contains("stick") || compact.contains("\u6728\u68cd")) {
            return BasicCraftTarget.STICKS;
        }
        if (compact.contains("door") || compact.contains("\u95e8")) {
            return BasicCraftTarget.DOOR;
        }
        return BasicCraftTarget.UNKNOWN;
    }

    private static ToolMaterialPreference inferToolMaterialPreference(String itemName) {
        String text = normalizeCraftRequest(itemName);
        String compact = text.replace(" ", "");
        if (compact.contains("wood") || compact.contains("wooden") || compact.contains("oak") || compact.contains("\u6728")) {
            return ToolMaterialPreference.WOOD;
        }
        if (compact.contains("stone") || compact.contains("cobble") || compact.contains("\u77f3")) {
            return ToolMaterialPreference.STONE;
        }
        return ToolMaterialPreference.ANY;
    }

    private static boolean containsCraftWord(String normalizedText, String word) {
        return (" " + normalizedText + " ").contains(" " + word + " ");
    }

    private static String preferredToolName(ToolMaterialPreference materialPreference, ToolSummary.ToolKind requiredTool) {
        String material = switch (materialPreference) {
            case WOOD -> "wooden ";
            case STONE -> "stone ";
            case ANY -> "";
        };
        return material + requiredTool.label();
    }

    public static void craftAtNearbyTable(ServerPlayer player, String itemName, int requestedCount, boolean allowContainerMaterials) {
        Mob npc = requireNpc(player);
        if (npc == null) {
            return;
        }

        String request = normalizeCraftRequest(itemName);
        BasicCraftTarget target = inferBasicCraftTarget(request);
        int count = requestedCount <= 0 && target == BasicCraftTarget.PLANKS ? 0 : clamp(requestedCount <= 0 ? 1 : requestedCount, 1, 2304);
        if (request.isBlank()) {
            say(player, "I need an item name to craft at a crafting table. Supported targets: axe, pickaxe, planks, sticks, door, basic_tools.");
            return;
        }

        BlockPos table = findNearestCraftingTable(player, npc);
        if (table == null) {
            say(player, "No reachable crafting table found within " + CRAFTING_TABLE_RADIUS + " blocks. Place one nearby or ask me to craft a handheld recipe.");
            return;
        }

        clearBreakProgress(npc);
        clearTask();
        followTargetUuid = null;
        taskOwnerUuid = player.getUUID();
        taskKind = TaskKind.CRAFT_AT_TABLE;
        activeTaskId = newTaskId(taskKind);
        taskRadius = CRAFTING_TABLE_RADIUS;
        taskStepsDone = 0;
        taskIdleTicks = 0;
        taskSearchTimeoutTicks = 0;
        taskOwnerPauseTicks = 0;
        taskPauseAnnounced = false;
        targetBlock = table.immutable();
        targetItemUuid = null;
        autoPickupTargetUuid = null;
        breakTicks = 0;
        placeTicks = 0;
        blockBreakProgress = 0.0F;
        lastBreakStage = -1;
        resetNavigationProgress();
        resetTaskSearchMemory();
        temporarilyBlockedStandPos = null;
        temporarilyBlockedStandPosTicks = 0;
        resetMobilityRecovery();
        craftTableItemRequest = request;
        craftTableRequestedCount = count;
        craftTableAllowContainerMaterials = allowContainerMaterials;
        npc.getNavigation().stop();

        String source = allowContainerMaterials
                ? "NPC storage or explicitly requested nearby containers"
                : "NPC storage or approved nearby containers";
        String message = "Going to crafting table at " + table.getX() + " " + table.getY() + " " + table.getZ()
                + " to craft " + request + " from " + source + ".";
        say(player, message);
        TaskFeedback.info(player, npc, taskName(), "TASK_STARTED", message);
    }

    public static void withdrawFromNearbyChest(ServerPlayer player, String itemName, int requestedCount) {
        AiNpcEntity npc = requireAiNpc(player);
        if (npc == null) {
            return;
        }
        String request = firstNonBlank(itemName, "");
        if (request.isBlank()) {
            say(player, "I need the item name to withdraw from nearby containers.");
            return;
        }

        List<ContainerAccess> containers = findNearbyContainerAccesses(player);
        if (containers.isEmpty()) {
            say(player, "No nearby chest/container found for withdrawal.");
            return;
        }

        int remaining = clamp(requestedCount <= 0 ? 64 : requestedCount, 1, 2304);
        int moved = 0;
        String lastItem = "";
        for (ContainerAccess access : containers) {
            Container container = access.container();
            for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || !matchesItemRequest(stack, request)) {
                    continue;
                }

                int amount = Math.min(remaining, stack.getCount());
                ItemStack extracted = container.removeItem(slot, amount);
                if (extracted.isEmpty()) {
                    continue;
                }
                lastItem = blockName(extracted);
                int extractedCount = extracted.getCount();
                ItemStack remainder = npc.addToInventory(extracted);
                if (!remainder.isEmpty()) {
                    giveOrDrop(player, remainder);
                }
                moved += extractedCount;
                remaining -= extractedCount;
                container.setChanged();
            }
            if (remaining <= 0) {
                break;
            }
        }

        if (moved <= 0) {
            say(player, "No nearby container item matched '" + request + "'.");
            return;
        }

        say(player, "Withdrew " + moved + "x " + lastItem + " from nearby containers into NPC storage first, then your inventory/drop if storage was full.");
        TaskFeedback.info(player, npc, "withdraw_from_chest", "ITEM_WITHDRAWN", "Withdrew " + moved + "x " + lastItem + ".");
        autoEquipBest(npc);
    }

    public static void depositItemToNearbyChest(ServerPlayer player, String itemName, int requestedCount) {
        if (itemName == null || itemName.isBlank()) {
            depositNpcStorageToNearbyChest(player);
            return;
        }

        AiNpcEntity npc = requireAiNpc(player);
        if (npc == null) {
            return;
        }

        List<ContainerAccess> containers = findNearbyContainerAccesses(player);
        if (containers.isEmpty()) {
            say(player, "No nearby chest/container found for deposit.");
            return;
        }

        int remaining = clamp(requestedCount <= 0 ? 2304 : requestedCount, 1, 2304);
        int moved = 0;
        Container inventory = npc.inventory();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !matchesItemRequest(stack, itemName)) {
                continue;
            }

            int amount = Math.min(remaining, stack.getCount());
            ItemStack moving = stack.copy();
            moving.setCount(amount);
            ItemStack remainder = insertIntoContainers(containers, moving);
            int inserted = amount - remainder.getCount();
            if (inserted <= 0) {
                continue;
            }

            stack.shrink(inserted);
            moved += inserted;
            remaining -= inserted;
            inventory.setChanged();
        }

        if (moved <= 0) {
            say(player, "No matching NPC storage item could be deposited, or nearby containers are full.");
            return;
        }

        say(player, "Deposited " + moved + " matching items from NPC storage into nearby containers.");
        TaskFeedback.info(player, npc, "deposit_item_to_chest", "ITEM_DEPOSITED", "Deposited " + moved + " matching items.");
    }

    private static void startPrimitiveTask(ServerPlayer player, Mob npc, TaskKind kind, BlockPos pos, String message) {
        clearBreakProgress(npc);
        clearTask();
        followTargetUuid = null;
        taskOwnerUuid = player.getUUID();
        taskKind = kind;
        activeTaskId = newTaskId(kind);
        taskRadius = McAiConfig.NPC_TASK_RADIUS.get();
        taskStepsDone = 0;
        taskIdleTicks = 0;
        taskSearchTimeoutTicks = 0;
        taskOwnerPauseTicks = 0;
        taskPauseAnnounced = false;
        targetBlock = pos.immutable();
        targetItemUuid = null;
        autoPickupTargetUuid = null;
        breakTicks = 0;
        placeTicks = 0;
        blockBreakProgress = 0.0F;
        lastBreakStage = -1;
        resetNavigationProgress();
        resetTaskSearchMemory();
        temporarilyBlockedStandPos = null;
        temporarilyBlockedStandPosTicks = 0;
        resetMobilityRecovery();
        npc.getNavigation().stop();
        say(player, message);
        TaskFeedback.info(player, npc, taskName(), "TASK_STARTED", message);
    }

    public static void stop(ServerPlayer player) {
        Mob npc = findNpcMob(player.getServer());
        followTargetUuid = null;
        groupFollowTargetUuid = null;
        clearPendingBuild();
        clearPendingRepair();
        if (npc != null) {
            clearBreakProgress(npc);
        }
        clearTask();
        if (npc != null) {
            npc.getNavigation().stop();
        }
        say(player, "NPC stopped.");
    }

    public static boolean hasActiveTask() {
        return taskKind != TaskKind.NONE;
    }

    public static void status(ServerPlayer player) {
        Entity npc = findNpc(player.getServer());
        if (npc == null) {
            say(player, "NPC is not spawned.");
            return;
        }

        say(player, "NPC at "
                + round(npc.getX()) + " " + round(npc.getY()) + " " + round(npc.getZ())
                + " in " + npc.level().dimension().location()
                + ", profile=" + profileId(npc)
                + ", skin=" + (npc instanceof AiNpcEntity aiNpc ? aiNpc.skin() : "")
                + ", task=" + taskName()
                + (taskOwnerPauseTicks <= 0 ? "" : ", paused=" + (taskOwnerPauseTicks / 20) + "s")
                + (taskStepsDone <= 0 ? "" : ", steps=" + taskStepsDone)
                + (followTargetUuid == null && groupFollowTargetUuid == null ? "." : ", following."));
    }

    public static void openInventory(ServerPlayer player) {
        AiNpcEntity npc = requireAiNpc(player);
        if (npc == null) {
            return;
        }

        npc.openInventory(player);
        say(player, "Opened NPC storage. Put blocks, logs, tools, and spare materials here; tasks will use this storage first.");
    }

    public static void equipFromPlayerHand(ServerPlayer player) {
        AiNpcEntity npc = requireAiNpc(player);
        if (npc == null) {
            return;
        }

        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            say(player, "Hold a tool, weapon, shield, or armor item in your main hand, then run /mcai npc equip.");
            return;
        }

        EquipmentSlot slot = npc.getEquipmentSlotForItem(held);
        ItemStack equipped = held.split(1);
        if (held.isEmpty()) {
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }

        ItemStack previous = npc.getItemBySlot(slot);
        npc.setItemSlot(slot, equipped);
        npc.setDropChance(slot, 2.0F);
        npc.setPersistenceRequired();
        storeOrReturn(player, npc, previous);

        say(player, "Equipped " + blockName(equipped) + " to NPC " + slot.getName()
                + (previous.isEmpty() ? "." : "; previous item moved to NPC storage or returned to you."));
    }

    public static void gearStatus(ServerPlayer player) {
        AiNpcEntity npc = requireAiNpc(player);
        if (npc == null) {
            return;
        }

        say(player, "Gear: mainhand=" + stackLabel(npc.getMainHandItem())
                + ", offhand=" + stackLabel(npc.getOffhandItem())
                + ", head=" + stackLabel(npc.getItemBySlot(EquipmentSlot.HEAD))
                + ", chest=" + stackLabel(npc.getItemBySlot(EquipmentSlot.CHEST))
                + ", legs=" + stackLabel(npc.getItemBySlot(EquipmentSlot.LEGS))
                + ", feet=" + stackLabel(npc.getItemBySlot(EquipmentSlot.FEET))
                + ", storage=" + npc.usedInventorySlots() + "/" + AiNpcEntity.INVENTORY_SIZE
                + " slots [" + summarizeContainerItems(npc.inventory(), 8) + "]"
                + ", autoPickup=idle/following, autoEquip=enabled.");
    }

    public static void chestStatus(ServerPlayer player) {
        List<ContainerAccess> containers = findNearbyContainerAccesses(player);
        if (containers.isEmpty()) {
            say(player, "Chest rules: no nearby containers found within " + NEARBY_CONTAINER_RADIUS
                    + " blocks. Build/craft material policy: NPC storage and gathering first; player inventory is never consumed.");
            return;
        }

        ContainerAccess nearest = containers.get(0);
        say(player, "Chest rules: material withdrawal requires approval=" + readiness(isChestMaterialUseApproved(player))
                + ". Priority is equipped gear > NPC storage > self-gathering > approved nearest containers; player inventory is never consumed. "
                + "Collected overflow can still go to chests. Manual deposit uses /mcai npc chest deposit. "
                + "Nearest container at " + nearest.pos().getX() + " " + nearest.pos().getY() + " " + nearest.pos().getZ()
                + ", distance=" + round(nearest.distance())
                + ", contents=[" + summarizeContainerItems(nearest.container(), 8) + "].");
    }

    public static void approveChestMaterials(ServerPlayer player) {
        chestMaterialApprovalOwnerUuid = player.getUUID();
        ChestApprovalSavedData data = chestApprovalData(player);
        data.approvedOwners.add(player.getUUID());
        data.setDirty();
        say(player, "Approved nearby chest/container materials for NPC build/craft/tool tasks. This approval is saved across restarts. Player inventory will still not be consumed.");
        Mob npc = activeNpcMob(player.getServer());
        if (npc != null && hasPendingRepair()) {
            TaskFeedback.info(player, npc, "repair_structure", "CHEST_APPROVAL_RESUME",
                    "Chest material approval received; attempting to resume saved structure repair.");
            continuePendingRepairAfterGather(player, npc);
        } else if (npc != null && pendingBuildKind != BuildKind.NONE) {
            TaskFeedback.info(player, npc, pendingBuildKind.taskName(), "CHEST_APPROVAL_RESUME",
                    "Chest material approval received; attempting to resume saved build.");
            continuePendingBuildAfterGather(player, npc);
        }
    }

    public static void revokeChestMaterials(ServerPlayer player) {
        ChestApprovalSavedData data = chestApprovalData(player);
        data.approvedOwners.remove(player.getUUID());
        data.setDirty();
        chestMaterialApprovalOwnerUuid = null;
        say(player, "Revoked nearby chest/container material approval. NPC tasks will use NPC storage and gathered materials only.");
    }

    public static boolean isChestMaterialUseApproved(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return chestApprovalData(player).approvedOwners.contains(player.getUUID())
                || (chestMaterialApprovalOwnerUuid != null && chestMaterialApprovalOwnerUuid.equals(player.getUUID()));
    }

    public static void depositNpcStorageToNearbyChest(ServerPlayer player) {
        AiNpcEntity npc = requireAiNpc(player);
        if (npc == null) {
            return;
        }

        List<ContainerAccess> containers = findNearbyContainerAccesses(player);
        if (containers.isEmpty()) {
            say(player, "No nearby chest/container found for deposit.");
            return;
        }

        Container inventory = npc.inventory();
        DepositResult result = depositInventoryToContainers(inventory, containers);

        if (result.moved() <= 0) {
            say(player, "Nearby containers have no space for NPC storage items.");
            return;
        }

        npc.setPersistenceRequired();
        say(player, "Deposited " + result.moved() + " items from NPC storage into nearby containers across " + result.touchedStacks() + " stacks. Equipped items were not moved.");
    }

    public static JsonObject runChestSelfTest(ServerPlayer player) {
        JsonObject result = new JsonObject();
        result.addProperty("name", "chest_deposit");
        result.addProperty("ok", false);
        result.addProperty("cleanup", "remove_test_chest");

        if (player == null) {
            result.addProperty("error", "NO_PLAYER");
            return result;
        }

        ServerLevel level = player.serverLevel();
        BlockPos chestPos = findChestSelfTestPos(level, player);
        if (chestPos == null) {
            result.addProperty("error", "NO_SAFE_AIR_BLOCK_NEAR_PLAYER");
            return result;
        }
        result.add("chestPos", blockPosJson(chestPos));

        boolean placed = false;
        try {
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
            placed = true;
            BlockEntity blockEntity = level.getBlockEntity(chestPos);
            if (!(blockEntity instanceof Container container)) {
                result.addProperty("error", "PLACED_BLOCK_IS_NOT_CONTAINER");
                return result;
            }

            container.clearContent();
            container.setItem(0, new ItemStack(Items.OAK_LOG, 2));

            AiNpcEntity testNpc = ModEntities.AI_NPC.get().create(level);
            if (testNpc == null) {
                result.addProperty("error", "NPC_ENTITY_CREATE_FAILED");
                return result;
            }
            testNpc.inventory().setItem(0, new ItemStack(Items.OAK_LOG, 7));

            DepositResult deposit = depositInventoryToContainers(
                    testNpc.inventory(),
                    List.of(new ContainerAccess(container, chestPos, distance(player.blockPosition(), chestPos)))
            );
            int chestLogs = countContainerItem(container, Items.OAK_LOG);
            boolean npcSlotEmpty = testNpc.inventory().getItem(0).isEmpty();
            boolean ok = deposit.moved() == 7 && deposit.touchedStacks() == 1 && chestLogs == 9 && npcSlotEmpty;

            result.addProperty("ok", ok);
            result.addProperty("moved", deposit.moved());
            result.addProperty("touchedStacks", deposit.touchedStacks());
            result.addProperty("expectedChestOakLogs", 9);
            result.addProperty("actualChestOakLogs", chestLogs);
            result.addProperty("npcSlotEmpty", npcSlotEmpty);
            result.addProperty("rule", "NPC storage deposits into nearest containers and merges matching stacks first.");
            if (!ok) {
                result.addProperty("error", "ASSERTION_FAILED");
            }
            return result;
        } catch (RuntimeException error) {
            result.addProperty("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            return result;
        } finally {
            if (placed) {
                BlockEntity blockEntity = level.getBlockEntity(chestPos);
                if (blockEntity instanceof Container container) {
                    container.clearContent();
                }
                level.setBlock(chestPos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    public static float attackDamage(Mob npc) {
        if (npc == null) {
            return 4.0F;
        }
        return Math.max(4.0F, weaponDamage(npc.getMainHandItem()));
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;

        if (tickCounter % AUTO_EQUIP_INTERVAL_TICKS == 0) {
            handleAutoEquip(event.getServer());
        }

        if (followTargetUuid != null && tickCounter % 10 == 0) {
            handleFollow(event.getServer());
        }

        if (groupFollowTargetUuid != null && tickCounter % 10 == 0) {
            handleGroupFollow(event.getServer());
        }

        if (collectItemsTask != null && tickCounter % 5 == 0) {
            handleCollectItemsController(event.getServer());
        } else if (taskKind != TaskKind.NONE && tickCounter % 5 == 0) {
            handleTask(event.getServer());
        }

        if (taskKind == TaskKind.NONE && tickCounter % AUTO_PICKUP_INTERVAL_TICKS == 0) {
            handleAutoPickup(event.getServer());
        }
    }

    public static JsonObject describeFor(ServerPlayer player) {
        JsonObject npcJson = new JsonObject();
        npcJson.addProperty("name", McAiConfig.BOT_NAME.get());
        Entity npc = findNpc(player.getServer());
        npcJson.addProperty("spawned", npc != null);
        npcJson.addProperty("task", taskName());
        npcJson.add("all", describeAll(player));
        if (npc == null) {
            npcJson.addProperty("dimension", player.level().dimension().location().toString());
            npcJson.addProperty("x", round(player.getX()));
            npcJson.addProperty("y", round(player.getY()));
            npcJson.addProperty("z", round(player.getZ()));
            return npcJson;
        }

        npcJson.addProperty("dimension", npc.level().dimension().location().toString());
        npcJson.addProperty("uuid", npc.getUUID().toString());
        npcJson.addProperty("name", npc.getName().getString());
        npcJson.addProperty("profileId", profileId(npc));
        npcJson.addProperty("skin", npc instanceof AiNpcEntity aiNpc ? aiNpc.skin() : "");
        npcJson.addProperty("x", round(npc.getX()));
        npcJson.addProperty("y", round(npc.getY()));
        npcJson.addProperty("z", round(npc.getZ()));
        npcJson.addProperty("following", followTargetUuid != null);
        npcJson.addProperty("groupFollowing", groupFollowTargetUuid != null);
        npcJson.addProperty("taskPaused", taskOwnerPauseTicks > 0);
        npcJson.addProperty("taskPauseSeconds", taskOwnerPauseTicks / 20);
        npcJson.addProperty("taskSearchRemainingSeconds", Math.max(0, taskSearchTimeoutTicks - taskIdleTicks) / 20);
        npcJson.addProperty("taskStepsDone", taskStepsDone);
        JsonObject mobility = new JsonObject();
        mobility.addProperty("repairAttempts", mobilityRepairAttempts);
        mobility.addProperty("maxRepairAttempts", MOBILITY_REPAIR_MAX_ATTEMPTS);
        mobility.addProperty("temporarilyBlockedStandPos", temporarilyBlockedStandPos == null ? "" : positionText(temporarilyBlockedStandPos));
        mobility.addProperty("temporarilyBlockedBreakTarget", temporarilyBlockedBreakTarget == null ? "" : positionText(temporarilyBlockedBreakTarget));
        mobility.addProperty("temporarilyBlockedKnownResourceTarget", temporarilyBlockedKnownResourceTarget == null ? "" : positionText(temporarilyBlockedKnownResourceTarget));
        mobility.addProperty("policy", "bounded bridge/step recovery using NPC storage or approved nearby containers; reports blockers instead of silently spinning");
        npcJson.add("mobility", mobility);
        JsonObject autonomy = new JsonObject();
        autonomy.addProperty("autoPickup", true);
        autonomy.addProperty("autoEquip", true);
        autonomy.addProperty("autoPickupActive", autoPickupTargetUuid != null);
        autonomy.addProperty("autoPickupRadius", AUTO_PICKUP_RADIUS);
        autonomy.addProperty("autoPickupPolicy", "runs while idle/following and pauses during explicit work tasks or active guard combat");
        npcJson.add("autonomy", autonomy);
        npcJson.add("protection", ProtectionManager.describeFor(player));
        npcJson.add("chestRules", chestRulesJson(player));
        if (npc instanceof AiNpcEntity aiNpc) {
            npcJson.add("inventory", aiNpc.inventorySummaryJson());
            npcJson.add("equipment", aiNpc.equipmentSummaryJson());
        }
        return npcJson;
    }

    public static AiNpcEntity activeAiNpc(MinecraftServer server) {
        Entity entity = findNpc(server);
        return entity instanceof AiNpcEntity aiNpc ? aiNpc : null;
    }

    public static Container activeNpcInventory(MinecraftServer server) {
        AiNpcEntity npc = activeAiNpc(server);
        return npc == null ? null : npc.inventory();
    }

    private static JsonObject chestRulesJson(ServerPlayer player) {
        JsonObject json = new JsonObject();
        List<ContainerAccess> containers = findNearbyContainerAccesses(player);
        json.addProperty("enabled", true);
        json.addProperty("nearbyContainerRadius", NEARBY_CONTAINER_RADIUS);
        json.addProperty("nearbyContainerLimit", NEARBY_CONTAINER_LIMIT);
        json.addProperty("priority", "equipped gear > NPC storage > self-gathering > approved nearby containers; player inventory excluded");
        json.addProperty("chestMaterialUseApproved", isChestMaterialUseApproved(player));
        json.addProperty("withdrawPolicy", "build/craft/tool tasks may consume nearby container materials only after player approval; explicit withdraw_from_chest remains a direct command");
        json.addProperty("depositPolicy", "collected overflow goes to nearest containers before player inventory; manual deposit moves NPC storage only");
        json.addProperty("manualCommands", "/mcai npc chest status, /mcai npc chest approve, /mcai npc chest revoke, /mcai npc chest deposit");
        json.addProperty("nearbyContainers", containers.size());
        if (!containers.isEmpty()) {
            ContainerAccess nearest = containers.get(0);
            JsonObject nearestJson = new JsonObject();
            nearestJson.addProperty("x", nearest.pos().getX());
            nearestJson.addProperty("y", nearest.pos().getY());
            nearestJson.addProperty("z", nearest.pos().getZ());
            nearestJson.addProperty("distance", round(nearest.distance()));
            nearestJson.addProperty("occupiedSlots", occupiedSlots(nearest.container()));
            nearestJson.addProperty("freeSlots", freeSlots(nearest.container()));
            nearestJson.addProperty("sampleItems", summarizeContainerItems(nearest.container(), 8));
            json.add("nearest", nearestJson);
        }
        return json;
    }

    public static Mob activeNpcMob(MinecraftServer server) {
        Entity entity = findNpc(server);
        return entity instanceof Mob mob ? mob : null;
    }

    public static String activeProfileId(MinecraftServer server) {
        Entity entity = findNpc(server);
        return entity == null ? NpcProfile.DEFAULT_ID : profileId(entity);
    }

    public static String activeDisplayName(MinecraftServer server) {
        Entity entity = findNpc(server);
        if (entity == null) {
            return McAiConfig.BOT_NAME.get();
        }
        String name = entity.getName().getString();
        return name.isBlank() ? McAiConfig.BOT_NAME.get() : name;
    }

    private static void startTask(ServerPlayer player, TaskKind kind, int requestedRadius, String message) {
        startTask(player, kind, requestedRadius, 0, message);
    }

    private static void startCollectItemsTask(ServerPlayer player, int requestedRadius, String message) {
        Mob npc = requireNpc(player);
        if (npc == null) {
            return;
        }

        clearTask();
        int radius = Math.max(4, Math.min(requestedRadius, McAiConfig.NPC_TASK_RADIUS.get()));
        followTargetUuid = null;
        taskOwnerUuid = player.getUUID();
        taskKind = TaskKind.COLLECT_ITEMS;
        activeTaskId = newTaskId(taskKind);
        taskRadius = radius;
        taskTargetCount = 0;
        taskStepsDone = 0;
        taskIdleTicks = 0;
        taskSearchTimeoutTicks = 0;
        taskOwnerPauseTicks = 0;
        taskPauseAnnounced = false;
        targetBlock = null;
        targetItemUuid = null;
        autoPickupTargetUuid = null;
        resetNavigationProgress();
        resetTaskSearchMemory();
        resetMobilityRecovery();
        temporarilyBlockedStandPos = null;
        temporarilyBlockedStandPosTicks = 0;
        temporarilyBlockedBreakTarget = null;
        temporarilyBlockedBreakTargetTicks = 0;
        collectItemsTask = new CollectItemsTaskState(player.getUUID(), npc.getUUID(), radius, activeTaskId);
        npc.getNavigation().stop();
        say(player, message + " Radius " + radius + ", max steps " + McAiConfig.NPC_MAX_TASK_STEPS.get() + ".");
        TaskFeedback.info(player, npc, taskName(), "TASK_STARTED", message);
        syncActiveTaskRuntime();
    }

    private static void startTask(ServerPlayer player, TaskKind kind, int requestedRadius, int requestedTimeoutTicks, String message) {
        startTask(player, kind, requestedRadius, requestedTimeoutTicks, 0, message);
    }

    private static void startTask(ServerPlayer player, TaskKind kind, int requestedRadius, int requestedTimeoutTicks, int requestedTargetCount, String message) {
        Mob npc = requireNpc(player);
        if (npc == null) {
            return;
        }

        if (!checkTaskToolPreconditions(player, npc, kind)) {
            followTargetUuid = null;
            clearTask();
            npc.getNavigation().stop();
            return;
        }

        int radius = Math.max(4, Math.min(requestedRadius, McAiConfig.NPC_TASK_RADIUS.get()));
        followTargetUuid = null;
        taskOwnerUuid = player.getUUID();
        taskKind = kind;
        activeTaskId = newTaskId(kind);
        taskRadius = radius;
        taskTargetCount = Math.max(0, Math.min(requestedTargetCount, McAiConfig.NPC_MAX_TASK_STEPS.get()));
        taskStepsDone = 0;
        taskIdleTicks = 0;
        taskSearchTimeoutTicks = Math.max(0, requestedTimeoutTicks);
        taskOwnerPauseTicks = 0;
        taskPauseAnnounced = false;
        targetBlock = null;
        targetItemUuid = null;
        autoPickupTargetUuid = null;
        breakTicks = 0;
        placeTicks = 0;
        blockBreakProgress = 0.0F;
        lastBreakStage = -1;
        resetNavigationProgress();
        resetTaskSearchMemory();
        temporarilyBlockedStandPos = null;
        temporarilyBlockedStandPosTicks = 0;
        temporarilyBlockedBreakTarget = null;
        temporarilyBlockedBreakTargetTicks = 0;
        resetMobilityRecovery();
        npc.getNavigation().stop();
        String targetText = taskTargetCount > 0 ? ", target " + taskTargetCount + " blocks" : "";
        say(player, message + " Radius " + radius + targetText + ", max steps " + McAiConfig.NPC_MAX_TASK_STEPS.get() + ".");
        TaskFeedback.info(player, npc, taskName(), "TASK_STARTED", message);
        recordTaskToolReadiness(player, npc, kind);
    }

    private static boolean checkTaskToolPreconditions(ServerPlayer player, Mob npc, TaskKind kind) {
        ToolSummary.ToolKind requiredTool = requiredTool(kind);
        if (requiredTool == null) {
            return true;
        }

        ToolSummary.ToolMatch tool = ToolSummary.bestTool(player, requiredTool);
        if (!tool.available() || isLowDurability(tool, kind)) {
            craftBasicTool(player, npc, kind, requiredTool);
            tool = ToolSummary.bestTool(player, requiredTool);
        }
        if (!tool.available()) {
            String message = taskStartFailure(kind) + ": I need a " + requiredTool.label()
                    + ", or enough wood/stone materials in NPC storage or approved nearby containers to craft one."
                    + chestApprovalHint(player);
            say(player, message);
            TaskFeedback.failure(player, npc, taskNameFor(kind), "NEED_" + requiredTool.label().toUpperCase(), message);
            return false;
        }

        if (isLowDurability(tool, kind)) {
            String message = taskStartFailure(kind) + ": " + tool.itemId() + " durability is too low (" + tool.remainingDurability() + "/" + tool.maxDurability() + ").";
            say(player, message);
            TaskFeedback.failure(player, npc, taskNameFor(kind), "LOW_" + requiredTool.label().toUpperCase() + "_DURABILITY", message);
            return false;
        }

        return true;
    }

    private static boolean isLowDurability(ToolSummary.ToolMatch tool, TaskKind kind) {
        int minimumDurability = (kind == TaskKind.MINE_ORES || kind == TaskKind.GATHER_STONE) ? MIN_MINING_TOOL_DURABILITY : MIN_HARVEST_TOOL_DURABILITY;
        return tool.available() && tool.maxDurability() > 0 && tool.remainingDurability() < minimumDurability;
    }

    private static boolean craftBasicTool(ServerPlayer player, Mob npc, TaskKind kind, ToolSummary.ToolKind requiredTool) {
        return craftBasicTool(player, npc, kind, requiredTool, false);
    }

    private static boolean craftBasicTool(ServerPlayer player, Mob npc, TaskKind kind, ToolSummary.ToolKind requiredTool, boolean allowContainerMaterials) {
        return craftBasicTool(player, npc, kind, requiredTool, allowContainerMaterials, ToolMaterialPreference.ANY, false);
    }

    private static boolean craftBasicTool(
            ServerPlayer player,
            Mob npc,
            TaskKind kind,
            ToolSummary.ToolKind requiredTool,
            boolean allowContainerMaterials,
            ToolMaterialPreference materialPreference,
            boolean reportFailure
    ) {
        ToolCraftPlan plan = findToolCraftPlan(player, requiredTool, allowContainerMaterials, materialPreference);
        if (plan == null) {
            if (reportFailure) {
                CraftingCounts counts = countCraftingMaterials(player, allowContainerMaterials);
                String message = "I could not craft a " + preferredToolName(materialPreference, requiredTool)
                        + " from " + materialSourceLabel(allowContainerMaterials)
                        + ". Available materials: logs=" + counts.logs()
                        + ", planks=" + counts.planks()
                        + ", sticks=" + counts.sticks()
                        + ", stone=" + counts.stoneHeadMaterials()
                        + "." + (allowContainerMaterials ? "" : chestApprovalHint(player));
                say(player, message);
                TaskFeedback.failure(player, npc, taskNameFor(kind), "TOOL_CRAFT_NO_MATERIALS", message);
            }
            return false;
        }
        if (!canFitCraftedTool(player, npc, plan)) {
            String message = "I can craft a " + requiredTool.label() + ", but NPC storage has no free slot for it.";
            if (reportFailure) {
                say(player, message);
            }
            TaskFeedback.warn(player, npc, taskNameFor(kind), "TOOL_CRAFT_BLOCKED", message);
            return false;
        }

        CraftLeftovers leftovers = consumeToolCraftMaterials(player, plan, allowContainerMaterials);
        if (leftovers == null) {
            if (reportFailure) {
                String message = "Tool crafting failed while consuming materials from " + materialSourceLabel(allowContainerMaterials) + ".";
                say(player, message);
                TaskFeedback.failure(player, npc, taskNameFor(kind), "TOOL_CRAFT_CONSUME_FAILED", message);
            }
            return false;
        }

        ItemStack crafted = new ItemStack(plan.result());
        if (!storeCraftedTool(player, npc, crafted, requiredTool)) {
            String message = "I crafted " + itemId(plan.result()) + ", but could not place it in NPC storage.";
            if (reportFailure) {
                say(player, message);
            }
            TaskFeedback.warn(player, npc, taskNameFor(kind), "TOOL_CRAFT_INSERT_FAILED", message);
            return false;
        }
        if (leftovers.planks() > 0) {
            giveOrStoreOrDrop(player, npc, new ItemStack(Items.OAK_PLANKS, leftovers.planks()));
        }
        if (leftovers.sticks() > 0) {
            giveOrStoreOrDrop(player, npc, new ItemStack(Items.STICK, leftovers.sticks()));
        }

        String message = "Crafted " + itemId(plan.result()) + " from " + plan.materialLabel()
                + " in " + materialSourceLabel(allowContainerMaterials) + ".";
        say(player, message);
        TaskFeedback.info(player, npc, taskNameFor(kind), "TOOL_CRAFTED", message);
        return true;
    }

    private static boolean canFitCraftedTool(ServerPlayer player, Mob npc, ToolCraftPlan plan) {
        if (npc instanceof AiNpcEntity aiNpc && (aiNpc.getMainHandItem().isEmpty() || aiNpc.inventory().canAddItem(new ItemStack(plan.result())))) {
            return true;
        }
        return false;
    }

    private static boolean wouldConsumePlankUnitsFreeSlot(Container container, int plankUnits) {
        int remaining = plankUnits;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !isPlank(stack)) {
                continue;
            }
            if (stack.getCount() <= remaining) {
                return true;
            }
            remaining -= Math.min(remaining, stack.getCount());
        }

        if (remaining <= 0) {
            return false;
        }
        int logsNeeded = divideCeil(remaining, PLANKS_PER_LOG);
        return wouldConsumeWholeStack(container, NpcManager::isCraftingLog, logsNeeded);
    }

    private static boolean wouldConsumeWholeStack(Container container, Predicate<ItemStack> matcher, int count) {
        int remaining = count;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            if (stack.getCount() <= remaining) {
                return true;
            }
            remaining -= Math.min(remaining, stack.getCount());
        }
        return false;
    }

    private static ToolCraftPlan findToolCraftPlan(ServerPlayer player, ToolSummary.ToolKind requiredTool) {
        return findToolCraftPlan(player, requiredTool, false);
    }

    private static ToolCraftPlan findToolCraftPlan(ServerPlayer player, ToolSummary.ToolKind requiredTool, boolean allowContainerMaterials) {
        return findToolCraftPlan(player, requiredTool, allowContainerMaterials, ToolMaterialPreference.ANY);
    }

    private static ToolCraftPlan findToolCraftPlan(
            ServerPlayer player,
            ToolSummary.ToolKind requiredTool,
            boolean allowContainerMaterials,
            ToolMaterialPreference materialPreference
    ) {
        CraftingCounts counts = countCraftingMaterials(player, allowContainerMaterials);
        if (materialPreference != ToolMaterialPreference.WOOD) {
            Item stoneTool = requiredTool == ToolSummary.ToolKind.PICKAXE ? Items.STONE_PICKAXE : Items.STONE_AXE;
            ToolCraftPlan stonePlan = buildToolCraftPlan(stoneTool, TOOL_HEAD_MATERIAL_COST, 0, counts, "stone");
            if (stonePlan != null) {
                return stonePlan;
            }
        }

        if (materialPreference != ToolMaterialPreference.STONE) {
            Item woodenTool = requiredTool == ToolSummary.ToolKind.PICKAXE ? Items.WOODEN_PICKAXE : Items.WOODEN_AXE;
            return buildToolCraftPlan(woodenTool, 0, TOOL_HEAD_MATERIAL_COST, counts, "wood");
        }

        return null;
    }

    private static ToolCraftPlan buildToolCraftPlan(Item result, int stoneCost, int woodenHeadPlanks, CraftingCounts counts, String materialLabel) {
        if (stoneCost > 0 && counts.stoneHeadMaterials() < stoneCost) {
            return null;
        }

        int existingSticksUsed = Math.min(counts.sticks(), TOOL_STICK_COST);
        int missingSticks = TOOL_STICK_COST - existingSticksUsed;
        int stickCrafts = divideCeil(missingSticks, STICKS_PER_CRAFT);
        int planksForSticks = stickCrafts * PLANKS_PER_STICK_CRAFT;
        int totalPlanksNeeded = woodenHeadPlanks + planksForSticks;
        int availablePlankUnits = counts.planks() + counts.logs() * PLANKS_PER_LOG;
        if (availablePlankUnits < totalPlanksNeeded) {
            return null;
        }

        int craftedStickLeftover = stickCrafts * STICKS_PER_CRAFT - missingSticks;
        return new ToolCraftPlan(result, stoneCost, totalPlanksNeeded, existingSticksUsed, craftedStickLeftover, materialLabel);
    }

    private static CraftLeftovers consumeToolCraftMaterials(ServerPlayer player, ToolCraftPlan plan) {
        return consumeToolCraftMaterials(player, plan, false);
    }

    private static CraftLeftovers consumeToolCraftMaterials(ServerPlayer player, ToolCraftPlan plan, boolean allowContainerMaterials) {
        if (plan.stoneCost() > 0 && consumeMatchingItems(player, NpcManager::isStoneToolHeadMaterial, plan.stoneCost(), allowContainerMaterials) < plan.stoneCost()) {
            return null;
        }
        if (plan.existingSticksUsed() > 0 && consumeMatchingItems(player, stack -> stack.is(Items.STICK), plan.existingSticksUsed(), allowContainerMaterials) < plan.existingSticksUsed()) {
            return null;
        }
        int leftoverPlanks = 0;
        if (plan.plankUnitsNeeded() > 0) {
            leftoverPlanks = consumePlankUnits(player, plan.plankUnitsNeeded(), allowContainerMaterials);
            if (leftoverPlanks < 0) {
                return null;
            }
        }
        return new CraftLeftovers(plan.craftedStickLeftover(), leftoverPlanks);
    }

    private static CraftingCounts countCraftingMaterials(ServerPlayer player) {
        return countCraftingMaterials(player, false);
    }

    private static CraftingCounts countCraftingMaterials(ServerPlayer player, boolean allowContainerMaterials) {
        MaterialCounter counter = new MaterialCounter();
        AiNpcEntity npc = activeAiNpc(player.getServer());
        if (npc != null) {
            countCraftingMaterials(npc.inventory(), counter);
        }
        for (Container container : findMaterialContainers(player, allowContainerMaterials)) {
            countCraftingMaterials(container, counter);
        }
        return counter.toCounts();
    }

    private static void countCraftingMaterials(Container container, MaterialCounter counter) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(Items.STICK)) {
                counter.sticks += stack.getCount();
            } else if (isPlank(stack)) {
                counter.planks += stack.getCount();
            } else if (isCraftingLog(stack)) {
                counter.logs += stack.getCount();
            } else if (isStoneToolHeadMaterial(stack)) {
                counter.stoneHeadMaterials += stack.getCount();
            }
        }
    }

    private static int consumeMatchingItems(ServerPlayer player, Predicate<ItemStack> matcher, int count) {
        return consumeMatchingItems(player, matcher, count, false);
    }

    private static int consumeMatchingItems(ServerPlayer player, Predicate<ItemStack> matcher, int count, boolean allowContainerMaterials) {
        int remaining = count;
        AiNpcEntity npc = activeAiNpc(player.getServer());
        if (npc != null) {
            remaining = consumeMatchingItems(npc.inventory(), matcher, remaining);
        }
        for (Container container : findMaterialContainers(player, allowContainerMaterials)) {
            if (remaining <= 0) {
                break;
            }
            remaining = consumeMatchingItems(container, matcher, remaining);
        }
        return count - remaining;
    }

    private static int consumeMatchingItems(Container container, Predicate<ItemStack> matcher, int count) {
        int remaining = count;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            int consumed = Math.min(remaining, stack.getCount());
            stack.shrink(consumed);
            remaining -= consumed;
            container.setChanged();
        }
        return remaining;
    }

    private static int consumePlankUnits(ServerPlayer player, int plankUnits) {
        return consumePlankUnits(player, plankUnits, false);
    }

    private static int consumePlankUnits(ServerPlayer player, int plankUnits, boolean allowContainerMaterials) {
        int consumedPlanks = consumeMatchingItems(player, NpcManager::isPlank, plankUnits, allowContainerMaterials);
        int remaining = plankUnits - consumedPlanks;
        if (remaining <= 0) {
            return 0;
        }

        int logsNeeded = divideCeil(remaining, PLANKS_PER_LOG);
        int consumedLogs = consumeMatchingItems(player, NpcManager::isCraftingLog, logsNeeded, allowContainerMaterials);
        if (consumedLogs < logsNeeded) {
            return -1;
        }

        return consumedLogs * PLANKS_PER_LOG - remaining;
    }

    private static boolean craftPlanks(ServerPlayer player, Mob npc, int targetPlanks) {
        return craftPlanks(player, npc, targetPlanks, false);
    }

    private static boolean craftPlanks(ServerPlayer player, Mob npc, int targetPlanks, boolean allowContainerMaterials) {
        boolean craftAll = targetPlanks <= 0;
        int requested = craftAll ? 2304 : clamp(targetPlanks, PLANKS_PER_LOG, 2304);
        int logsNeeded = craftAll ? requested : divideCeil(requested, PLANKS_PER_LOG);
        int consumedLogs = consumeMatchingItems(player, NpcManager::isCraftingLog, logsNeeded, allowContainerMaterials);
        if (consumedLogs <= 0) {
            String message = "No logs are available in " + materialSourceLabel(allowContainerMaterials) + " for plank crafting."
                    + (allowContainerMaterials ? "" : chestApprovalHint(player));
            say(player, message);
            TaskFeedback.warn(player, npc, taskKind == TaskKind.CRAFT_AT_TABLE ? "craft_at_table" : "craft_item", "NEED_LOGS", message);
            return false;
        }

        int crafted = consumedLogs * PLANKS_PER_LOG;
        giveOrStoreOrDrop(player, npc, new ItemStack(Items.OAK_PLANKS, crafted));
        String message = (craftAll ? "Converted all available logs: " : "Crafted ")
                + crafted + " oak planks from " + consumedLogs + " logs using " + materialSourceLabel(allowContainerMaterials) + ".";
        say(player, message);
        TaskFeedback.info(player, npc, taskKind == TaskKind.CRAFT_AT_TABLE ? "craft_at_table" : "craft_item", "PLANKS_CRAFTED", message);
        return true;
    }

    private static boolean craftSticks(ServerPlayer player, Mob npc, int targetSticks) {
        return craftSticks(player, npc, targetSticks, false);
    }

    private static boolean craftSticks(ServerPlayer player, Mob npc, int targetSticks, boolean allowContainerMaterials) {
        int crafts = divideCeil(clamp(targetSticks, STICKS_PER_CRAFT, 2304), STICKS_PER_CRAFT);
        int planksNeeded = crafts * PLANKS_PER_STICK_CRAFT;
        CraftingCounts counts = countCraftingMaterials(player, allowContainerMaterials);
        if (counts.planks() + counts.logs() * PLANKS_PER_LOG < planksNeeded) {
            String message = "Not enough planks/logs to craft sticks. Need " + planksNeeded + " plank units.";
            say(player, message);
            TaskFeedback.warn(player, npc, taskKind == TaskKind.CRAFT_AT_TABLE ? "craft_at_table" : "craft_item", "NEED_PLANKS", message);
            return false;
        }

        int leftoverPlanks = consumePlankUnits(player, planksNeeded, allowContainerMaterials);
        if (leftoverPlanks < 0) {
            String message = "Stick crafting failed while consuming plank units.";
            say(player, message);
            TaskFeedback.failure(player, npc, taskKind == TaskKind.CRAFT_AT_TABLE ? "craft_at_table" : "craft_item", "CRAFT_CONSUME_FAILED", message);
            return false;
        }

        int crafted = crafts * STICKS_PER_CRAFT;
        giveOrStoreOrDrop(player, npc, new ItemStack(Items.STICK, crafted));
        if (leftoverPlanks > 0) {
            giveOrStoreOrDrop(player, npc, new ItemStack(Items.OAK_PLANKS, leftoverPlanks));
        }
        String message = "Crafted " + crafted + " sticks using " + planksNeeded + " plank units from " + materialSourceLabel(allowContainerMaterials) + ".";
        say(player, message);
        TaskFeedback.info(player, npc, taskKind == TaskKind.CRAFT_AT_TABLE ? "craft_at_table" : "craft_item", "STICKS_CRAFTED", message);
        return true;
    }

    private static boolean craftDoors(ServerPlayer player, Mob npc, int targetDoors, boolean allowContainerMaterials) {
        int doorsRequested = clamp(targetDoors <= 0 ? 1 : targetDoors, 1, 2304);
        int crafts = divideCeil(doorsRequested, DOORS_PER_CRAFT);
        int planksNeeded = crafts * PLANKS_PER_DOOR_CRAFT;
        CraftingCounts counts = countCraftingMaterials(player, allowContainerMaterials);
        if (counts.planks() + counts.logs() * PLANKS_PER_LOG < planksNeeded) {
            String message = "Not enough planks/logs to craft doors. Need " + planksNeeded
                    + " plank units for " + (crafts * DOORS_PER_CRAFT) + " oak doors."
                    + (allowContainerMaterials ? "" : chestApprovalHint(player));
            say(player, message);
            TaskFeedback.warn(player, npc, taskKind == TaskKind.CRAFT_AT_TABLE ? "craft_at_table" : "craft_item", "NEED_DOOR_PLANKS", message);
            return false;
        }

        int leftoverPlanks = consumePlankUnits(player, planksNeeded, allowContainerMaterials);
        if (leftoverPlanks < 0) {
            String message = "Door crafting failed while consuming plank units.";
            say(player, message);
            TaskFeedback.failure(player, npc, taskKind == TaskKind.CRAFT_AT_TABLE ? "craft_at_table" : "craft_item", "DOOR_CRAFT_CONSUME_FAILED", message);
            return false;
        }

        int crafted = crafts * DOORS_PER_CRAFT;
        giveOrStoreOrDrop(player, npc, new ItemStack(Items.OAK_DOOR, crafted));
        if (leftoverPlanks > 0) {
            giveOrStoreOrDrop(player, npc, new ItemStack(Items.OAK_PLANKS, leftoverPlanks));
        }
        String message = "Crafted " + crafted + " oak doors using " + planksNeeded
                + " plank units from " + materialSourceLabel(allowContainerMaterials) + ".";
        say(player, message);
        TaskFeedback.info(player, npc, taskKind == TaskKind.CRAFT_AT_TABLE ? "craft_at_table" : "craft_item", "DOORS_CRAFTED", message);
        return true;
    }

    private static boolean storeCraftedTool(ServerPlayer player, Mob npc, ItemStack crafted, ToolSummary.ToolKind requiredTool) {
        if (crafted.isEmpty()) {
            return true;
        }
        if (npc instanceof AiNpcEntity aiNpc) {
            if (aiNpc.getMainHandItem().isEmpty() && matchesTool(crafted, requiredTool)) {
                aiNpc.setItemSlot(EquipmentSlot.MAINHAND, crafted.copy());
                aiNpc.setDropChance(EquipmentSlot.MAINHAND, 2.0F);
                aiNpc.setPersistenceRequired();
                return true;
            }

            ItemStack remainder = aiNpc.addToInventory(crafted);
            if (remainder.isEmpty()) {
                return true;
            }
            crafted = remainder;
        }

        return false;
    }

    private static void handleAutoEquip(MinecraftServer server) {
        AiNpcEntity npc = activeAiNpc(server);
        if (npc == null || !npc.isAlive()) {
            return;
        }
        autoEquipBest(npc);
    }

    private static boolean autoEquipBest(AiNpcEntity npc) {
        boolean changed = false;
        changed |= autoEquipArmorSlot(npc, EquipmentSlot.HEAD);
        changed |= autoEquipArmorSlot(npc, EquipmentSlot.CHEST);
        changed |= autoEquipArmorSlot(npc, EquipmentSlot.LEGS);
        changed |= autoEquipArmorSlot(npc, EquipmentSlot.FEET);
        changed |= autoEquipOffhandShield(npc);
        changed |= autoEquipMainHand(npc, preferredMainHandKind());
        if (changed) {
            npc.setPersistenceRequired();
        }
        return changed;
    }

    private static ToolSummary.ToolKind preferredMainHandKind() {
        return switch (taskKind) {
            case MINE_ORES, GATHER_STONE -> ToolSummary.ToolKind.PICKAXE;
            case HARVEST_LOGS -> ToolSummary.ToolKind.AXE;
            case BUILD_BASIC_HOUSE, BUILD_LARGE_HOUSE, REPAIR_STRUCTURE, CRAFT_AT_TABLE, BREAK_BLOCK, PLACE_BLOCK, COLLECT_ITEMS, NONE -> ToolSummary.ToolKind.WEAPON;
        };
    }

    private static boolean autoEquipArmorSlot(AiNpcEntity npc, EquipmentSlot equipmentSlot) {
        int currentScore = armorScore(npc.getItemBySlot(equipmentSlot), equipmentSlot);
        int bestSlot = -1;
        int bestScore = currentScore;
        Container inventory = npc.inventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || npc.getEquipmentSlotForItem(stack) != equipmentSlot) {
                continue;
            }

            int score = armorScore(stack, equipmentSlot);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot >= 0 && equipFromNpcStorage(npc, bestSlot, equipmentSlot);
    }

    private static boolean autoEquipMainHand(AiNpcEntity npc, ToolSummary.ToolKind kind) {
        int currentScore = mainHandScore(npc.getMainHandItem(), kind);
        int bestSlot = -1;
        int bestScore = currentScore;
        Container inventory = npc.inventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !matchesTool(stack, kind)) {
                continue;
            }

            int score = mainHandScore(stack, kind);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot >= 0 && equipFromNpcStorage(npc, bestSlot, EquipmentSlot.MAINHAND);
    }

    private static boolean autoEquipOffhandShield(AiNpcEntity npc) {
        ItemStack current = npc.getOffhandItem();
        if (!current.isEmpty() && !current.is(Items.SHIELD)) {
            return false;
        }

        int currentScore = shieldScore(current);
        int bestSlot = -1;
        int bestScore = currentScore;
        Container inventory = npc.inventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.is(Items.SHIELD)) {
                continue;
            }
            int score = shieldScore(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot >= 0 && equipFromNpcStorage(npc, bestSlot, EquipmentSlot.OFFHAND);
    }

    private static boolean equipFromNpcStorage(AiNpcEntity npc, int sourceSlot, EquipmentSlot equipmentSlot) {
        Container inventory = npc.inventory();
        ItemStack selected = inventory.removeItem(sourceSlot, 1);
        if (selected.isEmpty()) {
            return false;
        }

        ItemStack previous = npc.getItemBySlot(equipmentSlot);
        npc.setItemSlot(equipmentSlot, selected);
        npc.setDropChance(equipmentSlot, 2.0F);
        npc.setPersistenceRequired();
        if (!previous.isEmpty()) {
            ItemStack leftover = npc.addToInventory(previous);
            if (!leftover.isEmpty()) {
                npc.spawnAtLocation(leftover);
            }
        }
        return true;
    }

    private static void giveOrStoreOrDrop(ServerPlayer player, Mob npc, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (npc instanceof AiNpcEntity aiNpc) {
            stack = aiNpc.addToInventory(stack);
            if (stack.isEmpty()) {
                return;
            }
        }
        giveOrDrop(player, stack);
    }

    private static void storeOrReturn(ServerPlayer player, AiNpcEntity npc, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack remainder = npc.addToInventory(stack);
        if (remainder.isEmpty()) {
            return;
        }
        giveOrDrop(player, remainder);
    }

    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (!player.getInventory().add(stack) && !stack.isEmpty()) {
            player.drop(stack, false);
        }
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

    private static boolean isUsableBuildBlock(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
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

    private static boolean matchesBlockRequest(ItemStack stack, BlockItem blockItem, String request) {
        String normalized = normalizeRequest(request);
        if (normalized.isBlank()) {
            return true;
        }

        String itemId = normalizeRequest(itemId(stack));
        String blockId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString().toLowerCase(Locale.ROOT);
        String hoverName = normalizeRequest(stack.getHoverName().getString());
        return itemId.contains(normalized)
                || normalizeRequest(blockId).contains(normalized)
                || hoverName.contains(normalized);
    }

    private static boolean matchesItemRequest(ItemStack stack, String request) {
        String normalized = normalizeRequest(request);
        if (normalized.isBlank()) {
            return true;
        }

        String id = normalizeRequest(itemId(stack));
        String hoverName = normalizeRequest(stack.getHoverName().getString());
        return id.contains(normalized) || hoverName.contains(normalized);
    }

    private static String normalizeRequest(String value) {
        return firstNonBlank(value, "")
                .toLowerCase(Locale.ROOT)
                .replace("minecraft:", "")
                .replace(" ", "")
                .replace("_", "");
    }

    private static boolean isWithinPrimitiveRange(ServerPlayer player, BlockPos pos) {
        int range = Math.max(4, McAiConfig.NPC_TASK_RADIUS.get());
        return player.blockPosition().distSqr(pos) <= (double) range * range;
    }

    private static String toolLabel(ToolUse tool) {
        if (tool == null || tool.stack().isEmpty()) {
            return "hand";
        }
        return blockName(tool.stack()) + " from " + tool.source();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String readiness(boolean ready) {
        return ready ? "ready" : "missing";
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static int divideCeil(int value, int divisor) {
        if (value <= 0) {
            return 0;
        }
        return (value + divisor - 1) / divisor;
    }

    private static void handleFollow(MinecraftServer server) {
        ServerPlayer target = server.getPlayerList().getPlayer(followTargetUuid);
        Mob npc = findNpcMob(server);
        if (target == null || npc == null || npc.level() != target.level()) {
            return;
        }

        double distance = npc.distanceTo(target);
        double followDistance = Math.max(2.0D, McAiConfig.NPC_FOLLOW_DISTANCE.get());
        if (distance > followDistance + 1.0D) {
            npc.getNavigation().moveTo(target, McAiConfig.NPC_MOVE_SPEED.get());
        } else if (distance <= followDistance) {
            npc.getNavigation().stop();
        }
    }

    private static void handleGroupFollow(MinecraftServer server) {
        ServerPlayer target = server.getPlayerList().getPlayer(groupFollowTargetUuid);
        if (target == null || !target.isAlive()) {
            groupFollowTargetUuid = null;
            return;
        }

        int formationIndex = 0;
        double followDistance = Math.max(2.0D, McAiConfig.NPC_FOLLOW_DISTANCE.get());
        for (Mob npc : companionMobs(server)) {
            if (npc.level() != target.level()) {
                npc.getNavigation().stop();
                continue;
            }
            if (taskKind != TaskKind.NONE && npc.getUUID().equals(npcUuid)) {
                continue;
            }

            double radius = 2.0D + (formationIndex / 8) * 1.5D;
            double angle = (Math.PI * 2.0D / 8.0D) * (formationIndex % 8);
            double x = target.getX() + Math.cos(angle) * radius;
            double z = target.getZ() + Math.sin(angle) * radius;
            double dx = npc.getX() - x;
            double dy = npc.getY() - target.getY();
            double dz = npc.getZ() - z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > followDistance + 1.0D) {
                npc.getNavigation().moveTo(x, target.getY(), z, McAiConfig.NPC_MOVE_SPEED.get());
            } else if (distance <= followDistance) {
                npc.getNavigation().stop();
            }
            formationIndex++;
        }
    }

    private static void handleAutoPickup(MinecraftServer server) {
        if (ProtectionManager.hasActiveThreat()) {
            autoPickupTargetUuid = null;
            return;
        }

        AiNpcEntity npc = activeAiNpc(server);
        if (npc == null || !npc.isAlive()) {
            autoPickupTargetUuid = null;
            return;
        }

        ServerPlayer owner = currentFollowOwner(server, npc);
        if ((followTargetUuid != null || groupFollowTargetUuid != null) && owner == null) {
            autoPickupTargetUuid = null;
            return;
        }

        ItemEntity target = findAutoPickupTarget(npc, owner);
        if (target == null) {
            autoPickupTargetUuid = null;
            return;
        }

        autoPickupTargetUuid = target.getUUID();
        if (npc.distanceToSqr(target) > AUTO_PICKUP_REACH_SQR) {
            npc.getNavigation().moveTo(target, McAiConfig.NPC_MOVE_SPEED.get());
            npc.getLookControl().setLookAt(target, 20.0F, 20.0F);
            return;
        }

        ItemStack incoming = target.getItem().copy();
        String itemName = incoming.getHoverName().getString();
        int originalCount = incoming.getCount();
        ItemStack remainder = npc.addToInventory(incoming);
        if (!remainder.isEmpty()) {
            remainder = insertIntoNearbyContainers(npc, owner, remainder);
        }
        int moved = originalCount - remainder.getCount();
        if (moved <= 0) {
            autoPickupTargetUuid = null;
            return;
        }

        if (remainder.isEmpty()) {
            target.discard();
        } else {
            target.setItem(remainder);
        }
        autoPickupTargetUuid = null;
        autoEquipBest(npc);
        if (owner != null) {
            TaskFeedback.info(owner, npc, "npc_autonomy", "AUTO_PICKUP",
                    "Picked up " + moved + "x " + itemName + " into NPC storage or nearby containers.");
        }
    }

    private static ServerPlayer currentFollowOwner(MinecraftServer server, AiNpcEntity npc) {
        UUID ownerUuid = followTargetUuid != null ? followTargetUuid : groupFollowTargetUuid;
        if (ownerUuid == null) {
            return null;
        }

        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        return owner != null && owner.level() == npc.level() && owner.isAlive() ? owner : null;
    }

    private static ItemEntity findAutoPickupTarget(AiNpcEntity npc, ServerPlayer owner) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return null;
        }

        if (autoPickupTargetUuid != null) {
            Entity entity = level.getEntity(autoPickupTargetUuid);
            if (entity instanceof ItemEntity item && isAutoPickupCandidate(npc, owner, item)) {
                return item;
            }
        }

        List<ItemEntity> items = level.getEntitiesOfClass(
                ItemEntity.class,
                npc.getBoundingBox().inflate(AUTO_PICKUP_RADIUS),
                item -> isAutoPickupCandidate(npc, owner, item)
        );
        return items.stream()
                .min(Comparator.comparingDouble(item -> item.distanceToSqr(npc)))
                .orElse(null);
    }

    private static boolean isAutoPickupCandidate(AiNpcEntity npc, ServerPlayer owner, ItemEntity item) {
        if (!item.isAlive() || item.getItem().isEmpty() || item.level() != npc.level()) {
            return false;
        }
        if (!npc.inventory().canAddItem(item.getItem())) {
            return canInsertIntoNearbyContainer(npc, owner, item.getItem());
        }
        if (owner == null) {
            return true;
        }
        double ownerRadiusSqr = AUTO_PICKUP_OWNER_RADIUS * AUTO_PICKUP_OWNER_RADIUS;
        return item.distanceToSqr(owner) <= ownerRadiusSqr && npc.distanceToSqr(owner) <= ownerRadiusSqr;
    }

    private static void handleTask(MinecraftServer server) {
        ServerPlayer owner = server.getPlayerList().getPlayer(taskOwnerUuid);
        Mob npc = findNpcMob(server);
        if (npc == null) {
            clearTask();
            return;
        }

        if (owner == null || !owner.isAlive() || npc.level() != owner.level()) {
            pauseTaskUntilOwnerReturns(owner, npc);
            return;
        }

        resumeTaskIfPaused(owner);
        tickTemporaryNavigationBlocks();

        switch (taskKind) {
            case COLLECT_ITEMS -> handleCollectItems(owner, npc);
            case MINE_ORES -> handleBlockTask(owner, npc, TaskKind.MINE_ORES);
            case GATHER_STONE -> handleBlockTask(owner, npc, TaskKind.GATHER_STONE);
            case HARVEST_LOGS -> handleBlockTask(owner, npc, TaskKind.HARVEST_LOGS);
            case BUILD_BASIC_HOUSE, BUILD_LARGE_HOUSE -> handleBuildHouse(owner, npc);
            case REPAIR_STRUCTURE -> handleRepairStructure(owner, npc);
            case CRAFT_AT_TABLE -> handleCraftAtTable(owner, npc);
            case BREAK_BLOCK -> handlePrimitiveBreakBlock(owner, npc);
            case PLACE_BLOCK -> handlePrimitivePlaceBlock(owner, npc);
            case NONE -> {
            }
        }
    }

    private static void handleCollectItemsController(MinecraftServer server) {
        CollectItemsTaskState state = collectItemsTask;
        if (state == null) {
            return;
        }

        applyCollectItemsTaskState(state);
        ServerPlayer owner = server.getPlayerList().getPlayer(state.ownerUuid);
        Mob npc = findMobByUuid(server, state.npcUuid);
        if (npc == null) {
            clearTask();
            return;
        }

        if (owner == null || !owner.isAlive() || npc.level() != owner.level()) {
            pauseTaskUntilOwnerReturns(owner, npc);
            syncCollectItemsTaskFromLegacy();
            return;
        }

        resumeTaskIfPaused(owner);
        tickTemporaryNavigationBlocks();
        handleCollectItems(owner, npc);
        syncCollectItemsTaskFromLegacy();
    }

    private static void applyCollectItemsTaskState(CollectItemsTaskState state) {
        npcUuid = state.npcUuid;
        taskOwnerUuid = state.ownerUuid;
        taskKind = TaskKind.COLLECT_ITEMS;
        activeTaskId = state.taskId;
        taskRadius = state.radius;
        taskTargetCount = 0;
        taskStepsDone = state.stepsDone;
        taskIdleTicks = state.idleTicks;
        taskSearchTimeoutTicks = 0;
        taskOwnerPauseTicks = state.ownerPauseTicks;
        taskPauseAnnounced = state.pauseAnnounced;
        targetBlock = null;
        targetItemUuid = state.targetItemUuid;
        autoPickupTargetUuid = null;
    }

    private static void syncCollectItemsTaskFromLegacy() {
        CollectItemsTaskState state = collectItemsTask;
        if (state == null) {
            syncActiveTaskRuntime();
            return;
        }
        if (taskKind != TaskKind.COLLECT_ITEMS) {
            collectItemsTask = null;
            syncActiveTaskRuntime();
            return;
        }

        state.targetItemUuid = targetItemUuid;
        state.stepsDone = taskStepsDone;
        state.idleTicks = taskIdleTicks;
        state.ownerPauseTicks = taskOwnerPauseTicks;
        state.pauseAnnounced = taskPauseAnnounced;
        syncActiveTaskRuntime();
    }

    private static void pauseTaskUntilOwnerReturns(ServerPlayer owner, Mob npc) {
        taskOwnerPauseTicks += 5;
        npc.getNavigation().stop();
        clearBreakProgress(npc);
        targetBlock = null;
        targetItemUuid = null;
        autoPickupTargetUuid = null;
        breakTicks = 0;
        placeTicks = 0;
        resetNavigationProgress();

        if (!taskPauseAnnounced) {
            String message = "Task paused. I will resume " + taskName() + " after you respawn or return to my dimension.";
            if (owner != null) {
                say(owner, message);
            }
            TaskFeedback.warn(owner, npc, taskName(), "OWNER_UNAVAILABLE", message);
            taskPauseAnnounced = true;
        }

        if (taskOwnerPauseTicks >= TASK_OWNER_PAUSE_TICKS) {
            TaskFeedback.failure(owner, npc, taskName(), "OWNER_TIMEOUT", "Task timed out while waiting for the owner to return.");
            if (owner != null) {
                say(owner, "Task timed out while waiting for you to return.");
            }
            clearTask();
        }
    }

    private static void resumeTaskIfPaused(ServerPlayer owner) {
        if (taskOwnerPauseTicks <= 0) {
            return;
        }

        targetBlock = null;
        targetItemUuid = null;
        taskIdleTicks = 0;
        breakTicks = 0;
        placeTicks = 0;
        blockBreakProgress = 0.0F;
        lastBreakStage = -1;
        resetNavigationProgress();
        taskOwnerPauseTicks = 0;
        taskPauseAnnounced = false;
        say(owner, "Resuming " + taskName() + ".");
        TaskFeedback.info(owner, activeNpcMob(owner.getServer()), taskName(), "RESUMED", "Resuming task after owner returned.");
    }

    private static void handleCollectItems(ServerPlayer owner, Mob npc) {
        ServerLevel level = owner.serverLevel();
        ItemEntity target = findTargetItem(level, npc, owner);
        if (target == null) {
            taskIdleTicks += 5;
            if (taskIdleTicks >= 20) {
                say(owner, "No dropped items found nearby; collection complete.");
                TaskFeedback.info(owner, npc, taskName(), "TASK_COMPLETE", "No dropped items found nearby; collection complete.");
                clearTask();
            }
            return;
        }

        targetItemUuid = target.getUUID();
        taskIdleTicks = 0;
        if (npc.distanceToSqr(target) > 4.0) {
            npc.getNavigation().moveTo(target, McAiConfig.NPC_MOVE_SPEED.get());
            return;
        }

        ItemStack incoming = target.getItem().copy();
        int originalCount = incoming.getCount();
        if (npc instanceof AiNpcEntity aiNpc) {
            incoming = aiNpc.addToInventory(incoming);
        }
        if (!incoming.isEmpty() && npc instanceof AiNpcEntity aiNpc) {
            incoming = insertIntoNearbyContainers(aiNpc, owner, incoming);
        }
        if (!incoming.isEmpty()) {
            owner.getInventory().add(incoming);
        }
        int moved = originalCount - incoming.getCount();
        if (moved <= 0) {
            say(owner, "NPC storage, nearby containers, and your inventory are full; stopping item collection.");
            TaskFeedback.failure(owner, npc, taskName(), "INVENTORY_FULL", "NPC storage, nearby containers, and your inventory are full; stopping item collection.");
            clearTask();
            return;
        }

        if (incoming.isEmpty()) {
            target.discard();
        } else {
            target.setItem(incoming);
        }

        taskStepsDone++;
        if (npc instanceof AiNpcEntity aiNpc) {
            autoEquipBest(aiNpc);
        }
        String destination = npc instanceof AiNpcEntity ? "NPC storage or nearby containers first" : "your inventory";
        say(owner, "Collected " + moved + "x " + target.getItem().getHoverName().getString() + " into " + destination + ".");
        TaskFeedback.info(owner, npc, taskName(), "ITEM_COLLECTED", "Collected " + moved + "x " + target.getItem().getHoverName().getString() + " into " + destination + ".");
        if (taskStepsDone >= McAiConfig.NPC_MAX_TASK_STEPS.get()) {
            say(owner, "Collection task complete.");
            TaskFeedback.info(owner, npc, taskName(), "TASK_COMPLETE", "Collection task complete.");
            clearTask();
        }
    }

    private static void handleBlockTask(ServerPlayer owner, Mob npc, TaskKind kind) {
        ServerLevel level = owner.serverLevel();
        if (targetBlock == null || !isBreakTarget(level, targetBlock, kind) || findUsableTool(owner, kind, level.getBlockState(targetBlock)) == null) {
            clearBreakProgress(npc);
            targetBlock = findNearestBreakTarget(level, npc, owner, kind);
            resetBlockBreakState();
            placeTicks = 0;
        }

        if (targetBlock == null) {
            if (kind == TaskKind.HARVEST_LOGS && followOwnerWhileSearching(owner, npc)) {
                return;
            }
            finishAfterIdle(owner, npc, noBreakTargetMessage(kind), "NO_BREAK_TARGET");
            return;
        }

        BlockPos standPos = findStandPos(level, npc, targetBlock);
        if (kind == TaskKind.HARVEST_LOGS) {
            standPos = findHarvestStandPos(level, npc, targetBlock);
        }
        if (standPos == null && !canInteractWithBlock(npc, targetBlock)) {
            if (kind == TaskKind.HARVEST_LOGS && tryPlaceHarvestSupport(owner, npc, level, targetBlock)) {
                return;
            }
            reportNavigationBlocker(owner, npc, "UNREACHABLE_BREAK_TARGET",
                    "I cannot currently reach " + blockName(level.getBlockState(targetBlock)) + " at "
                            + positionText(targetBlock) + "; I will scan for another reachable target.", false);
            markBreakTargetBlocked(targetBlock);
            clearBreakProgress(npc);
            targetBlock = null;
            resetBlockBreakState();
            placeTicks = 0;
            return;
        }

        taskIdleTicks = 0;
        if (!canInteractWithBlock(npc, targetBlock)) {
            if (!moveToStandPos(npc, standPos)) {
                if (tryRecoverNavigationBlocker(owner, npc, level, standPos, targetBlock, "moving to break target")) {
                    return;
                }
                reportNavigationBlocker(owner, npc, "NAVIGATION_STUCK",
                        "Pathing got stuck near target " + positionText(targetBlock)
                                + "; selecting another reachable target.", true);
                markBreakTargetBlocked(targetBlock);
                clearBreakProgress(npc);
                targetBlock = null;
                resetBlockBreakState();
                placeTicks = 0;
            }
            return;
        }

        BlockState state = level.getBlockState(targetBlock);
        if (npc instanceof AiNpcEntity aiNpc) {
            ToolSummary.ToolKind requiredTool = requiredTool(kind);
            if (requiredTool != null) {
                autoEquipMainHand(aiNpc, requiredTool);
            }
        }
        ToolUse tool = findUsableTool(owner, kind, state);
        if (tool == null) {
            clearBreakProgress(npc);
            targetBlock = null;
            resetBlockBreakState();
            return;
        }

        npc.getNavigation().stop();
        npc.getLookControl().setLookAt(targetBlock.getX() + 0.5D, targetBlock.getY() + 0.5D, targetBlock.getZ() + 0.5D, 20.0F, 20.0F);
        if (tickCounter % 10 == 0) {
            npc.swing(InteractionHand.MAIN_HAND, true);
        }

        breakTicks += 5;
        blockBreakProgress += destroyProgressPerTick(level, targetBlock, state, tool.stack(), kind) * 5.0F;
        int progress = Math.min(9, (int) (blockBreakProgress * 10.0F));
        if (progress != lastBreakStage) {
            level.destroyBlockProgress(npc.getId(), targetBlock, progress);
            lastBreakStage = progress;
        }
        if (blockBreakProgress < 1.0F) {
            return;
        }

        BlockPos minedPos = targetBlock;
        boolean broken = breakBlockWithTool(level, owner, npc, minedPos, state, kind, tool);
        level.destroyBlockProgress(npc.getId(), minedPos, -1);
        targetBlock = null;
        resetBlockBreakState();

        if (broken) {
            taskStepsDone++;
            say(owner, "Broke " + blockName(state) + " with " + blockName(tool.stack()) + " at " + minedPos.getX() + " " + minedPos.getY() + " " + minedPos.getZ() + ".");
            TaskFeedback.info(owner, npc, taskName(), "BLOCK_BROKEN", "Broke " + blockName(state) + " with " + blockName(tool.stack()) + " at " + minedPos.getX() + " " + minedPos.getY() + " " + minedPos.getZ() + ".");
            int completionTarget = taskTargetCount > 0 ? taskTargetCount : McAiConfig.NPC_MAX_TASK_STEPS.get();
            if (taskStepsDone >= completionTarget) {
                String message = "Task complete after " + taskStepsDone + " blocks.";
                if (kind == TaskKind.GATHER_STONE) {
                    message += " I will collect nearby drops before crafting.";
                }
                say(owner, message);
                TaskFeedback.info(owner, npc, taskName(), "TASK_COMPLETE", message);
                if (kind == TaskKind.HARVEST_LOGS && continuePendingRepairAfterGather(owner, npc)) {
                    return;
                }
                if (kind == TaskKind.HARVEST_LOGS && continuePendingBuildAfterGather(owner, npc)) {
                    return;
                }
                clearTask();
            }
        }
    }

    private static String noBreakTargetMessage(TaskKind kind) {
        return switch (kind) {
            case MINE_ORES -> "No exposed ore blocks found nearby.";
            case GATHER_STONE -> "No reachable stone/cobblestone-like blocks found nearby.";
            case HARVEST_LOGS -> "No exposed logs found nearby.";
            case BUILD_BASIC_HOUSE, BUILD_LARGE_HOUSE, REPAIR_STRUCTURE, CRAFT_AT_TABLE, BREAK_BLOCK, PLACE_BLOCK, COLLECT_ITEMS, NONE -> "No reachable break target found nearby.";
        };
    }

    private static void handleBuildHouse(ServerPlayer owner, Mob npc) {
        ServerLevel level = owner.serverLevel();
        while (!buildQueue.isEmpty() && !level.getBlockState(buildQueue.get(0)).isAir()) {
            buildQueue.remove(0);
        }

        if (buildQueue.isEmpty()) {
            String message = currentBuildKind.completionMessage();
            say(owner, message);
            TaskFeedback.info(owner, npc, taskName(), "TASK_COMPLETE", message);
            if (currentBuildKind == pendingBuildKind) {
                clearPendingBuild();
            }
            clearTask();
            return;
        }

        BlockPos target = buildQueue.get(0);
        BlockPos standPos = findStandPos(level, npc, target);
        if (!canInteractWithBlock(npc, target)) {
            if (standPos == null) {
                buildQueue.remove(0);
                TaskFeedback.warn(owner, npc, taskName(), "UNREACHABLE_PLACE_TARGET",
                        "Skipped unreachable build position " + target.getX() + " " + target.getY() + " " + target.getZ() + ".");
                placeTicks = 0;
                return;
            }
            if (!moveToStandPos(npc, standPos)) {
                if (tryRecoverNavigationBlocker(owner, npc, level, standPos, target, "moving to build position")) {
                    return;
                }
                buildQueue.remove(0);
                reportNavigationBlocker(owner, npc, "NAVIGATION_STUCK",
                        "Skipped build position after getting stuck near " + positionText(target) + ".", true);
                placeTicks = 0;
            }
            return;
        }

        PlaceableBlock block = findPlaceableBlock(owner);
        if (block == null) {
            craftLogsIntoPlanksForBuild(owner, npc, buildQueue.size());
            block = findPlaceableBlock(owner);
        }
        if (block == null) {
            String message = "I ran out of placeable blocks before finishing the " + currentBuildKind.label()
                    + ". Player inventory is excluded." + chestApprovalHint(owner);
            say(owner, message);
            TaskFeedback.failure(owner, npc, taskName(), "NEED_BLOCKS", message);
            clearTask();
            return;
        }

        if (!isPlacementCollisionFree(level, target) && standPos != null && distanceToBlockSqr(npc, standPos) > 1.0D) {
            if (!moveToStandPos(npc, standPos)) {
                if (tryRecoverNavigationBlocker(owner, npc, level, standPos, target, "moving around blocked build position")) {
                    return;
                }
                buildQueue.remove(0);
                reportNavigationBlocker(owner, npc, "NAVIGATION_STUCK",
                        "Skipped blocked build position after pathing failed at " + positionText(target) + ".", true);
                placeTicks = 0;
            }
            return;
        }

        if (!canPlaceBlock(level, target, block.state())) {
            buildQueue.remove(0);
            TaskFeedback.warn(owner, npc, taskName(), "CANNOT_PLACE_BLOCK",
                    "Skipped blocked or unsupported build position " + target.getX() + " " + target.getY() + " " + target.getZ() + ".");
            placeTicks = 0;
            return;
        }

        npc.getNavigation().stop();
        npc.getLookControl().setLookAt(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, 20.0F, 20.0F);
        npc.swing(InteractionHand.MAIN_HAND, true);
        placeTicks += 5;
        if (placeTicks < McAiConfig.NPC_BLOCK_PLACE_TICKS.get()) {
            return;
        }

        level.setBlock(target, block.state(), 3);
        level.gameEvent(GameEvent.BLOCK_PLACE, target, GameEvent.Context.of(npc, block.state()));
        consumePlaceableBlock(block);
        buildQueue.remove(0);
        placeTicks = 0;
        taskStepsDone++;

        if (taskStepsDone % 10 == 0 || buildQueue.isEmpty()) {
            String message = "Building " + currentBuildKind.label() + ": placed " + taskStepsDone + ", remaining " + buildQueue.size() + ".";
            say(owner, message);
            TaskFeedback.info(owner, npc, taskName(), "BUILD_PROGRESS", message);
        }
    }

    private static void handleRepairStructure(ServerPlayer owner, Mob npc) {
        ServerLevel level = owner.serverLevel();
        while (!repairQueue.isEmpty() && repairPlacementComplete(level, repairQueue.get(0))) {
            repairQueue.remove(0);
        }

        if (repairQueue.isEmpty()) {
            String message = "Structure repair complete: placed " + taskStepsDone + " missing wall/door element(s).";
            say(owner, message);
            TaskFeedback.info(owner, npc, taskName(), "TASK_COMPLETE", message);
            clearTask();
            return;
        }

        RepairPlacement placement = repairQueue.get(0);
        targetBlock = placement.pos();
        BlockPos standPos = findStandPos(level, npc, placement.pos());
        if (!canInteractWithBlock(npc, placement.pos())) {
            if (standPos == null) {
                repairQueue.remove(0);
                TaskFeedback.warn(owner, npc, taskName(), "UNREACHABLE_REPAIR_TARGET",
                        "Skipped unreachable repair position " + positionText(placement.pos()) + ".");
                placeTicks = 0;
                return;
            }
            if (!moveToStandPos(npc, standPos)) {
                if (tryRecoverNavigationBlocker(owner, npc, level, standPos, placement.pos(), "moving to repair structure")) {
                    return;
                }
                repairQueue.remove(0);
                reportNavigationBlocker(owner, npc, "NAVIGATION_STUCK",
                        "Skipped repair position after getting stuck near " + positionText(placement.pos()) + ".", true);
                placeTicks = 0;
            }
            return;
        }

        if (placement.door()) {
            if (!canRepairDoorAt(level, placement)) {
                String message = "Cannot safely place a door at " + positionText(placement.pos())
                        + ". The opening may be blocked or unsupported.";
                say(owner, message);
                TaskFeedback.failure(owner, npc, taskName(), "CANNOT_PLACE_DOOR", message);
                clearTask();
                return;
            }
            if (!ensureRepairDoorAvailable(owner, npc)) {
                if (startPendingRepairMaterialGather(owner, npc, "door")) {
                    return;
                }
                String message = "I need an oak door or 6 plank units/logs in NPC storage or approved nearby containers before repairing the missing door."
                        + chestApprovalHint(owner);
                say(owner, message);
                TaskFeedback.failure(owner, npc, taskName(), "NEED_DOOR", message);
                clearTask();
                return;
            }
        } else {
            PlaceableBlock block = findPlaceableBlock(owner, placement.materialPreference());
            if (block == null && isPlankMaterialPreference(placement.materialPreference())) {
                craftPlanks(owner, npc, Math.max(PLANKS_PER_LOG, repairQueue.size()), isChestMaterialUseApproved(owner));
                block = findPlaceableBlock(owner, placement.materialPreference());
            }
            if (block == null) {
                if (startPendingRepairMaterialGather(owner, npc, placement.materialPreference())) {
                    return;
                }
                String message = "I need matching repair material " + placement.materialPreference()
                        + " in NPC storage or approved nearby containers before patching the wall."
                        + chestApprovalHint(owner);
                say(owner, message);
                TaskFeedback.failure(owner, npc, taskName(), "NEED_REPAIR_BLOCK", message);
                clearTask();
                return;
            }
            if (!canPlaceBlock(level, placement.pos(), block.state())) {
                repairQueue.remove(0);
                TaskFeedback.warn(owner, npc, taskName(), "CANNOT_PLACE_REPAIR_BLOCK",
                        "Skipped blocked wall repair position " + positionText(placement.pos()) + ".");
                placeTicks = 0;
                return;
            }
        }

        npc.getNavigation().stop();
        npc.getLookControl().setLookAt(placement.pos().getX() + 0.5D, placement.pos().getY() + 0.5D, placement.pos().getZ() + 0.5D, 20.0F, 20.0F);
        npc.swing(InteractionHand.MAIN_HAND, true);
        placeTicks += 5;
        if (placeTicks < McAiConfig.NPC_BLOCK_PLACE_TICKS.get()) {
            return;
        }

        if (placement.door()) {
            ItemSlot door = findDoorItem(owner);
            if (door == null) {
                placeTicks = 0;
                return;
            }
            level.setBlock(placement.pos(), placement.state(), 3);
            level.setBlock(placement.pos().above(), placement.upperState(), 3);
            level.gameEvent(GameEvent.BLOCK_PLACE, placement.pos(), GameEvent.Context.of(npc, placement.state()));
            consumeItemSlot(door, 1);
        } else {
            PlaceableBlock block = findPlaceableBlock(owner, placement.materialPreference());
            if (block == null) {
                placeTicks = 0;
                return;
            }
            level.setBlock(placement.pos(), block.state(), 3);
            level.gameEvent(GameEvent.BLOCK_PLACE, placement.pos(), GameEvent.Context.of(npc, block.state()));
            consumePlaceableBlock(block);
        }

        repairQueue.remove(0);
        placeTicks = 0;
        taskStepsDone++;
        if (taskStepsDone % 8 == 0 || repairQueue.isEmpty()) {
            String message = "Repair progress: placed " + taskStepsDone + ", remaining " + repairQueue.size() + ".";
            say(owner, message);
            TaskFeedback.info(owner, npc, taskName(), repairQueue.isEmpty() ? "TASK_COMPLETE" : "REPAIR_PROGRESS", message);
        }
    }

    private static void handleCraftAtTable(ServerPlayer owner, Mob npc) {
        ServerLevel level = owner.serverLevel();
        if (targetBlock == null || !level.getBlockState(targetBlock).is(Blocks.CRAFTING_TABLE)) {
            String message = "The crafting table target is missing or was removed.";
            say(owner, message);
            TaskFeedback.failure(owner, npc, taskName(), "CRAFTING_TABLE_MISSING", message);
            clearTask();
            return;
        }

        BlockPos standPos = findStandPos(level, npc, targetBlock);
        if (!canInteractWithBlock(npc, targetBlock)) {
            if (standPos == null) {
                String message = "Cannot reach crafting table at " + targetBlock.getX() + " " + targetBlock.getY() + " " + targetBlock.getZ() + ".";
                say(owner, message);
                TaskFeedback.failure(owner, npc, taskName(), "UNREACHABLE_CRAFTING_TABLE", message);
                clearTask();
                return;
            }
            if (!moveToStandPos(npc, standPos)) {
                if (tryRecoverNavigationBlocker(owner, npc, level, standPos, targetBlock, "moving to crafting table")) {
                    return;
                }
                String message = "I got stuck while moving to the crafting table at " + positionText(targetBlock)
                        + ". Try clearing the path, adding a step, or place the table closer.";
                reportNavigationBlocker(owner, npc, "NAVIGATION_STUCK", message, true);
                clearTask();
            }
            return;
        }

        npc.getNavigation().stop();
        resetNavigationProgress();
        npc.getLookControl().setLookAt(targetBlock.getX() + 0.5D, targetBlock.getY() + 0.5D, targetBlock.getZ() + 0.5D, 20.0F, 20.0F);
        if (tickCounter % 10 == 0) {
            npc.swing(InteractionHand.MAIN_HAND, true);
        }

        placeTicks += 5;
        if (placeTicks < McAiConfig.NPC_BLOCK_PLACE_TICKS.get()) {
            return;
        }

        boolean crafted = craftRequestedAtTable(owner, npc, craftTableItemRequest, craftTableRequestedCount, craftTableAllowContainerMaterials);
        if (crafted) {
            TaskFeedback.info(owner, npc, taskName(), "TASK_COMPLETE",
                    "Crafted " + craftTableItemRequest + " at crafting table using " + materialSourceLabel(craftTableAllowContainerMaterials) + ".");
        }
        clearTask();
    }

    private static void handlePrimitiveBreakBlock(ServerPlayer owner, Mob npc) {
        ServerLevel level = owner.serverLevel();
        if (targetBlock == null) {
            TaskFeedback.failure(owner, npc, taskName(), "NO_TARGET", "Primitive break task has no target block.");
            clearTask();
            return;
        }

        BlockState state = level.getBlockState(targetBlock);
        if (state.isAir()) {
            String message = "Block already gone at " + targetBlock.getX() + " " + targetBlock.getY() + " " + targetBlock.getZ() + ".";
            say(owner, message);
            TaskFeedback.info(owner, npc, taskName(), "TASK_COMPLETE", message);
            clearTask();
            return;
        }
        if (state.getDestroySpeed(level, targetBlock) < 0.0F || state.hasBlockEntity()) {
            String message = "Cannot primitive-break " + blockName(state) + " at " + targetBlock.getX() + " " + targetBlock.getY() + " " + targetBlock.getZ() + ".";
            say(owner, message);
            TaskFeedback.failure(owner, npc, taskName(), "UNBREAKABLE_OR_BLOCK_ENTITY", message);
            clearTask();
            return;
        }

        BlockPos standPos = findStandPos(level, npc, targetBlock);
        if (!canInteractWithBlock(npc, targetBlock)) {
            if (standPos == null) {
                String message = "Cannot reach block at " + targetBlock.getX() + " " + targetBlock.getY() + " " + targetBlock.getZ() + ".";
                say(owner, message);
                TaskFeedback.failure(owner, npc, taskName(), "UNREACHABLE_BREAK_TARGET", message);
                clearTask();
                return;
            }
            if (!moveToStandPos(npc, standPos)) {
                if (tryRecoverNavigationBlocker(owner, npc, level, standPos, targetBlock, "moving to break block")) {
                    return;
                }
                String message = "I got stuck while moving to break " + blockName(state) + " at " + positionText(targetBlock) + ".";
                reportNavigationBlocker(owner, npc, "NAVIGATION_STUCK", message, true);
                clearTask();
            }
            return;
        }

        ToolSummary.ToolKind preferredTool = preferredToolForState(state);
        if (npc instanceof AiNpcEntity aiNpc && preferredTool != null) {
            autoEquipMainHand(aiNpc, preferredTool);
        }

        ToolUse tool = findGenericBreakTool(owner, state);
        if (tool == null) {
            String message = "No valid tool is available for " + blockName(state) + ".";
            say(owner, message);
            TaskFeedback.failure(owner, npc, taskName(), "NEED_TOOL", message);
            clearTask();
            return;
        }

        npc.getNavigation().stop();
        npc.getLookControl().setLookAt(targetBlock.getX() + 0.5D, targetBlock.getY() + 0.5D, targetBlock.getZ() + 0.5D, 20.0F, 20.0F);
        if (tickCounter % 10 == 0) {
            npc.swing(InteractionHand.MAIN_HAND, true);
        }

        breakTicks += 5;
        blockBreakProgress += destroyProgressPerTickGeneric(level, targetBlock, state, tool.stack()) * 5.0F;
        int progress = Math.min(9, (int) (blockBreakProgress * 10.0F));
        if (progress != lastBreakStage) {
            level.destroyBlockProgress(npc.getId(), targetBlock, progress);
            lastBreakStage = progress;
        }
        if (blockBreakProgress < 1.0F) {
            return;
        }

        BlockPos brokenPos = targetBlock;
        boolean broken = breakBlockWithGenericTool(level, owner, npc, brokenPos, state, tool);
        level.destroyBlockProgress(npc.getId(), brokenPos, -1);
        targetBlock = null;
        resetBlockBreakState();

        if (broken) {
            String message = "Broke " + blockName(state) + " with " + toolLabel(tool)
                    + " at " + brokenPos.getX() + " " + brokenPos.getY() + " " + brokenPos.getZ() + ".";
            say(owner, message);
            TaskFeedback.info(owner, npc, taskName(), "TASK_COMPLETE", message);
        } else {
            TaskFeedback.failure(owner, npc, taskName(), "BREAK_FAILED", "Block break failed after progress completed.");
        }
        clearTask();
    }

    private static void handlePrimitivePlaceBlock(ServerPlayer owner, Mob npc) {
        ServerLevel level = owner.serverLevel();
        if (targetBlock == null) {
            TaskFeedback.failure(owner, npc, taskName(), "NO_TARGET", "Primitive place task has no target block.");
            clearTask();
            return;
        }
        if (!level.getBlockState(targetBlock).isAir()) {
            String message = "Cannot place at " + targetBlock.getX() + " " + targetBlock.getY() + " " + targetBlock.getZ() + " because it is no longer air.";
            say(owner, message);
            TaskFeedback.failure(owner, npc, taskName(), "PLACE_TARGET_OCCUPIED", message);
            clearTask();
            return;
        }

        BlockPos standPos = findStandPos(level, npc, targetBlock);
        if (!canInteractWithBlock(npc, targetBlock)) {
            if (standPos == null) {
                String message = "Cannot reach placement target " + targetBlock.getX() + " " + targetBlock.getY() + " " + targetBlock.getZ() + ".";
                say(owner, message);
                TaskFeedback.failure(owner, npc, taskName(), "UNREACHABLE_PLACE_TARGET", message);
                clearTask();
                return;
            }
            if (!moveToStandPos(npc, standPos)) {
                if (tryRecoverNavigationBlocker(owner, npc, level, standPos, targetBlock, "moving to place block")) {
                    return;
                }
                String message = "I got stuck while moving to place a block at " + positionText(targetBlock) + ".";
                reportNavigationBlocker(owner, npc, "NAVIGATION_STUCK", message, true);
                clearTask();
            }
            return;
        }

        PlaceableBlock block = findPlaceableBlock(owner, primitivePlaceBlockPreference);
        if (block == null) {
            String message = "I ran out of matching placeable blocks for primitive place_block."
                    + " Player inventory is excluded." + chestApprovalHint(owner);
            say(owner, message);
            TaskFeedback.failure(owner, npc, taskName(), "NEED_BLOCK", message);
            clearTask();
            return;
        }
        if (!canPlaceBlock(level, targetBlock, block.state())) {
            String message = "Cannot safely place " + blockName(block.stack()) + " at "
                    + targetBlock.getX() + " " + targetBlock.getY() + " " + targetBlock.getZ() + ".";
            say(owner, message);
            TaskFeedback.failure(owner, npc, taskName(), "CANNOT_PLACE_BLOCK", message);
            clearTask();
            return;
        }

        npc.getNavigation().stop();
        npc.getLookControl().setLookAt(targetBlock.getX() + 0.5D, targetBlock.getY() + 0.5D, targetBlock.getZ() + 0.5D, 20.0F, 20.0F);
        npc.swing(InteractionHand.MAIN_HAND, true);
        placeTicks += 5;
        if (placeTicks < McAiConfig.NPC_BLOCK_PLACE_TICKS.get()) {
            return;
        }

        BlockPos placedPos = targetBlock;
        String placedName = blockName(block.stack());
        level.setBlock(placedPos, block.state(), 3);
        level.gameEvent(GameEvent.BLOCK_PLACE, placedPos, GameEvent.Context.of(npc, block.state()));
        consumePlaceableBlock(block);
        String message = "Placed " + placedName + " at " + placedPos.getX() + " " + placedPos.getY() + " " + placedPos.getZ() + ".";
        say(owner, message);
        TaskFeedback.info(owner, npc, taskName(), "TASK_COMPLETE", message);
        clearTask();
    }

    private static boolean tryPlaceHarvestSupport(ServerPlayer owner, Mob npc, ServerLevel level, BlockPos logTarget) {
        HarvestSupportPlan support = findHarvestSupportPlan(level, npc, logTarget);
        if (support == null) {
            return false;
        }

        PlaceableBlock block = findScaffoldBlock(owner, level, support.supportPos());
        if (block == null) {
            reportNavigationBlocker(owner, npc, "NEED_SCAFFOLD_BLOCK",
                    "High log at " + positionText(logTarget)
                            + " needs a solid block for scaffolding, but none is available in NPC storage or approved nearby containers."
                            + chestApprovalHint(owner), false);
            markBreakTargetBlocked(logTarget);
            return false;
        }

        if (!canInteractWithBlock(npc, support.supportPos())) {
            if (support.placementStand() == null || !moveToStandPos(npc, support.placementStand())) {
                if (tryRecoverNavigationBlocker(owner, npc, level, support.placementStand(), support.supportPos(), "moving to place scaffold")) {
                    return true;
                }
                reportNavigationBlocker(owner, npc, "SCAFFOLD_PATH_BLOCKED",
                        "Could not path to scaffold placement for high log at " + positionText(logTarget) + ".", true);
                markBreakTargetBlocked(logTarget);
                return false;
            }
            return true;
        }

        npc.getNavigation().stop();
        npc.getLookControl().setLookAt(support.supportPos().getX() + 0.5D, support.supportPos().getY() + 0.5D,
                support.supportPos().getZ() + 0.5D, 20.0F, 20.0F);
        npc.swing(InteractionHand.MAIN_HAND, true);
        placeTicks += 5;
        if (placeTicks < McAiConfig.NPC_BLOCK_PLACE_TICKS.get()) {
            return true;
        }

        level.setBlock(support.supportPos(), block.state(), 3);
        level.gameEvent(GameEvent.BLOCK_PLACE, support.supportPos(), GameEvent.Context.of(npc, block.state()));
        consumePlaceableBlock(block);
        placeTicks = 0;
        resetNavigationProgress();
        TaskFeedback.info(owner, npc, taskName(), "HARVEST_SCAFFOLD_PLACED",
                "Placed scaffold block at " + positionText(support.supportPos()) + " to reach a high log.");
        return true;
    }

    private static void recordTaskToolReadiness(ServerPlayer player, Mob npc, TaskKind kind) {
        switch (kind) {
            case MINE_ORES -> ToolSummary.recordAvailabilityFeedback(
                    player,
                    npc,
                    taskName(),
                    ToolSummary.ToolKind.PICKAXE,
                    "PICKAXE_AVAILABLE",
                    "NEED_PICKAXE",
                    "Mining will not start without a usable pickaxe."
            );
            case GATHER_STONE -> ToolSummary.recordAvailabilityFeedback(
                    player,
                    npc,
                    taskName(),
                    ToolSummary.ToolKind.PICKAXE,
                    "PICKAXE_AVAILABLE",
                    "NEED_PICKAXE",
                    "Stone gathering will not start without a usable pickaxe."
            );
            case HARVEST_LOGS -> ToolSummary.recordAvailabilityFeedback(
                    player,
                    npc,
                    taskName(),
                    ToolSummary.ToolKind.AXE,
                    "AXE_AVAILABLE",
                    "NEED_AXE",
                    "Log harvesting will not start without a usable axe."
            );
            case BUILD_BASIC_HOUSE, BUILD_LARGE_HOUSE, CRAFT_AT_TABLE, BREAK_BLOCK, PLACE_BLOCK, COLLECT_ITEMS, NONE -> {
            }
        }
    }

    private static boolean followOwnerWhileSearching(ServerPlayer owner, Mob npc) {
        taskIdleTicks += 5;
        if (taskIdleTicks == 5) {
            String message = "No logs in immediate range yet. I will use remembered tree locations or scout within a bounded area for up to " + Math.max(1, taskSearchTimeoutTicks / 20) + " seconds.";
            say(owner, message);
            TaskFeedback.warn(owner, npc, taskName(), "SEARCHING_WITH_OWNER", message);
        }

        if (tryTravelToKnownLogs(owner, npc)) {
            return true;
        }

        if (taskSearchTimeoutTicks > 0 && taskIdleTicks >= taskSearchTimeoutTicks) {
            say(owner, "No exposed logs found while following you.");
            TaskFeedback.warn(owner, npc, taskName(), "SEARCH_TIMEOUT", "No exposed logs found while following you.");
            clearTask();
            return true;
        }

        if (scoutAroundOwnerForLogs(owner, npc)) {
            return true;
        }

        double followDistance = Math.max(3.0D, McAiConfig.NPC_FOLLOW_DISTANCE.get());
        if (npc.distanceToSqr(owner) > (followDistance + 1.0D) * (followDistance + 1.0D)) {
            if (tickCounter % 20 == 0 || npc.getNavigation().isDone()) {
                resetNavigationProgress();
                navigationTarget = owner.blockPosition();
                npc.getNavigation().moveTo(owner, McAiConfig.NPC_MOVE_SPEED.get());
            }
        } else {
            npc.getNavigation().stop();
            resetNavigationProgress();
        }
        return true;
    }

    private static boolean tryTravelToKnownLogs(ServerPlayer owner, Mob npc) {
        if (taskKnownResourceTarget == null) {
            WorldKnowledge.KnownPosition hint = WorldKnowledge.nearestResourceHint(
                    owner,
                    "logs",
                    KNOWN_RESOURCE_TRAVEL_MAX_DISTANCE,
                    KNOWN_RESOURCE_MAX_AGE_TICKS
            );
            if (hint != null && !isTemporarilyBlockedKnownResourceTarget(hint.blockPos())
                    && npc.distanceToSqr(hint.blockPos().getX() + 0.5D, hint.blockPos().getY() + 0.5D, hint.blockPos().getZ() + 0.5D) > 64.0D) {
                taskKnownResourceTarget = hint.blockPos().immutable();
                taskKnownResourceTravelTicks = 0;
                taskScoutTarget = null;
                String message = "I remember " + hint.block() + " near "
                        + positionText(taskKnownResourceTarget)
                        + " (" + hint.distance() + " blocks from you). I will go there and scan from that area.";
                say(owner, message);
                TaskFeedback.info(owner, npc, taskName(), "TRAVELING_TO_KNOWN_LOGS", message);
            }
        }

        if (taskKnownResourceTarget == null) {
            return false;
        }

        double distanceSqr = npc.distanceToSqr(
                taskKnownResourceTarget.getX() + 0.5D,
                taskKnownResourceTarget.getY() + 0.5D,
                taskKnownResourceTarget.getZ() + 0.5D
        );
        if (distanceSqr <= Math.max(36.0D, taskRadius * taskRadius * 0.25D)) {
            TaskFeedback.warn(owner, npc, taskName(), "KNOWN_RESOURCE_AREA_EXHAUSTED",
                    "Reached remembered log area near " + positionText(taskKnownResourceTarget)
                            + ", but no reachable logs were selected; I will ignore that hint briefly and scout locally.");
            markKnownResourceTargetBlocked(taskKnownResourceTarget);
            taskKnownResourceTarget = null;
            taskKnownResourceTravelTicks = 0;
            resetNavigationProgress();
            return false;
        }

        taskKnownResourceTravelTicks += 5;
        if (taskKnownResourceTravelTicks > KNOWN_RESOURCE_TRAVEL_TIMEOUT_TICKS) {
            String message = "I could not reach the remembered tree location at "
                    + positionText(taskKnownResourceTarget)
                    + "; I will fall back to local scouting.";
            TaskFeedback.warn(owner, npc, taskName(), "KNOWN_RESOURCE_UNREACHABLE", message);
            markKnownResourceTargetBlocked(taskKnownResourceTarget);
            taskKnownResourceTarget = null;
            taskKnownResourceTravelTicks = 0;
            resetNavigationProgress();
            return false;
        }

        if (!taskKnownResourceTarget.equals(navigationTarget)) {
            resetNavigationProgress();
            navigationTarget = taskKnownResourceTarget;
        }
        if (distanceSqr + NAVIGATION_STUCK_MIN_PROGRESS_SQR < lastNavigationDistanceSqr) {
            navigationStuckTicks = 0;
            lastNavigationDistanceSqr = distanceSqr;
        } else if (distanceSqr > NAVIGATION_CLOSE_ENOUGH_SQR) {
            navigationStuckTicks += 5;
        }
        if (navigationStuckTicks >= NAVIGATION_STUCK_RETRY_TICKS && distanceSqr > NAVIGATION_CLOSE_ENOUGH_SQR) {
            String message = "Pathing to remembered tree location " + positionText(taskKnownResourceTarget)
                    + " is not making progress; I will ignore that hint briefly and scout locally.";
            TaskFeedback.warn(owner, npc, taskName(), "KNOWN_RESOURCE_PATH_STUCK", message);
            markKnownResourceTargetBlocked(taskKnownResourceTarget);
            taskKnownResourceTarget = null;
            taskKnownResourceTravelTicks = 0;
            resetNavigationProgress();
            return false;
        }

        if (tickCounter % 20 == 0 || npc.getNavigation().isDone()) {
            boolean started = npc.getNavigation().moveTo(
                    taskKnownResourceTarget.getX() + 0.5D,
                    taskKnownResourceTarget.getY(),
                    taskKnownResourceTarget.getZ() + 0.5D,
                    McAiConfig.NPC_MOVE_SPEED.get()
            );
            if (!started && distanceSqr > NAVIGATION_CLOSE_ENOUGH_SQR) {
                String message = "Navigation could not start toward remembered tree location "
                        + positionText(taskKnownResourceTarget) + "; I will scout locally instead.";
                TaskFeedback.warn(owner, npc, taskName(), "KNOWN_RESOURCE_NO_PATH", message);
                markKnownResourceTargetBlocked(taskKnownResourceTarget);
                taskKnownResourceTarget = null;
                taskKnownResourceTravelTicks = 0;
                resetNavigationProgress();
                return false;
            }
        }
        return true;
    }

    private static boolean scoutAroundOwnerForLogs(ServerPlayer owner, Mob npc) {
        int scoutRadius = Math.min(HARVEST_SCOUT_RADIUS, Math.max(12, taskRadius + 12));
        taskScoutLegTicks += 5;
        if (taskScoutTarget == null || taskScoutLegTicks >= HARVEST_SCOUT_LEG_TICKS || npc.distanceToSqr(
                taskScoutTarget.getX() + 0.5D,
                taskScoutTarget.getY(),
                taskScoutTarget.getZ() + 0.5D
        ) < 9.0D) {
            taskScoutLegTicks = 0;
            taskScoutLegIndex++;
            double angle = (Math.PI / 4.0D) * (taskScoutLegIndex % 8);
            int x = owner.blockPosition().getX() + (int) Math.round(Math.cos(angle) * scoutRadius);
            int z = owner.blockPosition().getZ() + (int) Math.round(Math.sin(angle) * scoutRadius);
            taskScoutTarget = new BlockPos(x, owner.blockPosition().getY(), z);
            if (taskScoutLegIndex == 1) {
                TaskFeedback.info(owner, npc, taskName(), "SCOUTING_FOR_LOGS",
                        "No remembered reachable logs found; scouting a bounded " + scoutRadius + "-block ring around the player.");
            }
        }

        if (tickCounter % 20 == 0 || npc.getNavigation().isDone()) {
            navigationTarget = taskScoutTarget;
            npc.getNavigation().moveTo(
                    taskScoutTarget.getX() + 0.5D,
                    taskScoutTarget.getY(),
                    taskScoutTarget.getZ() + 0.5D,
                    McAiConfig.NPC_MOVE_SPEED.get()
            );
        }
        return true;
    }

    private static List<BlockPos> createBasicHouseBlueprint(BlockPos center, Direction forward) {
        return createHouseBlueprint(BuildKind.BASIC, center, forward);
    }

    private static List<BlockPos> createHouseBlueprint(BuildKind buildKind, BlockPos center, Direction forward) {
        return switch (buildKind) {
            case LARGE -> createLargeHouseBlueprint(center, forward);
            case BASIC, NONE -> createBasicHouseBlueprintInternal(center, forward);
        };
    }

    private static List<BlockPos> createBasicHouseBlueprintInternal(BlockPos center, Direction forward) {
        Direction buildForward = horizontalDirection(forward);
        Direction right = buildForward.getClockWise();
        List<BlockPos> positions = new ArrayList<>();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                positions.add(center.relative(right, dx).relative(buildForward, dz));
            }
        }

        for (int y = 1; y <= 3; y++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean perimeter = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    boolean doorway = dz == -2 && dx == 0 && y <= 2;
                    boolean sideWindow = y == 2 && dz == 0 && Math.abs(dx) == 2;
                    if (perimeter && !doorway && !sideWindow) {
                        positions.add(center.relative(right, dx).relative(buildForward, dz).above(y));
                    }
                }
            }
        }

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                positions.add(center.relative(right, dx).relative(buildForward, dz).above(4));
            }
        }

        return positions;
    }

    private static List<BlockPos> createLargeHouseBlueprint(BlockPos center, Direction forward) {
        Direction buildForward = horizontalDirection(forward);
        Direction right = buildForward.getClockWise();
        List<BlockPos> positions = new ArrayList<>();

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                positions.add(center.relative(right, dx).relative(buildForward, dz));
            }
        }

        for (int y = 1; y <= 4; y++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    boolean perimeter = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                    boolean doorway = dz == -3 && dx == 0 && y <= 2;
                    boolean frontWindow = dz == -3 && y == 2 && Math.abs(dx) == 2;
                    boolean backWindow = dz == 3 && y == 2 && Math.abs(dx) == 2;
                    boolean sideWindow = Math.abs(dx) == 3 && y == 2 && Math.abs(dz) == 1;
                    if (perimeter && !doorway && !frontWindow && !backWindow && !sideWindow) {
                        positions.add(center.relative(right, dx).relative(buildForward, dz).above(y));
                    }
                }
            }
        }

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                positions.add(center.relative(right, dx).relative(buildForward, dz).above(5));
            }
        }

        return positions;
    }

    private static List<BlockPos> remainingBuildPositions(ServerLevel level, List<BlockPos> blueprint) {
        List<BlockPos> remaining = new ArrayList<>();
        for (BlockPos pos : blueprint) {
            if (level.getBlockState(pos).isAir()) {
                remaining.add(pos);
            }
        }
        return remaining;
    }

    static int countMissingBasicHouseBlocks(ServerPlayer player, BlockPos center, Direction forward) {
        Direction buildForward = horizontalDirection(forward == null ? player.getDirection() : forward);
        BlockPos buildCenter = center == null ? player.blockPosition().relative(buildForward, 5) : center.immutable();
        return remainingBuildPositions(player.serverLevel(), createBasicHouseBlueprint(buildCenter, buildForward)).size();
    }

    static int countMissingLargeHouseBlocks(ServerPlayer player, BlockPos center, Direction forward) {
        Direction buildForward = horizontalDirection(forward == null ? player.getDirection() : forward);
        BlockPos buildCenter = center == null ? player.blockPosition().relative(buildForward, 8) : center.immutable();
        return remainingBuildPositions(player.serverLevel(), createHouseBlueprint(BuildKind.LARGE, buildCenter, buildForward)).size();
    }

    private static Direction horizontalDirection(Direction direction) {
        if (direction == Direction.NORTH || direction == Direction.SOUTH || direction == Direction.EAST || direction == Direction.WEST) {
            return direction;
        }
        return Direction.NORTH;
    }

    private static int countPlaceableBlocks(ServerPlayer player) {
        int count = 0;
        AiNpcEntity npc = activeAiNpc(player.getServer());
        if (npc != null) {
            count += countPlaceableBlocksInContainer(npc.inventory());
        }
        for (Container container : findApprovedMaterialContainers(player)) {
            count += countPlaceableBlocksInContainer(container);
        }
        return count;
    }

    private static int countPlaceableBlocks(ServerPlayer player, String blockPreference) {
        int count = 0;
        AiNpcEntity npc = activeAiNpc(player.getServer());
        if (npc != null) {
            count += countPlaceableBlocksInContainer(npc.inventory(), blockPreference);
        }
        for (Container container : findApprovedMaterialContainers(player)) {
            count += countPlaceableBlocksInContainer(container, blockPreference);
        }
        return count;
    }

    private static boolean continuePendingBuildAfterGather(ServerPlayer owner, Mob npc) {
        if (pendingBuildKind == BuildKind.NONE) {
            return false;
        }

        BlockPos center = pendingBuildCenter == null ? owner.blockPosition().relative(pendingBuildForward, 8) : pendingBuildCenter;
        Direction forward = horizontalDirection(pendingBuildForward);
        int missingBlocks = remainingBuildPositions(owner.serverLevel(), createHouseBlueprint(pendingBuildKind, center, forward)).size();
        craftLogsIntoPlanksForBuild(owner, npc, missingBlocks);
        int availableBlocks = countPlaceableBlocks(owner);
        if (missingBlocks <= 0) {
            clearPendingBuild();
            return false;
        }
        if (availableBlocks >= missingBlocks) {
            BuildKind buildKind = pendingBuildKind;
            clearPendingBuild();
            String message = "Wood gathered. Starting " + buildKind.label() + " with " + availableBlocks + " placeable blocks available.";
            say(owner, message);
            TaskFeedback.info(owner, npc, buildKind.taskName(), "BUILD_MATERIALS_READY", message);
            buildHouse(owner, buildKind, center, forward, false);
            return true;
        }
        if (pendingBuildGatherAttempts >= MAX_PENDING_BUILD_GATHER_ATTEMPTS) {
            String message = "I still need " + missingBlocks + " placeable blocks for the " + pendingBuildKind.label()
                    + ", but only have " + availableBlocks + " after " + pendingBuildGatherAttempts
                    + " wood-gathering rounds. Player inventory is excluded." + chestApprovalHint(owner);
            say(owner, message);
            TaskFeedback.warn(owner, npc, pendingBuildKind.taskName(), "NEED_BLOCKS_AFTER_GATHERING", message);
            clearPendingBuild();
            return false;
        }

        pendingBuildGatherAttempts++;
        String message = "Still need " + missingBlocks + " placeable blocks for the " + pendingBuildKind.label()
                + ", currently have " + availableBlocks + ". Gathering more wood, round "
                + pendingBuildGatherAttempts + "/" + MAX_PENDING_BUILD_GATHER_ATTEMPTS + ".";
        say(owner, message);
        TaskFeedback.warn(owner, npc, pendingBuildKind.taskName(), "CONTINUE_GATHERING_WOOD", message);
        startTask(owner, TaskKind.HARVEST_LOGS, McAiConfig.NPC_TASK_RADIUS.get(), PENDING_BUILD_HARVEST_SECONDS * 20,
                "Gathering more logs for " + pendingBuildKind.label() + ".");
        return true;
    }

    private static boolean startPendingRepairMaterialGather(ServerPlayer owner, Mob npc, String missingMaterial) {
        if (repairQueue.isEmpty()) {
            return false;
        }
        if (pendingRepairGatherAttempts >= MAX_PENDING_REPAIR_GATHER_ATTEMPTS) {
            return false;
        }

        pendingRepairQueue = new ArrayList<>(repairQueue);
        pendingRepairPlan = activeRepairPlan;
        pendingRepairRadius = taskRadius <= 0 ? STRUCTURE_REPAIR_RADIUS : taskRadius;
        pendingRepairGatherAttempts++;

        String message = "Repair needs " + missingMaterial + ", but allowed NPC materials are short. I will gather wood first, then craft planks/doors and resume repair. Round "
                + pendingRepairGatherAttempts + "/" + MAX_PENDING_REPAIR_GATHER_ATTEMPTS + ".";
        say(owner, message);
        TaskFeedback.warn(owner, npc, "repair_structure", "REPAIR_NEEDS_MATERIAL_GATHERING", message);
        startTask(owner, TaskKind.HARVEST_LOGS, McAiConfig.NPC_TASK_RADIUS.get(), PENDING_REPAIR_HARVEST_SECONDS * 20,
                "Gathering wood for structure repair.");
        if (taskKind == TaskKind.HARVEST_LOGS) {
            return true;
        }

        String failed = "I could not start wood gathering for structure repair. I may need an axe, reachable logs, or approved chest materials.";
        say(owner, failed);
        TaskFeedback.warn(owner, npc, "repair_structure", "REPAIR_GATHERING_NOT_STARTED", failed);
        clearPendingRepair();
        return false;
    }

    private static boolean continuePendingRepairAfterGather(ServerPlayer owner, Mob npc) {
        if (!hasPendingRepair()) {
            return false;
        }

        List<RepairPlacement> remaining = pendingRepairQueue.stream()
                .filter(placement -> !repairPlacementComplete(owner.serverLevel(), placement))
                .toList();
        if (remaining.isEmpty()) {
            String message = "Structure repair no longer needs the saved placements after gathering.";
            say(owner, message);
            TaskFeedback.info(owner, npc, "repair_structure", "TASK_COMPLETE", message);
            clearPendingRepair();
            return false;
        }

        prepareMaterialsForPendingRepair(owner, npc, remaining);
        if (!pendingRepairMaterialsReady(owner, remaining)) {
            if (pendingRepairGatherAttempts >= MAX_PENDING_REPAIR_GATHER_ATTEMPTS) {
                String message = "I still do not have enough planks/doors to finish structure repair after "
                        + pendingRepairGatherAttempts + " wood-gathering round(s)."
                        + chestApprovalHint(owner);
                say(owner, message);
                TaskFeedback.warn(owner, npc, "repair_structure", "REPAIR_MATERIALS_STILL_MISSING", message);
                clearPendingRepair();
                return false;
            }

            pendingRepairQueue = new ArrayList<>(remaining);
            pendingRepairGatherAttempts++;
            String message = "I still need more repair material. Gathering more wood, round "
                    + pendingRepairGatherAttempts + "/" + MAX_PENDING_REPAIR_GATHER_ATTEMPTS + ".";
            say(owner, message);
            TaskFeedback.warn(owner, npc, "repair_structure", "CONTINUE_REPAIR_GATHERING_WOOD", message);
            startTask(owner, TaskKind.HARVEST_LOGS, McAiConfig.NPC_TASK_RADIUS.get(), PENDING_REPAIR_HARVEST_SECONDS * 20,
                    "Gathering more wood for structure repair.");
            return true;
        }

        repairQueue = new ArrayList<>(remaining);
        activeRepairPlan = pendingRepairPlan;
        followTargetUuid = null;
        taskOwnerUuid = owner.getUUID();
        taskKind = TaskKind.REPAIR_STRUCTURE;
        activeTaskId = newTaskId(taskKind);
        taskRadius = pendingRepairRadius <= 0 ? STRUCTURE_REPAIR_RADIUS : pendingRepairRadius;
        taskStepsDone = 0;
        taskIdleTicks = 0;
        taskSearchTimeoutTicks = 0;
        taskOwnerPauseTicks = 0;
        taskPauseAnnounced = false;
        targetBlock = null;
        targetItemUuid = null;
        autoPickupTargetUuid = null;
        breakTicks = 0;
        placeTicks = 0;
        blockBreakProgress = 0.0F;
        lastBreakStage = -1;
        navigationTarget = null;
        resetTaskSearchMemory();
        temporarilyBlockedStandPos = null;
        temporarilyBlockedStandPosTicks = 0;
        resetMobilityRecovery();
        npc.getNavigation().stop();
        String message = "Repair materials are ready. Resuming structure repair with " + repairQueue.size() + " saved placement(s).";
        say(owner, message);
        TaskFeedback.info(owner, npc, taskName(), "REPAIR_MATERIALS_READY", message);
        clearPendingRepair();
        syncActiveTaskRuntime();
        return true;
    }

    private static void prepareMaterialsForPendingRepair(ServerPlayer owner, Mob npc, List<RepairPlacement> placements) {
        int wallPlacements = 0;
        boolean needsDoor = false;
        for (RepairPlacement placement : placements) {
            if (placement.door()) {
                needsDoor = true;
            } else {
                wallPlacements++;
            }
        }

        if (wallPlacements > 0) {
            craftLogsIntoPlanksForBuild(owner, npc, wallPlacements);
        }
        if (needsDoor && findDoorItem(owner) == null) {
            craftDoors(owner, npc, 1, isChestMaterialUseApproved(owner));
        }
    }

    private static boolean pendingRepairMaterialsReady(ServerPlayer owner, List<RepairPlacement> placements) {
        boolean needsDoor = false;
        Map<String, Integer> wallNeeds = new LinkedHashMap<>();
        for (RepairPlacement placement : placements) {
            if (placement.door()) {
                needsDoor = true;
            } else {
                wallNeeds.merge(placement.materialPreference(), 1, Integer::sum);
            }
        }
        if (needsDoor && findDoorItem(owner) == null) {
            return false;
        }
        for (Map.Entry<String, Integer> entry : wallNeeds.entrySet()) {
            if (countPlaceableBlocks(owner, entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasPendingRepair() {
        return !pendingRepairQueue.isEmpty();
    }

    private static void clearPendingRepair() {
        pendingRepairQueue = List.of();
        pendingRepairPlan = null;
        pendingRepairRadius = 0;
        pendingRepairGatherAttempts = 0;
    }

    private static void clearPendingBuild() {
        pendingBuildKind = BuildKind.NONE;
        pendingBuildCenter = null;
        pendingBuildForward = Direction.NORTH;
        pendingBuildGatherAttempts = 0;
    }

    private static void craftLogsIntoPlanksForBuild(ServerPlayer player, Mob npc, int targetPlaceableBlocks) {
        int available = countPlaceableBlocks(player);
        if (available >= targetPlaceableBlocks) {
            return;
        }

        int missing = targetPlaceableBlocks - available;
        int logsToConvert = divideCeil(missing, PLANKS_PER_LOG - 1);
        int consumedLogs = consumeMatchingItems(player, NpcManager::isCraftingLog, logsToConvert, isChestMaterialUseApproved(player));
        if (consumedLogs <= 0) {
            return;
        }

        giveOrStoreOrDrop(player, npc, new ItemStack(Items.OAK_PLANKS, consumedLogs * PLANKS_PER_LOG));
        String message = "Crafted " + (consumedLogs * PLANKS_PER_LOG) + " oak planks from " + consumedLogs + " logs for building.";
        say(player, message);
        TaskFeedback.info(player, npc, taskName(), "BUILD_PLANKS_CRAFTED", message);
    }

    public static boolean craftPlanksForBuild(ServerPlayer player, int targetPlaceableBlocks) {
        Mob npc = requireNpc(player);
        if (npc == null) {
            return false;
        }
        craftLogsIntoPlanksForBuild(player, npc, targetPlaceableBlocks);
        return countPlaceableBlocks(player) >= targetPlaceableBlocks;
    }

    private static PlaceableBlock findPlaceableBlock(ServerPlayer player) {
        return findPlaceableBlock(player, "");
    }

    private static PlaceableBlock findPlaceableBlock(ServerPlayer player, String blockPreference) {
        AiNpcEntity npc = activeAiNpc(player.getServer());
        if (npc != null) {
            PlaceableBlock fromNpcStorage = findPlaceableBlockInContainer(npc.inventory(), blockPreference);
            if (fromNpcStorage != null) {
                return fromNpcStorage;
            }
        }

        for (Container container : findApprovedMaterialContainers(player)) {
            PlaceableBlock fromContainer = findPlaceableBlockInContainer(container, blockPreference);
            if (fromContainer != null) {
                return fromContainer;
            }
        }
        return null;
    }

    private static PlaceableBlock findScaffoldBlock(ServerPlayer player, ServerLevel level, BlockPos target) {
        AiNpcEntity npc = activeAiNpc(player.getServer());
        if (npc != null) {
            PlaceableBlock fromNpcStorage = findScaffoldBlockInContainer(npc.inventory(), level, target);
            if (fromNpcStorage != null) {
                return fromNpcStorage;
            }
        }

        for (Container container : findApprovedMaterialContainers(player)) {
            PlaceableBlock fromContainer = findScaffoldBlockInContainer(container, level, target);
            if (fromContainer != null) {
                return fromContainer;
            }
        }
        return null;
    }

    private static int countPlaceableBlocksInContainer(Container container) {
        return countPlaceableBlocksInContainer(container, "");
    }

    private static int countPlaceableBlocksInContainer(Container container, String blockPreference) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (isUsableBuildBlock(stack)
                    && stack.getItem() instanceof BlockItem blockItem
                    && matchesBlockRequest(stack, blockItem, blockPreference)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static PlaceableBlock findPlaceableBlockInContainer(Container container) {
        return findPlaceableBlockInContainer(container, "");
    }

    private static PlaceableBlock findPlaceableBlockInContainer(Container container, String blockPreference) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (isUsableBuildBlock(stack)
                    && stack.getItem() instanceof BlockItem blockItem
                    && matchesBlockRequest(stack, blockItem, blockPreference)) {
                return new PlaceableBlock(blockItem.getBlock().defaultBlockState(), container, slot, stack);
            }
        }
        return null;
    }

    private static PlaceableBlock findScaffoldBlockInContainer(Container container, ServerLevel level, BlockPos target) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }

            BlockState state = blockItem.getBlock().defaultBlockState();
            if (canPlaceBlock(level, target, state) && isSolidSupport(level, target, state)) {
                return new PlaceableBlock(state, container, slot, stack);
            }
        }
        return null;
    }

    private static void consumePlaceableBlock(PlaceableBlock block) {
        block.stack().shrink(1);
        block.container().setChanged();
    }

    private static boolean repairPlacementComplete(ServerLevel level, RepairPlacement placement) {
        if (placement.door()) {
            return isDoorBlock(level.getBlockState(placement.pos()))
                    && isDoorBlock(level.getBlockState(placement.pos().above()));
        }
        return level.getBlockState(placement.pos()).is(placement.state().getBlock());
    }

    private static boolean canRepairDoorAt(ServerLevel level, RepairPlacement placement) {
        BlockState lower = level.getBlockState(placement.pos());
        BlockState upper = level.getBlockState(placement.pos().above());
        return (lower.isAir() || isDoorBlock(lower))
                && (upper.isAir() || isDoorBlock(upper))
                && isSolidSupport(level, placement.pos().below())
                && placement.state().canSurvive(level, placement.pos())
                && isPlacementCollisionFree(level, placement.pos())
                && isPlacementCollisionFree(level, placement.pos().above());
    }

    private static boolean ensureRepairDoorAvailable(ServerPlayer player, Mob npc) {
        if (findDoorItem(player) != null) {
            return true;
        }
        return craftDoors(player, npc, 1, isChestMaterialUseApproved(player)) && findDoorItem(player) != null;
    }

    private static ItemSlot findDoorItem(ServerPlayer player) {
        AiNpcEntity npc = activeAiNpc(player.getServer());
        if (npc != null) {
            ItemSlot slot = findDoorItemInContainer(npc.inventory());
            if (slot != null) {
                return slot;
            }
        }
        for (Container container : findApprovedMaterialContainers(player)) {
            ItemSlot slot = findDoorItemInContainer(container);
            if (slot != null) {
                return slot;
            }
        }
        return null;
    }

    private static ItemSlot findDoorItemInContainer(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && itemId(stack).endsWith("_door")) {
                return new ItemSlot(container, slot, stack);
            }
        }
        return null;
    }

    private static void consumeItemSlot(ItemSlot slot, int count) {
        slot.stack().shrink(count);
        if (slot.stack().isEmpty()) {
            slot.container().setItem(slot.slot(), ItemStack.EMPTY);
        }
        slot.container().setChanged();
    }

    private static boolean isPlankMaterialPreference(String preference) {
        return normalizeRequest(preference).contains("planks");
    }

    private static List<ContainerAccess> findNearbyContainerAccesses(ServerPlayer player) {
        return findNearbyContainerAccesses(player.serverLevel(), player.blockPosition());
    }

    private static List<ContainerAccess> findNearbyContainerAccesses(ServerLevel level, BlockPos center) {
        List<ContainerAccess> containers = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - NEARBY_CONTAINER_RADIUS,
                center.getY() - NEARBY_CONTAINER_VERTICAL_RADIUS,
                center.getZ() - NEARBY_CONTAINER_RADIUS,
                center.getX() + NEARBY_CONTAINER_RADIUS,
                center.getY() + NEARBY_CONTAINER_VERTICAL_RADIUS,
                center.getZ() + NEARBY_CONTAINER_RADIUS
        )) {
            BlockPos containerPos = pos.immutable();
            BlockEntity blockEntity = level.getBlockEntity(containerPos);
            if (blockEntity instanceof Container container) {
                containers.add(new ContainerAccess(container, containerPos, distance(center, containerPos)));
            }
        }

        containers.sort(Comparator.comparingDouble(ContainerAccess::distance));
        if (containers.size() > NEARBY_CONTAINER_LIMIT) {
            return new ArrayList<>(containers.subList(0, NEARBY_CONTAINER_LIMIT));
        }
        return containers;
    }

    private static List<Container> findNearbyContainers(ServerPlayer player) {
        List<Container> containers = new ArrayList<>();
        for (ContainerAccess access : findNearbyContainerAccesses(player)) {
            containers.add(access.container());
        }
        return containers;
    }

    private static List<Container> findApprovedMaterialContainers(ServerPlayer player) {
        if (!isChestMaterialUseApproved(player)) {
            return List.of();
        }
        return findNearbyContainers(player);
    }

    private static List<Container> findMaterialContainers(ServerPlayer player, boolean allowContainerMaterials) {
        return allowContainerMaterials ? findNearbyContainers(player) : findApprovedMaterialContainers(player);
    }

    private static ChestApprovalSavedData chestApprovalData(ServerPlayer player) {
        return player.getServer().overworld().getDataStorage().computeIfAbsent(CHEST_APPROVAL_FACTORY, CHEST_APPROVAL_DATA_NAME);
    }

    private static String materialSourceLabel(boolean allowContainerMaterials) {
        return allowContainerMaterials
                ? "NPC storage or explicitly requested nearby containers"
                : "NPC storage or approved nearby containers";
    }

    private static int countUnapprovedContainerPlaceableBlocks(ServerPlayer player) {
        if (isChestMaterialUseApproved(player)) {
            return 0;
        }

        int count = 0;
        for (Container container : findNearbyContainers(player)) {
            count += countPlaceableBlocksInContainer(container);
        }
        return count;
    }

    private static String chestApprovalHint(ServerPlayer player) {
        int pendingBlocks = countUnapprovedContainerPlaceableBlocks(player);
        if (pendingBlocks > 0) {
            return " Nearby containers have " + pendingBlocks
                    + " placeable blocks, but I need your approval before using chest materials. Say '/mcai ask approve chest materials' or run /mcai npc chest approve.";
        }
        return " If you want me to use nearby chest materials later, approve it with /mcai npc chest approve.";
    }

    private static ItemStack insertIntoNearbyContainers(AiNpcEntity npc, ServerPlayer owner, ItemStack stack) {
        if (stack.isEmpty() || !(npc.level() instanceof ServerLevel level)) {
            return stack;
        }
        BlockPos center = owner != null && owner.level() == npc.level() ? owner.blockPosition() : npc.blockPosition();
        return insertIntoContainers(findNearbyContainerAccesses(level, center), stack);
    }

    private static DepositResult depositInventoryToContainers(Container inventory, List<ContainerAccess> containers) {
        int moved = 0;
        int touchedStacks = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            int before = stack.getCount();
            ItemStack remainder = insertIntoContainers(containers, stack);
            int inserted = before - remainder.getCount();
            if (inserted <= 0) {
                continue;
            }

            moved += inserted;
            touchedStacks++;
            inventory.setItem(slot, remainder);
            if (remainder.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
        inventory.setChanged();
        return new DepositResult(moved, touchedStacks);
    }

    private static ItemStack insertIntoContainers(List<ContainerAccess> containers, ItemStack stack) {
        ItemStack remainder = stack.copy();
        for (ContainerAccess access : containers) {
            remainder = insertIntoContainer(access.container(), remainder);
            if (remainder.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }
        return remainder;
    }

    private static ItemStack insertIntoContainer(Container container, ItemStack stack) {
        ItemStack remainder = stack.copy();
        for (int slot = 0; slot < container.getContainerSize() && !remainder.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remainder) || !container.canPlaceItem(slot, remainder)) {
                continue;
            }

            int limit = Math.min(existing.getMaxStackSize(), container.getMaxStackSize(existing));
            int moved = Math.min(remainder.getCount(), Math.max(0, limit - existing.getCount()));
            if (moved <= 0) {
                continue;
            }
            existing.grow(moved);
            remainder.shrink(moved);
            container.setChanged();
        }

        for (int slot = 0; slot < container.getContainerSize() && !remainder.isEmpty(); slot++) {
            if (!container.getItem(slot).isEmpty() || !container.canPlaceItem(slot, remainder)) {
                continue;
            }

            int moved = Math.min(remainder.getCount(), container.getMaxStackSize(remainder));
            if (moved <= 0) {
                continue;
            }
            ItemStack placed = remainder.copy();
            placed.setCount(moved);
            container.setItem(slot, placed);
            remainder.shrink(moved);
            container.setChanged();
        }
        return remainder;
    }

    private static boolean canInsertIntoNearbyContainer(AiNpcEntity npc, ServerPlayer owner, ItemStack stack) {
        if (stack.isEmpty() || !(npc.level() instanceof ServerLevel level)) {
            return false;
        }
        BlockPos center = owner != null && owner.level() == npc.level() ? owner.blockPosition() : npc.blockPosition();
        for (ContainerAccess access : findNearbyContainerAccesses(level, center)) {
            if (canInsertIntoContainer(access.container(), stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canInsertIntoContainer(Container container, ItemStack stack) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (!container.canPlaceItem(slot, stack)) {
                continue;
            }
            if (existing.isEmpty()) {
                return true;
            }
            int limit = Math.min(existing.getMaxStackSize(), container.getMaxStackSize(existing));
            if (ItemStack.isSameItemSameComponents(existing, stack) && existing.getCount() < limit) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos findChestSelfTestPos(ServerLevel level, ServerPlayer player) {
        BlockPos center = player.blockPosition();
        for (int dy = 0; dy <= 3; dy++) {
            for (int radius = 2; radius <= 6; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        BlockPos pos = center.offset(dx, dy, dz);
                        if (pos.equals(center) || pos.equals(center.above()) || !level.getWorldBorder().isWithinBounds(pos)) {
                            continue;
                        }
                        if (level.getBlockState(pos).isAir() && level.noCollision(null, new AABB(pos))) {
                            return pos.immutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    private static int countContainerItem(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static JsonObject blockPosJson(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    private static ItemEntity findTargetItem(ServerLevel level, Mob npc, ServerPlayer owner) {
        if (targetItemUuid != null) {
            Entity entity = level.getEntity(targetItemUuid);
            if (entity instanceof ItemEntity item && item.isAlive() && !item.getItem().isEmpty()) {
                return item;
            }
        }

        List<ItemEntity> items = level.getEntitiesOfClass(
                ItemEntity.class,
                owner.getBoundingBox().inflate(taskRadius),
                item -> item.isAlive() && !item.getItem().isEmpty()
        );
        return items.stream()
                .min(Comparator.comparingDouble(item -> item.distanceToSqr(npc)))
                .orElse(null);
    }

    private static BlockPos findNearestBreakTarget(ServerLevel level, Mob npc, ServerPlayer owner, TaskKind kind) {
        if (kind == TaskKind.HARVEST_LOGS) {
            return findHarvestLogTarget(level, npc, owner);
        }

        BlockPos center = owner.blockPosition();
        int radius = Math.max(4, Math.min(taskRadius, McAiConfig.NPC_TASK_RADIUS.get()));
        int verticalRadius = Math.min(radius, 10);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - radius,
                center.getY() - verticalRadius,
                center.getZ() - radius,
                center.getX() + radius,
                center.getY() + verticalRadius,
                center.getZ() + radius
        )) {
            BlockPos candidate = pos.immutable();
            BlockState state = level.getBlockState(candidate);
            if (!isBreakTarget(level, candidate, kind)
                    || findUsableTool(owner, kind, state) == null
                    || isTemporarilyBlockedBreakTarget(candidate)
                    || findStandPos(level, npc, candidate) == null) {
                continue;
            }

            double heightPenalty = kind == TaskKind.HARVEST_LOGS ? Math.max(0, candidate.getY() - owner.blockPosition().getY()) * 0.35D : 0.0D;
            double distance = distanceToBlockSqr(npc, candidate) + heightPenalty;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        return best;
    }

    private static BlockPos findHarvestLogTarget(ServerLevel level, Mob npc, ServerPlayer owner) {
        BlockPos overhead = findImmediateOverheadHarvestTarget(level, npc, owner);
        if (overhead != null) {
            return overhead;
        }

        BlockPos best = findHarvestLogTargetAround(level, npc, owner, owner.blockPosition());
        BlockPos npcCentered = findHarvestLogTargetAround(level, npc, owner, npc.blockPosition());
        if (npcCentered != null && (best == null || distanceToBlockSqr(npc, npcCentered) < distanceToBlockSqr(npc, best))) {
            best = npcCentered;
        }
        return best;
    }

    private static BlockPos findHarvestLogTargetAround(ServerLevel level, Mob npc, ServerPlayer owner, BlockPos center) {
        int radius = Math.max(4, Math.min(taskRadius, McAiConfig.NPC_TASK_RADIUS.get()));
        int verticalRadius = Math.min(Math.max(radius, 10), 16);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - radius,
                center.getY() - verticalRadius,
                center.getZ() - radius,
                center.getX() + radius,
                center.getY() + verticalRadius,
                center.getZ() + radius
        )) {
            BlockPos candidate = pos.immutable();
            BlockState state = level.getBlockState(candidate);
            if (!isBreakTarget(level, candidate, TaskKind.HARVEST_LOGS)
                    || findUsableTool(owner, TaskKind.HARVEST_LOGS, state) == null
                    || isTemporarilyBlockedBreakTarget(candidate)) {
                continue;
            }

            boolean directlyReachable = canInteractWithBlock(npc, candidate)
                    || findHarvestStandPos(level, npc, candidate) != null;
            HarvestSupportPlan supportPlan = directlyReachable ? null : findHarvestSupportPlan(level, npc, candidate);
            if (!directlyReachable && (supportPlan == null || findScaffoldBlock(owner, level, supportPlan.supportPos()) == null)) {
                continue;
            }

            int heightAboveOwner = Math.max(0, candidate.getY() - owner.blockPosition().getY());
            double supportPenalty = directlyReachable ? 0.0D : HARVEST_SUPPORT_PENALTY;
            double score = distanceToBlockSqr(npc, candidate) + supportPenalty - heightAboveOwner * HARVEST_HIGH_LOG_BONUS;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        return best;
    }

    private static BlockPos findNearestCraftingTable(ServerPlayer player, Mob npc) {
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        int radius = Math.max(4, Math.min(McAiConfig.NPC_TASK_RADIUS.get(), CRAFTING_TABLE_RADIUS));
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - radius,
                center.getY() - CRAFTING_TABLE_VERTICAL_RADIUS,
                center.getZ() - radius,
                center.getX() + radius,
                center.getY() + CRAFTING_TABLE_VERTICAL_RADIUS,
                center.getZ() + radius
        )) {
            BlockPos candidate = pos.immutable();
            if (!level.getBlockState(candidate).is(Blocks.CRAFTING_TABLE)) {
                continue;
            }
            if (!canInteractWithBlock(npc, candidate) && findStandPos(level, npc, candidate) == null) {
                continue;
            }

            double distance = distanceToBlockSqr(npc, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        return best;
    }

    private static boolean craftRequestedAtTable(ServerPlayer player, Mob npc, String itemRequest, int requestedCount, boolean allowContainerMaterials) {
        String request = normalizeCraftRequest(itemRequest);
        BasicCraftTarget target = inferBasicCraftTarget(request);
        int count = requestedCount <= 0 && target == BasicCraftTarget.PLANKS ? 0 : clamp(requestedCount <= 0 ? 1 : requestedCount, 1, 2304);
        if (target == BasicCraftTarget.BASIC_TOOLS) {
            boolean axe = prepareBasicTool(player, npc, TaskKind.HARVEST_LOGS, ToolSummary.ToolKind.AXE, true, allowContainerMaterials);
            boolean pickaxe = prepareBasicTool(player, npc, TaskKind.MINE_ORES, ToolSummary.ToolKind.PICKAXE, true, allowContainerMaterials);
            return axe && pickaxe;
        }
        if (target == BasicCraftTarget.PICKAXE) {
            return craftRequestedTool(player, npc, TaskKind.MINE_ORES, ToolSummary.ToolKind.PICKAXE, request, count, allowContainerMaterials);
        }
        if (target == BasicCraftTarget.AXE) {
            return craftRequestedTool(player, npc, TaskKind.HARVEST_LOGS, ToolSummary.ToolKind.AXE, request, count, allowContainerMaterials);
        }
        if (target == BasicCraftTarget.PLANKS) {
            return craftPlanks(player, npc, count <= 0 ? 0 : Math.max(PLANKS_PER_LOG, count), allowContainerMaterials);
        }
        if (target == BasicCraftTarget.STICKS) {
            return craftSticks(player, npc, Math.max(STICKS_PER_CRAFT, count), allowContainerMaterials);
        }
        if (target == BasicCraftTarget.DOOR) {
            return craftDoors(player, npc, count, allowContainerMaterials);
        }
        if (request.contains("basic_tools") || request.contains("basic tools") || request.contains("tools")) {
            boolean axe = prepareBasicTool(player, npc, TaskKind.HARVEST_LOGS, ToolSummary.ToolKind.AXE, true, allowContainerMaterials);
            boolean pickaxe = prepareBasicTool(player, npc, TaskKind.MINE_ORES, ToolSummary.ToolKind.PICKAXE, true, allowContainerMaterials);
            return axe && pickaxe;
        }
        if (request.contains("pickaxe")) {
            return prepareBasicTool(player, npc, TaskKind.MINE_ORES, ToolSummary.ToolKind.PICKAXE, true, allowContainerMaterials);
        }
        if (request.contains("axe")) {
            return prepareBasicTool(player, npc, TaskKind.HARVEST_LOGS, ToolSummary.ToolKind.AXE, true, allowContainerMaterials);
        }
        if (request.contains("plank")) {
            return craftPlanks(player, npc, count <= 0 ? 0 : Math.max(PLANKS_PER_LOG, count), allowContainerMaterials);
        }
        if (request.contains("stick")) {
            return craftSticks(player, npc, Math.max(STICKS_PER_CRAFT, count), allowContainerMaterials);
        }
        if (request.contains("door")) {
            return craftDoors(player, npc, count, allowContainerMaterials);
        }

        String message = "Crafting table primitive supports axe, pickaxe, planks, sticks, door, and basic_tools; unsupported request: " + itemRequest + ".";
        say(player, message);
        TaskFeedback.failure(player, npc, "craft_at_table", "UNSUPPORTED_CRAFT", message);
        return false;
    }

    private static BlockPos findImmediateOverheadHarvestTarget(ServerLevel level, Mob npc, ServerPlayer owner) {
        BlockPos base = npc.blockPosition();
        int minY = base.getY() + 1;
        int maxY = Math.min(level.getMaxBuildHeight() - 1, base.getY() + OVERHEAD_HARVEST_HEIGHT);

        for (int tier = 0; tier <= OVERHEAD_HARVEST_RADIUS * 2; tier++) {
            for (int y = maxY; y >= minY; y--) {
                for (int dx = -OVERHEAD_HARVEST_RADIUS; dx <= OVERHEAD_HARVEST_RADIUS; dx++) {
                    for (int dz = -OVERHEAD_HARVEST_RADIUS; dz <= OVERHEAD_HARVEST_RADIUS; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) != tier) {
                            continue;
                        }

                        BlockPos candidate = new BlockPos(base.getX() + dx, y, base.getZ() + dz);
                        BlockState state = level.getBlockState(candidate);
                        if (isBreakTarget(level, candidate, TaskKind.HARVEST_LOGS)
                                && canInteractWithBlock(npc, candidate)
                                && findUsableTool(owner, TaskKind.HARVEST_LOGS, state) != null) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean isBreakTarget(ServerLevel level, BlockPos pos, TaskKind kind) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F || state.hasBlockEntity()) {
            return false;
        }

        return switch (kind) {
            case MINE_ORES -> isOre(state);
            case GATHER_STONE -> isStoneGatherTarget(state);
            case HARVEST_LOGS -> state.is(BlockTags.LOGS);
            case BUILD_BASIC_HOUSE, BUILD_LARGE_HOUSE, REPAIR_STRUCTURE, CRAFT_AT_TABLE, BREAK_BLOCK, PLACE_BLOCK, COLLECT_ITEMS, NONE -> false;
        };
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

    private static boolean isStoneGatherTarget(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.BLACKSTONE);
    }

    private static BlockPos findStandPos(ServerLevel level, Entity npc, BlockPos target) {
        return findStandPos(level, npc, target, pos -> true);
    }

    private static BlockPos findHarvestStandPos(ServerLevel level, Entity npc, BlockPos target) {
        return findStandPos(level, npc, target, pos -> isLikelyNavigableHarvestStand(npc, pos));
    }

    private static BlockPos findStandPos(ServerLevel level, Entity npc, BlockPos target, Predicate<BlockPos> candidateFilter) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = target.getY() - 4; y <= target.getY() + 1; y++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos candidate = new BlockPos(target.getX() + dx, y, target.getZ() + dz);
                    if (candidate.equals(target)
                            || isTemporarilyBlockedStandPos(candidate)
                            || !candidateFilter.test(candidate)
                            || !isStandable(level, candidate)
                            || !canReachBlockFromStand(candidate, target)) {
                        continue;
                    }

                    double distance = distanceToBlockSqr(npc, candidate);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isLikelyNavigableHarvestStand(Entity npc, BlockPos standPos) {
        int dy = standPos.getY() - npc.blockPosition().getY();
        return distanceToBlockSqr(npc, standPos) <= NAVIGATION_CLOSE_ENOUGH_SQR
                || (dy <= HARVEST_NAVIGABLE_STAND_MAX_UP && dy >= -HARVEST_NAVIGABLE_STAND_MAX_DOWN);
    }

    private static HarvestSupportPlan findHarvestSupportPlan(ServerLevel level, Entity npc, BlockPos target) {
        HarvestSupportPlan best = null;
        double bestScore = Double.MAX_VALUE;

        for (int y = target.getY() - 4; y <= target.getY() + 1; y++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos futureStand = new BlockPos(target.getX() + dx, y, target.getZ() + dz);
                    BlockPos supportPos = futureStand.below();
                    if (futureStand.equals(target)
                            || supportPos.equals(target)
                            || !canReachBlockFromStand(futureStand, target)
                            || !level.getBlockState(futureStand).isAir()
                            || !level.getBlockState(futureStand.above()).isAir()
                            || !level.getBlockState(supportPos).isAir()
                            || !isSolidSupport(level, supportPos.below())
                            || isEntityOccupyingBlock(npc, supportPos)
                            || isEntityOccupyingBlock(npc, futureStand)) {
                        continue;
                    }

                    BlockPos placementStand = findHarvestStandPos(level, npc, supportPos);
                    if (placementStand == null && !canInteractWithBlock(npc, supportPos)) {
                        continue;
                    }

                    double score = distanceToBlockSqr(npc, futureStand);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new HarvestSupportPlan(supportPos, futureStand, placementStand);
                    }
                }
            }
        }

        return best;
    }

    private static boolean isStandable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && isSolidSupport(level, pos.below())
                && level.noCollision(null, standingSpace(pos));
    }

    private static AABB standingSpace(BlockPos pos) {
        return new AABB(
                pos.getX() + 0.1D,
                pos.getY(),
                pos.getZ() + 0.1D,
                pos.getX() + 0.9D,
                pos.getY() + 1.95D,
                pos.getZ() + 0.9D
        );
    }

    private static boolean isSolidSupport(ServerLevel level, BlockPos pos) {
        return isSolidSupport(level, pos, level.getBlockState(pos));
    }

    private static boolean isSolidSupport(ServerLevel level, BlockPos pos, BlockState state) {
        return !state.isAir() && state.isFaceSturdy(level, pos, Direction.UP);
    }

    private static boolean isEntityOccupyingBlock(Entity entity, BlockPos pos) {
        return entity.getBoundingBox().intersects(new AABB(pos));
    }

    private static boolean breakBlockWithTool(ServerLevel level, ServerPlayer owner, Mob npc, BlockPos pos, BlockState state, TaskKind kind, ToolUse tool) {
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F || !canHarvestWithTool(state, tool.stack(), kind)) {
            return false;
        }

        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        Block block = state.getBlock();
        BlockState destroyedState = block.playerWillDestroy(level, pos, state, owner);
        ItemStack toolBeforeBreak = tool.stack().copy();
        tool.stack().mineBlock(level, destroyedState, pos, owner);
        markToolChanged(tool);

        level.levelEvent(2001, pos, Block.getId(state));
        boolean removed = destroyedState.onDestroyedByPlayer(level, pos, owner, true, level.getFluidState(pos));
        if (removed) {
            block.destroy(level, pos, destroyedState);
            block.playerDestroy(level, owner, pos, destroyedState, blockEntity, toolBeforeBreak);
            level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(npc, destroyedState));
        }
        return removed;
    }

    private static boolean breakBlockWithGenericTool(ServerLevel level, ServerPlayer owner, Mob npc, BlockPos pos, BlockState state, ToolUse tool) {
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F || !canUseToolForBlock(state, tool.stack())) {
            return false;
        }

        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        Block block = state.getBlock();
        BlockState destroyedState = block.playerWillDestroy(level, pos, state, owner);
        ItemStack toolBeforeBreak = tool.stack().copy();
        if (!tool.stack().isEmpty()) {
            tool.stack().mineBlock(level, destroyedState, pos, owner);
            markToolChanged(tool);
        }

        level.levelEvent(2001, pos, Block.getId(state));
        boolean removed = destroyedState.onDestroyedByPlayer(level, pos, owner, true, level.getFluidState(pos));
        if (removed) {
            block.destroy(level, pos, destroyedState);
            block.playerDestroy(level, owner, pos, destroyedState, blockEntity, toolBeforeBreak);
            level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(npc, destroyedState));
        }
        return removed;
    }

    private static ToolUse findGenericBreakTool(ServerPlayer player, BlockState state) {
        AiNpcEntity npc = activeAiNpc(player.getServer());
        ToolUse best = bestGenericToolInEquipment(npc, state);
        if (npc != null) {
            best = betterGenericTool(best, bestGenericToolInContainer(npc.inventory(), "npc_storage", state), state);
        }
        for (Container container : findApprovedMaterialContainers(player)) {
            best = betterGenericTool(best, bestGenericToolInContainer(container, "container", state), state);
        }
        if (best != null) {
            return best;
        }
        return canUseToolForBlock(state, ItemStack.EMPTY)
                ? new ToolUse(null, ItemStack.EMPTY, null, "hand", -1, null, null)
                : null;
    }

    private static ToolUse bestGenericToolInEquipment(AiNpcEntity npc, BlockState state) {
        if (npc == null) {
            return null;
        }

        ToolUse best = null;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = npc.getItemBySlot(slot);
            if (!isUsefulGenericBreakTool(state, stack)) {
                continue;
            }
            best = betterGenericTool(best, new ToolUse(classifyTool(stack), stack, null, "npc_equipment", -1, npc, slot), state);
        }
        return best;
    }

    private static ToolUse bestGenericToolInContainer(Container container, String source, BlockState state) {
        ToolUse best = null;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!isUsefulGenericBreakTool(state, stack)) {
                continue;
            }
            best = betterGenericTool(best, new ToolUse(classifyTool(stack), stack, container, source, slot, null, null), state);
        }
        return best;
    }

    private static ToolUse betterGenericTool(ToolUse current, ToolUse candidate, BlockState state) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        return toolScore(candidate.stack(), state) > toolScore(current.stack(), state) ? candidate : current;
    }

    private static boolean canUseToolForBlock(BlockState state, ItemStack stack) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        return !stack.isEmpty() && stack.isCorrectToolForDrops(state);
    }

    private static boolean isUsefulGenericBreakTool(BlockState state, ItemStack stack) {
        if (stack.isEmpty() || !canUseToolForBlock(state, stack) || !hasEnoughDurabilityForGenericBreak(stack)) {
            return false;
        }
        if (state.requiresCorrectToolForDrops()) {
            return stack.isCorrectToolForDrops(state);
        }
        return stack.getDestroySpeed(state) > 1.0F;
    }

    private static boolean hasEnoughDurabilityForGenericBreak(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return true;
        }
        return Math.max(0, stack.getMaxDamage() - stack.getDamageValue()) >= MIN_HARVEST_TOOL_DURABILITY;
    }

    private static ToolSummary.ToolKind classifyTool(ItemStack stack) {
        if (stack.is(ItemTags.PICKAXES)) {
            return ToolSummary.ToolKind.PICKAXE;
        }
        if (stack.is(ItemTags.AXES)) {
            return ToolSummary.ToolKind.AXE;
        }
        if (matchesTool(stack, ToolSummary.ToolKind.WEAPON)) {
            return ToolSummary.ToolKind.WEAPON;
        }
        return null;
    }

    private static ToolSummary.ToolKind preferredToolForState(BlockState state) {
        if (state.is(BlockTags.LOGS)) {
            return ToolSummary.ToolKind.AXE;
        }
        if (state.requiresCorrectToolForDrops()) {
            return ToolSummary.ToolKind.PICKAXE;
        }
        return null;
    }

    private static float destroyProgressPerTickGeneric(ServerLevel level, BlockPos pos, BlockState state, ItemStack tool) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness <= 0.0F) {
            return 1.0F;
        }

        float speed = tool.isEmpty() ? 1.0F : Math.max(1.0F, tool.getDestroySpeed(state));
        boolean correctTool = canUseToolForBlock(state, tool);
        return speed / hardness / (correctTool ? 30.0F : 100.0F);
    }

    private static ToolUse findUsableTool(ServerPlayer player, TaskKind kind, BlockState state) {
        ToolSummary.ToolKind requiredTool = requiredTool(kind);
        if (requiredTool == null) {
            return null;
        }

        AiNpcEntity npc = activeAiNpc(player.getServer());
        ToolUse best = bestToolInEquipment(npc, requiredTool, kind, state);
        if (npc != null) {
            best = betterTool(best, bestToolInContainer(npc.inventory(), "npc_storage", requiredTool, kind, state), state);
        }
        for (Container container : findApprovedMaterialContainers(player)) {
            best = betterTool(best, bestToolInContainer(container, "container", requiredTool, kind, state), state);
        }
        return best;
    }

    private static ToolUse bestToolInEquipment(AiNpcEntity npc, ToolSummary.ToolKind requiredTool, TaskKind task, BlockState state) {
        if (npc == null) {
            return null;
        }

        ToolUse best = null;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = npc.getItemBySlot(slot);
            if (stack.isEmpty() || !matchesTool(stack, requiredTool) || !hasEnoughDurability(stack, task) || !canHarvestWithTool(state, stack, task)) {
                continue;
            }
            best = betterTool(best, new ToolUse(requiredTool, stack, null, "npc_equipment", -1, npc, slot), state);
        }
        return best;
    }

    private static ToolUse bestToolInContainer(Container container, String source, ToolSummary.ToolKind requiredTool, TaskKind task, BlockState state) {
        ToolUse best = null;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !matchesTool(stack, requiredTool) || !hasEnoughDurability(stack, task) || !canHarvestWithTool(state, stack, task)) {
                continue;
            }
            best = betterTool(best, new ToolUse(requiredTool, stack, container, source, slot, null, null), state);
        }
        return best;
    }

    private static void markToolChanged(ToolUse tool) {
        if (tool.container() != null) {
            tool.container().setChanged();
        }
        if (tool.npc() != null && tool.equipmentSlot() != null) {
            tool.npc().setItemSlot(tool.equipmentSlot(), tool.stack().isEmpty() ? ItemStack.EMPTY : tool.stack());
            tool.npc().setPersistenceRequired();
        }
    }

    private static ToolUse betterTool(ToolUse current, ToolUse candidate, BlockState state) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        return toolScore(candidate.stack(), state) > toolScore(current.stack(), state) ? candidate : current;
    }

    private static int toolScore(ItemStack stack, BlockState state) {
        int durability = stack.isDamageableItem() ? Math.max(0, stack.getMaxDamage() - stack.getDamageValue()) : stack.getCount();
        return (int) (stack.getDestroySpeed(state) * 1000.0F) + durability;
    }

    private static int armorScore(ItemStack stack, EquipmentSlot equipmentSlot) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem armorItem) || npcArmorSlot(stack, equipmentSlot) != equipmentSlot) {
            return 0;
        }
        return armorItem.getDefense() * 10000
                + (int) (armorItem.getToughness() * 1000.0F)
                + materialTierScore(stack) * 100
                + durabilityScore(stack)
                + enchantmentBonus(stack);
    }

    private static EquipmentSlot npcArmorSlot(ItemStack stack, EquipmentSlot fallback) {
        if (stack.getItem() instanceof ArmorItem armorItem) {
            return armorItem.getEquipmentSlot();
        }
        return fallback;
    }

    private static int mainHandScore(ItemStack stack, ToolSummary.ToolKind kind) {
        if (stack.isEmpty() || !matchesTool(stack, kind)) {
            return 0;
        }

        int base = materialTierScore(stack) * 100 + durabilityScore(stack) + enchantmentBonus(stack);
        if (kind == ToolSummary.ToolKind.WEAPON) {
            if (stack.is(ItemTags.SWORDS)) {
                return base + 6000;
            }
            if (stack.is(ItemTags.AXES)) {
                return base + 4500;
            }
            if (stack.is(Items.TRIDENT)) {
                return base + 5500;
            }
            if (stack.is(Items.CROSSBOW)) {
                return base + 3500;
            }
            if (stack.is(Items.BOW)) {
                return base + 3000;
            }
            return base + 1000;
        }
        return base + (kind == ToolSummary.ToolKind.PICKAXE ? 4000 : 3500);
    }

    private static int shieldScore(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.SHIELD)) {
            return 0;
        }
        return 3000 + durabilityScore(stack) + enchantmentBonus(stack);
    }

    private static float weaponDamage(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0F;
        }

        int tier = materialTierScore(stack);
        if (stack.is(ItemTags.SWORDS)) {
            if (tier >= 60) {
                return 8.0F;
            }
            if (tier >= 50) {
                return 7.0F;
            }
            if (tier >= 40) {
                return 6.0F;
            }
            if (tier >= 30) {
                return 5.0F;
            }
            return 4.0F;
        }
        if (stack.is(ItemTags.AXES)) {
            if (tier >= 60) {
                return 10.0F;
            }
            if (tier >= 30) {
                return 9.0F;
            }
            return 7.0F;
        }
        if (stack.is(Items.TRIDENT)) {
            return 8.0F;
        }
        return 4.0F;
    }

    private static int materialTierScore(ItemStack stack) {
        String id = itemId(stack);
        if (id.contains("netherite")) {
            return 60;
        }
        if (id.contains("diamond")) {
            return 50;
        }
        if (id.contains("iron")) {
            return 40;
        }
        if (id.contains("chainmail")) {
            return 35;
        }
        if (id.contains("copper")) {
            return 32;
        }
        if (id.contains("stone")) {
            return 30;
        }
        if (id.contains("gold")) {
            return 25;
        }
        if (id.contains("wooden") || id.contains("wood") || id.contains("leather")) {
            return 10;
        }
        return 1;
    }

    private static int durabilityScore(ItemStack stack) {
        if (!stack.isDamageableItem()) {
            return stack.getCount();
        }
        return Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
    }

    private static int enchantmentBonus(ItemStack stack) {
        return stack.isEnchanted() ? 500 : 0;
    }

    private static boolean matchesTool(ItemStack stack, ToolSummary.ToolKind kind) {
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

    private static boolean hasEnoughDurability(ItemStack stack, TaskKind task) {
        if (!stack.isDamageableItem()) {
            return true;
        }
        int remaining = Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
        int minimum = (task == TaskKind.MINE_ORES || task == TaskKind.GATHER_STONE) ? MIN_MINING_TOOL_DURABILITY : MIN_HARVEST_TOOL_DURABILITY;
        return remaining >= minimum;
    }

    private static boolean canHarvestWithTool(BlockState state, ItemStack stack, TaskKind task) {
        return switch (task) {
            case MINE_ORES, GATHER_STONE -> stack.is(ItemTags.PICKAXES) && stack.isCorrectToolForDrops(state);
            case HARVEST_LOGS -> state.is(BlockTags.LOGS) && stack.is(ItemTags.AXES);
            case BUILD_BASIC_HOUSE, BUILD_LARGE_HOUSE, REPAIR_STRUCTURE, CRAFT_AT_TABLE, BREAK_BLOCK, PLACE_BLOCK, COLLECT_ITEMS, NONE -> false;
        };
    }

    private static float destroyProgressPerTick(ServerLevel level, BlockPos pos, BlockState state, ItemStack tool, TaskKind task) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness <= 0.0F) {
            return 1.0F;
        }

        float speed = Math.max(1.0F, tool.getDestroySpeed(state));
        boolean correctTool = canHarvestWithTool(state, tool, task);
        return speed / hardness / (correctTool ? 30.0F : 100.0F);
    }

    private static ToolSummary.ToolKind requiredTool(TaskKind kind) {
        return switch (kind) {
            case MINE_ORES, GATHER_STONE -> ToolSummary.ToolKind.PICKAXE;
            case HARVEST_LOGS -> ToolSummary.ToolKind.AXE;
            case BUILD_BASIC_HOUSE, BUILD_LARGE_HOUSE, REPAIR_STRUCTURE, CRAFT_AT_TABLE, BREAK_BLOCK, PLACE_BLOCK, COLLECT_ITEMS, NONE -> null;
        };
    }

    private static boolean canInteractWithBlock(Entity entity, BlockPos pos) {
        double dx = entity.getX() - (pos.getX() + 0.5D);
        double dy = entity.getEyeY() - (pos.getY() + 0.5D);
        double dz = entity.getZ() - (pos.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz <= INTERACTION_REACH_SQR;
    }

    private static boolean canReachBlockFromStand(BlockPos standPos, BlockPos target) {
        double dx = (standPos.getX() + 0.5D) - (target.getX() + 0.5D);
        double dy = (standPos.getY() + 1.6D) - (target.getY() + 0.5D);
        double dz = (standPos.getZ() + 0.5D) - (target.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz <= INTERACTION_REACH_SQR;
    }

    private static boolean moveToStandPos(Mob npc, BlockPos standPos) {
        if (standPos == null) {
            return false;
        }

        double distanceSqr = distanceToBlockSqr(npc, standPos);
        boolean sameTarget = navigationTarget != null && navigationTarget.distSqr(standPos) <= NAVIGATION_TARGET_EPSILON_SQR;
        if (!sameTarget) {
            navigationTarget = standPos.immutable();
            navigationStuckTicks = 0;
            lastNavigationDistanceSqr = distanceSqr;
            boolean started = npc.getNavigation().moveTo(standPos.getX() + 0.5D, standPos.getY(), standPos.getZ() + 0.5D, McAiConfig.NPC_MOVE_SPEED.get());
            if (!started && distanceSqr > NAVIGATION_CLOSE_ENOUGH_SQR) {
                markStandPosBlocked(standPos);
                resetNavigationProgress();
                return false;
            }
            return true;
        }

        if (distanceSqr + NAVIGATION_STUCK_MIN_PROGRESS_SQR < lastNavigationDistanceSqr) {
            navigationStuckTicks = 0;
            lastNavigationDistanceSqr = distanceSqr;
        } else if (distanceSqr > NAVIGATION_CLOSE_ENOUGH_SQR) {
            navigationStuckTicks += 5;
        }

        if (navigationStuckTicks >= NAVIGATION_STUCK_RETRY_TICKS && distanceSqr > NAVIGATION_CLOSE_ENOUGH_SQR) {
            markStandPosBlocked(standPos);
            npc.getNavigation().stop();
            resetNavigationProgress();
            return false;
        }

        if (npc.getNavigation().isDone() || tickCounter % 20 == 0) {
            boolean started = npc.getNavigation().moveTo(standPos.getX() + 0.5D, standPos.getY(), standPos.getZ() + 0.5D, McAiConfig.NPC_MOVE_SPEED.get());
            if (!started && distanceSqr > NAVIGATION_CLOSE_ENOUGH_SQR) {
                markStandPosBlocked(standPos);
                resetNavigationProgress();
                return false;
            }
        }
        return true;
    }

    private static void resetNavigationProgress() {
        navigationTarget = null;
        navigationStuckTicks = 0;
        lastNavigationDistanceSqr = Double.MAX_VALUE;
    }

    private static void resetTaskSearchMemory() {
        taskKnownResourceTarget = null;
        taskKnownResourceTravelTicks = 0;
        taskScoutTarget = null;
        taskScoutLegTicks = 0;
        taskScoutLegIndex = 0;
        temporarilyBlockedKnownResourceTarget = null;
        temporarilyBlockedKnownResourceTicks = 0;
    }

    private static void markStandPosBlocked(BlockPos standPos) {
        temporarilyBlockedStandPos = standPos.immutable();
        temporarilyBlockedStandPosTicks = NAVIGATION_BLOCKED_MEMORY_TICKS;
    }

    private static void markBreakTargetBlocked(BlockPos target) {
        if (target == null) {
            return;
        }
        temporarilyBlockedBreakTarget = target.immutable();
        temporarilyBlockedBreakTargetTicks = NAVIGATION_BLOCKED_TARGET_TICKS;
    }

    private static void markKnownResourceTargetBlocked(BlockPos target) {
        if (target == null) {
            return;
        }
        temporarilyBlockedKnownResourceTarget = target.immutable();
        temporarilyBlockedKnownResourceTicks = KNOWN_RESOURCE_BLOCKED_TICKS;
    }

    private static boolean isTemporarilyBlockedStandPos(BlockPos pos) {
        return temporarilyBlockedStandPos != null
                && temporarilyBlockedStandPosTicks > 0
                && temporarilyBlockedStandPos.equals(pos);
    }

    private static boolean isTemporarilyBlockedBreakTarget(BlockPos pos) {
        return temporarilyBlockedBreakTarget != null
                && temporarilyBlockedBreakTargetTicks > 0
                && temporarilyBlockedBreakTarget.equals(pos);
    }

    private static boolean isTemporarilyBlockedKnownResourceTarget(BlockPos pos) {
        if (temporarilyBlockedKnownResourceTarget == null || temporarilyBlockedKnownResourceTicks <= 0 || pos == null) {
            return false;
        }
        int dx = temporarilyBlockedKnownResourceTarget.getX() - pos.getX();
        int dy = temporarilyBlockedKnownResourceTarget.getY() - pos.getY();
        int dz = temporarilyBlockedKnownResourceTarget.getZ() - pos.getZ();
        return temporarilyBlockedKnownResourceTarget != null
                && dx * dx + dy * dy + dz * dz <= 64;
    }

    private static void tickTemporaryNavigationBlocks() {
        if (temporarilyBlockedStandPosTicks <= 0) {
            temporarilyBlockedStandPos = null;
        } else {
            temporarilyBlockedStandPosTicks = Math.max(0, temporarilyBlockedStandPosTicks - 5);
            if (temporarilyBlockedStandPosTicks == 0) {
                temporarilyBlockedStandPos = null;
            }
        }

        if (temporarilyBlockedBreakTargetTicks <= 0) {
            temporarilyBlockedBreakTarget = null;
        } else {
            temporarilyBlockedBreakTargetTicks = Math.max(0, temporarilyBlockedBreakTargetTicks - 5);
            if (temporarilyBlockedBreakTargetTicks == 0) {
                temporarilyBlockedBreakTarget = null;
            }
        }

        if (temporarilyBlockedKnownResourceTicks <= 0) {
            temporarilyBlockedKnownResourceTarget = null;
        } else {
            temporarilyBlockedKnownResourceTicks = Math.max(0, temporarilyBlockedKnownResourceTicks - 5);
            if (temporarilyBlockedKnownResourceTicks == 0) {
                temporarilyBlockedKnownResourceTarget = null;
            }
        }
    }

    private static boolean tryRecoverNavigationBlocker(ServerPlayer owner, Mob npc, ServerLevel level, BlockPos standPos, BlockPos goal, String reason) {
        if (goal == null) {
            return false;
        }
        if (mobilityRepairAttempts >= MOBILITY_REPAIR_MAX_ATTEMPTS) {
            reportNavigationBlocker(owner, npc, "MOBILITY_REPAIR_EXHAUSTED",
                    "I am still blocked while " + reason + " near " + positionText(goal)
                            + " after " + MOBILITY_REPAIR_MAX_ATTEMPTS + " bridge/step recovery attempts. I need a clearer route or a different target.",
                    true);
            return false;
        }

        MobilityRepair repair = findMobilityRepair(owner, npc, level, standPos, goal);
        if (repair == null) {
            reportNavigationBlocker(owner, npc, "MOBILITY_NEED_BLOCK",
                    "I got stuck while " + reason + " near " + positionText(goal)
                            + " and could not find a safe bridge/step placement with available NPC storage or approved container blocks."
                            + chestApprovalHint(owner),
                    false);
            return false;
        }

        String blockLabel = blockName(repair.block().stack());
        level.setBlock(repair.supportPos(), repair.block().state(), 3);
        level.gameEvent(GameEvent.BLOCK_PLACE, repair.supportPos(), GameEvent.Context.of(npc, repair.block().state()));
        consumePlaceableBlock(repair.block());
        mobilityRepairAttempts++;
        placeTicks = 0;
        resetNavigationProgress();
        temporarilyBlockedStandPos = null;
        temporarilyBlockedStandPosTicks = 0;

        String message = "I got stuck while " + reason + " near " + positionText(goal)
                + ", so I placed " + blockLabel + " at " + positionText(repair.supportPos())
                + " as a bridge/step (" + mobilityRepairAttempts + "/" + MOBILITY_REPAIR_MAX_ATTEMPTS + ").";
        TaskFeedback.info(owner, npc, taskName(), "MOBILITY_REPAIR_PLACED", message);
        say(owner, message);
        lastMobilityFeedbackTick = tickCounter;
        return true;
    }

    private static MobilityRepair findMobilityRepair(ServerPlayer owner, Mob npc, ServerLevel level, BlockPos standPos, BlockPos goal) {
        BlockPos base = npc.blockPosition();
        BlockPos target = standPos == null ? goal : standPos;
        List<BlockPos> candidates = new ArrayList<>();
        Direction primary = horizontalStepToward(base, target);
        if (primary != null) {
            addMobilityCandidates(candidates, base, primary);
        }

        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            if (direction != primary) {
                addMobilityCandidates(candidates, base, direction);
            }
        }
        addMobilityCandidate(candidates, base.below());

        for (BlockPos candidate : candidates) {
            if (!isMobilitySupportCandidate(level, npc, candidate, goal)) {
                continue;
            }
            PlaceableBlock block = findScaffoldBlock(owner, level, candidate);
            if (block != null) {
                return new MobilityRepair(candidate.immutable(), block);
            }
        }
        return null;
    }

    private static void addMobilityCandidates(List<BlockPos> candidates, BlockPos base, Direction direction) {
        BlockPos next = base.relative(direction);
        addMobilityCandidate(candidates, next.below());
        addMobilityCandidate(candidates, next);
    }

    private static void addMobilityCandidate(List<BlockPos> candidates, BlockPos candidate) {
        if (!candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    private static boolean isMobilitySupportCandidate(ServerLevel level, Mob npc, BlockPos candidate, BlockPos goal) {
        if (candidate == null
                || candidate.equals(goal)
                || !level.getWorldBorder().isWithinBounds(candidate)
                || isEntityOccupyingBlock(npc, candidate)
                || isEntityOccupyingBlock(npc, candidate.above())) {
            return false;
        }
        return level.getBlockState(candidate).isAir()
                && level.getBlockState(candidate.above()).isAir()
                && level.getBlockState(candidate.above(2)).isAir();
    }

    private static Direction horizontalStepToward(BlockPos from, BlockPos to) {
        if (to == null) {
            return null;
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return null;
    }

    private static void reportNavigationBlocker(ServerPlayer owner, Mob npc, String code, String message, boolean forceChat) {
        TaskFeedback.warn(owner, npc, taskName(), code, message);
        if (forceChat || tickCounter - lastMobilityFeedbackTick >= MOBILITY_FEEDBACK_COOLDOWN_TICKS) {
            say(owner, message);
            lastMobilityFeedbackTick = tickCounter;
        }
    }

    private static void resetMobilityRecovery() {
        mobilityRepairAttempts = 0;
        lastMobilityFeedbackTick = tickCounter - MOBILITY_FEEDBACK_COOLDOWN_TICKS;
    }

    private static boolean canPlaceBlock(ServerLevel level, BlockPos target, BlockState state) {
        return level.getBlockState(target).isAir()
                && state.canSurvive(level, target)
                && isPlacementCollisionFree(level, target);
    }

    private static boolean isPlacementCollisionFree(ServerLevel level, BlockPos target) {
        return level.noCollision(null, new AABB(target));
    }

    private static void resetBlockBreakState() {
        breakTicks = 0;
        blockBreakProgress = 0.0F;
        lastBreakStage = -1;
    }

    private static void clearBreakProgress(Mob npc) {
        if (targetBlock != null && npc.level() instanceof ServerLevel level) {
            level.destroyBlockProgress(npc.getId(), targetBlock, -1);
        }
        resetBlockBreakState();
    }

    private static void finishAfterIdle(ServerPlayer owner, Mob npc, String message, String code) {
        taskIdleTicks += 5;
        if (taskIdleTicks >= 20) {
            say(owner, message);
            TaskFeedback.warn(owner, npc, taskName(), code, message);
            if (taskKind == TaskKind.HARVEST_LOGS && pendingBuildKind != BuildKind.NONE) {
                if (!isChestMaterialUseApproved(owner) && countUnapprovedContainerPlaceableBlocks(owner) > 0) {
                    String pendingMessage = "I could not find reachable logs, but nearby containers have usable build materials. I saved the pending "
                            + pendingBuildKind.label() + "; approve chest materials and I will resume without using your inventory.";
                    say(owner, pendingMessage + chestApprovalHint(owner));
                    TaskFeedback.warn(owner, npc, pendingBuildKind.taskName(), "WAITING_FOR_CHEST_APPROVAL", pendingMessage);
                    clearTask();
                    return;
                }
                if (continuePendingBuildAfterGather(owner, npc)) {
                    return;
                }
                String pendingMessage = "Cannot continue " + pendingBuildKind.label()
                        + " automatically because no reachable logs were found." + chestApprovalHint(owner);
                say(owner, pendingMessage);
                TaskFeedback.warn(owner, npc, pendingBuildKind.taskName(), "GATHERING_WOOD_NO_LOGS", pendingMessage);
                clearPendingBuild();
            }
            if (taskKind == TaskKind.HARVEST_LOGS && hasPendingRepair()) {
                if (!isChestMaterialUseApproved(owner) && countUnapprovedContainerPlaceableBlocks(owner) > 0) {
                    String pendingMessage = "I could not find reachable logs, but nearby containers have usable repair materials. I saved the pending structure repair; approve chest materials and I will resume without using your inventory.";
                    say(owner, pendingMessage + chestApprovalHint(owner));
                    TaskFeedback.warn(owner, npc, "repair_structure", "WAITING_FOR_CHEST_APPROVAL", pendingMessage);
                    clearTask();
                    return;
                }
                if (continuePendingRepairAfterGather(owner, npc)) {
                    return;
                }
                String pendingMessage = "Cannot continue structure repair automatically because no reachable logs were found."
                        + chestApprovalHint(owner);
                say(owner, pendingMessage);
                TaskFeedback.warn(owner, npc, "repair_structure", "REPAIR_GATHERING_WOOD_NO_LOGS", pendingMessage);
                clearPendingRepair();
            }
            clearTask();
        }
    }

    private static Mob requireNpc(ServerPlayer player) {
        Mob npc = findNpcMob(player.getServer());
        if (npc != null) {
            return npc;
        }

        say(player, "NPC is not spawned. Use /mcai npc spawn.");
        return null;
    }

    private static AiNpcEntity requireAiNpc(ServerPlayer player) {
        Mob npc = requireNpc(player);
        if (npc instanceof AiNpcEntity aiNpc) {
            return aiNpc;
        }
        if (npc != null) {
            say(player, "This is a legacy NPC entity without storage. Use /mcai npc remove, then /mcai npc spawn.");
        }
        return null;
    }

    private static Mob findNpcMob(MinecraftServer server) {
        return activeNpcMob(server);
    }

    private static Mob findMobByUuid(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity instanceof Mob mob) {
                return mob;
            }
        }
        return null;
    }

    private static Entity findNpc(MinecraftServer server) {
        if (npcUuid != null) {
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(npcUuid);
                if (entity != null) {
                    return entity;
                }
            }
        }

        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (isCompanionEntity(entity)) {
                    npcUuid = entity.getUUID();
                    return entity;
                }
            }
        }
        return null;
    }

    private static Entity findCompanionEntity(MinecraftServer server, String selector, ServerPlayer player) {
        List<Entity> candidates = companionEntities(server);
        if (candidates.isEmpty()) {
            return null;
        }

        String normalized = selector == null ? "" : selector.trim();
        if (normalized.isBlank()) {
            return findNpc(server);
        }

        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : candidates) {
            if (!matchesNpcSelector(entity, normalized)) {
                continue;
            }
            double distance = player != null && player.level() == entity.level()
                    ? entity.distanceToSqr(player)
                    : Double.MAX_VALUE - 1.0D;
            if (best == null || distance < bestDistance) {
                best = entity;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean matchesNpcSelector(Entity entity, String selector) {
        String normalized = selector.trim();
        String uuid = entity.getUUID().toString();
        return uuid.equalsIgnoreCase(normalized)
                || (normalized.length() <= uuid.length() && uuid.regionMatches(true, 0, normalized, 0, normalized.length()))
                || profileId(entity).equalsIgnoreCase(normalized)
                || entity.getName().getString().equalsIgnoreCase(normalized);
    }

    private static List<Entity> companionEntities(MinecraftServer server) {
        List<Entity> entities = new ArrayList<>();
        if (server == null) {
            return entities;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (isCompanionEntity(entity)) {
                    entities.add(entity);
                }
            }
        }
        return entities;
    }

    private static List<Mob> companionMobs(MinecraftServer server) {
        List<Mob> mobs = new ArrayList<>();
        for (Entity entity : companionEntities(server)) {
            if (entity instanceof Mob mob) {
                mobs.add(mob);
            }
        }
        return mobs;
    }

    public static boolean isCompanionEntity(Entity entity) {
        if (entity.getTags().contains(TAG_COMPANION)) {
            return true;
        }

        boolean supportedEntity = entity instanceof AiNpcEntity || entity instanceof Villager;
        return supportedEntity && entity.getCustomName() != null && McAiConfig.BOT_NAME.get().equals(entity.getCustomName().getString());
    }

    public static boolean hasNpcNear(ServerPlayer player, double radius) {
        if (player == null || player.getServer() == null) {
            return false;
        }
        double radiusSqr = Math.max(0.0D, radius) * Math.max(0.0D, radius);
        for (Entity entity : companionEntities(player.getServer())) {
            if (entity.isAlive() && entity.level() == player.level() && entity.distanceToSqr(player) <= radiusSqr) {
                return true;
            }
        }
        return false;
    }

    private static JsonArray describeAll(ServerPlayer player) {
        JsonArray npcs = new JsonArray();
        MinecraftServer server = player == null ? null : player.getServer();
        for (Entity entity : companionEntities(server)) {
            boolean active = entity.getUUID().equals(npcUuid);
            boolean groupFollowing = groupFollowTargetUuid != null && !(active && taskKind != TaskKind.NONE);
            NpcRuntime runtime = runtimeForSnapshot(entity, active, active && followTargetUuid != null, groupFollowing);
            JsonObject item = NpcRuntimeSnapshot.from(
                    entity,
                    player,
                    profileId(entity),
                    active,
                    runtime.taskSnapshot(),
                    active && followTargetUuid != null,
                    groupFollowing,
                    followTargetUuid,
                    groupFollowTargetUuid
            ).toJson();
            npcs.add(item);
            if (npcs.size() >= 20) {
                return npcs;
            }
        }
        return npcs;
    }

    private static NpcRuntime runtimeForSnapshot(Entity entity, boolean activeNpc, boolean directFollowing, boolean groupFollowing) {
        NpcRuntime runtime = NPC_RUNTIMES.syncObservedNpc(
                entity.getUUID(),
                activeNpc,
                directFollowing,
                groupFollowing,
                followTargetUuid,
                groupFollowTargetUuid
        );
        if (activeNpc) {
            syncActiveTaskRuntime();
        } else {
            runtime.clearTask();
        }
        return runtime;
    }

    private static NpcRuntime syncActiveTaskRuntime() {
        return NPC_RUNTIMES.syncActiveNpcTask(npcUuid, activeTaskState());
    }

    private static NpcTaskState activeTaskState() {
        if (collectItemsTask != null) {
            return NpcTaskState.fromLegacyGlobalWithTaskId(
                    collectItemsTask.taskId,
                    "collect_items",
                    true,
                    collectItemsTask.ownerPauseTicks > 0,
                    collectItemsTask.stepsDone,
                    collectItemsTask.ownerPauseTicks / 20,
                    0,
                    collectItemsTask.ownerUuid,
                    "",
                    collectItemsTask.targetItemUuid
            );
        }

        boolean activeTask = taskKind != TaskKind.NONE;
        return NpcTaskState.fromLegacyGlobalWithTaskId(
                activeTask ? activeTaskId : "",
                activeTask ? taskName() : "idle",
                activeTask,
                activeTask && taskOwnerPauseTicks > 0,
                activeTask ? taskStepsDone : 0,
                activeTask ? taskOwnerPauseTicks / 20 : 0,
                activeTask ? Math.max(0, taskSearchTimeoutTicks - taskIdleTicks) / 20 : 0,
                activeTask ? taskOwnerUuid : null,
                activeTask && targetBlock != null ? positionText(targetBlock) : "",
                activeTask ? targetItemUuid : null
        );
    }

    private static void applyProfile(Entity entity, NpcProfile profile) {
        entity.setCustomName(Component.literal(profile.name()));
        entity.setCustomNameVisible(true);
        setProfileId(entity, profile.id());
        if (entity instanceof AiNpcEntity aiNpc) {
            aiNpc.setSkin(profile.skin());
        }
        if (entity instanceof Mob mob) {
            mob.setPersistenceRequired();
        }
    }

    private static void setProfileId(Entity entity, String profileId) {
        for (String tag : new ArrayList<>(entity.getTags())) {
            if (tag.startsWith(TAG_PROFILE_PREFIX)) {
                entity.removeTag(tag);
            }
        }
        entity.addTag(TAG_PROFILE_PREFIX + NpcProfile.sanitizeId(profileId, NpcProfile.DEFAULT_ID));
    }

    private static String profileId(Entity entity) {
        for (String tag : entity.getTags()) {
            if (tag.startsWith(TAG_PROFILE_PREFIX)) {
                String id = tag.substring(TAG_PROFILE_PREFIX.length());
                return id.isBlank() ? NpcProfile.DEFAULT_ID : id;
            }
        }
        return NpcProfile.DEFAULT_ID;
    }

    private static void clearTask() {
        collectItemsTask = null;
        taskOwnerUuid = null;
        taskKind = TaskKind.NONE;
        activeTaskId = "";
        targetBlock = null;
        targetItemUuid = null;
        autoPickupTargetUuid = null;
        taskRadius = 0;
        taskTargetCount = 0;
        taskStepsDone = 0;
        taskIdleTicks = 0;
        taskSearchTimeoutTicks = 0;
        taskOwnerPauseTicks = 0;
        taskPauseAnnounced = false;
        breakTicks = 0;
        placeTicks = 0;
        blockBreakProgress = 0.0F;
        lastBreakStage = -1;
        resetNavigationProgress();
        resetTaskSearchMemory();
        temporarilyBlockedStandPos = null;
        temporarilyBlockedStandPosTicks = 0;
        resetMobilityRecovery();
        primitivePlaceBlockPreference = "";
        craftTableItemRequest = "";
        craftTableRequestedCount = 0;
        craftTableAllowContainerMaterials = false;
        buildQueue = List.of();
        repairQueue = List.of();
        activeRepairPlan = null;
        currentBuildKind = BuildKind.BASIC;
        syncActiveTaskRuntime();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static String taskName() {
        return taskNameFor(taskKind);
    }

    private static String newTaskId(TaskKind kind) {
        return taskNameFor(kind) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String taskNameFor(TaskKind kind) {
        return switch (kind) {
            case NONE -> "idle";
            case COLLECT_ITEMS -> "collect_items";
            case MINE_ORES -> "mine_nearby_ore";
            case GATHER_STONE -> "gather_stone";
            case HARVEST_LOGS -> "harvest_logs";
            case BUILD_BASIC_HOUSE -> "build_basic_house";
            case BUILD_LARGE_HOUSE -> "build_large_house";
            case REPAIR_STRUCTURE -> "repair_structure";
            case CRAFT_AT_TABLE -> "craft_at_table";
            case BREAK_BLOCK -> "break_block";
            case PLACE_BLOCK -> "place_block";
        };
    }

    private static String taskStartFailure(TaskKind kind) {
        return switch (kind) {
            case MINE_ORES -> "Mining task not started";
            case GATHER_STONE -> "Stone gathering task not started";
            case HARVEST_LOGS -> "Log harvesting task not started";
            case BUILD_BASIC_HOUSE -> "Building task not started";
            case BUILD_LARGE_HOUSE -> "Large building task not started";
            case REPAIR_STRUCTURE -> "Structure repair task not started";
            case CRAFT_AT_TABLE -> "Crafting table task not started";
            case BREAK_BLOCK -> "Block breaking task not started";
            case PLACE_BLOCK -> "Block placement task not started";
            case COLLECT_ITEMS -> "Collection task not started";
            case NONE -> "Task not started";
        };
    }

    private static String blockName(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String blockName(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String stackLabel(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        String durability = stack.isDamageableItem()
                ? " " + Math.max(0, stack.getMaxDamage() - stack.getDamageValue()) + "/" + stack.getMaxDamage()
                : "";
        return blockName(stack) + "x" + stack.getCount() + durability;
    }

    private static String summarizeContainerItems(Container container, int maxItems) {
        StringBuilder builder = new StringBuilder();
        int added = 0;
        for (int slot = 0; slot < container.getContainerSize() && added < maxItems; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (added > 0) {
                builder.append(", ");
            }
            builder.append(stackLabel(stack));
            added++;
        }
        return added == 0 ? "empty" : builder.toString();
    }

    private static int occupiedSlots(Container container) {
        int occupied = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.getItem(slot).isEmpty()) {
                occupied++;
            }
        }
        return occupied;
    }

    private static int freeSlots(Container container) {
        int free = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double distanceToBlockSqr(Entity entity, BlockPos pos) {
        double dx = entity.getX() - (pos.getX() + 0.5);
        double dy = entity.getY() - (pos.getY() + 0.5);
        double dz = entity.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private static void say(ServerPlayer player, String message) {
        NpcChat.say(player, message);
    }

    private static String positionText(BlockPos pos) {
        if (pos == null) {
            return "unknown";
        }
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private enum TaskKind {
        NONE,
        COLLECT_ITEMS,
        MINE_ORES,
        GATHER_STONE,
        HARVEST_LOGS,
        BUILD_BASIC_HOUSE,
        BUILD_LARGE_HOUSE,
        REPAIR_STRUCTURE,
        CRAFT_AT_TABLE,
        BREAK_BLOCK,
        PLACE_BLOCK
    }

    private static final class CollectItemsTaskState {
        private final UUID ownerUuid;
        private final UUID npcUuid;
        private final int radius;
        private final String taskId;
        private UUID targetItemUuid;
        private int stepsDone;
        private int idleTicks;
        private int ownerPauseTicks;
        private boolean pauseAnnounced;

        private CollectItemsTaskState(UUID ownerUuid, UUID npcUuid, int radius, String taskId) {
            this.ownerUuid = ownerUuid;
            this.npcUuid = npcUuid;
            this.radius = radius;
            this.taskId = taskId;
        }
    }

    private enum BuildKind {
        NONE("none", "no build", "idle", TaskKind.NONE, ""),
        BASIC("basic", "basic 5x5 shelter", "build_basic_house", TaskKind.BUILD_BASIC_HOUSE, "Basic shelter complete."),
        LARGE("large", "large 7x7 house", "build_large_house", TaskKind.BUILD_LARGE_HOUSE, "Large house complete.");

        private final String id;
        private final String label;
        private final String taskName;
        private final TaskKind taskKind;
        private final String completionMessage;

        BuildKind(String id, String label, String taskName, TaskKind taskKind, String completionMessage) {
            this.id = id;
            this.label = label;
            this.taskName = taskName;
            this.taskKind = taskKind;
            this.completionMessage = completionMessage;
        }

        private String id() {
            return id;
        }

        private String label() {
            return label;
        }

        private String taskName() {
            return taskName;
        }

        private TaskKind taskKind() {
            return taskKind;
        }

        private String completionMessage() {
            return completionMessage;
        }
    }

    private static final class MaterialStats {
        private final String id;
        private final BlockState state;
        private int count;

        private MaterialStats(String id, BlockState state) {
            this.id = id;
            this.state = state;
        }
    }

    private record StructureBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        private static StructureBounds from(List<BlockPos> positions) {
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BlockPos pos : positions) {
                minX = Math.min(minX, pos.getX());
                maxX = Math.max(maxX, pos.getX());
                minY = Math.min(minY, pos.getY());
                maxY = Math.max(maxY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            return new StructureBounds(minX, maxX, minY, maxY, minZ, maxZ);
        }

        private int widthX() {
            return maxX - minX + 1;
        }

        private int widthZ() {
            return maxZ - minZ + 1;
        }

        private boolean containsHorizontal(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }

    private record RepairPlan(
            boolean blocked,
            String blockerCode,
            String summary,
            StructureBounds bounds,
            String materialPreference,
            BlockState wallState,
            List<RepairPlacement> placements,
            int wallPlacements,
            int doorPlacements,
            int roofPlacements,
            int floorPlacements
    ) {
        private static RepairPlan blocked(String code, String message) {
            return new RepairPlan(true, code, message, null, "", Blocks.AIR.defaultBlockState(), List.of(), 0, 0, 0, 0);
        }
    }

    private record RepairPlacement(
            BlockPos pos,
            BlockState state,
            BlockState upperState,
            String materialPreference,
            boolean door,
            RepairSurface surface
    ) {
    }

    private static final class MaterialCounter {
        private int sticks;
        private int planks;
        private int logs;
        private int stoneHeadMaterials;

        private CraftingCounts toCounts() {
            return new CraftingCounts(sticks, planks, logs, stoneHeadMaterials);
        }
    }

    private record CraftingCounts(int sticks, int planks, int logs, int stoneHeadMaterials) {
    }

    private record ToolCraftPlan(
            Item result,
            int stoneCost,
            int plankUnitsNeeded,
            int existingSticksUsed,
            int craftedStickLeftover,
            String materialLabel
    ) {
    }

    private record CraftLeftovers(int sticks, int planks) {
    }

    private record PlaceableBlock(BlockState state, Container container, int slot, ItemStack stack) {
    }

    private record ItemSlot(Container container, int slot, ItemStack stack) {
    }

    private record MobilityRepair(BlockPos supportPos, PlaceableBlock block) {
    }

    private record ContainerAccess(Container container, BlockPos pos, double distance) {
    }

    private record DepositResult(int moved, int touchedStacks) {
    }

    private record HarvestSupportPlan(BlockPos supportPos, BlockPos futureStand, BlockPos placementStand) {
    }

    private record ToolUse(
            ToolSummary.ToolKind kind,
            ItemStack stack,
            Container container,
            String source,
            int slot,
            AiNpcEntity npc,
            EquipmentSlot equipmentSlot
    ) {
    }

    private static final class ChestApprovalSavedData extends SavedData {
        private final Set<UUID> approvedOwners = new LinkedHashSet<>();

        private static ChestApprovalSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
            ChestApprovalSavedData data = new ChestApprovalSavedData();
            ListTag list = tag.getList("approvedOwners", Tag.TAG_STRING);
            for (int index = 0; index < list.size(); index++) {
                try {
                    data.approvedOwners.add(UUID.fromString(list.getString(index)));
                } catch (IllegalArgumentException ignored) {
                    // Ignore stale or hand-edited invalid UUID entries.
                }
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            ListTag list = new ListTag();
            for (UUID owner : approvedOwners) {
                list.add(StringTag.valueOf(owner.toString()));
            }
            tag.put("approvedOwners", list);
            return tag;
        }
    }
}
