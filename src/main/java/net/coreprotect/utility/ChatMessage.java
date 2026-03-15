package net.coreprotect.utility;

public final class ChatMessage {
    private ChatMessage() {
        throw new IllegalStateException("Utility class");
    }

    public static String parseQuotes(String string, String textColor) {
        if (string == null) {
            return "";
        }

        int indexFirst = string.indexOf("\"");
        int indexLast = string.lastIndexOf("\"");
        if (indexFirst > -1 && indexLast > indexFirst) {
            String quoteText = string.substring(indexFirst + 1, indexLast);
            String stripped = quoteText.replaceAll("(?i)\\u00A7[0-9A-FK-OR]", "");
            string = string.replace(quoteText, Color.DARK_AQUA + stripped + textColor);
        }

        return string;
    }
}
