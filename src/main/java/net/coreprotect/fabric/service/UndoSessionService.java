package net.coreprotect.fabric.service;

import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.util.QueryBounds;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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

        private void write(DataOutputStream output) throws IOException {
            output.writeBoolean(restore);
            output.writeBoolean(preview);
            writeNullableString(output, worldKey);
            writeBounds(output, bounds);
            output.writeLong(notBefore);
            output.writeLong(notAfter);
            writeStringList(output, actorNames);
            writeStringList(output, excludeActorNames);
            writeEventTypeList(output, actionFilter);
            writeStringList(output, includeTargets);
            writeStringList(output, excludeTargets);
            writeNullableString(output, description);
        }

        private static UndoOperation read(DataInputStream input) throws IOException {
            return new UndoOperation(
                input.readBoolean(),
                input.readBoolean(),
                readNullableString(input),
                readBounds(input),
                input.readLong(),
                input.readLong(),
                readStringList(input),
                readStringList(input),
                readEventTypeList(input),
                readStringList(input),
                readStringList(input),
                readNullableString(input)
            );
        }

        private static void writeBounds(DataOutputStream output, QueryBounds bounds) throws IOException {
            output.writeBoolean(bounds != null);
            if (bounds == null) {
                return;
            }
            writeNullableString(output, bounds.worldKey());
            writeBlockPos(output, bounds.minimum());
            writeBlockPos(output, bounds.maximum());
            writeExactPositions(output, bounds.exactPositionKeys());
        }

        private static QueryBounds readBounds(DataInputStream input) throws IOException {
            if (!input.readBoolean()) {
                return null;
            }
            String worldKey = readNullableString(input);
            BlockPos minimum = readBlockPos(input);
            BlockPos maximum = readBlockPos(input);
            long[] exactPositions = readExactPositions(input);
            if (worldKey == null || minimum == null || maximum == null) {
                return null;
            }
            return new QueryBounds(worldKey, minimum, maximum, exactPositions);
        }

        private static void writeExactPositions(DataOutputStream output, long[] exactPositions) throws IOException {
            if (exactPositions == null) {
                output.writeInt(-1);
                return;
            }
            output.writeInt(exactPositions.length);
            for (long exactPosition : exactPositions) {
                output.writeLong(exactPosition);
            }
        }

        private static long[] readExactPositions(DataInputStream input) throws IOException {
            int size = input.readInt();
            if (size < 0) {
                return null;
            }
            long[] exactPositions = new long[size];
            for (int index = 0; index < size; index++) {
                exactPositions[index] = input.readLong();
            }
            return exactPositions;
        }

        private static void writeBlockPos(DataOutputStream output, BlockPos pos) throws IOException {
            output.writeBoolean(pos != null);
            if (pos == null) {
                return;
            }
            output.writeInt(pos.getX());
            output.writeInt(pos.getY());
            output.writeInt(pos.getZ());
        }

        private static BlockPos readBlockPos(DataInputStream input) throws IOException {
            if (!input.readBoolean()) {
                return null;
            }
            return new BlockPos(input.readInt(), input.readInt(), input.readInt());
        }

        private static void writeNullableString(DataOutputStream output, String value) throws IOException {
            output.writeBoolean(value != null);
            if (value != null) {
                output.writeUTF(value);
            }
        }

        private static String readNullableString(DataInputStream input) throws IOException {
            return input.readBoolean() ? input.readUTF() : null;
        }

        private static void writeStringList(DataOutputStream output, List<String> values) throws IOException {
            if (values == null) {
                output.writeInt(-1);
                return;
            }
            output.writeInt(values.size());
            for (String value : values) {
                output.writeUTF(value == null ? "" : value);
            }
        }

        private static List<String> readStringList(DataInputStream input) throws IOException {
            int size = input.readInt();
            if (size < 0) {
                return null;
            }
            List<String> values = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                values.add(input.readUTF());
            }
            return values;
        }

        private static void writeEventTypeList(DataOutputStream output, List<CoreProtectEventType> values) throws IOException {
            if (values == null) {
                output.writeInt(-1);
                return;
            }
            output.writeInt(values.size());
            for (CoreProtectEventType value : values) {
                output.writeUTF(value.name());
            }
        }

        private static List<CoreProtectEventType> readEventTypeList(DataInputStream input) throws IOException {
            int size = input.readInt();
            if (size < 0) {
                return null;
            }
            List<CoreProtectEventType> values = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                values.add(CoreProtectEventType.valueOf(input.readUTF()));
            }
            return values;
        }
    }
}
