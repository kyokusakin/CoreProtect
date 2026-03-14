package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
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
