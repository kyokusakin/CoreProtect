package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.listener.entity.EntityDamageByBlockListener;
import net.coreprotect.fabric.listener.entity.EntityDamageByEntityListener;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EndCrystalEntity.class)
public abstract class EndCrystalEntityMixin {
    @Unique
    private boolean coreprotect$aliveBeforeDamage;
    @Unique
    private boolean coreprotect$loggedBreak;

    @Inject(method = "damage", at = @At("HEAD"))
    private void coreprotect$captureCrystalDamage(ServerWorld world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        EndCrystalEntity endCrystal = (EndCrystalEntity) (Object) this;
        coreprotect$aliveBeforeDamage = endCrystal.isAlive();
        coreprotect$loggedBreak = false;
    }

    @Inject(method = "damage", at = @At("RETURN"))
    private void coreprotect$logCrystalBreak(ServerWorld world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        EndCrystalEntity endCrystal = (EndCrystalEntity) (Object) this;
        if (!cir.getReturnValueZ()) {
            return;
        }
        if (!coreprotect$aliveBeforeDamage || endCrystal.isAlive() || coreprotect$loggedBreak) {
            return;
        }

        if (source.getAttacker() == null) {
            CoreProtectFabricMod.getRuntime().logger().logEntityBreak(EntityDamageByBlockListener.actorFromDamageSource(source), world, endCrystal.getBlockPos(), endCrystal);
        }
        else {
            EntityDamageByEntityListener.logEntityBreak(world, endCrystal.getBlockPos(), endCrystal, source.getAttacker());
        }
        coreprotect$loggedBreak = true;
        coreprotect$aliveBeforeDamage = false;
    }

    @Inject(method = "kill", at = @At("HEAD"))
    private void coreprotect$logCrystalKill(ServerWorld world, CallbackInfo ci) {
        EndCrystalEntity endCrystal = (EndCrystalEntity) (Object) this;
        if (!endCrystal.isAlive() || coreprotect$loggedBreak) {
            return;
        }

        EntityDamageByEntityListener.logEntityBreak(world, endCrystal.getBlockPos(), endCrystal, null);
        coreprotect$loggedBreak = true;
    }
}
