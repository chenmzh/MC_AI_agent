package com.mcaibot.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.Locale;

public record TargetSpec(
        String source,
        String kind,
        String description,
        String selector,
        String resourceCategory,
        Integer radius,
        BlockPos position,
        JsonObject raw
) {
    public static TargetSpec empty() {
        return new TargetSpec("", "", "", "", "", null, null, new JsonObject());
    }

    public static TargetSpec fromArgs(JsonObject args) {
        if (args == null || args.isEmpty()) {
            return empty();
        }

        JsonObject spec = args.has("targetSpec") && args.get("targetSpec").isJsonObject()
                ? args.getAsJsonObject("targetSpec")
                : new JsonObject();
        if (spec.isEmpty() && hasAny(args, "targetSource", "targetKind", "targetDescription", "targetSelector")) {
            spec.addProperty("source", AgentJson.string(args, "targetSource", ""));
            spec.addProperty("kind", AgentJson.string(args, "targetKind", ""));
            spec.addProperty("description", AgentJson.string(args, "targetDescription", ""));
            spec.addProperty("selector", AgentJson.string(args, "targetSelector", ""));
        }
        if (spec.isEmpty()) {
            return empty();
        }

        return fromJson(spec);
    }

    public static TargetSpec fromJson(JsonObject spec) {
        if (spec == null || spec.isEmpty()) {
            return empty();
        }

        return new TargetSpec(
                normalizeSource(firstString(spec, "source", "anchor", "reference", "mode")),
                normalizeKind(firstString(spec, "kind", "type", "targetType", "objectType")),
                firstString(spec, "description", "text", "phrase", "raw"),
                firstString(spec, "selector", "name", "id", "label"),
                normalizeKind(firstString(spec, "resourceCategory", "category", "material")),
                integer(spec.get("radius")),
                position(spec),
                spec.deepCopy()
        );
    }

    public boolean isPresent() {
        return !source.isBlank() || !kind.isBlank() || !description.isBlank() || position != null;
    }

    public boolean isSource(String value) {
        return source.equals(normalizeSource(value));
    }

    public JsonObject toJson() {
        JsonObject json = raw.deepCopy();
        json.addProperty("source", source);
        json.addProperty("kind", kind);
        json.addProperty("description", description);
        json.addProperty("selector", selector);
        json.addProperty("resourceCategory", resourceCategory);
        if (radius != null) {
            json.addProperty("radius", radius);
        }
        if (position != null) {
            json.add("position", blockPosJson(position));
        }
        json.addProperty("present", isPresent());
        return json;
    }

    private static boolean hasAny(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                return true;
            }
        }
        return false;
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = AgentJson.string(object, key, "");
            if (!value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static Integer integer(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return (int) Math.floor(element.getAsDouble());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static BlockPos position(JsonObject spec) {
        JsonObject source = spec.has("position") && spec.get("position").isJsonObject()
                ? spec.getAsJsonObject("position")
                : spec;
        Integer x = integer(source.get("x"));
        Integer y = integer(source.get("y"));
        Integer z = integer(source.get("z"));
        if (x == null || y == null || z == null) {
            return null;
        }
        return new BlockPos(x, y, z);
    }

    private static String normalizeSource(String value) {
        String normalized = normalize(value);
        return switch (normalized) {
            case "here", "this_place", "current", "current_position", "player_position", "at_player", "where_i_am", "under_player" -> "current_position";
            case "look", "look_at", "looking", "looking_at", "crosshair", "sight", "this_block" -> "looking_at";
            case "near", "nearby", "near_player", "around_player", "beside_player" -> "near_player";
            case "inside", "inside_structure", "current_structure", "inside_current_structure", "standing_in_structure" -> "inside_current_structure";
            case "known", "known_place", "memory", "remembered_place" -> "known_place";
            case "resource", "resource_hint", "known_resource" -> "resource_hint";
            case "position", "coordinates", "coord", "explicit_position", "exact_position" -> "explicit_position";
            default -> normalized;
        };
    }

    private static String normalizeKind(String value) {
        return normalize(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static JsonObject blockPosJson(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }
}
