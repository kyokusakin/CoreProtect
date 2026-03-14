package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.world.PortalCreateListener;
import net.coreprotect.fabric.util.PortalCreateContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.dimension.NetherPortal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetherPortal.class)
public abstract class NetherPortalMixin {
    @Inject(method = "createPortal", at = @At("HEAD"))
    private void coreprotect$beginPortalCreate(WorldAccess world, CallbackInfo ci) {
        if (world instanceof ServerWorld serverWorld) {
            PortalCreateContext.begin(serverWorld);
        }
    }

    @Inject(method = "createPortal", at = @At("RETURN"))
    private void coreprotect$endPortalCreate(WorldAccess world, CallbackInfo ci) {
        PortalCreateListener.logCompletedPortal(PortalCreateContext.end());
    }
}
