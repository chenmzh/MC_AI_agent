package com.mcaibot.companion;

import com.google.gson.JsonObject;

public interface BlueprintProvider {
    String id();

    JsonObject catalogJson();

    StructureBlueprint create(BlueprintRequest request);
}
