package net.coreprotect.language;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class Selector {
    public static final String FIRST = "{1}";
    public static final String SECOND = "{2}";
    public static final String THIRD = "{3}";
    public static final String FOURTH = "{4}";

    static final Set<String> SELECTORS = new HashSet<>(Arrays.asList(FIRST, SECOND, THIRD, FOURTH));

    private Selector() {
        throw new IllegalStateException("Utility class");
    }

    static String processSelection(String output, String param) {
        String content = output;
        try {
            content = content.substring(content.indexOf('{') + 1, content.indexOf('}'));
        }
        catch (Exception ignored) {
            return output;
        }

        if (!content.contains("|")) {
            return output;
        }

        int selectorIndex = Integer.parseInt(param.substring(1, 2)) - 1;
        String[] parts = content.split("\\|", -1);
        int resolvedIndex = Math.max(0, Math.min(selectorIndex, parts.length - 1));
        return output.replace("{" + content + "}", parts[resolvedIndex]);
    }
}
