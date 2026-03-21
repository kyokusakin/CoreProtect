package net.coreprotect.fabric.util;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryWrapper;

import java.util.List;
import java.util.Map;

public final class ContainerTransactionHelper {
    private ContainerTransactionHelper() {
    }

    public static List<ContainerDelta> diff(ItemStack before, ItemStack after) {
        return diff(before, after, null);
    }

    public static List<ContainerDelta> diff(ItemStack before, ItemStack after, RegistryWrapper.WrapperLookup lookup) {
        Map<LoggedItemData, Integer> beforeSnapshot = lookup == null
            ? ItemDeltaSnapshot.snapshotStacks(List.of(before == null ? ItemStack.EMPTY : before))
            : ItemDeltaSnapshot.snapshotStacks(List.of(before == null ? ItemStack.EMPTY : before), lookup);
        Map<LoggedItemData, Integer> afterSnapshot = lookup == null
            ? ItemDeltaSnapshot.snapshotStacks(List.of(after == null ? ItemStack.EMPTY : after))
            : ItemDeltaSnapshot.snapshotStacks(List.of(after == null ? ItemStack.EMPTY : after), lookup);
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
