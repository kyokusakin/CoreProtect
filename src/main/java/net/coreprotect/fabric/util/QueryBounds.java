package net.coreprotect.fabric.util;

import net.minecraft.util.math.BlockPos;

import java.util.Arrays;

public final class QueryBounds {
    private final String worldKey;
    private final BlockPos minimum;
    private final BlockPos maximum;
    private final long[] exactPositionKeys;

    public QueryBounds(String worldKey, BlockPos minimum, BlockPos maximum) {
        this(worldKey, minimum, maximum, null);
    }

    public QueryBounds(String worldKey, BlockPos minimum, BlockPos maximum, long[] exactPositionKeys) {
        this.worldKey = worldKey;
        this.minimum = minimum == null ? null : minimum.toImmutable();
        this.maximum = maximum == null ? null : maximum.toImmutable();
        if (exactPositionKeys == null || exactPositionKeys.length == 0) {
            this.exactPositionKeys = null;
        }
        else {
            this.exactPositionKeys = Arrays.copyOf(exactPositionKeys, exactPositionKeys.length);
            Arrays.sort(this.exactPositionKeys);
        }
    }

    public String worldKey() {
        return worldKey;
    }

    public BlockPos minimum() {
        return minimum;
    }

    public BlockPos maximum() {
        return maximum;
    }

    public boolean hasExactPositions() {
        return exactPositionKeys != null && exactPositionKeys.length > 0;
    }

    public int exactPositionCount() {
        return exactPositionKeys == null ? 0 : exactPositionKeys.length;
    }

    public long[] exactPositionKeys() {
        return exactPositionKeys == null ? null : Arrays.copyOf(exactPositionKeys, exactPositionKeys.length);
    }

    public boolean contains(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        if (hasExactPositions()) {
            return Arrays.binarySearch(exactPositionKeys, pos.asLong()) >= 0;
        }
        return containsWithinBounds(pos);
    }

    public boolean containsWithinBounds(BlockPos pos) {
        if (pos == null || minimum == null || maximum == null) {
            return false;
        }
        return pos.getX() >= minimum.getX() && pos.getX() <= maximum.getX()
            && pos.getY() >= minimum.getY() && pos.getY() <= maximum.getY()
            && pos.getZ() >= minimum.getZ() && pos.getZ() <= maximum.getZ();
    }
}
