package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.LeashKnotEntity;
import net.minecraft.item.LeadItem;
import net.minecraft.entity.player.PlayerEntity;
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
    private static final ThreadLocal<Set<Integer>> coreprotect$beforeLeashKnotIds = ThreadLocal.withInitial(HashSet::new);

    @Inject(method = "attachHeldMobsToBlock", at = @At("HEAD"))
    private static void coreprotect$captureLeashKnotPlacement(PlayerEntity player, World world, BlockPos pos, CallbackInfoReturnable<ActionResult> cir) {
        Set<Integer> knownIds = coreprotect$beforeLeashKnotIds.get();
        knownIds.clear();
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        for (LeashKnotEntity leashKnotEntity : serverWorld.getEntitiesByClass(LeashKnotEntity.class, Box.of(pos.toCenterPos(), 2.0D, 2.0D, 2.0D), Entity::isAlive)) {
            knownIds.add(leashKnotEntity.getId());
        }
    }

    @Inject(method = "attachHeldMobsToBlock", at = @At("RETURN"))
    private static void coreprotect$logLeashKnotPlacement(PlayerEntity player, World world, BlockPos pos, CallbackInfoReturnable<ActionResult> cir) {
        Set<Integer> knownIds = coreprotect$beforeLeashKnotIds.get();
        try {
            if (!cir.getReturnValue().isAccepted()) {
                return;
            }
            if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                return;
            }
            if (!(world instanceof ServerWorld serverWorld)) {
                return;
            }

            for (LeashKnotEntity leashKnotEntity : serverWorld.getEntitiesByClass(LeashKnotEntity.class, Box.of(pos.toCenterPos(), 2.0D, 2.0D, 2.0D), Entity::isAlive)) {
                if (knownIds.contains(leashKnotEntity.getId())) {
                    continue;
                }
                CoreProtectFabricMod.logEntityPlace(serverPlayer, serverWorld, leashKnotEntity.getBlockPos(), leashKnotEntity);
                break;
            }
        }
        finally {
            knownIds.clear();
        }
    }
}
