package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.item.BoatItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
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

@Mixin(BoatItem.class)
public abstract class BoatItemMixin {
    @Shadow
    @Final
    private EntityType<? extends AbstractBoatEntity> boatEntityType;

    @Unique
    private Set<UUID> coreprotect$knownBoatIds = Set.of();

    @Inject(method = "use", at = @At("HEAD"))
    private void coreprotect$captureBoats(World world, net.minecraft.entity.player.PlayerEntity user, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (!(world instanceof ServerWorld serverWorld) || !(user instanceof ServerPlayerEntity serverPlayer)) {
            coreprotect$knownBoatIds = Set.of();
            return;
        }

        Vec3d playerPos = new Vec3d(serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ());
        coreprotect$knownBoatIds = coreprotect$collectMatchingBoatIds(serverWorld, playerPos);
    }

    @Inject(method = "use", at = @At("RETURN"))
    private void coreprotect$logBoatPlacement(World world, net.minecraft.entity.player.PlayerEntity user, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        try {
            if (!cir.getReturnValue().isAccepted()) {
                return;
            }
            if (!(world instanceof ServerWorld serverWorld) || !(user instanceof ServerPlayerEntity serverPlayer)) {
                return;
            }

            Vec3d playerPos = new Vec3d(serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ());
            AbstractBoatEntity boat = coreprotect$findPlacedBoat(serverWorld, playerPos);
            if (boat == null) {
                return;
            }

            CoreProtectFabricMod.logEntityPlace(serverPlayer, serverWorld, boat.getBlockPos(), boat);
        }
        finally {
            coreprotect$knownBoatIds = Set.of();
        }
    }

    @Unique
    private Set<UUID> coreprotect$collectMatchingBoatIds(ServerWorld world, Vec3d center) {
        Set<UUID> ids = new HashSet<>();
        for (AbstractBoatEntity entity : coreprotect$findCandidateBoats(world, center)) {
            ids.add(entity.getUuid());
        }
        return ids;
    }

    @Unique
    private AbstractBoatEntity coreprotect$findPlacedBoat(ServerWorld world, Vec3d center) {
        AbstractBoatEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (AbstractBoatEntity entity : coreprotect$findCandidateBoats(world, center)) {
            if (coreprotect$knownBoatIds.contains(entity.getUuid())) {
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
    private List<AbstractBoatEntity> coreprotect$findCandidateBoats(ServerWorld world, Vec3d center) {
        return world.getEntitiesByClass(
            AbstractBoatEntity.class,
            Box.of(center, 14.0, 8.0, 14.0),
            entity -> entity.getType() == this.boatEntityType
        );
    }
}
