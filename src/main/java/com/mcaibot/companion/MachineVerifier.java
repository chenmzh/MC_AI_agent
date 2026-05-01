package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.Locale;

public final class MachineVerifier {
    private MachineVerifier() {
    }

    public static ActionResult verify(ServerPlayer player, MachineBlueprint blueprint) {
        ServerLevel level = player.serverLevel();
        JsonObject verification = new JsonObject();
        JsonArray missingSamples = new JsonArray();
        int missingRequired = 0;
        int missingOptional = 0;
        int fluidPlacements = 0;
        int redstonePlacements = 0;
        for (MachinePlacement placement : blueprint.placements()) {
            if (placement.role().contains("water") || placement.role().contains("lava")) {
                fluidPlacements++;
            }
            if (placement.role().contains("redstone") || placement.role().contains("lamp")) {
                redstonePlacements++;
            }
            if (level.getBlockState(placement.pos()).is(placement.state().getBlock())) {
                continue;
            }
            if (placement.optional()) {
                missingOptional++;
            } else {
                missingRequired++;
                if (missingSamples.size() < 12) {
                    missingSamples.add(placement.toJson());
                }
            }
        }
        verification.addProperty("machine", blueprint.id());
        verification.addProperty("missingRequiredBlocks", missingRequired);
        verification.addProperty("missingOptionalBlocks", missingOptional);
        verification.addProperty("fluidPlacements", fluidPlacements);
        verification.addProperty("redstonePlacements", redstonePlacements);
        verification.add("missingSamples", missingSamples);
        verification.add("testProcedure", blueprint.testProcedure());

        if (missingRequired > 0) {
            return ActionResult.blocked("MACHINE_STRUCTURE_INCOMPLETE",
                            "Machine structure is incomplete: missingRequiredBlocks=" + missingRequired + ".",
                            "Rebuild the same machine template at the authorized anchor or pick a clear site.")
                    .withObservation("verification", verification)
                    .withObservation("machine", blueprint.toJson(false));
        }

        JsonObject entityCheck = entityCheck(player, blueprint);
        verification.add("entityCheck", entityCheck);
        int waiting = entityCheck.get("missingEntityKinds").getAsInt();
        if (waiting > 0) {
            return ActionResult.success("WAITING_FOR_ENTITIES",
                            "Machine structure is present, but required entities are not all in place yet.")
                    .withObservation("verification", verification)
                    .withObservation("machine", blueprint.toJson(false))
                    .withEffect("readyForEntities", true)
                    .withEffect("machine", blueprint.id());
        }

        return ActionResult.success("MACHINE_VERIFIED",
                        "Machine structure verified: " + blueprint.label() + ".")
                .withObservation("verification", verification)
                .withObservation("machine", blueprint.toJson(false))
                .withEffect("machine", blueprint.id());
    }

    private static JsonObject entityCheck(ServerPlayer player, MachineBlueprint blueprint) {
        JsonObject json = new JsonObject();
        JsonArray results = new JsonArray();
        int missingKinds = 0;
        for (JsonElement element : blueprint.entityRequirements()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject req = element.getAsJsonObject();
            String entity = req.has("entity") ? req.get("entity").getAsString() : "";
            int required = req.has("count") ? req.get("count").getAsInt() : 0;
            int present = countEntity(player, blueprint, entity);
            JsonObject result = new JsonObject();
            result.addProperty("entity", entity);
            result.addProperty("required", required);
            result.addProperty("presentNearby", present);
            result.addProperty("ready", present >= required);
            if (present < required) {
                missingKinds++;
            }
            results.add(result);
        }
        json.add("requirements", results);
        json.addProperty("missingEntityKinds", missingKinds);
        json.addProperty("policy", "entities must come from the world; this version does not spawn villagers, zombies, or monsters");
        return json;
    }

    private static int countEntity(ServerPlayer player, MachineBlueprint blueprint, String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return 0;
        }
        ServerLevel level = player.serverLevel();
        AABB box = new AABB(blueprint.origin()).inflate(24, Math.max(24, blueprint.height() + 4), 24);
        String expected = entityId.toLowerCase(Locale.ROOT);
        int count = 0;
        for (Entity entity : level.getEntities((Entity) null, box, candidate -> true)) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().toLowerCase(Locale.ROOT);
            if (id.equals(expected)) {
                count++;
            }
        }
        return count;
    }
}
