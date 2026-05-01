package com.mcaibot.companion;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BridgeActions {
    private BridgeActions() {
    }

    public static void execute(ServerPlayer player, BridgeDecision decision) {
        execute(player, decision, null);
    }

    public static void execute(ServerPlayer player, BridgeDecision decision, NpcProfileStore profileStore) {
        BridgeDecision.Action action = decision.action();
        boolean groupTarget = isAllTargetScope(action);
        if (!groupTarget && !maybeSelectTargetNpc(player, action)) {
            return;
        }

        if (shouldSendReplyBeforeAction(decision)) {
            say(player, decision.reply());
        }

        if (decision.actionCall() != null && decision.actionCall().isPresent()) {
            handleActionCall(player, decision);
            return;
        }

        if (groupTarget) {
            if (dispatchGroupAction(player, decision)) {
                return;
            }
            say(player, "Action '" + action.name() + "' is not safely supported for all NPCs yet. Use a specific profileId or the selected NPC.");
            return;
        }

        switch (action.name()) {
            case "none" -> {
            }
            case "say" -> say(player, firstNonBlank(decision.action().message(), decision.reply(), "..."));
            case "ask_clarifying_question" -> say(player, firstNonBlank(
                    decision.action().message(),
                    decision.reply(),
                    "I need one more detail before I can do that."
            ));
            case "propose_plan" -> PlanManager.proposeOrSay(player, decision);
            case "start_plan", "begin_plan", "plan_task", "complex_task" -> PlanManager.startPlan(player, decision, true);
            case "taskgraph_next", "execute_next_taskgraph_node", "run_taskgraph_next" -> handleTaskGraphNext(player, decision);
            case "save_plan", "draft_plan" -> PlanManager.startPlan(player, decision, false);
            case "continue_plan", "resume_plan", "advance_plan", "run_plan" -> PlanManager.continuePlan(player);
            case "report_plan", "plan_status" -> PlanManager.reportPlan(player);
            case "cancel_plan", "clear_plan" -> PlanManager.cancelPlan(player);
            case "status", "npc_status", "report_status" -> {
                say(player, "Status: bridge online, dimension " + player.level().dimension().location());
                NpcManager.status(player);
            }
            case "report_task_status" -> reportTaskStatus(player, firstNonBlank(decision.action().message(), decision.reply(), ""));
            case "report_nearby" -> reportNearby(player, decision.action().radius() == null ? McAiConfig.SCAN_RADIUS.get() : decision.action().radius().intValue());
            case "report_inventory" -> reportInventory(player);
            case "report_crafting" -> NpcManager.reportCrafting(player);
            case "report_containers", "report_chests", "report_chest_rules" -> NpcManager.chestStatus(player);
            case "deposit_to_chest", "stash_items" -> {
                if (decision.action().item() == null || decision.action().item().isBlank()) {
                    NpcManager.depositNpcStorageToNearbyChest(player);
                } else {
                    NpcManager.depositItemToNearbyChest(player, decision.action().item(), actionCount(decision, 2304));
                }
            }
            case "deposit_item_to_chest" -> NpcManager.depositItemToNearbyChest(player, actionItem(decision), actionCount(decision, 2304));
            case "withdraw_from_chest", "take_from_chest" -> NpcManager.withdrawFromNearbyChest(player, actionItem(decision), actionCount(decision, 64));
            case "approve_chest_materials", "allow_chest_materials", "approve_container_materials" -> NpcManager.approveChestMaterials(player);
            case "revoke_chest_materials", "deny_chest_materials", "disallow_chest_materials" -> NpcManager.revokeChestMaterials(player);
            case "report_resources", "assess_resources" -> ResourceAssessment.report(player);
            case "inspect_block" -> {
                BridgeDecision.Position position = decision.action().position();
                if (position == null) {
                    say(player, "I need exact block coordinates before inspecting a block.");
                } else {
                    NpcManager.inspectBlock(player, blockPos(position));
                }
            }
            case "report_modded_nearby" -> ModInteractionManager.reportNearby(player, actionRadius(decision));
            case "inspect_mod_block" -> {
                BridgeDecision.Position position = decision.action().position();
                if (position == null) {
                    ModInteractionManager.reportNearby(player, actionRadius(decision));
                } else {
                    ModInteractionManager.inspectBlock(player, blockPos(position));
                }
            }
            case "create_wrench" -> PlanManager.startPlan(player, decision, true);
            case "use_mod_wrench" -> {
                BridgeDecision.Position position = decision.action().position();
                if (position == null) {
                    say(player, "I need exact block coordinates before using a wrench.");
                } else {
                    ModInteractionManager.useWrench(player, blockPos(position));
                }
            }
            case "come", "come_to_player", "look_at_player" -> NpcManager.comeTo(player);
            case "follow", "follow_player" -> NpcManager.follow(player);
            case "guard_player", "protect_player" -> ProtectionManager.start(player, actionRadius(decision));
            case "stop_guard" -> ProtectionManager.stop(player);
            case "stop", "stop_npc", "patrol_stop" -> {
                NpcManager.stop(player);
            }
            case "collect", "collect_items" -> NpcManager.collectItems(player, actionRadius(decision));
            case "mine", "mine_nearby_ore" -> NpcManager.mineOres(player, actionRadius(decision));
            case "gather_stone", "mine_stone", "gather_cobblestone", "collect_cobblestone" ->
                    NpcManager.gatherStone(player, actionRadius(decision), actionCount(decision, 3));
            case "wood", "harvest_logs" -> NpcManager.harvestLogs(player, actionRadius(decision), actionDurationSeconds(decision));
            case "prepare_basic_tools" -> NpcManager.prepareBasicTools(player, true, true);
            case "prepare_axe" -> NpcManager.prepareBasicTools(player, true, false);
            case "prepare_pickaxe" -> NpcManager.prepareBasicTools(player, false, true);
            case "craft_item" -> NpcManager.craftItem(player, actionItem(decision), actionCountAllowZero(decision, 1));
            case "craft_at_table", "use_crafting_table", "craft_with_workbench" ->
                    NpcManager.craftAtNearbyTable(player, actionItem(decision), actionCountAllowZero(decision, 1), false);
            case "craft_from_chest_at_table", "craft_chest_item_at_table" ->
                    NpcManager.craftAtNearbyTable(player, actionItem(decision), actionCountAllowZero(decision, 1), true);
            case "equip_best", "equip_best_gear", "auto_equip" -> NpcManager.autoEquipNow(player);
            case "gear", "gear_status", "report_gear" -> NpcManager.gearStatus(player);
            case "list", "list_npcs", "list_npc" -> NpcManager.listNpcs(player);
            case "break_block" -> {
                BridgeDecision.Position position = decision.action().position();
                if (position == null) {
                    say(player, "I need exact block coordinates before breaking a block.");
                } else {
                    NpcManager.breakBlockAt(player, blockPos(position));
                }
            }
            case "place_block" -> {
                BridgeDecision.Position position = decision.action().position();
                if (position == null) {
                    say(player, "I need exact block coordinates before placing a block.");
                } else {
                    NpcManager.placeBlockAt(player, blockPos(position), firstNonBlank(decision.action().block(), decision.action().item(), decision.action().value()));
                }
            }
            case "repair_structure", "repair_house", "repair_wall", "repair_door", "patch_house", "fix_house" -> {
                if (allowsChestMaterials(action)) {
                    NpcManager.approveChestMaterials(player);
                }
                String mode = repairMode(action);
                if ("preview".equals(mode)) {
                    NpcManager.previewNearbyStructureRepair(player, actionRadius(decision));
                } else if ("confirm".equals(mode)) {
                    NpcManager.confirmRepairPreview(player);
                } else {
                    NpcManager.repairNearbyStructure(player, actionRadius(decision));
                }
            }
            case "prepare_build_materials", "gather_wood", "collect_drops", "build_basic_shelter", "create_inspect" -> PlanManager.startPlan(player, decision, true);
            case "build_basic_house" -> NpcManager.buildBasicHouse(player);
            case "build_large_house" -> NpcManager.buildLargeHouse(player);
            case "goto_position" -> {
                BridgeDecision.Position position = decision.action().position();
                if (position == null) {
                    say(player, "No target position was provided.");
                } else {
                    NpcManager.goTo(player, position.x(), position.y(), position.z());
                }
            }
            case "remember" -> remember(player, decision, profileStore);
            case "update_behavior", "update_npc_behavior", "update_persona", "set_behavior", "set_persona", "set_npc_profile", "rename_npc" ->
                    updateNpcBehavior(player, decision, profileStore);
            case "recall" -> say(player, firstNonBlank(decision.reply(), "I checked memory, but there is no matching note in this response."));
            default -> say(player, "Action '" + decision.action().name() + "' is not implemented in the NeoForge companion yet.");
        }
    }

    private static void handleActionCall(ServerPlayer player, BridgeDecision decision) {
        ActionResult result = ActionPrimitiveRegistry.execute(player, decision.actionCall());
        String taskName = "action_call:" + firstNonBlank(decision.actionCall().name(), "unknown");
        TaskFeedback.recordActionResult(player, NpcManager.activeNpcMob(player.getServer()), taskName, result);

        if (result.isBlocked() || result.isFailed() || decision.reply() == null || decision.reply().isBlank()) {
            say(player, firstNonBlank(result.message(), result.status()));
        }
    }

    private static void handleTaskGraphNext(ServerPlayer player, BridgeDecision decision) {
        if (decision.taskGraph() != null && decision.taskGraph().isPresent()) {
            TaskGraph graph = decision.taskGraph();
            JsonObject node = TaskGraphPlanner.currentNode(graph);
            if (node.isEmpty()) {
                ActionResult result = ActionResult.blocked("NO_READY_NODE",
                        "No ready TaskGraph node was found in the model-supplied graph.",
                        "Repair currentNodeId, node.status, or dependsOn before asking Java to execute.");
                recordTaskGraphResult(player, graph, node, result);
                say(player, result.message());
                return;
            }

            ActionCall call = TaskGraphPlanner.actionCallForNode(graph, node, null);
            ActionResult result = ActionPrimitiveRegistry.execute(player, call)
                    .withEffect("taskGraphId", graph.id())
                    .withEffect("nodeId", AgentJson.string(node, "id", ""));
            TaskGraph updated = TaskGraphPlanner.applyActionResult(graph, AgentJson.string(node, "id", ""), result);
            recordTaskGraphResult(player, updated, node, result);
            if (result.isBlocked() || result.isFailed() || decision.reply() == null || decision.reply().isBlank()) {
                say(player, result.message());
            }
            return;
        }

        JsonObject execution = PlanManager.executeNextTaskGraphNode(player);
        JsonObject result = execution.has("actionResult") && execution.get("actionResult").isJsonObject()
                ? execution.getAsJsonObject("actionResult")
                : new JsonObject();
        String message = AgentJson.string(result, "message", "TaskGraph next node executed.");
        String status = AgentJson.string(result, "status", "success");
        if (status.equals("blocked") || status.equals("failed") || decision.reply() == null || decision.reply().isBlank()) {
            say(player, message);
        }
    }

    private static void recordTaskGraphResult(ServerPlayer player, TaskGraph graph, JsonObject node, ActionResult result) {
        ActionResult stored = result
                .withObservation("node", node == null ? new JsonObject() : node.deepCopy())
                .withObservation("taskGraph", graph.toJson());
        TaskFeedback.recordActionResult(player, NpcManager.activeNpcMob(player.getServer()),
                "taskgraph:" + graph.id(), stored);
    }

    private static boolean isAllTargetScope(BridgeDecision.Action action) {
        String explicitScope = normalizeKey(action.targetScope());
        if (!explicitScope.isBlank()) {
            return isAllScope(explicitScope);
        }
        return isAllScope(normalizeKey(action.profileId()));
    }

    private static boolean isAllScope(String normalizedScope) {
        return normalizedScope.equals("all")
                || normalizedScope.equals("everyone")
                || normalizedScope.equals("everybody")
                || normalizedScope.equals("team")
                || normalizedScope.equals("group")
                || normalizedScope.equals("party");
    }

    private static boolean dispatchGroupAction(ServerPlayer player, BridgeDecision decision) {
        String groupAction = groupActionName(decision.action().name());
        if (groupAction.isBlank()) {
            return false;
        }

        NpcManager.group(player, groupAction, actionRadius(decision), actionDurationSeconds(decision));
        return true;
    }

    private static boolean allowsChestMaterials(BridgeDecision.Action action) {
        String text = actionText(action);
        return text.contains("use_chest_materials")
                || text.contains("approve_chest_materials")
                || text.contains("allow_chest_materials")
                || text.contains("container_materials")
                || text.contains("material_permission");
    }

    private static String repairMode(BridgeDecision.Action action) {
        String text = actionText(action);
        if (text.contains("repair_confirm")
                || text.contains("confirm")
                || text.contains("\u786e\u8ba4")
                || text.contains("\u6309\u65b9\u6848")
                || text.contains("\u5f00\u59cb\u6267\u884c")
                || text.contains("\u5f00\u59cb\u4fee")) {
            return "confirm";
        }
        if (text.contains("repair_preview")
                || text.contains("preview")
                || text.contains("dry_run")
                || text.contains("\u9884\u89c8")
                || text.contains("\u65b9\u6848")
                || text.contains("\u5148\u770b")
                || text.contains("\u600e\u4e48\u4fee")) {
            return "preview";
        }
        return "";
    }

    private static String actionText(BridgeDecision.Action action) {
        return String.join(" ",
                firstNonBlank(action.key(), ""),
                firstNonBlank(action.value(), ""),
                firstNonBlank(action.message(), ""),
                firstNonBlank(action.item(), ""),
                firstNonBlank(action.block(), "")
        ).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String groupActionName(String actionName) {
        return switch (normalizeKey(actionName)) {
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
            default -> "";
        };
    }

    private static boolean isNpcScopedAction(String actionName) {
        String normalized = normalizeKey(actionName);
        return !groupActionName(normalized).isBlank()
                || normalized.equals("goto_position")
                || normalized.equals("break_block")
                || normalized.equals("place_block")
                || normalized.equals("prepare_basic_tools")
                || normalized.equals("prepare_axe")
                || normalized.equals("prepare_pickaxe")
                || normalized.equals("craft_item")
                || normalized.equals("craft_at_table")
                || normalized.equals("use_crafting_table")
                || normalized.equals("craft_with_workbench")
                || normalized.equals("craft_from_chest_at_table")
                || normalized.equals("craft_chest_item_at_table")
                || normalized.equals("build_basic_house")
                || normalized.equals("build_large_house")
                || normalized.equals("deposit_to_chest")
                || normalized.equals("stash_items")
                || normalized.equals("deposit_item_to_chest")
                || normalized.equals("withdraw_from_chest")
                || normalized.equals("take_from_chest");
    }

    private static int actionRadius(BridgeDecision decision) {
        return decision.action().radius() == null ? McAiConfig.NPC_TASK_RADIUS.get() : decision.action().radius().intValue();
    }

    private static int actionDurationSeconds(BridgeDecision decision) {
        return decision.action().durationSeconds() == null ? 90 : decision.action().durationSeconds().intValue();
    }

    private static int actionCount(BridgeDecision decision, int fallback) {
        Double count = decision.action().count();
        if (count == null || !Double.isFinite(count)) {
            return fallback;
        }
        return Math.max(1, Math.min(2304, count.intValue()));
    }

    private static int actionCountAllowZero(BridgeDecision decision, int fallback) {
        Double count = decision.action().count();
        if (count == null || !Double.isFinite(count)) {
            return fallback;
        }
        return Math.max(0, Math.min(2304, count.intValue()));
    }

    private static String actionItem(BridgeDecision decision) {
        return firstNonBlank(decision.action().item(), decision.action().value(), decision.action().key(), decision.action().message());
    }

    private static boolean shouldSendReplyBeforeAction(BridgeDecision decision) {
        if (decision.reply() == null || decision.reply().isBlank()) {
            return false;
        }

        String actionName = decision.action().name();
        return !"say".equals(actionName)
                && !"ask_clarifying_question".equals(actionName)
                && !"propose_plan".equals(actionName)
                && !"start_plan".equals(actionName)
                && !"begin_plan".equals(actionName)
                && !"plan_task".equals(actionName)
                && !"complex_task".equals(actionName)
                && !"taskgraph_next".equals(actionName)
                && !"execute_next_taskgraph_node".equals(actionName)
                && !"run_taskgraph_next".equals(actionName)
                && !"save_plan".equals(actionName)
                && !"draft_plan".equals(actionName)
                && !"continue_plan".equals(actionName)
                && !"resume_plan".equals(actionName)
                && !"advance_plan".equals(actionName)
                && !"run_plan".equals(actionName)
                && !"report_plan".equals(actionName)
                && !"plan_status".equals(actionName)
                && !"cancel_plan".equals(actionName)
                && !"clear_plan".equals(actionName)
                && !"report_task_status".equals(actionName)
                && !"report_resources".equals(actionName)
                && !"assess_resources".equals(actionName)
                && !"approve_chest_materials".equals(actionName)
                && !"allow_chest_materials".equals(actionName)
                && !"approve_container_materials".equals(actionName)
                && !"revoke_chest_materials".equals(actionName)
                && !"deny_chest_materials".equals(actionName)
                && !"disallow_chest_materials".equals(actionName)
                && !"remember".equals(actionName)
                && !"update_behavior".equals(actionName)
                && !"update_npc_behavior".equals(actionName)
                && !"update_persona".equals(actionName)
                && !"set_behavior".equals(actionName)
                && !"set_persona".equals(actionName)
                && !"set_npc_profile".equals(actionName)
                && !"rename_npc".equals(actionName)
                && !"report_modded_nearby".equals(actionName)
                && !"inspect_mod_block".equals(actionName)
                && !"use_mod_wrench".equals(actionName)
                && !"create_wrench".equals(actionName)
                && !"prepare_basic_tools".equals(actionName)
                && !"prepare_build_materials".equals(actionName)
                && !"gather_wood".equals(actionName)
                && !"collect_drops".equals(actionName)
                && !"build_basic_shelter".equals(actionName)
                && !"create_inspect".equals(actionName)
                && !"recall".equals(actionName);
    }

    private static boolean maybeSelectTargetNpc(ServerPlayer player, BridgeDecision.Action action) {
        String selector = firstNonBlank(action.profileId(), "");
        if (selector.isBlank() || isProfileMemoryAction(action.name())) {
            return true;
        }
        return NpcManager.selectActive(player, selector);
    }

    private static boolean isProfileMemoryAction(String actionName) {
        String normalized = normalizeKey(actionName);
        return normalized.equals("remember")
                || normalized.equals("update_behavior")
                || normalized.equals("update_npc_behavior")
                || normalized.equals("update_persona")
                || normalized.equals("set_behavior")
                || normalized.equals("set_persona")
                || normalized.equals("set_npc_profile")
                || normalized.equals("rename_npc");
    }

    private static BlockPos blockPos(BridgeDecision.Position position) {
        return BlockPos.containing(position.x(), position.y(), position.z());
    }

    private static void remember(ServerPlayer player, BridgeDecision decision, NpcProfileStore profileStore) {
        if (profileStore != null && isPersonaMemoryKey(decision.action().key())) {
            updateNpcBehavior(player, decision, profileStore);
            return;
        }

        String key = firstNonBlank(decision.action().key(), "that");
        String value = firstNonBlank(decision.action().value(), decision.action().message(), "");
        say(player, value.isBlank() ? "Remembered " + key + "." : "Remembered " + key + ": " + value);
    }

    private static void updateNpcBehavior(ServerPlayer player, BridgeDecision decision, NpcProfileStore profileStore) {
        if (profileStore == null) {
            say(player, "I can acknowledge that preference, but profile storage is not available in this context.");
            return;
        }

        BridgeDecision.Action action = decision.action();
        String selector = firstNonBlank(action.profileId(), NpcManager.activeProfileId(player.getServer()));
        NpcProfile current = profileStore.find(selector).orElseGet(profileStore::defaultProfile);

        String rememberedValue = action.value();
        String key = normalizeKey(action.key());
        String canonicalKey = canonicalPersonaKey(key);
        String actionName = normalizeKey(action.name());
        String actionValue = firstNonBlank(action.value(), action.message(), "");
        String newName = firstNonBlank(
                action.npcName(),
                valueIfAction(actionName, actionValue, "rename_npc"),
                valueIfKey(canonicalKey, rememberedValue, "name", "npc_name", "display_name"),
                current.name()
        );
        String newPersonality = firstNonBlank(
                action.personality(),
                valueIfAction(actionName, actionValue, "update_persona", "set_persona"),
                valueIfKey(canonicalKey, rememberedValue, "personality", "persona"),
                current.personality()
        );
        String requestedStyle = firstNonBlank(
                action.style(),
                action.behaviorPreference(),
                valueIfAction(actionName, actionValue, "update_behavior", "update_npc_behavior", "set_behavior"),
                valueIfKey(canonicalKey, rememberedValue, "behavior", "behavior_preference", "preference", "style", "behavior_style")
        );
        String newStyle = isAutonomyPreferenceKey(key)
                ? mergeAutonomyPreference(current.style(), requestedStyle)
                : mergeStylePreference(current.style(), requestedStyle);
        if (newStyle.isBlank()) {
            newStyle = current.style();
        }
        String newRole = firstNonBlank(
                action.defaultRole(),
                valueIfKey(canonicalKey, rememberedValue, "default_role", "role"),
                current.defaultRole()
        );
        String newSkin = firstNonBlank(
                valueIfKey(canonicalKey, rememberedValue, "skin", "appearance", "visual", "look"),
                current.skin()
        );

        NpcProfile updated = new NpcProfile(
                current.id(),
                limitText(newName, 48),
                limitText(newPersonality, 512),
                limitText(newSkin, 128),
                limitText(newStyle, 512),
                current.owner(),
                limitText(newRole, 96),
                current.enabled()
        );
        profileStore.upsert(updated);
        NpcManager.applyProfileToSpawned(player.getServer(), updated);

        List<String> changed = changedFields(current, updated);
        if (changed.isEmpty()) {
            say(player, "No NPC behavior preference changed.");
            return;
        }

        String message = "Updated " + updated.name() + " profile " + updated.id() + ": " + String.join(", ", changed) + ".";
        say(player, message);
        TaskFeedback.info(player, NpcManager.activeNpcMob(player.getServer()), "persona", "PROFILE_UPDATED", message);
    }

    private static List<String> changedFields(NpcProfile before, NpcProfile after) {
        List<String> changed = new ArrayList<>();
        if (!before.name().equals(after.name())) {
            changed.add("name=" + after.name());
        }
        if (!before.personality().equals(after.personality())) {
            changed.add("personality");
        }
        if (!before.style().equals(after.style())) {
            changed.add("behavior/style");
        }
        if (!before.defaultRole().equals(after.defaultRole())) {
            changed.add("role=" + after.defaultRole());
        }
        if (!before.skin().equals(after.skin())) {
            changed.add("skin=" + after.skin());
        }
        return changed;
    }

    private static boolean isPersonaMemoryKey(String key) {
        String normalized = canonicalPersonaKey(normalizeKey(key));
        return normalized.equals("name")
                || normalized.equals("npc_name")
                || normalized.equals("display_name")
                || normalized.equals("personality")
                || normalized.equals("persona")
                || normalized.equals("behavior")
                || normalized.equals("behavior_preference")
                || normalized.equals("preference")
                || normalized.equals("style")
                || normalized.equals("behavior_style")
                || normalized.equals("default_role")
                || normalized.equals("role")
                || normalized.equals("skin")
                || normalized.equals("appearance")
                || normalized.equals("visual")
                || normalized.equals("look");
    }

    private static String canonicalPersonaKey(String key) {
        return switch (normalizeKey(key)) {
            case "behavior.identity.name", "behavior_identity_name", "behavior.name", "behavior_name" -> "name";
            case "behavior.speaking_style", "behavior_speaking_style" -> "style";
            case "behavior.tool_durability", "behavior_tool_durability" -> "behavior";
            case "behavior.protection", "behavior_protection" -> "behavior";
            case "behavior.harvest_logs_range", "behavior_harvest_logs_range" -> "behavior";
            case "behavior.build_style", "behavior_build_style", "build_style" -> "style";
            case "behavior.autonomy", "behavior_autonomy", "autonomy" -> "behavior";
            case "behavior.appearance", "behavior_appearance", "behavior.skin", "behavior_skin", "behavior.appearance.skin", "behavior_appearance_skin", "appearance.skin", "appearance_skin" -> "skin";
            default -> normalizeKey(key);
        };
    }

    private static String mergeStylePreference(String currentStyle, String requestedStyle) {
        String update = firstNonBlank(requestedStyle, "");
        if (update.isBlank()) {
            return firstNonBlank(currentStyle, "");
        }

        String current = firstNonBlank(currentStyle, "");
        if (current.isBlank()) {
            return update;
        }

        if (current.contains(update)) {
            return current;
        }
        return current + "; " + update;
    }

    private static String mergeAutonomyPreference(String currentStyle, String requestedStyle) {
        String update = firstNonBlank(requestedStyle, "");
        if (update.isBlank()) {
            return firstNonBlank(currentStyle, "");
        }

        List<String> retained = new ArrayList<>();
        for (String segment : firstNonBlank(currentStyle, "").split(";")) {
            String trimmed = segment.trim();
            if (!trimmed.isBlank() && !isAutonomyStyleSegment(trimmed)) {
                retained.add(trimmed);
            }
        }
        retained.add(update);
        return String.join("; ", retained);
    }

    private static boolean isAutonomyPreferenceKey(String key) {
        String normalized = normalizeKey(key);
        return normalized.equals("behavior.autonomy")
                || normalized.equals("behavior_autonomy")
                || normalized.equals("autonomy");
    }

    private static boolean isAutonomyStyleSegment(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).trim();
        return normalized.startsWith("autonomy_off:")
                || normalized.startsWith("quiet:")
                || normalized.startsWith("danger_only:")
                || normalized.startsWith("balanced:")
                || normalized.startsWith("proactive:")
                || normalized.startsWith("social:")
                || normalized.startsWith("guardian:");
    }

    private static String valueIfKey(String key, String value, String... expectedKeys) {
        if (value == null || value.isBlank()) {
            return "";
        }
        for (String expectedKey : expectedKeys) {
            if (key.equals(expectedKey)) {
                return value;
            }
        }
        return "";
    }

    private static String valueIfAction(String actionName, String value, String... expectedActions) {
        if (value == null || value.isBlank()) {
            return "";
        }
        for (String expectedAction : expectedActions) {
            if (actionName.equals(expectedAction)) {
                return value;
            }
        }
        return "";
    }

    private static String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        return key.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static String limitText(String value, int maxLength) {
        String text = firstNonBlank(value, "");
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength).trim();
    }

    public static void reportNearby(ServerPlayer player, int radius) {
        AABB box = player.getBoundingBox().inflate(Math.max(4, Math.min(radius, 128)));
        List<EntityDistance> entities = new ArrayList<>();
        for (Entity entity : player.serverLevel().getEntities(player, box, Entity::isAlive)) {
            entities.add(new EntityDistance(entity, entity.distanceTo(player)));
        }

        entities.sort(Comparator.comparingDouble(EntityDistance::distance));
        if (entities.isEmpty()) {
            say(player, "No nearby entities within " + radius + " blocks.");
            return;
        }

        StringBuilder builder = new StringBuilder("Nearby: ");
        for (int i = 0; i < Math.min(entities.size(), 8); i++) {
            EntityDistance item = entities.get(i);
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(item.entity().getName().getString())
                    .append(" (")
                    .append(BuiltInRegistries.ENTITY_TYPE.getKey(item.entity().getType()))
                    .append(", ")
                    .append(Math.round(item.distance()))
                    .append("m)");
        }
        say(player, builder.toString());
    }

    public static void reportTaskStatus(ServerPlayer player, String modelMessage) {
        JsonObject npcState = NpcManager.describeFor(player);
        JsonObject feedback = TaskFeedback.snapshotJson(player, npcState);
        String taskName = feedback.has("activeTaskName") ? feedback.get("activeTaskName").getAsString() : "idle";
        int stepsDone = feedback.has("taskStepsDone") ? feedback.get("taskStepsDone").getAsInt() : 0;
        boolean spawned = feedback.has("spawned") && feedback.get("spawned").getAsBoolean();
        boolean paused = feedback.has("paused") && feedback.get("paused").getAsBoolean();

        StringBuilder builder = new StringBuilder();
        if (!modelMessage.isBlank()) {
            builder.append(modelMessage).append(" ");
        }
        builder.append("Task status: ")
                .append(spawned ? taskName : "no NPC spawned")
                .append(", steps=")
                .append(stepsDone);

        if (paused) {
            int pauseSeconds = feedback.has("taskPauseSeconds") ? feedback.get("taskPauseSeconds").getAsInt() : 0;
            builder.append(", paused=").append(pauseSeconds).append("s");
        }

        if (feedback.has("taskSearchRemainingSeconds")) {
            int remaining = feedback.get("taskSearchRemainingSeconds").getAsInt();
            if (remaining > 0) {
                builder.append(", searchRemaining=").append(remaining).append("s");
            }
        }

        builder.append(".");

        if (feedback.has("recentEvents") && feedback.get("recentEvents").isJsonArray() && feedback.getAsJsonArray("recentEvents").size() > 0) {
            JsonObject event = feedback.getAsJsonArray("recentEvents").get(0).getAsJsonObject();
            builder.append(" Latest: ")
                    .append(event.has("code") ? event.get("code").getAsString() : "event")
                    .append(" - ")
                    .append(event.has("message") ? event.get("message").getAsString() : "");
        }

        builder.append(" ").append(PlanManager.statusLineFor(player));

        say(player, builder.toString());
    }

    public static void reportInventory(ServerPlayer player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(itemId, stack.getCount(), Integer::sum);
        }

        if (counts.isEmpty()) {
            say(player, "Inventory is empty.");
            return;
        }

        StringBuilder builder = new StringBuilder("Inventory: ");
        int index = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append(" x").append(entry.getValue());
            index++;
            if (index >= 16) {
                builder.append(", ...");
                break;
            }
        }
        say(player, builder.toString());
    }

    private static void say(ServerPlayer player, String message) {
        NpcChat.say(player, message);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record EntityDistance(Entity entity, double distance) {
    }
}
