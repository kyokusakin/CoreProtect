package net.coreprotect.fabric.listener.entity;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.util.TransientLookupCache;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class HangingBreakByEntityListener {
    private HangingBreakByEntityListener() {
    }

    public static void logHangingBreak(ServerWorld world, BlockPos pos, Entity entity, Entity breaker) {
        if (breaker == null && entity instanceof AbstractDecorationEntity decorationEntity) {
            String worldKey = world.getRegistryKey().getValue().toString();
            String actor = TransientLookupCache.findRemovedActor(worldKey, decorationEntity.getAttachedBlockPos());
            boolean logDrops = false;

            if (actor != null && !actor.isBlank()) {
                logDrops = true;
            }
            else if (!decorationEntity.canStayAttached()) {
                actor = "#physics";
            }
            else {
                actor = "#obstruction";
            }

            if (entity instanceof ItemFrameEntity itemFrameEntity
                && logDrops
                && !itemFrameEntity.getHeldItemStack().isEmpty()
                && CoreProtectFabricMod.getRuntime() != null
                && CoreProtectFabricMod.getRuntime().config(world).itemTransactions()) {
                CoreProtectFabricMod.getRuntime().logger().logItemDrop(actor, worldKey, decorationEntity.getAttachedBlockPos(), net.coreprotect.fabric.util.LoggedItemData.fromStack(itemFrameEntity.getHeldItemStack(), world.getRegistryManager()), itemFrameEntity.getHeldItemStack().getCount(), "item_frame");
            }
            CoreProtectFabricMod.getRuntime().logger().logEntityBreak(actor, world, pos, entity);
            return;
        }

        EntityDamageByEntityListener.logEntityBreak(world, pos, entity, breaker);
    }
}
