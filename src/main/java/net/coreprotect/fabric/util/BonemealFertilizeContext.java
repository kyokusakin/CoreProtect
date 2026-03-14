package net.coreprotect.fabric.util;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BonemealFertilizeContext {
    private static final ThreadLocal<Deque<Session>> SESSIONS = ThreadLocal.withInitial(ArrayDeque::new);

    private BonemealFertilizeContext() {
    }

    public static void begin(ServerWorld world, String actor, BlockPos originPos, BlockState originState) {
        if (world == null || actor == null || actor.isBlank() || originPos == null || originState == null) {
            return;
        }
        SESSIONS.get().push(new Session(world, actor, originPos.toImmutable(), originState));
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

    public static CompletedFertilize end(boolean success) {
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
        return new CompletedFertilize(session.world(), session.actor(), session.originPos(), session.originState(), session.changes().values());
    }

    public static final class CompletedFertilize {
        private final ServerWorld world;
        private final String actor;
        private final BlockPos originPos;
        private final BlockState originState;
        private final Collection<FertilizeChange> changes;

        public CompletedFertilize(ServerWorld world, String actor, BlockPos originPos, BlockState originState, Collection<FertilizeChange> changes) {
            this.world = world;
            this.actor = actor;
            this.originPos = originPos;
            this.originState = originState;
            this.changes = changes;
        }

        public ServerWorld world() { return world; }
        public String actor() { return actor; }
        public BlockPos originPos() { return originPos; }
        public BlockState originState() { return originState; }
        public Collection<FertilizeChange> changes() { return changes; }
    }

    public static final class FertilizeChange {
        private final BlockPos pos;
        private final BlockState previousState;
        private BlockState currentState;

        public FertilizeChange(BlockPos pos, BlockState previousState, BlockState currentState) {
            this.pos = pos;
            this.previousState = previousState;
            this.currentState = currentState;
        }

        public BlockPos pos() { return pos; }
        public BlockState previousState() { return previousState; }
        public BlockState currentState() { return currentState; }
        private void setCurrentState(BlockState currentState) { this.currentState = currentState; }
    }

    private static final class Session {
        private final ServerWorld world;
        private final String actor;
        private final BlockPos originPos;
        private final BlockState originState;
        private final Map<String, FertilizeChange> changes = new LinkedHashMap<>();

        private Session(ServerWorld world, String actor, BlockPos originPos, BlockState originState) {
            this.world = world;
            this.actor = actor;
            this.originPos = originPos;
            this.originState = originState;
        }

        private ServerWorld world() { return world; }
        private String actor() { return actor; }
        private BlockPos originPos() { return originPos; }
        private BlockState originState() { return originState; }
        private Map<String, FertilizeChange> changes() { return changes; }

        private void record(BlockPos pos, BlockState previousState, BlockState currentState) {
            String key = pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
            FertilizeChange existing = changes.get(key);
            if (existing == null) {
                if (!previousState.equals(currentState)) {
                    changes.put(key, new FertilizeChange(pos, previousState, currentState));
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
