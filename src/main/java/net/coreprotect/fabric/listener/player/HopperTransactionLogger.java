package net.coreprotect.fabric.listener.player;

import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class HopperTransactionLogger {
    private HopperTransactionLogger() {
    }

    public static Map<LoggedItemData, Integer> snapshotInventory(Inventory inventory) {
        if (inventory == null) {
            return Map.of();
        }

        List<ItemStack> stacks = new ArrayList<>(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            stacks.add(inventory.getStack(slot));
        }
        return ItemDeltaSnapshot.snapshotStacks(stacks);
    }

    public static Map<LoggedItemData, Integer> snapshotItemEntities(List<ItemEntity> entities) {
        List<ItemStack> stacks = new ArrayList<>();
        if (entities != null) {
            for (ItemEntity entity : entities) {
                if (entity != null) {
                    stacks.add(entity.getStack());
                }
            }
        }
        return ItemDeltaSnapshot.snapshotStacks(stacks);
    }

    public static void logInventoryChange(FabricRuntime runtime, String actor, String worldKey, BlockPos pos, String containerType, Map<LoggedItemData, Integer> before, Map<LoggedItemData, Integer> after) {
        if (runtime == null || actor == null || actor.isBlank() || pos == null) {
            return;
        }
        List<ItemDeltaSnapshot.ItemDelta> deltas = ItemDeltaSnapshot.diff(before, after);
        if (runtime.config(worldKey).hopperFilterMeta() && deltas.stream().noneMatch(delta -> delta.item().hasMetadata())) {
            return;
        }

        for (ItemDeltaSnapshot.ItemDelta delta : deltas) {
            runtime.logger().logContainerChange(actor, worldKey, pos, containerType, delta.item(), Math.abs(delta.delta()), delta.delta() > 0);
        }
    }
}
