package com.hansul.recipe.video;

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.Normalizer;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Numbers a text states and numbers a source can back. A summary may only use numbers its sources say,
 * and Korean speech says them as words ("두 스푼", "삼 분", "한 개 반") where the summary writes digits.
 * Spoken numerals count only next to a unit or counter word, so 이 정도, 오늘 or 사과 are not numbers.
 */
public final class VideoNumberGrounding {

    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final char FRACTION_SLASH = '\u2044';
    private static final Pattern DIGIT_NUMBER = Pattern.compile(
            "(?<![0-9./])(?:"
                    + "(?<whole>[0-9]+)\\s+(?<mixedNumerator>[0-9]+)\\s*/\\s*(?<mixedDenominator>[0-9]+)"
                    + "|(?<numerator>[0-9]+)\\s*/\\s*(?<denominator>[0-9]+)"
                    + "|(?<value>[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?|[0-9]+(?:\\.[0-9]+)?)"
                    + ")(?![0-9])");

    // Amounts and times. In a summary a spoken numeral counts only with these, so "한 번 더" and "두 배로" stay words.
    private static final List<String> MEASURE_WORDS = List.of(
            "스푼", "숟가락", "숟갈", "큰술", "작은술", "티스푼", "컵", "국자", "꼬집", "줌", "그램", "킬로", "키로",
            "미리", "밀리", "리터", "센티", "분", "초", "시간", "도");
    private static final Set<String> MEASURES = Set.copyOf(MEASURE_WORDS);
    private static final List<String> UNIT_WORDS = Stream.concat(MEASURE_WORDS.stream(), Stream.of(
            "인분", "등분", "개", "장", "알", "쪽", "대", "봉", "봉지", "팩", "마리", "모", "단", "송이", "방울", "바퀴",
            "번", "배", "공기", "포기", "톨", "잔", "통", "조각", "그릇", "줄기")).toList();
    // Said with Sino-Korean numerals (삼 분, 이백 그램); every other unit is counted natively (두 스푼, 세 개).
    private static final Set<String> SINO_READ_UNITS =
            Set.of("분", "초", "도", "그램", "킬로", "키로", "미리", "밀리", "리터", "센티", "인분", "등분");
    // Glued to a one-syllable Sino-Korean numeral these counters spell everyday words: 일단, 이번, 이대로, 육개장,
    // 팔도, 백도.
    private static final Set<String> GLUED_COUNTERS =
            Set.of("개", "장", "알", "쪽", "줌", "대", "봉", "팩", "모", "단", "번", "배", "도");
    // Numerals and 반 glued to a unit that are words of their own: 네모나게, 세모, 반대로, 하나도, 한도, 구분, 이분이.
    private static final Set<String> NOT_QUANTITIES = Set.of("네모", "세모", "반대", "하나도", "한도", "구분", "이분");
    // What may follow the unit of a lone Sino-Korean numeral: 삼 분간, 오 분쯤, 사등분해서; not 이 도마, 이 분량,
    // 이 초콜릿 or the fraction 삼분의 일.
    private static final Pattern LONE_SINO_UNIT_ENDS = Pattern.compile(
            "(?![가-힣])|(?:간|동안|정도|쯤|가량|이|가|을|를|은|는|에|으로|로|도|만|씩|부터|까지|마다|후|뒤|짜리|입니다|내외"
                    + "|하|해|한|할|합)");
    private static final String UNIT = "(?<unit>" + longestFirst(UNIT_WORDS) + ")";
    // 반 ends the quantity: 한 개 반, 한 개 반을, 반쯤; but not 반죽, 반찬 or 반복.
    private static final String HALF_ENDS = "(?=(?:을|이|은|만|씩|쯤|정도|가량)?(?![가-힣]))";
    private static final String TRAILING_HALF = "(?:\\s?(?<half>반)" + HALF_ENDS + ")?";
    private static final String NATIVE_ONES = "하나|한|둘|두|셋|세|석|넷|네|넉|다섯|여섯|일곱|여덟|아홉";
    private static final Map<String, List<Integer>> NATIVE_RANGES = Map.ofEntries(
            Map.entry("한두", List.of(1, 2)), Map.entry("두세", List.of(2, 3)), Map.entry("세네", List.of(3, 4)),
            Map.entry("서너", List.of(3, 4)), Map.entry("서넛", List.of(3, 4)), Map.entry("네댓", List.of(4, 5)),
            Map.entry("너댓", List.of(4, 5)), Map.entry("너덧", List.of(4, 5)), Map.entry("네다섯", List.of(4, 5)),
            Map.entry("대여섯", List.of(5, 6)), Map.entry("예닐곱", List.of(6, 7)), Map.entry("일고여덟", List.of(7, 8)));
    // A spoken range (두세 번, 이삼 분) grounds both of its numbers; a transcript may also write 두 세 번.
    private static final Pattern NATIVE = Pattern.compile(
            "(?<![가-힣])(?:(?<range>한\\s?두|두\\s?세|세\\s?네|" + longestFirst(List.copyOf(NATIVE_RANGES.keySet())) + ")"
                    + "|(?<tens>열|스물|스무|서른|마흔|쉰)(?:\\s?(?<tensOnes>" + NATIVE_ONES + "))?"
                    + "|(?<ones>" + NATIVE_ONES + "))"
                    + "\\s?(?:" + UNIT + TRAILING_HALF + "|(?<bareHalf>반)" + HALF_ENDS + ")");
    private static final String SINO_DIGIT = "[일이삼사오육륙칠팔구]";
    private static final Pattern SINO = Pattern.compile(
            "(?<![가-힣])(?=[일이삼사오육륙칠팔구십백천])"
                    + "(?:(?<rangeLow>[이삼사오육칠팔])(?<rangeHigh>[삼사오육륙칠팔구])(?<rangeScale>[십백천]?)"
                    + "|(?<sino>(?:" + SINO_DIGIT + "?천)?(?:" + SINO_DIGIT + "?백)?(?:" + SINO_DIGIT + "?십)?"
                    + SINO_DIGIT + "?))"
                    + "(?<space>\\s?)" + UNIT + TRAILING_HALF);
    private static final Pattern LEADING_HALF = Pattern.compile("(?<![가-힣])반\\s?" + UNIT);
    private static final Pattern DIGITS_UNIT_HALF = Pattern.compile(
            "(?<![0-9./])(?<amount>[0-9]+(?:\\.[0-9]+)?)\\s?" + UNIT + "\\s?반" + HALF_ENDS);

