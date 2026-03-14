package net.coreprotect.fabric.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PurgeCommandParser {
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(mo|y|w|d|h|m|s)", Pattern.CASE_INSENSITIVE);

    private PurgeCommandParser() {
    }

    public static PurgeCommandOptions parse(String input) {
        if (input == null || input.isBlank()) {
            return new PurgeCommandOptions(null, null, null, false);
        }

        Integer seconds = null;
        String worldFilter = null;
        List<String> includeTargets = new ArrayList<>();
        boolean optimize = false;
        Mode mode = Mode.NONE;

        for (String rawToken : input.trim().split("\\s+")) {
            String token = normalize(rawToken);
            if (token.isBlank()) {
                continue;
            }

            if ("#optimize".equalsIgnoreCase(token) || "optimize".equalsIgnoreCase(token)) {
                optimize = true;
                mode = Mode.NONE;
                continue;
            }

            PrefixToken prefixToken = parsePrefixToken(token);
            if (prefixToken != null) {
                mode = prefixToken.mode();
                if (prefixToken.value().isBlank()) {
                    continue;
                }
                token = prefixToken.value();
            }

            switch (mode) {
                case TIME -> {
                    seconds = parseDuration(token);
                    mode = token.endsWith(",") ? Mode.TIME : Mode.NONE;
                }
                case WORLD -> {
                    String parsedWorld = parseWorldFilter(token);
                    if (parsedWorld != null) {
                        worldFilter = parsedWorld;
                    }
                    mode = token.endsWith(",") ? Mode.WORLD : Mode.NONE;
                }
                case INCLUDE -> mode = appendCsvValues(includeTargets, token) ? Mode.INCLUDE : Mode.NONE;
                default -> mode = Mode.NONE;
            }
        }

        return new PurgeCommandOptions(seconds, worldFilter, includeTargets.isEmpty() ? null : includeTargets, optimize);
    }

    private static boolean appendCsvValues(List<String> target, String token) {
        String cleaned = stripTrailingComma(token);
        if (!cleaned.isBlank()) {
            for (String rawValue : cleaned.split(",")) {
                String value = rawValue.trim();
                if (!value.isBlank()) {
                    target.add(value);
                }
            }
        }
        return token.endsWith(",");
    }

    private static PrefixToken parsePrefixToken(String token) {
        if (startsWithAny(token, "t:", "time:")) {
            return new PrefixToken(Mode.TIME, stripPrefix(token, "t:", "time:"));
        }
        if (startsWithAny(token, "r:", "w:", "world:")) {
            return new PrefixToken(Mode.WORLD, stripPrefix(token, "r:", "w:", "world:"));
        }
        if (startsWithAny(token, "i:", "include:", "item:", "items:", "b:", "block:", "blocks:")) {
            return new PrefixToken(Mode.INCLUDE, stripPrefix(token, "i:", "include:", "item:", "items:", "b:", "block:", "blocks:"));
        }
        return null;
    }

    private static String normalize(String token) {
        return token.trim().replace("\\", "").replace("'", "").replace("\"", "");
    }

    private static boolean startsWithAny(String input, String... prefixes) {
        for (String prefix : prefixes) {
            if (input.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
    }

    private static String stripPrefix(String input, String... prefixes) {
        for (String prefix : prefixes) {
            if (input.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return input.substring(prefix.length());
            }
        }
        return input;
    }

    private static String stripTrailingComma(String input) {
        return input == null ? "" : input.replaceAll(",+$", "").trim();
    }

    private static Integer parseDuration(String input) {
        String cleaned = stripTrailingComma(input).toLowerCase(Locale.ROOT);
        if (cleaned.isBlank()) {
            return null;
        }
        if (cleaned.matches("\\d+")) {
            try {
                return Integer.parseInt(cleaned);
            }
            catch (NumberFormatException exception) {
                return null;
            }
        }

        Matcher matcher = DURATION_PART.matcher(cleaned);
        double totalSeconds = 0.0D;
        int cursor = 0;
        boolean matched = false;
        while (matcher.find()) {
            if (matcher.start() != cursor) {
                return null;
            }

            matched = true;
            double value = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            totalSeconds += switch (unit) {
                case "y" -> value * 31536000.0D;
                case "mo" -> value * 2592000.0D;
                case "w" -> value * 604800.0D;
                case "d" -> value * 86400.0D;
                case "h" -> value * 3600.0D;
                case "m" -> value * 60.0D;
                case "s" -> value;
                default -> 0.0D;
            };
            cursor = matcher.end();
        }

        if (!matched || cursor != cleaned.length()) {
            return null;
        }
        return (int) Math.round(totalSeconds);
    }

    private static String parseWorldFilter(String input) {
        String cleaned = stripTrailingComma(input);
        return cleaned.isBlank() ? null : cleaned;
    }

    private enum Mode {
        NONE,
        TIME,
        WORLD,
        INCLUDE
    }

    private record PrefixToken(Mode mode, String value) {
    }
}
