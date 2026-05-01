package com.mcaibot.companion;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class McAiConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> BRIDGE_URL = BUILDER
            .comment("HTTP bridge decision endpoint exposed by mc-ai-bot.")
            .define("bridgeUrl", "http://127.0.0.1:8787/bridge/decide");

    public static final ModConfigSpec.ConfigValue<String> BRIDGE_TOKEN = BUILDER
            .comment("Optional bridge token. Sent as x-bridge-token. Leave empty only for local testing.")
            .define("bridgeToken", "");

    public static final ModConfigSpec.ConfigValue<String> BOT_NAME = BUILDER
            .comment("NPC/bot display name used in chat replies and mention detection.")
            .define("botName", "CodexBot");

    public static final ModConfigSpec.ConfigValue<String> TRIGGER_PREFIX = BUILDER
            .comment("Chat prefix that sends a message to the bridge. Empty disables prefix matching.")
            .define("triggerPrefix", "!ai ");

    public static final ModConfigSpec.BooleanValue ENABLE_CHAT_LISTENER = BUILDER
            .comment("Whether normal player chat can trigger the bridge.")
            .define("enableChatListener", true);

    public static final ModConfigSpec.ConfigValue<String> CHAT_LISTEN_MODE = BUILDER
            .comment("Normal chat bridge trigger mode: off, mention, or all. mention listens for bot/profile names and triggerPrefix; all treats nearby normal chat as NPC conversation.")
            .define("chatListenMode", "mention");

    public static final ModConfigSpec.BooleanValue CHAT_ALL_REQUIRES_NPC_NEARBY = BUILDER
            .comment("When chatListenMode=all, only treat normal chat as NPC input if a companion NPC is nearby.")
            .define("chatAllRequiresNpcNearby", true);

    public static final ModConfigSpec.IntValue CHAT_ALL_RADIUS = BUILDER
            .comment("Distance in blocks for chatListenMode=all when chatAllRequiresNpcNearby is true.")
            .defineInRange("chatAllRadius", 24, 4, 128);

    public static final ModConfigSpec.IntValue CHAT_COOLDOWN_MS = BUILDER
            .comment("Minimum milliseconds between bridge requests from normal chat per player. Set 0 to disable.")
            .defineInRange("chatCooldownMs", 1000, 0, 60000);

    public static final ModConfigSpec.IntValue SCAN_RADIUS = BUILDER
            .comment("Radius used for local entity scans.")
            .defineInRange("scanRadius", 32, 4, 128);

    public static final ModConfigSpec.IntValue REQUEST_TIMEOUT_MS = BUILDER
            .comment("HTTP timeout for bridge requests.")
            .defineInRange("requestTimeoutMs", 90000, 1000, 300000);

    public static final ModConfigSpec.DoubleValue NPC_MOVE_SPEED = BUILDER
            .comment("Navigation speed used by the in-world NPC entity.")
            .defineInRange("npcMoveSpeed", 1.1, 0.1, 3.0);

    public static final ModConfigSpec.IntValue NPC_FOLLOW_DISTANCE = BUILDER
            .comment("Distance in blocks the NPC tries to keep while following a player.")
            .defineInRange("npcFollowDistance", 4, 1, 32);

    public static final ModConfigSpec.BooleanValue NPC_DEFAULT_PROTECT_PLAYER = BUILDER
            .comment("Whether the NPC automatically protects its owner by default after spawn/server load. Use /mcai npc unguard to opt out at runtime.")
            .define("npcDefaultProtectPlayer", true);

    public static final ModConfigSpec.IntValue NPC_GUARD_RADIUS = BUILDER
            .comment("Default guard radius used when the NPC protects a player.")
            .defineInRange("npcGuardRadius", 16, 4, 24);

    public static final ModConfigSpec.BooleanValue NPC_INVULNERABLE = BUILDER
            .comment("Whether the spawned NPC entity is invulnerable.")
            .define("npcInvulnerable", true);

    public static final ModConfigSpec.IntValue NPC_TASK_RADIUS = BUILDER
            .comment("Maximum radius used by NPC work tasks such as collecting drops, mining ores, and harvesting logs.")
            .defineInRange("npcTaskRadius", 16, 4, 32);

    public static final ModConfigSpec.IntValue NPC_MAX_TASK_STEPS = BUILDER
            .comment("Maximum collected item stacks or harvested blocks per task request.")
            .defineInRange("npcMaxTaskSteps", 12, 1, 64);

    public static final ModConfigSpec.IntValue NPC_BLOCK_BREAK_TICKS = BUILDER
            .comment("Ticks spent breaking each NPC-harvested block.")
            .defineInRange("npcBlockBreakTicks", 30, 5, 200);

    public static final ModConfigSpec.IntValue NPC_BLOCK_PLACE_TICKS = BUILDER
            .comment("Minimum ticks between NPC block placements while building.")
            .defineInRange("npcBlockPlaceTicks", 5, 1, 40);

    public static final ModConfigSpec.BooleanValue DEV_TEST_SERVER_ENABLED = BUILDER
            .comment("Enable a localhost-only development test HTTP server. It is intended for automated local testing.")
            .define("devTestServerEnabled", true);

    public static final ModConfigSpec.IntValue DEV_TEST_SERVER_PORT = BUILDER
            .comment("Localhost port for the development test HTTP server.")
            .defineInRange("devTestServerPort", 8790, 1024, 65535);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private McAiConfig() {
    }
}
