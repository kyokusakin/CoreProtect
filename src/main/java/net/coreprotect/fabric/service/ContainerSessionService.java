package net.coreprotect.fabric.service;

import net.coreprotect.fabric.util.BlockStateSerializer;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LecternBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ContainerSessionService {
    private static final long CONTEXT_TTL_MS = 15_000L;
    private static final double MAX_CONTEXT_DISTANCE_SQUARED = 144.0D;

    private final Map<UUID, ContainerContext> contexts = new ConcurrentHashMap<>();

    public void trackPotentialAccess(ServerPlayerEntity player, ServerWorld world, BlockPos pos, BlockState state) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        int containerSlots = resolveContainerSlots(blockEntity);
        if (containerSlots <= 0) {
            return;
        }

        contexts.put(player.getUuid(), new ContainerContext(
            world.getRegistryKey().getValue().toString(),
            pos.toImmutable(),
            BlockStateSerializer.describeBlock(state),
            containerSlots,
            System.currentTimeMillis()
        ));
    }

    public ContainerContext getContext(ServerPlayerEntity player) {
        if (player == null) {
            return null;
        }

        if (player.currentScreenHandler == null || player.currentScreenHandler == player.playerScreenHandler) {
            contexts.remove(player.getUuid());
            return null;
        }

        int actualContainerSlots = countContainerSlots(player);
        if (actualContainerSlots <= 0) {
            contexts.remove(player.getUuid());
            return null;
        }

        ContainerContext context = contexts.get(player.getUuid());
        if (context == null) {
            return null;
        }

        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            contexts.remove(player.getUuid(), context);
            return null;
        }

        if (!context.worldKey().equals(world.getRegistryKey().getValue().toString())) {
            contexts.remove(player.getUuid(), context);
            return null;
        }

        if (System.currentTimeMillis() - context.createdAt() > CONTEXT_TTL_MS) {
            contexts.remove(player.getUuid(), context);
            return null;
        }

        if (player.squaredDistanceTo(
            context.pos().getX() + 0.5D,
            context.pos().getY() + 0.5D,
            context.pos().getZ() + 0.5D
        ) > MAX_CONTEXT_DISTANCE_SQUARED) {
            contexts.remove(player.getUuid(), context);
            return null;
        }

        BlockEntity blockEntity = world.getBlockEntity(context.pos());
        int expectedContainerSlots = resolveContainerSlots(blockEntity);
        if (expectedContainerSlots <= 0) {
            contexts.remove(player.getUuid(), context);
            return null;
        }
        if (context.containerSlots() != expectedContainerSlots || expectedContainerSlots != actualContainerSlots) {
            contexts.remove(player.getUuid(), context);
            return null;
        }

        String containerType = BlockStateSerializer.describeBlock(world.getBlockState(context.pos()));
        if (!containerType.equals(context.containerType())) {
            context = new ContainerContext(context.worldKey(), context.pos(), containerType, context.containerSlots(), context.createdAt());
            contexts.put(player.getUuid(), context);
        }

        return context;
    }

    public void clear(ServerPlayerEntity player) {
        contexts.remove(player.getUuid());
    }

    private int resolveContainerSlots(BlockEntity blockEntity) {
        if (blockEntity instanceof LecternBlockEntity) {
            return 1;
        }
        if (blockEntity instanceof Inventory inventory) {
            return inventory.size();
        }
        return 0;
    }

    private int countContainerSlots(ServerPlayerEntity player) {
        ScreenHandler handler = player.currentScreenHandler;
        if (handler == null || handler == player.playerScreenHandler) {
            return 0;
        }

        int count = 0;
        for (int index = 0; index < handler.slots.size(); index++) {
            if (handler.getSlot(index).inventory != player.getInventory()) {
                count++;
            }
        }
        return count;
    }

    public static final class ContainerContext {
        private final String worldKey;
        private final BlockPos pos;
        private final String containerType;
        private final int containerSlots;
        private final long createdAt;

        public ContainerContext(String worldKey, BlockPos pos, String containerType, int containerSlots, long createdAt) {
            this.worldKey = worldKey;
            this.pos = pos;
            this.containerType = containerType;
            this.containerSlots = containerSlots;
            this.createdAt = createdAt;
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

        public int containerSlots() {
            return containerSlots;
        }

        public long createdAt() {
            return createdAt;
        }
    }
}
