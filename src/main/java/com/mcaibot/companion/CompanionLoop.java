package com.mcaibot.companion;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CompanionLoop {
    private static final int EMBODIED_PRESENCE_INTERVAL_TICKS = 20;
    private static final int CHECK_INTERVAL_TICKS = 20 * 5;
    private static final int DEFAULT_INITIAL_COOLDOWN_TICKS = 20 * 60;
    private static final int DEFAULT_SUCCESS_COOLDOWN_TICKS = 20 * 150;
    private static final int DEFAULT_ERROR_COOLDOWN_TICKS = 20 * 45;
    private static final int EXPLICIT_INTERACTION_COOLDOWN_TICKS = 20 * 90;
    private static final int LOW_HEALTH_COOLDOWN_TICKS = 20 * 120;
    private static final int TASK_RESULT_COOLDOWN_TICKS = 20 * 75;
    private static final int NIGHT_COOLDOWN_TICKS = 20 * 240;
    private static final int LONG_SILENCE_COOLDOWN_TICKS = 20 * 420;
    private static final long LONG_SILENCE_MILLIS = 10L * 60L * 1000L;
    private static final double DEFAULT_NEARBY_PLAYER_RADIUS = 18.0D;
    private static final Set<String> SAFE_ACTIONS = Set.of(
            "none",
            "say",
            "ask_clarifying_question",
            "propose_plan",
            "report_status",
            "report_task_status",
            "recall"
    );
    private static final Map<UUID, LoopPlayerState> PLAYER_STATES = new HashMap<>();

    private int tickCounter;
    private int nextAllowedTick = DEFAULT_INITIAL_COOLDOWN_TICKS;
    private boolean requestInFlight;
    private String lastTrigger = "";
    private String lastTriggerReason = "";
    private long lastTriggerMillis;
    private UUID lastTargetPlayerUuid;

    public void onServerTick(ServerTickEvent.Post event, BridgeClient bridgeClient, NpcProfileStore profileStore) {
        tickCounter++;
        if (tickCounter % EMBODIED_PRESENCE_INTERVAL_TICKS == 0) {
            updateEmbodiedPresence(event.getServer(), profileStore);
        }
        if (tickCounter % CHECK_INTERVAL_TICKS != 0 || requestInFlight) {
            return;
        }

        MinecraftServer server = event.getServer();
        Mob npc = NpcManager.activeNpcMob(server);
        if (npc == null || !npc.isAlive()) {
            return;
        }

        NpcProfile profile = profileFor(server, profileStore);
        CompanionPolicy policy = CompanionPolicy.from(profile);
        if (!policy.enabled()) {
            return;
        }
        if (ProtectionManager.isActive() && !policy.allowWhileGuarding()) {
            return;
        }

        ServerPlayer player = nearestEligiblePlayer(server, npc, policy, profile);
        if (player == null) {
            return;
        }

        LoopPlayerState state = stateFor(player);
        JsonObject npcState = NpcManager.describeFor(player);
        TriggerCandidate candidate = selectTrigger(player, npc, npcState, state, policy);
        if (candidate == null) {
            return;
        }
        if (tickCounter < nextAllowedTick || tickCounter < state.nextAllowedTick) {
            state.consume(candidate, tickCounter);
            return;
        }
        if (!isNpcIdle(npcState)) {
            state.consume(candidate, tickCounter);
            return;
        }

        requestInFlight = true;
        lastTargetPlayerUuid = player.getUUID();
        state.consume(candidate, tickCounter);
        SocialMemory.recordTrigger(player, npc, candidate.trigger(), candidate.reason());
        BridgeContext context = BridgeContext.fromCompanionLoop(
                player,
                companionPrompt(npcState, policy, candidate),
                profileStore,
                policy.mode(),
                ticksToMillis(candidate.cooldownTicks()),
                candidate.guidance(),
                candidate.trigger(),
                candidate.reason()
        );
        bridgeClient.decide(context).whenComplete((decision, error) -> server.execute(() -> {
            requestInFlight = false;
            if (error != null) {
                McAiCompanion.LOGGER.debug("Companion loop bridge request failed", error);
                applyCooldown(state, DEFAULT_ERROR_COOLDOWN_TICKS);
                return;
            }
            if (decision == null || decision.action() == null) {
                applyCooldown(state, DEFAULT_ERROR_COOLDOWN_TICKS);
                return;
            }

            String actionName = decision.action().name();
            if (!SAFE_ACTIONS.contains(actionName)) {
                TaskFeedback.warn(player, npc, "companion_loop", "UNSAFE_ACTION_BLOCKED",
                        "Blocked companion loop action " + actionName + " because proactive companion turns may not start tasks or move the NPC.");
                applyCooldown(state, DEFAULT_ERROR_COOLDOWN_TICKS);
                return;
            }

            BridgeActions.execute(player, decision, profileStore);
            TaskFeedback.info(player, npc, "companion_loop", "COMPANION_TRIGGER",
                    "Companion loop executed " + actionName + " for trigger " + candidate.trigger() + ".");
            lastTrigger = candidate.trigger();
            lastTriggerReason = candidate.reason();
            lastTriggerMillis = System.currentTimeMillis();
            applyCooldown(state, candidate.cooldownTicks());
        }));
    }

    public void noteExplicitInteraction(ServerPlayer player) {
        if (player == null) {
            return;
        }
        LoopPlayerState state = stateFor(player);
        state.lastExplicitInteractionMillis = System.currentTimeMillis();
        state.nextAllowedTick = Math.max(state.nextAllowedTick, tickCounter + EXPLICIT_INTERACTION_COOLDOWN_TICKS);
        nextAllowedTick = Math.max(nextAllowedTick, tickCounter + EXPLICIT_INTERACTION_COOLDOWN_TICKS);
    }

    public UUID lastTargetPlayerUuid() {
        return lastTargetPlayerUuid;
    }

    public static JsonObject snapshotFor(ServerPlayer player, JsonObject npcState) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", "mc-agent-companion-loop-v1");
        json.addProperty("enabled", true);
        json.addProperty("safeActionsOnly", true);
        json.addProperty("mustNotInterruptTasks", true);
        json.addProperty("triggers", "low_health, night, task_complete, task_problem, long_silence");
        json.addProperty("globalCooldowns", "explicit interaction, trigger success, bridge error, per-trigger repeat suppression");
        json.addProperty("lastInteractionMillis", SocialMemory.lastInteractionMillis(player, npcState));

        if (player != null) {
            synchronized (PLAYER_STATES) {
                LoopPlayerState state = PLAYER_STATES.get(player.getUUID());
                if (state != null) {
                    json.addProperty("firstSeenMillis", state.firstSeenMillis);
                    json.addProperty("lastExplicitInteractionMillis", state.lastExplicitInteractionMillis);
                    json.addProperty("lastSeenTaskResultMillis", state.lastSeenTaskResultMillis);
                    json.addProperty("nextAllowedTick", state.nextAllowedTick);
                    json.add("triggerCooldowns", state.triggerCooldownsJson());
                }
            }
        }
        return json;
    }

    public JsonObject runtimeStateJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", "mc-agent-companion-loop-runtime-v1");
        json.addProperty("tickCounter", tickCounter);
        json.addProperty("nextAllowedTick", nextAllowedTick);
        json.addProperty("requestInFlight", requestInFlight);
        json.addProperty("lastTrigger", lastTrigger);
        json.addProperty("lastTriggerReason", lastTriggerReason);
        json.addProperty("lastTriggerMillis", lastTriggerMillis);
        if (lastTargetPlayerUuid != null) {
            json.addProperty("lastTargetPlayerUuid", lastTargetPlayerUuid.toString());
        }
        return json;
    }

    private TriggerCandidate selectTrigger(ServerPlayer player, Mob npc, JsonObject npcState, LoopPlayerState state, CompanionPolicy policy) {
        TaskResult latestResult = TaskFeedback.latestResultFor(player);
        if (latestResult != null && latestResult.timeMillis() > state.lastSeenTaskResultMillis) {
            String trigger = latestResult.isCompletion() ? "task_complete" : latestResult.isProblem() ? "task_problem" : "";
            if (!trigger.isBlank()) {
                return new TriggerCandidate(
                        trigger,
                        latestResult.taskName() + " " + latestResult.status() + ": " + latestResult.message(),
                        "Acknowledge the task outcome briefly. For failure or blockers, be calm and offer one next step without starting work.",
                        TASK_RESULT_COOLDOWN_TICKS,
                        latestResult.timeMillis(),
                        latestResult
                );
            }
            state.lastSeenTaskResultMillis = latestResult.timeMillis();
        }

        if (player.getHealth() <= Math.max(6.0F, player.getMaxHealth() * 0.4F)
                && !state.isTriggerCoolingDown("low_health", tickCounter)) {
            return new TriggerCandidate(
                    "low_health",
                    "Player health is low (" + Math.round(player.getHealth()) + "/" + Math.round(player.getMaxHealth()) + ").",
                    "Offer one concise safety check or reminder. Do not give commands or start combat/movement.",
                    LOW_HEALTH_COOLDOWN_TICKS,
                    0L,
                    null
            );
        }

        long dayTime = player.serverLevel().getDayTime();
        long dayCycle = Math.floorDiv(dayTime, 24000L);
        long timeOfDay = Math.floorMod(dayTime, 24000L);
        if (timeOfDay >= 13000L && timeOfDay <= 23000L && state.lastNightCycle != dayCycle) {
            return new TriggerCandidate(
                    "night",
                    "Night has started in the player's current dimension.",
                    "Make one low-pressure nighttime awareness comment only if useful.",
                    NIGHT_COOLDOWN_TICKS,
                    dayCycle,
                    null
            );
        }

        long lastInteraction = SocialMemory.lastInteractionMillis(player, npcState);
        long now = System.currentTimeMillis();
        long silenceAnchor = lastInteraction > 0L ? lastInteraction : state.firstSeenMillis;
        boolean socialCadence = policy.mode().equals("social") || policy.mode().equals("proactive");
        if (socialCadence
                && silenceAnchor > 0L
                && now - silenceAnchor >= LONG_SILENCE_MILLIS
                && !state.isTriggerCoolingDown("long_silence", tickCounter)) {
            return new TriggerCandidate(
                    "long_silence",
                    "No direct player/companion interaction for " + ((now - silenceAnchor) / 1000L) + " seconds.",
                    "Optionally make a short, non-demanding check-in. Prefer silence if there is no useful context.",
                    LONG_SILENCE_COOLDOWN_TICKS,
                    0L,
                    null
            );
        }

        if (nearbyHostile(player, npc, policy.nearbyPlayerRadius())
                && !state.isTriggerCoolingDown("nearby_hostile", tickCounter)) {
            return new TriggerCandidate(
                    "nearby_hostile",
                    "A hostile mob is near the player or companion.",
                    "Give one brief threat-awareness line. Do not start guard or attack actions from this loop.",
                    LOW_HEALTH_COOLDOWN_TICKS,
                    0L,
                    null
            );
        }

        return null;
    }

    private ServerPlayer nearestEligiblePlayer(MinecraftServer server, Mob npc, CompanionPolicy policy, NpcProfile profile) {
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

    private boolean nearbyHostile(ServerPlayer player, Mob npc, double radius) {
        AABB box = player.getBoundingBox().inflate(Math.max(8.0D, radius));
        return player.serverLevel().getEntitiesOfClass(Monster.class, box, Monster::isAlive).stream()
                .anyMatch(monster -> monster.distanceToSqr(player) <= radius * radius
                        || monster.distanceToSqr(npc) <= radius * radius);
    }

    private String companionPrompt(JsonObject npcState, CompanionPolicy policy, TriggerCandidate candidate) {
        String name = npcState.has("name") ? npcState.get("name").getAsString() : McAiConfig.BOT_NAME.get();
        return "[COMPANION_TRIGGER:" + candidate.trigger() + "] You are " + name + ". "
                + policy.promptGuidance() + " Trigger reason: " + candidate.reason() + " "
                + candidate.guidance() + " Use social.relationship and social.recentEvents if relevant. "
                + "Return one brief in-character message or no action. Do not start tasks, move, guard, mine, build, collect, or interrupt the player.";
    }

    private void applyCooldown(LoopPlayerState state, int cooldownTicks) {
        int clamped = Math.max(20, cooldownTicks);
        nextAllowedTick = tickCounter + clamped;
        state.nextAllowedTick = tickCounter + clamped;
    }

    private NpcProfile profileFor(MinecraftServer server, NpcProfileStore profileStore) {
        String profileId = NpcManager.activeProfileId(server);
        return profileStore == null
                ? NpcProfile.defaultProfile()
                : profileStore.findEnabled(profileId).orElseGet(profileStore::defaultProfile);
    }

    private LoopPlayerState stateFor(ServerPlayer player) {
        synchronized (PLAYER_STATES) {
            return PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new LoopPlayerState());
        }
    }

    private void updateEmbodiedPresence(MinecraftServer server, NpcProfileStore profileStore) {
        Mob npc = NpcManager.activeNpcMob(server);
        if (npc == null || !npc.isAlive()) {
            return;
        }
        NpcProfile profile = profileFor(server, profileStore);
        CompanionPolicy policy = CompanionPolicy.from(profile);
        if (!policy.enabled()) {
            return;
        }
        ServerPlayer player = nearestEligiblePlayer(server, npc, policy, profile);
        if (player == null || player.distanceToSqr(npc) > 144.0D) {
            return;
        }
        JsonObject npcState = NpcManager.describeFor(player);
        if (!isNpcIdle(npcState)) {
            return;
        }
        npc.getLookControl().setLookAt(player, 30.0F, 30.0F);
    }

    private static int ticksToMillis(int ticks) {
        return Math.max(0, ticks * 50);
    }

    private record TriggerCandidate(
            String trigger,
            String reason,
            String guidance,
            int cooldownTicks,
            long marker,
            TaskResult taskResult
    ) {
    }

    private static final class LoopPlayerState {
        private int nextAllowedTick;
        private final long firstSeenMillis = System.currentTimeMillis();
        private long lastExplicitInteractionMillis;
        private long lastSeenTaskResultMillis;
        private long lastNightCycle = -1L;
        private final Map<String, Integer> triggerCooldownUntilTick = new HashMap<>();

        private boolean isTriggerCoolingDown(String trigger, int currentTick) {
            Integer until = triggerCooldownUntilTick.get(trigger);
            return until != null && currentTick < until;
        }

        private void consume(TriggerCandidate candidate, int currentTick) {
            if (candidate == null) {
                return;
            }
            if (candidate.taskResult() != null) {
                lastSeenTaskResultMillis = Math.max(lastSeenTaskResultMillis, candidate.taskResult().timeMillis());
            }
            if ("night".equals(candidate.trigger())) {
                lastNightCycle = candidate.marker();
            }
            triggerCooldownUntilTick.put(candidate.trigger(), currentTick + candidate.cooldownTicks());
        }

        private JsonObject triggerCooldownsJson() {
            JsonObject json = new JsonObject();
            for (Map.Entry<String, Integer> entry : triggerCooldownUntilTick.entrySet()) {
                json.addProperty(entry.getKey(), entry.getValue());
            }
            return json;
        }
    }

    private record CompanionPolicy(
            String mode,
            boolean enabled,
            boolean allowWhileGuarding,
            int successCooldownTicks,
            double nearbyPlayerRadius,
            String promptGuidance
    ) {
        private static CompanionPolicy from(NpcProfile profile) {
            String preferenceText = preferenceText(profile);
            if (containsAny(preferenceText, "autonomy_off", "autonomy off", "no_autonomy", "no autonomy", "disable_autonomy", "disable autonomy")) {
                return new CompanionPolicy(
                        "off",
                        false,
                        false,
                        DEFAULT_SUCCESS_COOLDOWN_TICKS,
                        DEFAULT_NEARBY_PLAYER_RADIUS,
                        "Proactive companion interaction is disabled by this NPC profile."
                );
            }
            if (containsAny(preferenceText, "quiet", "shy", "silent", "low_frequency", "low frequency")) {
                return new CompanionPolicy(
                        "quiet",
                        true,
                        false,
                        20 * 360,
                        10.0D,
                        "Prefer silence. Speak only for clearly useful safety, task, or direct social context."
                );
            }
            if (containsAny(preferenceText, "danger_only", "danger only", "danger-only", "urgent_only", "urgent only")) {
                return new CompanionPolicy(
                        "danger_only",
                        true,
                        true,
                        20 * 240,
                        16.0D,
                        "Only speak for danger, urgent blockers, or direct player requests."
                );
            }
            if (containsAny(preferenceText, "proactive", "chatty", "talkative", "initiative", "high_frequency", "high frequency")) {
                return new CompanionPolicy(
                        "proactive",
                        true,
                        false,
                        20 * 90,
                        20.0D,
                        "Be willing to make concise helpful comments, while avoiding interruptions."
                );
            }
            if (containsAny(preferenceText, "social", "natural interaction", "more interaction", "interact more")) {
                return new CompanionPolicy(
                        "social",
                        true,
                        false,
                        20 * 100,
                        20.0D,
                        "Interact naturally and briefly, using relationship context when relevant."
                );
            }
            if (containsAny(preferenceText, "guardian", "guard", "protective", "protector", "watchful", "protect", "protection")) {
                return new CompanionPolicy(
                        "guardian",
                        true,
                        true,
                        20 * 150,
                        20.0D,
                        "Use a calm guardian tone and prioritize safety observations."
                );
            }
            return new CompanionPolicy(
                    "balanced",
                    true,
                    false,
                    DEFAULT_SUCCESS_COOLDOWN_TICKS,
                    DEFAULT_NEARBY_PLAYER_RADIUS,
                    "Use a balanced cadence and speak only when the trigger is useful and low-pressure."
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
}
