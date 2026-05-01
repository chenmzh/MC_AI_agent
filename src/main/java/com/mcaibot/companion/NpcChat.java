package com.mcaibot.companion;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NpcChat {
    private static final long DUPLICATE_SUPPRESS_MS = 12_000L;
    private static final Map<String, LastMessage> LAST_MESSAGES = new ConcurrentHashMap<>();

    private NpcChat() {
    }

    public static void say(ServerPlayer player, String message) {
        say(player, message, true);
    }

    public static void sayNow(ServerPlayer player, String message) {
        say(player, message, false);
    }

    public static void say(ServerPlayer player, String message, boolean suppressDuplicate) {
        if (player == null || message == null || message.isBlank()) {
            return;
        }

        String speaker = NpcManager.activeDisplayName(player.getServer());
        String text = message.trim();
        if (suppressDuplicate && isDuplicate(player, speaker, text)) {
            return;
        }
        player.sendSystemMessage(Component.literal("[" + speaker + "] " + text));
        SocialMemory.recordCompanionMessage(player, NpcManager.activeNpcMob(player.getServer()), text);
    }

    private static boolean isDuplicate(ServerPlayer player, String speaker, String message) {
        long now = System.currentTimeMillis();
        String key = player.getUUID() + ":" + speaker;
        LastMessage previous = LAST_MESSAGES.get(key);
        LAST_MESSAGES.put(key, new LastMessage(message, now));
        return previous != null
                && previous.message().equals(message)
                && now - previous.atMillis() <= DUPLICATE_SUPPRESS_MS;
    }

    private record LastMessage(String message, long atMillis) {
    }
}
