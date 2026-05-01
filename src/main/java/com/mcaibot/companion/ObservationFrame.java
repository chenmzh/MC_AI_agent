package com.mcaibot.companion;

import com.google.gson.JsonObject;

import java.time.Instant;

public final class ObservationFrame {
    private ObservationFrame() {
    }

    public static JsonObject fromContext(JsonObject context) {
        JsonObject frame = new JsonObject();
        frame.addProperty("schemaVersion", "mc-agent-observation-v1");
        frame.addProperty("generatedAt", Instant.now().toString());
        frame.addProperty("adapter", "neoforge");

        JsonObject actor = new JsonObject();
        actor.add("player", copy(context, "player"));
        actor.add("npc", copy(context, "npc"));
        actor.add("persona", copy(context, "persona"));
        actor.add("availablePersonas", AgentJson.array(context, "availablePersonas"));
        actor.add("nearbyEntities", AgentJson.array(context, "nearbyEntities"));
        frame.add("actor", actor);

        JsonObject worldKnowledge = AgentJson.object(context, "worldKnowledge");
        JsonObject perception = new JsonObject();
        perception.add("nearbyBlocks", AgentJson.array(context, "nearbyBlocks"));
        perception.add("nearbyContainers", AgentJson.array(context, "nearbyContainers"));
        perception.add("objects", copy(context, "objects"));
        perception.add("targetGrounding", copy(context, "targetGrounding"));
        perception.add("survivalEnvironment", copy(context, "survivalEnvironment"));
        perception.add("modded", copy(context, "modded"));
        perception.add("currentObservation", copy(worldKnowledge, "currentObservation"));
        frame.add("perception", perception);

        JsonObject memory = new JsonObject();
        memory.add("shortTerm", copy(worldKnowledge, "shortTermMemory"));
        memory.add("longTermMap", copy(worldKnowledge, "longTermMap"));
        memory.add("resourceHints", AgentJson.array(worldKnowledge, "resourceHints"));
        memory.add("containerHints", AgentJson.array(worldKnowledge, "containerHints"));
        memory.add("observedAreas", AgentJson.array(worldKnowledge, "observedAreas"));
        memory.add("dangers", AgentJson.array(worldKnowledge, "dangers"));
        memory.add("socialMemory", copy(context, "social"));
        frame.add("memory", memory);

        JsonObject resources = new JsonObject();
        resources.add("inventoryVisible", AgentJson.array(context, "inventory"));
        resources.add("npcAndApprovedResources", copy(context, "resources"));
        resources.add("tools", copy(context, "tools"));
        resources.add("crafting", copy(context, "crafting"));
        resources.add("blueprints", copy(context, "blueprints"));
        resources.add("structureBlueprints", copy(context, "structureBlueprints"));
        resources.add("machineTemplates", copy(context, "machineTemplates"));
        resources.add("travelPolicy", copy(context, "travelPolicy"));
        frame.add("resources", resources);

        JsonObject feedback = new JsonObject();
        feedback.add("executionFeedback", copy(context, "executionFeedback"));
        feedback.add("latestTaskResults", AgentJson.array(context, "latestTaskResults"));
        feedback.add("capabilityGaps", AgentJson.array(context, "capabilityGaps"));
        feedback.add("complexPlan", copy(context, "complexPlan"));
        feedback.add("planFeedback", copy(context, "planFeedback"));
        frame.add("feedback", feedback);

        JsonObject policies = new JsonObject();
        policies.addProperty("playerInventoryMaterials", false);
        policies.addProperty("chestMaterialsRequireApproval", true);
        policies.addProperty("neverAttackPlayers", true);
        policies.addProperty("neverAttackVillagersPetsNamedOrFencedAnimals", true);
        policies.addProperty("highAutonomySafeActionsOnlyWithoutPermission", true);
        policies.addProperty("boundedExplorationOnly", true);
        policies.addProperty("worldChangingActionsRequireWhitelistedAction", true);
        policies.addProperty("complexTasksMustBePauseableRecoverableExplainable", true);
        policies.addProperty("naturalTargetReferencesUseTargetSpecFirst", true);
        policies.addProperty("coordinatesAreFallbackOrDebugOnly", true);
        frame.add("policies", policies);

        frame.add("capabilities", copy(context, "capabilities"));
        frame.add("targetResolver", copy(context, "targetResolver"));
        frame.add("social", copy(context, "social"));
        frame.add("relationship", copy(context, "relationship"));
        frame.add("companion", copy(context, "companion"));
        frame.add("companionLoop", copy(context, "companionLoop"));
        frame.add("availableActions", SkillRegistry.actionPrimitivesJson());
        frame.add("skillRegistry", skillRegistrySummary());
        frame.add("agentLoop", AgentLoop.contractJson());
        return frame;
    }

    private static JsonObject copy(JsonObject object, String key) {
        return AgentJson.object(object, key);
    }

    private static JsonObject skillRegistrySummary() {
        JsonObject summary = new JsonObject();
        summary.addProperty("schemaVersion", "mc-agent-skill-registry-v1");
        summary.addProperty("skillCount", SkillRegistry.skillCount());
        summary.addProperty("fullRegistryPath", "context.skillRegistry");
        summary.addProperty("plannerContract", "Use context.skillRegistry.skills for full SkillSpec details; ObservationFrame keeps only this summary to control prompt size.");
        return summary;
    }
}
