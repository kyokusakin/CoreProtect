package net.coreprotect.fabric.util;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StructureGrowContext {
    private static final ThreadLocal<Deque<Session>> SESSIONS = ThreadLocal.withInitial(ArrayDeque::new);

    private StructureGrowContext() {
    }

    public static void begin(ServerWorld world, String actor) {
        if (world == null || actor == null || actor.isBlank()) {
            return;
        }
        SESSIONS.get().push(new Session(world, actor));
    }

    public static String currentActor() {
        Deque<Session> sessions = SESSIONS.get();
        return sessions.isEmpty() ? null : sessions.peek().actor();
    }

    public static boolean isActive() {
        return currentActor() != null;
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

    public static CompletedGrowth end(boolean success) {
        Deque<Session> sessions = SESSIONS.get();
        if (sessions.isEmpty()) {
            return null;
        }

        Session session = sessions.pop();
        if (sessions.isEmpty()) {
            SESSIONS.remove();
        }

        if (!success) {
            return null;
        }

        return new CompletedGrowth(session.world(), session.actor(), session.changes().values());
    }

    public static final class CompletedGrowth {
        private final ServerWorld world;
        private final String actor;
        private final Collection<GrowthChange> changes;

        public CompletedGrowth(ServerWorld world, String actor, Collection<GrowthChange> changes) {
            this.world = world;
            this.actor = actor;
            this.changes = changes;
        }

        public ServerWorld world() {
            return world;
        }

        public String actor() {
            return actor;
        }

        public Collection<GrowthChange> changes() {
            return changes;
        }
    }

    public static final class GrowthChange {
        private final BlockPos pos;
        private final BlockState previousState;
        private BlockState currentState;

        public GrowthChange(BlockPos pos, BlockState previousState, BlockState currentState) {
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
        private final String actor;
        private final Map<String, GrowthChange> changes = new LinkedHashMap<>();

        private Session(ServerWorld world, String actor) {
            this.world = world;
            this.actor = actor;
        }

        private ServerWorld world() {
            return world;
        }

        private String actor() {
            return actor;
        }

        private Map<String, GrowthChange> changes() {
            return changes;
        }

        private void record(BlockPos pos, BlockState previousState, BlockState currentState) {
            String key = pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
            GrowthChange existing = changes.get(key);
            if (existing == null) {
                if (!previousState.equals(currentState)) {
                    changes.put(key, new GrowthChange(pos, previousState, currentState));
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
