package net.coreprotect.fabric.listener.entity;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.world.ServerWorld;

public final class EntityDeathListener {
    private EntityDeathListener() {
    }

    public static void logEntityKill(ServerWorld world, Entity killer, LivingEntity killedEntity, DamageSource damageSource) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null) {
            return;
        }

        runtime.logger().logEntityKill(world, killer, killedEntity, damageSource);
    }
}
