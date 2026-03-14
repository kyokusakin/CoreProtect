package net.coreprotect.api;

import net.coreprotect.CoreProtect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public final class BlockAPI {
    private BlockAPI() {
        throw new IllegalStateException("API class");
    }

    public static List<String[]> performLookup(ServerWorld world, BlockPos pos, int offset) {
        return CoreProtect.getInstance().getAPI().blockLookup(world, pos, offset);
    }
}
