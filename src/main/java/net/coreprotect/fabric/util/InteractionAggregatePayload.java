package net.coreprotect.fabric.util;

public final class InteractionAggregatePayload {
    private static final String CLICK_COUNT_KEY = "__cp_click_count";
    private static final String CLICK_START_KEY = "__cp_click_start";
    private static final String CLICK_END_KEY = "__cp_click_end";

    private InteractionAggregatePayload() {
    }

    public static int clickCount(String payload) {
        String value = metadataValue(payload, CLICK_COUNT_KEY);
        if (value == null || value.isBlank()) {
            return 1;
        }

        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        }
        catch (NumberFormatException ignored) {
            return 1;
        }
    }

    public static String stripMetadata(String payload) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }

        StringBuilder cleaned = new StringBuilder();
        for (String line : payload.split("\\R", -1)) {
            if (isMetadataLine(line)) {
                continue;
            }
            if (cleaned.length() > 0) {
                cleaned.append('\n');
            }
            cleaned.append(line);
        }
        return cleaned.toString();
    }

    public static String withAggregation(String payload, int clickCount, long firstTimestamp, long lastTimestamp) {
        String basePayload = stripMetadata(payload);
        if (clickCount <= 1) {
            return basePayload;
        }

        StringBuilder combined = new StringBuilder();
        if (basePayload != null && !basePayload.isBlank()) {
            combined.append(basePayload);
        }
        appendMetadata(combined, CLICK_COUNT_KEY, Integer.toString(clickCount));
        appendMetadata(combined, CLICK_START_KEY, Long.toString(firstTimestamp));
        appendMetadata(combined, CLICK_END_KEY, Long.toString(lastTimestamp));
        return combined.toString();
    }

    private static String metadataValue(String payload, String key) {
        if (payload == null || payload.isBlank()) {
            return null;
        }

        String prefix = key + "=";
        for (String line : payload.split("\\R", -1)) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
        return null;
    }

    private static boolean isMetadataLine(String line) {
        return line.startsWith(CLICK_COUNT_KEY + "=")
            || line.startsWith(CLICK_START_KEY + "=")
            || line.startsWith(CLICK_END_KEY + "=");
    }

    private static void appendMetadata(StringBuilder builder, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(key).append('=').append(value);
    }
}
