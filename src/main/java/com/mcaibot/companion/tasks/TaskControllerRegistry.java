package com.mcaibot.companion.tasks;

import com.google.gson.JsonArray;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TaskControllerRegistry {
    private static final Map<String, TaskController> CONTROLLERS = createControllers();

    private TaskControllerRegistry() {
    }

    public static Collection<TaskController> all() {
        return CONTROLLERS.values();
    }

    public static Optional<TaskController> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CONTROLLERS.get(name));
    }

    public static JsonArray catalogJson() {
        JsonArray catalog = new JsonArray();
        for (TaskController controller : CONTROLLERS.values()) {
            catalog.add(controller.metadata().toJson());
        }
        return catalog;
    }

    private static Map<String, TaskController> createControllers() {
        Map<String, TaskController> controllers = new LinkedHashMap<>();
        register(controllers, metadata(
                "harvest_logs",
                false,
                true,
                "Harvests nearby logs through the legacy NpcManager timed block-breaking task.",
                List.of("active_npc", "owner_online_same_dimension", "usable_or_craftable_axe", "reachable_exposed_logs_or_follow_owner"),
                List.of("axe_durability", "npc_storage_free_slot_or_container_space", "optional_support_blocks"),
                List.of("npc_runtime", "npc_navigation", "npc_inventory", "nearby_log_blocks"),
                List.of("timed_block_breaking", "tool_required", "bounded_radius", "does_not_use_player_inventory"),
                targetScopePolicy(true, true, true, false, "owner_radius_or_follow_owner"),
                List.of("raw_logs", "dropped_items", "tool_durability_used")
        ));
        register(controllers, metadata(
                "mine_nearby_ore",
                false,
                true,
                "Mines nearby exposed ores through the legacy NpcManager timed block-breaking task.",
                List.of("active_npc", "owner_online_same_dimension", "usable_or_craftable_pickaxe", "reachable_exposed_ore"),
                List.of("pickaxe_durability", "npc_storage_free_slot_or_container_space"),
                List.of("npc_runtime", "npc_navigation", "npc_inventory", "nearby_ore_blocks"),
                List.of("timed_block_breaking", "tool_required", "bounded_radius", "ore_tier_must_be_safe", "does_not_use_player_inventory"),
                targetScopePolicy(true, true, true, false, "owner_radius"),
                List.of("ores_or_drops", "tool_durability_used")
        ));
        register(controllers, metadata(
                "gather_stone",
                false,
                true,
                "Gathers reachable stone/cobblestone-like blocks through the legacy NpcManager timed block-breaking task.",
                List.of("active_npc", "owner_online_same_dimension", "usable_or_craftable_pickaxe", "reachable_stone_like_block"),
                List.of("pickaxe_durability", "npc_storage_free_slot_or_container_space"),
                List.of("npc_runtime", "npc_navigation", "npc_inventory", "nearby_stone_blocks"),
                List.of("timed_block_breaking", "tool_required", "bounded_radius", "does_not_use_player_inventory"),
                targetScopePolicy(true, true, true, false, "owner_radius"),
                List.of("cobblestone_like_drops", "tool_durability_used")
        ));
        register(controllers, metadata(
                "collect_items",
                true,
                true,
                "Collects nearby dropped item entities through the legacy NpcManager collection task.",
                List.of("active_npc", "owner_online_same_dimension", "nearby_item_entities"),
                List.of("npc_storage_free_slot_or_container_space_or_owner_inventory_fallback"),
                List.of("npc_runtime", "npc_navigation", "npc_inventory", "nearby_item_entities"),
                List.of("bounded_radius", "does_not_break_blocks", "prefers_npc_storage_before_owner_inventory"),
                targetScopePolicy(true, true, true, false, "owner_radius"),
                List.of("items_in_npc_storage_or_nearby_container_or_owner_inventory")
        ));
        register(controllers, metadata(
                "build_basic_house",
                false,
                true,
                "Builds the legacy 5x5 basic shelter blueprint.",
                List.of("active_npc", "owner_online_same_dimension", "clear_build_volume", "placeable_blocks_available_or_approved_container_materials"),
                List.of("placeable_blocks", "npc_storage_materials", "approved_container_materials"),
                List.of("npc_runtime", "npc_navigation", "npc_inventory", "build_volume", "nearby_containers"),
                List.of("does_not_use_player_inventory", "container_materials_require_approval", "only_places_when_collision_safe", "bounded_blueprint"),
                targetScopePolicy(true, true, false, true, "current_or_explicit_build_anchor"),
                List.of("shelter_blocks_placed", "materials_consumed")
        ));
        register(controllers, metadata(
                "build_large_house",
                false,
                true,
                "Builds the legacy 7x7 large house blueprint with existing material-gather resume behavior.",
                List.of("active_npc", "owner_online_same_dimension", "clear_build_volume", "large_placeable_block_budget_or_gatherable_wood"),
                List.of("placeable_blocks", "logs_or_planks", "npc_storage_materials", "approved_container_materials"),
                List.of("npc_runtime", "npc_navigation", "npc_inventory", "build_volume", "nearby_containers", "nearby_trees_during_gather_resume"),
                List.of("does_not_use_player_inventory", "container_materials_require_approval", "only_places_when_collision_safe", "bounded_gather_resume"),
                targetScopePolicy(true, true, false, true, "current_or_explicit_build_anchor"),
                List.of("large_house_blocks_placed", "materials_consumed", "may_trigger_harvest_logs")
        ));
        register(controllers, metadata(
                "repair_structure",
                false,
                true,
                "Repairs a nearby existing structure by scanning the shell, inferring wall material, patching wall gaps, and crafting/placing a door when a clear doorway is missing one.",
                List.of("active_npc", "owner_online_same_dimension", "clear_nearby_building_shell", "repair_materials_or_craftable_door_materials"),
                List.of("dominant_wall_material", "npc_storage_materials", "approved_container_materials", "door_planks_when_needed"),
                List.of("npc_runtime", "npc_navigation", "npc_inventory", "nearby_structure", "nearby_containers"),
                List.of("does_not_use_player_inventory", "container_materials_require_approval", "only_repairs_nearby_candidate_structure", "bounded_scan_radius"),
                targetScopePolicy(true, true, false, true, "player_stands_near_or_inside_target_structure"),
                List.of("missing_wall_blocks_patched", "missing_door_placed", "materials_consumed")
        ));
        register(controllers, metadata(
                "craft_at_table",
                false,
                true,
                "Crafts requested items at a nearby crafting table through the legacy crafting task.",
                List.of("active_npc", "owner_online_same_dimension", "reachable_crafting_table", "known_supported_recipe"),
                List.of("npc_storage_materials", "approved_container_materials_when_explicit"),
                List.of("npc_runtime", "npc_navigation", "npc_inventory", "crafting_table", "nearby_containers_when_approved"),
                List.of("does_not_use_player_inventory", "container_materials_require_explicit_request_or_approval", "bounded_radius"),
                targetScopePolicy(true, true, false, true, "nearby_crafting_table_or_explicit_position"),
                List.of("crafted_items", "materials_consumed")
        ));
        register(controllers, metadata(
                "container_transfer",
                false,
                true,
                "Moves items between NPC storage and nearby containers through legacy chest transfer actions.",
                List.of("active_storage_capable_npc", "reachable_container", "item_and_count_clear"),
                List.of("npc_storage_items", "container_items", "free_slots"),
                List.of("npc_runtime", "npc_navigation", "npc_inventory", "target_container"),
                List.of("nearby_container_only", "explicit_item_transfer", "material_use_approval_required_for_automatic_build_or_craft"),
                targetScopePolicy(true, true, false, true, "nearby_container_or_explicit_container"),
                List.of("items_moved")
        ));
        return Collections.unmodifiableMap(controllers);
    }

    private static void register(Map<String, TaskController> controllers, TaskControllerMetadata metadata) {
        controllers.put(metadata.name(), new MetadataOnlyTaskController(metadata));
    }

    private static TaskControllerMetadata metadata(
            String name,
            boolean parallelSafe,
            boolean worldChanging,
            String description,
            List<String> requirements,
            List<String> resources,
            List<String> locks,
            List<String> safety,
            com.google.gson.JsonObject targetScopePolicy,
            List<String> effects
    ) {
        return new TaskControllerMetadata(
                name,
                parallelSafe,
                worldChanging,
                description,
                requirements,
                resources,
                locks,
                safety,
                targetScopePolicy,
                effects,
                true
        );
    }

    private static com.google.gson.JsonObject targetScopePolicy(
            boolean active,
            boolean single,
            boolean all,
            boolean requiresDisambiguation,
            String defaultAnchor
    ) {
        com.google.gson.JsonObject policy = new com.google.gson.JsonObject();
        policy.addProperty("active", active);
        policy.addProperty("single", single);
        policy.addProperty("all", all);
        policy.addProperty("requiresDisambiguation", requiresDisambiguation);
        policy.addProperty("defaultAnchor", defaultAnchor);
        return policy;
    }

    private record MetadataOnlyTaskController(TaskControllerMetadata metadata) implements TaskController {
    }
}
