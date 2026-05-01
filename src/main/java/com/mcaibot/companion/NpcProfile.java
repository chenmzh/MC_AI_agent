package com.mcaibot.companion;

import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.UUID;

public record NpcProfile(
        String id,
        String name,
        String personality,
        String skin,
        String style,
        String owner,
        String defaultRole,
        boolean enabled
) {
    public static final String DEFAULT_ID = "codexbot";
    public static final String DEFAULT_NAME = "CodexBot";
    public static final String DEFAULT_SKIN = "female_companion";
    public static final String DEFAULT_STYLE = "friendly_cooperative";
    public static final String DEFAULT_ROLE = "companion";
    public static final String DEFAULT_PERSONALITY = "Helpful, calm, practical, and focused on Minecraft survival tasks.";

    public NpcProfile {
        id = sanitizeId(id, DEFAULT_ID);
        name = textOrDefault(name, DEFAULT_NAME);
        personality = textOrDefault(personality, DEFAULT_PERSONALITY);
        skin = textOrDefault(skin, DEFAULT_SKIN);
        style = textOrDefault(style, DEFAULT_STYLE);
        owner = owner == null ? "" : owner.trim();
        defaultRole = textOrDefault(defaultRole, DEFAULT_ROLE);
    }

    public static NpcProfile defaultProfile() {
        return new NpcProfile(
                DEFAULT_ID,
                DEFAULT_NAME,
                DEFAULT_PERSONALITY,
                DEFAULT_SKIN,
                DEFAULT_STYLE,
                "",
                DEFAULT_ROLE,
                true
        );
    }

    public static NpcProfile fromJson(JsonObject json) {
        if (json == null) {
            return defaultProfile();
        }

        String name = stringValue(json, "name", DEFAULT_NAME);
        String id = sanitizeId(stringValue(json, "id", name), sanitizeId(name, DEFAULT_ID));
        return new NpcProfile(
                id,
                name,
                stringValue(json, "personality", DEFAULT_PERSONALITY),
                stringValue(json, "skin", DEFAULT_SKIN),
                stringValue(json, "style", DEFAULT_STYLE),
                stringValue(json, "owner", ""),
                stringValue(json, "defaultRole", DEFAULT_ROLE),
                booleanValue(json, "enabled", true)
        );
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("name", name);
        json.addProperty("personality", personality);
        json.addProperty("skin", skin);
        json.addProperty("style", style);
        json.addProperty("owner", owner);
        json.addProperty("defaultRole", defaultRole);
        json.addProperty("enabled", enabled);
        return json;
    }

    public JsonObject toPersonaJson() {
        JsonObject persona = toJson();
        persona.addProperty("prompt", personaPrompt());
        return persona;
    }

    public String personaPrompt() {
        String ownerText = owner.isBlank() ? "any player" : owner;
        return "You are " + name + ", an AI NPC in Minecraft.\n"
                + "Profile id: " + id + "\n"
                + "Default role: " + defaultRole + "\n"
                + "Personality: " + personality + "\n"
                + "Speaking/action style: " + style + "\n"
                + "Owner scope: " + ownerText + "\n"
                + "Skin/style hint: " + skin + "\n"
                + "Stay in character, respect the player request, and use available Minecraft actions when helpful.";
    }

    public boolean isOwnedBy(UUID playerUuid, String playerName) {
        if (owner.isBlank()) {
            return true;
        }
        if (playerUuid != null && owner.equalsIgnoreCase(playerUuid.toString())) {
            return true;
        }
        return playerName != null && owner.equalsIgnoreCase(playerName);
    }

    public boolean matchesSelector(String selector) {
        if (selector == null || selector.isBlank()) {
            return false;
        }
        String normalized = selector.trim();
        return id.equalsIgnoreCase(normalized) || name.equalsIgnoreCase(normalized);
    }

    public NpcProfile withId(String newId) {
        return new NpcProfile(newId, name, personality, skin, style, owner, defaultRole, enabled);
    }

    public NpcProfile withName(String newName) {
        return new NpcProfile(id, newName, personality, skin, style, owner, defaultRole, enabled);
    }

    public NpcProfile withPersonality(String newPersonality) {
        return new NpcProfile(id, name, newPersonality, skin, style, owner, defaultRole, enabled);
    }

    public NpcProfile withSkin(String newSkin) {
        return new NpcProfile(id, name, personality, newSkin, style, owner, defaultRole, enabled);
    }

    public NpcProfile withStyle(String newStyle) {
        return new NpcProfile(id, name, personality, skin, newStyle, owner, defaultRole, enabled);
    }

    public NpcProfile withDefaultRole(String newDefaultRole) {
        return new NpcProfile(id, name, personality, skin, style, owner, newDefaultRole, enabled);
    }

    public NpcProfile withOwner(String newOwner) {
        return new NpcProfile(id, name, personality, skin, style, newOwner, defaultRole, enabled);
    }

    public NpcProfile withEnabled(boolean newEnabled) {
        return new NpcProfile(id, name, personality, skin, style, owner, defaultRole, newEnabled);
    }

    public static String sanitizeId(String value, String fallback) {
        String fallbackValue = fallback == null || fallback.isBlank() ? DEFAULT_ID : fallback;
        if (value == null || value.isBlank()) {
            return fallbackValue;
        }

        String cleaned = value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]+", "_")
                .replaceAll("^_+|_+$", "");
        return cleaned.isBlank() ? fallbackValue : cleaned;
    }

    private static String textOrDefault(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.replace("\r\n", "\n").replace('\r', '\n').trim();
        return trimmed.isBlank() ? fallback : trimmed;
    }

    private static String stringValue(JsonObject json, String key, String fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean booleanValue(JsonObject json, String key, boolean fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
