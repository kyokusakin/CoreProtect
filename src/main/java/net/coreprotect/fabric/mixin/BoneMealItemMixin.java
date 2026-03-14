package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.block.BlockFertilizeListener;
import net.coreprotect.fabric.util.BonemealFertilizeContext;
import net.minecraft.block.BlockState;
import net.minecraft.item.BoneMealItem;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BoneMealItem.class)
public abstract class BoneMealItemMixin {
    @Inject(method = "useOnBlock", at = @At("HEAD"))
    private void coreprotect$beginBonemeal(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        if (!(context.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        String actor = context.getPlayer() instanceof ServerPlayerEntity serverPlayer
            ? serverPlayer.getName().getString()
            : "#bonemeal";
        BlockPos originPos = context.getBlockPos();
        BlockState originState = serverWorld.getBlockState(originPos);
        BonemealFertilizeContext.begin(serverWorld, actor, originPos, originState);
    }

    @Inject(method = "useOnBlock", at = @At("RETURN"))
    private void coreprotect$endBonemeal(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        BlockFertilizeListener.logCompletedFertilize(BonemealFertilizeContext.end(cir.getReturnValue().isAccepted()));
    }
}
