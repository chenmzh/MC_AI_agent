package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class MachineBuildController {
    private static final long AUTHORIZATION_TTL_MS = 30L * 60L * 1000L;
    private static final int MAX_DIRECT_MACHINE_BLOCKS = 1800;
    private static final Map<UUID, MachineAuthorization> AUTHORIZATIONS = new ConcurrentHashMap<>();

    private MachineBuildController() {
    }

    public static JsonObject catalogJson(ServerPlayer player) {
        JsonObject json = MachineTemplateRegistry.catalogJson();
        if (player != null) {
            json.add("currentAuthorization", authorizationSnapshot(player));
        }
        return json;
    }

    public static ActionResult previewMachine(ServerPlayer player, String templateId, BlockPos origin, Direction facing) {
        MachineBlueprint blueprint = MachineTemplateRegistry.create(templateId, player, origin, facing);
        JsonObject site = MachineSafetyChecker.validateSite(player, blueprint);
        JsonObject materialPlan = materialPlan(player, blueprint);
        String message = "Machine preview: " + blueprint.label()
                + " risk=" + blueprint.riskLevel()
                + " at " + blueprint.origin().toShortString()
                + ", requiredBlocks=" + blueprint.requiredPlacements()
                + ", missingMaterialKinds=" + materialPlan.get("missingKinds").getAsInt()
                + ", blockedRequired=" + site.get("blockedRequired").getAsInt() + ".";
        NpcChat.say(player, message);
        return ActionResult.success("MACHINE_PREVIEW_READY", message)
                .withObservation("machine", blueprint.toJson(true))
                .withObservation("machineCatalog", MachineTemplateRegistry.catalogJson())
                .withObservation("siteCheck", site)
                .withObservation("materialPlan", materialPlan)
                .withObservation("authorizationPolicy", authorizationPolicyJson());
    }

    public static ActionResult authorizeMachinePlan(ServerPlayer player, String templateId, BlockPos origin, Direction facing) {
        MachineBlueprint blueprint = MachineTemplateRegistry.create(templateId, player, origin, facing);
        JsonObject site = MachineSafetyChecker.validateSite(player, blueprint);
        if (site.get("blockedRequired").getAsInt() > 0) {
            String message = "Machine plan was not authorized because the target site is blocked or too close to protected blocks.";
            NpcChat.say(player, message);
            return ActionResult.blocked("MACHINE_AUTH_SITE_BLOCKED", message,
                            "Move to a clear test area, run preview_machine again, then authorize the plan.")
                    .withObservation("machine", blueprint.toJson(true))
                    .withObservation("siteCheck", site);
        }
        MachineAuthorization auth = new MachineAuthorization(
                player.getUUID(),
                blueprint.id(),
                dimensionId(player),
                blueprint.origin(),
                blueprint.facing(),
                blueprint.riskLevel(),
                System.currentTimeMillis() + AUTHORIZATION_TTL_MS
        );
        AUTHORIZATIONS.put(player.getUUID(), auth);
        String message = "Authorized machine plan " + blueprint.id() + " at " + blueprint.origin().toShortString()
                + " for " + (AUTHORIZATION_TTL_MS / 60000L) + " minutes.";
        NpcChat.say(player, message);
        return ActionResult.success("MACHINE_PLAN_AUTHORIZED", message)
                .withObservation("machine", blueprint.toJson(false))
                .withObservation("authorization", auth.toJson())
                .withObservation("materialPlan", materialPlan(player, blueprint))
                .withEffect("machine", blueprint.id())
                .withEffect("authorized", true);
    }

    public static ActionResult buildMachine(ServerPlayer player, String templateId, BlockPos origin, Direction facing) {
        Mob npc = NpcManager.activeNpcMob(player.getServer());
        if (npc == null) {
            return ActionResult.blocked("NO_NPC", "build_machine needs a spawned companion NPC.", "Spawn an NPC with /mcai npc spawn.");
        }
        MachineBlueprint blueprint = MachineTemplateRegistry.create(templateId, player, origin, facing);
        if (blueprint.placements().size() > MAX_DIRECT_MACHINE_BLOCKS) {
            return ActionResult.blocked("MACHINE_TOO_LARGE",
                    "Machine template has too many direct placements for this first implementation.",
                    "Use a smaller template or split it into phases.");
        }
        if (blueprint.highRisk()) {
            MachineAuthorization auth = AUTHORIZATIONS.get(player.getUUID());
            if (auth == null || !auth.matches(player, blueprint)) {
                return ActionResult.blocked("MACHINE_PLAN_AUTH_REQUIRED",
                                "High-risk machine build requires a matching saved plan authorization first.",
                                "Run preview_machine, then authorize_machine_plan for this exact template, dimension, anchor, and facing.")
                        .withObservation("machine", blueprint.toJson(false))
                        .withObservation("currentAuthorization", authorizationSnapshot(player));
            }
        }

        JsonObject site = MachineSafetyChecker.validateSite(player, blueprint);
        if (site.get("blockedRequired").getAsInt() > 0) {
            String message = "Machine build site is blocked; I will not clear player base blocks, containers, existing redstone, or modded machines.";
            NpcChat.say(player, message);
            return ActionResult.blocked("MACHINE_SITE_BLOCKED", message,
                            "Pick a clearer location, preview again, then authorize/build.")
                    .withObservation("machine", blueprint.toJson(true))
                    .withObservation("siteCheck", site)
                    .withObservation("materialPlan", materialPlan(player, blueprint));
        }

        JsonObject materialPlan = materialPlan(player, blueprint);
        if (materialPlan.get("missingKinds").getAsInt() > 0) {
            String message = "Machine materials are missing for " + blueprint.label() + ".";
            NpcChat.say(player, message);
            return ActionResult.blocked("MACHINE_MATERIALS_MISSING", message,
                            "Provide materials in NPC storage, approve a nearby container, or run gather_materials for machine categories.")
                    .withObservation("machine", blueprint.toJson(false))
                    .withObservation("materialPlan", materialPlan);
        }
        if (!consumeBudget(player, blueprint)) {
            return ActionResult.blocked("MACHINE_MATERIAL_CONSUME_FAILED",
                            "Material counts changed before building; required machine materials could not be consumed safely.",
                            "Preview the machine again and retry after materials are stable.")
                    .withObservation("machine", blueprint.toJson(false))
                    .withObservation("materialPlan", materialPlan(player, blueprint));
        }

        BuildStats stats = placeMachine(player.serverLevel(), blueprint);
        ActionResult verification = MachineVerifier.verify(player, blueprint);
        if (blueprint.highRisk()) {
            AUTHORIZATIONS.remove(player.getUUID());
        }
        String message = "Built machine " + blueprint.label()
                + ": placed=" + stats.placed()
                + ", skippedExisting=" + stats.skippedExisting()
                + ", optionalSkipped=" + stats.optionalSkipped() + ".";
        NpcChat.say(player, message);
        TaskFeedback.info(player, npc, "build_machine", "MACHINE_BUILT", message);
        return ActionResult.success("MACHINE_BUILT", message)
                .withObservation("machine", blueprint.toJson(false))
                .withObservation("buildExecution", stats.toJson())
                .withObservation("verification", verification.toJson())
                .withEffect("machine", blueprint.id())
                .withEffect("placed", stats.placed());
    }

    public static ActionResult testMachine(ServerPlayer player, String templateId, BlockPos origin, Direction facing) {
        MachineBlueprint blueprint = MachineTemplateRegistry.create(templateId, player, origin, facing);
        return MachineVerifier.verify(player, blueprint);
    }

    public static ActionResult cancelMachineBuild(ServerPlayer player) {
        AUTHORIZATIONS.remove(player.getUUID());
        NpcManager.stop(player);
        return ActionResult.success("MACHINE_BUILD_CANCELLED", "Cancelled active machine authorization/build task if one was running.")
                .withEffect("authorizationCleared", true);
    }

    public static ActionResult buildRedstoneTemplate(ServerPlayer player, String templateId) {
        String inferred = MachineTemplateRegistry.inferTemplate(templateId);
        MachineBlueprint blueprint = MachineTemplateRegistry.create(inferred, player, null, player.getDirection());
        if (!"redstone".equals(blueprint.category())) {
            return ActionResult.blocked("UNSUPPORTED_REDSTONE_TEMPLATE",
                    "build_redstone_template only supports redstone templates: pressure_door, button_door, lever_door, simple_lamp_switch.",
                    "Use preview_machine/authorize_machine_plan/build_machine for high-risk survival machines.");
        }
        return buildMachine(player, blueprint.id(), blueprint.origin(), blueprint.facing());
    }

    public static JsonObject authorizationSnapshot(ServerPlayer player) {
        MachineAuthorization auth = AUTHORIZATIONS.get(player.getUUID());
        if (auth == null) {
            JsonObject json = new JsonObject();
            json.addProperty("active", false);
            return json;
        }
        if (auth.expired()) {
            AUTHORIZATIONS.remove(player.getUUID());
            JsonObject json = new JsonObject();
            json.addProperty("active", false);
            json.addProperty("expired", true);
            return json;
        }
        return auth.toJson();
    }

    private static JsonObject authorizationPolicyJson() {
        JsonObject json = new JsonObject();
        json.addProperty("highRiskRequiresAuthorization", true);
        json.addProperty("authorizationTtlMinutes", AUTHORIZATION_TTL_MS / 60000L);
        json.addProperty("binding", "player, dimension, machine id, anchor, facing, risk level");
        json.addProperty("entityPolicy", "does not spawn villagers, zombies, or monsters");
        return json;
    }

    private static JsonObject materialPlan(ServerPlayer player, MachineBlueprint blueprint) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", "mc-agent-machine-material-plan-v1");
        root.addProperty("machine", blueprint.id());
        JsonArray requirements = new JsonArray();
        int missingKinds = 0;
        int missingTotal = 0;
        for (Map.Entry<String, Integer> entry : blueprint.materialBudget().entrySet()) {
            int available = countRequirement(player, entry.getKey());
            int missing = Math.max(0, entry.getValue() - available);
            JsonObject req = new JsonObject();
            req.addProperty("category", entry.getKey());
            req.addProperty("required", entry.getValue());
            req.addProperty("available", available);
            req.addProperty("missing", missing);
            requirements.add(req);
            if (missing > 0) {
                missingKinds++;
                missingTotal += missing;
            }
        }
        root.add("requirements", requirements);
        root.addProperty("missingKinds", missingKinds);
        root.addProperty("missingTotal", missingTotal);
        root.addProperty("sourcePriority", "NPC storage -> approved nearby containers; player inventory excluded");
        root.addProperty("rareMaterialPolicy", "report structured gaps; no deep mining or complex autocrafting chain in this stage");
        return root;
    }

    private static boolean consumeBudget(ServerPlayer player, MachineBlueprint blueprint) {
        for (Map.Entry<String, Integer> entry : blueprint.materialBudget().entrySet()) {
            if (countRequirement(player, entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        for (Map.Entry<String, Integer> entry : blueprint.materialBudget().entrySet()) {
            if (!BlockPaletteResolver.consumeItems(player, predicateFor(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static int countRequirement(ServerPlayer player, String key) {
        return BlockPaletteResolver.countItems(player, predicateFor(key));
    }

    private static Predicate<ItemStack> predicateFor(String key) {
        String normalized = key == null ? "" : key.toLowerCase();
        return stack -> {
            String id = BlockPaletteResolver.itemId(stack);
            return switch (normalized) {
                case "large_placeable_blocks" -> BlockPaletteResolver.isPlaceable(stack) && !isMachineComponent(id);
                case "redstone_components" -> id.equals("minecraft:redstone")
                        || id.equals("minecraft:redstone_torch")
                        || id.equals("minecraft:repeater")
                        || id.equals("minecraft:comparator");
                case "hoppers" -> id.equals("minecraft:hopper");
                case "chests" -> id.endsWith("chest") || id.equals("minecraft:barrel");
                case "water_buckets" -> id.equals("minecraft:water_bucket");
                case "lava_buckets" -> id.equals("minecraft:lava_bucket");
                case "beds" -> id.endsWith("_bed");
                case "workstations" -> id.equals("minecraft:composter")
                        || id.equals("minecraft:lectern")
                        || id.equals("minecraft:fletching_table")
                        || id.equals("minecraft:smithing_table")
                        || id.equals("minecraft:grindstone")
                        || id.equals("minecraft:cartography_table")
                        || id.equals("minecraft:loom")
                        || id.equals("minecraft:stonecutter")
                        || id.equals("minecraft:barrel")
                        || id.equals("minecraft:blast_furnace")
                        || id.equals("minecraft:smoker");
                case "trapdoors" -> id.endsWith("_trapdoor");
                case "slabs" -> id.endsWith("_slab");
                case "doors" -> id.endsWith("_door");
                case "pressure_plates" -> id.endsWith("_pressure_plate");
                case "buttons" -> id.endsWith("_button");
                case "levers" -> id.equals("minecraft:lever");
                case "redstone_lamps" -> id.equals("minecraft:redstone_lamp");
                case "ladders" -> id.equals("minecraft:ladder");
                case "glass_like" -> BlockPaletteResolver.isGlassLike(stack);
                default -> false;
            };
        };
    }

    private static boolean isMachineComponent(String id) {
        return id.equals("minecraft:hopper")
                || id.endsWith("chest")
                || id.equals("minecraft:barrel")
                || id.endsWith("_bucket")
                || id.endsWith("_bed")
                || id.endsWith("_trapdoor")
                || id.endsWith("_door")
                || id.endsWith("_pressure_plate")
                || id.endsWith("_button")
                || id.equals("minecraft:lever")
                || id.equals("minecraft:redstone")
                || id.equals("minecraft:redstone_lamp")
                || id.equals("minecraft:repeater")
                || id.equals("minecraft:comparator");
    }

    private static BuildStats placeMachine(ServerLevel level, MachineBlueprint blueprint) {
        int placed = 0;
        int skippedExisting = 0;
        int optionalSkipped = 0;
        int blocked = 0;
        JsonArray samples = new JsonArray();
        for (MachinePlacement placement : blueprint.placements()) {
            if (level.getBlockState(placement.pos()).is(placement.state().getBlock())) {
                skippedExisting++;
                continue;
            }
            if (!MachineSafetyChecker.canPlace(level, placement)) {
                if (placement.optional()) {
                    optionalSkipped++;
                } else {
                    blocked++;
                }
                if (samples.size() < 12) {
                    samples.add(placement.toJson());
                }
                continue;
            }
            level.setBlock(placement.pos(), placement.state(), 3);
            placed++;
        }
        return new BuildStats(placed, skippedExisting, optionalSkipped, blocked, samples);
    }

    private static String dimensionId(ServerPlayer player) {
        return player.serverLevel().dimension().location().toString();
    }

    private record MachineAuthorization(UUID owner, String machineId, String dimension, BlockPos origin, Direction facing, String riskLevel, long expiresAtMs) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAtMs;
        }

        boolean matches(ServerPlayer player, MachineBlueprint blueprint) {
            return !expired()
                    && owner.equals(player.getUUID())
                    && machineId.equals(blueprint.id())
                    && dimension.equals(dimensionId(player))
                    && origin.equals(blueprint.origin())
                    && facing.equals(blueprint.facing())
                    && riskLevel.equals(blueprint.riskLevel());
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("active", !expired());
            json.addProperty("machine", machineId);
            json.addProperty("dimension", dimension);
            json.addProperty("originX", origin.getX());
            json.addProperty("originY", origin.getY());
            json.addProperty("originZ", origin.getZ());
            json.addProperty("facing", facing.getName());
            json.addProperty("riskLevel", riskLevel);
            json.addProperty("expiresAtMs", expiresAtMs);
            return json;
        }
    }

    private record BuildStats(int placed, int skippedExisting, int optionalSkipped, int blocked, JsonArray samples) {
        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("placed", placed);
            json.addProperty("skippedExisting", skippedExisting);
            json.addProperty("optionalSkipped", optionalSkipped);
            json.addProperty("blocked", blocked);
            json.add("samples", samples.deepCopy());
            return json;
        }
    }
}
