package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ModInteractionManager {
    private static final int MAX_MODDED_BLOCKS = 80;
    private static final int MAX_STATE_PROPERTIES = 10;
    private static final int DEFAULT_RADIUS = 12;
    private static final int MAX_RADIUS = 32;

    private ModInteractionManager() {
    }

    public static JsonObject snapshotFor(ServerPlayer player) {
        int radius = Math.min(DEFAULT_RADIUS, Math.max(4, McAiConfig.SCAN_RADIUS.get()));
        JsonObject root = new JsonObject();
        root.addProperty("adapter", "generic_create_family");
        root.addProperty("hardDependency", false);
        root.addProperty("safeInteractionPolicy", "inspect/report by default; wrench only when explicitly requested with a target position");
        root.addProperty("wrenchAvailable", findWrench(player) != null);
        root.add("namespaces", namespaceSupportJson());
        root.add("nearbyBlocks", scanNearbyBlocks(player, radius));
        return root;
    }

    public static void reportNearby(ServerPlayer player, int requestedRadius) {
        int radius = clampRadius(requestedRadius);
        JsonArray blocks = scanNearbyBlocks(player, radius);
        if (blocks.isEmpty()) {
            say(player, "No Create-family or Aeronautics blocks found within " + radius + " blocks.");
            return;
        }

        StringBuilder builder = new StringBuilder("Modded nearby: ");
        for (int i = 0; i < Math.min(blocks.size(), 8); i++) {
            JsonObject block = blocks.get(i).getAsJsonObject();
            if (i > 0) {
                builder.append("; ");
            }
            builder.append(block.get("block").getAsString())
                    .append(" @ ")
                    .append(block.get("x").getAsInt())
                    .append(" ")
                    .append(block.get("y").getAsInt())
                    .append(" ")
                    .append(block.get("z").getAsInt())
                    .append(" ")
                    .append(block.get("category").getAsString());
        }
        say(player, builder.toString());
    }

    public static void inspectBlock(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        BlockState state = level.getBlockState(pos);
        String blockId = blockId(state);
        if (!isSupportedNamespace(namespace(blockId))) {
            say(player, "Target block is not a supported Create-family block: " + blockId + ".");
            return;
        }

        JsonObject detail = describeBlock(player, level, pos, state);
        say(player, "Inspect " + detail.get("block").getAsString()
                + " @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + ": category=" + detail.get("category").getAsString()
                + ", blockEntity=" + detail.get("hasBlockEntity").getAsBoolean()
                + ", inventorySlots=" + (detail.has("inventorySlots") ? detail.get("inventorySlots").getAsInt() : 0)
                + ".");
    }

    public static void useWrench(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        BlockState state = level.getBlockState(pos);
        String blockId = blockId(state);
        if (!isSupportedNamespace(namespace(blockId))) {
            say(player, "I will not use the wrench there because " + blockId + " is not a supported Create-family block.");
            return;
        }

        WrenchUse wrench = findWrench(player);
        if (wrench == null) {
            say(player, "No Create wrench found in your inventory or nearby containers.");
            return;
        }

        Direction hitFace = faceTowardPlayer(player, pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), hitFace, pos, false);
        InteractionResult result = wrench.stack().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        wrench.container().setChanged();

        String message = "Used " + itemId(wrench.stack()) + " on " + blockId + " at "
                + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ": " + result + ".";
        say(player, message);
        TaskFeedback.info(player, NpcManager.activeNpcMob(player.getServer()), "mod_interaction", "WRENCH_USED", message);
    }

    private static JsonArray namespaceSupportJson() {
        JsonArray namespaces = new JsonArray();
        for (String namespace : new String[]{
                "create",
                "aeronautics",
                "createaddition",
                "create_connected",
                "create_central_kitchen",
                "create_enchantment_industry",
                "create_stuff_additions",
                "createdragonsplus"
        }) {
            namespaces.add(namespace);
        }
        return namespaces;
    }

    private static JsonArray scanNearbyBlocks(ServerPlayer player, int radius) {
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        int verticalRadius = Math.min(12, Math.max(5, radius / 2));
        JsonArray blocks = new JsonArray();

        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - radius,
                center.getY() - verticalRadius,
                center.getZ() - radius,
                center.getX() + radius,
                center.getY() + verticalRadius,
                center.getZ() + radius
        )) {
            BlockPos candidate = pos.immutable();
            BlockState state = level.getBlockState(candidate);
            if (!isSupportedNamespace(namespace(blockId(state)))) {
                continue;
            }
            blocks.add(describeBlock(player, level, candidate, state));
            if (blocks.size() >= MAX_MODDED_BLOCKS) {
                break;
            }
        }
        return blocks;
    }

    private static JsonObject describeBlock(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        String blockId = blockId(state);
        JsonObject json = new JsonObject();
        json.addProperty("block", blockId);
        json.addProperty("namespace", namespace(blockId));
        json.addProperty("category", category(blockId));
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        json.addProperty("distance", round(distance(player.blockPosition(), pos)));
        json.addProperty("hasBlockEntity", state.hasBlockEntity());
        json.add("properties", stateProperties(state));

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            json.addProperty("blockEntityType", blockEntity.getType().toString());
            json.addProperty("blockEntityClass", blockEntity.getClass().getName());
            if (blockEntity instanceof Container container) {
                json.addProperty("inventorySlots", container.getContainerSize());
                json.addProperty("occupiedSlots", occupiedSlots(container));
            }
        }
        return json;
    }

    private static JsonObject stateProperties(BlockState state) {
        JsonObject properties = new JsonObject();
        int count = 0;
        for (Property<?> property : state.getProperties()) {
            properties.addProperty(property.getName(), valueName(state, property));
            count++;
            if (count >= MAX_STATE_PROPERTIES) {
                break;
            }
        }
        return properties;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static WrenchUse findWrench(ServerPlayer player) {
        WrenchUse fromInventory = findWrench(player.getInventory(), "inventory");
        if (fromInventory != null) {
            return fromInventory;
        }

        for (Container container : nearbyContainers(player)) {
            WrenchUse fromContainer = findWrench(container, "container");
            if (fromContainer != null) {
                return fromContainer;
            }
        }
        return null;
    }

    private static WrenchUse findWrench(Container container, String source) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && isWrench(stack)) {
                return new WrenchUse(stack, container, source, slot);
            }
        }
        return null;
    }

    private static boolean isWrench(ItemStack stack) {
        String id = itemId(stack);
        return id.equals("create:wrench") || id.endsWith(":wrench") || id.contains("wrench");
    }

    private static Iterable<Container> nearbyContainers(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        int radius = Math.min(McAiConfig.NPC_TASK_RADIUS.get(), 12);
        int verticalRadius = 5;
        java.util.List<Container> containers = new java.util.ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - radius,
                center.getY() - verticalRadius,
                center.getZ() - radius,
                center.getX() + radius,
                center.getY() + verticalRadius,
                center.getZ() + radius
        )) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Container container) {
                containers.add(container);
                if (containers.size() >= 12) {
                    break;
                }
            }
        }
        return containers;
    }

    private static int occupiedSlots(Container container) {
        int occupied = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.getItem(slot).isEmpty()) {
                occupied++;
            }
        }
        return occupied;
    }

    private static String category(String id) {
        String value = id.toLowerCase(Locale.ROOT);
        if (value.contains("propeller") || value.contains("envelope") || value.contains("levitite")
                || value.contains("potato_cannon") || value.contains("steam_vent")) {
            return "aeronautics";
        }
        if (containsAny(value, "motor", "water_wheel", "steam_engine", "windmill", "hand_crank")) {
            return "kinetic_source";
        }
        if (containsAny(value, "shaft", "cogwheel", "gearbox", "belt", "chain_drive", "clutch", "gearshift")) {
            return "kinetic_transfer";
        }
        if (containsAny(value, "press", "mixer", "basin", "saw", "millstone", "crusher", "fan", "burner", "spout", "deployer", "depot")) {
            return "processing";
        }
        if (containsAny(value, "funnel", "chute", "tunnel", "mechanical_arm", "portable_storage_interface", "stockpile")) {
            return "logistics";
        }
        if (containsAny(value, "gauge", "display", "link", "controller", "sequenced")) {
            return "control";
        }
        return "create_family";
    }

    private static boolean isSupportedNamespace(String namespace) {
        return namespace.equals("create")
                || namespace.equals("aeronautics")
                || namespace.equals("createaddition")
                || namespace.equals("create_connected")
                || namespace.equals("create_central_kitchen")
                || namespace.equals("create_enchantment_industry")
                || namespace.equals("create_stuff_additions")
                || namespace.equals("createdragonsplus")
                || namespace.contains("create");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static Direction faceTowardPlayer(ServerPlayer player, BlockPos pos) {
        Vec3 target = Vec3.atCenterOf(pos);
        Vec3 delta = player.position().subtract(target);
        return Direction.getNearest(delta.x, delta.y, delta.z);
    }

    private static int clampRadius(int radius) {
        return Math.max(4, Math.min(MAX_RADIUS, radius));
    }

    private static String blockId(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key == null ? "minecraft:air" : key.toString();
    }

    private static String itemId(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "minecraft:air" : key.toString().toLowerCase(Locale.ROOT);
    }

    private static String namespace(String id) {
        int split = id.indexOf(':');
        return split <= 0 ? "minecraft" : id.substring(0, split);
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static void say(ServerPlayer player, String message) {
        NpcChat.say(player, message);
    }

    private record WrenchUse(ItemStack stack, Container container, String source, int slot) {
    }
}
