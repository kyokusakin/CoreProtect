package net.coreprotect.fabric.util;

import net.minecraft.item.ItemStack;

import java.util.List;
import java.util.Map;

public final class ContainerTransactionHelper {
    private ContainerTransactionHelper() {
    }

    public static List<ContainerDelta> diff(ItemStack before, ItemStack after) {
        Map<LoggedItemData, Integer> beforeSnapshot = ItemDeltaSnapshot.snapshotStacks(List.of(before == null ? ItemStack.EMPTY : before));
        Map<LoggedItemData, Integer> afterSnapshot = ItemDeltaSnapshot.snapshotStacks(List.of(after == null ? ItemStack.EMPTY : after));
        return diff(beforeSnapshot, afterSnapshot);
    }

    public static List<ContainerDelta> diff(Map<LoggedItemData, Integer> before, Map<LoggedItemData, Integer> after) {
        return ItemDeltaSnapshot.diff(before, after).stream()
            .map(delta -> new ContainerDelta(delta.item(), Math.abs(delta.delta()), delta.delta() > 0))
            .toList();
    }

    public record ContainerDelta(LoggedItemData item, int count, boolean added) {
    }
}