package com.mcaibot.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Local-only test harness for driving in-world assertions from the terminal.
 */
public final class DevTestHttpServer {
    private static final int SERVER_TASK_TIMEOUT_SECONDS = 30;
    private static final int MAX_REQUEST_HEADER_BYTES = 16384;
    private static final int MAX_REQUEST_BODY_BYTES = 32768;

    private final BiConsumer<ServerPlayer, String> interactionHandler;
    private volatile MinecraftServer minecraftServer;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread thread;

    public DevTestHttpServer(BiConsumer<ServerPlayer, String> interactionHandler) {
        this.interactionHandler = interactionHandler;
    }

    public synchronized void start(MinecraftServer server) {
        minecraftServer = server;
        if (!McAiConfig.DEV_TEST_SERVER_ENABLED.get()) {
            McAiCompanion.LOGGER.info("MC AI Companion dev test HTTP server disabled.");
            return;
        }
        if (running) {
            return;
        }

        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), McAiConfig.DEV_TEST_SERVER_PORT.get()));
            running = true;
            thread = new Thread(this::acceptLoop, "mc-ai-dev-test-http");
            thread.setDaemon(true);
            thread.start();
            McAiCompanion.LOGGER.info("MC AI Companion dev test HTTP server listening on http://127.0.0.1:{}/", McAiConfig.DEV_TEST_SERVER_PORT.get());
        } catch (IOException error) {
            running = false;
            closeSocket();
            McAiCompanion.LOGGER.warn("Failed to start MC AI Companion dev test HTTP server", error);
        }
    }

    public synchronized void stop() {
        running = false;
        minecraftServer = null;
        closeSocket();
        thread = null;
    }

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                handle(socket);
            } catch (SocketException error) {
                if (running) {
                    McAiCompanion.LOGGER.warn("Dev test HTTP socket error", error);
                }
            } catch (IOException error) {
                if (running) {
                    McAiCompanion.LOGGER.warn("Dev test HTTP accept error", error);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);
            Request request = readRequest(socket);
            JsonResponse response = route(request);
            writeJson(socket, response.status(), response.body());
        } catch (Exception error) {
            try {
                writeJson(socket, 500, error("INTERNAL_ERROR", error.getClass().getSimpleName() + ": " + error.getMessage()));
            } catch (IOException ignored) {
                // The caller disconnected or the socket is already closed.
            }
        }
    }

    private JsonResponse route(Request request) {
        URI uri = URI.create(request.target());
        String method = request.method().toUpperCase(Locale.ROOT);
        String path = uri.getPath();

        if ("GET".equals(method) && "/health".equals(path)) {
            return new JsonResponse(200, health());
        }
        if ("GET".equals(method) && "/state".equals(path)) {
            return new JsonResponse(200, onServer(uri, server -> state(server, uri)));
        }
        if ("GET".equals(method) && "/runtime".equals(path)) {
            return new JsonResponse(200, onServer(uri, server -> runtime(server, uri)));
        }
        if ("GET".equals(method) && "/observation".equals(path)) {
            return new JsonResponse(200, onServer(uri, server -> observation(server, uri)));
        }
        if ("GET".equals(method) && "/skills".equals(path)) {
            return new JsonResponse(200, skills());
        }
        if ("GET".equals(method) && "/blueprints".equals(path)) {
            return new JsonResponse(200, blueprints());
        }
        if ("GET".equals(method) && "/machines".equals(path)) {
            return new JsonResponse(200, onServer(uri, server -> machines(server, uri)));
        }
        if ("POST".equals(method) && "/test/runtime".equals(path)) {
            return new JsonResponse(200, onServer(uri, server -> runtimeContextTest(server, uri)));
        }
        if ("POST".equals(method) && "/test/agent_contracts".equals(path)) {
            return new JsonResponse(200, onServer(uri, server -> agentContractsTest(server, uri)));
        }
        if ("POST".equals(method) && "/test/taskgraph_next".equals(path)) {
            return new JsonResponse(200, onServer(uri, server -> taskGraphNext(server, uri)));
        }
        if ("POST".equals(method) && "/test/chest".equals(path)) {
            return new JsonResponse(200, onServer(uri, server -> chestTest(server, uri)));
        }
        if ("POST".equals(method) && "/taskgraph/next".equals(path)) {
            return new JsonResponse(200, onServer(uri, server -> taskGraphNext(server, uri)));
        }
        if ("POST".equals(method) && "/voice/transcript".equals(path)) {
            return new JsonResponse(200, onServer(uri, server -> voiceTranscript(server, uri, request.body())));
        }
        if ("POST".equals(method) && "/test/all".equals(path)) {
            return new JsonResponse(200, onServer(uri, server -> allTests(server, uri)));
        }
        if ("POST".equals(method) && path.startsWith("/action/")) {
            String action = path.substring("/action/".length());
            return new JsonResponse(200, onServer(uri, server -> runAction(server, uri, action)));
        }

        JsonObject notFound = error("NOT_FOUND", "Supported endpoints: GET /health, GET /state, GET /runtime, GET /observation, GET /skills, GET /blueprints, GET /machines, POST /voice/transcript, POST /test/runtime, POST /test/agent_contracts, POST /test/taskgraph_next, POST /test/chest, POST /test/all, POST /taskgraph/next, POST /action/<collect_items|harvest_logs|salvage_nearby_wood_structure|gather_stone|gather_materials|preview_structure|build_structure|cancel_structure|preview_machine|authorize_machine_plan|build_machine|test_machine|cancel_machine_build|survival_assist|till_field|plant_crop|harvest_crops|hunt_food_animal|feed_animal|breed_animals|tame_animal|build_redstone_template|build_basic_house|build_large_house|repair_structure|start_plan|taskgraph_next|continue_plan|report_plan|cancel_plan|inspect_block|break_block|place_block|craft_item|craft_at_table|craft_from_chest_at_table|withdraw_from_chest|deposit_item_to_chest|approve_chest_materials|revoke_chest_materials|equip_best_gear|stop|stop_guard|come|follow>.");
        return new JsonResponse(404, notFound);
    }

    private JsonObject health() {
        MinecraftServer server = minecraftServer;
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        json.addProperty("service", "mc_ai_companion_dev_test");
        json.addProperty("time", Instant.now().toString());
        json.addProperty("host", "127.0.0.1");
        json.addProperty("port", McAiConfig.DEV_TEST_SERVER_PORT.get());
        json.addProperty("serverAvailable", server != null);
        json.addProperty("playersOnline", server == null ? 0 : server.getPlayerList().getPlayerCount());
        JsonArray endpoints = new JsonArray();
        endpoints.add("GET /health");
        endpoints.add("GET /state?player=<name>");
        endpoints.add("GET /runtime?player=<name>");
        endpoints.add("GET /observation?player=<name>");
        endpoints.add("GET /skills");
        endpoints.add("GET /blueprints");
        endpoints.add("GET /machines?player=<name>");
        endpoints.add("POST /test/runtime?player=<name>");
        endpoints.add("POST /test/agent_contracts?player=<name>");
        endpoints.add("POST /test/taskgraph_next?player=<name>");
        endpoints.add("POST /test/chest?player=<name>");
        endpoints.add("POST /test/all?player=<name>");
        endpoints.add("POST /taskgraph/next?player=<name>");
        endpoints.add("POST /voice/transcript?player=<name>&text=<spoken text>");
        endpoints.add("POST /action/collect_items?player=<name>&radius=16");
        endpoints.add("POST /action/harvest_logs?player=<name>&radius=16&seconds=90");
        endpoints.add("POST /action/salvage_nearby_wood_structure?player=<name>&radius=16&count=12");
        endpoints.add("POST /action/gather_stone?player=<name>&radius=16&count=3");
        endpoints.add("POST /action/survival_assist?player=<name>");
        endpoints.add("POST /action/till_field?player=<name>&radius=8");
        endpoints.add("POST /action/plant_crop?player=<name>&crop=wheat&radius=8");
        endpoints.add("POST /action/harvest_crops?player=<name>&radius=12");
        endpoints.add("POST /action/hunt_food_animal?player=<name>&animal=cow&radius=16");
        endpoints.add("POST /action/feed_animal?player=<name>&animal=cow&radius=16");
        endpoints.add("POST /action/breed_animals?player=<name>&animal=cow&radius=16");
        endpoints.add("POST /action/tame_animal?player=<name>&animal=wolf&radius=16");
        endpoints.add("POST /action/build_redstone_template?player=<name>&template=pressure_door");
        endpoints.add("POST /action/gather_materials?player=<name>&material=logs|stone|sand|dirt|glass_like|placeable_blocks&count=64");
        endpoints.add("POST /action/preview_structure?player=<name>&template=starter_cabin_7x7|storage_shed_5x7|bridge_3w|watchtower_5x5|farm_fence_9x9|path_lights");
        endpoints.add("POST /action/build_structure?player=<name>&template=starter_cabin_7x7&autoGather=true");
        endpoints.add("POST /action/cancel_structure?player=<name>");
        endpoints.add("POST /action/preview_machine?player=<name>&machine=mob_drop_tower_v1|iron_farm_v1|villager_breeder_v1|trading_hall_v1");
        endpoints.add("POST /action/authorize_machine_plan?player=<name>&machine=mob_drop_tower_v1");
        endpoints.add("POST /action/build_machine?player=<name>&machine=mob_drop_tower_v1");
        endpoints.add("POST /action/test_machine?player=<name>&machine=mob_drop_tower_v1");
        endpoints.add("POST /action/cancel_machine_build?player=<name>");
        endpoints.add("POST /action/build_basic_house?player=<name>");
        endpoints.add("POST /action/build_large_house?player=<name>");
        endpoints.add("POST /action/repair_structure?player=<name>&radius=12");
        endpoints.add("POST /action/start_plan?player=<name>&goal=build_basic_shelter&run=true");
        endpoints.add("POST /action/taskgraph_next?player=<name>");
        endpoints.add("POST /action/continue_plan?player=<name>");
        endpoints.add("POST /action/report_plan?player=<name>");
        endpoints.add("POST /action/cancel_plan?player=<name>");
        endpoints.add("POST /action/inspect_block?player=<name>&x=<x>&y=<y>&z=<z>");
        endpoints.add("POST /action/break_block?player=<name>&x=<x>&y=<y>&z=<z>");
        endpoints.add("POST /action/place_block?player=<name>&x=<x>&y=<y>&z=<z>&block=oak_planks");
        endpoints.add("POST /action/craft_item?player=<name>&item=axe|pickaxe|planks|sticks|door&count=1");
        endpoints.add("POST /action/craft_at_table?player=<name>&item=axe|pickaxe|planks|sticks|door&count=1");
        endpoints.add("POST /action/craft_from_chest_at_table?player=<name>&item=planks&count=16");
        endpoints.add("POST /action/withdraw_from_chest?player=<name>&item=oak_log&count=16");
        endpoints.add("POST /action/deposit_item_to_chest?player=<name>&item=oak_log&count=16");
        endpoints.add("POST /action/approve_chest_materials?player=<name>");
        endpoints.add("POST /action/revoke_chest_materials?player=<name>");
        endpoints.add("POST /action/equip_best_gear?player=<name>");
        endpoints.add("POST /action/stop?player=<name>");
        endpoints.add("POST /action/stop_guard?player=<name>");
        json.add("endpoints", endpoints);
        return json;
    }

    private JsonObject voiceTranscript(MinecraftServer server, URI uri, String body) {
        ServerPlayer player = selectPlayer(server, queryParam(uri, "player"));
        if (player == null) {
            return error("PLAYER_NOT_FOUND", "No matching online player for voice transcript.");
        }

        String text = transcriptText(uri, body);
        if (text.isBlank()) {
            return error("EMPTY_TRANSCRIPT", "Provide transcript text with ?text=..., raw body text, form text=..., or JSON {\"text\":\"...\"}.");
        }
        if (text.length() > 1000) {
            text = text.substring(0, 1000);
        }

        interactionHandler.accept(player, text);

        JsonObject json = baseOk("voice_transcript");
        json.addProperty("accepted", true);
        json.addProperty("player", player.getGameProfile().getName());
        json.addProperty("text", text);
        json.addProperty("message", "Transcript submitted to the same AI bridge path as normal chat.");
        return json;
    }

    private JsonObject runtime(MinecraftServer server, URI uri) {
        JsonObject json = baseOk("runtime");
        json.addProperty("serverVersion", server.getServerVersion());
        json.addProperty("devTestPort", McAiConfig.DEV_TEST_SERVER_PORT.get());
        json.add("players", playersJson(server));

        ServerPlayer player = selectPlayer(server, queryParam(uri, "player"));
        if (player == null) {
            json.addProperty("ok", false);
            json.addProperty("error", "NO_PLAYER_ONLINE");
            return json;
        }

        JsonObject npc = NpcManager.describeFor(player);
        JsonObject bridge = BridgeContext.fromPlayer(player, "[DEV_TEST_RUNTIME]").payload();
        JsonObject context = bridge.has("context") && bridge.get("context").isJsonObject()
                ? bridge.getAsJsonObject("context")
                : new JsonObject();
        JsonObject capabilities = context.has("capabilities") && context.get("capabilities").isJsonObject()
                ? context.getAsJsonObject("capabilities")
                : new JsonObject();
        JsonArray taskControllerCatalog = capabilities.has("taskControllerCatalog") && capabilities.get("taskControllerCatalog").isJsonArray()
                ? capabilities.getAsJsonArray("taskControllerCatalog")
                : new JsonArray();

        json.addProperty("selectedPlayer", player.getGameProfile().getName());
        json.add("npc", npc);
        json.add("plan", PlanManager.snapshotFor(player));
        json.add("planFeedback", PlanManager.feedbackFor(player));
        json.add("taskFeedback", TaskFeedback.snapshotJson(player, npc));
        json.add("resources", ResourceAssessment.snapshotFor(player));
        json.add("survivalEnvironment", context.has("survivalEnvironment") ? context.get("survivalEnvironment") : SurvivalEnvironment.snapshotFor(player));
        json.add("capabilities", capabilities);
        json.add("taskControllerCatalog", taskControllerCatalog);
        json.add("worldKnowledge", context.has("worldKnowledge") ? context.get("worldKnowledge") : new JsonObject());
        json.add("social", context.has("social") ? context.get("social") : new JsonObject());
        json.add("companion", context.has("companion") ? context.get("companion") : new JsonObject());
        json.add("observationFrame", context.has("observationFrame") ? context.get("observationFrame") : new JsonObject());
        json.add("skillRegistry", context.has("skillRegistry") ? context.get("skillRegistry") : SkillRegistry.catalogJson());
        json.add("actionPrimitives", context.has("actionPrimitives") ? context.get("actionPrimitives") : SkillRegistry.actionPrimitivesJson());
        json.add("agentLoop", context.has("agentLoop") ? context.get("agentLoop") : AgentLoop.contractJson());
        return json;
    }

    private JsonObject observation(MinecraftServer server, URI uri) {
        JsonObject json = baseOk("observation");
        ServerPlayer player = selectPlayer(server, queryParam(uri, "player"));
        if (player == null) {
            json.addProperty("ok", false);
            json.addProperty("error", "NO_PLAYER_ONLINE");
            return json;
        }

        JsonObject bridge = BridgeContext.fromPlayer(player, "[DEV_TEST_OBSERVATION]").payload();
        JsonObject context = bridge.has("context") && bridge.get("context").isJsonObject()
                ? bridge.getAsJsonObject("context")
                : new JsonObject();
        json.addProperty("selectedPlayer", player.getGameProfile().getName());
        json.add("social", context.has("social") ? context.get("social") : new JsonObject());
        json.add("companion", context.has("companion") ? context.get("companion") : new JsonObject());
        json.add("survivalEnvironment", context.has("survivalEnvironment") ? context.get("survivalEnvironment") : SurvivalEnvironment.snapshotFor(player));
        json.add("observationFrame", context.has("observationFrame") ? context.get("observationFrame") : new JsonObject());
        return json;
    }

    private JsonObject skills() {
        JsonObject json = baseOk("skills");
        json.add("skillRegistry", SkillRegistry.catalogJson());
        json.add("actionPrimitives", SkillRegistry.actionPrimitivesJson());
        json.add("agentLoop", AgentLoop.contractJson());
        return json;
    }

    private JsonObject blueprints() {
        JsonObject json = baseOk("blueprints");
        json.add("catalog", BlueprintTemplateRegistry.catalogJson());
        return json;
    }

    private JsonObject machines(MinecraftServer server, URI uri) {
        JsonObject json = baseOk("machines");
        ServerPlayer player = selectPlayer(server, queryParam(uri, "player"));
        json.add("catalog", MachineBuildController.catalogJson(player));
        return json;
    }

    private JsonObject state(MinecraftServer server, URI uri) {
        JsonObject json = baseOk("state");
        json.addProperty("serverVersion", server.getServerVersion());
        json.addProperty("devTestPort", McAiConfig.DEV_TEST_SERVER_PORT.get());
        json.addProperty("bridgeUrl", McAiConfig.BRIDGE_URL.get());
        json.add("players", playersJson(server));

        ServerPlayer player = selectPlayer(server, queryParam(uri, "player"));
        if (player == null) {
            json.addProperty("ok", false);
            json.addProperty("error", "NO_PLAYER_ONLINE");
            return json;
        }

        json.addProperty("selectedPlayer", player.getGameProfile().getName());
        json.add("npc", NpcManager.describeFor(player));
        json.add("plan", PlanManager.snapshotFor(player));
        json.add("resources", ResourceAssessment.snapshotFor(player));
        JsonObject bridge = BridgeContext.fromPlayer(player, "[DEV_TEST_STATE]").payload();
        JsonObject context = bridge.has("context") && bridge.get("context").isJsonObject()
                ? bridge.getAsJsonObject("context")
                : new JsonObject();
        json.add("social", context.has("social") ? context.get("social") : new JsonObject());
        json.add("companion", context.has("companion") ? context.get("companion") : new JsonObject());
        return json;
    }

    private JsonObject chestTest(MinecraftServer server, URI uri) {
        ServerPlayer player = selectPlayer(server, queryParam(uri, "player"));
        if (player == null) {
            return error("NO_PLAYER_ONLINE", "Open the world/server with at least one player before running in-world tests.");
        }
        return NpcManager.runChestSelfTest(player);
    }

    private JsonObject taskGraphNext(MinecraftServer server, URI uri) {
        ServerPlayer player = selectPlayer(server, queryParam(uri, "player"));
        if (player == null) {
            return error("NO_PLAYER_ONLINE", "Open the world/server with at least one player before running TaskGraph tests.");
        }
        return PlanManager.executeNextTaskGraphNode(player);
    }

    private JsonObject runtimeContextTest(MinecraftServer server, URI uri) {
        ServerPlayer player = selectPlayer(server, queryParam(uri, "player"));
        if (player == null) {
            return error("NO_PLAYER_ONLINE", "Open the world/server with at least one player before running in-world tests.");
        }

        JsonObject json = baseOk("runtime_context");
        JsonArray failures = new JsonArray();

        JsonObject npc = NpcManager.describeFor(player);
        json.add("npc", npc);
        if (!npc.has("all") || !npc.get("all").isJsonArray()) {
            failures.add("npc.all must be an array");
        } else {
            JsonArray all = npc.getAsJsonArray("all");
            json.addProperty("npcCount", all.size());
            for (int index = 0; index < all.size(); index++) {
                if (!all.get(index).isJsonObject()) {
                    failures.add("npc.all[" + index + "] must be an object");
                    continue;
                }
                JsonObject item = all.get(index).getAsJsonObject();
                requireProperty(item, "npc.all[" + index + "]", "uuid", failures);
                requireProperty(item, "npc.all[" + index + "]", "name", failures);
                requireProperty(item, "npc.all[" + index + "]", "profileId", failures);
                requireObject(item, "npc.all[" + index + "]", "inventory", failures);
                requireObject(item, "npc.all[" + index + "]", "equipment", failures);
                requireObject(item, "npc.all[" + index + "]", "task", failures);
                if (item.has("task") && item.get("task").isJsonObject()) {
                    requireProperty(item.getAsJsonObject("task"), "npc.all[" + index + "].task", "status", failures);
                }
                requireObject(item, "npc.all[" + index + "]", "follow", failures);
                requireObject(item, "npc.all[" + index + "]", "activeInfo", failures);
            }
        }

        JsonObject bridge = BridgeContext.fromPlayer(player, "[DEV_TEST_RUNTIME_CONTEXT]").payload();
        JsonObject context = bridge.has("context") && bridge.get("context").isJsonObject()
                ? bridge.getAsJsonObject("context")
                : new JsonObject();
        JsonObject capabilities = context.has("capabilities") && context.get("capabilities").isJsonObject()
                ? context.getAsJsonObject("capabilities")
                : new JsonObject();
        json.add("capabilities", capabilities);
        requireBooleanCapability(capabilities, "perNpcRuntimeSnapshots", true, failures);
        requireBooleanCapability(capabilities, "agentContracts", true, failures);
        requireBooleanCapability(capabilities, "observationFrame", true, failures);
        requireBooleanCapability(capabilities, "goalSpec", true, failures);
        requireBooleanCapability(capabilities, "actionCall", true, failures);
        requireBooleanCapability(capabilities, "actionResult", true, failures);
        requireBooleanCapability(capabilities, "skillSpecRegistry", true, failures);
        requireBooleanCapability(capabilities, "taskGraphPlanning", true, failures);
        requireBooleanCapability(capabilities, "structuredClarificationRecovery", true, failures);
        requireBooleanCapability(capabilities, "worldKnowledgeShortTermMemory", true, failures);
        requireBooleanCapability(capabilities, "worldKnowledgeLongTermMap", true, failures);
        requireBooleanCapability(capabilities, "socialMemory", true, failures);
        requireBooleanCapability(capabilities, "relationshipState", true, failures);
        requireBooleanCapability(capabilities, "relationshipContext", true, failures);
        requireBooleanCapability(capabilities, "relationshipPreferences", true, failures);
        requireBooleanCapability(capabilities, "socialEvents", true, failures);
        requireBooleanCapability(capabilities, "companionLoop", true, failures);
        requireBooleanCapability(capabilities, "proactiveCompanionTriggers", true, failures);
        requireBooleanCapability(capabilities, "boundedAutonomousResourceSearch", true, failures);
        requireBooleanCapability(capabilities, "survivalEnvironment", true, failures);
        requireBooleanCapability(capabilities, "highAutonomySafetyPolicy", true, failures);
        requireBooleanCapability(capabilities, "farmingActions", true, failures);
        requireBooleanCapability(capabilities, "animalCareActions", true, failures);
        requireBooleanCapability(capabilities, "safeHuntingActions", true, failures);
        requireBooleanCapability(capabilities, "redstoneTemplateActions", true, failures);
        requireBooleanCapability(capabilities, "materialGatheringAction", true, failures);
        requireBooleanCapability(capabilities, "structureBlueprintTemplates", true, failures);
        requireBooleanCapability(capabilities, "structurePreviewAction", true, failures);
        requireBooleanCapability(capabilities, "structureBuildAction", true, failures);
        requireBooleanCapability(capabilities, "machineBlueprintTemplates", true, failures);
        requireBooleanCapability(capabilities, "machinePreviewAction", true, failures);
        requireBooleanCapability(capabilities, "machineBuildAction", true, failures);
        requireBooleanCapability(capabilities, "machinePlanAuthorization", true, failures);
        requireBooleanCapability(capabilities, "createMachineBuilds", false, failures);
        requireBooleanCapability(capabilities, "travelController", true, failures);
        requireBooleanCapability(capabilities, "litematicaBlueprintProvider", false, failures);
        requireBooleanCapability(capabilities, "parallelNpcWork", false, failures);
        requireBooleanCapability(capabilities, "taskControllerRuntime", false, failures);
        requireBooleanCapability(capabilities, "collectItemsControllerRuntime", true, failures);
        JsonArray taskControllerCatalog = capabilities.has("taskControllerCatalog") && capabilities.get("taskControllerCatalog").isJsonArray()
                ? capabilities.getAsJsonArray("taskControllerCatalog")
                : new JsonArray();
        json.add("taskControllerCatalog", taskControllerCatalog);
        requireTaskControllerParallelSafe(taskControllerCatalog, "collect_items", true, failures);
        requireTaskControllerParallelSafe(taskControllerCatalog, "salvage_nearby_wood_structure", false, failures);
        requireTaskControllerParallelSafe(taskControllerCatalog, "build_basic_house", false, failures);
        requireTaskControllerParallelSafe(taskControllerCatalog, "build_large_house", false, failures);
        requireTaskControllerParallelSafe(taskControllerCatalog, "repair_structure", false, failures);
        requireTaskControllerParallelSafe(taskControllerCatalog, "preview_structure", true, failures);
        requireTaskControllerParallelSafe(taskControllerCatalog, "build_structure", false, failures);
        requireTaskControllerParallelSafe(taskControllerCatalog, "gather_materials", false, failures);
        requireTaskControllerParallelSafe(taskControllerCatalog, "preview_machine", true, failures);
        requireTaskControllerParallelSafe(taskControllerCatalog, "authorize_machine_plan", true, failures);
        requireTaskControllerParallelSafe(taskControllerCatalog, "build_machine", false, failures);
        requireTaskControllerParallelSafe(taskControllerCatalog, "test_machine", true, failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "collect_items", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "salvage_nearby_wood_structure", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "gather_materials", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "preview_structure", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "build_structure", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "preview_machine", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "authorize_machine_plan", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "build_machine", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "test_machine", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "gather_stone", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "build_basic_house", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "repair_structure", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "craft_at_table", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "survival_assist", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "till_field", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "harvest_crops", failures);
        requireTaskControllerPlannerContract(taskControllerCatalog, "build_redstone_template", failures);
        JsonObject worldKnowledge = context.has("worldKnowledge") && context.get("worldKnowledge").isJsonObject()
                ? context.getAsJsonObject("worldKnowledge")
                : new JsonObject();
        requireObject(worldKnowledge, "context.worldKnowledge", "currentObservation", failures);
        requireObject(worldKnowledge, "context.worldKnowledge", "shortTermMemory", failures);
        requireObject(worldKnowledge, "context.worldKnowledge", "longTermMap", failures);
        requireObject(context, "context", "observationFrame", failures);
        requireObject(context, "context", "social", failures);
        requireObject(context, "context", "relationship", failures);
        requireObject(context, "context", "companion", failures);
        requireObject(context, "context", "companionLoop", failures);
        requireObject(context, "context", "survivalEnvironment", failures);
        requireObject(context, "context", "structureBlueprints", failures);
        requireObject(context, "context", "travelPolicy", failures);
        requireObject(context, "context", "skillRegistry", failures);
        requireJsonArray(context, "context", "actionPrimitives", failures);
        requireObject(context, "context", "agentLoop", failures);

        json.add("failures", failures);
        json.addProperty("ok", failures.isEmpty());
        return json;
    }

    private JsonObject agentContractsTest(MinecraftServer server, URI uri) {
        ServerPlayer player = selectPlayer(server, queryParam(uri, "player"));
        if (player == null) {
            return error("NO_PLAYER_ONLINE", "Open the world/server with at least one player before running agent contract tests.");
        }

        JsonObject json = baseOk("agent_contracts");
        JsonArray failures = new JsonArray();
        JsonObject bridge = BridgeContext.fromPlayer(player, "[DEV_TEST_AGENT_CONTRACTS]").payload();
        JsonObject context = bridge.has("context") && bridge.get("context").isJsonObject()
                ? bridge.getAsJsonObject("context")
                : new JsonObject();
        JsonObject observation = context.has("observationFrame") && context.get("observationFrame").isJsonObject()
                ? context.getAsJsonObject("observationFrame")
                : new JsonObject();
        JsonObject skills = context.has("skillRegistry") && context.get("skillRegistry").isJsonObject()
                ? context.getAsJsonObject("skillRegistry")
                : new JsonObject();
        JsonArray primitives = context.has("actionPrimitives") && context.get("actionPrimitives").isJsonArray()
                ? context.getAsJsonArray("actionPrimitives")
                : new JsonArray();

        json.add("observationFrame", observation);
        json.add("skillRegistry", skills);
        json.add("actionPrimitives", primitives);
        JsonObject actionResult = ActionResult.success("CONTRACT_OK", "ActionResult contract sample.").toJson();
        json.add("actionResultContract", actionResult);
        TaskFeedback.recordActionResult(player, NpcManager.activeNpcMob(server),
                "dev_test:action_result_contract",
                ActionResult.blocked("DEV_BLOCKED_SAMPLE",
                        "Dev contract sample blocker.",
                        "Use this sample to verify structured ActionResult feedback."));
        JsonObject taskFeedback = TaskFeedback.snapshotJson(player, NpcManager.describeFor(player));
        json.add("taskFeedbackAfterActionResult", taskFeedback);
        requireProperty(observation, "observationFrame", "schemaVersion", failures);
        requireObject(observation, "observationFrame", "actor", failures);
        requireObject(observation, "observationFrame", "perception", failures);
        requireObject(observation, "observationFrame", "memory", failures);
        requireObject(observation, "observationFrame", "resources", failures);
        requireObject(observation, "observationFrame", "feedback", failures);
        requireObject(observation, "observationFrame", "policies", failures);
        requireObject(observation, "observationFrame", "social", failures);
        requireObject(observation, "observationFrame", "relationship", failures);
        requireObject(observation, "observationFrame", "companion", failures);
        requireObject(observation, "observationFrame", "companionLoop", failures);
        requireJsonArray(observation, "observationFrame", "availableActions", failures);
        requireObject(observation, "observationFrame", "skillRegistry", failures);
        JsonObject perception = observation.has("perception") && observation.get("perception").isJsonObject()
                ? observation.getAsJsonObject("perception")
                : new JsonObject();
        requireObject(perception, "observationFrame.perception", "objects", failures);
        requireObject(perception, "observationFrame.perception", "survivalEnvironment", failures);
        JsonObject objects = perception.has("objects") && perception.get("objects").isJsonObject()
                ? perception.getAsJsonObject("objects")
                : new JsonObject();
        requireJsonArray(objects, "observationFrame.perception.objects", "structures", failures);
        requireJsonArray(objects, "observationFrame.perception.objects", "doors", failures);
        requireJsonArray(objects, "observationFrame.perception.objects", "walls", failures);
        requireJsonArray(objects, "observationFrame.perception.objects", "workstations", failures);
        requireJsonArray(objects, "observationFrame.perception.objects", "containers", failures);
        requireJsonArray(objects, "observationFrame.perception.objects", "resourceClusters", failures);
        requireJsonArray(objects, "observationFrame.perception.objects", "hazards", failures);
        JsonObject feedback = observation.has("feedback") && observation.get("feedback").isJsonObject()
                ? observation.getAsJsonObject("feedback")
                : new JsonObject();
        JsonObject resources = observation.has("resources") && observation.get("resources").isJsonObject()
                ? observation.getAsJsonObject("resources")
                : new JsonObject();
        requireObject(resources, "observationFrame.resources", "structureBlueprints", failures);
        requireObject(resources, "observationFrame.resources", "machineTemplates", failures);
        requireObject(resources, "observationFrame.resources", "travelPolicy", failures);
        requireJsonArray(feedback, "observationFrame.feedback", "capabilityGaps", failures);
        requireProperty(skills, "skillRegistry", "schemaVersion", failures);
        requireJsonArray(skills, "skillRegistry", "skills", failures);
        requirePrimitive(primitives, "prepare_basic_tools", failures);
        requirePrimitive(primitives, "report_resources", failures);
        requirePrimitive(primitives, "survival_assist", failures);
        requirePrimitive(primitives, "till_field", failures);
        requirePrimitive(primitives, "plant_crop", failures);
        requirePrimitive(primitives, "harvest_crops", failures);
        requirePrimitive(primitives, "hunt_food_animal", failures);
        requirePrimitive(primitives, "feed_animal", failures);
        requirePrimitive(primitives, "breed_animals", failures);
        requirePrimitive(primitives, "tame_animal", failures);
        requirePrimitive(primitives, "build_redstone_template", failures);
        requirePrimitive(primitives, "gather_materials", failures);
        requirePrimitive(primitives, "preview_structure", failures);
        requirePrimitive(primitives, "build_structure", failures);
        requirePrimitive(primitives, "cancel_structure", failures);
        requirePrimitive(primitives, "preview_machine", failures);
        requirePrimitive(primitives, "authorize_machine_plan", failures);
        requirePrimitive(primitives, "build_machine", failures);
        requirePrimitive(primitives, "test_machine", failures);
        requirePrimitive(primitives, "cancel_machine_build", failures);
        requirePrimitive(primitives, "salvage_nearby_wood_structure", failures);
        requirePrimitive(primitives, "break_block", failures);
        requirePrimitive(primitives, "place_block", failures);
        requirePrimitive(primitives, "gather_stone", failures);
        requirePrimitive(primitives, "craft_item", failures);
        requirePrimitive(primitives, "build_basic_house", failures);
        requirePrimitive(primitives, "build_large_house", failures);
        requirePrimitive(primitives, "repair_structure", failures);
        requirePrimitive(primitives, "guard_player", failures);
        requireActionResultContract(actionResult, failures);
        requireLatestTaskFeedbackActionResult(taskFeedback, failures);

        json.add("failures", failures);
        json.addProperty("ok", failures.isEmpty());
        return json;
    }

    private JsonObject allTests(MinecraftServer server, URI uri) {
        JsonObject json = baseOk("all_tests");
        ServerPlayer player = selectPlayer(server, queryParam(uri, "player"));
        if (player == null) {
            json.addProperty("ok", false);
            json.addProperty("error", "NO_PLAYER_ONLINE");
            return json;
        }

        JsonArray tests = new JsonArray();
        JsonObject runtime = runtimeContextTest(server, uri);
        tests.add(runtime);
        JsonObject contracts = agentContractsTest(server, uri);
        tests.add(contracts);
        JsonObject chest = NpcManager.runChestSelfTest(player);
        tests.add(chest);
        json.add("tests", tests);
        json.addProperty("ok", runtime.has("ok") && runtime.get("ok").getAsBoolean()
                && contracts.has("ok") && contracts.get("ok").getAsBoolean()
                && chest.has("ok") && chest.get("ok").getAsBoolean());
        return json;
    }

    private JsonObject runAction(MinecraftServer server, URI uri, String action) {
        ServerPlayer player = selectPlayer(server, queryParam(uri, "player"));
        if (player == null) {
            return error("NO_PLAYER_ONLINE", "Open the world/server with at least one player before running actions.");
        }

        JsonObject json = baseOk("action");
        json.addProperty("action", action);
        json.addProperty("player", player.getGameProfile().getName());

        switch (action) {
            case "collect_items" -> {
                int radius = intQueryParam(uri, "radius", McAiConfig.NPC_TASK_RADIUS.get(), 4, 32);
                NpcManager.collectItems(player, radius);
                json.addProperty("started", true);
                json.addProperty("radius", radius);
                json.addProperty("note", "Started controller-backed dropped item collection. Poll /state until npc.task returns to idle.");
            }
            case "harvest_logs" -> {
                int radius = intQueryParam(uri, "radius", McAiConfig.NPC_TASK_RADIUS.get(), 4, 32);
                int seconds = intQueryParam(uri, "seconds", 90, 20, 300);
                NpcManager.harvestLogs(player, radius, seconds);
                json.addProperty("started", true);
                json.addProperty("radius", radius);
                json.addProperty("seconds", seconds);
                json.addProperty("note", "Started log harvesting. Poll /state until npc.task returns to idle before starting build_basic_house or build_large_house.");
            }
            case "salvage_nearby_wood_structure" -> {
                int radius = intQueryParam(uri, "radius", McAiConfig.NPC_TASK_RADIUS.get(), 4, 32);
                int count = intQueryParam(uri, "count", McAiConfig.NPC_MAX_TASK_STEPS.get(), 1, McAiConfig.NPC_MAX_TASK_STEPS.get());
                NpcManager.salvageNearbyWoodStructure(player, radius, count);
                json.addProperty("started", true);
                json.addProperty("radius", radius);
                json.addProperty("count", count);
                json.addProperty("note", "Started nearby wooden structure salvage from the player's current position. Poll /state until npc.task returns to idle.");
            }
            case "gather_stone" -> {
                int radius = intQueryParam(uri, "radius", McAiConfig.NPC_TASK_RADIUS.get(), 4, 32);
                int count = intQueryParam(uri, "count", 3, 1, McAiConfig.NPC_MAX_TASK_STEPS.get());
                NpcManager.gatherStone(player, radius, count);
                json.addProperty("started", true);
                json.addProperty("radius", radius);
                json.addProperty("count", count);
                json.addProperty("note", "Started stone gathering. Poll /state until npc.task returns to idle, then run collect_items before crafting stone tools.");
            }
            case "survival_assist" -> addActionResult(json, SurvivalActions.survivalAssist(player));
            case "till_field" -> {
                int radius = intQueryParam(uri, "radius", 8, 4, 24);
                addActionResult(json, SurvivalActions.tillField(player, radius));
                json.addProperty("radius", radius);
            }
            case "plant_crop" -> {
                int radius = intQueryParam(uri, "radius", 8, 4, 24);
                String crop = firstNonBlank(firstNonBlank(queryParam(uri, "crop"), queryParam(uri, "item")), "wheat");
                addActionResult(json, SurvivalActions.plantCrop(player, crop, radius));
                json.addProperty("radius", radius);
                json.addProperty("crop", crop);
            }
            case "harvest_crops" -> {
                int radius = intQueryParam(uri, "radius", 12, 4, 24);
                addActionResult(json, SurvivalActions.harvestCrops(player, radius));
                json.addProperty("radius", radius);
            }
            case "hunt_food_animal" -> {
                int radius = intQueryParam(uri, "radius", 16, 4, 24);
                String animal = firstNonBlank(queryParam(uri, "animal"), "animal");
                addActionResult(json, SurvivalActions.huntFoodAnimal(player, animal, radius));
                json.addProperty("radius", radius);
                json.addProperty("animal", animal);
            }
            case "feed_animal" -> {
                int radius = intQueryParam(uri, "radius", 16, 4, 24);
                String animal = firstNonBlank(queryParam(uri, "animal"), "animal");
                addActionResult(json, SurvivalActions.feedAnimal(player, animal, radius));
                json.addProperty("radius", radius);
                json.addProperty("animal", animal);
            }
            case "breed_animals" -> {
                int radius = intQueryParam(uri, "radius", 16, 4, 24);
                String animal = firstNonBlank(queryParam(uri, "animal"), "animal");
                addActionResult(json, SurvivalActions.breedAnimals(player, animal, radius));
                json.addProperty("radius", radius);
                json.addProperty("animal", animal);
            }
            case "tame_animal" -> {
                int radius = intQueryParam(uri, "radius", 16, 4, 24);
                String animal = firstNonBlank(queryParam(uri, "animal"), "animal");
                addActionResult(json, SurvivalActions.tameAnimal(player, animal, radius));
                json.addProperty("radius", radius);
                json.addProperty("animal", animal);
            }
            case "build_redstone_template" -> {
                String template = firstNonBlank(queryParam(uri, "template"), "pressure_door");
                addActionResult(json, SurvivalActions.buildRedstoneTemplate(player, template));
                json.addProperty("template", template);
            }
            case "gather_materials" -> {
                String material = firstNonBlank(queryParam(uri, "material"), queryParam(uri, "category"), "placeable_blocks");
                int count = intQueryParam(uri, "count", 64, 1, 2304);
                addActionResult(json, MaterialGatherer.gatherMaterials(player, material, count));
                json.addProperty("material", material);
                json.addProperty("count", count);
            }
            case "preview_structure" -> {
                String template = firstNonBlank(queryParam(uri, "template"), queryParam(uri, "blueprint"), "starter_cabin_7x7");
                String style = firstNonBlank(queryParam(uri, "style"), "rustic");
                addActionResult(json, StructureBuildController.previewStructure(player, template, optionalQueryBlockPos(uri), queryDirection(uri, player.getDirection()), style));
                json.addProperty("template", template);
                json.addProperty("style", style);
            }
            case "build_structure" -> {
                String template = firstNonBlank(queryParam(uri, "template"), queryParam(uri, "blueprint"), "starter_cabin_7x7");
                String style = firstNonBlank(queryParam(uri, "style"), "rustic");
                boolean autoGather = booleanQueryParam(uri, "autoGather", true);
                addActionResult(json, StructureBuildController.buildStructure(player, template, optionalQueryBlockPos(uri), queryDirection(uri, player.getDirection()), style, autoGather));
                json.addProperty("template", template);
                json.addProperty("style", style);
                json.addProperty("autoGather", autoGather);
            }
            case "cancel_structure" -> addActionResult(json, StructureBuildController.cancelStructure(player));
            case "preview_machine" -> {
                String machine = firstNonBlank(queryParam(uri, "machine"), queryParam(uri, "template"), "mob_drop_tower_v1");
                addActionResult(json, MachineBuildController.previewMachine(player, machine, optionalQueryBlockPos(uri), queryDirection(uri, player.getDirection())));
                json.addProperty("machine", machine);
            }
            case "authorize_machine_plan" -> {
                String machine = firstNonBlank(queryParam(uri, "machine"), queryParam(uri, "template"), "mob_drop_tower_v1");
                addActionResult(json, MachineBuildController.authorizeMachinePlan(player, machine, optionalQueryBlockPos(uri), queryDirection(uri, player.getDirection())));
                json.addProperty("machine", machine);
            }
            case "build_machine" -> {
                String machine = firstNonBlank(queryParam(uri, "machine"), queryParam(uri, "template"), "mob_drop_tower_v1");
                addActionResult(json, MachineBuildController.buildMachine(player, machine, optionalQueryBlockPos(uri), queryDirection(uri, player.getDirection())));
                json.addProperty("machine", machine);
            }
            case "test_machine" -> {
                String machine = firstNonBlank(queryParam(uri, "machine"), queryParam(uri, "template"), "mob_drop_tower_v1");
                addActionResult(json, MachineBuildController.testMachine(player, machine, optionalQueryBlockPos(uri), queryDirection(uri, player.getDirection())));
                json.addProperty("machine", machine);
            }
            case "cancel_machine_build" -> addActionResult(json, MachineBuildController.cancelMachineBuild(player));
            case "build_basic_house" -> {
                addActionResult(json, StructureBuildController.buildStructure(player, "starter_cabin_7x7", optionalQueryBlockPos(uri), queryDirection(uri, player.getDirection()), "rustic", false));
                json.addProperty("note", "Compatibility action routed through starter_cabin_7x7 blueprint.");
            }
            case "build_large_house" -> {
                addActionResult(json, StructureBuildController.buildStructure(player, "starter_cabin_7x7", optionalQueryBlockPos(uri), queryDirection(uri, player.getDirection()), "rustic", true));
                json.addProperty("note", "Compatibility action routed through starter_cabin_7x7 blueprint with autoGather.");
            }
            case "repair_structure", "repair_house", "repair_wall", "repair_door" -> {
                int radius = intQueryParam(uri, "radius", 12, 4, McAiConfig.NPC_TASK_RADIUS.get());
                boolean started = NpcManager.repairNearbyStructure(player, radius);
                json.addProperty("started", started);
                json.addProperty("radius", radius);
                json.addProperty("note", "Scans the nearby building shell, infers wall material, and repairs missing wall/door blocks if the shell is clear.");
            }
            case "start_plan" -> {
                String goal = firstNonBlank(queryParam(uri, "goal"), "build_basic_shelter");
                boolean runFirstStage = booleanQueryParam(uri, "run", true);
                PlanManager.startPlan(player, devPlanDecision(player, "start_plan", goal), runFirstStage);
                json.addProperty("started", true);
                json.addProperty("goal", goal);
                json.addProperty("runFirstStage", runFirstStage);
            }
            case "taskgraph_next", "execute_next_taskgraph_node", "run_taskgraph_next" -> {
                json.add("taskGraphExecution", PlanManager.executeNextTaskGraphNode(player));
                json.addProperty("started", true);
            }
            case "continue_plan" -> {
                PlanManager.continuePlan(player);
                json.addProperty("started", true);
            }
            case "report_plan" -> {
                PlanManager.reportPlan(player);
                json.addProperty("started", true);
            }
            case "cancel_plan" -> {
                PlanManager.cancelPlan(player);
                json.addProperty("started", true);
            }
            case "inspect_block" -> {
                BlockPos pos = queryBlockPos(uri, player.blockPosition());
                NpcManager.inspectBlock(player, pos);
                json.addProperty("started", true);
                json.add("target", blockPosJson(pos));
            }
            case "break_block" -> {
                BlockPos pos = queryBlockPos(uri, player.blockPosition());
                NpcManager.breakBlockAt(player, pos);
                json.addProperty("started", true);
                json.add("target", blockPosJson(pos));
            }
            case "place_block" -> {
                BlockPos pos = queryBlockPos(uri, player.blockPosition());
                String block = queryParam(uri, "block");
                NpcManager.placeBlockAt(player, pos, block);
                json.addProperty("started", true);
                json.add("target", blockPosJson(pos));
                json.addProperty("block", block);
            }
            case "craft_item" -> {
                String item = queryParam(uri, "item");
                int count = intQueryParam(uri, "count", 1, 1, 2304);
                NpcManager.craftItem(player, item, count);
                json.addProperty("started", true);
                json.addProperty("item", item);
                json.addProperty("count", count);
            }
            case "craft_at_table", "craft_from_chest_at_table" -> {
                String item = queryParam(uri, "item");
                int count = intQueryParam(uri, "count", 1, 1, 2304);
                boolean allowChest = "craft_from_chest_at_table".equals(action);
                NpcManager.craftAtNearbyTable(player, item, count, allowChest);
                json.addProperty("started", true);
                json.addProperty("item", item);
                json.addProperty("count", count);
                json.addProperty("allowExplicitContainerMaterials", allowChest);
            }
            case "withdraw_from_chest" -> {
                String item = queryParam(uri, "item");
                int count = intQueryParam(uri, "count", 64, 1, 2304);
                NpcManager.withdrawFromNearbyChest(player, item, count);
                json.addProperty("started", true);
                json.addProperty("item", item);
                json.addProperty("count", count);
            }
            case "deposit_item_to_chest" -> {
                String item = queryParam(uri, "item");
                int count = intQueryParam(uri, "count", 2304, 1, 2304);
                NpcManager.depositItemToNearbyChest(player, item, count);
                json.addProperty("started", true);
                json.addProperty("item", item);
                json.addProperty("count", count);
            }
            case "approve_chest_materials" -> {
                NpcManager.approveChestMaterials(player);
                json.addProperty("started", true);
                json.addProperty("chestMaterialUseApproved", true);
            }
            case "revoke_chest_materials" -> {
                NpcManager.revokeChestMaterials(player);
                json.addProperty("started", true);
                json.addProperty("chestMaterialUseApproved", false);
            }
            case "equip_best_gear" -> {
                NpcManager.autoEquipNow(player);
                json.addProperty("started", true);
            }
            case "stop" -> {
                NpcManager.stop(player);
                json.addProperty("started", true);
                json.addProperty("note", "Stopped the NPC work task. Default guard/protection remains active; use stop_guard or /mcai npc unguard to disable protection.");
            }
            case "stop_guard" -> {
                ProtectionManager.stop(player);
                json.addProperty("started", true);
                json.addProperty("note", "Stopped guard mode and suppressed default protection until guard is enabled again.");
            }
            case "come" -> {
                NpcManager.comeTo(player);
                json.addProperty("started", true);
            }
            case "follow" -> {
                NpcManager.follow(player);
                json.addProperty("started", true);
            }
            default -> {
                json.addProperty("ok", false);
                json.addProperty("error", "UNSUPPORTED_ACTION");
                json.addProperty("message", "Allowed actions: collect_items, harvest_logs, gather_stone, gather_materials, preview_structure, build_structure, cancel_structure, preview_machine, authorize_machine_plan, build_machine, test_machine, cancel_machine_build, survival_assist, till_field, plant_crop, harvest_crops, hunt_food_animal, feed_animal, breed_animals, tame_animal, build_redstone_template, build_basic_house, build_large_house, start_plan, taskgraph_next, continue_plan, report_plan, cancel_plan, inspect_block, break_block, place_block, craft_item, craft_at_table, craft_from_chest_at_table, withdraw_from_chest, deposit_item_to_chest, approve_chest_materials, revoke_chest_materials, equip_best_gear, stop, stop_guard, come, follow.");
            }
        }

        json.add("npc", NpcManager.describeFor(player));
        return json;
    }

    private static void addActionResult(JsonObject json, ActionResult result) {
        json.add("actionResult", result.toJson());
        json.addProperty("started", result.isStarted());
        json.addProperty("success", result.isSuccess());
        json.addProperty("blocked", result.isBlocked());
        if (result.isBlocked() || result.isFailed()) {
            json.addProperty("ok", false);
        }
    }

    private JsonObject onServer(URI uri, Function<MinecraftServer, JsonObject> action) {
        MinecraftServer server = minecraftServer;
        if (server == null) {
            return error("SERVER_NOT_AVAILABLE", "Minecraft server is not started.");
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(action.apply(server));
            } catch (RuntimeException error) {
                future.completeExceptionally(error);
            }
        });

        try {
            return future.get(SERVER_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception error) {
            JsonObject json = error("SERVER_TASK_FAILED", error.getClass().getSimpleName() + ": " + error.getMessage());
            json.addProperty("path", uri.getPath());
            return json;
        }
    }

    private static ServerPlayer selectPlayer(MinecraftServer server, String requestedName) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return null;
        }
        if (requestedName == null || requestedName.isBlank()) {
            return players.get(0);
        }
        for (ServerPlayer player : players) {
            if (player.getGameProfile().getName().equalsIgnoreCase(requestedName)) {
                return player;
            }
        }
        return null;
    }

    private static JsonArray playersJson(MinecraftServer server) {
        JsonArray players = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", player.getGameProfile().getName());
            item.addProperty("dimension", player.level().dimension().location().toString());
            item.addProperty("x", Math.round(player.getX() * 100.0D) / 100.0D);
            item.addProperty("y", Math.round(player.getY() * 100.0D) / 100.0D);
            item.addProperty("z", Math.round(player.getZ() * 100.0D) / 100.0D);
            players.add(item);
        }
        return players;
    }

    private static JsonObject blockPosJson(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    private static JsonObject baseOk(String name) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        json.addProperty("name", name);
        return json;
    }

    private static void requireProperty(JsonObject object, String owner, String property, JsonArray failures) {
        if (!object.has(property)) {
            failures.add(owner + "." + property + " is missing");
        }
    }

    private static void requireObject(JsonObject object, String owner, String property, JsonArray failures) {
        if (!object.has(property) || !object.get(property).isJsonObject()) {
            failures.add(owner + "." + property + " must be an object");
        }
    }

    private static void requireBooleanCapability(JsonObject capabilities, String key, boolean expected, JsonArray failures) {
        if (!capabilities.has(key) || !capabilities.get(key).isJsonPrimitive() || !capabilities.get(key).getAsJsonPrimitive().isBoolean()) {
            failures.add("capabilities." + key + " must be boolean " + expected);
            return;
        }
        boolean actual = capabilities.get(key).getAsBoolean();
        if (actual != expected) {
            failures.add("capabilities." + key + " expected " + expected + " but was " + actual);
        }
    }

    private static void requireTaskControllerParallelSafe(JsonArray catalog, String name, boolean expected, JsonArray failures) {
        JsonObject controller = taskControllerByName(catalog, name);
        if (controller == null) {
            failures.add("capabilities.taskControllerCatalog missing " + name);
            return;
        }
        if (!controller.has("parallelSafe")
                || !controller.get("parallelSafe").isJsonPrimitive()
                || !controller.get("parallelSafe").getAsJsonPrimitive().isBoolean()) {
            failures.add("capabilities.taskControllerCatalog." + name + ".parallelSafe must be boolean " + expected);
            return;
        }
        boolean actual = controller.get("parallelSafe").getAsBoolean();
        if (actual != expected) {
            failures.add("capabilities.taskControllerCatalog." + name + ".parallelSafe expected " + expected + " but was " + actual);
        }
    }

    private static JsonObject taskControllerByName(JsonArray catalog, String name) {
        for (JsonElement element : catalog) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject controller = element.getAsJsonObject();
            if (controller.has("name")
                    && controller.get("name").isJsonPrimitive()
                    && name.equals(controller.get("name").getAsString())) {
                return controller;
            }
        }
        return null;
    }

    private static void requireTaskControllerPlannerContract(JsonArray catalog, String name, JsonArray failures) {
        JsonObject controller = taskControllerByName(catalog, name);
        if (controller == null) {
            failures.add("capabilities.taskControllerCatalog missing planner contract for " + name);
            return;
        }
        requireJsonArray(controller, "capabilities.taskControllerCatalog." + name, "requirements", failures);
        requireJsonArray(controller, "capabilities.taskControllerCatalog." + name, "resources", failures);
        requireJsonArray(controller, "capabilities.taskControllerCatalog." + name, "locks", failures);
        requireJsonArray(controller, "capabilities.taskControllerCatalog." + name, "safety", failures);
        requireJsonArray(controller, "capabilities.taskControllerCatalog." + name, "effects", failures);
        requireObject(controller, "capabilities.taskControllerCatalog." + name, "targetScopePolicy", failures);
    }

    private static void requirePrimitive(JsonArray primitives, String name, JsonArray failures) {
        for (JsonElement element : primitives) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject primitive = element.getAsJsonObject();
            if (!primitive.has("name") || !name.equals(primitive.get("name").getAsString())) {
                continue;
            }
            requireProperty(primitive, "actionPrimitives." + name, "executor", failures);
            requireProperty(primitive, "actionPrimitives." + name, "safetyLevel", failures);
            requireJsonArray(primitive, "actionPrimitives." + name, "requiredContext", failures);
            requireJsonArray(primitive, "actionPrimitives." + name, "repairStrategies", failures);
            return;
        }
        failures.add("actionPrimitives missing " + name);
    }

    private static void requireActionResultContract(JsonObject actionResult, JsonArray failures) {
        requireProperty(actionResult, "actionResult", "schemaVersion", failures);
        requireProperty(actionResult, "actionResult", "status", failures);
        requireProperty(actionResult, "actionResult", "code", failures);
        requireProperty(actionResult, "actionResult", "message", failures);
        requireObject(actionResult, "actionResult", "effects", failures);
        requireObject(actionResult, "actionResult", "observations", failures);
        requireJsonArray(actionResult, "actionResult", "blockers", failures);
        requireJsonArray(actionResult, "actionResult", "suggestedRepairs", failures);
        requireProperty(actionResult, "actionResult", "retryable", failures);
        requireProperty(actionResult, "actionResult", "terminal", failures);
        requireProperty(actionResult, "actionResult", "requiresReplan", failures);
        requireProperty(actionResult, "actionResult", "suggestedNextAction", failures);
    }

    private static void requireLatestTaskFeedbackActionResult(JsonObject taskFeedback, JsonArray failures) {
        if (!taskFeedback.has("latestResults") || !taskFeedback.get("latestResults").isJsonArray()) {
            failures.add("taskFeedback.latestResults must be an array");
            return;
        }

        JsonArray latestResults = taskFeedback.getAsJsonArray("latestResults");
        for (JsonElement element : latestResults) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject result = element.getAsJsonObject();
            if (!"dev_test:action_result_contract".equals(result.has("taskName") ? result.get("taskName").getAsString() : "")) {
                continue;
            }
            if (!result.has("actionResult") || !result.get("actionResult").isJsonObject()) {
                failures.add("taskFeedback.latestResults dev_test result must include actionResult object");
                return;
            }
            requireActionResultContract(result.getAsJsonObject("actionResult"), failures);
            return;
        }

        failures.add("taskFeedback.latestResults missing dev_test:action_result_contract");
    }

    private static void requireJsonArray(JsonObject object, String owner, String property, JsonArray failures) {
        if (!object.has(property) || !object.get(property).isJsonArray()) {
            failures.add(owner + "." + property + " must be an array");
        }
    }

    private static JsonObject error(String code, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", false);
        json.addProperty("error", code);
        json.addProperty("message", message);
        return json;
    }

    private static String transcriptText(URI uri, String body) {
        String text = queryParam(uri, "text");
        if (!text.isBlank()) {
            return text.trim();
        }

        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        if (trimmed.startsWith("{")) {
            try {
                JsonObject json = JsonParser.parseString(trimmed).getAsJsonObject();
                for (String key : List.of("text", "transcript", "message")) {
                    if (json.has(key) && json.get(key).isJsonPrimitive()) {
                        String value = json.get(key).getAsString().trim();
                        if (!value.isBlank()) {
                            return value;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                return "";
            }
        }

        String formText = formParam(trimmed, "text");
        if (!formText.isBlank()) {
            return formText.trim();
        }
        formText = formParam(trimmed, "transcript");
        if (!formText.isBlank()) {
            return formText.trim();
        }
        return trimmed;
    }

    private static String formParam(String body, String name) {
        for (String part : body.split("&")) {
            int split = part.indexOf('=');
            if (split <= 0) {
                continue;
            }
            String key = URLDecoder.decode(part.substring(0, split), StandardCharsets.UTF_8);
            if (!name.equals(key)) {
                continue;
            }
            return URLDecoder.decode(part.substring(split + 1), StandardCharsets.UTF_8);
        }
        return "";
    }

    private static String queryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String part : query.split("&")) {
            int split = part.indexOf('=');
            String key = split < 0 ? part : part.substring(0, split);
            if (!URLDecoder.decode(key, StandardCharsets.UTF_8).equals(name)) {
                continue;
            }
            String value = split < 0 ? "" : part.substring(split + 1);
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        return "";
    }

    private static int intQueryParam(URI uri, String name, int fallback, int min, int max) {
        String raw = queryParam(uri, name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean booleanQueryParam(URI uri, String name, boolean fallback) {
        String raw = queryParam(uri, name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "y", "on" -> true;
            case "0", "false", "no", "n", "off" -> false;
            default -> fallback;
        };
    }

    private static BridgeDecision devPlanDecision(ServerPlayer player, String actionName, String goal) {
        String message = "Dev test plan: " + goal;
        return new BridgeDecision(message, new BridgeDecision.Action(
                actionName,
                player.getGameProfile().getName(),
                message,
                null,
                null,
                null,
                null,
                "goal",
                goal,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "active",
                null
        ));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static BlockPos queryBlockPos(URI uri, BlockPos fallback) {
        int x = intQueryParam(uri, "x", fallback.getX(), -30000000, 30000000);
        int y = intQueryParam(uri, "y", fallback.getY(), -2048, 2048);
        int z = intQueryParam(uri, "z", fallback.getZ(), -30000000, 30000000);
        return new BlockPos(x, y, z);
    }

    private static BlockPos optionalQueryBlockPos(URI uri) {
        if (queryParam(uri, "x").isBlank() || queryParam(uri, "y").isBlank() || queryParam(uri, "z").isBlank()) {
            return null;
        }
        return queryBlockPos(uri, BlockPos.ZERO);
    }

    private static Direction queryDirection(URI uri, Direction fallback) {
        String raw = firstNonBlank(queryParam(uri, "facing"), queryParam(uri, "direction"), queryParam(uri, "forward"));
        if (!raw.isBlank()) {
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (direction.getName().equals(normalized)) {
                    return direction;
                }
            }
        }
        return fallback == null || fallback.getAxis().isVertical() ? Direction.NORTH : fallback;
    }

    private static Request readRequest(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        byte[] headerBytes = readHeaderBytes(input);
        String headerText = new String(headerBytes, StandardCharsets.US_ASCII);
        String[] lines = headerText.split("\\r?\\n");
        if (lines.length == 0) {
            throw new IOException("Empty HTTP request.");
        }

        String requestLine = lines[0];
        if (requestLine == null || requestLine.isBlank()) {
            throw new IOException("Empty HTTP request.");
        }

        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("Malformed HTTP request line: " + requestLine);
        }

        int contentLength = 0;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ("content-length".equalsIgnoreCase(name)) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException error) {
                    throw new IOException("Invalid Content-Length: " + value);
                }
            }
        }

        if (contentLength < 0 || contentLength > MAX_REQUEST_BODY_BYTES) {
            throw new IOException("Unsupported request body size: " + contentLength);
        }
        byte[] bodyBytes = contentLength == 0 ? new byte[0] : input.readNBytes(contentLength);
        if (bodyBytes.length != contentLength) {
            throw new IOException("Incomplete HTTP request body.");
        }

        return new Request(parts[0], parts[1], new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private static byte[] readHeaderBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != -1) {
            output.write(b);
            int size = output.size();
            if (size > MAX_REQUEST_HEADER_BYTES) {
                throw new IOException("HTTP request headers are too large.");
            }
            byte[] bytes = output.toByteArray();
            if (size >= 4
                    && bytes[size - 4] == '\r'
                    && bytes[size - 3] == '\n'
                    && bytes[size - 2] == '\r'
                    && bytes[size - 1] == '\n') {
                return bytes;
            }
            if (size >= 2 && bytes[size - 2] == '\n' && bytes[size - 1] == '\n') {
                return bytes;
            }
        }
        throw new IOException("Unexpected end of HTTP request headers.");
    }

    private static void writeJson(Socket socket, int status, JsonObject payload) throws IOException {
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        String statusText = switch (status) {
            case 200 -> "OK";
            case 404 -> "Not Found";
            default -> "Error";
        };
        String headers = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private void closeSocket() {
        if (serverSocket == null) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Closing during shutdown.
        }
        serverSocket = null;
    }

    private record Request(String method, String target, String body) {
    }

    private record JsonResponse(int status, JsonObject body) {
    }
}
