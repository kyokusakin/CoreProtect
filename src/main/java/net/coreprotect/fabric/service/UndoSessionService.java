package net.coreprotect.fabric.service;

import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.util.QueryBounds;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class UndoSessionService {
    private final Map<String, UndoOperation> sessions = new ConcurrentHashMap<>();

    public UndoSessionService() {
    }

    public UndoSessionService(Path storagePath, Logger logger) {
        // Upstream session behavior is in-memory only.
    }

    public void remember(String sessionKey, UndoOperation operation) {
        if (sessionKey == null || sessionKey.isBlank() || operation == null) {
            return;
        }
        sessions.put(sessionKey, operation);
    }

    public UndoOperation get(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return null;
        }
        return sessions.get(sessionKey);
    }

    public UndoOperation consume(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return null;
        }
        return sessions.remove(sessionKey);
    }

    public UndoOperation consumePreview(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return null;
        }

        UndoOperation operation = sessions.get(sessionKey);
        if (operation == null || !operation.preview()) {
            return null;
        }

        sessions.remove(sessionKey);
        return operation;
    }

    public void clear(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        sessions.remove(sessionKey);
    }

    public static final class UndoOperation {
        private final boolean restore;
        private final boolean preview;
        private final String worldKey;
        private final QueryBounds bounds;
        private final long notBefore;
        private final long notAfter;
        private final List<String> actorNames;
        private final List<String> excludeActorNames;
        private final List<CoreProtectEventType> actionFilter;
        private final List<String> includeTargets;
        private final List<String> excludeTargets;
        private final String description;

        public UndoOperation(
            boolean restore,
            boolean preview,
            String worldKey,
            QueryBounds bounds,
            long notBefore,
            long notAfter,
            List<String> actorNames,
            List<String> excludeActorNames,
            List<CoreProtectEventType> actionFilter,
            List<String> includeTargets,
            List<String> excludeTargets,
            String description
        ) {
            this.restore = restore;
            this.preview = preview;
            this.worldKey = worldKey;
            this.bounds = bounds;
            this.notBefore = notBefore;
            this.notAfter = notAfter;
            this.actorNames = actorNames == null ? null : List.copyOf(actorNames);
            this.excludeActorNames = excludeActorNames == null ? null : List.copyOf(excludeActorNames);
            this.actionFilter = actionFilter == null ? null : List.copyOf(actionFilter);
            this.includeTargets = includeTargets == null ? null : List.copyOf(includeTargets);
            this.excludeTargets = excludeTargets == null ? null : List.copyOf(excludeTargets);
            this.description = description == null ? "" : description;
        }

        public boolean restore() {
            return restore;
        }

        public boolean preview() {
            return preview;
        }

        public String worldKey() {
            return worldKey;
        }

        public QueryBounds bounds() {
            return bounds;
        }

        public long notBefore() {
            return notBefore;
        }

        public long notAfter() {
            return notAfter;
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

        public String description() {
            return description;
        }
    }
}
