package net.coreprotect.fabric.util;

import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.ItemStack;

public final class ProjectileItemResolver {
    private ProjectileItemResolver() {
    }

    public static ItemStack resolveStack(ProjectileEntity projectile) {
        if (projectile instanceof PersistentProjectileEntity persistentProjectileEntity) {
            return persistentProjectileEntity.getItemStack().copy();
        }
        if (projectile instanceof ThrownItemEntity thrownItemEntity) {
            return thrownItemEntity.getStack().copy();
        }
        if (projectile instanceof FireworkRocketEntity fireworkRocketEntity) {
            return fireworkRocketEntity.getStack().copy();
        }
        return ItemStack.EMPTY;
    }
}
