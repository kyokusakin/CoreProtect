package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.player.PlayerDeathListener;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
    @Unique
    private Map<LoggedItemData, Integer> coreprotect$beforeDeathInventory = Map.of();

    @Inject(method = "onDeath", at = @At("HEAD"))
    private void coreprotect$captureDeathInventory(DamageSource damageSource, CallbackInfo ci) {
        coreprotect$beforeDeathInventory = ItemDeltaSnapshot.snapshotPlayerInventory((ServerPlayerEntity) (Object) this);
    }

    @Inject(method = "onDeath", at = @At("RETURN"))
    private void coreprotect$logDeathDrops(DamageSource damageSource, CallbackInfo ci) {
        try {
            ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
            PlayerDeathListener.logDeathDrops(player, coreprotect$beforeDeathInventory, ItemDeltaSnapshot.snapshotPlayerInventory(player));
        }
        finally {
            coreprotect$beforeDeathInventory = Map.of();
        }
    }
}
