package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public record ResolvedTarget(
        boolean resolved,
        String code,
        String message,
        String targetType,
        BlockPos position,
        Direction face,
        double confidence,
        boolean unsafe,
        boolean ambiguous,
        JsonObject safety,
        JsonArray candidates,
        TargetSpec spec
) {
    public static ResolvedTarget success(String code, String message, String targetType, BlockPos position, Direction face, double confidence, TargetSpec spec) {
        return new ResolvedTarget(true, code, message, targetType, position, face, confidence, false, false, new JsonObject(), new JsonArray(), spec);
    }

    public static ResolvedTarget blocked(String code, String message, TargetSpec spec) {
        return new ResolvedTarget(false, code, message, "", null, null, 0.0D, false, false, new JsonObject(), new JsonArray(), spec);
    }

    public ResolvedTarget withSafety(JsonObject safety, boolean unsafe) {
        return new ResolvedTarget(resolved, code, message, targetType, position, face, confidence, unsafe, ambiguous,
                safety == null ? new JsonObject() : safety.deepCopy(), candidates, spec);
    }

    public ResolvedTarget withCandidates(JsonArray candidates, boolean ambiguous) {
        return new ResolvedTarget(resolved, code, message, targetType, position, face, confidence, unsafe, ambiguous,
                safety, candidates == null ? new JsonArray() : candidates.deepCopy(), spec);
    }

    public ActionResult toBlockedActionResult(String repair) {
        return ActionResult.blocked(code, message, repair)
                .withObservation("target", toJson());
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", "mc-agent-resolved-target-v1");
        json.addProperty("resolved", resolved);
        json.addProperty("code", code);
        json.addProperty("message", message);
        json.addProperty("targetType", targetType);
        json.addProperty("confidence", confidence);
        json.addProperty("unsafe", unsafe);
        json.addProperty("ambiguous", ambiguous);
        if (position != null) {
            json.add("position", blockPosJson(position));
            json.addProperty("x", position.getX());
            json.addProperty("y", position.getY());
            json.addProperty("z", position.getZ());
        }
        if (face != null) {
            json.addProperty("face", face.getName());
        }
        json.add("safety", safety.deepCopy());
        json.add("candidates", candidates.deepCopy());
        json.add("targetSpec", spec == null ? new JsonObject() : spec.toJson());
        return json;
    }

    private static JsonObject blockPosJson(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }
}
