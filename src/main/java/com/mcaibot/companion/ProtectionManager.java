package com.mcaibot.companion;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class ProtectionManager {
    private static final int MIN_GUARD_RADIUS = 4;
    private static final int DEFAULT_GUARD_RADIUS = 16;
    private static final int MAX_GUARD_RADIUS = 24;
    private static final int ATTACK_COOLDOWN_TICKS = 20;
    private static final int NAVIGATION_INTERVAL_TICKS = 5;
    private static final int OWNER_PAUSE_TIMEOUT_TICKS = 20 * 600;
    private static final double ATTACK_REACH_SQR = 4.0D;
    private static final float GUARD_ATTACK_DAMAGE = 4.0F;

    private static UUID guardOwnerUuid;
    private static UUID threatUuid;
    private static int guardRadius = DEFAULT_GUARD_RADIUS;
    private static int tickCounter;
    private static int nextAttackTick;
    private static int ownerPauseTicks;
    private static boolean pauseAnnounced;
    private static boolean defaultProtectionSuppressed;

    private ProtectionManager() {
    }

    public static void start(ServerPlayer player) {
        start(player, McAiConfig.NPC_GUARD_RADIUS.get());
    }

    public static void start(ServerPlayer player, int requestedRadius) {
        defaultProtectionSuppressed = false;
        startInternal(player, requestedRadius, false);
    }

    public static void enableDefaultProtection(ServerPlayer player) {
        defaultProtectionSuppressed = false;
        if (McAiConfig.NPC_DEFAULT_PROTECT_PLAYER.get()) {
            startInternal(player, McAiConfig.NPC_GUARD_RADIUS.get(), true);
        }
    }

    private static void startInternal(ServerPlayer player, int requestedRadius, boolean defaultGuard) {
        Mob npc = findNpcMob(player.getServer());
        if (npc == null) {
            say(player, "NPC is not spawned. Use /mcai npc spawn.");
            return;
        }

        guardOwnerUuid = player.getUUID();
        threatUuid = null;
        guardRadius = clampRadius(requestedRadius);
        nextAttackTick = 0;
        ownerPauseTicks = 0;
        pauseAnnounced = false;
        npc.getNavigation().moveTo(player, McAiConfig.NPC_MOVE_SPEED.get());
        String message = (defaultGuard ? "Default guard enabled. " : "")
                + "Guarding you within " + guardRadius + " blocks. I will attack hostile mobs only, never players.";
        say(player, message);
        TaskFeedback.info(player, npc, "guard_player", "GUARD_STARTED", message);
        ToolSummary.recordAvailabilityFeedback(
                player,
                npc,
                "guard_player",
                ToolSummary.ToolKind.WEAPON,
                "WEAPON_AVAILABLE",
                "NEED_WEAPON",
                "Using base guard damage until a better weapon is available in NPC storage/equipment."
        );
    }

    public static void stop(ServerPlayer player) {
        defaultProtectionSuppressed = true;
        boolean wasActive = guardOwnerUuid != null;
        if (wasActive) {
            TaskFeedback.info(player, findNpcMob(player.getServer()), "guard_player", "GUARD_STOPPED", "Guard stopped by player.");
        }
        stopSilently(player.getServer());
        say(player, wasActive ? "Guard stopped. Default protection is disabled until /mcai npc guard." : "Guard was not active. Default protection is disabled until /mcai npc guard.");
    }

    public static void stopSilently(MinecraftServer server) {
        Mob npc = findNpcMob(server);
        if (guardOwnerUuid != null && npc != null) {
            npc.getNavigation().stop();
        }
        clearGuardTarget(npc);
        guardOwnerUuid = null;
        threatUuid = null;
        guardRadius = DEFAULT_GUARD_RADIUS;
        nextAttackTick = 0;
        ownerPauseTicks = 0;
        pauseAnnounced = false;
    }

    public static boolean isActive() {
        return guardOwnerUuid != null;
    }

    public static boolean hasActiveThreat() {
        return threatUuid != null;
    }

    public static JsonObject describeFor(ServerPlayer player) {
        JsonObject json = new JsonObject();
        json.addProperty("defaultEnabled", McAiConfig.NPC_DEFAULT_PROTECT_PLAYER.get());
        json.addProperty("defaultSuppressed", defaultProtectionSuppressed);
        json.addProperty("active", guardOwnerUuid != null);
        json.addProperty("owner", guardOwnerUuid == null ? "" : guardOwnerUuid.toString());
        json.addProperty("radius", guardRadius);
        json.addProperty("hasThreat", threatUuid != null);
        json.addProperty("threat", threatUuid == null ? "" : threatUuid.toString());
        json.addProperty("policy", "default-on; attacks hostile mobs only and never attacks player entities");
        return json;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (guardOwnerUuid == null) {
            ensureDefaultProtection(event.getServer());
            return;
        }

        tickCounter++;
        if (tickCounter % 2 != 0) {
            return;
        }

        MinecraftServer server = event.getServer();
        ServerPlayer owner = server.getPlayerList().getPlayer(guardOwnerUuid);
        Mob npc = findNpcMob(server);
        if (npc == null) {
            stopSilently(server);
            return;
        }

        if (owner == null || !owner.isAlive()) {
            pauseUntilOwnerReturns(owner, npc);
            return;
        }

        resumeIfPaused(owner);

        if (npc.level() != owner.level()) {
            clearGuardTarget(npc);
            pauseUntilOwnerReturns(owner, npc);
            return;
        }

        Mob threat = resolveThreat(owner.serverLevel(), npc, owner);
        if (threat == null) {
            clearGuardTarget(npc);
            if (NpcManager.hasActiveTask()) {
                return;
            }
            followOwner(owner, npc);
            return;
        }

        engageThreat(owner, npc, threat);
    }

    private static void ensureDefaultProtection(MinecraftServer server) {
        if (!McAiConfig.NPC_DEFAULT_PROTECT_PLAYER.get() || defaultProtectionSuppressed || findNpcMob(server) == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isAlive()) {
                startInternal(player, McAiConfig.NPC_GUARD_RADIUS.get(), true);
                return;
            }
        }
    }

    private static void pauseUntilOwnerReturns(ServerPlayer owner, Mob npc) {
        ownerPauseTicks += 2;
        npc.getNavigation().stop();
        clearGuardTarget(npc);

        if (!pauseAnnounced) {
            String message = "Guard paused. I will resume protecting you after you respawn or return to my dimension.";
            if (owner != null) {
                say(owner, message);
            }
            TaskFeedback.warn(owner, npc, "guard_player", "OWNER_UNAVAILABLE", message);
            pauseAnnounced = true;
        }

        if (ownerPauseTicks >= OWNER_PAUSE_TIMEOUT_TICKS) {
            TaskFeedback.failure(owner, npc, "guard_player", "OWNER_TIMEOUT", "Guard timed out while waiting for the owner to return.");
            if (owner != null) {
                say(owner, "Guard timed out while waiting for you to return.");
            }
            stopSilently(npc.getServer());
        }
    }

    private static void resumeIfPaused(ServerPlayer owner) {
        if (ownerPauseTicks <= 0) {
            return;
        }

        ownerPauseTicks = 0;
        pauseAnnounced = false;
        say(owner, "Guard resumed.");
        TaskFeedback.info(owner, findNpcMob(owner.getServer()), "guard_player", "RESUMED", "Guard resumed after owner returned.");
    }

    private static Mob resolveThreat(ServerLevel level, Mob npc, ServerPlayer owner) {
        if (threatUuid != null) {
            Entity entity = level.getEntity(threatUuid);
            if (entity instanceof Mob mob && isValidThreat(mob, npc, owner)) {
                return mob;
            }
        }

        List<Mob> threats = level.getEntitiesOfClass(
                Mob.class,
                owner.getBoundingBox().inflate(guardRadius),
                mob -> isValidThreat(mob, npc, owner)
        );

        return threats.stream()
                .min(Comparator
                        .comparingInt((Mob mob) -> mob.getTarget() == owner ? 0 : 1)
                        .thenComparingDouble(mob -> mob.distanceToSqr(owner))
                        .thenComparingDouble(mob -> mob.distanceToSqr(npc)))
                .orElse(null);
    }

    private static boolean isValidThreat(Mob mob, Mob npc, ServerPlayer owner) {
        if (!mob.isAlive() || mob == npc || mob.level() != owner.level()) {
            return false;
        }

        if (!isHostileMob(mob)) {
            return false;
        }

        double radiusSqr = guardRadius * guardRadius;
        return mob.distanceToSqr(owner) <= radiusSqr;
    }

    private static boolean isHostileMob(Entity entity) {
        if (entity instanceof Player) {
            return false;
        }

        return entity instanceof Mob && (entity instanceof Monster || entity instanceof Enemy);
    }

    private static void engageThreat(ServerPlayer owner, Mob npc, Mob threat) {
        boolean newThreat = !threat.getUUID().equals(threatUuid);
        threatUuid = threat.getUUID();
        npc.setTarget(threat);
        npc.getLookControl().setLookAt(threat, 30.0F, 30.0F);
        if (newThreat) {
            TaskFeedback.info(owner, npc, "guard_player", "THREAT_ENGAGED", "Engaging hostile mob " + threat.getName().getString() + ".");
        }

        if (npc.distanceToSqr(threat) > ATTACK_REACH_SQR) {
            if (tickCounter % NAVIGATION_INTERVAL_TICKS == 0) {
                npc.getNavigation().moveTo(threat, McAiConfig.NPC_MOVE_SPEED.get());
            }
            return;
        }

        npc.getNavigation().stop();
        if (tickCounter < nextAttackTick) {
            return;
        }

        npc.swing(InteractionHand.MAIN_HAND, true);
        threat.hurt(npc.damageSources().mobAttack(npc), Math.max(GUARD_ATTACK_DAMAGE, NpcManager.attackDamage(npc)));
        nextAttackTick = tickCounter + ATTACK_COOLDOWN_TICKS;
    }

    private static void followOwner(ServerPlayer owner, Mob npc) {
        double followDistance = Math.max(2.0D, McAiConfig.NPC_FOLLOW_DISTANCE.get());
        if (npc.distanceToSqr(owner) > followDistance * followDistance) {
            if (tickCounter % NAVIGATION_INTERVAL_TICKS == 0) {
                npc.getNavigation().moveTo(owner, McAiConfig.NPC_MOVE_SPEED.get());
            }
            return;
        }

        npc.getNavigation().stop();
        npc.getLookControl().setLookAt(owner, 20.0F, 20.0F);
    }

    private static void clearGuardTarget(Mob npc) {
        if (npc != null && threatUuid != null && npc.getTarget() != null && threatUuid.equals(npc.getTarget().getUUID())) {
            npc.setTarget(null);
        }
        threatUuid = null;
    }

    private static Mob findNpcMob(MinecraftServer server) {
        return NpcManager.activeNpcMob(server);
    }

    private static int clampRadius(int requestedRadius) {
        return Math.max(MIN_GUARD_RADIUS, Math.min(requestedRadius, MAX_GUARD_RADIUS));
    }

    private static void say(ServerPlayer player, String message) {
        NpcChat.say(player, message);
    }
}