    private static final Map<String, Integer> NATIVE_VALUES = Map.ofEntries(
            Map.entry("하나", 1), Map.entry("한", 1), Map.entry("둘", 2), Map.entry("두", 2),
            Map.entry("셋", 3), Map.entry("세", 3), Map.entry("석", 3), Map.entry("넷", 4), Map.entry("네", 4),
            Map.entry("넉", 4), Map.entry("다섯", 5), Map.entry("여섯", 6), Map.entry("일곱", 7),
            Map.entry("여덟", 8), Map.entry("아홉", 9), Map.entry("열", 10), Map.entry("스물", 20),
            Map.entry("스무", 20), Map.entry("서른", 30), Map.entry("마흔", 40), Map.entry("쉰", 50));
    private static final Map<Character, Integer> SINO_DIGITS = Map.of(
            '일', 1, '이', 2, '삼', 3, '사', 4, '오', 5, '육', 6, '륙', 6, '칠', 7, '팔', 8, '구', 9);

    private VideoNumberGrounding() {
    }

    /**
     * Digit numbers: {@code 200}, {@code 1.5}, {@code 1,000} (1000), {@code 1/2} and {@code ½} (0.5) and
     * {@code 1 1/2} (1.5 and its whole part 1), without trailing zeros; plus native and Sino-Korean numerals of an
     * amount or a time ("세 스푼", "반 컵", "삼 분"), but not of a counter ("한 번", "두 개").
     */
    public static Set<BigDecimal> numbersIn(String text) {
        return numbers(text, true);
    }

