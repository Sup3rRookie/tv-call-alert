package dev.tvcallalert.core;

/** Cleans the caller name typed on the TV before it is stored and shown full screen. */
public final class NameSanitizer {
    private NameSanitizer() {
    }

    /** Keeps letters, digits and single spaces, capped at the name limit. */
    public static String clean(String input, String fallback) {
        if (input == null) {
            return fallback;
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        boolean lastSpace = true;
        int i = 0;
        while (i < input.length() && count < Limits.MAX_NAME_CHARS) {
            int cp = input.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(cp);
                count++;
                lastSpace = false;
            } else if (Character.isSpaceChar(cp) && !lastSpace) {
                sb.append(' ');
                count++;
                lastSpace = true;
            }
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? fallback : out;
    }
}
