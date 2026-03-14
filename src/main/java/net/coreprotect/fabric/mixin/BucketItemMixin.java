package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.hook.BucketItemHooks;
import net.coreprotect.fabric.listener.player.PlayerBucketEmptyListener;
import net.coreprotect.fabric.listener.player.PlayerBucketFillListener;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.BucketItem;
import net.minecraft.item.EntityBucketItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
    @Shadow
    @Final
    private Fluid fluid;

    @Inject(method = "use", at = @At("HEAD"))
    private void coreprotect$captureBucketUse(World world, PlayerEntity user, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (!(world instanceof ServerWorld serverWorld) || !(user instanceof ServerPlayerEntity serverPlayer)) {
            BucketItemHooks.clear();
            return;
        }

        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || !runtime.config(serverWorld).logBuckets()) {
            BucketItemHooks.clear();
            return;
        }

        if ((Object) this instanceof EntityBucketItem) {
            BucketItemHooks.setEntityContext(new BucketItemHooks.EntityBucketContext(serverPlayer));
        }
        else {
            BucketItemHooks.clearEntityContext();
        }

        RaycastContext.FluidHandling fluidHandling = fluid == Fluids.EMPTY
            ? RaycastContext.FluidHandling.SOURCE_ONLY
            : RaycastContext.FluidHandling.NONE;
        BlockHitResult hitResult = ItemAccessor.coreprotect$invokeRaycast(world, user, fluidHandling);
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            BucketItemHooks.clearBucketContext();
            return;
        }

        BlockPos blockPos = hitResult.getBlockPos();
        if (fluid == Fluids.EMPTY) {
            BucketItemHooks.setBucketContext(new BucketItemHooks.BucketUseContext(serverPlayer, serverWorld, true, blockPos.toImmutable(), null, serverWorld.getBlockState(blockPos), null));
            return;
        }

        Direction side = hitResult.getSide();
        BlockPos offsetPos = blockPos.offset(side);
        BucketItemHooks.setBucketContext(new BucketItemHooks.BucketUseContext(serverPlayer, serverWorld, false, blockPos.toImmutable(), offsetPos.toImmutable(), null, fluid));
    }

    @Inject(method = "use", at = @At("RETURN"))
    private void coreprotect$logBucketUse(World world, PlayerEntity user, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        BucketItemHooks.BucketUseContext context = BucketItemHooks.consumeBucketContext();
        BucketItemHooks.clearEntityContext();
        if (context == null || !cir.getReturnValue().isAccepted()) {
            return;
        }

        if (context.fill()) {
            PlayerBucketFillListener.logBucketFill(context.player(), context.world(), context.pos(), context.originalState());
            return;
        }

        BlockPos logPos = resolveBucketEmptyLogPos(context);
        PlayerBucketEmptyListener.logBucketEmpty(context.player(), context.world(), logPos, context.fluid());
    }

    @Unique
    private BlockPos resolveBucketEmptyLogPos(BucketItemHooks.BucketUseContext context) {
        BlockPos primaryPos = context.pos();
        BlockPos alternatePos = context.alternatePos();
        Fluid bucketFluid = context.fluid();
        if (alternatePos == null || bucketFluid == null) {
            return primaryPos;
        }

        BlockState primaryState = context.world().getBlockState(primaryPos);
        if (stateReflectsBucketFluid(primaryState, bucketFluid)) {
            return primaryPos;
        }

        BlockState alternateState = context.world().getBlockState(alternatePos);
        if (stateReflectsBucketFluid(alternateState, bucketFluid)) {
            return alternatePos;
        }

        return primaryPos;
    }

    @Unique
    private boolean stateReflectsBucketFluid(BlockState state, Fluid fluid) {
        return !state.getFluidState().isEmpty() && state.getFluidState().getFluid() == fluid;
    }
}