    /** {@link #numbersIn} plus numerals said with any unit or counter word ("두 개", "두세 번", "네 인분"). */
    public static Set<BigDecimal> groundedNumbers(String text) {
        return numbers(text, false);
    }

    /** The numbers of {@code text} that none of the sources can back; empty means grounded. */
    public static Set<BigDecimal> missingNumbers(String text, Collection<String> sources) {
        return missing(numbersIn(text), sources);
    }

    /**
     * Like {@link #missingNumbers}, but for an amount or a serving count every spoken numeral counts: "세 개" or
     * "네 인분" must be what the source says, while a step may say "한 번 더" without the video counting it.
     */
    public static Set<BigDecimal> missingAmounts(String amount, Collection<String> sources) {
        return missing(groundedNumbers(amount), sources);
    }

    public static boolean isGrounded(String text, Collection<String> sources) {
        return missingNumbers(text, sources).isEmpty();
    }

    private static Set<BigDecimal> missing(Set<BigDecimal> stated, Collection<String> sources) {
        for (var source : sources) {
            if (stated.isEmpty()) {
                break;
            }
            stated.removeAll(groundedNumbers(source));
        }
        return stated;
    }

    private static Set<BigDecimal> numbers(String text, boolean measuresOnly) {
        var numbers = new TreeSet<BigDecimal>();
        if (text == null || text.isEmpty()) {
            return numbers;
        }
        var plain = plainNumerals(text);
        digitNumbers(plain, numbers);
        nativeNumbers(plain, measuresOnly, numbers);
        sinoNumbers(plain, measuresOnly, numbers);
        var matcher = LEADING_HALF.matcher(plain);
        while (matcher.find()) {
            if (counts(matcher.group("unit"), measuresOnly) && !NOT_QUANTITIES.contains(matcher.group())) {
                numbers.add(HALF);
            }
        }
        matcher = DIGITS_UNIT_HALF.matcher(plain);
        while (matcher.find()) {
            if (counts(matcher.group("unit"), measuresOnly)) {
                numbers.add(new BigDecimal(matcher.group("amount")).add(HALF).stripTrailingZeros());
            }
        }
        return numbers;
    }

    private static void digitNumbers(String text, Set<BigDecimal> numbers) {
        var matcher = DIGIT_NUMBER.matcher(text);
        while (matcher.find()) {
            if (matcher.group("whole") != null) {
                var whole = new BigDecimal(matcher.group("whole"));
                numbers.add(whole.stripTrailingZeros());
                addFraction(numbers, whole, matcher.group("mixedNumerator"), matcher.group("mixedDenominator"));
            } else if (matcher.group("numerator") != null) {
                addFraction(numbers, BigDecimal.ZERO, matcher.group("numerator"), matcher.group("denominator"));
            } else {
                numbers.add(new BigDecimal(matcher.group("value").replace(",", "")).stripTrailingZeros());
            }
        }
    }

    private static void nativeNumbers(String text, boolean measuresOnly, Set<BigDecimal> numbers) {
        var matcher = NATIVE.matcher(text);
        while (matcher.find()) {
            // A bare 반 ("하나 반") has no unit, so only a source counts it.
            if (NOT_QUANTITIES.contains(matcher.group()) || !counts(matcher.group("unit"), measuresOnly)) {
                continue;
            }
            var half = matcher.group("half") != null || matcher.group("bareHalf") != null;
            var range = matcher.group("range");
            if (range != null) {
                for (var value : NATIVE_RANGES.get(range.replaceAll("\\s", ""))) {
                    add(numbers, BigDecimal.valueOf(value), half);
                }
                continue;
            }
            var value = nativeValue(matcher.group("tens")) + nativeValue(matcher.group("tensOnes"))
                    + nativeValue(matcher.group("ones"));
            add(numbers, BigDecimal.valueOf(value), half);
        }
    }

