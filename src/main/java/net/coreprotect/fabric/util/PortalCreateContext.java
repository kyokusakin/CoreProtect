package net.coreprotect.fabric.util;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PortalCreateContext {
    private static final ThreadLocal<Deque<Session>> SESSIONS = ThreadLocal.withInitial(ArrayDeque::new);

    private PortalCreateContext() {
    }

    public static void begin(ServerWorld world) {
        if (world == null) {
            return;
        }
        SESSIONS.get().push(new Session(world));
    }

    public static boolean isActive() {
        return !SESSIONS.get().isEmpty();
    }

    public static void recordChange(ServerWorld world, BlockPos pos, BlockState previousState, BlockState currentState) {
        Deque<Session> sessions = SESSIONS.get();
        if (sessions.isEmpty()) {
            return;
        }

        Session session = sessions.peek();
        if (session.world() != world || pos == null || previousState == null || currentState == null) {
            return;
        }

        session.record(pos.toImmutable(), previousState, currentState);
    }

    public static CompletedPortal end() {
        Deque<Session> sessions = SESSIONS.get();
        if (sessions.isEmpty()) {
            return null;
        }

        Session session = sessions.pop();
        if (sessions.isEmpty()) {
            SESSIONS.remove();
        }
        return new CompletedPortal(session.world(), session.changes().values());
    }

    public static final class CompletedPortal {
        private final ServerWorld world;
        private final Collection<PortalChange> changes;

        public CompletedPortal(ServerWorld world, Collection<PortalChange> changes) {
            this.world = world;
            this.changes = changes;
        }

        public ServerWorld world() {
            return world;
        }

        public Collection<PortalChange> changes() {
            return changes;
        }
    }

    public static final class PortalChange {
        private final BlockPos pos;
        private final BlockState previousState;
        private BlockState currentState;

        public PortalChange(BlockPos pos, BlockState previousState, BlockState currentState) {
            this.pos = pos;
            this.previousState = previousState;
            this.currentState = currentState;
        }

        public BlockPos pos() {
            return pos;
        }

        public BlockState previousState() {
            return previousState;
        }

        public BlockState currentState() {
            return currentState;
        }

        private void setCurrentState(BlockState currentState) {
            this.currentState = currentState;
        }
    }

    private static final class Session {
        private final ServerWorld world;
        private final Map<String, PortalChange> changes = new LinkedHashMap<>();

        private Session(ServerWorld world) {
            this.world = world;
        }

        private ServerWorld world() {
            return world;
        }

        private Map<String, PortalChange> changes() {
            return changes;
        }

        private void record(BlockPos pos, BlockState previousState, BlockState currentState) {
            String key = pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
            PortalChange existing = changes.get(key);
            if (existing == null) {
                if (!previousState.equals(currentState)) {
                    changes.put(key, new PortalChange(pos, previousState, currentState));
                }
                return;
            }

            existing.setCurrentState(currentState);
            if (existing.previousState().equals(currentState)) {
                changes.remove(key);
            }
        }
    }
}
