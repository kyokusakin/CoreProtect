package net.coreprotect.fabric.service;

import net.coreprotect.fabric.config.CoreProtectFabricConfig;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldConfigService {
    private final Path rootDirectory;
    private final CoreProtectFabricConfig globalConfig;
    private final ConcurrentHashMap<String, CoreProtectFabricConfig> cache = new ConcurrentHashMap<>();

    private WorldConfigService(Path rootDirectory, CoreProtectFabricConfig globalConfig) {
        this.rootDirectory = rootDirectory;
        this.globalConfig = globalConfig;
    }

    public static WorldConfigService load(Path rootDirectory, Path configPath) throws IOException {
        CoreProtectFabricConfig globalConfig = CoreProtectFabricConfig.load(configPath);
        return new WorldConfigService(rootDirectory, globalConfig);
    }

    public CoreProtectFabricConfig globalConfig() {
        return globalConfig;
    }

    public CoreProtectFabricConfig resolve(String worldKey) {
        if (worldKey == null || worldKey.isBlank()) {
            return globalConfig;
        }
        return cache.computeIfAbsent(worldKey, this::loadWorldOverride);
    }

    private CoreProtectFabricConfig loadWorldOverride(String worldKey) {
        for (Path candidate : configCandidates(worldKey)) {
            if (!Files.exists(candidate)) {
                continue;
            }
            try {
                return CoreProtectFabricConfig.load(candidate, globalConfig);
            }
            catch (IOException ignored) {
            }
        }
        return globalConfig;
    }

    private List<Path> configCandidates(String worldKey) {
        Set<String> names = new LinkedHashSet<>();
        String normalizedKey = worldKey.trim();

        if (World.OVERWORLD.getValue().toString().equals(normalizedKey)) {
            names.add("world.properties");
            names.add("overworld.properties");
        }
        else if (World.NETHER.getValue().toString().equals(normalizedKey)) {
            names.add("world_nether.properties");
            names.add("the_nether.properties");
        }
        else if (World.END.getValue().toString().equals(normalizedKey)) {
            names.add("world_the_end.properties");
            names.add("the_end.properties");
        }

        names.add(sanitizeWorldKey(normalizedKey) + ".properties");
        if (normalizedKey.startsWith("minecraft:")) {
            names.add(normalizedKey.substring("minecraft:".length()).replace('/', '_') + ".properties");
        }

        List<Path> candidates = new ArrayList<>();
        for (String name : names) {
            candidates.add(rootDirectory.resolve(name));
        }
        return candidates;
    }

    private String sanitizeWorldKey(String worldKey) {
        return worldKey
            .replace(':', '_')
            .replace('/', '_')
            .replace('\\', '_');
    }
}
