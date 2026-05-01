package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

public final class WorldKnowledge {
    private static final int MAX_BLOCK_SCAN_RADIUS = 32;
    private static final int MAX_VERTICAL_SCAN_RADIUS = 16;
    private static final int MAX_BLOCK_CHUNK_RADIUS = 3;
    private static final int MAX_ENTITY_SCAN_RADIUS = 64;
    private static final int MAX_ENTITY_CHUNK_RADIUS = 4;

    private static final int MAX_KNOWN_AREAS_CACHED = 160;
    private static final int MAX_RESOURCE_HINTS_CACHED = 240;
    private static final int MAX_CONTAINER_HINTS_CACHED = 120;
    private static final int MAX_DANGER_HINTS_CACHED = 160;
    private static final int MAX_RECENT_OBSERVATIONS_CACHED = 48;

    private static final int OUTPUT_KNOWN_AREAS = 12;
    private static final int OUTPUT_RESOURCE_HINTS = 12;
    private static final int OUTPUT_CONTAINER_HINTS = 10;
    private static final int OUTPUT_DANGER_HINTS = 12;
    private static final int OUTPUT_RECENT_OBSERVATIONS = 8;
    private static final int OUTPUT_CONTAINER_SAMPLE_ITEMS = 8;

    private static final LinkedHashMap<String, AreaKnowledge> KNOWN_AREAS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, ResourceHint> RESOURCE_HINTS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, ContainerHint> CONTAINER_HINTS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, DangerHint> DANGER_HINTS = new LinkedHashMap<>();
    private static final ArrayDeque<ObservationPoint> RECENT_OBSERVATIONS = new ArrayDeque<>();

    private WorldKnowledge() {
    }

    public static synchronized JsonObject snapshotFor(ServerPlayer player) {
        Observation observation = observe(player);
        applyObservation(observation);
        return toJson(player, observation);
    }

    public static synchronized KnownPosition nearestResourceHint(ServerPlayer player, String category, double maxDistance, long maxAgeGameTicks) {
        Observation observation = observe(player);
        applyObservation(observation);

        String playerDimension = player.serverLevel().dimension().location().toString();
        long now = player.serverLevel().getGameTime();
        ResourceHint best = null;
        double bestDistance = Double.MAX_VALUE;
        String requestedCategory = category == null ? "" : category;
        for (ResourceHint hint : RESOURCE_HINTS.values()) {
            if (!hint.dimension.equals(playerDimension) || !hint.category.equals(requestedCategory)) {
                continue;
            }
            if (maxAgeGameTicks > 0 && now - hint.lastSeenGameTime > maxAgeGameTicks) {
                continue;
            }

            double distance = distance(player.getX(), player.getY(), player.getZ(), hint.nearestX + 0.5, hint.nearestY + 0.5, hint.nearestZ + 0.5);
            if (distance > maxDistance || distance >= bestDistance) {
                continue;
            }
            bestDistance = distance;
            best = hint;
        }

        if (best == null) {
            return null;
        }
        return new KnownPosition(
                best.dimension,
                best.category,
                best.block,
                best.nearestX,
                best.nearestY,
                best.nearestZ,
                round(bestDistance),
                best.count,
                best.lastSeenGameTime
        );
    }

    public static synchronized void recordPlayerObservation(ServerPlayer player) {
        applyObservation(observe(player));
    }

