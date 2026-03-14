package net.coreprotect.fabric.service;

import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PreviewService {
    private final Map<UUID, PreviewSession> sessions = new ConcurrentHashMap<>();

    public void show(ServerPlayerEntity player, List<PreviewBlockChange> changes) {
        clear(player);
        if (player == null || changes == null || changes.isEmpty()) {
            return;
        }

        Map<PreviewKey, PreviewBlockChange> deduplicated = new LinkedHashMap<>();
        for (PreviewBlockChange change : changes) {
            deduplicated.put(new PreviewKey(change.worldKey(), change.pos()), change);
        }

        List<PreviewBlockChange> previewed = new ArrayList<>(deduplicated.values());
        sessions.put(player.getUuid(), new PreviewSession(previewed));

        String currentWorldKey = worldKey(player);
        for (PreviewBlockChange change : previewed) {
            if (!currentWorldKey.equals(change.worldKey())) {
                continue;
            }
            player.networkHandler.sendPacket(new BlockUpdateS2CPacket(change.pos(), change.state()));
        }
    }

    public boolean clear(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }

        PreviewSession session = sessions.remove(player.getUuid());
        if (session == null) {
            return false;
        }

        String currentWorldKey = worldKey(player);
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        for (PreviewBlockChange change : session.changes()) {
            if (!currentWorldKey.equals(change.worldKey())) {
                continue;
            }
            player.networkHandler.sendPacket(new BlockUpdateS2CPacket(change.pos(), world.getBlockState(change.pos())));
        }
        return true;
    }

    public void clear(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        sessions.remove(playerUuid);
    }

    private String worldKey(ServerPlayerEntity player) {
        return ((ServerWorld) player.getEntityWorld()).getRegistryKey().getValue().toString();
    }

    public static final class PreviewBlockChange {
        private final String worldKey;
        private final BlockPos pos;
        private final BlockState state;

        public PreviewBlockChange(String worldKey, BlockPos pos, BlockState state) {
            this.worldKey = worldKey;
            this.pos = pos.toImmutable();
            this.state = state;
        }

        public String worldKey() {
            return worldKey;
        }

        public BlockPos pos() {
            return pos;
        }

        public BlockState state() {
            return state;
        }
    }

    private static final class PreviewSession {
        private final List<PreviewBlockChange> changes;

        private PreviewSession(List<PreviewBlockChange> changes) {
            this.changes = List.copyOf(changes);
        }

        private List<PreviewBlockChange> changes() {
            return changes;
        }
    }

    private static final class PreviewKey {
        private final String worldKey;
        private final BlockPos pos;

        private PreviewKey(String worldKey, BlockPos pos) {
            this.worldKey = worldKey;
            this.pos = pos.toImmutable();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PreviewKey key)) {
                return false;
            }
            return worldKey.equals(key.worldKey) && pos.equals(key.pos);
        }

        @Override
        public int hashCode() {
            return 31 * worldKey.hashCode() + pos.hashCode();
        }
    }
}
