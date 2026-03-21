package net.coreprotect.fabric.util;

import net.minecraft.component.ComponentChanges;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

public record LoggedItemData(String itemKey, String displayName, String componentChanges, String serializedStack) {
    private static final Pattern COMPONENT_KEY_PATTERN = Pattern.compile("(?:\\{|, )(?<key>minecraft:[a-z0-9_./-]+)=>");
    private static final Pattern POTION_KEY_PATTERN = Pattern.compile("minecraft:potion / minecraft:(?<key>[a-z0-9_./-]+)");
    private static final String CUSTOM_NAME_MARKER = "minecraft:custom_name=>";
    private static final int MAX_COMPONENT_SUMMARY_LENGTH = 128;

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
        String rawComponentChanges = changes.isEmpty() ? "" : changes.toString();
        return new LoggedItemData(
            Registries.ITEM.getId(stack.getItem()).toString(),
            rawComponentChanges.contains(CUSTOM_NAME_MARKER) ? stack.getName().getString() : "",
            summarizeComponentChanges(rawComponentChanges),
            serializeUncountedStack(stack, lookup)
        );
    }

    public static LoggedItemData fromItemKey(String itemKey) {
        return new LoggedItemData(itemKey, "", "", "");
    }

    public boolean hasMetadata() {
        return !componentChanges.isBlank() || !serializedStack.isBlank();
    }

    public static String summarizeComponentChanges(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return summarizeComponentChanges(stack.getComponentChanges().toString());
    }

    public static String summarizeComponentChanges(String rawComponentChanges) {
        if (rawComponentChanges == null || rawComponentChanges.isBlank()) {
            return "";
        }

        Set<String> tokens = new LinkedHashSet<>();
        Matcher keyMatcher = COMPONENT_KEY_PATTERN.matcher(rawComponentChanges);
        while (keyMatcher.find()) {
            String key = simplifyIdentifier(keyMatcher.group("key"));
            if (key.isBlank()) {
                continue;
            }
            tokens.add(summarizeComponentToken(key, rawComponentChanges));
        }

        String summary = String.join(",", tokens);
        if (summary.isBlank()) {
            summary = abbreviate(rawComponentChanges, MAX_COMPONENT_SUMMARY_LENGTH);
        }
        else if (summary.length() > MAX_COMPONENT_SUMMARY_LENGTH) {
            summary = abbreviate(summary, MAX_COMPONENT_SUMMARY_LENGTH);
        }

        return summary + "#" + shortHash(rawComponentChanges);
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

    private static String summarizeComponentToken(String key, String rawComponentChanges) {
        return switch (key) {
            case "banner_patterns" -> appendCount(key, countOccurrences(rawComponentChanges, "minecraft:banner_pattern / minecraft:"));
            case "enchantments", "stored_enchantments" -> appendCount(key, countOccurrences(rawComponentChanges, "minecraft:enchantment / minecraft:"));
            case "potion_contents" -> {
                Matcher potionMatcher = POTION_KEY_PATTERN.matcher(rawComponentChanges);
                if (potionMatcher.find()) {
                    yield key + "[" + potionMatcher.group("key") + "]";
                }
                yield key;
            }
            default -> key;
        };
    }

    private static String appendCount(String key, int count) {
        return count > 0 ? key + "[" + count + "]" : key;
    }

    private static int countOccurrences(String value, String token) {
        if (value == null || value.isBlank() || token == null || token.isBlank()) {
            return 0;
        }

        int matches = 0;
        int index = 0;
        while (true) {
            index = value.indexOf(token, index);
            if (index < 0) {
                return matches;
            }
            matches++;
            index += token.length();
        }
    }

    private static String shortHash(String value) {
        CRC32 crc32 = new CRC32();
        crc32.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Long.toHexString(crc32.getValue());
    }

    private static String abbreviate(String value, int maxLength) {
        String normalized = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
