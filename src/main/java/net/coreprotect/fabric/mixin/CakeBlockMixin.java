package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.player.FoodLevelChangeListener;
import net.minecraft.block.BlockState;
import net.minecraft.block.CakeBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CakeBlock.class)
public abstract class CakeBlockMixin {
    @Unique
    private static final ThreadLocal<CakeEatContext> coreprotect$cakeEat = new ThreadLocal<>();

    @Inject(method = "tryEat", at = @At("HEAD"))
    private static void coreprotect$captureCakeEat(WorldAccess world, BlockPos pos, BlockState state, PlayerEntity player, CallbackInfoReturnable<ActionResult> cir) {
        if (!(world instanceof ServerWorld serverWorld) || !(player instanceof ServerPlayerEntity serverPlayer)) {
            coreprotect$cakeEat.remove();
            return;
        }
        coreprotect$cakeEat.set(new CakeEatContext(serverPlayer, serverWorld, pos.toImmutable(), state));
    }

    @Inject(method = "tryEat", at = @At("RETURN"))
    private static void coreprotect$logCakeEat(WorldAccess world, BlockPos pos, BlockState state, PlayerEntity player, CallbackInfoReturnable<ActionResult> cir) {
        CakeEatContext context = coreprotect$cakeEat.get();
        coreprotect$cakeEat.remove();
        if (context == null || !cir.getReturnValue().isAccepted()) {
            return;
        }

        FoodLevelChangeListener.logCakeEat(
            context.player(),
            context.world(),
            context.pos(),
            context.previousState(),
            context.world().getBlockState(context.pos())
        );
    }

    @Unique
    private record CakeEatContext(ServerPlayerEntity player, ServerWorld world, BlockPos pos, BlockState previousState) {
    }
}
