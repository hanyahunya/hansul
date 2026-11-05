package com.hansul.recipe.video;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Text rules shared by both passes and the validator, so stored evidence and quotes compare alike. */
final class VideoText {

    // Format characters (zero-width space and joiners, BOM, direction marks) and non-whitespace controls.
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\x00-\\x08\\x0E-\\x1F\\x7F-\\x84\\x86-\\x9F]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NOT_COMPACT = Pattern.compile("[\\s\\p{P}\\p{S}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final int MIN_QUOTE_LENGTH = 2;

    private VideoText() {
    }

    /** NFC, invisible characters removed, NBSP as a space, whitespace collapsed, trimmed; null reads as "". */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        var text = Normalizer.normalize(value, Normalizer.Form.NFC);
        text = INVISIBLE.matcher(text).replaceAll("").replace(' ', ' ');
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }

    /** Normalized, lower-cased, without whitespace, punctuation or symbols. */
    static String compact(String value) {
        return NOT_COMPACT.matcher(normalize(value).toLowerCase(Locale.ROOT)).replaceAll("");
    }

    static boolean quoteMatches(String quote, String source) {
        var needle = compact(quote);
        return needle.length() >= MIN_QUOTE_LENGTH && compact(source).contains(needle);
    }

    /** Keeps line breaks (a description is shown as written) but drops invisible characters. */
    static String multiline(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        var text = Normalizer.normalize(value, Normalizer.Form.NFC)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        text = INVISIBLE.matcher(text).replaceAll("").strip();
        return text.isEmpty() ? null : clamp(text, maxLength);
    }

    /**
     * Shortens to at most {@code maxLength} chars, preferring a word boundary and never splitting a number
     * or a surrogate pair: a cut "150g" must not leave a "15" that the grounding check would trust.
     */
    static String clamp(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        var cut = maxLength;
        for (var index = maxLength; index > maxLength / 2; index--) {
            if (Character.isWhitespace(value.charAt(index))) {
                cut = index;
                break;
            }
        }
        while (cut > 0 && isNumberPart(value.charAt(cut - 1)) && isNumberPart(value.charAt(cut))) {
            cut--;
        }
        if (cut > 0 && Character.isHighSurrogate(value.charAt(cut - 1))) {
            cut--;
        }
        return value.substring(0, cut).strip();
    }

    private static boolean isNumberPart(char character) {
        return (character >= '0' && character <= '9') || character == '.' || character == ',' || character == '/';
    }
}
