package net.coreprotect.fabric.util;

import net.minecraft.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record LoggedItemChange(LoggedItemData item, int count, String contextLabel) {
    private static final Pattern LEGACY_TARGET_PATTERN = Pattern.compile("^([^\\s]+?)x(\\d+).*$");

    public LoggedItemChange {
        item = item == null ? LoggedItemData.fromItemKey("") : item;
        count = Math.max(1, count);
        contextLabel = contextLabel == null ? "" : contextLabel;
    }

    public static LoggedItemChange fromStack(ItemStack stack, int count, String contextLabel) {
        return new LoggedItemChange(LoggedItemData.fromStack(stack), count, contextLabel);
    }

    public static LoggedItemChange parse(String target, String payload) {
        if (payload == null || payload.isBlank()) {
            return fromLegacyTarget(target);
        }

        String[] lines = payload.split("\\R");
        if (lines.length > 0 && lines[0].contains("=")) {
            Map<String, String> values = parseKeyValueLines(lines);
            String itemKey = values.getOrDefault("item", extractLegacyItemKey(target));
            int count = parseCount(values.get("count"));
            return new LoggedItemChange(
                new LoggedItemData(
                    itemKey,
                    values.getOrDefault("name", ""),
                    values.getOrDefault("components", ""),
                    values.getOrDefault("nbt", "")
                ),
                count,
                values.getOrDefault("context", "")
            );
        }

        String itemKey = lines.length > 0 ? lines[0] : extractLegacyItemKey(target);
        int count = lines.length > 1 ? parseCount(lines[1]) : 1;
        String contextLabel = lines.length > 2 ? lines[2] : "";
        return new LoggedItemChange(LoggedItemData.fromItemKey(itemKey), count, contextLabel);
    }

    public String summaryTarget() {
        StringBuilder summary = new StringBuilder(baseItemLabel());
        summary.append("x").append(count);
        if (item.hasMetadata()) {
            summary.append(" [meta]");
        }
        if (!contextLabel.isBlank()) {
            summary.append(" via ").append(simplifyIdentifier(contextLabel));
        }
        return summary.toString();
    }

    public String lookupTarget() {
        StringBuilder summary = new StringBuilder(baseItemLabel());
        summary.append("x").append(count);
        if (item.hasMetadata()) {
            if (!item.displayName().isBlank()) {
                summary.append(" \"").append(abbreviate(item.displayName(), 40)).append("\"");
            }
            summary.append(" {").append(abbreviate(item.componentChanges(), 96)).append("}");
        }
        if (!contextLabel.isBlank()) {
            summary.append(" via ").append(simplifyIdentifier(contextLabel));
        }
        return summary.toString();
    }

    public String serializePayload() {
        StringBuilder payload = new StringBuilder();
        appendEntry(payload, "item", item.itemKey());
        appendEntry(payload, "count", Integer.toString(count));
        if (!contextLabel.isBlank()) {
            appendEntry(payload, "context", contextLabel);
        }
        if (!item.displayName().isBlank()) {
            appendEntry(payload, "name", item.displayName());
        }
        if (item.hasMetadata()) {
            appendEntry(payload, "components", item.componentChanges());
        }
        if (!item.serializedStack().isBlank()) {
            appendEntry(payload, "nbt", item.serializedStack());
        }
        return payload.toString();
    }

    private static LoggedItemChange fromLegacyTarget(String target) {
        return new LoggedItemChange(LoggedItemData.fromItemKey(extractLegacyItemKey(target)), 1, "");
    }

    private String baseItemLabel() {
        String itemKey = item.simplifiedItemKey();
        if (!itemKey.isBlank()) {
            return itemKey;
        }
        if (!item.displayName().isBlank()) {
            return abbreviate(item.displayName(), 40);
        }
        return "unknown_item";
    }

    private static void appendEntry(StringBuilder payload, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!payload.isEmpty()) {
            payload.append('\n');
        }
        payload.append(key).append('=').append(escape(value));
    }

    private static Map<String, String> parseKeyValueLines(String[] lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            values.put(line.substring(0, separator), unescape(line.substring(separator + 1)));
        }
        return values;
    }

    private static int parseCount(String value) {
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        }
        catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static String extractLegacyItemKey(String target) {
        if (target == null || target.isBlank()) {
            return "";
        }

        Matcher matcher = LEGACY_TARGET_PATTERN.matcher(target);
        if (!matcher.matches()) {
            return "";
        }

        String itemKey = matcher.group(1);
        if (itemKey.isBlank()) {
            return "";
        }
        return itemKey.contains(":") ? itemKey : "minecraft:" + itemKey;
    }

    private static String simplifyIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }

    private static String abbreviate(String value, int maxLength) {
        String normalized = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
    }

    private static String unescape(String value) {
        StringBuilder builder = new StringBuilder();
        boolean escaping = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!escaping) {
                if (current == '\\') {
                    escaping = true;
                }
                else {
                    builder.append(current);
                }
                continue;
            }

            switch (current) {
                case 'n':
                    builder.append('\n');
                    break;
                case 'r':
                    builder.append('\r');
                    break;
                case '\\':
                    builder.append('\\');
                    break;
                default:
                    builder.append(current);
                    break;
            }
            escaping = false;
        }

        if (escaping) {
            builder.append('\\');
        }
        return builder.toString();
    }
}
