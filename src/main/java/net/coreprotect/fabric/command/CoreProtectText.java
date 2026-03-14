package net.coreprotect.fabric.command;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class CoreProtectText {
    private static final String PREFIX = "CoreProtect";
    private static final String PREFIX_SEPARATOR = " - ";
    private static final String LEGACY_PREFIX = PREFIX + PREFIX_SEPARATOR;

    private CoreProtectText() {
    }

    public static MutableText prefixed(String message) {
        return Text.literal(PREFIX).formatted(Formatting.DARK_AQUA)
            .append(Text.literal(PREFIX_SEPARATOR).formatted(Formatting.WHITE))
            .append(Text.literal(stripLegacyPrefix(message)).formatted(Formatting.WHITE));
    }

    public static MutableText line(String message) {
        return Text.literal(message).formatted(Formatting.WHITE);
    }

    public static MutableText header(String title) {
        return Text.literal("----- ").formatted(Formatting.WHITE)
            .append(Text.literal(title).formatted(Formatting.DARK_AQUA))
            .append(Text.literal(" -----").formatted(Formatting.WHITE));
    }

    public static MutableText usage(String usage, String description) {
        return Text.literal(usage).formatted(Formatting.DARK_AQUA)
            .append(Text.literal(" - ").formatted(Formatting.GRAY))
            .append(Text.literal(description).formatted(Formatting.WHITE));
    }

    private static String stripLegacyPrefix(String message) {
        if (message == null) {
            return "";
        }
        return message.startsWith(LEGACY_PREFIX) ? message.substring(LEGACY_PREFIX.length()) : message;
    }
}