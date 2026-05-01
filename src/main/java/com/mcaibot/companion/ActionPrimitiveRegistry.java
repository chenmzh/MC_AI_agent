package com.mcaibot.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

public final class ActionPrimitiveRegistry {
    private ActionPrimitiveRegistry() {
    }

    public static ActionResult execute(ServerPlayer player, ActionCall call) {
        if (call == null || !call.isPresent()) {
            return ActionResult.blocked("NO_ACTION_CALL", "No ActionCall was provided.", "Return an ActionCall with a registered primitive name.");
        }

        String name = normalize(call.name());
        JsonObject args = call.args() == null ? new JsonObject() : call.args();

        try {
            ActionResult result = switch (name) {
                case "observe_environment" -> SurvivalActions.reportEnvironment(player);
                case "report_nearby", "scan_nearby", "scan_hostiles" -> {
                    BridgeActions.reportNearby(player, radius(args));
                    yield ActionResult.success("DONE", "Reported nearby entities from the current observation.");
                }
                case "prepare_basic_tools", "prepare_axe", "prepare_pickaxe" -> withNpc(player, "prepare_basic_tools", () -> {
                    boolean requireAxe = boolArg(args, "requireAxe", !"prepare_pickaxe".equals(name));
                    boolean requirePickaxe = boolArg(args, "requirePickaxe", "prepare_pickaxe".equals(name));
                    boolean ready = NpcManager.prepareBasicTools(player, requireAxe, requirePickaxe);
                    if (!ready) {
                        return ActionResult.blocked("BASIC_TOOLS_NOT_READY",
                                "Could not prepare the requested basic tool from NPC storage or approved nearby containers.",
                                "Gather sticks/planks/cobblestone, approve nearby chest materials, or ask the player for tool materials.");
                    }
                    return ActionResult.success("DONE", "Prepared requested basic tools from NPC storage or approved nearby container materials.")
                            .withEffect("requireAxe", requireAxe)
                            .withEffect("requirePickaxe", requirePickaxe);
                });
                case "report_resources", "assess_resources" -> {
                    ResourceAssessment.report(player);
                    yield ActionResult.success("DONE", "Reported NPC storage, approved container resources, and blueprint material gaps.")
                            .withObservation("resources", ResourceAssessment.snapshotFor(player));
                }
                case "survival_assist", "help_me_survive" -> withNpc(player, "survival_assist", () ->
                        SurvivalActions.survivalAssist(player));
                case "till_field", "prepare_field" -> withNpc(player, "till_field", () ->
                        SurvivalActions.tillField(player, radius(args)));
                case "plant_crop", "plant_crops" -> withNpc(player, "plant_crop", () ->
                        SurvivalActions.plantCrop(player, firstString(args, "crop", "item", "seed", "targetCrop"), radius(args)));
                case "harvest_crops" -> withNpc(player, "harvest_crops", () ->
                        SurvivalActions.harvestCrops(player, radius(args)));
                case "hunt_food_animal", "hunt_animal" -> withNpc(player, "hunt_food_animal", () ->
                        SurvivalActions.huntFoodAnimal(player, firstString(args, "animal", "entity", "targetAnimal"), radius(args)));
                case "feed_animal" -> withNpc(player, "feed_animal", () ->
                        SurvivalActions.feedAnimal(player, firstString(args, "animal", "entity", "targetAnimal"), radius(args)));
                case "breed_animals", "breed_animal" -> withNpc(player, "breed_animals", () ->
                        SurvivalActions.breedAnimals(player, firstString(args, "animal", "entity", "targetAnimal"), radius(args)));
                case "tame_animal" -> withNpc(player, "tame_animal", () ->
                        SurvivalActions.tameAnimal(player, firstString(args, "animal", "entity", "targetAnimal"), radius(args)));
                case "build_redstone_template", "redstone_template" -> withNpc(player, "build_redstone_template", () ->
                        SurvivalActions.buildRedstoneTemplate(player, firstString(args, "template", "name", "structure")));
                case "gather_materials" -> withNpc(player, "gather_materials", () ->
                        MaterialGatherer.gatherMaterials(player,
                                firstString(args, "material", "category", "target", "item", "block"),
                                count(args, 64)));
                case "preview_structure" -> withNpc(player, "preview_structure", () ->
                        StructureBuildController.previewStructure(player,
                                firstString(args, "template", "templateId", "structure", "blueprint"),
                                blockPos(args),
                                directionFromName(firstStringFromValue(firstString(args, "forward", "direction", "facing"), player.getDirection().getName())),
                                firstString(args, "style", "palette", "materialPreference")));
                case "build_structure" -> withNpc(player, "build_structure", () ->
                        StructureBuildController.buildStructure(player,
                                firstString(args, "template", "templateId", "structure", "blueprint"),
                                blockPos(args),
                                directionFromName(firstStringFromValue(firstString(args, "forward", "direction", "facing"), player.getDirection().getName())),
                                firstString(args, "style", "palette", "materialPreference"),
                                boolArg(args, "autoGather", true)));
                case "cancel_structure" -> withNpc(player, "cancel_structure", () ->
                        StructureBuildController.cancelStructure(player));
                case "move_to", "goto_position" -> moveTo(player, args);
                case "come_to_player", "come" -> withNpc(player, "come_to_player", () -> {
                    NpcManager.comeTo(player);
                    return ActionResult.started("STARTED", "NPC is moving to the player.");
                });
                case "follow_player", "follow" -> withNpc(player, "follow_player", () -> {
                    NpcManager.follow(player);
                    return ActionResult.started("STARTED", "NPC is following the player.");
                });
                case "collect_items", "collect" -> withNpc(player, "collect_items", () -> {
                    NpcManager.collectItems(player, radius(args));
                    return ActionResult.started("STARTED", "NPC started collecting nearby dropped items.");
                });
                case "harvest_logs", "gather_wood", "wood" -> withNpc(player, "harvest_logs", () -> {
                    NpcManager.harvestLogs(player, radius(args), durationSeconds(args));
                    return ActionResult.started("STARTED", "NPC started bounded log harvesting.");
                });
                case "mine_nearby_ore", "mine" -> withNpc(player, "mine_nearby_ore", () -> {
                    NpcManager.mineOres(player, radius(args));
                    return ActionResult.started("STARTED", "NPC started mining nearby exposed ore.");
                });
                case "gather_stone", "mine_stone", "gather_cobblestone", "collect_cobblestone" -> withNpc(player, "gather_stone", () -> {
                    int targetCount = count(args, 3);
                    NpcManager.gatherStone(player, radius(args), targetCount <= 0 ? 3 : targetCount);
                    return ActionResult.started("STARTED", "NPC started gathering stone/cobblestone-like material.")
                            .withEffect("targetCount", targetCount <= 0 ? 3 : targetCount);
                });
                case "inspect_block" -> inspectBlock(player, args);
                case "break_block" -> withNpc(player, "break_block", () -> {
                    BlockPos pos = blockPos(args);
                    if (pos == null) {
                        return ActionResult.blocked("MISSING_POSITION", "break_block needs exact x/y/z coordinates.", "Call inspect_block/report_nearby or ask the player for coordinates.");
                    }
                    NpcManager.breakBlockAt(player, pos);
                    return ActionResult.started("STARTED", "NPC started player-like block breaking.");
                });
                case "place_block" -> withNpc(player, "place_block", () -> {
                    BlockPos pos = blockPos(args);
                    if (pos == null) {
                        return ActionResult.blocked("MISSING_POSITION", "place_block needs exact x/y/z coordinates.", "Ask the player for coordinates or derive them from a verified blueprint.");
                    }
                    String block = firstString(args, "block", "item", "material", "blockPreference");
                    if (block.isBlank()) {
                        return ActionResult.blocked("MISSING_BLOCK", "place_block needs a requested block/material.", "Choose a placeable material from resources or ask the player.");
                    }
                    NpcManager.placeBlockAt(player, pos, block);
                    return ActionResult.started("STARTED", "NPC started player-like block placement.");
                });
                case "craft_item" -> withNpc(player, "craft_item", () -> {
                    String item = firstString(args, "item", "targetItem", "recipe");
                    if (item.isBlank()) {
                        return ActionResult.blocked("MISSING_ITEM", "craft_item needs a supported item.", "Ask for item or select from ToolSummary/crafting context.");
                    }
                    String canonical = NpcManager.canonicalCraftItem(item);
                    if (canonical.isBlank()) {
                        return ActionResult.blocked("UNSUPPORTED_CRAFT",
                                "craft_item supports axe, pickaxe, planks, sticks, door, and basic_tools; unsupported request: " + item + ".",
                                "Normalize aliases such as wood arx to axe, or save a higher-level plan for unsupported recipes.");
                    }
                    boolean crafted = NpcManager.craftItem(player, item, count(args, 1));
                    if (!crafted) {
                        return ActionResult.blocked("CRAFT_NOT_COMPLETED",
                                "NPC could not complete primitive crafting for " + canonical + ".",
                                "Inspect resources, approve chest materials if appropriate, gather materials, or ask a clarifying question.");
                    }
                    return ActionResult.success("DONE", "NPC completed primitive crafting for " + canonical + ".")
                            .withEffect("item", canonical);
                });
                case "craft_at_table" -> withNpc(player, "craft_at_table", () -> craftAtTable(player, args, false));
                case "craft_from_chest_at_table" -> withNpc(player, "craft_from_chest_at_table", () -> craftAtTable(player, args, true));
                case "equip_item", "equip_best_gear", "auto_equip" -> withNpc(player, "equip_item", () -> {
                    NpcManager.autoEquipNow(player);
                    return ActionResult.success("DONE", "NPC equipped the best available gear from NPC storage, or reported it was already best.");
                });
                case "open_container", "report_containers", "report_chests" -> {
                    NpcManager.chestStatus(player);
                    yield ActionResult.success("DONE", "Reported nearby container rules and contents summary.");
                }
                case "withdraw_from_chest", "take_from_chest" -> withNpc(player, "withdraw_from_chest", () -> {
                    String item = firstString(args, "item", "targetItem");
                    if (item.isBlank()) {
                        return ActionResult.blocked("MISSING_ITEM", "withdraw_from_chest needs an explicit item.", "Ask which item/count to take.");
                    }
                    NpcManager.withdrawFromNearbyChest(player, item, count(args, 64));
                    return ActionResult.started("STARTED", "NPC attempted nearby container withdrawal.");
                });
                case "deposit_to_chest", "deposit_item_to_chest" -> withNpc(player, "deposit_to_chest", () -> {
                    String item = firstString(args, "item", "targetItem");
                    if (item.isBlank()) {
                        NpcManager.depositNpcStorageToNearbyChest(player);
                    } else {
                        NpcManager.depositItemToNearbyChest(player, item, count(args, 2304));
                    }
                    return ActionResult.started("STARTED", "NPC attempted nearby container deposit.");
                });
                case "guard_player", "protect_player" -> withNpc(player, "guard_player", () -> {
                    ProtectionManager.start(player, radius(args));
                    return ActionResult.started("STARTED", "Protection mode started. The NPC will attack hostile mobs but never player entities.");
                });
                case "build_basic_house", "build_basic_shelter" -> withNpc(player, "build_basic_house", () -> {
                    BlockPos pos = blockPos(args);
                    return StructureBuildController.buildStructure(player, "starter_cabin_7x7", pos,
                                    directionFromName(firstStringFromValue(firstString(args, "forward", "direction", "facing"), player.getDirection().getName())),
                                    firstString(args, "style", "palette", "materialPreference"),
                                    false)
                            .withEffect("taskName", "build_basic_house");
                });
                case "build_large_house", "large_house" -> withNpc(player, "build_large_house", () -> {
                    BlockPos pos = blockPos(args);
                    return StructureBuildController.buildStructure(player, "starter_cabin_7x7", pos,
                                    directionFromName(firstStringFromValue(firstString(args, "forward", "direction", "facing"), player.getDirection().getName())),
                                    firstString(args, "style", "palette", "materialPreference"),
                                    true)
                            .withEffect("taskName", "build_large_house");
                });
                case "repair_structure", "repair_house", "repair_wall", "repair_door", "patch_house", "fix_house" -> withNpc(player, "repair_structure", () -> {
                    boolean started = NpcManager.repairNearbyStructure(player, radius(args));
                    if (!started) {
                        return ActionResult.blocked("REPAIR_NOT_STARTED",
                                "Structure repair did not start. The runtime reported whether the nearby shell was unclear, already complete, or missing materials.",
                                "Stand inside/near the damaged structure, approve chest materials if needed, or provide exact coordinates/material.");
                    }
                    return ActionResult.started("STARTED", "NPC started structure repair from inferred nearby shell.")
                            .withEffect("taskName", "repair_structure");
                });
                case "stop_guard" -> {
                    ProtectionManager.stop(player);
                    yield ActionResult.success("DONE", "Protection mode stopped.");
                }
                case "report_modded_nearby" -> {
                    ModInteractionManager.reportNearby(player, radius(args));
                    yield ActionResult.success("DONE", "Reported nearby Create-family/modded blocks.");
                }
                case "inspect_mod_block" -> {
                    BlockPos pos = blockPos(args);
                    if (pos == null) {
                        ModInteractionManager.reportNearby(player, radius(args));
                        yield ActionResult.blocked("MISSING_POSITION", "No exact modded block position was provided; reported nearby modded blocks instead.", "Choose one reported coordinate and call inspect_mod_block again.");
                    }
                    ModInteractionManager.inspectBlock(player, pos);
                    yield ActionResult.success("DONE", "Inspected the exact modded block.");
                }
                case "use_mod_wrench" -> {
                    BlockPos pos = blockPos(args);
                    if (pos == null) {
                        yield ActionResult.blocked("MISSING_POSITION", "use_mod_wrench needs exact x/y/z coordinates.", "Inspect/report nearby modded blocks and ask the player to confirm a target.");
                    }
                    ModInteractionManager.useWrench(player, pos);
                    yield ActionResult.started("STARTED", "Attempted safe wrench interaction on the target block.");
                }
                case "interact_block" -> ActionResult.blocked("INTERACT_NOT_GENERIC", "Generic interact_block is not exposed yet because mod/block semantics differ.", "Use inspect_block, inspect_mod_block, use_mod_wrench, open_container, or ask for a specific interaction.");
                case "attack_entity" -> ActionResult.blocked("USE_GUARD_PLAYER", "Direct arbitrary attack_entity is not exposed; protection mode safely filters hostiles and never attacks players.", "Use guard_player/protect_player or ask for a safer combat policy.");
                case "recover_mobility" -> ActionResult.blocked("RUNTIME_REPAIR_AUTOMATIC", "Mobility repair is handled by task controllers when a blocker occurs.", "Report latestTaskResults and retry the blocked task once, or ask for scaffold/path blocks.");
                default -> ActionResult.blocked("UNKNOWN_PRIMITIVE", "Unknown ActionCall primitive '" + call.name() + "'.", "Select a primitive from ObservationFrame.availableActions.");
            };
            return enrichResult(player, call, name, result);
        } catch (RuntimeException error) {
            return ActionResult.failed("EXECUTION_EXCEPTION", error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static ActionResult moveTo(ServerPlayer player, JsonObject args) {
        BlockPos pos = blockPos(args);
        if (pos == null) {
            return ActionResult.blocked("MISSING_POSITION", "move_to needs exact x/y/z coordinates.", "Ask for a target position or use a skill that can derive one.");
        }
        return withNpc(player, "move_to", () -> {
            NpcManager.goTo(player, pos.getX(), pos.getY(), pos.getZ());
            return ActionResult.started("STARTED", "NPC is pathing to " + pos.toShortString() + ".");
        });
    }

    private static ActionResult inspectBlock(ServerPlayer player, JsonObject args) {
        BlockPos pos = blockPos(args);
        if (pos == null) {
            return ActionResult.blocked("MISSING_POSITION", "inspect_block needs exact x/y/z coordinates.", "Ask for a target block position or use report_nearby first.");
        }
        NpcManager.inspectBlock(player, pos);
        return ActionResult.success("DONE", "Inspected block at " + pos.toShortString() + ".");
    }

    private static ActionResult craftAtTable(ServerPlayer player, JsonObject args, boolean allowContainerMaterials) {
        String item = firstString(args, "item", "targetItem", "recipe");
        if (item.isBlank()) {
            return ActionResult.blocked("MISSING_ITEM", "craft_at_table needs a supported item.", "Ask for item or select from crafting context.");
        }
        NpcManager.craftAtNearbyTable(player, item, count(args, 1), allowContainerMaterials);
        return ActionResult.started("STARTED", "NPC started crafting " + item + " at a nearby table.");
    }

    private static ActionResult withNpc(ServerPlayer player, String action, PrimitiveExecution execution) {
        JsonObject npc = NpcManager.describeFor(player);
        if (!npc.has("spawned") || !npc.get("spawned").getAsBoolean()) {
            return ActionResult.blocked("NO_NPC", action + " needs a spawned companion NPC.", "Spawn an NPC with /mcai npc spawn or select an existing NPC.");
        }
        return execution.run();
    }

    private static BlockPos blockPos(JsonObject args) {
        JsonObject position = args.has("position") && args.get("position").isJsonObject()
                ? args.getAsJsonObject("position")
                : args;
        Integer x = integer(position.get("x"));
        Integer y = integer(position.get("y"));
        Integer z = integer(position.get("z"));
        if (x == null || y == null || z == null) {
            return null;
        }
        return new BlockPos(x, y, z);
    }

    private static Integer integer(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return (int) Math.floor(element.getAsDouble());
    }

    private static int radius(JsonObject args) {
        return clamp(intArg(args, "radius", McAiConfig.NPC_TASK_RADIUS.get()), 4, 128);
    }

    private static int durationSeconds(JsonObject args) {
        return clamp(intArg(args, "durationSeconds", 90), 10, 300);
    }

    private static int count(JsonObject args, int fallback) {
        return clamp(intArg(args, "count", fallback), 0, 2304);
    }

    private static int intArg(JsonObject args, String key, int fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return fallback;
        }
        return (int) Math.floor(args.get(key).getAsDouble());
    }

    private static boolean boolArg(JsonObject args, String key, boolean fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return args.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String firstString(JsonObject args, String... keys) {
        for (String key : keys) {
            if (args != null && args.has(key) && !args.get(key).isJsonNull()) {
                String value = args.get(key).getAsString();
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static Direction directionFromName(String name) {
        String normalized = firstStringFromValue(name, Direction.NORTH.getName()).toLowerCase(Locale.ROOT);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (direction.getName().equals(normalized)) {
                return direction;
            }
        }
        return Direction.NORTH;
    }

    private static String firstStringFromValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static ActionResult enrichResult(ServerPlayer player, ActionCall call, String actionName, ActionResult result) {
        JsonObject npc = NpcManager.describeFor(player);
        JsonObject observations = new JsonObject();
        observations.addProperty("actionName", actionName);
        observations.add("actionCall", call.toJson());
        observations.add("npc", npc);
        observations.add("taskFeedback", TaskFeedback.snapshotJson(player, npc));
        observations.add("resources", ResourceAssessment.snapshotFor(player));
        return result.withEffect("actionName", actionName).withObservations(observations);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    @FunctionalInterface
    private interface PrimitiveExecution {
        ActionResult run();
    }
}
