package net.coreprotect.fabric.listener.player;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

public final class ArmorStandManipulateListener {
    private ArmorStandManipulateListener() {
    }

    public static void logArmorStandInteraction(ServerPlayerEntity player, ServerWorld world, ArmorStandEntity armorStand, Map<LoggedItemData, Integer> beforeEquipment, Map<LoggedItemData, Integer> afterEquipment) {
        BlockPos pos = armorStand.getBlockPos();
        for (ItemDeltaSnapshot.ItemDelta delta : ItemDeltaSnapshot.diff(beforeEquipment, afterEquipment)) {
            if (delta.delta() > 0) {
                CoreProtectFabricMod.logItemDrop(player, world.getRegistryKey().getValue().toString(), pos, delta.item(), delta.delta(), "armor_stand");
            }
            else {
                CoreProtectFabricMod.logItemPickup(player, world.getRegistryKey().getValue().toString(), pos, delta.item(), -delta.delta(), "armor_stand");
            }
        }

        PlayerInteractEntityListener.logEntityUse(player, world, pos, armorStand);
    }

    public static void logArmorStandBreak(ServerPlayerEntity player, ServerWorld world, BlockPos pos, Map<LoggedItemData, Integer> equipment) {
        for (Map.Entry<LoggedItemData, Integer> entry : equipment.entrySet()) {
            CoreProtectFabricMod.logItemDrop(player, world.getRegistryKey().getValue().toString(), pos, entry.getKey(), entry.getValue(), "armor_stand");
        }
    }
}
