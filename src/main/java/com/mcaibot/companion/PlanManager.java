package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class PlanManager {
    private static final String DATA_NAME = "mc_ai_companion_complex_plans";
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_BLOCKED = "blocked";
    private static final String STATUS_DONE = "done";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String TASK_HARVEST_LOGS = "harvest_logs";
    private static final String TASK_MINE_ORES = "mine_nearby_ore";
    private static final String TASK_GATHER_STONE = "gather_stone";
    private static final String TASK_COLLECT_ITEMS = "collect_items";
    private static final String TASK_BUILD_BASIC_HOUSE = "build_basic_house";
    private static final String TASK_BUILD_LARGE_HOUSE = "build_large_house";
    private static final String TASK_REPAIR_STRUCTURE = "repair_structure";
    private static final int AUTO_ADVANCE_INTERVAL_TICKS = 80;
    private static final int MAX_BUILD_MATERIAL_GATHER_CYCLES = 8;
    private static final int MAX_STAGE_LAUNCH_ATTEMPTS = 3;

    private static final SavedData.Factory<PlanSavedData> FACTORY = new SavedData.Factory<>(
            PlanSavedData::new,
            PlanSavedData::load
    );
    private static long tickCounter;

    private PlanManager() {
    }

    public static void startPlan(ServerPlayer player, BridgeDecision decision, boolean runFirstStage) {
        BridgeDecision.Action action = decision.action();
        String message = firstNonBlank(action.message(), decision.reply(), "");
        String requestedGoal = TaskGraphPlanner.requestedGoal(decision, directStageGoal(action.name()));
        PlanSkillLibrary.PlanBlueprint blueprint = PlanSkillLibrary.blueprintFor(requestedGoal, message);
        PlanSavedData data = data(player);
        PlanState plan = PlanState.create(player, blueprint, message, action);
        plan.executionAuthorized = runFirstStage;

        if (blueprint.stages().isEmpty()) {
            plan.status = STATUS_BLOCKED;
            plan.blockedCode = "UNKNOWN_GOAL";
            plan.blockedReason = "I do not have an execution skill for that goal yet. Supported skills: "
                    + PlanSkillLibrary.supportedSkillsSummary() + ".";
        }

        data.plans.put(player.getUUID(), plan);
        data.setDirty();

        TaskFeedback.info(player, NpcManager.activeNpcMob(player.getServer()), "complex_plan", "PLAN_SAVED",
                "Saved plan " + plan.goal + " with " + plan.stages.size() + " stage(s).");

        if (plan.status.equals(STATUS_BLOCKED)) {
            say(player, "Plan blocked: " + plan.blockedReason);
            return;
        }

        say(player, "Plan saved: " + plan.goal + ". Stages: " + String.join(" -> ", plan.stages)
                + ". Next: " + PlanSkillLibrary.stageNextStep(plan.currentStageName()) + ".");
        if (runFirstStage) {
            executeNextTaskGraphNode(player);
        }
    }

    public static void proposeOrSay(ServerPlayer player, BridgeDecision decision) {
        BridgeDecision.Action action = decision.action();
        String message = firstNonBlank(action.message(), decision.reply(), "");
        PlanSkillLibrary.PlanBlueprint blueprint = PlanSkillLibrary.blueprintFor(firstNonBlank(action.value(), action.key(), ""), message);
        if (blueprint.stages().isEmpty()) {
            say(player, firstNonBlank(action.message(), decision.reply(), "I should plan this as a multi-step task before acting."));
            return;
        }

        startPlan(player, decision, false);
    }

    public static void continuePlan(ServerPlayer player) {
        continuePlan(player, false, true);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        if (tickCounter % AUTO_ADVANCE_INTERVAL_TICKS != 0) {
            return;
        }

        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            PlanState plan = data(player).plans.get(player.getUUID());
            if (plan == null
                    || plan.status.equals(STATUS_DONE)
                    || plan.status.equals(STATUS_CANCELLED)
                    || plan.status.equals(STATUS_BLOCKED)) {
                continue;
            }
            if (plan.status.equals(STATUS_RUNNING) || (plan.status.equals(STATUS_ACTIVE) && plan.executionAuthorized)) {
                continuePlan(player, true, false);
            }
        }
    }

    private static void continuePlan(ServerPlayer player, boolean quiet, boolean authorizeExecution) {
        PlanSavedData data = data(player);
        PlanState plan = data.plans.get(player.getUUID());
        if (plan == null) {
            if (!quiet) {
                say(player, "No saved complex plan. Start one with a supported goal: " + PlanSkillLibrary.supportedSkillsSummary() + ".");
            }
            return;
        }
        if (authorizeExecution) {
            plan.executionAuthorized = true;
            touch(data, plan);
        }

        if (plan.status.equals(STATUS_DONE)) {
            if (!quiet) {
                say(player, statusLine(plan) + " It is already complete.");
            }
            return;
        }
        if (plan.status.equals(STATUS_CANCELLED)) {
            if (!quiet) {
                say(player, statusLine(plan) + " It was cancelled; start a new plan if needed.");
            }
            return;
        }
        if (plan.stages.isEmpty()) {
            plan.status = STATUS_BLOCKED;
            plan.blockedCode = "UNKNOWN_GOAL";
            plan.blockedReason = "No supported stages are attached to this plan.";
            touch(data, plan);
            if (!quiet) {
                say(player, statusLine(plan));
            }
            return;
        }

        if (plan.status.equals(STATUS_BLOCKED) && authorizeExecution) {
            plan.status = STATUS_ACTIVE;
            plan.blockedCode = "";
            plan.blockedReason = "";
            plan.lastAction = "Retrying blocked stage after player requested continue.";
            plan.stageLaunchAttempts = 0;
            clearRunningStage(plan);
            touch(data, plan);
            if (!quiet) {
                say(player, "Retrying saved plan from stage " + stageProgress(plan) + ".");
            }
        }

        if (plan.status.equals(STATUS_RUNNING)) {
            RunningStageResult result = reconcileRunningStage(player, data, plan, quiet);
            if (result == RunningStageResult.WAITING || result == RunningStageResult.BLOCKED) {
                return;
            }

            if (result == RunningStageResult.ADVANCE) {
                String completed = plan.currentStageName();
                plan.completedStages.add(completed);
                plan.currentStage++;
                plan.status = STATUS_ACTIVE;
                plan.blockedCode = "";
                plan.blockedReason = "";
                clearRunningStage(plan);
                plan.stageLaunchAttempts = 0;
                TaskFeedback.info(player, NpcManager.activeNpcMob(player.getServer()), "complex_plan", "STAGE_DONE",
                        "Completed stage " + completed + ".");
            }
        }

        if (plan.currentStage >= plan.stages.size()) {
            plan.status = STATUS_DONE;
            plan.lastAction = "All planned stages complete.";
            touch(data, plan);
            say(player, "Plan complete: " + plan.goal + ".");
            TaskFeedback.info(player, NpcManager.activeNpcMob(player.getServer()), "complex_plan", "PLAN_DONE",
                    "Completed plan " + plan.goal + ".");
            return;
        }

        String stage = plan.currentStageName();
        PlanSkillLibrary.StageAssessment assessment = PlanSkillLibrary.assess(player, snapshot(plan), stage);
        if (!assessment.ready()) {
            plan.status = STATUS_BLOCKED;
            plan.blockedCode = assessment.code();
            plan.blockedReason = assessment.message();
            plan.lastAction = "Blocked before " + stage + ".";
            touch(data, plan);
            say(player, statusLine(plan));
            TaskFeedback.warn(player, NpcManager.activeNpcMob(player.getServer()), "complex_plan", assessment.code(), assessment.message());
            return;
        }

        plan.status = STATUS_RUNNING;
        plan.blockedCode = "";
        plan.blockedReason = "";
        plan.lastAction = assessment.message();
        touch(data, plan);
        runStage(player, data, plan, stage);
    }

    public static void reportPlan(ServerPlayer player) {
        PlanState plan = data(player).plans.get(player.getUUID());
        if (plan == null) {
            say(player, "No saved complex plan. Supported skills: " + PlanSkillLibrary.supportedSkillsSummary() + ".");
            return;
        }
        say(player, statusLine(plan));
    }

    public static void cancelPlan(ServerPlayer player) {
        PlanSavedData data = data(player);
        PlanState plan = data.plans.get(player.getUUID());
        if (plan == null) {
            say(player, "No saved complex plan to cancel.");
            return;
        }

        plan.status = STATUS_CANCELLED;
        plan.blockedCode = "";
        plan.blockedReason = "";
        plan.lastAction = "Cancelled by player request.";
        touch(data, plan);
        say(player, "Cancelled plan: " + plan.goal + ".");
    }

    public static JsonObject snapshotFor(ServerPlayer player) {
        PlanState plan = data(player).plans.get(player.getUUID());
        JsonObject root = new JsonObject();
        root.addProperty("supportedSkills", PlanSkillLibrary.supportedSkillsSummary());
        if (plan == null) {
            root.addProperty("active", false);
            root.addProperty("status", "none");
            root.add("taskGraph", TaskGraph.empty("").toJson());
            return root;
        }

        PlanSnapshot snapshot = snapshot(plan);
        root.addProperty("active", !plan.status.equals(STATUS_DONE) && !plan.status.equals(STATUS_CANCELLED));
        root.addProperty("goal", snapshot.goal());
        root.addProperty("status", snapshot.status());
        root.addProperty("currentStage", snapshot.currentStage());
        root.addProperty("currentStageIndex", snapshot.currentStageIndex());
        root.addProperty("stageCount", snapshot.stageCount());
        root.addProperty("nextStep", snapshot.nextStep());
        root.addProperty("blockedCode", snapshot.blockedCode());
        root.addProperty("blockedReason", snapshot.blockedReason());
        root.addProperty("lastAction", snapshot.lastAction());
        root.addProperty("lastTaskGraphNodeId", plan.lastTaskGraphNodeId);
        root.addProperty("createdAtMillis", snapshot.createdAtMillis());
        root.addProperty("updatedAtMillis", snapshot.updatedAtMillis());
        root.addProperty("executionAuthorized", plan.executionAuthorized);
        root.addProperty("awaitingExecutionApproval", plan.status.equals(STATUS_ACTIVE) && !plan.executionAuthorized);
        root.addProperty("runningTaskName", plan.runningTaskName);
        root.addProperty("runningObservedBusy", plan.runningObservedBusy);
        root.addProperty("stageStartedAtMillis", plan.stageStartedAtMillis);
        root.addProperty("stageLaunchAttempts", plan.stageLaunchAttempts);
        root.addProperty("maxStageLaunchAttempts", MAX_STAGE_LAUNCH_ATTEMPTS);
        root.addProperty("survivesPlayerDeath", true);
        root.addProperty("storage", "overworld_saved_data");
        root.add("lastActionCall", jsonObjectFromString(plan.lastActionCallJson));
        root.add("lastActionResult", jsonObjectFromString(plan.lastActionResultJson));
        root.add("resources", ResourceAssessment.snapshotFor(player));
        if (plan.hasBuildAnchor) {
            root.add("buildAnchor", buildAnchorJson(plan));
        }

        if (snapshot.hasTargetPosition()) {
            JsonObject target = new JsonObject();
            target.addProperty("x", snapshot.targetX());
            target.addProperty("y", snapshot.targetY());
            target.addProperty("z", snapshot.targetZ());
            root.add("targetPosition", target);
        }

        JsonArray stages = new JsonArray();
        for (int index = 0; index < plan.stages.size(); index++) {
            String stage = plan.stages.get(index);
            JsonObject stageJson = new JsonObject();
            stageJson.addProperty("name", stage);
            stageJson.addProperty("displayName", PlanSkillLibrary.stageDisplayName(stage));
            stageJson.addProperty("nextStep", PlanSkillLibrary.stageNextStep(stage));
            stageJson.addProperty("status", stageStatus(plan, index));
            if (index >= plan.currentStage && !plan.status.equals(STATUS_DONE) && !plan.status.equals(STATUS_CANCELLED)) {
                PlanSkillLibrary.StageAssessment assessment = PlanSkillLibrary.assess(player, snapshot, stage);
                stageJson.addProperty("ready", assessment.ready());
                stageJson.addProperty("assessmentCode", assessment.code());
                stageJson.addProperty("assessment", assessment.message());
            }
            stages.add(stageJson);
        }
        root.add("stages", stages);
        root.add("taskGraph", TaskGraphPlanner.fromPlanSnapshot(snapshot).toJson());
        return root;
    }

    public static JsonObject feedbackFor(ServerPlayer player) {
        PlanState plan = data(player).plans.get(player.getUUID());
        JsonObject root = new JsonObject();
        if (plan == null) {
            root.addProperty("active", false);
            root.addProperty("status", "none");
            root.addProperty("retryable", false);
            root.addProperty("suggestedNextAction", "start_plan");
            root.addProperty("summary", "No saved complex plan.");
            return root;
        }

        boolean active = !plan.status.equals(STATUS_DONE) && !plan.status.equals(STATUS_CANCELLED);
        boolean retryable = plan.status.equals(STATUS_BLOCKED) && !plan.stages.isEmpty();
        root.addProperty("active", active);
        root.addProperty("goal", plan.goal);
        root.addProperty("status", plan.status);
        root.addProperty("currentStage", plan.currentStageName());
        root.addProperty("currentStageIndex", plan.currentStage);
        root.addProperty("stageCount", plan.stages.size());
        root.addProperty("blockerCode", plan.blockedCode);
        root.addProperty("blocker", plan.blockedReason);
        root.addProperty("lastAction", plan.lastAction);
        root.addProperty("lastTaskGraphNodeId", plan.lastTaskGraphNodeId);
        root.addProperty("retryable", retryable);
        root.addProperty("retryAction", retryable ? "continue_plan" : "");
        root.addProperty("suggestedNextAction", suggestedNextAction(plan));
        root.addProperty("question", suggestedQuestion(plan));
        root.addProperty("stageLaunchAttempts", plan.stageLaunchAttempts);
        root.addProperty("maxStageLaunchAttempts", MAX_STAGE_LAUNCH_ATTEMPTS);
        root.addProperty("survivesPlayerDeath", true);
        root.add("lastActionCall", jsonObjectFromString(plan.lastActionCallJson));
        root.add("lastActionResult", jsonObjectFromString(plan.lastActionResultJson));
        if (plan.hasBuildAnchor) {
            root.add("buildAnchor", buildAnchorJson(plan));
        }

        JsonArray completed = new JsonArray();
        for (String stage : plan.completedStages) {
            completed.add(stage);
            if (completed.size() >= 16) {
                break;
            }
        }
        root.add("completedStages", completed);
        return root;
    }

    public static JsonObject executeNextTaskGraphNode(ServerPlayer player) {
        PlanSavedData data = data(player);
        PlanState plan = data.plans.get(player.getUUID());
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("name", "taskgraph_next");
        root.addProperty("player", player.getGameProfile().getName());

        if (plan == null) {
            ActionResult result = ActionResult.blocked("NO_SAVED_PLAN",
                    "No saved complex plan exists for this player.",
                    "Start or save a plan before executing the next TaskGraph node.");
            root.add("actionResult", result.toJson());
            root.add("taskGraph", TaskGraph.empty("").toJson());
            return root;
        }

        plan.executionAuthorized = true;
        touch(data, plan);

        TaskGraph beforeGraph = TaskGraphPlanner.fromPlanSnapshot(snapshot(plan));
        root.add("taskGraphBefore", beforeGraph.toJson());

        ActionResult result;
        JsonObject node = TaskGraphPlanner.currentNode(beforeGraph);
        ActionCall call = ActionCall.empty();

        if (plan.status.equals(STATUS_DONE)) {
            result = ActionResult.success("PLAN_DONE", "The saved TaskGraph is already complete.")
                    .withEffect("planStatus", plan.status);
        } else if (plan.status.equals(STATUS_CANCELLED)) {
            result = ActionResult.blocked("PLAN_CANCELLED",
                    "The saved TaskGraph was cancelled.",
                    "Start a new plan if the goal is still needed.");
        } else if (plan.stages.isEmpty()) {
            plan.status = STATUS_BLOCKED;
            plan.blockedCode = "UNKNOWN_GOAL";
            plan.blockedReason = "No supported stages are attached to this plan.";
            touch(data, plan);
            result = ActionResult.blocked(plan.blockedCode, plan.blockedReason,
                    "Ask the model to create a TaskGraph with known action primitives or clarify the goal.");
        } else if (plan.status.equals(STATUS_BLOCKED)) {
            result = ActionResult.blocked(firstNonBlank(plan.blockedCode, "PLAN_BLOCKED"),
                    firstNonBlank(plan.blockedReason, "The plan is blocked."),
                    suggestedQuestion(plan));
        } else if (plan.status.equals(STATUS_RUNNING)) {
            result = reconcileTaskGraphRunningNode(player, data, plan, node);
        } else if (node.isEmpty()) {
            result = ActionResult.blocked("NO_READY_NODE",
                    "No ready TaskGraph node was found for the saved plan.",
                    "Inspect the saved TaskGraph dependencies or ask the model to repair node statuses.");
        } else {
            result = executeReadyTaskGraphNode(player, data, plan, beforeGraph, node);
            call = ActionCall.fromJson(jsonObjectFromString(plan.lastActionCallJson));
        }

        if (!call.isPresent() && !plan.lastActionCallJson.isBlank()) {
            call = ActionCall.fromJson(jsonObjectFromString(plan.lastActionCallJson));
        }
        if (!node.isEmpty() && plan.lastTaskGraphNodeId.isBlank()) {
            plan.lastTaskGraphNodeId = stringValue(node, "id", "");
            touch(data, plan);
        }

        String resultNodeId = firstNonBlank(plan.lastTaskGraphNodeId, stringValue(node, "id", ""));
        result = result.withEffect("taskGraphId", beforeGraph.id())
                .withEffect("nodeId", resultNodeId)
                .withEffect("planGoal", plan.goal)
                .withEffect("planStatus", plan.status);
        TaskGraph resultGraph = TaskGraphPlanner.applyActionResult(beforeGraph, resultNodeId, result);
        TaskGraph savedGraph = TaskGraphPlanner.fromPlanSnapshot(snapshot(plan));
        recordLastGraphResult(player, data, plan, node, call, result);
        root.add("currentNode", node);
        root.add("actionCall", call.toJson());
        root.add("actionResult", result.toJson());
        root.add("taskGraphResult", resultGraph.toJson());
        root.add("taskGraph", savedGraph.toJson());
        root.add("planFeedback", feedbackFor(player));
        root.add("npc", NpcManager.describeFor(player));
        return root;
    }

    static String statusLineFor(ServerPlayer player) {
        PlanState plan = data(player).plans.get(player.getUUID());
        return plan == null ? "Complex plan: none." : statusLine(plan);
    }

    private static ActionResult executeReadyTaskGraphNode(ServerPlayer player, PlanSavedData data, PlanState plan, TaskGraph graph, JsonObject node) {
        String stage = plan.currentStageName();
        PlanSkillLibrary.StageAssessment assessment = PlanSkillLibrary.assess(player, snapshot(plan), stage);
        if (!assessment.ready()) {
            markBlocked(player, data, plan, assessment.code(), assessment.message());
            return ActionResult.blocked(assessment.code(), assessment.message(), suggestedQuestion(plan));
        }

        ActionCall call = TaskGraphPlanner.actionCallForNode(graph, node, snapshot(plan));
        String nodeId = stringValue(node, "id", "");
        plan.lastTaskGraphNodeId = nodeId;
        plan.lastActionCallJson = call.toJson().toString();

        String expectedTask = expectedTaskForAction(call.name());
        if (!expectedTask.isBlank()) {
            if (!ensureCanLaunchLongStage(player, data, plan, expectedTask)) {
                return ActionResult.blocked(firstNonBlank(plan.blockedCode, "STAGE_RETRY_LIMIT"),
                        firstNonBlank(plan.blockedReason, "The TaskGraph node hit the launch retry limit."),
                        suggestedQuestion(plan));
            }
            prepareLongStage(plan, expectedTask);
            touch(data, plan);
        }

        ActionResult result = ActionPrimitiveRegistry.execute(player, call);
        if (result.isSuccess()) {
            finishImmediateStage(player, data, plan);
            plan.lastAction = "TaskGraph node " + nodeId + " completed action " + call.name() + ": " + result.message();
            touch(data, plan);
            return result.withEffect("nodeCompleted", true);
        }

        if (result.isStarted()) {
            if (expectedTask.isBlank()) {
                finishImmediateStage(player, data, plan);
                plan.lastAction = "TaskGraph node " + nodeId + " executed one-shot action " + call.name() + ": " + result.message();
                touch(data, plan);
                return new ActionResult("success", result.code(), result.message(), result.effects(), result.observations(), new JsonArray(), false, result.suggestedRepairs())
                        .withEffect("nodeCompleted", true)
                        .withEffect("treatedAsImmediate", true);
            }

            plan.status = STATUS_RUNNING;
            plan.blockedCode = "";
            plan.blockedReason = "";
            plan.lastAction = "TaskGraph node " + nodeId + " started runtime task " + expectedTask + ".";
            touch(data, plan);
            verifyLongStageStarted(player, data, plan, expectedTask);
            if (plan.status.equals(STATUS_BLOCKED)) {
                return ActionResult.blocked(firstNonBlank(plan.blockedCode, "STAGE_DID_NOT_START"),
                        firstNonBlank(plan.blockedReason, "The TaskGraph node did not start."),
                        suggestedQuestion(plan));
            }
            return result.withEffect("runningTaskName", expectedTask)
                    .withEffect("nodeRunning", true);
        }

        markBlocked(player, data, plan, result.code(), result.message());
        return result;
    }

    private static ActionResult reconcileTaskGraphRunningNode(ServerPlayer player, PlanSavedData data, PlanState plan, JsonObject node) {
        RunningStageResult running = reconcileRunningStage(player, data, plan, true);
        String nodeId = stringValue(node, "id", firstNonBlank(plan.lastTaskGraphNodeId, ""));
        if (running == RunningStageResult.WAITING) {
            return ActionResult.started("TASK_RUNNING", firstNonBlank(plan.lastAction, "TaskGraph node is still running."))
                    .withEffect("nodeId", nodeId)
                    .withEffect("runningTaskName", plan.runningTaskName);
        }
        if (running == RunningStageResult.ADVANCE) {
            String completed = plan.currentStageName();
            plan.completedStages.add(completed);
            plan.currentStage++;
            plan.status = STATUS_ACTIVE;
            plan.blockedCode = "";
            plan.blockedReason = "";
            clearRunningStage(plan);
            plan.stageLaunchAttempts = 0;

            if (plan.currentStage >= plan.stages.size()) {
                plan.status = STATUS_DONE;
                plan.lastAction = "TaskGraph node " + nodeId + " completed the final stage.";
                touch(data, plan);
                say(player, "Plan complete: " + plan.goal + ".");
                TaskFeedback.info(player, NpcManager.activeNpcMob(player.getServer()), "complex_plan", "PLAN_DONE",
                        "Completed plan " + plan.goal + ".");
                return ActionResult.success("PLAN_DONE", "TaskGraph completed all planned stages.")
                        .withEffect("nodeCompleted", true)
                        .withEffect("planCompleted", true);
            }

            plan.lastAction = "TaskGraph node " + nodeId + " completed stage " + completed + "; next stage is " + plan.currentStageName() + ".";
            touch(data, plan);
            TaskFeedback.info(player, NpcManager.activeNpcMob(player.getServer()), "complex_plan", "STAGE_DONE",
                    "Completed stage " + completed + ".");
            return ActionResult.success("NODE_COMPLETE", plan.lastAction)
                    .withEffect("nodeCompleted", true)
                    .withEffect("nextStage", plan.currentStageName());
        }
        if (running == RunningStageResult.RETRY) {
            return ActionResult.blocked("NODE_RETRY_READY",
                    firstNonBlank(plan.lastAction, "The previous runtime task stopped without completion feedback; retry the same TaskGraph node."),
                    "Call taskgraph_next again to relaunch the same node, or inspect recent task feedback before replanning.");
        }
        return ActionResult.blocked(firstNonBlank(plan.blockedCode, "PLAN_BLOCKED"),
                firstNonBlank(plan.blockedReason, "The running TaskGraph node is blocked."),
                suggestedQuestion(plan));
    }

    private static void runStage(ServerPlayer player, PlanSavedData data, PlanState plan, String stage) {
        switch (stage) {
            case PlanSkillLibrary.PREPARE_BASIC_TOOLS -> {
                boolean requireAxe = plan.goal.equals(PlanSkillLibrary.PREPARE_BASIC_TOOLS)
                        || plan.goal.equals(PlanSkillLibrary.GATHER_MATERIALS)
                        || plan.goal.equals(PlanSkillLibrary.GATHER_WOOD)
                        || plan.goal.equals(PlanSkillLibrary.BUILD_BASIC_SHELTER)
                        || plan.goal.equals(PlanSkillLibrary.BUILD_LARGE_HOUSE);
                boolean requirePickaxe = plan.goal.equals(PlanSkillLibrary.PREPARE_BASIC_TOOLS)
                        || plan.goal.equals(PlanSkillLibrary.GATHER_STONE)
                        || plan.goal.equals(PlanSkillLibrary.MINE_RESOURCES)
                        || plan.goal.equals(PlanSkillLibrary.CRAFT_STONE_AXE)
                        || plan.goal.equals(PlanSkillLibrary.CRAFT_STONE_PICKAXE);
                boolean ready = NpcManager.prepareBasicTools(player, requireAxe, requirePickaxe);
                if (!ready) {
                    markBlocked(player, data, plan, "BASIC_TOOLS_NOT_READY",
                            "Could not prepare the required basic tool from NPC storage or approved nearby containers.");
                    return;
                }
                plan.lastAction = "Prepared available basic tools from NPC storage or approved nearby container materials.";
                finishImmediateStage(player, data, plan);
            }
            case PlanSkillLibrary.PREPARE_BUILD_MATERIALS -> {
                ResourceAssessment.Snapshot resources = ResourceAssessment.snapshot(player);
                int requiredBlocks = ResourceAssessment.BASIC_SHELTER_BLOCKS;
                if (plan.goal.equals(PlanSkillLibrary.BUILD_BASIC_SHELTER)) {
                    ensureBuildAnchor(player, plan);
                    requiredBlocks = remainingBuildBlocks(player, plan);
                }
                if (resources.placeableBlocks() < requiredBlocks
                        && resources.placeableBlocksAfterPlankConversion() >= requiredBlocks) {
                    boolean converted = NpcManager.craftPlanksForBuild(player, requiredBlocks);
                    resources = ResourceAssessment.snapshot(player);
                    if (converted || resources.placeableBlocks() >= requiredBlocks) {
                        plan.lastAction = "Converted available logs into planks for the build; placeable blocks="
                                + resources.placeableBlocks() + "/" + requiredBlocks + ".";
                    } else if (resources.placeableBlocksAfterPlankConversion() >= requiredBlocks) {
                        markBlocked(player, data, plan, "LOG_CONVERSION_FAILED",
                                "Logs are sufficient after conversion, but I could not convert or store enough planks for the build.");
                        return;
                    }
                }
                if (resources.placeableBlocks() < requiredBlocks) {
                    int missingBlocks = Math.max(0, requiredBlocks - resources.placeableBlocksAfterPlankConversion());
                    if (plan.goal.equals(PlanSkillLibrary.BUILD_BASIC_SHELTER) && queueMoreBuildMaterialGathering(player, data, plan, resources, requiredBlocks, missingBlocks)) {
                        return;
                    }
                    markBlocked(player, data, plan, "NEED_BUILDING_BLOCKS",
                            "Need " + missingBlocks
                                    + " more placeable blocks for the basic shelter blueprint.");
                    return;
                }
                ResourceAssessment.report(player);
                plan.lastAction = "Build materials ready for the basic shelter blueprint; remaining blocks needed=" + requiredBlocks + ".";
                finishImmediateStage(player, data, plan);
            }
            case PlanSkillLibrary.GATHER_WOOD -> {
                if (plan.goal.equals(PlanSkillLibrary.BUILD_BASIC_SHELTER)
                        && ResourceAssessment.snapshot(player).basicShelterReady()) {
                    plan.lastAction = "Skipped wood gathering because the basic shelter already has enough placeable blocks.";
                    finishImmediateStage(player, data, plan);
                    return;
                }
                if (!ensureCanLaunchLongStage(player, data, plan, TASK_HARVEST_LOGS)) {
                    return;
                }
                prepareLongStage(plan, TASK_HARVEST_LOGS);
                touch(data, plan);
                say(player, "Plan stage " + stageProgress(plan) + ": gathering wood. I will use available tools/materials and report if blocked.");
                NpcManager.harvestLogs(player, plan.radius, plan.durationSeconds);
                verifyLongStageStarted(player, data, plan, TASK_HARVEST_LOGS);
            }
            case PlanSkillLibrary.GATHER_STONE -> {
                if ((plan.goal.equals(PlanSkillLibrary.CRAFT_STONE_AXE)
                        || plan.goal.equals(PlanSkillLibrary.CRAFT_STONE_PICKAXE))
                        && ResourceAssessment.snapshot(player).cobblestoneLike() >= 3) {
                    plan.lastAction = "Skipped stone gathering because enough cobblestone-like material is already available.";
                    finishImmediateStage(player, data, plan);
                    return;
                }
                if (!ensureCanLaunchLongStage(player, data, plan, TASK_GATHER_STONE)) {
                    return;
                }
                prepareLongStage(plan, TASK_GATHER_STONE);
                touch(data, plan);
                say(player, "Plan stage " + stageProgress(plan) + ": gathering stone. I will use a real pickaxe and collect the drops next.");
                NpcManager.gatherStone(player, plan.radius, 3);
                verifyLongStageStarted(player, data, plan, TASK_GATHER_STONE);
            }
            case PlanSkillLibrary.MINE_RESOURCES -> {
                if (!ensureCanLaunchLongStage(player, data, plan, TASK_MINE_ORES)) {
                    return;
                }
                prepareLongStage(plan, TASK_MINE_ORES);
                touch(data, plan);
                say(player, "Plan stage " + stageProgress(plan) + ": mining exposed nearby resources. I will use a real pickaxe and collect the drops next.");
                NpcManager.mineOres(player, plan.radius);
                verifyLongStageStarted(player, data, plan, TASK_MINE_ORES);
            }
            case PlanSkillLibrary.COLLECT_DROPS -> {
                if (!ensureCanLaunchLongStage(player, data, plan, TASK_COLLECT_ITEMS)) {
                    return;
                }
                prepareLongStage(plan, TASK_COLLECT_ITEMS);
                touch(data, plan);
                say(player, "Plan stage " + stageProgress(plan) + ": collecting nearby drops before checking materials.");
                NpcManager.collectItems(player, plan.radius);
                verifyLongStageStarted(player, data, plan, TASK_COLLECT_ITEMS);
            }
            case PlanSkillLibrary.CRAFT_AXE -> {
                if (ResourceAssessment.snapshot(player).hasAxe()) {
                    plan.lastAction = "Skipped axe crafting because the NPC already has a usable axe.";
                    finishImmediateStage(player, data, plan);
                    return;
                }
                boolean crafted = NpcManager.craftItem(player, "axe", 1);
                if (!crafted) {
                    markBlocked(player, data, plan, "AXE_CRAFT_FAILED",
                            "Could not craft an axe from current NPC storage or approved nearby containers.");
                    return;
                }
                finishImmediateStage(player, data, plan);
            }
            case PlanSkillLibrary.CRAFT_PICKAXE -> {
                if (ResourceAssessment.snapshot(player).hasPickaxe()) {
                    plan.lastAction = "Skipped pickaxe crafting because the NPC already has a usable pickaxe.";
                    finishImmediateStage(player, data, plan);
                    return;
                }
                boolean crafted = NpcManager.craftItem(player, "pickaxe", 1);
                if (!crafted) {
                    markBlocked(player, data, plan, "PICKAXE_CRAFT_FAILED",
                            "Could not craft a pickaxe from current NPC storage or approved nearby containers.");
                    return;
                }
                finishImmediateStage(player, data, plan);
            }
            case PlanSkillLibrary.CRAFT_PLANKS -> {
                boolean crafted = NpcManager.craftItem(player, "planks", 0);
                if (!crafted) {
                    markBlocked(player, data, plan, "PLANKS_CRAFT_FAILED",
                            "Could not convert current NPC logs into planks.");
                    return;
                }
                finishImmediateStage(player, data, plan);
            }
            case PlanSkillLibrary.CRAFT_STICKS -> {
                boolean crafted = NpcManager.craftItem(player, "sticks", 4);
                if (!crafted) {
                    markBlocked(player, data, plan, "STICKS_CRAFT_FAILED",
                            "Could not craft sticks from current NPC storage or approved nearby containers.");
                    return;
                }
                finishImmediateStage(player, data, plan);
            }
            case PlanSkillLibrary.CRAFT_STONE_AXE -> {
                boolean crafted = NpcManager.craftItem(player, "stone_axe", 1);
                if (!crafted) {
                    markBlocked(player, data, plan, "STONE_AXE_CRAFT_FAILED",
                            "Could not craft a stone axe from current NPC storage or approved nearby containers.");
                    return;
                }
                finishImmediateStage(player, data, plan);
            }
            case PlanSkillLibrary.CRAFT_STONE_PICKAXE -> {
                boolean crafted = NpcManager.craftItem(player, "stone_pickaxe", 1);
                if (!crafted) {
                    markBlocked(player, data, plan, "STONE_PICKAXE_CRAFT_FAILED",
                            "Could not craft a stone pickaxe from current NPC storage or approved nearby containers.");
                    return;
                }
                finishImmediateStage(player, data, plan);
            }
            case PlanSkillLibrary.BUILD_BASIC_SHELTER -> {
                if (!ensureCanLaunchLongStage(player, data, plan, TASK_BUILD_BASIC_HOUSE)) {
                    return;
                }
                ensureBuildAnchor(player, plan);
                prepareLongStage(plan, TASK_BUILD_BASIC_HOUSE);
                touch(data, plan);
                say(player, "Plan stage " + stageProgress(plan) + ": building a basic shelter. I will use NPC storage, gathered blocks, and approved nearby container blocks only.");
                NpcManager.buildBasicHouse(player, plan.buildCenterPos(), plan.buildForward());
                verifyLongStageStarted(player, data, plan, TASK_BUILD_BASIC_HOUSE);
            }
            case PlanSkillLibrary.BUILD_LARGE_HOUSE -> {
                if (!ensureCanLaunchLongStage(player, data, plan, TASK_BUILD_LARGE_HOUSE)) {
                    return;
                }
                ensureBuildAnchor(player, plan);
                prepareLongStage(plan, TASK_BUILD_LARGE_HOUSE);
                touch(data, plan);
                say(player, "Plan stage " + stageProgress(plan) + ": building a larger house. I will use NPC storage, gathered blocks, and approved nearby container blocks only.");
                NpcManager.buildLargeHouse(player, plan.buildCenterPos(), plan.buildForward());
                verifyLongStageStarted(player, data, plan, TASK_BUILD_LARGE_HOUSE);
            }
            case PlanSkillLibrary.REPAIR_STRUCTURE -> {
                if (!ensureCanLaunchLongStage(player, data, plan, TASK_REPAIR_STRUCTURE)) {
                    return;
                }
                prepareLongStage(plan, TASK_REPAIR_STRUCTURE);
                touch(data, plan);
                say(player, "Plan stage " + stageProgress(plan) + ": repairing the nearby structure. I will scan the shell, match wall material, and craft/place a door if the doorway is clear.");
                NpcManager.repairNearbyStructure(player, plan.radius);
                verifyLongStageStarted(player, data, plan, TASK_REPAIR_STRUCTURE);
            }
            case PlanSkillLibrary.EQUIP_GEAR -> {
                NpcManager.autoEquipNow(player);
                finishImmediateStage(player, data, plan);
            }
            case PlanSkillLibrary.DEPOSIT_STORAGE -> {
                NpcManager.depositNpcStorageToNearbyChest(player);
                finishImmediateStage(player, data, plan);
            }
            case PlanSkillLibrary.PROTECT_PLAYER -> {
                ProtectionManager.start(player, plan.radius);
                finishImmediateStage(player, data, plan);
            }
            case PlanSkillLibrary.CREATE_INSPECT -> {
                say(player, "Plan stage " + stageProgress(plan) + ": inspecting Create/Aeronautics content.");
                if (plan.hasTarget) {
                    ModInteractionManager.inspectBlock(player, plan.targetPos());
                } else {
                    ModInteractionManager.reportNearby(player, plan.radius);
                }
                finishImmediateStage(player, data, plan);
            }
            case PlanSkillLibrary.CREATE_WRENCH -> {
                say(player, "Plan stage " + stageProgress(plan) + ": using Create wrench at " + plan.targetX + " " + plan.targetY + " " + plan.targetZ + ".");
                ModInteractionManager.useWrench(player, plan.targetPos());
                finishImmediateStage(player, data, plan);
            }
            default -> {
                plan.status = STATUS_BLOCKED;
                plan.blockedCode = "UNKNOWN_STAGE";
                plan.blockedReason = "No execution skill exists for stage '" + stage + "'.";
                touch(data, plan);
                say(player, statusLine(plan));
            }
        }
    }

    private static void finishImmediateStage(ServerPlayer player, PlanSavedData data, PlanState plan) {
        plan.completedStages.add(plan.currentStageName());
        clearRunningStage(plan);
        plan.stageLaunchAttempts = 0;
        plan.currentStage++;
        if (plan.currentStage >= plan.stages.size()) {
            plan.status = STATUS_DONE;
            plan.lastAction = "Immediate stage completed.";
            touch(data, plan);
            say(player, "Plan complete: " + plan.goal + ".");
            return;
        }

        plan.status = STATUS_ACTIVE;
        plan.lastAction = "Immediate stage completed; ready for next stage.";
        touch(data, plan);
        say(player, "Stage complete. Next: " + PlanSkillLibrary.stageNextStep(plan.currentStageName()) + ".");
    }

    private static RunningStageResult reconcileRunningStage(ServerPlayer player, PlanSavedData data, PlanState plan, boolean quiet) {
        String expectedTask = firstNonBlank(plan.runningTaskName, expectedTaskForStage(plan.currentStageName()));
        if (expectedTask.isBlank()) {
            markBlocked(player, data, plan, "UNKNOWN_RUNNING_TASK", "The saved running stage has no matching NPC task.");
            return RunningStageResult.BLOCKED;
        }

        JsonObject npc = NpcManager.describeFor(player);
        String currentTask = stringValue(npc, "task", "idle");
        boolean paused = boolValue(npc, "taskPaused", false);
        if (paused || currentTask.equals(expectedTask)) {
            plan.runningObservedBusy = true;
            plan.lastAction = paused
                    ? "Stage is paused because the owner is dead, offline, or in another dimension."
                    : "NPC task " + expectedTask + " is still running.";
            touch(data, plan);
            if (!quiet) {
                say(player, statusLine(plan) + " Still executing; continue again after the current NPC task finishes.");
            }
            return RunningStageResult.WAITING;
        }

        if (!currentTask.equals("idle")) {
            markBlocked(player, data, plan, "TASK_CONFLICT", "NPC is busy with " + currentTask + " instead of expected task " + expectedTask + ".");
            return RunningStageResult.BLOCKED;
        }

        if (plan.stageStartedAtMillis <= 0L) {
            plan.status = STATUS_ACTIVE;
            plan.lastAction = "Saved running stage had no launch timestamp; retrying the same stage instead of dropping the plan.";
            clearRunningStage(plan);
            touch(data, plan);
            return RunningStageResult.RETRY;
        }

        EventSummary event = latestEventForTask(player, expectedTask, plan.stageStartedAtMillis);
        if (event != null && event.isCompletion()) {
            plan.lastAction = "Stage completed from feedback " + event.code() + ": " + event.message();
            touch(data, plan);
            return RunningStageResult.ADVANCE;
        }

        if (event != null && event.isTerminalProblem()) {
            if (tryRecoverStageProblem(player, data, plan, event)) {
                return RunningStageResult.RETRY;
            }
            markBlocked(player, data, plan, event.code(), event.message());
            return RunningStageResult.BLOCKED;
        }

        if (plan.runningObservedBusy) {
            plan.status = STATUS_ACTIVE;
            plan.lastAction = "The previous NPC task stopped without completion feedback; retrying the same stage instead of dropping the plan.";
            clearRunningStage(plan);
            touch(data, plan);
            return RunningStageResult.RETRY;
        }

        markBlocked(player, data, plan, "STAGE_DID_NOT_START", "NPC task " + expectedTask + " did not start. Check recent task feedback for missing tools, blocks, or targets.");
        return RunningStageResult.BLOCKED;
    }

    private static void prepareLongStage(PlanState plan, String expectedTask) {
        plan.runningTaskName = expectedTask;
        plan.runningObservedBusy = false;
        plan.stageStartedAtMillis = System.currentTimeMillis();
        plan.stageLaunchAttempts++;
    }

    private static boolean ensureCanLaunchLongStage(ServerPlayer player, PlanSavedData data, PlanState plan, String expectedTask) {
        if (plan.stageLaunchAttempts < MAX_STAGE_LAUNCH_ATTEMPTS) {
            return true;
        }

        markBlocked(player, data, plan, "STAGE_RETRY_LIMIT",
                "Stage " + plan.currentStageName() + " tried to launch " + expectedTask + " "
                        + plan.stageLaunchAttempts + " times without a stable completion signal. I preserved the plan; fix the blocker or ask me to continue to retry.");
        return false;
    }

    private static void verifyLongStageStarted(ServerPlayer player, PlanSavedData data, PlanState plan, String expectedTask) {
        JsonObject npc = NpcManager.describeFor(player);
        String currentTask = stringValue(npc, "task", "idle");
        if (currentTask.equals(expectedTask)) {
            plan.runningObservedBusy = true;
            plan.lastAction = "Started NPC task " + expectedTask + ".";
            touch(data, plan);
            return;
        }

        EventSummary event = latestEventForTask(player, expectedTask, plan.stageStartedAtMillis);
        if (event != null && event.isCompletion()) {
            plan.runningObservedBusy = true;
            plan.lastAction = "NPC task " + expectedTask + " completed immediately: " + event.message();
            touch(data, plan);
            return;
        }
        if (event != null && event.isTerminalProblem()) {
            if (tryRecoverStageProblem(player, data, plan, event)) {
                return;
            }
            markBlocked(player, data, plan, event.code(), event.message());
            return;
        }

        if (!currentTask.equals("idle")) {
            markBlocked(player, data, plan, "TASK_CONFLICT", "NPC started " + currentTask + " instead of expected task " + expectedTask + ".");
            return;
        }

        markBlocked(player, data, plan, "STAGE_DID_NOT_START", "NPC task " + expectedTask + " did not start. I preserved the plan so it can be retried after fixing the blocker.");
    }

    private static void clearRunningStage(PlanState plan) {
        plan.runningTaskName = "";
        plan.runningObservedBusy = false;
        plan.stageStartedAtMillis = 0L;
    }

    private static boolean tryRecoverStageProblem(ServerPlayer player, PlanSavedData data, PlanState plan, EventSummary event) {
        String stage = plan.currentStageName();
        if (stage.equals(PlanSkillLibrary.GATHER_WOOD) && isSearchFailure(event)) {
            WorldKnowledge.KnownPosition knownLogs = WorldKnowledge.nearestResourceHint(player, "logs", 96.0D, 20L * 60L * 30L);
            if (knownLogs == null || plan.stageLaunchAttempts >= MAX_STAGE_LAUNCH_ATTEMPTS) {
                return false;
            }

            plan.status = STATUS_ACTIVE;
            plan.blockedCode = "";
            plan.blockedReason = "";
            plan.durationSeconds = Math.min(300, Math.max(plan.durationSeconds + 60, 150));
            plan.lastAction = "Recovered from " + event.code()
                    + " by retrying gather_wood toward remembered logs at "
                    + knownLogs.x() + " " + knownLogs.y() + " " + knownLogs.z() + ".";
            clearRunningStage(plan);
            touch(data, plan);
            say(player, "I got no nearby logs, but I remember trees at "
                    + knownLogs.x() + " " + knownLogs.y() + " " + knownLogs.z()
                    + ". I will retry and travel there instead of stopping.");
            TaskFeedback.info(player, NpcManager.activeNpcMob(player.getServer()), "complex_plan", "RECOVER_USING_WORLD_MEMORY", plan.lastAction);
            return true;
        }

        return false;
    }

    private static boolean isSearchFailure(EventSummary event) {
        return switch (firstNonBlank(event.code(), "")) {
            case "SEARCH_TIMEOUT", "NO_BREAK_TARGET", "GATHERING_WOOD_NO_LOGS" -> true;
            default -> false;
        };
    }

    private static String expectedTaskForStage(String stage) {
        return switch (stage) {
            case PlanSkillLibrary.GATHER_WOOD -> TASK_HARVEST_LOGS;
            case PlanSkillLibrary.MINE_RESOURCES -> TASK_MINE_ORES;
            case PlanSkillLibrary.GATHER_STONE -> TASK_GATHER_STONE;
            case PlanSkillLibrary.COLLECT_DROPS -> TASK_COLLECT_ITEMS;
            case PlanSkillLibrary.BUILD_BASIC_SHELTER -> TASK_BUILD_BASIC_HOUSE;
            case PlanSkillLibrary.BUILD_LARGE_HOUSE -> TASK_BUILD_LARGE_HOUSE;
            case PlanSkillLibrary.REPAIR_STRUCTURE -> TASK_REPAIR_STRUCTURE;
            default -> "";
        };
    }

    private static String expectedTaskForAction(String action) {
        return switch (normalize(action)) {
            case "harvest_logs", "gather_wood", "wood" -> TASK_HARVEST_LOGS;
            case "mine_nearby_ore", "mine_ore", "mine_resources", "mine" -> TASK_MINE_ORES;
            case "gather_stone", "mine_stone", "gather_cobblestone", "collect_cobblestone" -> TASK_GATHER_STONE;
            case "collect_items", "collect", "collect_drops" -> TASK_COLLECT_ITEMS;
            case "build_basic_house", "build_basic_shelter" -> TASK_BUILD_BASIC_HOUSE;
            case "build_large_house", "large_house" -> TASK_BUILD_LARGE_HOUSE;
            case "repair_structure", "repair_house", "repair_wall", "repair_door" -> TASK_REPAIR_STRUCTURE;
            default -> "";
        };
    }

    private static void recordLastGraphResult(ServerPlayer player, PlanSavedData data, PlanState plan, JsonObject node, ActionCall call, ActionResult result) {
        plan.lastTaskGraphNodeId = firstNonBlank(stringValue(node, "id", ""), plan.lastTaskGraphNodeId);
        plan.lastActionCallJson = call == null || !call.isPresent() ? plan.lastActionCallJson : call.toJson().toString();
        plan.lastActionResultJson = result == null ? "" : result.toJson().toString();
        touch(data, plan);
        if (result != null) {
            TaskFeedback.recordActionResult(player, NpcManager.activeNpcMob(player.getServer()),
                    "taskgraph:" + firstNonBlank(plan.goal, "saved_plan"), result);
        }
    }

    private static void markBlocked(ServerPlayer player, PlanSavedData data, PlanState plan, String code, String message) {
        plan.status = STATUS_BLOCKED;
        plan.blockedCode = firstNonBlank(code, "PLAN_BLOCKED");
        plan.blockedReason = firstNonBlank(message, "The plan is blocked.");
        plan.lastAction = "Blocked during " + plan.currentStageName() + ".";
        clearRunningStage(plan);
        touch(data, plan);
        say(player, statusLine(plan));
        TaskFeedback.warn(player, NpcManager.activeNpcMob(player.getServer()), "complex_plan", plan.blockedCode, plan.blockedReason);
    }

    private static EventSummary latestEventForTask(ServerPlayer player, String taskName, long sinceMillis) {
        TaskResult result = TaskFeedback.latestResultForTask(player, taskName, sinceMillis);
        if (result != null) {
            return new EventSummary(
                    result.severity(),
                    result.code(),
                    result.message(),
                    result.status()
            );
        }

        JsonObject feedback = TaskFeedback.snapshotJson(player, NpcManager.describeFor(player));
        if (!feedback.has("recentEvents") || !feedback.get("recentEvents").isJsonArray()) {
            return null;
        }

        JsonArray events = feedback.getAsJsonArray("recentEvents");
        for (int index = 0; index < events.size(); index++) {
            if (!events.get(index).isJsonObject()) {
                continue;
            }

            JsonObject event = events.get(index).getAsJsonObject();
            if (!taskName.equals(stringValue(event, "taskName", ""))) {
                continue;
            }

            long timeMillis = longValue(event, "timeMillis", 0L);
            if (sinceMillis > 0L && timeMillis > 0L && timeMillis < sinceMillis) {
                continue;
            }

            return new EventSummary(
                    stringValue(event, "severity", "info"),
                    stringValue(event, "code", "TASK_EVENT"),
                    stringValue(event, "message", ""),
                    ""
            );
        }
        return null;
    }

    private static void ensureBuildAnchor(ServerPlayer player, PlanState plan) {
        if (plan.hasBuildAnchor) {
            return;
        }

        Direction forward = player.getDirection();
        BlockPos center = plan.hasTarget ? plan.targetPos() : player.blockPosition().relative(forward, 5);
        plan.hasBuildAnchor = true;
        plan.buildCenterX = center.getX();
        plan.buildCenterY = center.getY();
        plan.buildCenterZ = center.getZ();
        plan.buildForwardName = horizontalDirection(forward).getName();
        plan.lastAction = "Anchored basic shelter blueprint at " + plan.buildCenterX + " "
                + plan.buildCenterY + " " + plan.buildCenterZ + " facing " + plan.buildForwardName + ".";
    }

    private static JsonObject buildAnchorJson(PlanState plan) {
        JsonObject anchor = new JsonObject();
        anchor.addProperty("x", plan.buildCenterX);
        anchor.addProperty("y", plan.buildCenterY);
        anchor.addProperty("z", plan.buildCenterZ);
        anchor.addProperty("forward", firstNonBlank(plan.buildForwardName, Direction.NORTH.getName()));
        anchor.addProperty("checkpointPolicy", "rebuild_queue_from_world_state_and_skip_non_air_positions");
        return anchor;
    }

    private static int remainingBuildBlocks(ServerPlayer player, PlanState plan) {
        if (!plan.hasBuildAnchor) {
            return ResourceAssessment.BASIC_SHELTER_BLOCKS;
        }
        return NpcManager.countMissingBasicHouseBlocks(player, plan.buildCenterPos(), plan.buildForward());
    }

    private static boolean queueMoreBuildMaterialGathering(
            ServerPlayer player,
            PlanSavedData data,
            PlanState plan,
            ResourceAssessment.Snapshot resources,
            int requiredBlocks,
            int missingBlocks
    ) {
        int gatherCycles = countStage(plan, PlanSkillLibrary.GATHER_WOOD);
        if (gatherCycles >= MAX_BUILD_MATERIAL_GATHER_CYCLES) {
            return false;
        }

        int insertAt = Math.max(0, Math.min(plan.currentStage, plan.stages.size()));
        plan.stages.add(insertAt, PlanSkillLibrary.GATHER_WOOD);
        plan.stages.add(insertAt + 1, PlanSkillLibrary.COLLECT_DROPS);
        plan.status = STATUS_ACTIVE;
        plan.blockedCode = "";
        plan.blockedReason = "";
        plan.lastAction = "Need " + missingBlocks
                + " more placeable blocks; queued another bounded wood-gathering cycle "
                + (gatherCycles + 1) + "/" + MAX_BUILD_MATERIAL_GATHER_CYCLES + ".";
        clearRunningStage(plan);
        plan.stageLaunchAttempts = 0;
        touch(data, plan);
        say(player, "Materials short for shelter: " + resources.placeableBlocks() + "/" + requiredBlocks
                + " approved blocks (" + resources.placeableBlocksAfterPlankConversion()
                + " after log-to-plank conversion). I will gather more wood before checking again; chest materials need your approval.");
        TaskFeedback.info(player, NpcManager.activeNpcMob(player.getServer()), "complex_plan", "MATERIAL_GATHER_CYCLE_QUEUED", plan.lastAction);
        return true;
    }

    private static int countStage(PlanState plan, String stageName) {
        int count = 0;
        for (String stage : plan.stages) {
            if (stageName.equals(stage)) {
                count++;
            }
        }
        return count;
    }

    private static String suggestedNextAction(PlanState plan) {
        if (plan.status.equals(STATUS_BLOCKED)) {
            return switch (firstNonBlank(plan.blockedCode, "")) {
                case "NO_NPC" -> "spawn_npc_then_continue_plan";
                case "NEED_BUILDING_BLOCKS", "NEED_BLOCKS" -> "gather_wood_or_use_container_blocks_then_continue_plan";
                case "NEED_AXE", "NEED_AXE_OR_MATERIALS", "NEED_BASIC_TOOL_MATERIALS" -> "provide_or_gather_tool_materials_then_continue_plan";
                case "SEARCH_TIMEOUT", "NO_BREAK_TARGET" -> "lead_npc_to_target_or_expand_search_then_continue_plan";
                case "INVENTORY_FULL" -> "free_inventory_space_or_place_container_then_continue_plan";
                case "STAGE_RETRY_LIMIT" -> "inspect_blocker_then_continue_plan";
                default -> "answer_question_or_continue_plan";
            };
        }
        if (plan.status.equals(STATUS_RUNNING)) {
            return "wait_or_report_plan";
        }
        if (plan.status.equals(STATUS_ACTIVE) && !plan.executionAuthorized) {
            return "continue_plan_when_player_approves";
        }
        if (plan.status.equals(STATUS_DONE)) {
            return "start_new_plan_if_needed";
        }
        if (plan.status.equals(STATUS_CANCELLED)) {
            return "start_new_plan_if_needed";
        }
        return "continue_plan";
    }

    private static String suggestedQuestion(PlanState plan) {
        if (!plan.status.equals(STATUS_BLOCKED)) {
            return "";
        }

        return switch (firstNonBlank(plan.blockedCode, "")) {
            case "NO_NPC" -> "No companion NPC is spawned. Should I spawn one and continue?";
            case "NEED_BUILDING_BLOCKS", "NEED_BLOCKS" ->
                    "I need more placeable blocks. Should I gather more wood, use approved nearby chest blocks, or shrink/change the blueprint?";
            case "NEED_AXE", "NEED_AXE_OR_MATERIALS", "NEED_BASIC_TOOL_MATERIALS" ->
                    "I need a usable axe or basic tool materials. Can I use nearby chest materials, or should you provide sticks/planks/cobblestone?";
            case "SEARCH_TIMEOUT", "NO_BREAK_TARGET" ->
                    "I could not find the target blocks in range. Should I follow you farther, expand search, or stop?";
            case "INVENTORY_FULL" ->
                    "NPC storage is full. Should I deposit to a nearby container, wait while you clear space, or cancel the plan?";
            case "STAGE_RETRY_LIMIT" ->
                    "This stage hit the retry limit. Should I retry now, change the plan, or cancel?";
            default -> "The plan is blocked. Should I retry, change the goal, or cancel?";
        };
    }

    private static boolean isNpcTaskBusy(ServerPlayer player) {
        JsonObject npc = NpcManager.describeFor(player);
        String task = stringValue(npc, "task", "idle");
        boolean paused = boolValue(npc, "taskPaused", false);
        return paused || !task.equals("idle");
    }

    private static String statusLine(PlanState plan) {
        StringBuilder builder = new StringBuilder("Complex plan: ")
                .append(plan.goal)
                .append(", status=")
                .append(plan.status);

        if (!plan.stages.isEmpty()) {
            builder.append(", stage=")
                    .append(Math.min(plan.currentStage + 1, plan.stages.size()))
                    .append("/")
                    .append(plan.stages.size())
                    .append(" ")
                    .append(plan.currentStageName())
                    .append(", next=")
                    .append(PlanSkillLibrary.stageNextStep(plan.currentStageName()));
        }

        if (!plan.blockedReason.isBlank()) {
            builder.append(", blocked=")
                    .append(plan.blockedCode)
                    .append(": ")
                    .append(plan.blockedReason);
        }

        if (!plan.lastAction.isBlank()) {
            builder.append(", last=")
                    .append(plan.lastAction);
        }
        if (plan.status.equals(STATUS_ACTIVE) && !plan.executionAuthorized) {
            builder.append(", awaiting execution approval");
        }
        builder.append(".");
        return builder.toString();
    }

    private static String stageStatus(PlanState plan, int index) {
        if (index < plan.currentStage || plan.completedStages.contains(plan.stages.get(index))) {
            return STATUS_DONE;
        }
        if (index == plan.currentStage) {
            return plan.status;
        }
        return "pending";
    }

    private static String stageProgress(PlanState plan) {
        return (plan.currentStage + 1) + "/" + plan.stages.size() + " (" + plan.currentStageName() + ")";
    }

    private static PlanSavedData data(ServerPlayer player) {
        return player.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static void touch(PlanSavedData data, PlanState plan) {
        plan.updatedAtMillis = System.currentTimeMillis();
        data.setDirty();
    }

    private static PlanSnapshot snapshot(PlanState plan) {
        return new PlanSnapshot(
                plan.goal,
                plan.status,
                plan.currentStageName(),
                plan.currentStage,
                plan.stages.size(),
                plan.hasTarget,
                plan.targetX,
                plan.targetY,
                plan.targetZ,
                plan.radius,
                plan.durationSeconds,
                PlanSkillLibrary.stageNextStep(plan.currentStageName()),
                plan.blockedCode,
                plan.blockedReason,
                plan.lastAction,
                plan.createdAtMillis,
                plan.updatedAtMillis,
                plan.hasBuildAnchor,
                plan.buildCenterX,
                plan.buildCenterY,
                plan.buildCenterZ,
                firstNonBlank(plan.buildForwardName, Direction.NORTH.getName())
        );
    }

    private static String directStageGoal(String actionName) {
        String name = normalize(actionName);
        return switch (name) {
            case PlanSkillLibrary.PREPARE_BASIC_TOOLS, PlanSkillLibrary.PREPARE_BUILD_MATERIALS,
                 PlanSkillLibrary.GATHER_MATERIALS, PlanSkillLibrary.GATHER_WOOD, PlanSkillLibrary.GATHER_STONE, PlanSkillLibrary.MINE_RESOURCES, PlanSkillLibrary.COLLECT_DROPS,
                 PlanSkillLibrary.CRAFT_AXE, PlanSkillLibrary.CRAFT_PICKAXE, PlanSkillLibrary.CRAFT_PLANKS, PlanSkillLibrary.CRAFT_STICKS,
                 PlanSkillLibrary.CRAFT_STONE_AXE, PlanSkillLibrary.CRAFT_STONE_PICKAXE,
                 PlanSkillLibrary.BUILD_BASIC_SHELTER, PlanSkillLibrary.BUILD_LARGE_HOUSE, PlanSkillLibrary.REPAIR_STRUCTURE,
                 PlanSkillLibrary.EQUIP_GEAR, PlanSkillLibrary.DEPOSIT_STORAGE, PlanSkillLibrary.PROTECT_PLAYER,
                 PlanSkillLibrary.CREATE_INSPECT, PlanSkillLibrary.CREATE_WRENCH -> name;
            default -> "";
        };
    }

    private static String normalize(String value) {
        return firstNonBlank(value, "").trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static Direction horizontalDirection(Direction direction) {
        if (direction == Direction.NORTH || direction == Direction.SOUTH || direction == Direction.EAST || direction == Direction.WEST) {
            return direction;
        }
        return Direction.NORTH;
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

    private static JsonObject jsonObjectFromString(String value) {
        if (value == null || value.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(value).getAsJsonObject();
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    private static String stringValue(JsonObject json, String key, String fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean boolValue(JsonObject json, String key, boolean fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long longValue(JsonObject json, String key, long fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static void say(ServerPlayer player, String message) {
        NpcChat.say(player, message);
    }

    public record PlanSnapshot(
            String goal,
            String status,
            String currentStage,
            int currentStageIndex,
            int stageCount,
            boolean hasTargetPosition,
            double targetX,
            double targetY,
            double targetZ,
            int radius,
            int durationSeconds,
            String nextStep,
            String blockedCode,
            String blockedReason,
            String lastAction,
            long createdAtMillis,
            long updatedAtMillis,
            boolean hasBuildAnchor,
            int buildCenterX,
            int buildCenterY,
            int buildCenterZ,
            String buildForwardName
    ) {
    }

    private enum RunningStageResult {
        WAITING,
        ADVANCE,
        RETRY,
        BLOCKED
    }

    private record EventSummary(String severity, String code, String message, String status) {
        private boolean isCompletion() {
            return "complete".equals(status) || "TASK_COMPLETE".equals(code);
        }

        private boolean isTerminalProblem() {
            if ("failed".equals(status) || "blocked".equals(status)) {
                return true;
            }
            if ("failure".equals(severity)) {
                return true;
            }
            if (!"warn".equals(severity)) {
                return false;
            }
            return switch (code) {
                case "SEARCHING_WITH_OWNER", "NEED_SCAFFOLD_BLOCK", "UNREACHABLE_PLACE_TARGET", "CANNOT_PLACE_BLOCK" -> false;
                default -> true;
            };
        }
    }

    private static final class PlanState {
        private UUID ownerUuid;
        private String ownerName;
        private String goal;
        private String message;
        private String status;
        private List<String> stages;
        private List<String> completedStages;
        private int currentStage;
        private int radius;
        private int durationSeconds;
        private boolean hasTarget;
        private double targetX;
        private double targetY;
        private double targetZ;
        private boolean hasBuildAnchor;
        private int buildCenterX;
        private int buildCenterY;
        private int buildCenterZ;
        private String buildForwardName;
        private String blockedCode;
        private String blockedReason;
        private String lastAction;
        private String lastTaskGraphNodeId;
        private String lastActionCallJson;
        private String lastActionResultJson;
        private boolean executionAuthorized;
        private String runningTaskName;
        private boolean runningObservedBusy;
        private long stageStartedAtMillis;
        private int stageLaunchAttempts;
        private long createdAtMillis;
        private long updatedAtMillis;

        private static PlanState create(ServerPlayer player, PlanSkillLibrary.PlanBlueprint blueprint, String message, BridgeDecision.Action action) {
            PlanState plan = new PlanState();
            plan.ownerUuid = player.getUUID();
            plan.ownerName = player.getGameProfile().getName();
            plan.goal = blueprint.goal();
            plan.message = firstNonBlank(message, "");
            plan.status = STATUS_ACTIVE;
            plan.stages = new ArrayList<>(blueprint.stages());
            plan.completedStages = new ArrayList<>();
            plan.currentStage = 0;
            plan.radius = action.radius() == null ? McAiConfig.NPC_TASK_RADIUS.get() : Math.max(4, Math.min(action.radius().intValue(), McAiConfig.NPC_TASK_RADIUS.get()));
            int defaultDurationSeconds = blueprint.goal().equals(PlanSkillLibrary.BUILD_BASIC_SHELTER) ? 180 : 90;
            plan.durationSeconds = action.durationSeconds() == null ? defaultDurationSeconds : Math.max(10, Math.min(action.durationSeconds().intValue(), 300));
            plan.blockedCode = "";
            plan.blockedReason = "";
            plan.lastAction = "Plan created from player request.";
            plan.lastTaskGraphNodeId = "";
            plan.lastActionCallJson = "";
            plan.lastActionResultJson = "";
            plan.executionAuthorized = false;
            plan.runningTaskName = "";
            plan.runningObservedBusy = false;
            plan.stageStartedAtMillis = 0L;
            plan.stageLaunchAttempts = 0;
            plan.createdAtMillis = System.currentTimeMillis();
            plan.updatedAtMillis = plan.createdAtMillis;

            BridgeDecision.Position position = action.position();
            plan.hasTarget = position != null;
            if (position != null) {
                plan.targetX = position.x();
                plan.targetY = position.y();
                plan.targetZ = position.z();
            }
            if (blueprint.goal().equals(PlanSkillLibrary.BUILD_BASIC_SHELTER) && position != null) {
                Direction forward = horizontalDirection(player.getDirection());
                plan.hasBuildAnchor = true;
                BlockPos center = BlockPos.containing(position.x(), position.y(), position.z());
                plan.buildCenterX = center.getX();
                plan.buildCenterY = center.getY();
                plan.buildCenterZ = center.getZ();
                plan.buildForwardName = forward.getName();
            } else {
                plan.hasBuildAnchor = false;
                plan.buildForwardName = Direction.NORTH.getName();
            }
            return plan;
        }

        private String currentStageName() {
            if (stages.isEmpty()) {
                return "";
            }
            int index = Math.max(0, Math.min(currentStage, stages.size() - 1));
            return stages.get(index);
        }

        private BlockPos targetPos() {
            return BlockPos.containing(targetX, targetY, targetZ);
        }

        private BlockPos buildCenterPos() {
            return new BlockPos(buildCenterX, buildCenterY, buildCenterZ);
        }

        private Direction buildForward() {
            return directionFromName(buildForwardName);
        }
    }

    private static final class PlanSavedData extends SavedData {
        private final Map<UUID, PlanState> plans = new LinkedHashMap<>();

        private static PlanSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
            PlanSavedData data = new PlanSavedData();
            ListTag list = tag.getList("plans", Tag.TAG_COMPOUND);
            for (int index = 0; index < list.size(); index++) {
                CompoundTag planTag = list.getCompound(index);
                if (!planTag.contains("ownerUuid")) {
                    continue;
                }

                PlanState plan = new PlanState();
                plan.ownerUuid = planTag.getUUID("ownerUuid");
                plan.ownerName = planTag.getString("ownerName");
                plan.goal = planTag.getString("goal");
                plan.message = planTag.getString("message");
                plan.status = firstNonBlank(planTag.getString("status"), STATUS_ACTIVE);
                plan.stages = readStringList(planTag, "stages");
                plan.completedStages = readStringList(planTag, "completedStages");
                plan.currentStage = planTag.getInt("currentStage");
                plan.radius = planTag.getInt("radius");
                plan.durationSeconds = planTag.getInt("durationSeconds");
                plan.hasTarget = planTag.getBoolean("hasTarget");
                plan.targetX = planTag.getDouble("targetX");
                plan.targetY = planTag.getDouble("targetY");
                plan.targetZ = planTag.getDouble("targetZ");
                plan.hasBuildAnchor = planTag.getBoolean("hasBuildAnchor");
                plan.buildCenterX = planTag.getInt("buildCenterX");
                plan.buildCenterY = planTag.getInt("buildCenterY");
                plan.buildCenterZ = planTag.getInt("buildCenterZ");
                plan.buildForwardName = firstNonBlank(planTag.getString("buildForwardName"), Direction.NORTH.getName());
                plan.blockedCode = planTag.getString("blockedCode");
                plan.blockedReason = planTag.getString("blockedReason");
                plan.lastAction = planTag.getString("lastAction");
                plan.lastTaskGraphNodeId = planTag.getString("lastTaskGraphNodeId");
                plan.lastActionCallJson = planTag.getString("lastActionCallJson");
                plan.lastActionResultJson = planTag.getString("lastActionResultJson");
                plan.executionAuthorized = planTag.contains("executionAuthorized")
                        ? planTag.getBoolean("executionAuthorized")
                        : plan.status.equals(STATUS_RUNNING);
                plan.runningTaskName = planTag.getString("runningTaskName");
                plan.runningObservedBusy = planTag.getBoolean("runningObservedBusy");
                plan.stageStartedAtMillis = planTag.getLong("stageStartedAtMillis");
                plan.stageLaunchAttempts = planTag.getInt("stageLaunchAttempts");
                plan.createdAtMillis = planTag.getLong("createdAtMillis");
                plan.updatedAtMillis = planTag.getLong("updatedAtMillis");
                data.plans.put(plan.ownerUuid, plan);
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            ListTag list = new ListTag();
            for (PlanState plan : plans.values()) {
                CompoundTag planTag = new CompoundTag();
                planTag.putUUID("ownerUuid", plan.ownerUuid);
                planTag.putString("ownerName", firstNonBlank(plan.ownerName, ""));
                planTag.putString("goal", firstNonBlank(plan.goal, ""));
                planTag.putString("message", firstNonBlank(plan.message, ""));
                planTag.putString("status", firstNonBlank(plan.status, STATUS_ACTIVE));
                planTag.put("stages", writeStringList(plan.stages));
                planTag.put("completedStages", writeStringList(plan.completedStages));
                planTag.putInt("currentStage", plan.currentStage);
                planTag.putInt("radius", plan.radius);
                planTag.putInt("durationSeconds", plan.durationSeconds);
                planTag.putBoolean("hasTarget", plan.hasTarget);
                planTag.putDouble("targetX", plan.targetX);
                planTag.putDouble("targetY", plan.targetY);
                planTag.putDouble("targetZ", plan.targetZ);
                planTag.putBoolean("hasBuildAnchor", plan.hasBuildAnchor);
                planTag.putInt("buildCenterX", plan.buildCenterX);
                planTag.putInt("buildCenterY", plan.buildCenterY);
                planTag.putInt("buildCenterZ", plan.buildCenterZ);
                planTag.putString("buildForwardName", firstNonBlank(plan.buildForwardName, Direction.NORTH.getName()));
                planTag.putString("blockedCode", firstNonBlank(plan.blockedCode, ""));
                planTag.putString("blockedReason", firstNonBlank(plan.blockedReason, ""));
                planTag.putString("lastAction", firstNonBlank(plan.lastAction, ""));
                planTag.putString("lastTaskGraphNodeId", firstNonBlank(plan.lastTaskGraphNodeId, ""));
                planTag.putString("lastActionCallJson", firstNonBlank(plan.lastActionCallJson, ""));
                planTag.putString("lastActionResultJson", firstNonBlank(plan.lastActionResultJson, ""));
                planTag.putBoolean("executionAuthorized", plan.executionAuthorized);
                planTag.putString("runningTaskName", firstNonBlank(plan.runningTaskName, ""));
                planTag.putBoolean("runningObservedBusy", plan.runningObservedBusy);
                planTag.putLong("stageStartedAtMillis", plan.stageStartedAtMillis);
                planTag.putInt("stageLaunchAttempts", plan.stageLaunchAttempts);
                planTag.putLong("createdAtMillis", plan.createdAtMillis);
                planTag.putLong("updatedAtMillis", plan.updatedAtMillis);
                list.add(planTag);
            }
            tag.put("plans", list);
            return tag;
        }

        private static List<String> readStringList(CompoundTag tag, String key) {
            List<String> values = new ArrayList<>();
            ListTag list = tag.getList(key, Tag.TAG_STRING);
            for (int index = 0; index < list.size(); index++) {
                values.add(list.getString(index));
            }
            return values;
        }

        private static ListTag writeStringList(List<String> values) {
            ListTag list = new ListTag();
            for (String value : values) {
                list.add(StringTag.valueOf(value));
            }
            return list;
        }
    }
}
