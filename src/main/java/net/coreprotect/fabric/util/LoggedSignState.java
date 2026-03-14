package net.coreprotect.fabric.util;

import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;

import java.util.Arrays;
import java.util.Objects;

public final class LoggedSignState {
    private static final DyeColor DEFAULT_COLOR = DyeColor.BLACK;

    private final boolean front;
    private final DyeColor color;
    private final boolean glowing;
    private final boolean waxed;
    private final String[] lines;

    public LoggedSignState(boolean front, DyeColor color, boolean glowing, boolean waxed, String[] lines) {
        this.front = front;
        this.color = color == null ? DEFAULT_COLOR : color;
        this.glowing = glowing;
        this.waxed = waxed;
        this.lines = normalizeLines(lines);
    }

    public static LoggedSignState of(boolean front, DyeColor color, boolean glowing, boolean waxed, String[] lines) {
        return new LoggedSignState(front, color, glowing, waxed, lines);
    }

    public static LoggedSignState blank(boolean front) {
        return new LoggedSignState(front, DEFAULT_COLOR, false, false, null);
    }

    public static LoggedSignState fromBlockEntity(SignBlockEntity signBlockEntity, boolean front) {
        SignText text = signBlockEntity.getText(front);
        String[] lines = new String[4];
        for (int index = 0; index < lines.length; index++) {
            lines[index] = text.getMessage(index, false).getString();
        }
        return new LoggedSignState(front, text.getColor(), text.isGlowing(), signBlockEntity.isWaxed(), lines);
    }

    public static LoggedSignState parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }

        String[] rawParts = payload.split("\\n", -1);
        if (rawParts.length == 0) {
            return null;
        }

        boolean front;
        if ("front".equalsIgnoreCase(rawParts[0])) {
            front = true;
        }
        else if ("back".equalsIgnoreCase(rawParts[0])) {
            front = false;
        }
        else {
            return null;
        }

        if (!containsStructuredMetadata(rawParts)) {
            String[] lines = new String[4];
            for (int index = 0; index < lines.length && index + 1 < rawParts.length; index++) {
                lines[index] = rawParts[index + 1];
            }
            return new LoggedSignState(front, DEFAULT_COLOR, false, false, lines);
        }

        DyeColor color = DEFAULT_COLOR;
        boolean glowing = false;
        boolean waxed = false;
        String[] lines = new String[4];
        for (int index = 1; index < rawParts.length; index++) {
            String part = rawParts[index];
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }

            String key = part.substring(0, separator);
            String value = part.substring(separator + 1);
            switch (key) {
                case "color" -> color = DyeColor.byId(value, DEFAULT_COLOR);
                case "glowing" -> glowing = Boolean.parseBoolean(value);
                case "waxed" -> waxed = Boolean.parseBoolean(value);
                default -> {
                    if (!key.startsWith("line")) {
                        continue;
                    }

                    try {
                        int lineIndex = Integer.parseInt(key.substring("line".length())) - 1;
                        if (lineIndex >= 0 && lineIndex < lines.length) {
                            lines[lineIndex] = value;
                        }
                    }
                    catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return new LoggedSignState(front, color, glowing, waxed, lines);
    }

    private static boolean containsStructuredMetadata(String[] rawParts) {
        for (int index = 1; index < rawParts.length; index++) {
            String part = rawParts[index];
            if (part.startsWith("color=")
                || part.startsWith("glowing=")
                || part.startsWith("waxed=")
                || part.startsWith("line1=")
                || part.startsWith("line2=")
                || part.startsWith("line3=")
                || part.startsWith("line4=")) {
                return true;
            }
        }
        return false;
    }

    private static String[] normalizeLines(String[] lines) {
        String[] normalized = new String[] { "", "", "", "" };
        if (lines == null) {
            return normalized;
        }

        for (int index = 0; index < normalized.length && index < lines.length; index++) {
            normalized[index] = lines[index] == null ? "" : lines[index];
        }
        return normalized;
    }

    public boolean front() {
        return front;
    }

    public DyeColor color() {
        return color;
    }

    public boolean glowing() {
        return glowing;
    }

    public boolean waxed() {
        return waxed;
    }

    public String[] lines() {
        return lines.clone();
    }

    public String serialize() {
        StringBuilder payload = new StringBuilder(front ? "front" : "back");
        payload.append('\n').append("color=").append(color.asString());
        payload.append('\n').append("glowing=").append(glowing);
        payload.append('\n').append("waxed=").append(waxed);
        for (int index = 0; index < lines.length; index++) {
            payload.append('\n').append("line").append(index + 1).append('=').append(lines[index]);
        }
        return payload.toString();
    }

    public SignText applyTo(SignText signText) {
        SignText updated = signText.withColor(color).withGlowing(glowing);
        for (int index = 0; index < lines.length; index++) {
            updated = updated.withMessage(index, Text.literal(lines[index]));
        }
        return updated;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LoggedSignState state)) {
            return false;
        }
        return front == state.front
            && glowing == state.glowing
            && waxed == state.waxed
            && color == state.color
            && Arrays.equals(lines, state.lines);
    }

    @Override
    public int hashCode() {
        return Objects.hash(front, color, glowing, waxed, Arrays.hashCode(lines));
    }
}
