package com.mcaibot.companion;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class SurvivalEnvironment {
    private static final int ENTITY_RADIUS = 32;
    private static final int BLOCK_RADIUS = 16;
    private static final int BLOCK_VERTICAL_RADIUS = 6;

    private SurvivalEnvironment() {
    }

    public static JsonObject snapshotFor(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        Counts counts = scanCounts(player, level, center);

        JsonObject time = new JsonObject();
        long dayTime = level.getDayTime() % 24000L;
        time.addProperty("dayTime", dayTime);
        time.addProperty("isDay", level.isDay());
        time.addProperty("isNight", !level.isDay());
        time.addProperty("phase", timePhase(dayTime));

        JsonObject weather = new JsonObject();
        weather.addProperty("raining", level.isRaining());
        weather.addProperty("thundering", level.isThundering());

        JsonObject light = new JsonObject();
        light.addProperty("playerBlock", level.getMaxLocalRawBrightness(center));
        light.addProperty("darkNearbySamples", counts.darkBlocks);

        JsonObject entities = new JsonObject();
        entities.addProperty("hostileMobs", counts.hostileMobs);
        entities.addProperty("hostilesWithin16", counts.closeHostiles);
        entities.addProperty("foodAnimals", counts.foodAnimals);
        entities.addProperty("protectedAnimals", counts.protectedAnimals);
        entities.addProperty("tameableAnimals", counts.tameableAnimals);

        JsonObject resources = new JsonObject();
        resources.addProperty("matureCrops", counts.matureCrops);
        resources.addProperty("farmlandBlocks", counts.farmlandBlocks);
        resources.addProperty("waterSources", counts.waterSources);
        resources.addProperty("logs", counts.logs);
        resources.addProperty("stoneLike", counts.stoneLike);

        JsonObject priorities = new JsonObject();
        priorities.addProperty("dangerLevel", dangerLevel(player, counts));
        priorities.addProperty("recommendedFirstAction", recommendedFirstAction(player, counts));
        priorities.addProperty("reason", recommendedReason(player, counts));

        JsonObject policy = new JsonObject();
        policy.addProperty("autonomyDefault", "high_with_safety_boundaries");
        policy.addProperty("safeAutonomousActions", "observe, report, follow, protect, collect_items, harvest_crops, prepare_basic_tools");
        policy.addProperty("permissionedActions", "break_blocks, mine, build, till_field, redstone_templates, hunt_animals, use_container_materials");
        policy.addProperty("neverAttack", "players, villagers, named animals, tamed animals, fenced animals");

        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", "mc-ai-survival-environment-v1");
        json.addProperty("scanRadius", ENTITY_RADIUS);
        json.addProperty("blockRadius", BLOCK_RADIUS);
        json.add("time", time);
        json.add("weather", weather);
        json.add("light", light);
        json.add("entities", entities);
        json.add("resources", resources);
        json.add("priorities", priorities);
        json.add("policy", policy);
        return json;
    }

    private static Counts scanCounts(ServerPlayer player, ServerLevel level, BlockPos center) {
        Counts counts = new Counts();
        AABB entityBox = player.getBoundingBox().inflate(ENTITY_RADIUS);
        for (Entity entity : level.getEntities(player, entityBox, Entity::isAlive)) {
            if (entity instanceof Mob mob && isHostile(mob)) {
                counts.hostileMobs++;
                if (entity.distanceToSqr(player) <= 16.0D * 16.0D) {
                    counts.closeHostiles++;
                }
            }
            if (entity instanceof Animal animal) {
                if (isTameableAnimal(animal)) {
                    counts.tameableAnimals++;
                }
                if (isProtectedAnimal(animal)) {
                    counts.protectedAnimals++;
                } else if (isFoodAnimal(animal)) {
                    counts.foodAnimals++;
                }
            }
        }

        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - BLOCK_RADIUS,
                center.getY() - BLOCK_VERTICAL_RADIUS,
                center.getZ() - BLOCK_RADIUS,
                center.getX() + BLOCK_RADIUS,
                center.getY() + BLOCK_VERTICAL_RADIUS,
                center.getZ() + BLOCK_RADIUS
        )) {
            BlockPos current = pos.immutable();
            BlockState state = level.getBlockState(current);
            String id = blockId(state);
            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                counts.matureCrops++;
            }
            if (state.is(Blocks.FARMLAND)) {
                counts.farmlandBlocks++;
            }
            if (state.is(Blocks.WATER)) {
                counts.waterSources++;
            }
            if (id.endsWith("_log") || id.endsWith("_stem")) {
                counts.logs++;
            }
            if (id.equals("minecraft:stone") || id.equals("minecraft:cobblestone") || id.equals("minecraft:deepslate")
                    || id.equals("minecraft:blackstone")) {
                counts.stoneLike++;
            }
            if (state.isAir() && level.getMaxLocalRawBrightness(current) <= 7) {
                counts.darkBlocks++;
            }
        }
        return counts;
    }

    static boolean isHostile(Entity entity) {
        return entity instanceof Mob && (entity instanceof Monster || entity instanceof Enemy);
    }

    static boolean isFoodAnimal(Entity entity) {
        String id = entityId(entity);
        return id.equals("minecraft:cow")
                || id.equals("minecraft:sheep")
                || id.equals("minecraft:pig")
                || id.equals("minecraft:chicken")
                || id.equals("minecraft:rabbit");
    }

    static boolean isTameableAnimal(Entity entity) {
        String id = entityId(entity);
        return id.equals("minecraft:wolf")
                || id.equals("minecraft:cat")
                || id.equals("minecraft:horse")
                || id.equals("minecraft:donkey")
                || id.equals("minecraft:mule")
                || id.equals("minecraft:llama");
    }

    static boolean isProtectedAnimal(Animal animal) {
        return animal.isBaby()
                || animal.hasCustomName()
                || animal.isLeashed()
                || isTameableAnimal(animal)
                || isNearFence(animal);
    }

    private static boolean isNearFence(Entity entity) {
        BlockPos center = entity.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-2, -1, -2), center.offset(2, 1, 2))) {
            String id = blockId(entity.level().getBlockState(pos));
            if (id.contains("fence") || id.contains("wall")) {
                return true;
            }
        }
        return false;
    }

    private static String timePhase(long dayTime) {
        if (dayTime < 1000 || dayTime >= 23000) {
            return "dawn";
        }
        if (dayTime < 12000) {
            return "day";
        }
        if (dayTime < 13000) {
            return "dusk";
        }
        return "night";
    }

    private static String dangerLevel(ServerPlayer player, Counts counts) {
        if (player.getHealth() <= 6.0F || counts.closeHostiles > 0) {
            return "immediate";
        }
        if (counts.hostileMobs > 0 || !player.serverLevel().isDay()) {
            return "elevated";
        }
        return "low";
    }

    private static String recommendedFirstAction(ServerPlayer player, Counts counts) {
        if ("immediate".equals(dangerLevel(player, counts))) {
            return "guard_player";
        }
        if (counts.matureCrops > 0) {
            return "harvest_crops";
        }
        if (counts.logs > 0) {
            return "harvest_logs";
        }
        if (counts.foodAnimals > 0) {
            return "report_nearby_or_hunt_with_permission";
        }
        return "observe_environment";
    }

    private static String recommendedReason(ServerPlayer player, Counts counts) {
        if ("immediate".equals(dangerLevel(player, counts))) {
            return "Player health or nearby hostile mobs make protection the first priority.";
        }
        if (counts.matureCrops > 0) {
            return "Mature crops are available and can be harvested without breaking player storage.";
        }
        if (counts.logs > 0) {
            return "Nearby logs can bootstrap tools and building materials.";
        }
        return "No urgent survival blocker was detected in the bounded scan.";
    }

    static String entityId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static final class Counts {
        private int hostileMobs;
        private int closeHostiles;
        private int foodAnimals;
        private int protectedAnimals;
        private int tameableAnimals;
        private int matureCrops;
        private int farmlandBlocks;
        private int waterSources;
        private int logs;
        private int stoneLike;
        private int darkBlocks;
    }
}
