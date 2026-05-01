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
                "gather_materials",
                false,
                true,
                "Acquires requested build materials through source priority: NPC storage, approved containers, known resource hints, then bounded same-dimension scouting.",
                List.of("active_npc", "owner_online_same_dimension", "material_category", "bounded_count"),
                List.of("npc_storage_materials", "approved_container_materials", "known_resource_hints", "bounded_scout_budget"),
                List.of("npc_runtime", "npc_navigation", "npc_inventory", "world_knowledge_resource_hints"),
                List.of("does_not_use_player_inventory", "container_materials_require_approval", "same_dimension_only", "bounded_time_and_distance"),
                targetScopePolicy(true, true, false, true, "owner_radius_or_known_resource_hint"),
                List.of("material_count_ready", "collect_items_may_start", "blocker_reports_missing_source")
        ));
        register(controllers, metadata(
                "preview_structure",
                true,
                false,
                "Previews a deterministic blueprint template, site blockers, and material budget without modifying the world.",
                List.of("template_or_structure_intent", "owner_online_same_dimension"),
                List.of("blueprint_catalog", "resources", "build_anchor"),
                List.of("blueprint_registry", "resource_assessment", "site_scan"),
                List.of("no_world_change", "does_not_use_player_inventory"),
                targetScopePolicy(true, true, false, true, "player_relative_or_explicit_build_anchor"),
                List.of("blueprint_preview", "site_check", "material_plan")
        ));
        register(controllers, metadata(
                "build_structure",
                false,
                true,
                "Builds a deterministic blueprint template with safe site checks, role-based block candidates, optional decoration skipping, and material-gather recovery.",
                List.of("active_npc", "owner_online_same_dimension", "approved_template", "safe_build_volume", "materials_or_auto_gather"),
                List.of("npc_storage_materials", "approved_container_materials", "placeable_blocks", "blueprint_template"),
                List.of("npc_runtime", "npc_inventory", "build_volume", "nearby_containers"),
                List.of("does_not_use_player_inventory", "container_materials_require_approval", "will_not_clear_base_core_blocks", "template_only"),
                targetScopePolicy(true, true, false, true, "player_relative_or_explicit_build_anchor"),
                List.of("blueprint_blocks_placed", "optional_decorations_skipped_if_missing", "may_trigger_gather_materials")
        ));
        register(controllers, metadata(
                "cancel_structure",
                true,
                false,
                "Stops an active structure/build-related runtime task.",
                List.of("active_npc"),
                List.of("npc_runtime"),
                List.of("npc_runtime"),
                List.of("safe_stop"),
                targetScopePolicy(true, true, false, false, "active_npc"),
                List.of("active_task_stopped_or_idle")
        ));
        register(controllers, metadata(
                "preview_machine",
                true,
                false,
                "Previews a deterministic redstone or survival-machine template, including risk, footprint, material budget, entity needs, and site blockers.",
                List.of("template_or_machine_intent", "owner_online_same_dimension"),
                List.of("machine_template_catalog", "npc_storage_materials", "approved_container_materials", "build_anchor"),
                List.of("machine_template_registry", "machine_safety_checker", "resource_assessment"),
                List.of("no_world_change", "does_not_use_player_inventory", "high_risk_requires_saved_plan_before_build"),
                targetScopePolicy(true, true, false, true, "player_relative_or_explicit_machine_anchor"),
                List.of("machine_preview", "risk_summary", "material_plan", "site_check")
        ));
        register(controllers, metadata(
                "authorize_machine_plan",
                true,
                false,
                "Creates a saved build authorization for the exact high-risk machine template, anchor, dimension, facing, range, and risk level.",
                List.of("template_or_machine_intent", "owner_online_same_dimension", "player_confirmation_after_preview"),
                List.of("machine_template_catalog", "build_anchor", "plan_ttl"),
                List.of("machine_authorization_store", "machine_template_registry"),
                List.of("no_world_change", "authorization_binds_player_dimension_anchor_facing_template_and_risk", "expires_automatically"),
                targetScopePolicy(true, true, false, true, "exact_previewed_machine_anchor"),
                List.of("machine_plan_authorized", "authorization_snapshot")
        ));
        register(controllers, metadata(
                "build_machine",
                false,
                true,
                "Builds an authorized deterministic vanilla redstone or survival-machine template by direct placement while consuming NPC or approved-container materials.",
                List.of("active_npc", "owner_online_same_dimension", "matching_saved_plan_for_high_risk_templates", "safe_build_volume", "materials_available"),
                List.of("npc_storage_materials", "approved_container_materials", "machine_template_catalog", "authorization_snapshot"),
                List.of("npc_runtime", "npc_inventory", "build_volume", "nearby_containers", "machine_authorization_store"),
                List.of("does_not_use_player_inventory", "container_materials_require_approval", "template_only", "blocks_protected_base_core_blocks", "does_not_spawn_villagers_or_monsters", "blocks_forbidden_lag_or_exploit_machines"),
                targetScopePolicy(true, true, false, true, "authorized_machine_anchor"),
                List.of("machine_blocks_placed", "materials_consumed", "verification_snapshot", "may_wait_for_entities")
        ));
        register(controllers, metadata(
                "test_machine",
                true,
                false,
                "Verifies a machine template in-place without modifying the world, checking required blocks, redstone/fluid presence, collection paths, and entity readiness.",
                List.of("template_or_machine_intent", "owner_online_same_dimension"),
                List.of("machine_template_catalog", "build_anchor", "nearby_entities", "nearby_blocks"),
                List.of("machine_verifier", "site_scan"),
                List.of("no_world_change", "missing_villagers_or_zombies_report_WAITING_FOR_ENTITIES_not_structure_failure"),
                targetScopePolicy(true, true, false, true, "machine_anchor"),
                List.of("machine_verification", "blocker_or_waiting_state")
        ));
        register(controllers, metadata(
                "cancel_machine_build",
                true,
                false,
                "Cancels the current machine build authorization for the player.",
                List.of("owner_online_same_dimension"),
                List.of("authorization_snapshot"),
                List.of("machine_authorization_store"),
                List.of("safe_stop", "no_world_change"),
                targetScopePolicy(true, true, false, false, "current_player_authorization"),
                List.of("machine_authorization_cancelled_or_idle")
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
        register(controllers, metadata(
                "survival_assist",
                false,
                true,
                "Chooses the first survival-priority action from environment observation: guard, harvest crops, gather logs, or report blockers.",
                List.of("active_npc", "owner_online_same_dimension", "survival_environment_snapshot"),
                List.of("npc_inventory", "nearby_threats", "nearby_resources"),
                List.of("npc_runtime", "npc_navigation", "npc_inventory", "survival_environment"),
                List.of("high_autonomy_safe_policy", "world_changes_only_through_whitelisted_actions", "does_not_use_player_inventory"),
                targetScopePolicy(true, true, false, true, "owner_radius"),
                List.of("priority_survival_action_started_or_reported")
        ));
        register(controllers, metadata(
                "till_field",
                false,
                true,
                "Tills a bounded starter field near water using a hoe from NPC storage.",
                List.of("active_npc", "owner_online_same_dimension", "hoe_in_npc_storage", "nearby_tillable_soil", "nearby_water"),
                List.of("hoe_durability", "tillable_soil"),
                List.of("npc_runtime", "npc_inventory", "nearby_field_blocks"),
                List.of("requires_player_command_or_saved_plan_permission", "bounded_rectangle", "does_not_use_player_inventory"),
                targetScopePolicy(true, true, false, true, "owner_radius_near_water"),
                List.of("farmland_blocks_created")
        ));
        register(controllers, metadata(
                "plant_crop",
                false,
                true,
                "Plants common crops on nearby empty farmland from NPC storage.",
                List.of("active_npc", "owner_online_same_dimension", "seed_or_crop_item_in_npc_storage", "empty_farmland_nearby"),
                List.of("seeds_or_crop_items"),
                List.of("npc_runtime", "npc_inventory", "nearby_farmland"),
                List.of("uses_only_npc_storage", "bounded_radius"),
                targetScopePolicy(true, true, false, true, "owner_radius_field"),
                List.of("crop_blocks_planted", "seeds_consumed")
        ));
        register(controllers, metadata(
                "harvest_crops",
                true,
                true,
                "Harvests mature nearby crops only, then starts dropped-item collection.",
                List.of("active_npc", "owner_online_same_dimension", "mature_crops_nearby"),
                List.of("npc_storage_free_slot_or_container_space"),
                List.of("npc_runtime", "npc_navigation", "nearby_crops", "collect_items"),
                List.of("mature_crops_only", "bounded_radius", "safe_autonomous_action"),
                targetScopePolicy(true, true, true, false, "owner_radius"),
                List.of("crop_drops", "seeds", "collect_items_started")
        ));
        register(controllers, metadata(
                "hunt_food_animal",
                false,
                true,
                "Attacks one adult unprotected food animal while avoiding named, baby, leashed, tameable, or fenced animals.",
                List.of("active_npc", "owner_online_same_dimension", "adult_wild_food_animal_nearby", "player_permission_or_saved_plan_permission"),
                List.of("weapon_or_hand", "npc_health"),
                List.of("npc_runtime", "npc_navigation", "nearby_animals"),
                List.of("never_attacks_players_villagers_pets_named_babies_or_fenced_animals", "bounded_radius"),
                targetScopePolicy(true, true, false, true, "owner_radius"),
                List.of("food_animal_attacked", "drops_available_for_collect_items")
        ));
        register(controllers, metadata(
                "feed_animal",
                false,
                true,
                "Feeds a nearby matching animal with accepted food from NPC storage.",
                List.of("active_npc", "owner_online_same_dimension", "matching_animal_nearby", "accepted_food_in_npc_storage"),
                List.of("animal_food_items"),
                List.of("npc_runtime", "npc_inventory", "nearby_animals"),
                List.of("uses_only_npc_storage", "bounded_radius"),
                targetScopePolicy(true, true, false, true, "owner_radius"),
                List.of("animal_fed", "food_consumed")
        ));
        register(controllers, metadata(
                "breed_animals",
                false,
                true,
                "Feeds two adult unprotected matching animals for breeding using NPC storage feed.",
                List.of("active_npc", "owner_online_same_dimension", "two_adult_unprotected_matching_animals", "two_matching_food_items_in_npc_storage"),
                List.of("animal_food_items"),
                List.of("npc_runtime", "npc_inventory", "nearby_animals"),
                List.of("avoids_babies_named_leashed_tameable_or_fenced_animals", "uses_only_npc_storage"),
                targetScopePolicy(true, true, false, true, "owner_radius"),
                List.of("two_animals_fed_for_breeding", "food_consumed")
        ));
        register(controllers, metadata(
                "tame_animal",
                false,
                true,
                "Tames supported nearby animals from NPC storage items; v1 supports wolves and cats.",
                List.of("active_npc", "owner_online_same_dimension", "supported_tameable_nearby", "taming_item_in_npc_storage"),
                List.of("bones_or_fish"),
                List.of("npc_runtime", "npc_inventory", "nearby_animals"),
                List.of("requires_player_command_or_saved_plan_permission", "uses_only_npc_storage"),
                targetScopePolicy(true, true, false, true, "owner_radius"),
                List.of("animal_tamed_or_already_tame_reported", "taming_item_consumed")
        ));
        register(controllers, metadata(
                "build_redstone_template",
                false,
                true,
                "Compatibility action for low-risk redstone templates backed by the machine template system.",
                List.of("active_npc", "owner_online_same_dimension", "approved_redstone_template", "materials_available", "clear_target_space"),
                List.of("doors_or_lamps_or_redstone_components", "npc_storage_materials", "approved_container_materials"),
                List.of("npc_runtime", "npc_inventory", "build_volume", "machine_template_registry"),
                List.of("requires_player_command_or_saved_plan_permission", "template_only", "does_not_modify_unknown_redstone", "does_not_build_high_frequency_clocks"),
                targetScopePolicy(true, true, false, true, "player_facing_clear_flat_anchor"),
                List.of("redstone_template_built", "materials_consumed", "verification_snapshot")
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
