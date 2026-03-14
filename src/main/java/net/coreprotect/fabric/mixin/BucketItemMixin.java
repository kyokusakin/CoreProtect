package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.listener.player.PlayerBucketEmptyListener;
import net.coreprotect.fabric.listener.player.PlayerBucketFillListener;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidFillable;
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
    @Unique
    private static final ThreadLocal<BucketUseContext> coreprotect$bucketUseContext = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<EntityBucketContext> coreprotect$entityBucketContext = new ThreadLocal<>();

    @Shadow
    @Final
    private Fluid fluid;

    @Shadow
    protected static BlockHitResult raycast(World world, PlayerEntity player, RaycastContext.FluidHandling fluidHandling) {
        throw new AssertionError();
    }

    @Inject(method = "use", at = @At("HEAD"))
    private void coreprotect$captureBucketUse(World world, PlayerEntity user, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (!(world instanceof ServerWorld serverWorld) || !(user instanceof ServerPlayerEntity serverPlayer)) {
            coreprotect$bucketUseContext.remove();
            coreprotect$entityBucketContext.remove();
            return;
        }

        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || !runtime.config().logBuckets()) {
            coreprotect$bucketUseContext.remove();
            coreprotect$entityBucketContext.remove();
            return;
        }

        if ((Object) this instanceof EntityBucketItem) {
            coreprotect$entityBucketContext.set(new EntityBucketContext(serverPlayer));
        }
        else {
            coreprotect$entityBucketContext.remove();
        }

        RaycastContext.FluidHandling fluidHandling = fluid == Fluids.EMPTY
            ? RaycastContext.FluidHandling.SOURCE_ONLY
            : RaycastContext.FluidHandling.NONE;
        BlockHitResult hitResult = raycast(world, user, fluidHandling);
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            coreprotect$bucketUseContext.remove();
            return;
        }

        BlockPos blockPos = hitResult.getBlockPos();
        if (fluid == Fluids.EMPTY) {
            coreprotect$bucketUseContext.set(new BucketUseContext(serverPlayer, serverWorld, true, blockPos.toImmutable(), serverWorld.getBlockState(blockPos), null));
            return;
        }

        Direction side = hitResult.getSide();
        BlockPos offsetPos = blockPos.offset(side);
        BlockState clickedState = serverWorld.getBlockState(blockPos);
        Block clickedBlock = clickedState.getBlock();
        BlockPos targetPos = clickedBlock instanceof FluidFillable && fluid == Fluids.WATER ? blockPos : offsetPos;
        coreprotect$bucketUseContext.set(new BucketUseContext(serverPlayer, serverWorld, false, targetPos.toImmutable(), null, fluid));
    }

    @Inject(method = "use", at = @At("RETURN"))
    private void coreprotect$logBucketUse(World world, PlayerEntity user, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        BucketUseContext context = coreprotect$bucketUseContext.get();
        coreprotect$bucketUseContext.remove();
        coreprotect$entityBucketContext.remove();
        if (context == null || !cir.getReturnValue().isAccepted()) {
            return;
        }

        if (context.fill()) {
            PlayerBucketFillListener.logBucketFill(context.player(), context.world(), context.pos(), context.originalState());
            return;
        }

        PlayerBucketEmptyListener.logBucketEmpty(context.player(), context.world(), context.pos(), context.fluid());
    }

    @Unique
    private record BucketUseContext(
        ServerPlayerEntity player,
        ServerWorld world,
        boolean fill,
        BlockPos pos,
        BlockState originalState,
        Fluid fluid
    ) {
    }

    @Unique
    static EntityBucketContext coreprotect$consumeEntityBucketContext() {
        EntityBucketContext context = coreprotect$entityBucketContext.get();
        coreprotect$entityBucketContext.remove();
        return context;
    }

    @Unique
    record EntityBucketContext(ServerPlayerEntity player) {
    }
}
