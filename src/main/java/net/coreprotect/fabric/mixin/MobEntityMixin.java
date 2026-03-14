package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MobEntity.class)
public abstract class MobEntityMixin {
    @Inject(method = "loot", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/mob/MobEntity;sendPickup(Lnet/minecraft/entity/Entity;I)V"))
    private void coreprotect$logMobPickup(ServerWorld serverWorld, ItemEntity itemEntity, CallbackInfo ci) {
        MobEntity mob = (MobEntity) (Object) this;
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || !runtime.config(serverWorld).logItemPickups()) {
            return;
        }

        ItemStack stack = itemEntity.getStack();
        if (stack.isEmpty()) {
            return;
        }

        String entityId = Registries.ENTITY_TYPE.getId(mob.getType()).getPath();
        String actorName = "#" + entityId.toLowerCase();

        runtime.logger().logItemPickup(
            actorName,
            serverWorld.getRegistryKey().getValue().toString(),
            mob.getBlockPos(),
            LoggedItemData.fromStack(stack, serverWorld.getRegistryManager()),
            stack.getCount(),
            null
        );
    }
}
