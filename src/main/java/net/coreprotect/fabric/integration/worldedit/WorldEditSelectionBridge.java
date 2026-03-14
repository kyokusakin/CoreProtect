package net.coreprotect.fabric.integration.worldedit;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import net.coreprotect.fabric.util.QueryBounds;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class WorldEditSelectionBridge {
    private static final long EXACT_SELECTION_LIMIT = 1_000_000L;

    private WorldEditSelectionBridge() {
    }

    public static QueryBounds getSelection(ServerPlayerEntity player) {
        LocalSession session = WorldEdit.getInstance().getSessionManager().getIfPresent(FabricAdapter.adaptPlayer(player));
        if (session == null) {
            throw new IllegalStateException("No WorldEdit session is available for this player.");
        }

        World selectionWorld = session.getSelectionWorld();
        if (selectionWorld == null) {
            throw new IllegalStateException("Complete a WorldEdit selection first.");
        }

        try {
            Region region = session.getSelection(selectionWorld);
            net.minecraft.world.World minecraftWorld = FabricAdapter.adapt(selectionWorld);
            if (!(minecraftWorld instanceof ServerWorld)) {
                throw new IllegalStateException("The selected WorldEdit region is not in a loaded server world.");
            }

            BlockVector3 minimum = region.getMinimumPoint();
            BlockVector3 maximum = region.getMaximumPoint();
            long[] exactPositions = null;
            if (!(region instanceof CuboidRegion)) {
                long volume = region.getVolume();
                if (volume > EXACT_SELECTION_LIMIT) {
                    throw new IllegalStateException("The selected non-cuboid WorldEdit region is too large to inspect exactly.");
                }
                exactPositions = collectExactPositions(region, volume);
            }
            return new QueryBounds(
                ((ServerWorld) minecraftWorld).getRegistryKey().getValue().toString(),
                new BlockPos(minimum.x(), minimum.y(), minimum.z()),
                new BlockPos(maximum.x(), maximum.y(), maximum.z()),
                exactPositions
            );
        }
        catch (IncompleteRegionException exception) {
            throw new IllegalStateException("Complete a WorldEdit selection first.", exception);
        }
    }

    private static long[] collectExactPositions(Region region, long volumeHint) {
        int size = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, volumeHint));
        long[] positions = new long[size];
        int index = 0;
        for (BlockVector3 point : region) {
            if (index >= positions.length) {
                throw new IllegalStateException("The selected WorldEdit region exceeded the supported exact-position limit.");
            }
            positions[index++] = BlockPos.asLong(point.x(), point.y(), point.z());
        }
        if (index == positions.length) {
            return positions;
        }
        long[] trimmed = new long[index];
        System.arraycopy(positions, 0, trimmed, 0, index);
        return trimmed;
    }
}
