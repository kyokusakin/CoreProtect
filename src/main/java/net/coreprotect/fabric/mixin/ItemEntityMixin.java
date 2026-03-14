package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Unique
    private Map<LoggedItemData, Integer> coreprotect$beforeInventory = Map.of();

    @Inject(method = "onPlayerCollision", at = @At("HEAD"))
    private void coreprotect$captureInventory(PlayerEntity player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayerEntity)) {
            coreprotect$beforeInventory = Map.of();
            return;
        }

        ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
        coreprotect$beforeInventory = ItemDeltaSnapshot.snapshotPlayerInventory(serverPlayer);
    }

    @Inject(method = "onPlayerCollision", at = @At("RETURN"))
    private void coreprotect$logItemPickup(PlayerEntity player, CallbackInfo ci) {
        try {
            if (!(player instanceof ServerPlayerEntity)) {
                return;
            }

            ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
            if (!(((ItemEntity) (Object) this).getEntityWorld() instanceof ServerWorld)) {
                return;
            }

            ServerWorld serverWorld = (ServerWorld) ((ItemEntity) (Object) this).getEntityWorld();
            for (ItemDeltaSnapshot.ItemDelta delta : ItemDeltaSnapshot.diff(coreprotect$beforeInventory, ItemDeltaSnapshot.snapshotPlayerInventory(serverPlayer))) {
                if (delta.delta() <= 0) {
                    continue;
                }
                CoreProtectFabricMod.logItemPickup(
                    serverPlayer,
                    serverWorld.getRegistryKey().getValue().toString(),
                    ((ItemEntity) (Object) this).getBlockPos(),
                    delta.item(),
                    delta.delta(),
                    null
                );
            }
        }
        finally {
            coreprotect$beforeInventory = Map.of();
        }
    }
}
