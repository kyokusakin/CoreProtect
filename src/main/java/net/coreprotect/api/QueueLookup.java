package net.coreprotect.api;

import net.coreprotect.CoreProtect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public final class QueueLookup {
    private QueueLookup() {
        throw new IllegalStateException("API class");
    }

    public static List<String[]> performLookup(ServerWorld world, BlockPos pos) {
        return CoreProtect.getInstance().getAPI().queueLookup(world, pos);
    }
}