    private static void sinoNumbers(String text, boolean measuresOnly, Set<BigDecimal> numbers) {
        var matcher = SINO.matcher(text);
        while (matcher.find()) {
            if (!counts(matcher.group("unit"), measuresOnly) || NOT_QUANTITIES.contains(matcher.group())) {
                continue;
            }
            var half = matcher.group("half") != null;
            if (matcher.group("rangeLow") != null) {
                var low = SINO_DIGITS.get(matcher.group("rangeLow").charAt(0));
                var high = SINO_DIGITS.get(matcher.group("rangeHigh").charAt(0));
                if (high == low + 1) {
                    var scale = matcher.group("rangeScale").isEmpty() ? 1 : sinoValue(matcher.group("rangeScale"));
                    add(numbers, BigDecimal.valueOf(low * scale), half);
                    add(numbers, BigDecimal.valueOf(high * scale), half);
                }
                continue;
            }
            var numeral = matcher.group("sino");
            if (!numeral.isEmpty() && !(numeral.length() == 1 && isLoneSinoWord(text, matcher))) {
                add(numbers, BigDecimal.valueOf(sinoValue(numeral)), half);
            }
        }
    }

    // A lone 일, 이, 삼... begins many words, so with it a unit counts only where no other word can be meant.
    private static boolean isLoneSinoWord(String text, Matcher matcher) {
        var unit = matcher.group("unit");
        var spaced = !matcher.group("space").isEmpty();
        if (!spaced && GLUED_COUNTERS.contains(unit)) {
            return true;
        }
        // The demonstrative: 이 컵으로 is "with this cup"; two cups are 두 컵.
        if (spaced && "이".equals(matcher.group("sino")) && !SINO_READ_UNITS.contains(unit)) {
            return true;
        }
        return !LONE_SINO_UNIT_ENDS.matcher(text).region(matcher.end(), text.length()).lookingAt();
    }

    private static boolean counts(String unit, boolean measuresOnly) {
        return !measuresOnly || (unit != null && MEASURES.contains(unit));
    }

    /**
     * ½ and ３ are numbers too: a fraction character becomes " 1/2" (so 1½ reads as 1 1/2, not 11/2), and
     * fullwidth or other-script digits, commas and slashes become ASCII.
     */
    private static String plainNumerals(String text) {
        var plain = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (Character.getType(codePoint) == Character.OTHER_NUMBER) {
                var compatible = Normalizer.normalize(Character.toString(codePoint), Normalizer.Form.NFKC);
                plain.append(compatible.indexOf(FRACTION_SLASH) > 0 ? " " : "").append(compatible);
            } else if (codePoint > 0x7F && Character.isDigit(codePoint)) {
                plain.append(Character.digit(codePoint, 10));
            } else {
                plain.appendCodePoint(codePoint);
            }
        });
        return Normalizer.normalize(plain, Normalizer.Form.NFKC).replace(FRACTION_SLASH, '/');
    }

    private static void addFraction(Set<BigDecimal> numbers, BigDecimal whole, String numerator, String denominator) {
        var divisor = new BigDecimal(denominator);
        if (divisor.signum() == 0) {
            numbers.add(new BigDecimal(numerator).stripTrailingZeros());
            numbers.add(BigDecimal.ZERO);
            return;
        }
        numbers.add(whole.add(new BigDecimal(numerator).divide(divisor, MathContext.DECIMAL64)).stripTrailingZeros());
    }

    private static void add(Set<BigDecimal> numbers, BigDecimal value, boolean andAHalf) {
        numbers.add(value.stripTrailingZeros());
        if (andAHalf) {
            numbers.add(value.add(HALF).stripTrailingZeros());
        }
    }

    private static int nativeValue(String word) {
        return word == null ? 0 : NATIVE_VALUES.get(word);
    }

    private static int sinoValue(String numeral) {
        var total = 0;
        var digit = 0;
        for (var index = 0; index < numeral.length(); index++) {
            var character = numeral.charAt(index);
            var multiplier = switch (character) {
                case '십' -> 10;
                case '백' -> 100;
                case '천' -> 1000;
                default -> 0;
            };
            if (multiplier == 0) {
                digit = SINO_DIGITS.get(character);
            } else {
                total += (digit == 0 ? 1 : digit) * multiplier;
                digit = 0;
            }
        }
        return total + digit;
    }

    private static String longestFirst(List<String> words) {
        return String.join("|", words.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList());
    }
}
