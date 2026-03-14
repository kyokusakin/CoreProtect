package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ArmorStandItem;
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

@Mixin(ArmorStandItem.class)
public abstract class ArmorStandItemMixin {
    @Inject(method = "useOnBlock", at = @At("RETURN"))
    private void coreprotect$logArmorStandPlacement(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
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
        ArmorStandEntity armorStand = coreprotect$findPlacedArmorStand(world, context.getBlockPos().offset(context.getSide()));
        if (armorStand == null) {
            return;
        }

        CoreProtectFabricMod.logEntityPlace(player, world, armorStand.getBlockPos(), armorStand);
    }

    @Unique
    private ArmorStandEntity coreprotect$findPlacedArmorStand(ServerWorld world, BlockPos searchPos) {
        Vec3d center = Vec3d.ofCenter(searchPos);
        Box searchBox = Box.of(center, 3.0, 4.0, 3.0);
        List<ArmorStandEntity> matches = world.getEntitiesByClass(
            ArmorStandEntity.class,
            searchBox,
            entity -> entity.squaredDistanceTo(center) <= 4.0
        );

        ArmorStandEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ArmorStandEntity armorStand : matches) {
            double distance = armorStand.squaredDistanceTo(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = armorStand;
            }
        }
        return best;
    }
}
