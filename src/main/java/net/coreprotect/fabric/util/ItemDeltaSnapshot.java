package net.coreprotect.fabric.util;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class ItemDeltaSnapshot {
    private ItemDeltaSnapshot() {
    }

    public static Map<LoggedItemData, Integer> snapshotPlayerInventory(ServerPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> stacks = new ArrayList<>(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            stacks.add(inventory.getStack(slot));
        }
        return snapshotStacks(stacks);
    }

    public static Map<LoggedItemData, Integer> snapshotStacks(Iterable<ItemStack> stacks) {
        Map<LoggedItemData, Integer> counts = new HashMap<>();
        if (stacks == null) {
            return counts;
        }

        Iterator<ItemStack> iterator = stacks.iterator();
        while (iterator.hasNext()) {
            ItemStack stack = iterator.next();
            if (stack == null || stack.isEmpty()) {
                continue;
            }

            counts.merge(LoggedItemData.fromStack(stack), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    public static List<ItemDelta> diff(Map<LoggedItemData, Integer> before, Map<LoggedItemData, Integer> after) {
        Map<LoggedItemData, Integer> beforeSnapshot = before == null ? Map.of() : before;
        Map<LoggedItemData, Integer> afterSnapshot = after == null ? Map.of() : after;
        TreeSet<LoggedItemData> itemKeys = new TreeSet<>((left, right) -> {
            int itemKeyComparison = left.itemKey().compareTo(right.itemKey());
            if (itemKeyComparison != 0) {
                return itemKeyComparison;
            }
            int displayNameComparison = left.displayName().compareTo(right.displayName());
            if (displayNameComparison != 0) {
                return displayNameComparison;
            }
            int componentChangesComparison = left.componentChanges().compareTo(right.componentChanges());
            if (componentChangesComparison != 0) {
                return componentChangesComparison;
            }
            return left.serializedStack().compareTo(right.serializedStack());
        });
        itemKeys.addAll(beforeSnapshot.keySet());
        itemKeys.addAll(afterSnapshot.keySet());

        List<ItemDelta> deltas = new ArrayList<>();
        for (LoggedItemData itemKey : itemKeys) {
            int previous = beforeSnapshot.getOrDefault(itemKey, 0);
            int current = afterSnapshot.getOrDefault(itemKey, 0);
            int delta = current - previous;
            if (delta == 0) {
                continue;
            }
            deltas.add(new ItemDelta(itemKey, delta));
        }
        return deltas;
    }

    public static int countDelta(Map<LoggedItemData, Integer> before, Map<LoggedItemData, Integer> after, LoggedItemData item) {
        Map<LoggedItemData, Integer> beforeSnapshot = before == null ? Map.of() : before;
        Map<LoggedItemData, Integer> afterSnapshot = after == null ? Map.of() : after;
        if (item == null) {
            return 0;
        }
        return afterSnapshot.getOrDefault(item, 0) - beforeSnapshot.getOrDefault(item, 0);
    }

    public record ItemDelta(LoggedItemData item, int delta) {
        public String itemKey() {
            return item.itemKey();
        }

        public String displayName() {
            return item.displayName();
        }

        public String componentChanges() {
            return item.componentChanges();
        }
    }
}
