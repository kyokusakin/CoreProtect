package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Mixin(SpawnEggItem.class)
public abstract class SpawnEggItemMixin {
    @Unique
    private final Set<Integer> coreprotect$beforeEntityIds = new HashSet<>();

    @Shadow
    public abstract EntityType<?> getEntityType(ItemStack stack);

    @Inject(method = "useOnBlock", at = @At("HEAD"))
    private void coreprotect$captureSpawnEggPlacement(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        coreprotect$beforeEntityIds.clear();
        if (!(context.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }
        coreprotect$beforeEntityIds.addAll(coreprotect$collectNearbyEntityIds(serverWorld, context.getBlockPos(), getEntityType(context.getStack())));
    }

    @Inject(method = "useOnBlock", at = @At("RETURN"))
    private void coreprotect$logSpawnEggPlacement(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        try {
            if (!cir.getReturnValue().isAccepted()) {
                return;
            }
            if (!(context.getPlayer() instanceof ServerPlayerEntity player)) {
                return;
            }
            if (!(context.getWorld() instanceof ServerWorld serverWorld)) {
                return;
            }

            EntityType<?> entityType = getEntityType(context.getStack());
            Entity placedEntity = coreprotect$findPlacedEntity(serverWorld, context.getBlockPos(), entityType);
            if (placedEntity != null) {
                CoreProtectFabricMod.logEntityPlace(player, serverWorld, placedEntity.getBlockPos(), placedEntity);
            }
        }
        finally {
            coreprotect$beforeEntityIds.clear();
        }
    }

    @Inject(method = "spawnBaby", at = @At("RETURN"))
    private static void coreprotect$logSpawnEggBaby(
        ServerPlayerEntity user,
        LivingEntity entity,
        EntityType<?> entityType,
        ServerWorld world,
        Vec3d pos,
        ItemStack stack,
        CallbackInfoReturnable<Optional<Entity>> cir
    ) {
        if (user == null || world == null) {
            return;
        }
        Optional<Entity> spawnedEntity = cir.getReturnValue();
        if (spawnedEntity == null || spawnedEntity.isEmpty()) {
            return;
        }
        CoreProtectFabricMod.logEntityPlace(user, world, spawnedEntity.get().getBlockPos(), spawnedEntity.get());
    }

    @Unique
    private Entity coreprotect$findPlacedEntity(ServerWorld world, BlockPos pos, EntityType<?> entityType) {
        return world.getEntitiesByType(
            entityType,
            Box.of(pos.toCenterPos(), 6.0D, 6.0D, 6.0D),
            entity -> !coreprotect$beforeEntityIds.contains(entity.getId())
        ).stream().findFirst().orElse(null);
    }

    @Unique
    private Set<Integer> coreprotect$collectNearbyEntityIds(World world, BlockPos pos, EntityType<?> entityType) {
        Set<Integer> ids = new HashSet<>();
        if (!(world instanceof ServerWorld serverWorld)) {
            return ids;
        }
        for (Entity entity : serverWorld.getEntitiesByType(entityType, Box.of(pos.toCenterPos(), 6.0D, 6.0D, 6.0D), candidate -> true)) {
            ids.add(entity.getId());
        }
        return ids;
    }
}
