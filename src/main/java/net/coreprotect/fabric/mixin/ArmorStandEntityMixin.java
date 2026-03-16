package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.listener.entity.EntityDamageByBlockListener;
import net.coreprotect.fabric.listener.entity.EntityDamageByEntityListener;
import net.coreprotect.fabric.listener.player.ArmorStandManipulateListener;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

@Mixin(ArmorStandEntity.class)
public abstract class ArmorStandEntityMixin {
    @Unique
    private Map<LoggedItemData, Integer> coreprotect$beforeEquipment = Map.of();
    @Unique
    private Map<LoggedItemData, Integer> coreprotect$beforeBreakEquipment = Map.of();
    @Unique
    private boolean coreprotect$aliveBeforeDamage;

    @Inject(method = "interactAt", at = @At("HEAD"))
    private void coreprotect$captureArmorStandState(PlayerEntity player, Vec3d hitPos, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        ArmorStandEntity armorStand = (ArmorStandEntity) (Object) this;
        coreprotect$beforeEquipment = coreprotect$snapshotEquipment(armorStand);
    }

    @Inject(method = "interactAt", at = @At("RETURN"))
    private void coreprotect$logArmorStandInteraction(PlayerEntity player, Vec3d hitPos, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        try {
            if (!cir.getReturnValue().isAccepted()) {
                return;
            }
            if (!(player instanceof ServerPlayerEntity)) {
                return;
            }

            ArmorStandEntity armorStand = (ArmorStandEntity) (Object) this;
            if (!(armorStand.getEntityWorld() instanceof ServerWorld)) {
                return;
            }

            ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
            ServerWorld world = (ServerWorld) armorStand.getEntityWorld();
            Map<LoggedItemData, Integer> afterEquipment = coreprotect$snapshotEquipment(armorStand);
            if (coreprotect$beforeEquipment.equals(afterEquipment)) {
                return;
            }
            ArmorStandManipulateListener.logArmorStandInteraction(serverPlayer, world, armorStand, coreprotect$beforeEquipment, afterEquipment);
        }
        finally {
            coreprotect$beforeEquipment = Map.of();
        }
    }

    @Inject(method = "damage", at = @At("HEAD"))
    private void coreprotect$captureArmorStandBreak(ServerWorld world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        ArmorStandEntity armorStand = (ArmorStandEntity) (Object) this;
        coreprotect$aliveBeforeDamage = armorStand.isAlive();
        coreprotect$beforeBreakEquipment = coreprotect$snapshotEquipment(armorStand);
    }

    @Inject(method = "damage", at = @At("RETURN"))
    private void coreprotect$logArmorStandBreak(ServerWorld world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        try {
            ArmorStandEntity armorStand = (ArmorStandEntity) (Object) this;
            if (!cir.getReturnValueZ()) {
                return;
            }
            if (!coreprotect$aliveBeforeDamage || armorStand.isAlive()) {
                return;
            }

            if (source.getAttacker() instanceof ServerPlayerEntity && !((ServerPlayerEntity) source.getAttacker()).isCreative()) {
                ServerPlayerEntity player = (ServerPlayerEntity) source.getAttacker();
                ArmorStandManipulateListener.logArmorStandBreak(player, world, armorStand.getBlockPos(), coreprotect$beforeBreakEquipment);
            }

            if (source.getAttacker() == null) {
                CoreProtectFabricMod.getRuntime().logger().logEntityBreak(EntityDamageByBlockListener.actorFromDamageSource(source), world, armorStand.getBlockPos(), armorStand);
            }
            else {
                EntityDamageByEntityListener.logEntityBreak(world, armorStand.getBlockPos(), armorStand, source.getAttacker());
            }
        }
        finally {
            coreprotect$beforeBreakEquipment = Map.of();
            coreprotect$aliveBeforeDamage = false;
        }
    }

    @Unique
    private Map<LoggedItemData, Integer> coreprotect$snapshotEquipment(ArmorStandEntity armorStand) {
        return ItemDeltaSnapshot.snapshotStacks(List.of(
            coreprotect$copy(armorStand.getEquippedStack(EquipmentSlot.FEET)),
            coreprotect$copy(armorStand.getEquippedStack(EquipmentSlot.LEGS)),
            coreprotect$copy(armorStand.getEquippedStack(EquipmentSlot.CHEST)),
            coreprotect$copy(armorStand.getEquippedStack(EquipmentSlot.HEAD)),
            coreprotect$copy(armorStand.getEquippedStack(EquipmentSlot.MAINHAND)),
            coreprotect$copy(armorStand.getEquippedStack(EquipmentSlot.OFFHAND))
        ));
    }

    @Unique
    private ItemStack coreprotect$copy(ItemStack stack) {
        return stack == null ? ItemStack.EMPTY : stack.copy();
    }
}
