package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

public final class TaskGraphPlanner {
    private TaskGraphPlanner() {
    }

    public static GoalSpec goalFromDecision(BridgeDecision decision) {
        if (decision != null && decision.goalSpec() != null && decision.goalSpec().isPresent()) {
            return decision.goalSpec();
        }
        String raw = "";
        if (decision != null) {
            raw = firstNonBlank(decision.action().message(), decision.action().value(), decision.action().key(), decision.reply(), "");
        }
        return GoalSpec.empty(raw);
    }

    public static String requestedGoal(BridgeDecision decision, String fallback) {
        GoalSpec goal = goalFromDecision(decision);
        BridgeDecision.Action action = decision.action();
        return firstNonBlank(
                action.value(),
                action.key(),
                goal.intent(),
                fallback,
                goal.rawRequest(),
                action.message(),
                decision.reply(),
                ""
        );
    }

    public static TaskGraph planFor(GoalSpec goalSpec, String requestedGoal, String message) {
        String goalText = firstNonBlank(requestedGoal, goalSpec == null ? "" : goalSpec.intent(), goalSpec == null ? "" : goalSpec.rawRequest(), message, "");
        PlanSkillLibrary.PlanBlueprint blueprint = PlanSkillLibrary.blueprintFor(goalText, message);
        JsonArray nodes = new JsonArray();
        JsonArray previous = new JsonArray();

        for (int index = 0; index < blueprint.stages().size(); index++) {
            String stage = blueprint.stages().get(index);
            String id = "n" + (index + 1);
            nodes.add(TaskGraph.node(id, skillForStage(stage), actionForStage(stage), index == 0 ? "ready" : "pending", previous, ""));
            previous = new JsonArray();
            previous.add(id);
        }

        if (nodes.isEmpty()) {
            JsonArray none = new JsonArray();
            nodes.add(TaskGraph.node("n1", "observe_environment", "observe_environment", "ready", none, ""));
            JsonArray depends = new JsonArray();
            depends.add("n1");
            nodes.add(TaskGraph.node("n2", "ask_clarifying_question", "ask_clarifying_question", "pending", depends, "UNKNOWN_GOAL"));
        }

        String status = blueprint.stages().isEmpty() ? "needs_clarification_or_model_planning" : "draft";
        String current = nodes.isEmpty() ? "" : nodes.get(0).getAsJsonObject().get("id").getAsString();
        String summary = blueprint.stages().isEmpty()
                ? "No deterministic SkillSpec path matched this goal; collect ObservationFrame and ask for missing constraints or let Codex propose a TaskGraph."
                : "Deterministic TaskGraph from SkillSpec preconditions/effects: " + String.join(" -> ", blueprint.stages()) + ".";
        return new TaskGraph("taskgraph-" + normalizeId(goalText), blueprint.goal(), status, nodes, current, summary);
    }

    public static TaskGraph fromPlanSnapshot(PlanManager.PlanSnapshot snapshot) {
        if (snapshot == null || snapshot.stageCount() <= 0) {
            return TaskGraph.empty("");
        }
        PlanSkillLibrary.PlanBlueprint blueprint = PlanSkillLibrary.blueprintFor(snapshot.goal(), "");
        JsonArray nodes = new JsonArray();
        JsonArray previous = new JsonArray();
        for (int index = 0; index < blueprint.stages().size(); index++) {
            String stage = blueprint.stages().get(index);
            String status = index < snapshot.currentStageIndex()
                    ? "complete"
                    : index == snapshot.currentStageIndex() ? snapshot.status() : "pending";
            String id = "n" + (index + 1);
            nodes.add(TaskGraph.node(id, skillForStage(stage), actionForStage(stage), status, previous, ""));
            previous = new JsonArray();
            previous.add(id);
        }
        return new TaskGraph("taskgraph-" + normalizeId(snapshot.goal()), snapshot.goal(), snapshot.status(), nodes, "n" + Math.max(1, snapshot.currentStageIndex() + 1), "TaskGraph projection of saved complex plan.");
    }

