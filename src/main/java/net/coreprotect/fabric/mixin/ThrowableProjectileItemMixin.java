package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.EggItem;
import net.minecraft.item.EnderPearlItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SnowballItem;
import net.minecraft.item.ThrowablePotionItem;
import net.minecraft.item.WindChargeItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({ EggItem.class, SnowballItem.class, EnderPearlItem.class, ThrowablePotionItem.class, WindChargeItem.class })
public abstract class ThrowableProjectileItemMixin {
    @Inject(method = "use", at = @At("RETURN"))
    private void coreprotect$logThrownItem(World world, PlayerEntity user, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (!cir.getReturnValue().isAccepted()) {
            return;
        }
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        if (!(user instanceof ServerPlayerEntity serverPlayerEntity)) {
            return;
        }

        CoreProtectFabricMod.logItemThrow(serverPlayerEntity, serverWorld, user.getBlockPos(), new ItemStack((Item) (Object) this));
    }
}
