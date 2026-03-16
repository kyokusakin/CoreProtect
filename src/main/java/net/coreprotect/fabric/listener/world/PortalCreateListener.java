package net.coreprotect.fabric.listener.world;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.util.PortalCreateContext;
import net.coreprotect.fabric.util.TransientLookupCache;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;

public final class PortalCreateListener {
    private PortalCreateListener() {
    }

    public static void logCompletedPortal(PortalCreateContext.CompletedPortal portal) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || portal == null || !runtime.config(portal.world()).portals()) {
            return;
        }

        String actor = resolveActor(portal);
        for (PortalCreateContext.PortalChange change : portal.changes()) {
            BlockState previousState = change.previousState();
            BlockState currentState = change.currentState();

            if (!previousState.isAir()) {
                runtime.logger().logBlockBreak(null, actor, portal.world(), change.pos(), previousState);
            }
            if (!currentState.isAir()) {
                runtime.logger().logBlockPlace(null, actor, portal.world(), change.pos(), currentState);
            }
        }
    }

    private static String resolveActor(PortalCreateContext.CompletedPortal portal) {
        String worldKey = portal.world().getRegistryKey().getValue().toString();
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();

        for (PortalCreateContext.PortalChange change : portal.changes()) {
            if (change.previousState().isIn(BlockTags.FIRE) || change.currentState().isIn(BlockTags.FIRE) || change.currentState().isOf(Blocks.NETHER_PORTAL)) {
                String actor = TransientLookupCache.findPlacedActor(
                    worldKey,
                    change.pos(),
                    "minecraft:fire",
                    "minecraft:soul_fire",
                    "minecraft:nether_portal"
                );
                if (actor != null && !actor.isBlank()) {
                    return actor;
                }
                actor = runtime.database().lookupLatestBlockPlaceActor(worldKey, change.pos());
                if (actor != null && !actor.isBlank()) {
                    return actor;
                }
            }
        }

        return "#portal";
    }
}
