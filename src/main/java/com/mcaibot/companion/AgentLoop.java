package com.mcaibot.companion;

import com.google.gson.JsonObject;

public final class AgentLoop {
    private AgentLoop() {
    }

    public static JsonObject contractJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", "mc-agent-loop-v1");
        json.addProperty("cycle", "observe -> interpret_goal -> plan_task_graph -> execute_one_action -> verify_action_result -> reflect_or_replan -> report");
        json.addProperty("modelRole", "goal understanding, clarification, strategy choice, and complex failure reflection");
        json.addProperty("runtimeRole", "execute only whitelisted actions with player-like rules and return ActionResult");
        json.addProperty("taskGraphNextAction", "taskgraph_next executes one ready TaskGraph node and persists structured ActionResult for the next model turn");
        json.addProperty("actionResultSchema", "mc-agent-action-result-v1(status,code,message,effects,observations,blockers,retryable,suggestedRepairs,requiresReplan)");
        json.addProperty("maxUnattendedRepairCycles", 3);
        json.addProperty("maxExplorationRadiusWithoutApproval", 64);
        json.addProperty("mustAskWhen", "target, permission, participant, material, location, or safety constraint changes outcome materially");
        json.addProperty("defaultMaterialPolicy", "NPC storage and self-gathering first; approved nearby containers second; player inventory never consumed");
        JsonObject companionLoop = new JsonObject();
        companionLoop.addProperty("cycle", "observe social/task/danger state -> maybe speak/report/ask -> apply cooldown -> never start world-changing work");
        companionLoop.addProperty("safeActionsOnly", "none, say, ask_clarifying_question, propose_plan, report_status, report_task_status, recall");
        companionLoop.addProperty("triggers", "low_health, nearby_hostile, night, task_complete, task_problem, long_silence");
        companionLoop.addProperty("embodiment", "idle NPC may look at nearby player without moving or interrupting tasks");
        companionLoop.addProperty("antiSpam", "explicit interaction cooldown, per-player cooldown, trigger cooldown, duplicate chat suppression");
        json.add("companionLoop", companionLoop);
        return json;
    }
}
