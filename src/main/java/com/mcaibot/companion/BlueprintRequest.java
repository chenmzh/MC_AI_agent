package com.mcaibot.companion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public record BlueprintRequest(
        String templateId,
        BlockPos origin,
        Direction facing,
        String style,
        String materialPreference
) {
    public BlueprintRequest {
        templateId = textOrDefault(templateId, "starter_cabin_7x7");
        origin = origin == null ? BlockPos.ZERO : origin.immutable();
        facing = horizontal(facing);
        style = textOrDefault(style, "rustic");
        materialPreference = textOrDefault(materialPreference, "");
    }

    private static Direction horizontal(Direction direction) {
        if (direction == null || direction.getAxis().isVertical()) {
            return Direction.NORTH;
        }
        return direction;
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
