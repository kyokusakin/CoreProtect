package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(PersistentProjectileEntity.class)
public abstract class PersistentProjectileEntityMixin {
    @Unique
    private Map<LoggedItemData, Integer> coreprotect$beforeInventory = Map.of();

    @Inject(method = "onPlayerCollision", at = @At("HEAD"))
    private void coreprotect$captureArrowPickup(PlayerEntity player, CallbackInfo ci) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            coreprotect$beforeInventory = ItemDeltaSnapshot.snapshotPlayerInventory(serverPlayer);
        }
        else {
            coreprotect$beforeInventory = Map.of();
        }
    }

    @Inject(method = "onPlayerCollision", at = @At("RETURN"))
    private void coreprotect$logArrowPickup(PlayerEntity player, CallbackInfo ci) {
        try {
            if (!(player instanceof ServerPlayerEntity serverPlayer) || !(serverPlayer.getEntityWorld() instanceof ServerWorld serverWorld)) {
                return;
            }

            for (ItemDeltaSnapshot.ItemDelta delta : ItemDeltaSnapshot.diff(coreprotect$beforeInventory, ItemDeltaSnapshot.snapshotPlayerInventory(serverPlayer))) {
                if (delta.delta() <= 0) {
                    continue;
                }
                CoreProtectFabricMod.logItemPickup(
                    serverPlayer,
                    serverWorld.getRegistryKey().getValue().toString(),
                    ((PersistentProjectileEntity) (Object) this).getBlockPos(),
                    delta.item(),
                    delta.delta(),
                    "projectile_pickup"
                );
            }
        }
        finally {
            coreprotect$beforeInventory = Map.of();
        }
    }
}
