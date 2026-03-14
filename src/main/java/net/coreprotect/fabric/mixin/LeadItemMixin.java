package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.LeashKnotEntity;
import net.minecraft.item.LeadItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Set;

@Mixin(LeadItem.class)
public abstract class LeadItemMixin {
    @Unique
    private final Set<Integer> coreprotect$beforeLeashKnotIds = new HashSet<>();

    @Inject(method = "attachHeldMobsToBlock", at = @At("HEAD"))
    private void coreprotect$captureLeashKnotPlacement(ServerPlayerEntity player, World world, BlockPos pos, CallbackInfoReturnable<ActionResult> cir) {
        coreprotect$beforeLeashKnotIds.clear();
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        for (LeashKnotEntity leashKnotEntity : serverWorld.getEntitiesByClass(LeashKnotEntity.class, Box.of(pos.toCenterPos(), 2.0D, 2.0D, 2.0D), Entity::isAlive)) {
            coreprotect$beforeLeashKnotIds.add(leashKnotEntity.getId());
        }
    }

    @Inject(method = "attachHeldMobsToBlock", at = @At("RETURN"))
    private void coreprotect$logLeashKnotPlacement(ServerPlayerEntity player, World world, BlockPos pos, CallbackInfoReturnable<ActionResult> cir) {
        try {
            if (!cir.getReturnValue().isAccepted()) {
                return;
            }
            if (!(world instanceof ServerWorld serverWorld)) {
                return;
            }

            for (LeashKnotEntity leashKnotEntity : serverWorld.getEntitiesByClass(LeashKnotEntity.class, Box.of(pos.toCenterPos(), 2.0D, 2.0D, 2.0D), Entity::isAlive)) {
                if (coreprotect$beforeLeashKnotIds.contains(leashKnotEntity.getId())) {
                    continue;
                }
                CoreProtectFabricMod.logEntityPlace(player, serverWorld, leashKnotEntity.getBlockPos(), leashKnotEntity);
                break;
            }
        }
        finally {
            coreprotect$beforeLeashKnotIds.clear();
        }
    }
}
