package com.mcaibot.companion;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class AutonomyManager {
    private static final int CHECK_INTERVAL_TICKS = 20 * 10;
    private static final int DEFAULT_INITIAL_COOLDOWN_TICKS = 20 * 60;
    private static final int DEFAULT_SUCCESS_COOLDOWN_TICKS = 20 * 180;
    private static final int DEFAULT_ERROR_COOLDOWN_TICKS = 20 * 60;
    private static final int DEFAULT_EXPLICIT_INTERACTION_COOLDOWN_TICKS = 20 * 90;
    private static final double DEFAULT_NEARBY_PLAYER_RADIUS = 16.0D;
    private static final Set<String> SAFE_ACTIONS = Set.of(
            "none",
            "say",
            "ask_clarifying_question",
            "propose_plan",
            "report_status",
            "report_task_status",
            "recall"
    );

    private int tickCounter;
    private int nextAllowedTick = DEFAULT_INITIAL_COOLDOWN_TICKS;
    private boolean requestInFlight;
    private UUID lastTargetPlayerUuid;

    public void onServerTick(ServerTickEvent.Post event, BridgeClient bridgeClient, NpcProfileStore profileStore) {
        tickCounter++;
        if (tickCounter % CHECK_INTERVAL_TICKS != 0 || requestInFlight) {
            return;
        }

        MinecraftServer server = event.getServer();
        Mob npc = NpcManager.activeNpcMob(server);
        if (npc == null || !npc.isAlive()) {
            return;
        }

        NpcProfile profile = profileFor(server, profileStore);
        AutonomyPolicy policy = AutonomyPolicy.from(profile);
        if (!policy.enabled() || tickCounter < nextAllowedTick) {
            return;
        }
        if (ProtectionManager.isActive() && !policy.allowWhileGuarding()) {
            return;
        }

        ServerPlayer player = nearestEligiblePlayer(server, npc, policy, profile);
        if (player == null) {
            return;
        }

        JsonObject npcState = NpcManager.describeFor(player);
        if (!isNpcIdle(npcState)) {
            return;
        }
        String salienceReason = salienceReason(player, npc, policy);
        if (salienceReason.isBlank()) {
            return;
        }

        requestInFlight = true;
        lastTargetPlayerUuid = player.getUUID();
        BridgeContext context = BridgeContext.fromAutonomy(
                player,
                autonomyPrompt(npcState, policy),
                profileStore,
                policy.mode(),
                ticksToMillis(policy.successCooldownTicks()),
                policy.promptGuidance(),
                salienceReason
        );
        bridgeClient.decide(context).whenComplete((decision, error) -> server.execute(() -> {
            requestInFlight = false;
            if (error != null) {
                McAiCompanion.LOGGER.debug("Autonomy bridge request failed", error);
                nextAllowedTick = tickCounter + policy.errorCooldownTicks();
                return;
            }

            if (decision == null || decision.action() == null) {
                nextAllowedTick = tickCounter + policy.errorCooldownTicks();
                return;
            }

            String actionName = decision.action().name();
            if (!SAFE_ACTIONS.contains(actionName)) {
                TaskFeedback.warn(player, npc, "autonomy", "UNSAFE_ACTION_BLOCKED",
                        "Blocked autonomous action " + actionName + " because autonomy may not start tasks or move the NPC.");
                nextAllowedTick = tickCounter + policy.errorCooldownTicks();
                return;
            }

            BridgeActions.execute(player, decision, profileStore);
            TaskFeedback.info(player, npc, "autonomy", "AUTONOMY_TICK",
                    "Autonomous bridge response executed with action " + actionName + " using " + policy.mode() + " policy.");
            nextAllowedTick = tickCounter + policy.successCooldownTicks();
        }));
    }

    public void noteExplicitInteraction() {
        nextAllowedTick = Math.max(nextAllowedTick, tickCounter + DEFAULT_EXPLICIT_INTERACTION_COOLDOWN_TICKS);
    }

    private ServerPlayer nearestEligiblePlayer(MinecraftServer server, Mob npc, AutonomyPolicy policy, NpcProfile profile) {
        double radiusSqr = policy.nearbyPlayerRadius() * policy.nearbyPlayerRadius();
        return server.getPlayerList().getPlayers().stream()
                .filter(ServerPlayer::isAlive)
                .filter(player -> !player.isSpectator())
                .filter(player -> profile == null || profile.isOwnedBy(player.getUUID(), player.getGameProfile().getName()))
                .filter(player -> player.level() == npc.level())
                .filter(player -> player.distanceToSqr(npc) <= radiusSqr)
                .min(Comparator.comparingDouble(player -> player.distanceToSqr(npc)))
                .orElse(null);
    }

    private boolean isNpcIdle(JsonObject npcState) {
        String taskName = npcState.has("task") ? npcState.get("task").getAsString() : "idle";
        boolean following = npcState.has("following") && npcState.get("following").getAsBoolean();
        boolean paused = npcState.has("taskPaused") && npcState.get("taskPaused").getAsBoolean();
        return "idle".equals(taskName) && !following && !paused;
    }

    private String salienceReason(ServerPlayer player, Mob npc, AutonomyPolicy policy) {
        String mode = policy.mode();
        if (player.getHealth() <= Math.max(6.0F, player.getMaxHealth() * 0.4F)) {
            return "player_low_health";
        }
        if (nearbyHostile(player, npc, policy.nearbyPlayerRadius())) {
            return "nearby_hostile";
        }
        if (mode.equals("social") || mode.equals("proactive")) {
            return "profile_social_cadence";
        }
        return "";
    }

    private boolean nearbyHostile(ServerPlayer player, Mob npc, double radius) {
        AABB box = player.getBoundingBox().inflate(Math.max(8.0D, radius));
        return player.serverLevel().getEntitiesOfClass(Monster.class, box, Monster::isAlive).stream()
                .anyMatch(monster -> monster.distanceToSqr(player) <= radius * radius
                        || monster.distanceToSqr(npc) <= radius * radius);
    }

    private String autonomyPrompt(JsonObject npcState, AutonomyPolicy policy) {
        String name = npcState.has("name") ? npcState.get("name").getAsString() : McAiConfig.BOT_NAME.get();
        return "[AUTONOMY_TICK:" + policy.mode() + "] You are " + name + ". " + policy.promptGuidance() + " If it is useful, make one brief in-character observation, "
                + "ask one low-pressure question, or suggest a helpful next step. "
                + "Do not start tasks, move, guard, mine, build, collect, or interrupt the player.";
    }

    public UUID lastTargetPlayerUuid() {
        return lastTargetPlayerUuid;
    }

    private NpcProfile profileFor(MinecraftServer server, NpcProfileStore profileStore) {
        String profileId = NpcManager.activeProfileId(server);
        return profileStore == null
                ? NpcProfile.defaultProfile()
                : profileStore.findEnabled(profileId).orElseGet(profileStore::defaultProfile);
    }

    private record AutonomyPolicy(
            String mode,
            boolean enabled,
            boolean allowWhileGuarding,
            int successCooldownTicks,
            int errorCooldownTicks,
            double nearbyPlayerRadius,
            String promptGuidance
    ) {
        private static AutonomyPolicy from(NpcProfile profile) {
            String preferenceText = preferenceText(profile);
            if (containsAny(preferenceText, "autonomy_off", "autonomy off", "no_autonomy", "no autonomy", "disable_autonomy", "disable autonomy")) {
                return new AutonomyPolicy(
                        "off",
                        false,
                        false,
                        DEFAULT_SUCCESS_COOLDOWN_TICKS,
                        DEFAULT_ERROR_COOLDOWN_TICKS,
                        DEFAULT_NEARBY_PLAYER_RADIUS,
                        "Autonomous interaction is disabled by this NPC profile."
                );
            }
            if (containsAny(preferenceText, "quiet", "shy", "silent", "low_frequency", "low frequency")) {
                return new AutonomyPolicy(
                        "quiet",
                        true,
                        false,
                        20 * 360,
                        20 * 120,
                        10.0D,
                        "Prefer silence. Only speak when the observation is clearly useful and keep it very short."
                );
            }
            if (containsAny(preferenceText, "danger_only", "danger only", "danger-only", "urgent_only", "urgent only")) {
                return new AutonomyPolicy(
                        "danger_only",
                        true,
                        true,
                        20 * 240,
                        20 * 90,
                        16.0D,
                        "Only speak for danger, urgent blockers, or direct player requests."
                );
            }
            if (containsAny(preferenceText, "proactive", "chatty", "talkative", "initiative", "high_frequency", "high frequency")) {
                return new AutonomyPolicy(
                        "proactive",
                        true,
                        false,
                        20 * 90,
                        20 * 45,
                        20.0D,
                        "Be more willing to offer a concise helpful suggestion, but still avoid interrupting tasks."
                );
            }
            if (containsAny(preferenceText, "social", "natural interaction", "more interaction", "interact more")) {
                return new AutonomyPolicy(
                        "social",
                        true,
                        false,
                        20 * 75,
                        20 * 45,
                        20.0D,
                        "Interact more naturally and a bit more often, while keeping messages brief and non-repetitive."
                );
            }
            if (containsAny(preferenceText, "guardian", "guard", "protective", "protector", "watchful", "protect", "protection")) {
                return new AutonomyPolicy(
                        "guardian",
                        true,
                        true,
                        20 * 150,
                        20 * 60,
                        20.0D,
                        "Use a calm guardian tone. Prefer safety observations and threat awareness, without starting guard actions."
                );
            }
            return new AutonomyPolicy(
                    "balanced",
                    true,
                    false,
                    DEFAULT_SUCCESS_COOLDOWN_TICKS,
                    DEFAULT_ERROR_COOLDOWN_TICKS,
                    DEFAULT_NEARBY_PLAYER_RADIUS,
                    "Use a balanced cadence and only speak when there is a useful, low-pressure reason."
            );
        }

        private static String preferenceText(NpcProfile profile) {
            if (profile == null) {
                return "";
            }
            return (profile.style() + " " + profile.personality() + " " + profile.defaultRole()).toLowerCase(Locale.ROOT);
        }

        private static boolean containsAny(String text, String... tokens) {
            for (String token : tokens) {
                if (text.contains(token)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static int ticksToMillis(int ticks) {
        return Math.max(0, ticks * 50);
    }
}
