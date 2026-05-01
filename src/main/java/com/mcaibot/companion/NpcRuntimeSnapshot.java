package com.mcaibot.companion;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public record NpcRuntimeSnapshot(
        String uuid,
        String name,
        String type,
        String profileId,
        String skin,
        String dimension,
        double x,
        double y,
        double z,
        double distanceToPlayer,
        boolean alive,
        boolean active,
        JsonObject inventory,
        JsonObject equipment,
        TaskRuntimeSnapshot task,
        FollowRuntimeSnapshot follow
) {
    public static NpcRuntimeSnapshot from(
            Entity entity,
            ServerPlayer observer,
            String profileId,
            boolean active,
            TaskRuntimeSnapshot task,
            boolean directFollowing,
            boolean groupFollowing,
            UUID followTargetUuid,
            UUID groupFollowTargetUuid
    ) {
        return new NpcRuntimeSnapshot(
                entity.getUUID().toString(),
                entity.getName().getString(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                profileId,
                entity instanceof AiNpcEntity aiNpc ? aiNpc.skin() : "",
                entity.level().dimension().location().toString(),
                round(entity.getX()),
                round(entity.getY()),
                round(entity.getZ()),
                observer != null && observer.level() == entity.level() ? round(entity.distanceTo(observer)) : -1.0D,
                entity.isAlive(),
                active,
                inventoryJson(entity),
                equipmentJson(entity),
                task,
                new FollowRuntimeSnapshot(
                        directFollowing || groupFollowing,
                        directFollowing,
                        groupFollowing,
                        directFollowing ? uuidText(followTargetUuid) : "",
                        groupFollowing ? uuidText(groupFollowTargetUuid) : ""
                )
        );
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", uuid);
        json.addProperty("name", name);
        json.addProperty("type", type);
        json.addProperty("profileId", profileId);
        json.addProperty("skin", skin);
        json.addProperty("dimension", dimension);
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("z", z);
        json.addProperty("distanceToPlayer", distanceToPlayer);
        json.addProperty("alive", alive);
        json.addProperty("active", active);

        JsonObject activeInfo = new JsonObject();
        activeInfo.addProperty("selected", active);
        activeInfo.addProperty("receivesDirectCommands", active);
        activeInfo.addProperty("singleTaskRuntimeApplies", active);
        json.add("activeInfo", activeInfo);

        json.add("inventory", inventory);
        json.add("equipment", equipment);
        json.add("task", task.toJson());
        json.add("follow", follow.toJson());
        return json;
    }

    private static JsonObject inventoryJson(Entity entity) {
        JsonObject json;
        if (entity instanceof AiNpcEntity aiNpc) {
            json = aiNpc.inventorySummaryJson();
            json.addProperty("available", true);
            return json;
        }

        json = new JsonObject();
        json.addProperty("available", false);
        json.addProperty("reason", "entity_has_no_npc_storage");
        json.addProperty("slots", 0);
        json.addProperty("usedSlots", 0);
        json.addProperty("freeSlots", 0);
        return json;
    }

    private static JsonObject equipmentJson(Entity entity) {
        JsonObject json;
        if (entity instanceof AiNpcEntity aiNpc) {
            json = aiNpc.equipmentSummaryJson();
            json.addProperty("available", true);
            return json;
        }
        if (entity instanceof Mob mob) {
            json = new JsonObject();
            json.addProperty("available", true);
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                json.add(slot.getName(), stackJson(slot.getName(), mob.getItemBySlot(slot)));
            }
            return json;
        }

        json = new JsonObject();
        json.addProperty("available", false);
        json.addProperty("reason", "entity_has_no_equipment_slots");
        return json;
    }

    private static JsonObject stackJson(String slot, ItemStack stack) {
        JsonObject json = new JsonObject();
        json.addProperty("slotName", slot);
        json.addProperty("empty", stack.isEmpty());
        if (stack.isEmpty()) {
            return json;
        }
        json.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        json.addProperty("count", stack.getCount());
        if (stack.isDamageableItem()) {
            json.addProperty("remainingDurability", Math.max(0, stack.getMaxDamage() - stack.getDamageValue()));
            json.addProperty("maxDurability", stack.getMaxDamage());
        }
        return json;
    }

    private static String uuidText(UUID uuid) {
        return uuid == null ? "" : uuid.toString();
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    public record FollowRuntimeSnapshot(
            boolean active,
            boolean direct,
            boolean group,
            String targetUuid,
            String groupTargetUuid
    ) {
        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("active", active);
            json.addProperty("direct", direct);
            json.addProperty("group", group);
            json.addProperty("mode", direct ? "direct" : group ? "group" : "none");
            if (!targetUuid.isBlank()) {
                json.addProperty("targetUuid", targetUuid);
            }
            if (!groupTargetUuid.isBlank()) {
                json.addProperty("groupTargetUuid", groupTargetUuid);
            }
            return json;
        }
    }
}
