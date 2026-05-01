package com.mcaibot.companion;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod(McAiCompanion.MODID)
public final class McAiCompanion {
    public static final String MODID = "mc_ai_companion";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final String CHAT_MODE_OFF = "off";
    private static final String CHAT_MODE_MENTION = "mention";
    private static final String CHAT_MODE_ALL = "all";

    private final BridgeClient bridgeClient = new BridgeClient();
    private final NpcProfileStore profileStore = NpcProfileStore.createDefault();
    private final CompanionLoop companionLoop = new CompanionLoop();
    private final DevTestHttpServer devTestHttpServer = new DevTestHttpServer(this::askBridge);
    private final Map<UUID, Long> chatCooldowns = new ConcurrentHashMap<>();

    public McAiCompanion(IEventBus modEventBus, ModContainer modContainer) {
        ModEntities.register(modEventBus);
        modEventBus.addListener(this::onEntityAttributeCreation);
        modContainer.registerConfig(ModConfig.Type.COMMON, McAiConfig.SPEC);
        profileStore.load();
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("MC AI Companion loaded.");
    }

    private void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.AI_NPC.get(), AiNpcEntity.createAttributes().build());
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        if (!McAiConfig.ENABLE_CHAT_LISTENER.get()) {
            return;
        }

        String rawText = event.getRawText().trim();
        if (rawText.isBlank()) {
            return;
        }
        String mode = normalizedChatListenMode();
        if (CHAT_MODE_OFF.equals(mode)) {
            return;
        }

        String lower = rawText.toLowerCase(Locale.ROOT);
        String botName = McAiConfig.BOT_NAME.get().toLowerCase(Locale.ROOT);
        String prefix = McAiConfig.TRIGGER_PREFIX.get();

        boolean mentioned = lower.contains(botName) || mentionsEnabledPersona(lower);
        boolean prefixed = !prefix.isBlank() && rawText.startsWith(prefix);
        boolean directConversation = CHAT_MODE_ALL.equals(mode)
                && !prefixed
                && !mentioned
                && canUseDirectChat(event.getPlayer());
        if (!mentioned && !prefixed && !directConversation) {
            return;
        }
        if (!passesChatCooldown(event.getPlayer(), prefixed || mentioned)) {
            return;
        }

        if (!canControlActiveProfile(event.getPlayer())) {
            NpcChat.sayNow(event.getPlayer(), "This NPC profile is owned by another player.");
            return;
        }

        String message = prefixed ? rawText.substring(prefix.length()).trim() : stripAddressing(rawText);
        if (message.isBlank()) {
            return;
        }
        askBridge(event.getPlayer(), message);
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        NpcManager.onServerTick(event);
        PlanManager.onServerTick(event);
        ProtectionManager.onServerTick(event);
        companionLoop.onServerTick(event, bridgeClient, profileStore);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        devTestHttpServer.start(event.getServer());
        bridgeClient.postSkillsHandshake().whenComplete((ok, error) -> {
            if (error != null) {
                LOGGER.debug("Bridge skills handshake failed", error);
            } else if (!ok) {
                LOGGER.debug("Bridge skills handshake was not accepted.");
            }
        });
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        devTestHttpServer.stop();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("mcai")
                        .then(Commands.literal("help").executes(context -> {
                            sendHelp(context.getSource());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("status").executes(context -> {
                            sendStatus(context.getSource());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("ask")
                                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    askBridge(player, StringArgumentType.getString(context, "message"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(Commands.literal("scan").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            BridgeActions.reportNearby(player, McAiConfig.SCAN_RADIUS.get());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("inventory").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            BridgeActions.reportInventory(player);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(chatCommands())
                        .then(npcCommands())
        );
    }

    private LiteralArgumentBuilder<CommandSourceStack> chatCommands() {
        return Commands.literal("chat")
                .then(Commands.literal("status").executes(context -> {
                    sendChatStatus(context.getSource());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal(CHAT_MODE_OFF).executes(context -> setChatMode(context.getSource(), CHAT_MODE_OFF)))
                .then(Commands.literal(CHAT_MODE_MENTION).executes(context -> setChatMode(context.getSource(), CHAT_MODE_MENTION)))
                .then(Commands.literal(CHAT_MODE_ALL).executes(context -> setChatMode(context.getSource(), CHAT_MODE_ALL)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> npcCommands() {
        return Commands.literal("npc")
                .then(Commands.literal("spawn").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    NpcManager.spawnNear(player, profileStore.defaultProfile());
                    return Command.SINGLE_SUCCESS;
                })
                        .then(Commands.argument("profile", StringArgumentType.word()).executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            String selector = StringArgumentType.getString(context, "profile");
                            NpcProfile profile = profileStore.findEnabled(selector).orElseGet(profileStore::defaultProfile);
                            NpcManager.spawnNear(player, profile);
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("list").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    NpcManager.listNpcs(player);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(npcAllCommands())
                .then(Commands.literal("select")
                        .then(Commands.argument("npc", StringArgumentType.word()).executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            NpcManager.selectActive(player, StringArgumentType.getString(context, "npc"));
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("profiles").executes(context -> {
                    sendProfiles(context.getSource());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("reloadprofiles").executes(context -> {
                    profileStore.reload();
                    context.getSource().sendSuccess(() -> Component.literal("NPC profiles reloaded from " + profileStore.loadedPath()), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("rename")
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(context ->
                                updateActiveProfile(context.getSource(), "name", StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("personality")
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(context ->
                                updateActiveProfile(context.getSource(), "personality", StringArgumentType.getString(context, "text")))))
                .then(Commands.literal("style")
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(context ->
                                updateActiveProfile(context.getSource(), "style", StringArgumentType.getString(context, "text")))))
                .then(Commands.literal("skin")
                        .then(Commands.argument("skin", StringArgumentType.greedyString()).executes(context ->
                                updateActiveProfile(context.getSource(), "skin", StringArgumentType.getString(context, "skin")))))
                .then(Commands.literal("role")
                        .then(Commands.argument("role", StringArgumentType.greedyString()).executes(context ->
                                updateActiveProfile(context.getSource(), "role", StringArgumentType.getString(context, "role")))))
                .then(Commands.literal("profile")
                        .then(Commands.literal("create")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(context ->
                                                createProfile(context.getSource(), StringArgumentType.getString(context, "id"), StringArgumentType.getString(context, "name"))))))
                        .then(Commands.literal("enable")
                                .then(Commands.argument("profile", StringArgumentType.word()).executes(context ->
                                        setProfileEnabled(context.getSource(), StringArgumentType.getString(context, "profile"), true))))
                        .then(Commands.literal("disable")
                                .then(Commands.argument("profile", StringArgumentType.word()).executes(context ->
                                        setProfileEnabled(context.getSource(), StringArgumentType.getString(context, "profile"), false))))
                        .then(Commands.literal("name")
                                .then(Commands.argument("profile", StringArgumentType.word())
                                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(context ->
                                                updateProfile(context.getSource(), StringArgumentType.getString(context, "profile"), "name", StringArgumentType.getString(context, "name"))))))
                        .then(Commands.literal("personality")
                                .then(Commands.argument("profile", StringArgumentType.word())
                                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(context ->
                                                updateProfile(context.getSource(), StringArgumentType.getString(context, "profile"), "personality", StringArgumentType.getString(context, "text"))))))
                        .then(Commands.literal("style")
                                .then(Commands.argument("profile", StringArgumentType.word())
                                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(context ->
                                                updateProfile(context.getSource(), StringArgumentType.getString(context, "profile"), "style", StringArgumentType.getString(context, "text"))))))
                        .then(Commands.literal("skin")
                                .then(Commands.argument("profile", StringArgumentType.word())
                                        .then(Commands.argument("skin", StringArgumentType.greedyString()).executes(context ->
                                                updateProfile(context.getSource(), StringArgumentType.getString(context, "profile"), "skin", StringArgumentType.getString(context, "skin"))))))
                        .then(Commands.literal("role")
                                .then(Commands.argument("profile", StringArgumentType.word())
                                        .then(Commands.argument("role", StringArgumentType.greedyString()).executes(context ->
                                                updateProfile(context.getSource(), StringArgumentType.getString(context, "profile"), "role", StringArgumentType.getString(context, "role"))))))
                        .then(Commands.literal("owner")
                                .then(Commands.argument("profile", StringArgumentType.word())
                                        .then(Commands.argument("owner", StringArgumentType.greedyString()).executes(context ->
                                                updateProfile(context.getSource(), StringArgumentType.getString(context, "profile"), "owner", StringArgumentType.getString(context, "owner")))))))
                .then(Commands.literal("remove").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    NpcManager.removeWithMessage(player);
                    return Command.SINGLE_SUCCESS;
                })
                        .then(Commands.argument("npc", StringArgumentType.word()).executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            NpcManager.removeSelectedWithMessage(player, StringArgumentType.getString(context, "npc"));
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("come").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    NpcManager.comeTo(player);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("follow").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    NpcManager.follow(player);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("guard").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ProtectionManager.start(player);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("unguard").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ProtectionManager.stop(player);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("stop").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    NpcManager.stop(player);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("inventory").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    NpcManager.openInventory(player);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("equip").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    NpcManager.equipFromPlayerHand(player);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("gear").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    NpcManager.gearStatus(player);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("chest")
                        .then(Commands.literal("status").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            NpcManager.chestStatus(player);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("approve").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            NpcManager.approveChestMaterials(player);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("revoke").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            NpcManager.revokeChestMaterials(player);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("deposit").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            NpcManager.depositNpcStorageToNearbyChest(player);
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("repair")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            NpcManager.repairNearbyStructure(player);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("preview").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            NpcManager.previewNearbyStructureRepair(player);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("confirm").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            NpcManager.confirmRepairPreview(player);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("run").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            NpcManager.repairNearbyStructure(player);
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("collect").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    NpcManager.collectItems(player, McAiConfig.NPC_TASK_RADIUS.get());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("mine").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    NpcManager.mineOres(player, McAiConfig.NPC_TASK_RADIUS.get());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("wood").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    NpcManager.harvestLogs(player, McAiConfig.NPC_TASK_RADIUS.get());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("build").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    NpcManager.buildBasicHouse(player);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("status").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    NpcManager.status(player);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("goto")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg()).executes(context -> {
                                            ServerPlayer player = context.getSource().getPlayerOrException();
                                            NpcManager.goTo(
                                                    player,
                                                    DoubleArgumentType.getDouble(context, "x"),
                                                    DoubleArgumentType.getDouble(context, "y"),
                                                    DoubleArgumentType.getDouble(context, "z")
                                            );
                                            return Command.SINGLE_SUCCESS;
                                        })))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> npcAllCommands() {
        return Commands.literal("all")
                .then(Commands.literal("come").executes(context -> runNpcGroup(context.getSource(), "come")))
                .then(Commands.literal("follow").executes(context -> runNpcGroup(context.getSource(), "follow")))
                .then(Commands.literal("stop").executes(context -> runNpcGroup(context.getSource(), "stop")))
                .then(Commands.literal("status").executes(context -> runNpcGroup(context.getSource(), "status")))
                .then(Commands.literal("gear").executes(context -> runNpcGroup(context.getSource(), "gear")))
                .then(Commands.literal("collect").executes(context -> runNpcGroup(context.getSource(), "collect_items")))
                .then(Commands.literal("wood").executes(context -> runNpcGroup(context.getSource(), "harvest_logs")))
                .then(Commands.literal("stone").executes(context -> runNpcGroup(context.getSource(), "gather_stone")))
                .then(Commands.literal("mine").executes(context -> runNpcGroup(context.getSource(), "mine_nearby_ore")));
    }

    private int runNpcGroup(CommandSourceStack source, String action) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        NpcManager.group(player, action);
        return Command.SINGLE_SUCCESS;
    }

    private void sendStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "MC AI Companion: bridge=" + McAiConfig.BRIDGE_URL.get()
                        + ", botName=" + McAiConfig.BOT_NAME.get()
                        + ", chatListener=" + McAiConfig.ENABLE_CHAT_LISTENER.get()
                        + ", chatMode=" + normalizedChatListenMode()
                        + ", chatAllRadius=" + McAiConfig.CHAT_ALL_RADIUS.get()
                        + ", devTest=http://127.0.0.1:" + McAiConfig.DEV_TEST_SERVER_PORT.get()
                        + ", companionLoop=" + companionLoop.runtimeStateJson()
                        + ", profiles=" + profileStore.loadedPath()
        ), false);
    }

    private void sendChatStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "MC AI chat mode=" + normalizedChatListenMode()
                        + ", triggerPrefix='" + McAiConfig.TRIGGER_PREFIX.get() + "'"
                        + ", allRequiresNearbyNpc=" + McAiConfig.CHAT_ALL_REQUIRES_NPC_NEARBY.get()
                        + ", allRadius=" + McAiConfig.CHAT_ALL_RADIUS.get()
                        + ", cooldownMs=" + McAiConfig.CHAT_COOLDOWN_MS.get()
        ), false);
    }

    private int setChatMode(CommandSourceStack source, String mode) {
        McAiConfig.CHAT_LISTEN_MODE.set(mode);
        source.sendSuccess(() -> Component.literal(chatModeMessage(mode)), false);
        return Command.SINGLE_SUCCESS;
    }

    private String chatModeMessage(String mode) {
        return switch (mode) {
            case CHAT_MODE_OFF -> "Normal chat AI listener is off. /mcai ask still works.";
            case CHAT_MODE_ALL -> "Normal chat now talks to the selected NPC when a companion is within "
                    + McAiConfig.CHAT_ALL_RADIUS.get() + " blocks. Use /mcai chat mention to require the NPC name again.";
            default -> "Normal chat now requires an NPC mention or prefix. Say '" + McAiConfig.BOT_NAME.get()
                    + " <request>' or '" + McAiConfig.TRIGGER_PREFIX.get() + "<request>'.";
        };
    }

    private void sendHelp(CommandSourceStack source) {
        sendHelpLine(source, "MC AI Companion help:");
        sendHelpLine(source, "/mcai status - show bridge/profile config.");
        sendHelpLine(source, "/mcai ask <message> - ask the AI in English or Chinese. Example: /mcai ask 保护我");
        sendHelpLine(source, "/mcai chat status|mention|all|off - control normal chat input. all lets nearby plain chat talk to the selected NPC.");
        sendHelpLine(source, "/mcai scan | /mcai inventory - local scan and inventory summary.");
        sendHelpLine(source, "/mcai npc spawn [profile] - spawn an NPC. Use /mcai npc profiles to list profiles.");
        sendHelpLine(source, "/mcai npc list|select <npc>|remove [npc] - manage multiple spawned NPCs. Commands target the selected NPC.");
        sendHelpLine(source, "/mcai npc all come|follow|stop|status|gear|collect|wood|stone|mine - command all spawned NPCs safely.");
        sendHelpLine(source, "/mcai npc rename|personality|style|skin|role <text> - edit the selected NPC profile in-game.");
        sendHelpLine(source, "/mcai npc profile create|enable|disable|name|personality|style|skin|role|owner ... - edit named profiles.");
        sendHelpLine(source, "/mcai npc reloadprofiles - reload editable NPC names/personalities from " + profileStore.loadedPath());
        sendHelpLine(source, "/mcai npc come|follow|stop|status|goto <x> <y> <z> - movement and state.");
        sendHelpLine(source, "/mcai npc inventory|gear|equip - open storage, inspect auto-pickup/auto-equip, or equip your held item.");
        sendHelpLine(source, "/mcai npc chest status|approve|revoke|deposit - inspect/approve chest material use or deposit NPC storage.");
        sendHelpLine(source, "/mcai npc collect|mine|wood|stone|build - safe work tasks near you. Ask AI for repair_structure to patch a nearby house/wall/door.");
        sendHelpLine(source, "Material policy: build/craft never consumes your inventory; NPC gathers first, then uses approved nearby chests.");
        sendHelpLine(source, "AI primitive actions: inspect/break/place exact block, repair nearby structure, craft basic tools/planks/sticks/door, approve/withdraw/deposit nearby chest items, equip best gear.");
        sendHelpLine(source, "/mcai npc guard|unguard - protection is on by default; unguard opts out. Player entities are never attacked.");
        sendHelpLine(source, "Dev tests: GET http://127.0.0.1:" + McAiConfig.DEV_TEST_SERVER_PORT.get() + "/health, POST /test/chest.");
        sendHelpLine(source, "Chat triggers: say " + McAiConfig.BOT_NAME.get() + " <request>, " + McAiConfig.TRIGGER_PREFIX.get() + "<request>, or /mcai chat all for plain nearby chat.");
        sendHelpLine(source, "Voice input: any speech-to-text mod that sends normal chat works; local STT tools can POST /voice/transcript to the dev HTTP server.");
    }

    private void sendHelpLine(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private String normalizedChatListenMode() {
        String mode = McAiConfig.CHAT_LISTEN_MODE.get();
        if (mode == null) {
            return CHAT_MODE_MENTION;
        }
        return switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case CHAT_MODE_OFF -> CHAT_MODE_OFF;
            case CHAT_MODE_ALL -> CHAT_MODE_ALL;
            default -> CHAT_MODE_MENTION;
        };
    }

    private boolean canUseDirectChat(ServerPlayer player) {
        if (!McAiConfig.CHAT_ALL_REQUIRES_NPC_NEARBY.get()) {
            return true;
        }
        return NpcManager.hasNpcNear(player, McAiConfig.CHAT_ALL_RADIUS.get());
    }

    private boolean passesChatCooldown(ServerPlayer player, boolean explicitAddressing) {
        int cooldownMs = McAiConfig.CHAT_COOLDOWN_MS.get();
        if (cooldownMs <= 0 || player == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long previous = chatCooldowns.put(player.getUUID(), now);
        boolean passed = previous == null || now - previous >= cooldownMs;
        if (!passed && explicitAddressing) {
            NpcChat.sayNow(player, "Please wait a moment before sending another AI chat request.");
        }
        return passed;
    }

    private String stripAddressing(String text) {
        String trimmed = text == null ? "" : text.trim();
        for (NpcProfile profile : profileStore.enabledProfiles()) {
            trimmed = stripLeadingName(trimmed, profile.name());
            trimmed = stripLeadingName(trimmed, profile.id());
        }
        return stripLeadingName(trimmed, McAiConfig.BOT_NAME.get()).trim();
    }

    private String stripLeadingName(String text, String name) {
        if (text == null || name == null || name.isBlank()) {
            return text == null ? "" : text;
        }
        String trimmed = text.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        String lowerName = name.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith(lowerName)) {
            return trimmed;
        }
        String remainder = trimmed.substring(name.trim().length()).trim();
        while (!remainder.isEmpty() && ":,，。:：- ".indexOf(remainder.charAt(0)) >= 0) {
            remainder = remainder.substring(1).trim();
        }
        return remainder.isBlank() ? trimmed : remainder;
    }

    private boolean mentionsEnabledPersona(String lowerText) {
        for (NpcProfile profile : profileStore.enabledProfiles()) {
            if (matchesMention(lowerText, profile.id()) || matchesMention(lowerText, profile.name())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesMention(String lowerText, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        return lowerText.contains(candidate.toLowerCase(Locale.ROOT));
    }

    private boolean canControlActiveProfile(ServerPlayer player) {
        String profileId = NpcManager.activeProfileId(player.getServer());
        NpcProfile profile = profileStore.findEnabled(profileId).orElseGet(profileStore::defaultProfile);
        return profile.isOwnedBy(player.getUUID(), player.getGameProfile().getName());
    }

    private void sendProfiles(CommandSourceStack source) {
        StringBuilder builder = new StringBuilder("NPC profiles: ");
        int index = 0;
        for (NpcProfile profile : profileStore.profiles()) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(profile.id()).append("=").append(profile.name());
            if (!profile.enabled()) {
                builder.append("(disabled)");
            }
            index++;
        }
        if (index == 0) {
            builder.append("none");
        }
        builder.append(". Edit in-game with /mcai npc profile ..., or edit ").append(profileStore.loadedPath()).append(" then run /mcai npc reloadprofiles.");
        source.sendSuccess(() -> Component.literal(builder.toString()), false);
    }

    private int updateActiveProfile(CommandSourceStack source, String field, String value) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return updateProfile(source, NpcManager.activeProfileId(player.getServer()), field, value);
    }

    private int updateProfile(CommandSourceStack source, String selector, String field, String value) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        NpcProfile current = profileStore.find(selector).orElse(null);
        if (current == null) {
            source.sendFailure(Component.literal("No NPC profile matched '" + selector + "'. Use /mcai npc profiles."));
            return 0;
        }

        NpcProfile updated = updateProfileField(current, field, value);
        profileStore.upsert(updated);
        NpcManager.applyProfileToSpawned(player.getServer(), updated);
        source.sendSuccess(() -> Component.literal("Updated profile " + describeProfile(updated) + "."), false);
        return Command.SINGLE_SUCCESS;
    }

    private int createProfile(CommandSourceStack source, String id, String name) {
        String normalizedId = NpcProfile.sanitizeId(id, id);
        if (profileStore.find(normalizedId).isPresent()) {
            source.sendFailure(Component.literal("NPC profile '" + normalizedId + "' already exists."));
            return 0;
        }

        NpcProfile created = new NpcProfile(
                normalizedId,
                name,
                NpcProfile.DEFAULT_PERSONALITY,
                NpcProfile.DEFAULT_SKIN,
                NpcProfile.DEFAULT_STYLE,
                "",
                NpcProfile.DEFAULT_ROLE,
                true
        );
        profileStore.upsert(created);
        source.sendSuccess(() -> Component.literal("Created NPC profile " + describeProfile(created) + ". Spawn it with /mcai npc spawn " + created.id() + "."), false);
        return Command.SINGLE_SUCCESS;
    }

    private int setProfileEnabled(CommandSourceStack source, String selector, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        NpcProfile current = profileStore.find(selector).orElse(null);
        if (current == null) {
            source.sendFailure(Component.literal("No NPC profile matched '" + selector + "'."));
            return 0;
        }

        NpcProfile updated = current.withEnabled(enabled);
        profileStore.upsert(updated);
        NpcManager.applyProfileToSpawned(player.getServer(), updated);
        source.sendSuccess(() -> Component.literal((enabled ? "Enabled " : "Disabled ") + "NPC profile " + describeProfile(updated) + "."), false);
        return Command.SINGLE_SUCCESS;
    }

    private NpcProfile updateProfileField(NpcProfile profile, String field, String value) {
        String trimmed = value == null ? "" : value.trim();
        return switch (field) {
            case "name" -> profile.withName(trimmed);
            case "personality" -> profile.withPersonality(trimmed);
            case "style" -> profile.withStyle(trimmed);
            case "skin" -> profile.withSkin(trimmed);
            case "role" -> profile.withDefaultRole(trimmed);
            case "owner" -> profile.withOwner(trimmed);
            default -> profile;
        };
    }

    private String describeProfile(NpcProfile profile) {
        return profile.id() + "=" + profile.name()
                + " role=" + profile.defaultRole()
                + " skin=" + profile.skin()
                + (profile.enabled() ? "" : " disabled");
    }

    private void askBridge(ServerPlayer player, String message) {
        if (!canControlActiveProfile(player)) {
            NpcChat.sayNow(player, "This NPC profile is owned by another player.");
            return;
        }
        companionLoop.noteExplicitInteraction(player);
        SocialMemory.recordPlayerMessage(player, NpcManager.activeNpcMob(player.getServer()), message);
        NpcChat.sayNow(player, "Thinking...");
        BridgeContext context = BridgeContext.fromPlayer(player, message, profileStore);
        bridgeClient.decide(context).whenComplete((decision, error) -> {
            player.getServer().execute(() -> {
                if (error != null) {
                    LOGGER.warn("Bridge decision failed", error);
                    NpcChat.sayNow(player, "Bridge error: " + error.getMessage());
                    return;
                }

                BridgeActions.execute(player, decision, profileStore);
            });
        });
    }
}
