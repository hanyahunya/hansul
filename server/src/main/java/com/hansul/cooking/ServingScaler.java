package com.hansul.cooking;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact arithmetic only: a proportional baseline is not an assertion of the right cooking quantity. */
public final class ServingScaler {
    private static final String NUMBER = "\\d+(?:\\.\\d+)?(?:/\\d+)?(?:\\s+\\d+/\\d+)?";
    private static final Pattern AMOUNT = Pattern.compile(
            "^\\s*(?<approx>약\\s*|대략\\s*)?(?<first>" + NUMBER + ")\\s*(?:(?<separator>[~∼–—-])\\s*(?<last>"
                    + NUMBER + "))?\\s*(?<unit>[^\\s\\d]+)(?:\\s+(?<note>.*))?\\s*$");

    private ServingScaler() {}

    public static ScaledAmount scale(String original, int sourceServings, int targetServings) {
        return scale(original, BigDecimal.valueOf(sourceServings), BigDecimal.valueOf(targetServings));
    }

    public static ScaledAmount scale(String original, BigDecimal sourceServings, BigDecimal targetServings) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(sourceServings, "sourceServings");
        Objects.requireNonNull(targetServings, "targetServings");
        if (sourceServings.signum() <= 0 || targetServings.signum() <= 0) {
            throw new IllegalArgumentException("Servings must be positive");
        }
        String cleaned = original.replace("\u200B", "").strip();
        if (cleaned.isEmpty()) {
            return new ScaledAmount(original, original, "", Status.UNPARSEABLE);
        }
        Matcher match = AMOUNT.matcher(cleaned);
        if (!match.matches()) {
            return new ScaledAmount(original, cleaned, "", Status.UNPARSEABLE);
        }
        try {
            Rational factor = Rational.fromDecimal(targetServings).divide(Rational.fromDecimal(sourceServings));
            Rational first = Rational.parse(match.group("first")).multiply(factor);
            String lastText = match.group("last");
            String amount = lastText == null
                    ? first.display()
                    : first.display() + match.group("separator") + Rational.parse(lastText).multiply(factor).display();
            String approximation = Objects.requireNonNullElse(match.group("approx"), "").strip();
            String note = Objects.requireNonNullElse(match.group("note"), "").strip();
            return new ScaledAmount(
                    original,
                    (approximation.isEmpty() ? "" : approximation + " ") + amount + match.group("unit"),
                    note,
                    sourceServings.compareTo(targetServings) == 0 ? Status.UNCHANGED : Status.PROPORTIONAL_ESTIMATE);
        } catch (ArithmeticException | NumberFormatException exception) {
            return new ScaledAmount(original, cleaned, "", Status.UNPARSEABLE);
        }
    }

    public enum Status {
        UNCHANGED,
        PROPORTIONAL_ESTIMATE,
        UNPARSEABLE
    }

    public record ScaledAmount(String original, String displayText, String originalNote, Status status) {
        public ScaledAmount {
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(displayText, "displayText");
            Objects.requireNonNull(originalNote, "originalNote");
            Objects.requireNonNull(status, "status");
        }
    }

    private record Rational(BigInteger numerator, BigInteger denominator) {
        private Rational {
            Objects.requireNonNull(numerator, "numerator");
            Objects.requireNonNull(denominator, "denominator");
            if (denominator.signum() == 0) {
                throw new ArithmeticException("Zero denominator");
            }
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
            BigInteger divisor = numerator.gcd(denominator);
            numerator = numerator.divide(divisor);
            denominator = denominator.divide(divisor);
        }

        static Rational parse(String raw) {
            String text = raw.strip();
            int mixedSpace = text.indexOf(' ');
            if (mixedSpace >= 0) {
                Rational whole = parse(text.substring(0, mixedSpace));
                Rational fractional = parse(text.substring(mixedSpace + 1));
                return whole.add(fractional);
            }
            int slash = text.indexOf('/');
            if (slash >= 0) {
                return new Rational(
                        new BigInteger(text.substring(0, slash)),
                        new BigInteger(text.substring(slash + 1)));
            }
            return fromDecimal(new BigDecimal(text));
        }

        static Rational fromDecimal(BigDecimal decimal) {
            BigInteger denominator = BigInteger.TEN.pow(Math.max(0, decimal.scale()));
            return new Rational(decimal.movePointRight(Math.max(0, decimal.scale())).toBigIntegerExact(), denominator);
        }

        Rational add(Rational other) {
            return new Rational(
                    numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator));
        }

        Rational multiply(Rational other) {
            return new Rational(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
        }

        Rational divide(Rational other) {
            return new Rational(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
        }

        String display() {
            if (denominator.equals(BigInteger.ONE)) {
                return numerator.toString();
            }
            BigInteger whole = numerator.divide(denominator);
            if (whole.signum() != 0 && finiteDecimal()) {
                return new BigDecimal(numerator).divide(new BigDecimal(denominator)).stripTrailingZeros().toPlainString();
            }
            if (whole.signum() != 0) {
                BigInteger remainder = numerator.remainder(denominator).abs();
                return whole + " " + remainder + "/" + denominator;
            }
            return numerator + "/" + denominator;
        }

        private boolean finiteDecimal() {
            BigInteger remaining = denominator;
            while (remaining.mod(BigInteger.TWO).signum() == 0) {
                remaining = remaining.divide(BigInteger.TWO);
            }
            BigInteger five = BigInteger.valueOf(5);
            while (remaining.mod(five).signum() == 0) {
                remaining = remaining.divide(five);
            }
            return remaining.equals(BigInteger.ONE);
        }
    }
}
