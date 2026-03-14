package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.MinecartItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Mixin(MinecartItem.class)
public abstract class MinecartItemMixin {
    @Shadow
    @Final
    private EntityType<? extends AbstractMinecartEntity> type;

    @Unique
    private Set<UUID> coreprotect$knownMinecartIds = Set.of();

    @Inject(method = "useOnBlock", at = @At("HEAD"))
    private void coreprotect$captureMinecarts(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        if (!(context.getWorld() instanceof ServerWorld serverWorld) || !(context.getPlayer() instanceof ServerPlayerEntity)) {
            coreprotect$knownMinecartIds = Set.of();
            return;
        }

        coreprotect$knownMinecartIds = coreprotect$collectMatchingMinecartIds(serverWorld, context.getBlockPos());
    }

    @Inject(method = "useOnBlock", at = @At("RETURN"))
    private void coreprotect$logMinecartPlacement(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        try {
            if (!cir.getReturnValue().isAccepted()) {
                return;
            }
            if (!(context.getWorld() instanceof ServerWorld serverWorld) || !(context.getPlayer() instanceof ServerPlayerEntity serverPlayer)) {
                return;
            }

            AbstractMinecartEntity minecart = coreprotect$findPlacedMinecart(serverWorld, context.getBlockPos());
            if (minecart == null) {
                return;
            }

            CoreProtectFabricMod.logEntityPlace(serverPlayer, serverWorld, minecart.getBlockPos(), minecart);
        }
        finally {
            coreprotect$knownMinecartIds = Set.of();
        }
    }

    @Unique
    private Set<UUID> coreprotect$collectMatchingMinecartIds(ServerWorld world, BlockPos searchPos) {
        Set<UUID> ids = new HashSet<>();
        for (AbstractMinecartEntity entity : coreprotect$findCandidateMinecarts(world, searchPos)) {
            ids.add(entity.getUuid());
        }
        return ids;
    }

    @Unique
    private AbstractMinecartEntity coreprotect$findPlacedMinecart(ServerWorld world, BlockPos searchPos) {
        AbstractMinecartEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        Vec3d center = Vec3d.ofCenter(searchPos);
        for (AbstractMinecartEntity entity : coreprotect$findCandidateMinecarts(world, searchPos)) {
            if (coreprotect$knownMinecartIds.contains(entity.getUuid())) {
                continue;
            }

            double distance = entity.squaredDistanceTo(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    @Unique
    private List<AbstractMinecartEntity> coreprotect$findCandidateMinecarts(ServerWorld world, BlockPos searchPos) {
        return world.getEntitiesByClass(
            AbstractMinecartEntity.class,
            Box.of(Vec3d.ofCenter(searchPos), 3.0, 3.0, 3.0),
            entity -> entity.getType() == this.type
        );
    }
}
