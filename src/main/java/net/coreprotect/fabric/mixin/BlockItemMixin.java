package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;Lnet/minecraft/block/BlockState;)Z", at = @At("RETURN"))
    private void coreprotect$logPlacement(ItemPlacementContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }

        if (!(context.getWorld() instanceof ServerWorld)) {
            return;
        }

        if (!(context.getPlayer() instanceof ServerPlayerEntity)) {
            return;
        }

        ServerWorld serverWorld = (ServerWorld) context.getWorld();
        ServerPlayerEntity player = (ServerPlayerEntity) context.getPlayer();
        BlockPos clickedPos = context.getBlockPos();
        BlockPos placedPos = serverWorld.getBlockState(clickedPos).isOf(state.getBlock())
            ? clickedPos
            : clickedPos.offset(context.getSide());

        CoreProtectFabricMod.logBlockPlace(player, serverWorld, placedPos, serverWorld.getBlockState(placedPos));
    }
}
