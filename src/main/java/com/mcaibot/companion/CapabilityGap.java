package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

public final class CapabilityGap {
    private static final int MAX_GAPS = 8;

    private CapabilityGap() {
    }

    public static JsonArray fromExecutionFeedback(JsonObject executionFeedback) {
        JsonArray latestResults = AgentJson.array(executionFeedback, "latestResults");
        return fromLatestResults(latestResults);
    }

    public static JsonArray fromLatestResults(JsonArray latestResults) {
        JsonArray gaps = new JsonArray();
        for (JsonElement element : latestResults) {
            if (gaps.size() >= MAX_GAPS) {
                break;
            }
            if (element == null || !element.isJsonObject()) {
                continue;
            }

            JsonObject result = element.getAsJsonObject();
            String status = AgentJson.string(result, "status", "");
            if (!isProblem(status)) {
                continue;
            }

            JsonObject gap = gapFor(result);
            if (!gap.isEmpty()) {
                gaps.add(gap);
            }
        }
        return gaps;
    }

    private static JsonObject gapFor(JsonObject result) {
        String code = AgentJson.string(result, "code", "TASK_BLOCKED");
        String taskName = AgentJson.string(result, "taskName", "unknown");
        String message = AgentJson.string(result, "message", "");
        JsonObject actionResult = AgentJson.object(result, "actionResult");
        JsonArray suggestedRepairs = AgentJson.array(actionResult, "suggestedRepairs");
        boolean retryable = AgentJson.bool(actionResult, "retryable", true);

        JsonObject gap = new JsonObject();
        gap.addProperty("schemaVersion", "mc-agent-capability-gap-v1");
        gap.addProperty("taskName", taskName);
        gap.addProperty("code", code);
        gap.addProperty("status", AgentJson.string(result, "status", "blocked"));
        gap.addProperty("rootCause", rootCauseFor(code, message, taskName));
        gap.addProperty("missing", missingFor(code, message));
        gap.addProperty("retryable", retryable);
        gap.addProperty("message", message);
        gap.add("suggestedRepairs", suggestedRepairs);
        gap.addProperty("nextStep", nextStepFor(code, retryable));
        gap.addProperty("prevention", preventionFor(code, taskName));
        return gap;
    }

    private static boolean isProblem(String status) {
        String value = normalize(status);
        return value.equals("blocked") || value.equals("failed") || value.equals("failure");
    }

    private static String rootCauseFor(String code, String message, String taskName) {
        String text = normalize(code + " " + message + " " + taskName);
        if (containsAny(text, "unknown_primitive", "unsupported", "not_generic", "not_exposed")) {
            return "skill_gap";
        }
        if (containsAny(text, "missing_position", "no_target", "ambiguous", "unclear")) {
            return "missing_context";
        }
        if (containsAny(text, "need_block", "need_blocks", "need_repair_block", "need_door", "need_logs", "need_planks", "need_stone", "no_material")) {
            return "missing_resource";
        }
        if (containsAny(text, "need_tool", "need_axe", "need_pickaxe", "low_", "durability", "tool")) {
            return "tool_or_equipment";
        }
        if (containsAny(text, "unreachable", "no_path", "cannot_place", "path", "mobility")) {
            return "execution_blocker";
        }
        if (containsAny(text, "approval", "permission", "chest", "container")) {
            return "permission_required";
        }
        if (containsAny(text, "crafting_table", "workbench", "station")) {
            return "missing_workstation";
        }
        return "runtime_failure";
    }

    private static String missingFor(String code, String message) {
        String text = normalize(code + " " + message);
        if (containsAny(text, "position", "target", "coordinates")) {
            return "target_location";
        }
        if (containsAny(text, "door")) {
            return "door_or_door_materials";
        }
        if (containsAny(text, "blocks", "repair_block", "material", "planks", "logs", "stone")) {
            return "materials";
        }
        if (containsAny(text, "tool", "axe", "pickaxe", "durability")) {
            return "usable_tool";
        }
        if (containsAny(text, "crafting_table", "workbench")) {
            return "crafting_table";
        }
        if (containsAny(text, "primitive", "skill", "unsupported")) {
            return "registered_skill";
        }
        if (containsAny(text, "approval", "permission", "chest", "container")) {
            return "player_permission";
        }
        return "unknown";
    }

    private static String nextStepFor(String code, boolean retryable) {
        String text = normalize(code);
        if (containsAny(text, "unknown_primitive", "unsupported")) {
            return "record_skill_gap_and_ask_developer_or_choose_supported_skill";
        }
        if (containsAny(text, "missing_position", "no_target")) {
            return "ask_player_for_target_or_observe_more_context";
        }
        if (containsAny(text, "need_", "material", "blocks")) {
            return "gather_materials_or_request_container_approval";
        }
        if (containsAny(text, "unreachable", "cannot_place", "path")) {
            return "recover_mobility_or_replan_route";
        }
        return retryable ? "retry_with_repair_strategy" : "ask_player_or_replan";
    }

    private static String preventionFor(String code, String taskName) {
        String text = normalize(code + " " + taskName);
        if (containsAny(text, "unknown_primitive", "unsupported")) {
            return "new abilities must update SkillRegistry, action primitives, Node schema, planner, help, and tests together";
        }
        if (containsAny(text, "missing_position", "no_target")) {
            return "planner must require object-level target context or ask one clarifying question before irreversible actions";
        }
        if (containsAny(text, "need_", "material", "blocks")) {
            return "planner must run resource assessment and material acquisition before launching the executor";
        }
        if (containsAny(text, "unreachable", "cannot_place", "path")) {
            return "executor must return path blockers and the planner must have bounded scaffold or reroute repair strategies";
        }
        return "classify the failure, add a regression scenario, then generalize the skill or planner rule";
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
