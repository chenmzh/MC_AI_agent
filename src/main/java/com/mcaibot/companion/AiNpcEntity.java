package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class AiNpcEntity extends PathfinderMob {
    public static final int INVENTORY_SIZE = 27;
    private static final String TAG_INVENTORY = "McAiInventory";
    private static final String TAG_SKIN = "McAiSkin";
    private static final EntityDataAccessor<String> DATA_SKIN = SynchedEntityData.defineId(AiNpcEntity.class, EntityDataSerializers.STRING);

    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);

    public AiNpcEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.inventory.addListener(container -> this.setPersistenceRequired());
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.28D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.STEP_HEIGHT, 1.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN, NpcProfile.DEFAULT_SKIN);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    public SimpleContainer inventory() {
        return inventory;
    }

    public String skin() {
        return entityData.get(DATA_SKIN);
    }

    public void setSkin(String skin) {
        String normalized = skin == null || skin.isBlank() ? NpcProfile.DEFAULT_SKIN : skin.trim();
        entityData.set(DATA_SKIN, normalized);
        setPersistenceRequired();
    }

    public ItemStack addToInventory(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remainder = inventory.addItem(stack);
        inventory.setChanged();
        setPersistenceRequired();
        return remainder;
    }

    public int usedInventorySlots() {
        int used = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (!inventory.getItem(slot).isEmpty()) {
                used++;
            }
        }
        return used;
    }

    public JsonObject inventorySummaryJson() {
        JsonObject json = new JsonObject();
        JsonArray items = new JsonArray();
        int used = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            used++;
            JsonObject item = stackJson("slot_" + slot, stack);
            item.addProperty("slot", slot);
            items.add(item);
        }
        json.addProperty("slots", inventory.getContainerSize());
        json.addProperty("usedSlots", used);
        json.addProperty("freeSlots", inventory.getContainerSize() - used);
        json.add("items", items);
        return json;
    }

    public JsonObject equipmentSummaryJson() {
        JsonObject json = new JsonObject();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = getItemBySlot(slot);
            json.add(slot.getName(), stackJson(slot.getName(), stack));
        }
        return json;
    }

    public void openInventory(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (containerId, playerInventory, ignored) -> ChestMenu.threeRows(containerId, playerInventory, inventory),
                Component.literal(getName().getString() + " Storage")
        ));
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (!level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            openInventory(serverPlayer);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.put(TAG_INVENTORY, inventory.createTag(level().registryAccess()));
        compound.putString(TAG_SKIN, skin());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        if (compound.contains(TAG_INVENTORY, Tag.TAG_LIST)) {
            inventory.fromTag(compound.getList(TAG_INVENTORY, Tag.TAG_COMPOUND), level().registryAccess());
        }
        if (compound.contains(TAG_SKIN, Tag.TAG_STRING)) {
            setSkin(compound.getString(TAG_SKIN));
        }
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        for (ItemStack stack : inventory.removeAllItems()) {
            spawnAtLocation(stack);
        }
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
}
