package com.mcaibot.companion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class NpcProfileStore {
    public static final String FILE_NAME = "npc_profiles.json";
    public static final String EXAMPLE_FILE_NAME = "npc_profiles.example.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Object lock = new Object();
    private final Path configPath;
    private final Path gamePath;
    private final Path examplePath;

    private List<NpcProfile> profiles = List.of(NpcProfile.defaultProfile());
    private Path loadedPath;

    public NpcProfileStore(Path configPath, Path gamePath, Path examplePath) {
        this.configPath = configPath;
        this.gamePath = gamePath;
        this.examplePath = examplePath;
    }

    public static NpcProfileStore createDefault() {
        Path configPath = FMLPaths.CONFIGDIR.get()
                .resolve(McAiCompanion.MODID)
                .resolve(FILE_NAME);
        Path gamePath = FMLPaths.GAMEDIR.get().resolve(FILE_NAME);
        Path examplePath = FMLPaths.GAMEDIR.get().resolve(EXAMPLE_FILE_NAME);
        return new NpcProfileStore(configPath, gamePath, examplePath);
    }

    public List<NpcProfile> load() {
        synchronized (lock) {
            ensureExampleFile();

            Path path = firstExisting(configPath, gamePath);
            if (path == null) {
                profiles = List.of(NpcProfile.defaultProfile());
                loadedPath = configPath;
                writeProfiles(configPath, profiles);
                return profiles;
            }

            loadedPath = path;
            profiles = readProfilesOrDefault(path);
            return List.copyOf(profiles);
        }
    }

    public List<NpcProfile> reload() {
        return load();
    }

    public List<NpcProfile> profiles() {
        synchronized (lock) {
            return List.copyOf(profiles);
        }
    }

    public List<NpcProfile> enabledProfiles() {
        synchronized (lock) {
            return profiles.stream()
                    .filter(NpcProfile::enabled)
                    .toList();
        }
    }

    public Optional<NpcProfile> find(String selector) {
        synchronized (lock) {
            return profiles.stream()
                    .filter(profile -> profile.matchesSelector(selector))
                    .findFirst();
        }
    }

    public Optional<NpcProfile> findEnabled(String selector) {
        synchronized (lock) {
            return profiles.stream()
                    .filter(NpcProfile::enabled)
                    .filter(profile -> profile.matchesSelector(selector))
                    .findFirst();
        }
    }

    public NpcProfile defaultProfile() {
        synchronized (lock) {
            return profiles.stream()
                    .filter(NpcProfile::enabled)
                    .findFirst()
                    .orElse(NpcProfile.defaultProfile());
        }
    }

    public JsonArray enabledProfilesJson() {
        JsonArray array = new JsonArray();
        for (NpcProfile profile : enabledProfiles()) {
            array.add(profile.toPersonaJson());
        }
        return array;
    }

    public JsonObject currentPersonaJson(String selector) {
        return findEnabled(selector)
                .orElseGet(this::defaultProfile)
                .toPersonaJson();
    }

    public void save(Collection<NpcProfile> newProfiles) {
        synchronized (lock) {
            List<NpcProfile> normalized = normalizeProfiles(newProfiles);
            if (normalized.isEmpty()) {
                normalized = List.of(NpcProfile.defaultProfile());
            }

            Path target = loadedPath == null ? configPath : loadedPath;
            writeProfiles(target, normalized);
            loadedPath = target;
            profiles = List.copyOf(normalized);
        }
    }

    public void upsert(NpcProfile profile) {
        synchronized (lock) {
            List<NpcProfile> next = new ArrayList<>(profiles);
            for (int index = 0; index < next.size(); index++) {
                if (next.get(index).id().equalsIgnoreCase(profile.id())) {
                    next.set(index, profile);
                    save(next);
                    return;
                }
            }
            next.add(profile);
            save(next);
        }
    }

    public boolean remove(String selector) {
        synchronized (lock) {
            List<NpcProfile> next = profiles.stream()
                    .filter(profile -> !profile.matchesSelector(selector))
                    .toList();
            if (next.size() == profiles.size()) {
                return false;
            }
            save(next);
            return true;
        }
    }

    public Path loadedPath() {
        synchronized (lock) {
            return loadedPath;
        }
    }

    public Path configPath() {
        return configPath;
    }

    public Path gamePath() {
        return gamePath;
    }

    private List<NpcProfile> readProfilesOrDefault(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            List<NpcProfile> parsed = parseProfiles(root);
            if (!parsed.isEmpty()) {
                return parsed;
            }
            McAiCompanion.LOGGER.warn("NPC profile file {} did not contain valid profiles; using default profile in memory.", path);
        } catch (Exception error) {
            McAiCompanion.LOGGER.warn("Failed to read NPC profile file {}; using default profile in memory.", path, error);
        }
        return List.of(NpcProfile.defaultProfile());
    }

    private List<NpcProfile> parseProfiles(JsonElement root) {
        if (root == null || root.isJsonNull()) {
            return List.of();
        }

        List<NpcProfile> parsed = new ArrayList<>();
        if (root.isJsonArray()) {
            addProfiles(parsed, root.getAsJsonArray());
        } else if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            JsonElement profilesElement = object.get("profiles");
            if (profilesElement != null && profilesElement.isJsonArray()) {
                addProfiles(parsed, profilesElement.getAsJsonArray());
            } else {
                parsed.add(NpcProfile.fromJson(object));
            }
        }
        return normalizeProfiles(parsed);
    }

    private void addProfiles(List<NpcProfile> parsed, JsonArray array) {
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            parsed.add(NpcProfile.fromJson(element.getAsJsonObject()));
        }
    }

    private List<NpcProfile> normalizeProfiles(Collection<NpcProfile> source) {
        Map<String, NpcProfile> unique = new LinkedHashMap<>();
        for (NpcProfile profile : source) {
            if (profile == null) {
                continue;
            }

            NpcProfile candidate = profile;
            String baseId = candidate.id();
            int suffix = 2;
            while (unique.containsKey(candidate.id().toLowerCase())) {
                candidate = profile.withId(baseId + "_" + suffix);
                suffix++;
            }
            unique.put(candidate.id().toLowerCase(), candidate);
        }
        return List.copyOf(unique.values());
    }

    private void writeProfiles(Path path, Collection<NpcProfile> profilesToWrite) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(toDocument(profilesToWrite), writer);
            }
        } catch (IOException error) {
            McAiCompanion.LOGGER.warn("Failed to write NPC profile file {}", path, error);
        }
    }

    private void ensureExampleFile() {
        if (examplePath == null || Files.exists(examplePath)) {
            return;
        }
        writeProfiles(examplePath, sampleProfiles());
    }

    private JsonObject toDocument(Collection<NpcProfile> profilesToWrite) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        JsonArray array = new JsonArray();
        for (NpcProfile profile : profilesToWrite) {
            array.add(profile.toJson());
        }
        root.add("profiles", array);
        return root;
    }

    private Collection<NpcProfile> sampleProfiles() {
        return List.of(
                NpcProfile.defaultProfile(),
                new NpcProfile(
                        "builder",
                        "Mason",
                        "Patient builder who explains plans before placing blocks and prefers safe, compact shelters.",
                        "builder_companion",
                        "methodical_builder",
                        "",
                        "builder",
                        true
                ),
                new NpcProfile(
                        "scout",
                        "Lina",
                        "Curious scout who speaks briefly, reports hazards, and prioritizes exploration and navigation.",
                        "scout_companion",
                        "concise_scout",
                        "",
                        "scout",
                        false
                )
        );
    }

    private Path firstExisting(Path first, Path second) {
        if (first != null && Files.isRegularFile(first)) {
            return first;
        }
        if (second != null && Files.isRegularFile(second)) {
            return second;
        }
        return null;
    }
}
