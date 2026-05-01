package com.mcaibot.companion;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcaibot.companion.tasks.TaskControllerRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class BridgeClient {
    private static final Gson GSON = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public CompletableFuture<BridgeDecision> decide(BridgeContext context) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(McAiConfig.BRIDGE_URL.get()))
                .timeout(Duration.ofMillis(McAiConfig.REQUEST_TIMEOUT_MS.get()))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(context.payload())));

        String token = McAiConfig.BRIDGE_TOKEN.get();
        if (!token.isBlank()) {
            builder.header("x-bridge-token", token);
        }

        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("Bridge HTTP " + response.statusCode() + ": " + response.body());
                    }
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (!root.has("ok") || !root.get("ok").getAsBoolean()) {
                        throw new IllegalStateException("Bridge rejected request: " + response.body());
                    }
                    return BridgeDecision.fromJson(root.getAsJsonObject("decision"));
                });
    }

    public CompletableFuture<Boolean> postSkillsHandshake() {
        JsonObject payload = new JsonObject();
        payload.addProperty("skillsProtocolVersion", "neoforge-skills-v1");
        payload.addProperty("generatedAt", Instant.now().toString());
        payload.addProperty("modVersion", "0.1.0");
        payload.add("skillRegistry", SkillRegistry.catalogJson());
        payload.add("actionPrimitives", SkillRegistry.actionPrimitivesJson());
        payload.add("taskControllerCatalog", TaskControllerRegistry.catalogJson());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(skillsUri())
                .timeout(Duration.ofMillis(McAiConfig.REQUEST_TIMEOUT_MS.get()))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)));

        String token = McAiConfig.BRIDGE_TOKEN.get();
        if (!token.isBlank()) {
            builder.header("x-bridge-token", token);
        }

        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> response.statusCode() >= 200 && response.statusCode() < 300);
    }

    private static URI skillsUri() {
        String decisionUrl = McAiConfig.BRIDGE_URL.get();
        if (decisionUrl.endsWith("/bridge/decide")) {
            return URI.create(decisionUrl.substring(0, decisionUrl.length() - "/bridge/decide".length()) + "/bridge/skills");
        }
        return URI.create(decisionUrl).resolve("/bridge/skills");
    }
}
