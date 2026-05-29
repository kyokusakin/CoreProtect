package net.coreprotect.fabric.listener.entity;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.projectile.ExplosiveProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public final class EntityExplodeListener {
    private EntityExplodeListener() {
    }

    public static void logExplosion(ServerWorld world, Entity entity, List<BlockPos> blocks) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || blocks == null || blocks.isEmpty()) {
            return;
        }

        String actor = "#explosion";
        boolean log = runtime.config(world).explosions();
        if (entity != null) {
            String entityPath = Registries.ENTITY_TYPE.getId(entity.getType()).getPath();
            if ("wind_charge".equals(entityPath) || "breeze_wind_charge".equals(entityPath)) {
                return;
            }

            if (entity instanceof TntEntity || entity instanceof TntMinecartEntity) {
                actor = "#tnt";
            }
            else if (entity instanceof CreeperEntity) {
                actor = "#creeper";
            }
            else if (entity instanceof EnderDragonEntity || entity instanceof EnderDragonPart) {
                actor = "#enderdragon";
                if (!runtime.config(world).logEntityChanges()) {
                    log = false;
                }
            }
            else if (entity instanceof WitherEntity || entity instanceof WitherSkullEntity) {
                actor = "#wither";
                if (!runtime.config(world).logEntityChanges()) {
                    log = false;
                }
            }
            else if (entity instanceof EndCrystalEntity) {
                actor = "#end_crystal";
            }
            else if (entity instanceof ExplosiveProjectileEntity) {
                actor = resolveFireballActor((ProjectileEntity) entity);
            }
        }

        if (!log) {
            return;
        }

        for (BlockPos pos : blocks) {
            BlockState state = world.getBlockState(pos);
            if (!state.isAir()) {
                runtime.logger().logBlockBreak(null, actor, world, pos, state);
            }
        }
    }

    /**
     * Attributes a fireball explosion to the entity that fired it, mirroring upstream's
     * Ghast fireball attribution. A fireball deflected by a player is credited to that player;
     * otherwise the explosion is attributed to the shooting mob (e.g. {@code #ghast}). Fireballs
     * without a known shooter fall back to the generic {@code #explosion} actor.
     */
    private static String resolveFireballActor(ProjectileEntity fireball) {
        Entity owner = fireball.getOwner();
        if (owner instanceof ServerPlayerEntity) {
            return owner.getName().getString();
        }
        if (owner instanceof LivingEntity) {
            return "#" + Registries.ENTITY_TYPE.getId(owner.getType()).getPath();
        }
        return "#explosion";
    }
}
