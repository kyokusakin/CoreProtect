package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.listener.block.BlockPlaceListener;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.block.LecternBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(LecternBlock.class)
public abstract class LecternBlockMixin {
    @Unique
    private static final ThreadLocal<LecternBookPlaceContext> coreprotect$lecternPlaceContext = new ThreadLocal<>();

    @Inject(method = "putBook", at = @At("HEAD"))
    private static void coreprotect$captureLecternBookPlace(LivingEntity user, World world, BlockPos pos, net.minecraft.block.BlockState state, ItemStack stack, CallbackInfo ci) {
        if (!(user instanceof ServerPlayerEntity serverPlayer) || !(world instanceof ServerWorld serverWorld) || stack == null || stack.isEmpty()) {
            coreprotect$lecternPlaceContext.remove();
            return;
        }

        if (CoreProtectFabricMod.getRuntime() == null) {
            coreprotect$lecternPlaceContext.remove();
            return;
        }

        ItemStack placedBook = stack.copy();
        placedBook.setCount(1);
        coreprotect$lecternPlaceContext.set(new LecternBookPlaceContext(
            serverPlayer,
            serverWorld,
            pos.toImmutable(),
            placedBook,
            ItemDeltaSnapshot.snapshotPlayerInventory(serverPlayer)
        ));
    }

    @Inject(method = "putBook", at = @At("RETURN"))
    private static void coreprotect$logLecternBookPlace(LivingEntity user, World world, BlockPos pos, net.minecraft.block.BlockState state, ItemStack stack, CallbackInfo ci) {
        LecternBookPlaceContext context = coreprotect$lecternPlaceContext.get();
        coreprotect$lecternPlaceContext.remove();
        if (context == null) {
            return;
        }

        BlockPlaceListener.logLecternBookPlace(
            context.player(),
            context.world(),
            context.pos(),
            context.placedBook(),
            context.beforePlayerInventory(),
            ItemDeltaSnapshot.snapshotPlayerInventory(context.player())
        );
    }

    @Unique
    private record LecternBookPlaceContext(
        ServerPlayerEntity player,
        ServerWorld world,
        BlockPos pos,
        ItemStack placedBook,
        Map<LoggedItemData, Integer> beforePlayerInventory
    ) {
    }
}