    private static Observation observe(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        String dimension = level.dimension().location().toString();
        BlockPos center = player.blockPosition();
        int configuredRadius = Math.max(1, McAiConfig.SCAN_RADIUS.get());
        int blockRadius = Math.min(configuredRadius, MAX_BLOCK_SCAN_RADIUS);
        int verticalRadius = Math.min(MAX_VERTICAL_SCAN_RADIUS, Math.max(4, blockRadius / 2));
        int entityRadius = Math.min(configuredRadius, MAX_ENTITY_SCAN_RADIUS);
        long gameTime = level.getGameTime();

        Observation observation = new Observation(
                dimension,
                center,
                gameTime,
                blockRadius,
                verticalRadius,
                MAX_BLOCK_CHUNK_RADIUS,
                entityRadius,
                MAX_ENTITY_CHUNK_RADIUS
        );

        int centerChunkX = blockToChunk(center.getX());
        int centerChunkZ = blockToChunk(center.getZ());
        int minChunkX = Math.max(centerChunkX - MAX_BLOCK_CHUNK_RADIUS, blockToChunk(center.getX() - blockRadius));
        int maxChunkX = Math.min(centerChunkX + MAX_BLOCK_CHUNK_RADIUS, blockToChunk(center.getX() + blockRadius));
        int minChunkZ = Math.max(centerChunkZ - MAX_BLOCK_CHUNK_RADIUS, blockToChunk(center.getZ() - blockRadius));
        int maxChunkZ = Math.min(centerChunkZ + MAX_BLOCK_CHUNK_RADIUS, blockToChunk(center.getZ() + blockRadius));
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                observation.areas.add(new AreaSeen(dimension, chunkX, chunkZ));
            }
        }

        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - blockRadius,
                center.getY() - verticalRadius,
                center.getZ() - blockRadius,
                center.getX() + blockRadius,
                center.getY() + verticalRadius,
                center.getZ() + blockRadius
        )) {
            if (!withinChunkWindow(pos, centerChunkX, centerChunkZ, MAX_BLOCK_CHUNK_RADIUS)) {
                continue;
            }

            BlockState state = level.getBlockState(pos);
            String category = resourceCategory(state);
            if (category != null) {
                String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                observation.addResource(pos, category, blockId);
            }

            if (state.hasBlockEntity()) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity instanceof Container container) {
                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    observation.addContainer(pos, blockId, container);
                }
            }
        }

        AABB entityBox = player.getBoundingBox().inflate(entityRadius);
        for (Entity entity : level.getEntities(player, entityBox, entity -> entity.isAlive()
                && entity.getType().getCategory() == MobCategory.MONSTER)) {
            if (!withinChunkWindow(entity.blockPosition(), centerChunkX, centerChunkZ, MAX_ENTITY_CHUNK_RADIUS)) {
                continue;
            }

            observation.addDanger(entity);
        }

        return observation;
    }

    private static void applyObservation(Observation observation) {
        for (AreaSeen seen : observation.areas) {
            String key = areaKey(seen.dimension(), seen.chunkX(), seen.chunkZ());
            AreaKnowledge area = KNOWN_AREAS.get(key);
            if (area == null) {
                area = new AreaKnowledge(seen.dimension(), seen.chunkX(), seen.chunkZ(), observation.gameTime);
                KNOWN_AREAS.put(key, area);
            }
            area.observe(observation);
        }

        for (ResourceSeen seen : observation.resources.values()) {
            String key = resourceKey(seen.dimension, seen.chunkX, seen.chunkZ, seen.category, seen.block);
            ResourceHint hint = RESOURCE_HINTS.get(key);
            if (hint == null) {
                hint = new ResourceHint(seen.dimension, seen.chunkX, seen.chunkZ, seen.category, seen.block, observation.gameTime);
                RESOURCE_HINTS.put(key, hint);
            }
            hint.update(seen, observation.gameTime);
        }

        for (ContainerSeen seen : observation.containers.values()) {
            String key = positionKey(seen.dimension, seen.x, seen.y, seen.z);
            ContainerHint hint = CONTAINER_HINTS.get(key);
            if (hint == null) {
                hint = new ContainerHint(seen.dimension, seen.x, seen.y, seen.z, observation.gameTime);
                CONTAINER_HINTS.put(key, hint);
            }
            hint.update(seen, observation.gameTime);
        }

        for (DangerSeen seen : observation.dangers.values()) {
            String key = dangerKey(seen.dimension, seen.chunkX, seen.chunkZ, seen.type);
            DangerHint hint = DANGER_HINTS.get(key);
            if (hint == null) {
                hint = new DangerHint(seen.dimension, seen.chunkX, seen.chunkZ, seen.type, observation.gameTime);
                DANGER_HINTS.put(key, hint);
            }
            hint.update(seen, observation.gameTime);
        }

        RECENT_OBSERVATIONS.addFirst(new ObservationPoint(
                observation.dimension,
                observation.center.getX(),
                observation.center.getY(),
                observation.center.getZ(),
                observation.gameTime,
                observation.blockRadius,
                observation.verticalRadius,
                observation.blockChunkRadius,
                observation.entityRadius,
                observation.entityChunkRadius,
                observation.areas.size(),
                observation.resources.size(),
                observation.containers.size(),
                observation.dangers.size()
        ));

        while (RECENT_OBSERVATIONS.size() > MAX_RECENT_OBSERVATIONS_CACHED) {
            RECENT_OBSERVATIONS.removeLast();
        }

        pruneOldest(KNOWN_AREAS, MAX_KNOWN_AREAS_CACHED, area -> area.lastSeenGameTime);
        pruneOldest(RESOURCE_HINTS, MAX_RESOURCE_HINTS_CACHED, hint -> hint.lastSeenGameTime);
        pruneOldest(CONTAINER_HINTS, MAX_CONTAINER_HINTS_CACHED, hint -> hint.lastSeenGameTime);
        pruneOldest(DANGER_HINTS, MAX_DANGER_HINTS_CACHED, hint -> hint.lastSeenGameTime);
    }

    private static JsonObject toJson(ServerPlayer player, Observation observation) {
        JsonObject root = new JsonObject();
        root.add("limits", limitsJson(observation));
        root.add("summary", summaryJson(player, observation));
        root.add("currentObservation", currentObservationJson(observation));
        root.add("shortTermMemory", shortTermMemoryJson());
        root.add("longTermMap", longTermMapJson(player));
        root.add("knownAreas", knownAreasJson(player));
        root.add("resourceHints", resourceHintsJson(player));
        root.add("containerHints", containerHintsJson(player));
        root.add("dangerHints", dangerHintsJson(player));
        root.add("recentObservations", recentObservationsJson());
        return root;
    }

    private static JsonObject currentObservationJson(Observation observation) {
        JsonObject current = new JsonObject();
        current.addProperty("dimension", observation.dimension);
        current.addProperty("x", observation.center.getX());
        current.addProperty("y", observation.center.getY());
        current.addProperty("z", observation.center.getZ());
        current.addProperty("gameTime", observation.gameTime);
        current.addProperty("blockScanRadius", observation.blockRadius);
        current.addProperty("verticalScanRadius", observation.verticalRadius);
        current.addProperty("observedAreas", observation.areas.size());
        current.addProperty("resourceGroups", observation.resources.size());
        current.addProperty("containers", observation.containers.size());
        current.addProperty("dangerGroups", observation.dangers.size());
        current.addProperty("role", "fresh local perception around the player, wider than the small nearbyBlocks summary");
        return current;
    }

    private static JsonObject shortTermMemoryJson() {
        JsonObject memory = new JsonObject();
        memory.addProperty("role", "recent observations used as working memory when the NPC moves or searches");
        memory.addProperty("retainedObservations", RECENT_OBSERVATIONS.size());
        memory.add("recentObservations", recentObservationsJson());
        return memory;
    }

    private static JsonObject longTermMapJson(ServerPlayer player) {
        JsonObject map = new JsonObject();
        map.addProperty("role", "bounded in-session world map of explored chunks, resources, containers, and danger hints");
        map.addProperty("knownAreasCached", KNOWN_AREAS.size());
        map.addProperty("resourceHintsCached", RESOURCE_HINTS.size());
        map.addProperty("containerHintsCached", CONTAINER_HINTS.size());
        map.addProperty("dangerHintsCached", DANGER_HINTS.size());
        map.add("nearestResourceHints", resourceHintsJson(player));
        map.add("knownContainers", containerHintsJson(player));
        map.add("knownDanger", dangerHintsJson(player));
        map.add("exploredAreas", knownAreasJson(player));
        return map;
    }

    private static JsonObject limitsJson(Observation observation) {
        JsonObject limits = new JsonObject();
        limits.addProperty("blockScanRadius", observation.blockRadius);
        limits.addProperty("verticalScanRadius", observation.verticalRadius);
        limits.addProperty("blockChunkRadius", observation.blockChunkRadius);
        limits.addProperty("entityScanRadius", observation.entityRadius);
        limits.addProperty("entityChunkRadius", observation.entityChunkRadius);
        limits.addProperty("maxKnownAreasCached", MAX_KNOWN_AREAS_CACHED);
        limits.addProperty("maxResourceHintsCached", MAX_RESOURCE_HINTS_CACHED);
        limits.addProperty("maxContainerHintsCached", MAX_CONTAINER_HINTS_CACHED);
        limits.addProperty("maxDangerHintsCached", MAX_DANGER_HINTS_CACHED);

        JsonObject outputTopN = new JsonObject();
        outputTopN.addProperty("knownAreas", OUTPUT_KNOWN_AREAS);
        outputTopN.addProperty("resourceHints", OUTPUT_RESOURCE_HINTS);
        outputTopN.addProperty("containerHints", OUTPUT_CONTAINER_HINTS);
        outputTopN.addProperty("dangerHints", OUTPUT_DANGER_HINTS);
        outputTopN.addProperty("recentObservations", OUTPUT_RECENT_OBSERVATIONS);
        limits.add("outputTopN", outputTopN);
        return limits;
    }

    private static JsonObject summaryJson(ServerPlayer player, Observation observation) {
        JsonObject summary = new JsonObject();
        summary.addProperty("currentDimension", player.serverLevel().dimension().location().toString());
        summary.addProperty("lastObservationGameTime", observation.gameTime);
        summary.addProperty("knownAreasCached", KNOWN_AREAS.size());
        summary.addProperty("resourceHintsCached", RESOURCE_HINTS.size());
        summary.addProperty("containerHintsCached", CONTAINER_HINTS.size());
        summary.addProperty("dangerHintsCached", DANGER_HINTS.size());
        summary.addProperty("currentObservationAreas", observation.areas.size());
        summary.addProperty("currentObservationResourceGroups", observation.resources.size());
        summary.addProperty("currentObservationContainers", observation.containers.size());
        summary.addProperty("currentObservationDangerGroups", observation.dangers.size());
        return summary;
    }

    private static JsonArray knownAreasJson(ServerPlayer player) {
        List<AreaKnowledge> areas = new ArrayList<>(KNOWN_AREAS.values());
        areas.sort((left, right) -> compareByDimensionDistanceAndRecency(
                player,
                left.dimension,
                left.centerX(),
                player.getBlockY(),
                left.centerZ(),
                left.lastSeenGameTime,
                right.dimension,
                right.centerX(),
                player.getBlockY(),
                right.centerZ(),
                right.lastSeenGameTime
        ));

        JsonArray result = new JsonArray();
        for (AreaKnowledge area : areas) {
            JsonObject areaJson = new JsonObject();
            areaJson.addProperty("dimension", area.dimension);
            areaJson.addProperty("chunkX", area.chunkX);
            areaJson.addProperty("chunkZ", area.chunkZ);
            areaJson.addProperty("centerX", area.centerX());
            areaJson.addProperty("centerZ", area.centerZ());
            areaJson.addProperty("observations", area.observations);
            areaJson.addProperty("firstSeenGameTime", area.firstSeenGameTime);
            areaJson.addProperty("lastSeenGameTime", area.lastSeenGameTime);
            areaJson.addProperty("lastObserverX", area.lastObserverX);
            areaJson.addProperty("lastObserverY", area.lastObserverY);
            areaJson.addProperty("lastObserverZ", area.lastObserverZ);
            areaJson.addProperty("lastBlockScanRadius", area.lastBlockScanRadius);
            areaJson.addProperty("resourceHintGroups", countResourceHints(area));
            areaJson.addProperty("containerHints", countContainerHints(area));
            areaJson.addProperty("dangerHintGroups", countDangerHints(area));
            addDistanceIfSameDimension(player, areaJson, area.dimension, area.centerX(), player.getBlockY(), area.centerZ());
            result.add(areaJson);
            if (result.size() >= OUTPUT_KNOWN_AREAS) {
                break;
            }
        }
        return result;
    }

    private static JsonArray resourceHintsJson(ServerPlayer player) {
        List<ResourceHint> hints = new ArrayList<>(RESOURCE_HINTS.values());
        hints.sort((left, right) -> compareByDimensionDistanceAndRecency(
                player,
                left.dimension,
                left.nearestX,
                left.nearestY,
                left.nearestZ,
                left.lastSeenGameTime,
                right.dimension,
                right.nearestX,
                right.nearestY,
                right.nearestZ,
                right.lastSeenGameTime
        ));

        JsonArray result = new JsonArray();
        for (ResourceHint hint : hints) {
            JsonObject hintJson = new JsonObject();
            hintJson.addProperty("dimension", hint.dimension);
            hintJson.addProperty("category", hint.category);
            hintJson.addProperty("block", hint.block);
            hintJson.addProperty("chunkX", hint.chunkX);
            hintJson.addProperty("chunkZ", hint.chunkZ);
            hintJson.addProperty("x", hint.nearestX);
            hintJson.addProperty("y", hint.nearestY);
            hintJson.addProperty("z", hint.nearestZ);
            hintJson.addProperty("countInObservedChunk", hint.count);
            hintJson.addProperty("observations", hint.observations);
            hintJson.addProperty("firstSeenGameTime", hint.firstSeenGameTime);
            hintJson.addProperty("lastSeenGameTime", hint.lastSeenGameTime);
            addDistanceIfSameDimension(player, hintJson, hint.dimension, hint.nearestX, hint.nearestY, hint.nearestZ);
            result.add(hintJson);
            if (result.size() >= OUTPUT_RESOURCE_HINTS) {
                break;
            }
        }
        return result;
    }

    private static JsonArray containerHintsJson(ServerPlayer player) {
        List<ContainerHint> hints = new ArrayList<>(CONTAINER_HINTS.values());
        hints.sort((left, right) -> compareByDimensionDistanceAndRecency(
                player,
                left.dimension,
                left.x,
                left.y,
                left.z,
                left.lastSeenGameTime,
                right.dimension,
                right.x,
                right.y,
                right.z,
                right.lastSeenGameTime
        ));

        JsonArray result = new JsonArray();
        for (ContainerHint hint : hints) {
            JsonObject hintJson = new JsonObject();
            hintJson.addProperty("dimension", hint.dimension);
            hintJson.addProperty("block", hint.block);
            hintJson.addProperty("x", hint.x);
            hintJson.addProperty("y", hint.y);
            hintJson.addProperty("z", hint.z);
            hintJson.addProperty("chunkX", blockToChunk(hint.x));
            hintJson.addProperty("chunkZ", blockToChunk(hint.z));
            hintJson.addProperty("occupiedSlots", hint.occupiedSlots);
            hintJson.addProperty("totalItems", hint.totalItems);
            hintJson.addProperty("observations", hint.observations);
            hintJson.addProperty("firstSeenGameTime", hint.firstSeenGameTime);
            hintJson.addProperty("lastSeenGameTime", hint.lastSeenGameTime);
            hintJson.add("sampleItems", sampleItemsJson(hint.sampleItems));
            addDistanceIfSameDimension(player, hintJson, hint.dimension, hint.x, hint.y, hint.z);
            result.add(hintJson);
            if (result.size() >= OUTPUT_CONTAINER_HINTS) {
                break;
            }
        }
        return result;
    }

    private static JsonArray dangerHintsJson(ServerPlayer player) {
        List<DangerHint> hints = new ArrayList<>(DANGER_HINTS.values());
        hints.sort((left, right) -> compareByDimensionDistanceAndRecency(
                player,
                left.dimension,
                left.nearestX,
                left.nearestY,
                left.nearestZ,
                left.lastSeenGameTime,
                right.dimension,
                right.nearestX,
                right.nearestY,
                right.nearestZ,
                right.lastSeenGameTime
        ));

        JsonArray result = new JsonArray();
        for (DangerHint hint : hints) {
            JsonObject hintJson = new JsonObject();
            hintJson.addProperty("dimension", hint.dimension);
            hintJson.addProperty("type", hint.type);
            hintJson.addProperty("nearestName", hint.nearestName);
            hintJson.addProperty("chunkX", hint.chunkX);
            hintJson.addProperty("chunkZ", hint.chunkZ);
            hintJson.addProperty("x", hint.nearestX);
            hintJson.addProperty("y", hint.nearestY);
            hintJson.addProperty("z", hint.nearestZ);
            hintJson.addProperty("countInObservedChunk", hint.count);
            hintJson.addProperty("observations", hint.observations);
            hintJson.addProperty("firstSeenGameTime", hint.firstSeenGameTime);
            hintJson.addProperty("lastSeenGameTime", hint.lastSeenGameTime);
            addDistanceIfSameDimension(player, hintJson, hint.dimension, hint.nearestX, hint.nearestY, hint.nearestZ);
            result.add(hintJson);
            if (result.size() >= OUTPUT_DANGER_HINTS) {
                break;
            }
        }
        return result;
    }

    private static JsonArray recentObservationsJson() {
        JsonArray result = new JsonArray();
        for (ObservationPoint observation : RECENT_OBSERVATIONS) {
            JsonObject observationJson = new JsonObject();
            observationJson.addProperty("dimension", observation.dimension());
            observationJson.addProperty("x", observation.x());
            observationJson.addProperty("y", observation.y());
            observationJson.addProperty("z", observation.z());
            observationJson.addProperty("gameTime", observation.gameTime());
            observationJson.addProperty("blockScanRadius", observation.blockRadius());
            observationJson.addProperty("verticalScanRadius", observation.verticalRadius());
            observationJson.addProperty("blockChunkRadius", observation.blockChunkRadius());
            observationJson.addProperty("entityScanRadius", observation.entityRadius());
            observationJson.addProperty("entityChunkRadius", observation.entityChunkRadius());
            observationJson.addProperty("observedAreas", observation.observedAreas());
            observationJson.addProperty("resourceGroups", observation.resourceGroups());
            observationJson.addProperty("containers", observation.containers());
            observationJson.addProperty("dangerGroups", observation.dangerGroups());
            result.add(observationJson);
            if (result.size() >= OUTPUT_RECENT_OBSERVATIONS) {
                break;
            }
        }
        return result;
    }

    private static JsonArray sampleItemsJson(List<ItemSample> sampleItems) {
        JsonArray items = new JsonArray();
        for (ItemSample sample : sampleItems) {
            JsonObject item = new JsonObject();
            item.addProperty("item", sample.item());
            item.addProperty("count", sample.count());
            items.add(item);
            if (items.size() >= OUTPUT_CONTAINER_SAMPLE_ITEMS) {
                break;
            }
        }
        return items;
    }

    private static int compareByDimensionDistanceAndRecency(
            ServerPlayer player,
            String leftDimension,
            int leftX,
            int leftY,
            int leftZ,
            long leftLastSeen,
            String rightDimension,
            int rightX,
            int rightY,
            int rightZ,
            long rightLastSeen
    ) {
        String playerDimension = player.serverLevel().dimension().location().toString();
        int dimensionCompare = Integer.compare(
                leftDimension.equals(playerDimension) ? 0 : 1,
                rightDimension.equals(playerDimension) ? 0 : 1
        );
        if (dimensionCompare != 0) {
            return dimensionCompare;
        }

        int distanceCompare = Double.compare(
                distanceToPlayer(player, leftDimension, leftX, leftY, leftZ),
                distanceToPlayer(player, rightDimension, rightX, rightY, rightZ)
        );
        if (distanceCompare != 0) {
            return distanceCompare;
        }

        return Long.compare(rightLastSeen, leftLastSeen);
    }

    private static int countResourceHints(AreaKnowledge area) {
        int count = 0;
        for (ResourceHint hint : RESOURCE_HINTS.values()) {
            if (sameArea(area, hint.dimension, hint.chunkX, hint.chunkZ)) {
                count++;
            }
        }
        return count;
    }

    private static int countContainerHints(AreaKnowledge area) {
        int count = 0;
        for (ContainerHint hint : CONTAINER_HINTS.values()) {
            if (sameArea(area, hint.dimension, blockToChunk(hint.x), blockToChunk(hint.z))) {
                count++;
            }
        }
        return count;
    }

    private static int countDangerHints(AreaKnowledge area) {
        int count = 0;
        for (DangerHint hint : DANGER_HINTS.values()) {
            if (sameArea(area, hint.dimension, hint.chunkX, hint.chunkZ)) {
                count++;
            }
        }
        return count;
    }

    private static boolean sameArea(AreaKnowledge area, String dimension, int chunkX, int chunkZ) {
        return area.dimension.equals(dimension) && area.chunkX == chunkX && area.chunkZ == chunkZ;
    }

    private static void addDistanceIfSameDimension(ServerPlayer player, JsonObject json, String dimension, int x, int y, int z) {
        String playerDimension = player.serverLevel().dimension().location().toString();
        if (dimension.equals(playerDimension)) {
            json.addProperty("distance", round(distance(player.getX(), player.getY(), player.getZ(), x + 0.5, y + 0.5, z + 0.5)));
        }
    }

    private static double distanceToPlayer(ServerPlayer player, String dimension, int x, int y, int z) {
        String playerDimension = player.serverLevel().dimension().location().toString();
        if (!dimension.equals(playerDimension)) {
            return Double.MAX_VALUE;
        }
        return distance(player.getX(), player.getY(), player.getZ(), x + 0.5, y + 0.5, z + 0.5);
    }

    private static String resourceCategory(BlockState state) {
        if (state.isAir()) {
            return null;
        }
        if (state.is(BlockTags.LOGS)) {
            return "logs";
        }
        if (isOre(state)) {
            return "ores";
        }
        return null;
    }

    private static boolean isOre(BlockState state) {
        return state.is(BlockTags.COAL_ORES)
                || state.is(BlockTags.COPPER_ORES)
                || state.is(BlockTags.IRON_ORES)
                || state.is(BlockTags.GOLD_ORES)
                || state.is(BlockTags.REDSTONE_ORES)
                || state.is(BlockTags.LAPIS_ORES)
                || state.is(BlockTags.EMERALD_ORES)
                || state.is(BlockTags.DIAMOND_ORES);
    }

    private static boolean withinChunkWindow(BlockPos pos, int centerChunkX, int centerChunkZ, int chunkRadius) {
        int chunkX = blockToChunk(pos.getX());
        int chunkZ = blockToChunk(pos.getZ());
        return Math.abs(chunkX - centerChunkX) <= chunkRadius && Math.abs(chunkZ - centerChunkZ) <= chunkRadius;
    }

    private static int blockToChunk(int blockCoord) {
        return blockCoord >> 4;
    }

    private static String areaKey(String dimension, int chunkX, int chunkZ) {
        return dimension + "|" + chunkX + "|" + chunkZ;
    }

    private static String resourceKey(String dimension, int chunkX, int chunkZ, String category, String block) {
        return areaKey(dimension, chunkX, chunkZ) + "|" + category + "|" + block;
    }

    private static String dangerKey(String dimension, int chunkX, int chunkZ, String type) {
        return areaKey(dimension, chunkX, chunkZ) + "|" + type;
    }

    private static String positionKey(String dimension, int x, int y, int z) {
        return dimension + "|" + x + "|" + y + "|" + z;
    }

    private static <T> void pruneOldest(LinkedHashMap<String, T> map, int maxSize, ToLongFunction<T> lastSeen) {
        while (map.size() > maxSize) {
            String oldestKey = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, T> entry : map.entrySet()) {
                long entryTime = lastSeen.applyAsLong(entry.getValue());
                if (entryTime < oldestTime) {
                    oldestTime = entryTime;
                    oldestKey = entry.getKey();
                }
            }

            if (oldestKey == null) {
                return;
            }
            map.remove(oldestKey);
        }
    }

    private static double distance(BlockPos a, BlockPos b) {
        return distance(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    private static double distance(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record AreaSeen(String dimension, int chunkX, int chunkZ) {
    }

    private record ItemSample(String item, int count) {
    }

    private record ObservationPoint(
            String dimension,
            int x,
            int y,
            int z,
            long gameTime,
            int blockRadius,
            int verticalRadius,
            int blockChunkRadius,
            int entityRadius,
            int entityChunkRadius,
            int observedAreas,
            int resourceGroups,
            int containers,
            int dangerGroups
    ) {
    }

    public record KnownPosition(
            String dimension,
            String category,
            String block,
            int x,
            int y,
            int z,
            double distance,
            int countInObservedChunk,
            long lastSeenGameTime
    ) {
        public BlockPos blockPos() {
            return new BlockPos(x, y, z);
        }
    }

    private static final class Observation {
        private final String dimension;
        private final BlockPos center;
        private final long gameTime;
        private final int blockRadius;
        private final int verticalRadius;
        private final int blockChunkRadius;
        private final int entityRadius;
        private final int entityChunkRadius;
        private final List<AreaSeen> areas = new ArrayList<>();
        private final Map<String, ResourceSeen> resources = new LinkedHashMap<>();
        private final Map<String, ContainerSeen> containers = new LinkedHashMap<>();
        private final Map<String, DangerSeen> dangers = new LinkedHashMap<>();

        private Observation(
                String dimension,
                BlockPos center,
                long gameTime,
                int blockRadius,
                int verticalRadius,
                int blockChunkRadius,
                int entityRadius,
                int entityChunkRadius
        ) {
            this.dimension = dimension;
            this.center = center;
            this.gameTime = gameTime;
            this.blockRadius = blockRadius;
            this.verticalRadius = verticalRadius;
            this.blockChunkRadius = blockChunkRadius;
            this.entityRadius = entityRadius;
            this.entityChunkRadius = entityChunkRadius;
        }

        private void addResource(BlockPos pos, String category, String block) {
            int chunkX = blockToChunk(pos.getX());
            int chunkZ = blockToChunk(pos.getZ());
            String key = resourceKey(dimension, chunkX, chunkZ, category, block);
            ResourceSeen seen = resources.get(key);
            if (seen == null) {
                seen = new ResourceSeen(dimension, chunkX, chunkZ, category, block);
                resources.put(key, seen);
            }
            seen.add(pos, center);
        }

        private void addContainer(BlockPos pos, String block, Container container) {
            String key = positionKey(dimension, pos.getX(), pos.getY(), pos.getZ());
            ContainerSeen seen = new ContainerSeen(dimension, pos.getX(), pos.getY(), pos.getZ(), block);
            int totalItems = 0;
            int occupiedSlots = 0;
            List<ItemSample> sampleItems = new ArrayList<>();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }

                occupiedSlots++;
                totalItems += stack.getCount();
                if (sampleItems.size() < OUTPUT_CONTAINER_SAMPLE_ITEMS) {
                    sampleItems.add(new ItemSample(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
                }
            }

            seen.occupiedSlots = occupiedSlots;
            seen.totalItems = totalItems;
            seen.sampleItems = sampleItems;
            containers.put(key, seen);
        }

        private void addDanger(Entity entity) {
            BlockPos pos = entity.blockPosition();
            int chunkX = blockToChunk(pos.getX());
            int chunkZ = blockToChunk(pos.getZ());
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            String key = dangerKey(dimension, chunkX, chunkZ, type);
            DangerSeen seen = dangers.get(key);
            if (seen == null) {
                seen = new DangerSeen(dimension, chunkX, chunkZ, type);
                dangers.put(key, seen);
            }
            seen.add(entity, center);
        }
    }

    private static final class AreaKnowledge {
        private final String dimension;
        private final int chunkX;
        private final int chunkZ;
        private final long firstSeenGameTime;
        private long lastSeenGameTime;
        private int observations;
        private int lastObserverX;
        private int lastObserverY;
        private int lastObserverZ;
        private int lastBlockScanRadius;

        private AreaKnowledge(String dimension, int chunkX, int chunkZ, long firstSeenGameTime) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.firstSeenGameTime = firstSeenGameTime;
        }

        private void observe(Observation observation) {
            lastSeenGameTime = observation.gameTime;
            observations++;
            lastObserverX = observation.center.getX();
            lastObserverY = observation.center.getY();
            lastObserverZ = observation.center.getZ();
            lastBlockScanRadius = observation.blockRadius;
        }

        private int centerX() {
            return chunkX * 16 + 8;
        }

        private int centerZ() {
            return chunkZ * 16 + 8;
        }
    }

    private static final class ResourceSeen {
        private final String dimension;
        private final int chunkX;
        private final int chunkZ;
        private final String category;
        private final String block;
        private int count;
        private int nearestX;
        private int nearestY;
        private int nearestZ;
        private double nearestDistance = Double.MAX_VALUE;

        private ResourceSeen(String dimension, int chunkX, int chunkZ, String category, String block) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.category = category;
            this.block = block;
        }

        private void add(BlockPos pos, BlockPos center) {
            count++;
            double distance = distance(center, pos);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestX = pos.getX();
                nearestY = pos.getY();
                nearestZ = pos.getZ();
            }
        }
    }

    private static final class ContainerSeen {
        private final String dimension;
        private final int x;
        private final int y;
        private final int z;
        private final String block;
        private int occupiedSlots;
        private int totalItems;
        private List<ItemSample> sampleItems = List.of();

        private ContainerSeen(String dimension, int x, int y, int z, String block) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
        }
    }

    private static final class DangerSeen {
        private final String dimension;
        private final int chunkX;
        private final int chunkZ;
        private final String type;
        private int count;
        private int nearestX;
        private int nearestY;
        private int nearestZ;
        private String nearestName = "";
        private double nearestDistance = Double.MAX_VALUE;

        private DangerSeen(String dimension, int chunkX, int chunkZ, String type) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.type = type;
        }

        private void add(Entity entity, BlockPos center) {
            count++;
            BlockPos pos = entity.blockPosition();
            double distance = distance(center, pos);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestX = pos.getX();
                nearestY = pos.getY();
                nearestZ = pos.getZ();
                nearestName = entity.getName().getString();
            }
        }
    }

    private static final class ResourceHint {
        private final String dimension;
        private final int chunkX;
        private final int chunkZ;
        private final String category;
        private final String block;
        private final long firstSeenGameTime;
        private long lastSeenGameTime;
        private int observations;
        private int count;
        private int nearestX;
        private int nearestY;
        private int nearestZ;

        private ResourceHint(String dimension, int chunkX, int chunkZ, String category, String block, long firstSeenGameTime) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.category = category;
            this.block = block;
            this.firstSeenGameTime = firstSeenGameTime;
        }

        private void update(ResourceSeen seen, long gameTime) {
            lastSeenGameTime = gameTime;
            observations++;
            count = seen.count;
            nearestX = seen.nearestX;
            nearestY = seen.nearestY;
            nearestZ = seen.nearestZ;
        }
    }

    private static final class ContainerHint {
        private final String dimension;
        private final int x;
        private final int y;
        private final int z;
        private final long firstSeenGameTime;
        private long lastSeenGameTime;
        private int observations;
        private String block;
        private int occupiedSlots;
        private int totalItems;
        private List<ItemSample> sampleItems = List.of();

        private ContainerHint(String dimension, int x, int y, int z, long firstSeenGameTime) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.firstSeenGameTime = firstSeenGameTime;
        }

        private void update(ContainerSeen seen, long gameTime) {
            lastSeenGameTime = gameTime;
            observations++;
            block = seen.block;
            occupiedSlots = seen.occupiedSlots;
            totalItems = seen.totalItems;
            sampleItems = seen.sampleItems;
        }
    }

    private static final class DangerHint {
        private final String dimension;
        private final int chunkX;
        private final int chunkZ;
        private final String type;
        private final long firstSeenGameTime;
        private long lastSeenGameTime;
        private int observations;
        private int count;
        private int nearestX;
        private int nearestY;
        private int nearestZ;
        private String nearestName = "";

        private DangerHint(String dimension, int chunkX, int chunkZ, String type, long firstSeenGameTime) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.type = type;
            this.firstSeenGameTime = firstSeenGameTime;
        }

        private void update(DangerSeen seen, long gameTime) {
            lastSeenGameTime = gameTime;
            observations++;
            count = seen.count;
            nearestX = seen.nearestX;
            nearestY = seen.nearestY;
            nearestZ = seen.nearestZ;
            nearestName = seen.nearestName;
        }
    }
}
