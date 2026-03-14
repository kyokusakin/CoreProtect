package net.coreprotect.fabric.service;

import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.util.QueryBounds;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LookupSessionService {
    private final Map<String, LookupQuery> sessions = new ConcurrentHashMap<>();

    public void remember(String sessionKey, LookupQuery query) {
        if (sessionKey == null || sessionKey.isBlank() || query == null) {
            return;
        }
        sessions.put(sessionKey, query);
    }

    public LookupQuery get(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return null;
        }
        return sessions.get(sessionKey);
    }

    public void clear(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        sessions.remove(sessionKey);
    }

    public static final class LookupQuery {
        private final String worldKey;
        private final BlockPos center;
        private final Integer radius;
        private final QueryBounds bounds;
        private final int minimumSeconds;
        private final int maximumSeconds;
        private final int linesPerPage;
        private final List<String> actorNames;
        private final List<String> excludeActorNames;
        private final List<CoreProtectEventType> actionFilter;
        private final List<String> includeTargets;
        private final List<String> excludeTargets;

        public LookupQuery(
            String worldKey,
            BlockPos center,
            Integer radius,
            QueryBounds bounds,
            int minimumSeconds,
            int maximumSeconds,
            int linesPerPage,
            List<String> actorNames,
            List<String> excludeActorNames,
            List<CoreProtectEventType> actionFilter,
            List<String> includeTargets,
            List<String> excludeTargets
        ) {
            this.worldKey = worldKey;
            this.center = center == null ? null : center.toImmutable();
            this.radius = radius;
            this.bounds = bounds;
            this.minimumSeconds = minimumSeconds;
            this.maximumSeconds = maximumSeconds;
            this.linesPerPage = linesPerPage;
            this.actorNames = actorNames == null ? null : List.copyOf(actorNames);
            this.excludeActorNames = excludeActorNames == null ? null : List.copyOf(excludeActorNames);
            this.actionFilter = actionFilter == null ? null : List.copyOf(actionFilter);
            this.includeTargets = includeTargets == null ? null : List.copyOf(includeTargets);
            this.excludeTargets = excludeTargets == null ? null : List.copyOf(excludeTargets);
        }

        public String worldKey() {
            return worldKey;
        }

        public BlockPos center() {
            return center;
        }

        public Integer radius() {
            return radius;
        }

        public QueryBounds bounds() {
            return bounds;
        }

        public int minimumSeconds() {
            return minimumSeconds;
        }

        public int maximumSeconds() {
            return maximumSeconds;
        }

        public int linesPerPage() {
            return linesPerPage;
        }

        public List<String> actorNames() {
            return actorNames;
        }

        public List<String> excludeActorNames() {
            return excludeActorNames;
        }

        public List<CoreProtectEventType> actionFilter() {
            return actionFilter;
        }

        public List<String> includeTargets() {
            return includeTargets;
        }

        public List<String> excludeTargets() {
            return excludeTargets;
        }

        public LookupQuery withLinesPerPage(int linesPerPage) {
            return new LookupQuery(
                worldKey,
                center,
                radius,
                bounds,
                minimumSeconds,
                maximumSeconds,
                linesPerPage,
                actorNames,
                excludeActorNames,
                actionFilter,
                includeTargets,
                excludeTargets
            );
        }
    }
}
