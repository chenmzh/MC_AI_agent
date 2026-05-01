package com.mcaibot.companion;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public final class SurvivalActions {
    private static final int DEFAULT_RADIUS = 12;
    private static final int MAX_RADIUS = 24;

    private SurvivalActions() {
    }

    public static ActionResult reportEnvironment(ServerPlayer player) {
        JsonObject snapshot = SurvivalEnvironment.snapshotFor(player);
        JsonObject priorities = snapshot.getAsJsonObject("priorities");
        NpcChat.say(player, "Environment: danger=" + priorities.get("dangerLevel").getAsString()
                + ", next=" + priorities.get("recommendedFirstAction").getAsString() + ".");
        return ActionResult.success("ENVIRONMENT_REPORTED", "Reported survival environment snapshot.")
                .withObservation("survivalEnvironment", snapshot);
    }

    public static ActionResult survivalAssist(ServerPlayer player) {
        JsonObject environment = SurvivalEnvironment.snapshotFor(player);
        JsonObject priorities = environment.getAsJsonObject("priorities");
        String next = priorities.get("recommendedFirstAction").getAsString();
        if ("guard_player".equals(next)) {
            ProtectionManager.start(player, McAiConfig.NPC_GUARD_RADIUS.get());
            return ActionResult.started("SURVIVAL_GUARD_STARTED", "Immediate danger detected; started guard mode.")
                    .withObservation("survivalEnvironment", environment);
        }
        if ("harvest_crops".equals(next)) {
            return harvestCrops(player, DEFAULT_RADIUS).withObservation("survivalEnvironment", environment);
        }
        if ("harvest_logs".equals(next)) {
            NpcManager.harvestLogs(player, McAiConfig.NPC_TASK_RADIUS.get(), 90);
            return ActionResult.started("SURVIVAL_LOGS_STARTED", "Nearby logs detected; started log harvesting for survival materials.")
                    .withObservation("survivalEnvironment", environment);
        }
        return ActionResult.success("SURVIVAL_OBSERVED", priorities.get("reason").getAsString())
                .withObservation("survivalEnvironment", environment);
    }

    public static ActionResult tillField(ServerPlayer player, int requestedRadius) {
        Mob npc = requireNpc(player, "till_field");
        if (npc == null) {
            return missingNpc();
        }
        if (!hasNpcItem(player, stack -> itemId(stack).endsWith("_hoe"))) {
            return ActionResult.blocked("NEED_HOE", "The NPC needs a hoe in NPC storage before tilling farmland.",
                    "Put a hoe in NPC storage or add hoe crafting before retrying.");
        }

        ServerLevel level = player.serverLevel();
        int radius = clampRadius(requestedRadius);
        int tilled = 0;
        int maxTiles = Math.min(25, radius * radius);
        for (BlockPos pos : BlockPos.betweenClosed(player.blockPosition().offset(-radius, -1, -radius), player.blockPosition().offset(radius, 1, radius))) {
            if (tilled >= maxTiles) {
                break;
            }
            BlockPos target = pos.immutable();
            if (!isTillable(level.getBlockState(target)) || !level.getBlockState(target.above()).isAir()) {
                continue;
            }
            if (!hasNearbyWater(level, target, 4)) {
                continue;
            }
            level.setBlock(target, Blocks.FARMLAND.defaultBlockState(), 3);
            tilled++;
        }

        if (tilled <= 0) {
            return ActionResult.blocked("NO_TILLABLE_WATERED_SOIL",
                    "No safe dirt/grass blocks near water were found for a bounded starter field.",
                    "Stand near dirt/grass and a water source, or place water before asking again.");
        }
        String message = "Tilled " + tilled + " watered farmland blocks for a starter field.";
        NpcChat.say(player, message);
        TaskFeedback.info(player, npc, "till_field", "FIELD_TILLED", message);
        return ActionResult.success("FIELD_TILLED", message).withEffect("tilledBlocks", tilled);
    }

    public static ActionResult plantCrop(ServerPlayer player, String requestedCrop, int requestedRadius) {
        Mob npc = requireNpc(player, "plant_crop");
        if (npc == null) {
            return missingNpc();
        }
        CropSeed crop = cropSeed(requestedCrop);
        Container inventory = NpcManager.activeNpcInventory(player.getServer());
        if (inventory == null) {
            return missingNpc();
        }

        int radius = clampRadius(requestedRadius);
        int planted = 0;
        for (BlockPos pos : BlockPos.betweenClosed(player.blockPosition().offset(-radius, -1, -radius), player.blockPosition().offset(radius, 1, radius))) {
            BlockPos soil = pos.immutable();
            BlockPos cropPos = soil.above();
            if (!player.serverLevel().getBlockState(soil).is(Blocks.FARMLAND)
                    || !player.serverLevel().getBlockState(cropPos).isAir()
                    || !consumeNpcItem(inventory, stack -> stack.is(crop.seedItem()), 1)) {
                continue;
            }
            player.serverLevel().setBlock(cropPos, crop.cropBlock().defaultBlockState(), 3);
            planted++;
        }

        if (planted <= 0) {
            return ActionResult.blocked("NO_SEEDS_OR_FARMLAND",
                    "No matching seeds in NPC storage or no empty farmland was found nearby.",
                    "Put seeds in NPC storage, till farmland near water, then retry.");
        }
        String message = "Planted " + planted + " " + crop.label() + " crop blocks from NPC storage.";
        NpcChat.say(player, message);
        TaskFeedback.info(player, npc, "plant_crop", "CROPS_PLANTED", message);
        return ActionResult.success("CROPS_PLANTED", message).withEffect("planted", planted).withEffect("crop", crop.label());
    }

    public static ActionResult harvestCrops(ServerPlayer player, int requestedRadius) {
        Mob npc = requireNpc(player, "harvest_crops");
        if (npc == null) {
            return missingNpc();
        }
        ServerLevel level = player.serverLevel();
        int radius = clampRadius(requestedRadius);
        int harvested = 0;
        for (BlockPos pos : BlockPos.betweenClosed(player.blockPosition().offset(-radius, -4, -radius), player.blockPosition().offset(radius, 4, radius))) {
            BlockPos cropPos = pos.immutable();
            BlockState state = level.getBlockState(cropPos);
            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                level.destroyBlock(cropPos, true, npc);
                harvested++;
            }
        }
        if (harvested <= 0) {
            return ActionResult.blocked("NO_MATURE_CROPS", "No mature crops were found in the bounded scan.",
                    "Move near mature crops or ask for a larger safe radius.");
        }
        NpcManager.collectItems(player, Math.min(MAX_RADIUS, Math.max(radius, 8)));
        String message = "Harvested " + harvested + " mature crops and started collecting the drops.";
        NpcChat.say(player, message);
        TaskFeedback.info(player, npc, "harvest_crops", "CROPS_HARVESTED", message);
        return ActionResult.started("CROPS_HARVESTED", message).withEffect("harvested", harvested);
    }

    public static ActionResult huntFoodAnimal(ServerPlayer player, String requestedAnimal, int requestedRadius) {
        Mob npc = requireNpc(player, "hunt_food_animal");
        if (npc == null) {
            return missingNpc();
        }
        Animal target = findAnimal(player, requestedAnimal, requestedRadius, animal ->
                SurvivalEnvironment.isFoodAnimal(animal) && !SurvivalEnvironment.isProtectedAnimal(animal));
        if (target == null) {
            return ActionResult.blocked("NO_SAFE_HUNT_TARGET",
                    "No adult unprotected food animal was found. I will not attack named, baby, tameable, leashed, or fenced animals.",
                    "Move near wild cows, sheep, pigs, chickens, or rabbits and confirm the hunt again.");
        }
        if (npc.distanceToSqr(target) > 9.0D) {
            npc.getNavigation().moveTo(target, McAiConfig.NPC_MOVE_SPEED.get());
            return ActionResult.started("HUNT_APPROACHING", "Moving toward wild " + SurvivalEnvironment.entityId(target) + " before attacking.")
                    .withEffect("target", SurvivalEnvironment.entityId(target));
        }
        npc.swing(InteractionHand.MAIN_HAND);
        npc.doHurtTarget(target);
        String message = "Attacked wild " + SurvivalEnvironment.entityId(target) + ". I avoided protected animals.";
        NpcChat.say(player, message);
        TaskFeedback.info(player, npc, "hunt_food_animal", "HUNT_ATTACKED", message);
        return ActionResult.started("HUNT_ATTACKED", message).withEffect("target", SurvivalEnvironment.entityId(target));
    }

    public static ActionResult feedAnimal(ServerPlayer player, String requestedAnimal, int requestedRadius) {
        Animal target = findAnimal(player, requestedAnimal, requestedRadius, animal -> true);
        if (target == null) {
            return ActionResult.blocked("NO_ANIMAL_TARGET", "No matching nearby animal was found.",
                    "Move near the animal or specify cow, sheep, pig, chicken, wolf, cat, or horse.");
        }
        Container inventory = NpcManager.activeNpcInventory(player.getServer());
        if (inventory == null) {
            return missingNpc();
        }
        ItemStack food = consumeAnimalFood(inventory, target);
        if (food.isEmpty()) {
            return ActionResult.blocked("NO_ANIMAL_FOOD", "NPC storage has no food accepted by " + SurvivalEnvironment.entityId(target) + ".",
                    "Put the correct feed item in NPC storage, such as wheat, seeds, carrot, bone, or fish.");
        }
        if (target instanceof Animal animal && !animal.isBaby()) {
            animal.setInLove(player);
        }
        String message = "Fed " + SurvivalEnvironment.entityId(target) + " with " + itemId(food) + ".";
        NpcChat.say(player, message);
        TaskFeedback.info(player, NpcManager.activeNpcMob(player.getServer()), "feed_animal", "ANIMAL_FED", message);
        return ActionResult.success("ANIMAL_FED", message).withEffect("animal", SurvivalEnvironment.entityId(target)).withEffect("item", itemId(food));
    }

    public static ActionResult breedAnimals(ServerPlayer player, String requestedAnimal, int requestedRadius) {
        Container inventory = NpcManager.activeNpcInventory(player.getServer());
        if (inventory == null) {
            return missingNpc();
        }
        List<Animal> targets = player.serverLevel().getEntitiesOfClass(Animal.class, player.getBoundingBox().inflate(clampRadius(requestedRadius)),
                        animal -> animal.isAlive()
                                && !animal.isBaby()
                                && matchesAnimalRequest(animal, requestedAnimal)
                                && !SurvivalEnvironment.isProtectedAnimal(animal))
                .stream()
                .sorted(Comparator.comparingDouble(animal -> animal.distanceToSqr(player)))
                .limit(2)
                .toList();
        if (targets.size() < 2) {
            return ActionResult.blocked("NEED_TWO_BREEDABLE_ANIMALS",
                    "Need two adult unprotected matching animals nearby before breeding.",
                    "Move near two adult animals of the same type and keep feed in NPC storage.");
        }
        int fed = 0;
        String item = "";
        for (Animal animal : targets) {
            ItemStack food = consumeAnimalFood(inventory, animal);
            if (food.isEmpty()) {
                break;
            }
            item = itemId(food);
            animal.setInLove(player);
            fed++;
        }
        if (fed < 2) {
            return ActionResult.blocked("NO_BREEDING_FOOD",
                    "NPC storage does not have enough matching food to feed two animals.",
                    "Add more feed to NPC storage and retry.");
        }
        String message = "Fed two animals for breeding using " + item + ".";
        NpcChat.say(player, message);
        TaskFeedback.info(player, NpcManager.activeNpcMob(player.getServer()), "breed_animals", "ANIMALS_BRED", message);
        return ActionResult.success("ANIMALS_BRED", message).withEffect("fed", fed).withEffect("item", item);
    }

    public static ActionResult tameAnimal(ServerPlayer player, String requestedAnimal, int requestedRadius) {
        Container inventory = NpcManager.activeNpcInventory(player.getServer());
        if (inventory == null) {
            return missingNpc();
        }
        Animal target = findAnimal(player, requestedAnimal, requestedRadius, SurvivalEnvironment::isTameableAnimal);
        if (!(target instanceof TamableAnimal tameable)) {
            return ActionResult.blocked("NO_TAMEABLE_TARGET", "No supported tameable animal was found nearby.",
                    "Move near a wolf or cat and put bones or fish in NPC storage.");
        }
        if (tameable.isTame()) {
            return ActionResult.success("ALREADY_TAME", "That animal is already tamed.");
        }
        ItemStack item = consumeTamingItem(inventory, tameable);
        if (item.isEmpty()) {
            return ActionResult.blocked("NO_TAMING_ITEM", "NPC storage has no safe taming item for " + SurvivalEnvironment.entityId(tameable) + ".",
                    "Use bones for wolves or cod/salmon for cats.");
        }
        tameable.tame(player);
        String message = "Tamed " + SurvivalEnvironment.entityId(tameable) + " using " + itemId(item) + ".";
        NpcChat.say(player, message);
        TaskFeedback.info(player, NpcManager.activeNpcMob(player.getServer()), "tame_animal", "ANIMAL_TAMED", message);
        return ActionResult.success("ANIMAL_TAMED", message).withEffect("animal", SurvivalEnvironment.entityId(tameable));
    }

    public static ActionResult buildRedstoneTemplate(ServerPlayer player, String templateName) {
        Mob npc = requireNpc(player, "build_redstone_template");
        if (npc == null) {
            return missingNpc();
        }
        String template = normalize(templateName);
        if (template.isBlank() || template.equals("redstone") || template.equals("auto_door")) {
            template = "pressure_door";
        }
        if (!template.equals("pressure_door") && !template.equals("automatic_door")) {
            return ActionResult.blocked("UNSUPPORTED_REDSTONE_TEMPLATE",
                    "Supported redstone template v1 is pressure_door only.",
                    "Ask for pressure_door, or add another verified template to the registry first.");
        }
        Container inventory = NpcManager.activeNpcInventory(player.getServer());
        if (inventory == null) {
            return missingNpc();
        }
        if (!hasNpcItem(player, stack -> itemId(stack).endsWith("_door"))
                || countNpcItems(player, stack -> itemId(stack).endsWith("_pressure_plate")) < 2) {
            return ActionResult.blocked("NEED_REDSTONE_DOOR_MATERIALS",
                    "NPC storage needs one door and two pressure plates for the pressure_door template.",
                    "Put a door and two pressure plates in NPC storage, or ask the NPC to craft them first.");
        }
        Direction facing = horizontal(player.getDirection());
        BlockPos lower = player.blockPosition().relative(facing, 3);
        BlockPos frontPlate = lower.relative(facing);
        BlockPos backPlate = lower.relative(facing.getOpposite());
        ServerLevel level = player.serverLevel();
        if (!canPlaceDoorTemplate(level, lower, frontPlate, backPlate)) {
            return ActionResult.blocked("REDSTONE_TEMPLATE_SPACE_BLOCKED",
                    "The pressure door template needs clear two-block door space plus clear pressure plate blocks on solid ground.",
                    "Stand facing a clear flat location and retry.");
        }

        consumeNpcItem(inventory, stack -> itemId(stack).endsWith("_door"), 1);
        consumeNpcItem(inventory, stack -> itemId(stack).endsWith("_pressure_plate"), 2);
        BlockState lowerDoor = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT)
                .setValue(BlockStateProperties.OPEN, false);
        level.setBlock(lower, lowerDoor, 3);
        level.setBlock(lower.above(), lowerDoor.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 3);
        level.setBlock(frontPlate, Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), 3);
        level.setBlock(backPlate, Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), 3);
        String message = "Built verified pressure_door template at " + lower.toShortString() + ".";
        NpcChat.say(player, message);
        TaskFeedback.info(player, npc, "build_redstone_template", "REDSTONE_TEMPLATE_BUILT", message);
        return ActionResult.success("REDSTONE_TEMPLATE_BUILT", message).withEffect("template", "pressure_door");
    }

    private static boolean canPlaceDoorTemplate(ServerLevel level, BlockPos lower, BlockPos frontPlate, BlockPos backPlate) {
        return level.getBlockState(lower).isAir()
                && level.getBlockState(lower.above()).isAir()
                && level.getBlockState(frontPlate).isAir()
                && level.getBlockState(backPlate).isAir()
                && level.getBlockState(lower.below()).isSolidRender(level, lower.below())
                && level.getBlockState(frontPlate.below()).isSolidRender(level, frontPlate.below())
                && level.getBlockState(backPlate.below()).isSolidRender(level, backPlate.below());
    }

    private static Animal findAnimal(ServerPlayer player, String request, int requestedRadius, Predicate<Animal> extraFilter) {
        int radius = clampRadius(requestedRadius);
        return player.serverLevel().getEntitiesOfClass(Animal.class, player.getBoundingBox().inflate(radius), animal ->
                        animal.isAlive()
                                && matchesAnimalRequest(animal, request)
                                && extraFilter.test(animal))
                .stream()
                .min(Comparator.comparingDouble(animal -> animal.distanceToSqr(player)))
                .orElse(null);
    }

    private static boolean matchesAnimalRequest(Animal animal, String request) {
        String normalized = normalize(request);
        if (normalized.isBlank() || normalized.equals("animal") || normalized.equals("animals")) {
            return true;
        }
        String id = SurvivalEnvironment.entityId(animal);
        return id.contains(normalized) || normalized.contains(id.substring(id.indexOf(':') + 1));
    }

    private static ItemStack consumeAnimalFood(Container inventory, Animal animal) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && animal.isFood(stack)) {
                ItemStack consumed = stack.copy();
                consumed.setCount(1);
                stack.shrink(1);
                inventory.setChanged();
                return consumed;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack consumeTamingItem(Container inventory, TamableAnimal animal) {
        String id = SurvivalEnvironment.entityId(animal);
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            boolean ok = (id.equals("minecraft:wolf") && stack.is(Items.BONE))
                    || (id.equals("minecraft:cat") && (stack.is(Items.COD) || stack.is(Items.SALMON)));
            if (!ok) {
                continue;
            }
            ItemStack consumed = stack.copy();
            consumed.setCount(1);
            stack.shrink(1);
            inventory.setChanged();
            return consumed;
        }
        return ItemStack.EMPTY;
    }

    private static boolean isTillable(BlockState state) {
        return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL);
    }

    private static boolean hasNearbyWater(ServerLevel level, BlockPos pos, int radius) {
        for (BlockPos water : BlockPos.betweenClosed(pos.offset(-radius, -1, -radius), pos.offset(radius, 1, radius))) {
            if (level.getBlockState(water).is(Blocks.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNpcItem(ServerPlayer player, Predicate<ItemStack> matcher) {
        return countNpcItems(player, matcher) > 0;
    }

    private static int countNpcItems(ServerPlayer player, Predicate<ItemStack> matcher) {
        Container inventory = NpcManager.activeNpcInventory(player.getServer());
        if (inventory == null) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && matcher.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean consumeNpcItem(Container inventory, Predicate<ItemStack> matcher, int count) {
        int remaining = count;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            int consumed = Math.min(remaining, stack.getCount());
            stack.shrink(consumed);
            remaining -= consumed;
            inventory.setChanged();
        }
        return remaining <= 0;
    }

    private static CropSeed cropSeed(String request) {
        String normalized = normalize(request);
        if (normalized.contains("carrot")) {
            return new CropSeed(Items.CARROT, Blocks.CARROTS, "carrot");
        }
        if (normalized.contains("potato")) {
            return new CropSeed(Items.POTATO, Blocks.POTATOES, "potato");
        }
        if (normalized.contains("beet")) {
            return new CropSeed(Items.BEETROOT_SEEDS, Blocks.BEETROOTS, "beetroot");
        }
        return new CropSeed(Items.WHEAT_SEEDS, Blocks.WHEAT, "wheat");
    }

    private static Mob requireNpc(ServerPlayer player, String taskName) {
        Mob npc = NpcManager.activeNpcMob(player.getServer());
        if (npc == null) {
            TaskFeedback.failure(player, null, taskName, "NO_NPC", "NPC is not spawned.");
        }
        return npc;
    }

    private static ActionResult missingNpc() {
        return ActionResult.blocked("NO_NPC", "NPC is not spawned.", "Use /mcai npc spawn before asking for survival work.");
    }

    private static int clampRadius(int radius) {
        if (radius <= 0) {
            return DEFAULT_RADIUS;
        }
        return Math.max(4, Math.min(MAX_RADIUS, radius));
    }

    private static Direction horizontal(Direction direction) {
        return direction == null || direction.getAxis() == Direction.Axis.Y ? Direction.NORTH : direction;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_').trim();
    }

    private record CropSeed(Item seedItem, Block cropBlock, String label) {
    }
}
