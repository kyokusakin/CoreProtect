package net.coreprotect.fabric.util;

import net.minecraft.component.ComponentChanges;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

public record LoggedItemData(String itemKey, String displayName, String componentChanges, String serializedStack) {
    public LoggedItemData {
        itemKey = itemKey == null ? "" : itemKey;
        displayName = displayName == null ? "" : displayName;
        componentChanges = componentChanges == null ? "" : componentChanges;
        serializedStack = serializedStack == null ? "" : serializedStack;
    }

    public static LoggedItemData fromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return fromItemKey("");
        }

        ComponentChanges changes = stack.getComponentChanges();
        return new LoggedItemData(
            Registries.ITEM.getId(stack.getItem()).toString(),
            stack.getName().getString(),
            changes.isEmpty() ? "" : changes.toString(),
            stack.getImmutableComponents().toString()
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
    private static String simplifyIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }
}