    public static JsonObject currentNode(TaskGraph graph) {
        if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
            return new JsonObject();
        }

        String currentNodeId = firstNonBlank(graph.currentNodeId(), "");
        if (!currentNodeId.isBlank()) {
            JsonObject byId = nodeById(graph.nodes(), currentNodeId);
            if (isExecutableNode(byId)) {
                return byId;
            }
        }

        for (JsonElement element : graph.nodes()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject node = element.getAsJsonObject();
            if (isExecutableNode(node) && dependenciesComplete(graph.nodes(), node)) {
                return node.deepCopy();
            }
        }
        return new JsonObject();
    }

    public static ActionCall actionCallForNode(TaskGraph graph, JsonObject node, PlanManager.PlanSnapshot snapshot) {
        if (node == null || node.isEmpty()) {
            return ActionCall.empty();
        }
        if (node.has("actionCall") && node.get("actionCall").isJsonObject()) {
            ActionCall supplied = ActionCall.fromJson(node.getAsJsonObject("actionCall"));
            if (supplied.isPresent()) {
                return supplied;
            }
        }

        String action = firstNonBlank(nodeString(node, "action"), nodeString(node, "primitive"), nodeString(node, "skill"));
        JsonObject args = argsForAction(action, snapshot);
        if (node.has("args") && node.get("args").isJsonObject()) {
            JsonObject nodeArgs = node.getAsJsonObject("args");
            for (String key : nodeArgs.keySet()) {
                args.add(key, nodeArgs.get(key).deepCopy());
            }
        }

        String nodeId = nodeString(node, "id");
        String reason = "TaskGraph " + (graph == null ? "" : graph.id()) + " node " + nodeId;
        String expectedEffect = firstNonBlank(nodeString(node, "expectedEffect"), "Execute node action and return structured ActionResult feedback.");
        return new ActionCall(action, args, nodeString(node, "targetNpc"), "active", reason, expectedEffect, safetyLevelForAction(action));
    }

    public static TaskGraph applyActionResult(TaskGraph graph, String nodeId, ActionResult result) {
        if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
            return TaskGraph.empty("");
        }

        String updatedNodeId = firstNonBlank(nodeId, graph.currentNodeId());
        JsonArray nodes = new JsonArray();
        for (JsonElement element : graph.nodes()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject node = element.getAsJsonObject().deepCopy();
            if (updatedNodeId.equals(nodeString(node, "id"))) {
                node.addProperty("status", statusForResult(result));
                node.add("lastActionResult", result == null ? new JsonObject() : result.toJson());
            }
            nodes.add(node);
        }

        String nextNodeId = updatedNodeId;
        String status = graph.status();
        if (result != null && result.isSuccess()) {
            nextNodeId = markNextReady(nodes);
            status = nextNodeId.isBlank() ? "done" : "active";
        } else if (result != null && result.isStarted()) {
            status = "running";
        } else if (result != null && result.isBlocked()) {
            status = "blocked";
        } else if (result != null && result.isFailed()) {
            status = "failed";
        }

        return new TaskGraph(graph.id(), graph.goal(), status, nodes, nextNodeId, graph.summary());
    }

    private static String skillForStage(String stage) {
        return switch (stage) {
            case PlanSkillLibrary.PREPARE_BASIC_TOOLS -> "prepare_tools";
            case PlanSkillLibrary.PREPARE_BUILD_MATERIALS -> "gather_materials";
            case PlanSkillLibrary.GATHER_WOOD -> "harvest_logs";
            case PlanSkillLibrary.GATHER_STONE -> "gather_stone";
            case PlanSkillLibrary.MINE_RESOURCES -> "mine_nearby_ore";
            case PlanSkillLibrary.COLLECT_DROPS -> "collect_items";
            case PlanSkillLibrary.CRAFT_AXE -> "craft_item";
            case PlanSkillLibrary.CRAFT_PICKAXE -> "craft_item";
            case PlanSkillLibrary.CRAFT_PLANKS -> "craft_item";
            case PlanSkillLibrary.CRAFT_STICKS -> "craft_item";
            case PlanSkillLibrary.CRAFT_STONE_AXE -> "craft_item";
            case PlanSkillLibrary.CRAFT_STONE_PICKAXE -> "craft_item";
            case PlanSkillLibrary.BUILD_BASIC_SHELTER -> "build_structure";
            case PlanSkillLibrary.BUILD_LARGE_HOUSE -> "build_structure";
            case PlanSkillLibrary.REPAIR_STRUCTURE -> "repair_structure";
            case PlanSkillLibrary.EQUIP_GEAR -> "equip_item";
            case PlanSkillLibrary.DEPOSIT_STORAGE -> "deposit_to_chest";
            case PlanSkillLibrary.PROTECT_PLAYER -> "protect_player";
            case PlanSkillLibrary.CREATE_INSPECT -> "report_modded_nearby";
            case PlanSkillLibrary.CREATE_WRENCH -> "use_mod_wrench";
            default -> SkillRegistry.hasSkill(stage) ? stage : "observe_environment";
        };
    }

    private static String actionForStage(String stage) {
        return switch (stage) {
            case PlanSkillLibrary.PREPARE_BASIC_TOOLS -> "prepare_basic_tools";
            case PlanSkillLibrary.PREPARE_BUILD_MATERIALS -> "gather_materials";
            case PlanSkillLibrary.GATHER_WOOD -> "harvest_logs";
            case PlanSkillLibrary.GATHER_STONE -> "gather_stone";
            case PlanSkillLibrary.MINE_RESOURCES -> "mine_nearby_ore";
            case PlanSkillLibrary.COLLECT_DROPS -> "collect_items";
            case PlanSkillLibrary.CRAFT_AXE -> "craft_item";
            case PlanSkillLibrary.CRAFT_PICKAXE -> "craft_item";
            case PlanSkillLibrary.CRAFT_PLANKS -> "craft_item";
            case PlanSkillLibrary.CRAFT_STICKS -> "craft_item";
            case PlanSkillLibrary.CRAFT_STONE_AXE -> "craft_item";
            case PlanSkillLibrary.CRAFT_STONE_PICKAXE -> "craft_item";
            case PlanSkillLibrary.BUILD_BASIC_SHELTER -> "build_structure";
            case PlanSkillLibrary.BUILD_LARGE_HOUSE -> "build_structure";
            case PlanSkillLibrary.REPAIR_STRUCTURE -> "repair_structure";
            case PlanSkillLibrary.EQUIP_GEAR -> "equip_best_gear";
            case PlanSkillLibrary.DEPOSIT_STORAGE -> "deposit_to_chest";
            case PlanSkillLibrary.PROTECT_PLAYER -> "protect_player";
            case PlanSkillLibrary.CREATE_INSPECT -> "report_modded_nearby";
            case PlanSkillLibrary.CREATE_WRENCH -> "use_mod_wrench";
            default -> stage;
        };
    }

    private static JsonObject argsForAction(String action, PlanManager.PlanSnapshot snapshot) {
        JsonObject args = new JsonObject();
        String normalized = normalize(action);
        if (snapshot == null) {
            return args;
        }

        args.addProperty("radius", snapshot.radius());
        args.addProperty("durationSeconds", snapshot.durationSeconds());
        if (snapshot.hasTargetPosition()) {
            args.add("position", position(snapshot.targetX(), snapshot.targetY(), snapshot.targetZ()));
        }
        if (normalized.equals("build_basic_house") || normalized.equals("build_basic_shelter") || normalized.equals("build_structure")) {
            if (snapshot.hasBuildAnchor()) {
                args.add("position", position(snapshot.buildCenterX(), snapshot.buildCenterY(), snapshot.buildCenterZ()));
                args.addProperty("forward", firstNonBlank(snapshot.buildForwardName(), "north"));
            }
            args.addProperty("template", "starter_cabin_7x7");
            args.addProperty("style", "rustic");
            args.addProperty("autoGather", !snapshot.goal().equals(PlanSkillLibrary.BUILD_BASIC_SHELTER));
        }
        if (normalized.equals("gather_materials")) {
            args.addProperty("material", snapshot.goal().equals(PlanSkillLibrary.GATHER_STONE) ? "stone" : "placeable_blocks");
            args.addProperty("count", snapshot.goal().equals(PlanSkillLibrary.BUILD_LARGE_HOUSE) ? 184 : 94);
        }
        if (normalized.equals("prepare_basic_tools") || normalized.equals("prepare_axe") || normalized.equals("prepare_pickaxe")) {
            boolean needsAxe = snapshot.goal().equals(PlanSkillLibrary.GATHER_WOOD)
                    || snapshot.goal().equals(PlanSkillLibrary.GATHER_MATERIALS)
                    || snapshot.goal().equals(PlanSkillLibrary.BUILD_BASIC_SHELTER)
                    || snapshot.goal().equals(PlanSkillLibrary.BUILD_LARGE_HOUSE)
                    || normalized.equals("prepare_axe")
                    || normalized.equals("prepare_basic_tools");
            boolean needsPickaxe = snapshot.goal().equals(PlanSkillLibrary.PREPARE_BASIC_TOOLS)
                    || snapshot.goal().equals(PlanSkillLibrary.GATHER_STONE)
                    || snapshot.goal().equals(PlanSkillLibrary.MINE_RESOURCES)
                    || snapshot.goal().equals(PlanSkillLibrary.CRAFT_STONE_AXE)
                    || snapshot.goal().equals(PlanSkillLibrary.CRAFT_STONE_PICKAXE)
                    || normalized.equals("prepare_pickaxe");
            args.addProperty("requireAxe", needsAxe);
            args.addProperty("requirePickaxe", needsPickaxe);
        }
        if (normalized.equals("gather_stone")) {
            boolean stoneTool = snapshot.goal().equals(PlanSkillLibrary.CRAFT_STONE_AXE)
                    || snapshot.goal().equals(PlanSkillLibrary.CRAFT_STONE_PICKAXE);
            args.addProperty("count", stoneTool ? 3 : 8);
        }
        if (normalized.equals("craft_item") && (snapshot.goal().equals(PlanSkillLibrary.CRAFT_STONE_AXE)
                || snapshot.currentStage().equals(PlanSkillLibrary.CRAFT_STONE_AXE))) {
            args.addProperty("item", "stone_axe");
            args.addProperty("count", 1);
        }
        if (normalized.equals("craft_item") && (snapshot.goal().equals(PlanSkillLibrary.CRAFT_STONE_PICKAXE)
                || snapshot.currentStage().equals(PlanSkillLibrary.CRAFT_STONE_PICKAXE))) {
            args.addProperty("item", "stone_pickaxe");
            args.addProperty("count", 1);
        }
        if (normalized.equals("craft_item") && (snapshot.goal().equals(PlanSkillLibrary.CRAFT_AXE)
                || snapshot.currentStage().equals(PlanSkillLibrary.CRAFT_AXE))) {
            args.addProperty("item", "axe");
            args.addProperty("count", 1);
        }
        if (normalized.equals("craft_item") && (snapshot.goal().equals(PlanSkillLibrary.CRAFT_PICKAXE)
                || snapshot.currentStage().equals(PlanSkillLibrary.CRAFT_PICKAXE))) {
            args.addProperty("item", "pickaxe");
            args.addProperty("count", 1);
        }
        if (normalized.equals("craft_item") && (snapshot.goal().equals(PlanSkillLibrary.CRAFT_PLANKS)
                || snapshot.currentStage().equals(PlanSkillLibrary.CRAFT_PLANKS))) {
            args.addProperty("item", "planks");
            args.addProperty("count", 0);
        }
        if (normalized.equals("craft_item") && (snapshot.goal().equals(PlanSkillLibrary.CRAFT_STICKS)
                || snapshot.currentStage().equals(PlanSkillLibrary.CRAFT_STICKS))) {
            args.addProperty("item", "sticks");
            args.addProperty("count", 4);
        }
        return args;
    }

    private static JsonObject position(double x, double y, double z) {
        JsonObject position = new JsonObject();
        position.addProperty("x", x);
        position.addProperty("y", y);
        position.addProperty("z", z);
        return position;
    }

    private static boolean isExecutableNode(JsonObject node) {
        if (node == null || node.isEmpty()) {
            return false;
        }
        String status = nodeString(node, "status");
        return status.equals("ready") || status.equals("active") || status.equals("running");
    }

    private static JsonObject nodeById(JsonArray nodes, String nodeId) {
        for (JsonElement element : nodes) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject node = element.getAsJsonObject();
            if (nodeId.equals(nodeString(node, "id"))) {
                return node.deepCopy();
            }
        }
        return new JsonObject();
    }

    private static boolean dependenciesComplete(JsonArray nodes, JsonObject node) {
        JsonArray dependsOn = node.has("dependsOn") && node.get("dependsOn").isJsonArray()
                ? node.getAsJsonArray("dependsOn")
                : new JsonArray();
        for (JsonElement dependency : dependsOn) {
            String dependencyId = dependency.getAsString();
            JsonObject dependencyNode = nodeById(nodes, dependencyId);
            if (!nodeString(dependencyNode, "status").equals("complete")
                    && !nodeString(dependencyNode, "status").equals("done")) {
                return false;
            }
        }
        return true;
    }

    private static String markNextReady(JsonArray nodes) {
        String nextNodeId = "";
        for (JsonElement element : nodes) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject node = element.getAsJsonObject();
            String status = nodeString(node, "status");
            if (status.equals("complete") || status.equals("done")) {
                continue;
            }
            if (nextNodeId.isBlank() && dependenciesComplete(nodes, node)) {
                nextNodeId = nodeString(node, "id");
                node.addProperty("status", "ready");
            } else if (status.equals("ready")) {
                node.addProperty("status", "pending");
            }
        }
        return nextNodeId;
    }

    private static String statusForResult(ActionResult result) {
        if (result == null) {
            return "failed";
        }
        if (result.isSuccess()) {
            return "complete";
        }
        if (result.isStarted()) {
            return "running";
        }
        return result.status();
    }

    private static String safetyLevelForAction(String action) {
        String normalized = normalize(action);
        if (normalized.equals("break_block") || normalized.equals("place_block") || normalized.equals("salvage_nearby_wood_structure") || normalized.equals("build_structure") || normalized.equals("build_machine") || normalized.equals("build_redstone_template") || normalized.equals("build_basic_house") || normalized.equals("build_basic_shelter") || normalized.equals("build_large_house") || normalized.equals("repair_structure")) {
            return "destructive";
        }
        if (normalized.equals("withdraw_from_chest") || normalized.equals("deposit_to_chest") || normalized.equals("use_mod_wrench")) {
            return "permissioned";
        }
        if (normalized.equals("guard_player") || normalized.equals("protect_player")) {
            return "combat";
        }
        return "normal";
    }

    private static String nodeString(JsonObject node, String key) {
        return AgentJson.string(node, key, "");
    }

    private static String normalizeId(String value) {
        String text = firstNonBlank(value, "general_task").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        text = text.replaceAll("_+", "_").replaceAll("^_|_$", "");
        return text.isBlank() ? "general_task" : text;
    }

    private static String normalize(String value) {
        return firstNonBlank(value, "").trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
