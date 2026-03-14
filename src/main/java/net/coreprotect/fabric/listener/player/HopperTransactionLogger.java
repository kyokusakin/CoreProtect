package net.coreprotect.fabric.listener.player;

import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
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

        for (ItemDeltaSnapshot.ItemDelta delta : ItemDeltaSnapshot.diff(before, after)) {
            ItemStack stack = buildSyntheticStack(delta.item(), Math.abs(delta.delta()));
            if (delta.delta() > 0) {
                if (!stack.isEmpty()) {
                    runtime.logger().logContainerTransaction(actor, worldKey, pos, containerType, -1, 0, SlotActionType.QUICK_MOVE, ItemStack.EMPTY, stack, ItemStack.EMPTY, ItemStack.EMPTY);
                }
                runtime.logger().logItemPickup(actor, worldKey, pos, delta.item(), delta.delta(), containerType);
            }
            else {
                if (!stack.isEmpty()) {
                    runtime.logger().logContainerTransaction(actor, worldKey, pos, containerType, -1, 0, SlotActionType.QUICK_MOVE, stack, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
                }
                runtime.logger().logItemDrop(actor, worldKey, pos, delta.item(), -delta.delta(), containerType);
            }
        }
    }

    private static ItemStack buildSyntheticStack(LoggedItemData item, int count) {
        if (item == null || item.itemKey() == null || item.itemKey().isBlank()) {
            return ItemStack.EMPTY;
        }

        Identifier identifier = Identifier.tryParse(item.itemKey());
        if (identifier == null || !Registries.ITEM.containsId(identifier)) {
            return ItemStack.EMPTY;
        }

        return new ItemStack(Registries.ITEM.get(identifier), Math.max(1, count));
    }
}
