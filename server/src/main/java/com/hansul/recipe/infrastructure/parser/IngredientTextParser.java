package com.hansul.recipe.infrastructure.parser;

import com.hansul.recipe.domain.ParsedIngredient;
import com.hansul.recipe.domain.SourceEvidence;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class IngredientTextParser {

    private static final String NUMBER = "(?:[0-9]+\\s+[0-9]+\\s*/\\s*[0-9]+|[0-9]+\\s*/\\s*[0-9]+|[0-9]+(?:\\.[0-9]+)?)";
    private static final Pattern NAME_AND_QUANTITY = Pattern.compile(
            "^(?<name>.+?)\\s+(?<quantity>(?:(?:약\\s*)?" + NUMBER
                    + "\\s*[^\\s(,]*(?:\\s*\\([^)]*\\))?|약간|적당량|조금|취향껏|필요량))$"
    );
    private static final Pattern COMPACT_NAME_AND_QUANTITY = Pattern.compile(
            "^(?<name>.*?[^0-9\\s])(?<quantity>" + NUMBER
                    + "\\s*[^\\s(,]+(?:\\s*\\([^)]*\\))?)$"
    );
    private static final Pattern AMOUNT_AND_UNIT = Pattern.compile(
            "^(?:약\\s*)?(?:(?<whole>[0-9]+)\\s+)?(?:(?<numerator>[0-9]+)\\s*/\\s*(?<denominator>[0-9]+)|(?<decimal>[0-9]+(?:\\.[0-9]+)?))\\s*(?<unit>[^\\s(]+)?"
    );
    private static final Map<String, String> UNIT_ALIASES = Map.ofEntries(
            Map.entry("그램", "g"),
            Map.entry("킬로그램", "kg"),
            Map.entry("밀리리터", "ml"),
            Map.entry("리터", "l"),
            Map.entry("큰술", "큰술"),
            Map.entry("작은술", "작은술")
    );

    public ParsedIngredient parse(int position, String rawText, SourceEvidence evidence) {
        var normalized = TextNormalizer.normalize(rawText);
        var matcher = NAME_AND_QUANTITY.matcher(normalized);
        if (!matcher.matches()) {
            matcher = COMPACT_NAME_AND_QUANTITY.matcher(normalized);
        }

        String name;
        String quantityText;
        if (matcher.matches()) {
            name = TextNormalizer.normalize(matcher.group("name"));
            quantityText = TextNormalizer.normalize(matcher.group("quantity"));
        } else {
            name = normalized;
            quantityText = null;
        }

        var amountAndUnit = parseAmountAndUnit(quantityText);
        return new ParsedIngredient(
                position,
                rawText,
                name,
                quantityText,
                amountAndUnit.amount(),
                amountAndUnit.unit(),
                amountAndUnit.warning(),
                evidence
        );
    }

    private AmountAndUnit parseAmountAndUnit(String quantityText) {
        if (quantityText == null) {
            return new AmountAndUnit(null, null, null);
        }

        var matcher = AMOUNT_AND_UNIT.matcher(quantityText);
        if (!matcher.find()) {
            return new AmountAndUnit(null, null, null);
        }

        final BigDecimal amount;
        try {
            if (matcher.group("denominator") != null) {
                var numerator = new BigDecimal(matcher.group("numerator"));
                var denominator = new BigDecimal(matcher.group("denominator"));
                if (denominator.signum() == 0) {
                    return new AmountAndUnit(
                            null,
                            null,
                            "Quantity could not be normalized; original text was preserved"
                    );
                }
                var fraction = numerator.divide(denominator, 6, RoundingMode.HALF_UP);
                amount = matcher.group("whole") == null
                        ? fraction
                        : fraction.add(new BigDecimal(matcher.group("whole")));
            } else {
                amount = new BigDecimal(matcher.group("decimal"));
            }
        } catch (ArithmeticException | NumberFormatException exception) {
            return new AmountAndUnit(
                    null,
                    null,
                    "Quantity could not be normalized; original text was preserved"
            );
        }

        var rawUnit = matcher.group("unit");
        var unit = rawUnit == null || rawUnit.isBlank()
                ? null
                : UNIT_ALIASES.getOrDefault(rawUnit.toLowerCase(Locale.ROOT), rawUnit);
        return new AmountAndUnit(amount.stripTrailingZeros(), unit, null);
    }

    private record AmountAndUnit(BigDecimal amount, String unit, String warning) {
    }
}
