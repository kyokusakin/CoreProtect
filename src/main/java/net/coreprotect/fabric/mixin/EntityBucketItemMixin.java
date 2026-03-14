package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.minecraft.entity.Entity;
import net.minecraft.item.EntityBucketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityBucketItem.class)
public abstract class EntityBucketItemMixin {
    @Inject(method = "spawnEntity", at = @At("RETURN"))
    private void coreprotect$logBucketedEntityPlacement(ServerWorld world, ItemStack stack, BlockPos pos, CallbackInfo ci) {
        BucketItemMixin.EntityBucketContext context = BucketItemMixin.coreprotect$consumeEntityBucketContext();
        if (context == null) {
            return;
        }

        Entity placedEntity = coreprotect$findPlacedEntity(world, pos, context.player());
        if (placedEntity != null) {
            CoreProtectFabricMod.logEntityPlace(context.player(), world, placedEntity.getBlockPos(), placedEntity);
        }
    }

    @Unique
    private Entity coreprotect$findPlacedEntity(ServerWorld world, BlockPos pos, ServerPlayerEntity player) {
        return world.getOtherEntities(
            player,
            net.minecraft.util.math.Box.of(pos.toCenterPos(), 4.0D, 4.0D, 4.0D),
            entity -> entity.isAlive()
        ).stream().min((left, right) -> Double.compare(left.squaredDistanceTo(pos.toCenterPos()), right.squaredDistanceTo(pos.toCenterPos()))).orElse(null);
    }
}
