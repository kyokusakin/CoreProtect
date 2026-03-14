package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.entity.EntityDamageByEntityListener;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.VehicleEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VehicleEntity.class)
public abstract class VehicleEntityMixin {
    @Unique
    private boolean coreprotect$aliveBeforeDamage;

    @Inject(method = "damage", at = @At("HEAD"))
    private void coreprotect$captureVehicleDamage(ServerWorld world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        VehicleEntity vehicle = (VehicleEntity) (Object) this;
        if (!(vehicle instanceof AbstractBoatEntity) && !(vehicle instanceof AbstractMinecartEntity)) {
            coreprotect$aliveBeforeDamage = false;
            return;
        }

        coreprotect$aliveBeforeDamage = vehicle.isAlive();
    }

    @Inject(method = "damage", at = @At("RETURN"))
    private void coreprotect$logVehicleBreak(ServerWorld world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        VehicleEntity vehicle = (VehicleEntity) (Object) this;
        if (!(vehicle instanceof AbstractBoatEntity) && !(vehicle instanceof AbstractMinecartEntity)) {
            return;
        }
        if (!cir.getReturnValueZ()) {
            return;
        }
        if (!coreprotect$aliveBeforeDamage || vehicle.isAlive()) {
            return;
        }

        EntityDamageByEntityListener.logEntityBreak(world, vehicle.getBlockPos(), vehicle, source.getAttacker());
        coreprotect$aliveBeforeDamage = false;
    }
}
