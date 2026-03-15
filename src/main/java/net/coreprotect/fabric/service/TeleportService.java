package net.coreprotect.fabric.service;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Set;

public final class TeleportService {
    private TeleportService() {
    }

    public static TeleportResult teleport(ServerPlayerEntity player, ServerWorld world, double x, double y, double z, boolean preserveRequestedY) {
        double targetY = preserveRequestedY ? y : Math.min(y, 63.0D);
        int safeY = player.isSpectator() ? BlockPos.ofFloored(x, targetY, z).getY() : findSafeY(world, x, targetY, z);
        BlockPos safePos = BlockPos.ofFloored(x, safeY, z);
        world.getChunk(safePos);
        player.teleport(world, x, safeY, z, Set.of(), player.getYaw(), player.getPitch(), false);
        return new TeleportResult(world, x, safeY, z);
    }

    private static int findSafeY(ServerWorld world, double x, double y, double z) {
        int minY = world.getBottomY();
        int maxY = world.getTopYInclusive();
        int startY = Math.max(minY, Math.min(maxY, BlockPos.ofFloored(x, y, z).getY()));
        int maxDistance = Math.max(startY - minY, maxY - startY);

        for (int distance = 0; distance <= maxDistance; distance++) {
            int upY = startY + distance;
            if (upY <= maxY && isSafeLanding(world, x, upY, z)) {
                return upY;
            }

            if (distance == 0) {
                continue;
            }

            int downY = startY - distance;
            if (downY >= minY && isSafeLanding(world, x, downY, z)) {
                return downY;
            }
        }

        return Math.max(minY, Math.min(maxY, startY));
    }

    private static boolean isSafeLanding(ServerWorld world, double x, int y, double z) {
        BlockPos feetPos = BlockPos.ofFloored(x, y, z);
        BlockPos headPos = feetPos.up();
        BlockPos groundPos = feetPos.down();

        if (!isPassable(world, feetPos) || !isPassable(world, headPos)) {
            return false;
        }

        if (!hasSolidGround(world, groundPos)) {
            return false;
        }

        BlockState groundState = world.getBlockState(groundPos);
        if (isUnsafe(groundState)) {
            return false;
        }

        return world.getFluidState(feetPos).isEmpty() && world.getFluidState(headPos).isEmpty();
    }

    private static boolean isPassable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getCollisionShape(world, pos).isEmpty() && world.getFluidState(pos).isEmpty();
    }

    private static boolean hasSolidGround(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean isUnsafe(BlockState state) {
        return state.isOf(Blocks.LAVA)
            || state.isIn(BlockTags.FIRE)
            || state.isOf(Blocks.CACTUS)
            || state.isOf(Blocks.MAGMA_BLOCK)
            || state.isOf(Blocks.SWEET_BERRY_BUSH)
            || state.isOf(Blocks.WITHER_ROSE)
            || state.isOf(Blocks.POWDER_SNOW)
            || (state.isOf(Blocks.CAMPFIRE) && state.get(CampfireBlock.LIT))
            || (state.isOf(Blocks.SOUL_CAMPFIRE) && state.get(CampfireBlock.LIT));
    }

    public record TeleportResult(ServerWorld world, double x, double y, double z) {
    }
}
