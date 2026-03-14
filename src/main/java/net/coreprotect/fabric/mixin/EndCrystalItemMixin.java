package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.EndCrystalItem;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(EndCrystalItem.class)
public abstract class EndCrystalItemMixin {
    @Inject(method = "useOnBlock", at = @At("RETURN"))
    private void coreprotect$logEndCrystalPlacement(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
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
        EndCrystalEntity endCrystal = coreprotect$findPlacedCrystal(world, context.getBlockPos().up());
        if (endCrystal == null) {
            return;
        }

        CoreProtectFabricMod.logEntityPlace(player, world, endCrystal.getBlockPos(), endCrystal);
    }

    @Unique
    private EndCrystalEntity coreprotect$findPlacedCrystal(ServerWorld world, BlockPos searchPos) {
        Vec3d center = Vec3d.ofCenter(searchPos);
        Box searchBox = Box.of(center, 3.0, 4.0, 3.0);
        List<EndCrystalEntity> matches = world.getEntitiesByClass(
            EndCrystalEntity.class,
            searchBox,
            entity -> entity.squaredDistanceTo(center) <= 4.0
        );

        EndCrystalEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (EndCrystalEntity endCrystal : matches) {
            double distance = endCrystal.squaredDistanceTo(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = endCrystal;
            }
        }
        return best;
    }
}
