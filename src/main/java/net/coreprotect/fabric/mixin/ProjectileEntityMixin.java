package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.util.LoggedItemData;
import net.coreprotect.fabric.util.ProjectileItemResolver;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ProjectileEntity.class)
public abstract class ProjectileEntityMixin {
    @Unique
    private boolean coreprotect$shotLogged = false;

    @Inject(method = "setOwner(Lnet/minecraft/entity/Entity;)V", at = @At("RETURN"))
    private void coreprotect$logMobProjectileShot(Entity owner, CallbackInfo ci) {
        if (coreprotect$shotLogged || owner == null || owner instanceof PlayerEntity) {
            return;
        }
        ProjectileEntity projectile = (ProjectileEntity) (Object) this;
        if (!(projectile.getEntityWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        ItemStack stack = ProjectileItemResolver.resolveStack(projectile);
        if (stack.isEmpty()) {
            return;
        }

        String actorName;
        if (owner instanceof MobEntity mob) {
            actorName = "#" + Registries.ENTITY_TYPE.getId(mob.getType()).getPath().toLowerCase();
        } else {
            actorName = "#" + Registries.ENTITY_TYPE.getId(owner.getType()).getPath().toLowerCase();
        }

        coreprotect$shotLogged = true;
        CoreProtectFabricMod.getRuntime().logger().logItemShoot(
            actorName,
            serverWorld.getRegistryKey().getValue().toString(),
            projectile.getBlockPos(),
            LoggedItemData.fromStack(stack, serverWorld.getRegistryManager()),
            stack.getCount(),
            null
        );
    }
}
