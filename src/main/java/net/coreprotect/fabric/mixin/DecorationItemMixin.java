package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.entity.HangingPlaceListener;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.item.DecorationItem;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(DecorationItem.class)
public abstract class DecorationItemMixin {
    @Shadow
    @Final
    private EntityType<? extends AbstractDecorationEntity> entityType;

    @Inject(method = "useOnBlock", at = @At("RETURN"))
    private void coreprotect$logDecorationPlacement(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        if (!cir.getReturnValue().isAccepted()) {
            return;
        }
        if (!(context.getWorld() instanceof ServerWorld)) {
            return;
        }
        if (!(context.getPlayer() instanceof ServerPlayerEntity)) {
            return;
        }

        ServerWorld world = (ServerWorld) context.getWorld();
        ServerPlayerEntity player = (ServerPlayerEntity) context.getPlayer();
        BlockPos attachedPos = context.getBlockPos();
        Direction facing = context.getSide();
        AbstractDecorationEntity decorationEntity = coreprotect$findPlacedEntity(world, attachedPos, facing);
        if (decorationEntity == null) {
            return;
        }

        HangingPlaceListener.logHangingPlace(player, world, attachedPos, decorationEntity);
    }

    @Unique
    private AbstractDecorationEntity coreprotect$findPlacedEntity(ServerWorld world, BlockPos attachedPos, Direction facing) {
        Box searchBox = Box.of(Vec3d.ofCenter(attachedPos), 4.0, 4.0, 4.0);
        List<AbstractDecorationEntity> matches = world.getEntitiesByClass(
            AbstractDecorationEntity.class,
            searchBox,
            entity -> entity.getType() == this.entityType
                && attachedPos.equals(entity.getAttachedBlockPos())
                && entity.getHorizontalFacing() == facing
        );
        return matches.isEmpty() ? null : matches.get(0);
    }
}
