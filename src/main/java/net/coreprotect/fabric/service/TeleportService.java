package net.coreprotect.fabric.service;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
        int feetY = startY;

        while (feetY <= maxY) {
            BlockPos feetPos = BlockPos.ofFloored(x, feetY, z);
            BlockPos headPos = feetPos.up();
            BlockPos groundPos = feetPos.down();
            if (isPassable(world, feetPos) && isPassable(world, headPos) && !isUnsafe(world.getBlockState(groundPos))) {
                return feetY;
            }
            feetY++;
        }

        return Math.max(minY, Math.min(maxY, startY));
    }

    private static boolean isPassable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean isUnsafe(BlockState state) {
        return state.isOf(Blocks.LAVA) || state.isIn(BlockTags.FIRE);
    }

    public record TeleportResult(ServerWorld world, double x, double y, double z) {
    }
}
