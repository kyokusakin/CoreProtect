package net.coreprotect.fabric.service;

import net.coreprotect.fabric.util.BlockStateSerializer;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LecternBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ContainerSessionService {
    private final Map<UUID, ContainerContext> contexts = new ConcurrentHashMap<>();

    public void trackPotentialAccess(ServerPlayerEntity player, ServerWorld world, BlockPos pos, BlockState state) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof Inventory) && !(blockEntity instanceof LecternBlockEntity)) {
            return;
        }

        contexts.put(player.getUuid(), new ContainerContext(
            world.getRegistryKey().getValue().toString(),
            pos.toImmutable(),
            BlockStateSerializer.describeBlock(state)
        ));
    }

    public ContainerContext getContext(ServerPlayerEntity player) {
        return contexts.get(player.getUuid());
    }

    public void clear(ServerPlayerEntity player) {
        contexts.remove(player.getUuid());
    }

    public static final class ContainerContext {
        private final String worldKey;
        private final BlockPos pos;
        private final String containerType;

        public ContainerContext(String worldKey, BlockPos pos, String containerType) {
            this.worldKey = worldKey;
            this.pos = pos;
            this.containerType = containerType;
        }

        public String worldKey() {
            return worldKey;
        }

        public BlockPos pos() {
            return pos;
        }

        public String containerType() {
            return containerType;
        }
    }
}
