package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.entity.EntityDamageByEntityListener;
import net.minecraft.entity.Bucketable;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(Bucketable.class)
public interface BucketableMixin {
    @Unique
    ThreadLocal<BucketCaptureContext> coreprotect$bucketCaptureContext = new ThreadLocal<>();

    @Inject(method = "tryBucket", at = @At("HEAD"))
    private void coreprotect$captureBucketBreak(PlayerEntity player, Hand hand, LivingEntity entity, CallbackInfoReturnable<Optional<?>> cir) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            coreprotect$bucketCaptureContext.remove();
            return;
        }
        if (!(entity.getEntityWorld() instanceof ServerWorld serverWorld)) {
            coreprotect$bucketCaptureContext.remove();
            return;
        }
        coreprotect$bucketCaptureContext.set(new BucketCaptureContext(serverWorld, serverPlayer, entity, entity.getBlockPos().toImmutable()));
    }

    @Inject(method = "tryBucket", at = @At("RETURN"))
    private void coreprotect$logBucketBreak(PlayerEntity player, Hand hand, LivingEntity entity, CallbackInfoReturnable<Optional<?>> cir) {
        try {
            BucketCaptureContext context = coreprotect$bucketCaptureContext.get();
            if (context == null || cir.getReturnValue().isEmpty()) {
                return;
            }
            if (entity.isAlive()) {
                return;
            }
            EntityDamageByEntityListener.logEntityBreak(context.world(), context.pos(), context.entity(), context.player());
        }
        finally {
            coreprotect$bucketCaptureContext.remove();
        }
    }

    @Unique
    record BucketCaptureContext(ServerWorld world, ServerPlayerEntity player, LivingEntity entity, BlockPos pos) {
    }
}
