package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

public final class TravelController {
    public static final int KNOWN_RESOURCE_MAX_DISTANCE = 192;
    public static final int SCOUT_RING_MAX_RADIUS = 96;
    public static final int DEFAULT_TIME_BUDGET_SECONDS = 180;

    private TravelController() {
    }

    public static JsonObject policyJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", "mc-agent-travel-policy-v1");
        json.addProperty("knownResourceMaxDistance", KNOWN_RESOURCE_MAX_DISTANCE);
        json.addProperty("scoutRingMaxRadius", SCOUT_RING_MAX_RADIUS);
        json.addProperty("defaultTimeBudgetSeconds", DEFAULT_TIME_BUDGET_SECONDS);
        json.addProperty("sameDimensionOnly", true);
        json.addProperty("originReturnPointSaved", true);
        json.addProperty("stopOnLowHealthOrNightDanger", true);
        json.addProperty("mobilityRepair", "legacy navigation handles 1-block step/jump and bounded scaffold bridge/step placement when materials are available");
        JsonArray supported = new JsonArray();
        supported.add("ordinary_pathfinding");
        supported.add("one_block_step_or_jump");
        supported.add("short_scaffold_bridge");
        supported.add("shallow_water_swim");
        supported.add("short_safe_dive_for_unstuck_or_nearby_material");
        json.add("supportedMovement", supported);
        return json;
    }

    public static JsonObject travelState(ServerPlayer player, BlockPos target, String purpose) {
        JsonObject json = policyJson();
        BlockPos origin = player == null ? BlockPos.ZERO : player.blockPosition();
        BlockPos goal = target == null ? origin : target.immutable();
        json.addProperty("purpose", purpose == null ? "" : purpose);
        json.addProperty("originX", origin.getX());
        json.addProperty("originY", origin.getY());
        json.addProperty("originZ", origin.getZ());
        json.addProperty("returnPointX", origin.getX());
        json.addProperty("returnPointY", origin.getY());
        json.addProperty("returnPointZ", origin.getZ());
        json.addProperty("targetX", goal.getX());
        json.addProperty("targetY", goal.getY());
        json.addProperty("targetZ", goal.getZ());
        json.addProperty("pathBudgetBlocks", KNOWN_RESOURCE_MAX_DISTANCE);
        json.addProperty("stuckCount", 0);
        return json;
    }
}
