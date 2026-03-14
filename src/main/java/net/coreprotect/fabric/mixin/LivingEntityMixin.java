package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.listener.entity.EntityDeathListener;
import net.coreprotect.fabric.util.EntityBlockChangeContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "applyMovementEffects", at = @At("HEAD"))
    private void coreprotect$pushMovementContext(ServerWorld world, BlockPos pos, CallbackInfo ci) {
        if (!((Object) this instanceof LivingEntity entity)) {
            return;
        }
        String actor = entity instanceof ServerPlayerEntity player ? player.getName().getString() : "#" + Registries.ENTITY_TYPE.getId(entity.getType()).getPath();
        EntityBlockChangeContext.push(actor);
    }

    @Inject(method = "applyMovementEffects", at = @At("RETURN"))
    private void coreprotect$popMovementContext(ServerWorld world, BlockPos pos, CallbackInfo ci) {
        if (!((Object) this instanceof LivingEntity entity)) {
            return;
        }
        EntityBlockChangeContext.pop();
    }

    @Inject(method = "onDeath", at = @At("RETURN"))
    private void coreprotect$logEntityKill(DamageSource damageSource, CallbackInfo ci) {
        if (!((Object) this instanceof LivingEntity killedEntity)) {
            return;
        }
        if (killedEntity instanceof ServerPlayerEntity) {
            return;
        }
        if (!(killedEntity.getEntityWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        EntityDeathListener.logEntityKill(serverWorld, damageSource == null ? null : damageSource.getSource(), killedEntity, damageSource);
    }

    @Inject(method = "sendEquipmentBreakStatus", at = @At("RETURN"))
    private void coreprotect$logEquipmentBreak(Item item, EquipmentSlot slot, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayerEntity player)) {
            return;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld serverWorld) || item == null) {
            return;
        }

        String itemKey = Registries.ITEM.getId(item).toString();
        CoreProtectFabricMod.getRuntime().logger().logItemDestroy(
            player.getUuidAsString(),
            player.getName().getString(),
            serverWorld.getRegistryKey().getValue().toString(),
            player.getBlockPos(),
            net.coreprotect.fabric.util.LoggedItemData.fromItemKey(itemKey),
            1,
            "item_break"
        );
    }
}
