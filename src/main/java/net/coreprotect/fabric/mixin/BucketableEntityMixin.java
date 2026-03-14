package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.entity.EntityDamageByEntityListener;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.AxolotlEntity;
import net.minecraft.entity.passive.FishEntity;
import net.minecraft.entity.passive.TadpoleEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({ AxolotlEntity.class, FishEntity.class, TadpoleEntity.class })
public abstract class BucketableEntityMixin {
    @Unique
    private ServerWorld coreprotect$bucketWorld;
    @Unique
    private ServerPlayerEntity coreprotect$bucketPlayer;
    @Unique
    private BlockPos coreprotect$bucketPos;

    @Inject(method = "interactMob", at = @At("HEAD"))
    private void coreprotect$captureBucketBreak(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        coreprotect$bucketWorld = null;
        coreprotect$bucketPlayer = null;
        coreprotect$bucketPos = null;
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }
        if (!(((LivingEntity) (Object) this).getEntityWorld() instanceof ServerWorld serverWorld)) {
            return;
        }
        coreprotect$bucketWorld = serverWorld;
        coreprotect$bucketPlayer = serverPlayer;
        coreprotect$bucketPos = ((LivingEntity) (Object) this).getBlockPos().toImmutable();
    }

    @Inject(method = "interactMob", at = @At("RETURN"))
    private void coreprotect$logBucketBreak(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        try {
            if (coreprotect$bucketWorld == null || coreprotect$bucketPlayer == null || coreprotect$bucketPos == null) {
                return;
            }
            if (!cir.getReturnValue().isAccepted()) {
                return;
            }

            LivingEntity entity = (LivingEntity) (Object) this;
            if (entity.isAlive()) {
                return;
            }
            EntityDamageByEntityListener.logEntityBreak(coreprotect$bucketWorld, coreprotect$bucketPos, entity, coreprotect$bucketPlayer);
        }
        finally {
            coreprotect$bucketWorld = null;
            coreprotect$bucketPlayer = null;
            coreprotect$bucketPos = null;
        }
    }
}
