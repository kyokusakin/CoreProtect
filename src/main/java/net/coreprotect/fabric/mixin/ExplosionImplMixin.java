package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.entity.EntityExplodeListener;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.explosion.ExplosionImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ExplosionImpl.class)
public abstract class ExplosionImplMixin {
    @Shadow
    public abstract ServerWorld getWorld();

    @Shadow
    public abstract Entity getEntity();

    @Inject(method = "destroyBlocks", at = @At("HEAD"))
    private void coreprotect$logExplosionBlocks(List<BlockPos> blocks, CallbackInfo ci) {
        EntityExplodeListener.logExplosion(getWorld(), getEntity(), blocks);
    }
}
