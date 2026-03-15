package net.coreprotect.utility;

import java.util.Locale;

public final class StringUtils {
    private StringUtils() {
        throw new IllegalStateException("Utility class");
    }

    public static String capitalize(String string, boolean allWords) {
        if (string == null || string.isEmpty()) {
            return string;
        }

        if (string.length() <= 1) {
            return string.toUpperCase(Locale.ROOT);
        }

        string = string.toLowerCase(Locale.ROOT);

        if (allWords) {
            StringBuilder builder = new StringBuilder();
            for (String substring : string.split(" ")) {
                if (substring.length() >= 3 && !substring.equals("and") && !substring.equals("the")) {
                    substring = substring.substring(0, 1).toUpperCase(Locale.ROOT) + substring.substring(1);
                }
                if (builder.length() > 0) {
                    builder.append(" ");
                }
                builder.append(substring);
            }
            return builder.toString();
        }

        return string.substring(0, 1).toUpperCase(Locale.ROOT) + string.substring(1);
    }
}
