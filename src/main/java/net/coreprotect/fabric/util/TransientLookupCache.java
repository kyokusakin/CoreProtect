package net.coreprotect.fabric.util;

import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TransientLookupCache {
    private static final long BLOCK_ACTOR_TTL_MS = 30_000L;
    private static final long REMOVED_ACTOR_TTL_MS = 30_000L;
    private static final long SPREAD_TTL_MS = 1_800_000L;
    private static final long DRAGON_EGG_TTL_MS = 5_000L;
    private static final long DRAGON_EGG_LINK_MS = 50L;
    private static final long CLEANUP_INTERVAL_MS = 1_000L;

    private static final Map<String, BlockActorEntry> BLOCK_ACTORS = new ConcurrentHashMap<>();
    private static final Map<String, RemovedActorEntry> REMOVED_ACTORS = new ConcurrentHashMap<>();
    private static final Map<String, SpreadEntry> SPREADS = new ConcurrentHashMap<>();
    private static final Map<String, DragonEggEntry> DRAGON_EGG_INTERACTIONS = new ConcurrentHashMap<>();

    private static volatile long lastCleanupAt = System.currentTimeMillis();

    private TransientLookupCache() {
    }

    public static void rememberPlacedActor(String worldKey, BlockPos pos, String actorName, String stateKey) {
        if (worldKey == null || actorName == null || actorName.isBlank()) {
            return;
        }

        cleanupIfNeeded();
        BLOCK_ACTORS.put(blockKey(worldKey, pos), new BlockActorEntry(System.currentTimeMillis(), actorName, stateKey));
    }

    public static void rememberPlacedActor(String worldKey, BlockPos pos, String actorName) {
        rememberPlacedActor(worldKey, pos, actorName, null);
    }

    public static void rememberRemovedActor(String worldKey, BlockPos pos, String actorName, String stateKey) {
        if (worldKey == null || actorName == null || actorName.isBlank()) {
            return;
        }

        cleanupIfNeeded();
        REMOVED_ACTORS.put(blockKey(worldKey, pos), new RemovedActorEntry(System.currentTimeMillis(), actorName, stateKey));
    }

    public static String findRemovedActor(String worldKey, BlockPos pos) {
        if (worldKey == null) {
            return null;
        }

        cleanupIfNeeded();
        String key = blockKey(worldKey, pos);
        RemovedActorEntry entry = REMOVED_ACTORS.get(key);
        if (entry == null || isExpired(entry.timestampMs(), REMOVED_ACTOR_TTL_MS)) {
            REMOVED_ACTORS.remove(key);
            return null;
        }
        return entry.actorName();
    }

    public static CachedBlockAction findRemovedAction(String worldKey, BlockPos pos) {
        if (worldKey == null) {
            return null;
        }

        cleanupIfNeeded();
        String key = blockKey(worldKey, pos);
        RemovedActorEntry entry = REMOVED_ACTORS.get(key);
        if (entry == null || isExpired(entry.timestampMs(), REMOVED_ACTOR_TTL_MS)) {
            REMOVED_ACTORS.remove(key);
            return null;
        }
        return new CachedBlockAction(entry.timestampMs(), entry.actorName(), entry.stateKey());
    }

    public static String findPlacedActor(String worldKey, BlockPos pos) {
        return findPlacedActor(worldKey, pos, (String[]) null);
    }

    public static String findPlacedActor(String worldKey, BlockPos pos, String... stateKeys) {
        if (worldKey == null) {
            return null;
        }

        cleanupIfNeeded();
        String key = blockKey(worldKey, pos);
        BlockActorEntry entry = BLOCK_ACTORS.get(key);
        if (entry == null || isExpired(entry.timestampMs(), BLOCK_ACTOR_TTL_MS)) {
            BLOCK_ACTORS.remove(key);
            return null;
        }
        if (stateKeys != null && stateKeys.length > 0) {
            boolean matched = false;
            for (String stateKey : stateKeys) {
                if (stateKey != null && stateKey.equals(entry.stateKey())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return null;
            }
        }
        return entry.actorName();
    }

    public static CachedBlockAction findPlacedAction(String worldKey, BlockPos pos) {
        if (worldKey == null) {
            return null;
        }

        cleanupIfNeeded();
        String key = blockKey(worldKey, pos);
        BlockActorEntry entry = BLOCK_ACTORS.get(key);
        if (entry == null || isExpired(entry.timestampMs(), BLOCK_ACTOR_TTL_MS)) {
            BLOCK_ACTORS.remove(key);
            return null;
        }
        return new CachedBlockAction(entry.timestampMs(), entry.actorName(), entry.stateKey());
    }

    public static boolean markSpreadAndCheckDuplicate(String worldKey, BlockPos pos, String fluidKey) {
        cleanupIfNeeded();
        String key = blockKey(worldKey, pos);
        SpreadEntry previous = SPREADS.put(key, new SpreadEntry(System.currentTimeMillis(), fluidKey));
        return previous != null && !isExpired(previous.timestampMs(), SPREAD_TTL_MS) && previous.fluidKey().equals(fluidKey);
    }

    public static void rememberDragonEggInteraction(String worldKey, BlockPos pos, String actorName) {
        if (worldKey == null || actorName == null || actorName.isBlank()) {
            return;
        }

        cleanupIfNeeded();
        DRAGON_EGG_INTERACTIONS.put(blockKey(worldKey, pos), new DragonEggEntry(System.currentTimeMillis(), actorName));
    }

    public static String consumeDragonEggInteraction(String worldKey, BlockPos pos) {
        cleanupIfNeeded();
        DragonEggEntry entry = DRAGON_EGG_INTERACTIONS.remove(blockKey(worldKey, pos));
        if (entry == null || isExpired(entry.timestampMs(), DRAGON_EGG_TTL_MS)) {
            return null;
        }
        return (System.currentTimeMillis() - entry.timestampMs()) <= DRAGON_EGG_LINK_MS ? entry.actorName() : null;
    }

    public static void clear() {
        BLOCK_ACTORS.clear();
        REMOVED_ACTORS.clear();
        SPREADS.clear();
        DRAGON_EGG_INTERACTIONS.clear();
        lastCleanupAt = System.currentTimeMillis();
    }

    private static void cleanupIfNeeded() {
        long now = System.currentTimeMillis();
        if ((now - lastCleanupAt) < CLEANUP_INTERVAL_MS) {
            return;
        }

        lastCleanupAt = now;
        BLOCK_ACTORS.entrySet().removeIf(entry -> isExpired(entry.getValue().timestampMs(), BLOCK_ACTOR_TTL_MS));
        REMOVED_ACTORS.entrySet().removeIf(entry -> isExpired(entry.getValue().timestampMs(), REMOVED_ACTOR_TTL_MS));
        SPREADS.entrySet().removeIf(entry -> isExpired(entry.getValue().timestampMs(), SPREAD_TTL_MS));
        DRAGON_EGG_INTERACTIONS.entrySet().removeIf(entry -> isExpired(entry.getValue().timestampMs(), DRAGON_EGG_TTL_MS));
    }

    private static boolean isExpired(long timestampMs, long ttlMs) {
        return (System.currentTimeMillis() - timestampMs) > ttlMs;
    }

    private static String blockKey(String worldKey, BlockPos pos) {
        return worldKey + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
    }

    private record BlockActorEntry(long timestampMs, String actorName, String stateKey) {
    }

    private record RemovedActorEntry(long timestampMs, String actorName, String stateKey) {
    }

    private record SpreadEntry(long timestampMs, String fluidKey) {
    }

    private record DragonEggEntry(long timestampMs, String actorName) {
    }

    public record CachedBlockAction(long timestampMs, String actorName, String stateKey) {
    }
}
