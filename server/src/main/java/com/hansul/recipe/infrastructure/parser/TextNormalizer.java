package com.hansul.recipe.infrastructure.parser;

import java.text.Normalizer;

final class TextNormalizer {

    private TextNormalizer() {
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }

        var normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        var result = new StringBuilder(normalized.length());
        var lastWasSpace = true;
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (Character.getType(codePoint) == Character.FORMAT) {
                continue;
            }
            if (Character.isWhitespace(codePoint)
                    || Character.getType(codePoint) == Character.SPACE_SEPARATOR) {
                if (!lastWasSpace) {
                    result.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            result.appendCodePoint(codePoint);
            lastWasSpace = false;
        }
        return result.toString().trim();
    }
}
