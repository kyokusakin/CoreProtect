package net.coreprotect.fabric.util;

import net.minecraft.component.ComponentChanges;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;

public record LoggedItemData(String itemKey, String displayName, String componentChanges, String serializedStack) {
    public LoggedItemData {
        itemKey = itemKey == null ? "" : itemKey;
        displayName = displayName == null ? "" : displayName;
        componentChanges = componentChanges == null ? "" : componentChanges;
        serializedStack = serializedStack == null ? "" : serializedStack;
    }

    public static LoggedItemData fromStack(ItemStack stack) {
        return fromStack(stack, null);
    }

    public static LoggedItemData fromStack(ItemStack stack, RegistryWrapper.WrapperLookup lookup) {
        if (stack == null || stack.isEmpty()) {
            return fromItemKey("");
        }

        ComponentChanges changes = stack.getComponentChanges();
        return new LoggedItemData(
            Registries.ITEM.getId(stack.getItem()).toString(),
            stack.getName().getString(),
            changes.isEmpty() ? "" : changes.toString(),
            serializeUncountedStack(stack, lookup)
        );
    }

    public static LoggedItemData fromItemKey(String itemKey) {
        return new LoggedItemData(itemKey, "", "", "");
    }

    public boolean hasMetadata() {
        return !componentChanges.isBlank() || !serializedStack.isBlank();
    }

    public String simplifiedItemKey() {
        return simplifyIdentifier(itemKey);
    }

    private static String serializeUncountedStack(ItemStack stack, RegistryWrapper.WrapperLookup lookup) {
        if (stack == null || stack.isEmpty() || lookup == null) {
            return "";
        }

        NbtElement encoded = ItemStack.UNCOUNTED_CODEC
            .encodeStart(RegistryOps.of(NbtOps.INSTANCE, lookup), stack)
            .result()
            .orElse(null);
        return encoded == null ? "" : encoded.toString();
    }

    private static String simplifyIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }
}
