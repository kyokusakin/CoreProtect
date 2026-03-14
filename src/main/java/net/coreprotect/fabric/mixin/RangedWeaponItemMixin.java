package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.util.ProjectileItemResolver;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ BowItem.class, CrossbowItem.class })
public abstract class RangedWeaponItemMixin {
    @Inject(method = "shoot", at = @At("TAIL"))
    private void coreprotect$logProjectileShot(LivingEntity shooter, ProjectileEntity projectile, int index, float speed, float divergence, float yaw, LivingEntity target, CallbackInfo ci) {
        if (!(shooter instanceof ServerPlayerEntity serverPlayerEntity)) {
            return;
        }
        if (!(shooter.getEntityWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        ItemStack projectileStack = ProjectileItemResolver.resolveStack(projectile);
        if (projectileStack.isEmpty()) {
            return;
        }

        CoreProtectFabricMod.logItemShoot(serverPlayerEntity, serverWorld, projectile.getBlockPos(), projectileStack);
    }
}
