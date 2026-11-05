package com.hansul.recipe.video;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class VideoNumberGroundingTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "1T                     | 1",
            "200g                   | 200",
            "1.5L                   | 1.5",
            "0.5컵                  | 0.5",
            "1/2                    | 0.5",
            "대파 1/2대             | 0.5",
            "1 1/2컵                | 1, 1.5",
            "1,000원                | 1000",
            "물 1,200ml             | 1200",
            "2~3분                  | 2, 3",
            "180도에서 15분          | 15, 180",
            "1,2번 과정              | 1, 2",
            "3:30                   | 3, 30"
    })
    void readsDigitNumbersFractionsAndThousands(String text, String expected) {
        assertThat(plain(VideoNumberGrounding.numbersIn(text))).isEqualTo(list(expected));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "한 스푼                 | 1",
            "두 개                   | 2",
            "세 숟가락               | 3",
            "네 큰술                 | 4",
            "다섯 장                 | 5",
            "반 컵                   | 0.5",
            "반개                    | 0.5",
            "한 개 반                | 1, 1.5",
            "한 개 반을 넣어 주세요     | 1, 1.5",
            "하나 반                 | 1, 1.5",
            "두 스푼 반              | 2, 2.5",
            "열 개                   | 10",
            "열두 개                 | 12",
            "열 두 개                | 12",
            "스무 개                 | 20",
            "스물다섯 마리            | 25",
            "서른 장                 | 30",
            "한번 더 뒤집어           | 1",
            "두 번 저어 주세요         | 2",
            "한 시간 반              | 1, 1.5",
            "삼 분                   | 3",
            "삼분                    | 3",
            "오 분에서 칠 분          | 5, 7",
            "십 분                   | 10",
            "이십 초                 | 20",
            "삼십오 분               | 35",
            "이백 그램               | 200",
            "백오십 그램             | 150",
            "백팔십 도               | 180",
            "삼 인분                 | 3",
            "이인분                  | 2",
            "삼 개                   | 3",
            "1시간 반                | 1, 1.5",
            "1컵 반                  | 1, 1.5",
            "1T 그리고 두 스푼        | 1, 2"
    })
    void groundsSpokenNumeralsSaidWithAUnit(String text, String expected) {
        assertThat(plain(VideoNumberGrounding.groundedNumbers(text))).isEqualTo(list(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "이 정도면 충분해요",
            "오늘은 김치볶음밥이에요",
            "사과를 깎아 주세요",
            "일단 양파를 썰어요",
            "이번에는 불을 줄여요",
            "이대로 두세요",
            "육개장 스타일로",
            "두부를 네모나게 썰어요",
            "세모 모양으로",
            "반대로 뒤집어 주세요",
            "반죽을 치대요",
            "반찬으로 좋아요",
            "세게 저어 주세요",
            "하나도 안 매워요",
            "오이를 채 썰어요",
            "천천히 볶아요",
            "구워 주세요",
            "한국 사람 입맛에"
    })
    void ordinaryWordsAreNotNumbers(String text) {
        assertThat(VideoNumberGrounding.groundedNumbers(text)).isEmpty();
    }

    @Test
    void aSummaryIsGroundedWhenEveryDigitItWritesIsSaidOrShown() {
        var speech = List.of("신김치 한 컵을 넣고 중불에서 삼 분 정도 볶아 줍니다\n");

        assertThat(VideoNumberGrounding.isGrounded("신김치 1컵을 넣고 중불에서 3분 볶아주세요.", speech)).isTrue();
        assertThat(VideoNumberGrounding.missingNumbers("중불에서 5분 볶아주세요.", speech))
                .containsExactly(new BigDecimal("5"));
        assertThat(VideoNumberGrounding.isGrounded("대파 1/2대를 넣어주세요.", List.of("대파 반 대 넣어요"))).isTrue();
        assertThat(VideoNumberGrounding.isGrounded("양파 1 1/2개", List.of("양파 한 개 반 준비해요"))).isTrue();
        assertThat(VideoNumberGrounding.isGrounded("불을 끄고 뜸을 들여주세요.", List.of())).isTrue();
        assertThat(VideoNumberGrounding.isGrounded("2개", List.of("양파는 하나", "달걀 두 개"))).isTrue();
    }

    @Test
    void unitConversionsAndWordsWithoutAUnitAreNotGrounded() {
        assertThat(VideoNumberGrounding.missingNumbers("간장 15ml를 넣어주세요.", List.of("간장 1T 넣어 주세요")))
                .containsExactly(new BigDecimal("15"));
        assertThat(VideoNumberGrounding.missingNumbers("2인분 기준이에요.", List.of("이 정도면 두 사람이 먹어요")))
                .containsExactly(new BigDecimal("2"));
        assertThat(VideoNumberGrounding.missingNumbers("1컵을 넣어주세요.", List.of("일단 물을 넣어요")))
                .containsExactly(new BigDecimal("1"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "간장 세 스푼                | 3",
            "국간장 반 숟가락             | 0.5",
            "설탕 두 숟갈                 | 2",
            "올리고당 한 큰술             | 1",
            "소금 두 작은술               | 2",
            "식초 세 티스푼               | 3",
            "물 한 컵 반                  | 1, 1.5",
            "1컵 반                       | 1, 1.5",
            "육수 두 국자                 | 2",
            "소금 한 꼬집                 | 1",
            "부추 한 줌                   | 1",
            "돼지고기 이백 그램            | 200",
            "고기 한 킬로                 | 1",
            "물 오백 미리                 | 500",
            "우유 이백 밀리               | 200",
            "물 이 리터                   | 2",
            "중불에서 삼 분간              | 3",
            "삼십 초 정도                 | 30",
            "한 시간 반                   | 1, 1.5",
            "백팔십 도에서                | 180",
            "간장 두세 스푼               | 2, 3",
            "이삼 분                      | 2, 3"
    })
    void aSummaryCountsTheSpokenNumeralsOfAmountsAndTimes(String text, String expected) {
        assertThat(plain(VideoNumberGrounding.numbersIn(text))).isEqualTo(list(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "한 번 더 저어주세요",
            "두 번 뒤집어주세요",
            "양을 두 배로 늘려주세요",
            "대파 한 대를 썰어주세요",
            "양파 두 개를 준비해주세요",
            "참기름을 한 바퀴 둘러주세요",
            "밥 한 공기를 넣어주세요",
            "세 등분으로 잘라주세요",
            "하나 반을 넣어주세요",
            "이 컵으로 물을 떠 주세요",
            "이 스푼으로 섞어주세요",
            "두부를 네모나게 썰어주세요",
            "흰자와 노른자를 구분해 주세요",
            "이 도마에서 썰어주세요"
    })
    void aSummaryDoesNotCountCountersOrWordsThatLookLikeNumbers(String text) {
        assertThat(VideoNumberGrounding.numbersIn(text)).isEmpty();
    }

    @Test
    void aSpokenAmountOrTimeInASummaryMustBeTheOneTheVideoSays() {
        assertThat(VideoNumberGrounding.missingNumbers("간장 세 스푼", List.of("간장 두 스푼")))
                .containsExactly(new BigDecimal("3"));
        assertThat(VideoNumberGrounding.missingNumbers("중불에서 오 분 볶아주세요.", List.of("삼 분 정도 볶아요")))
                .containsExactly(new BigDecimal("5"));
        assertThat(VideoNumberGrounding.missingNumbers("물 한 컵 반을 부어주세요.", List.of("물 한 컵 부어요")))
                .containsExactly(new BigDecimal("1.5"));
        assertThat(VideoNumberGrounding.isGrounded("간장 두 스푼을 넣어주세요.", List.of("간장 2스푼"))).isTrue();
        assertThat(VideoNumberGrounding.isGrounded("한 번 더 저어주세요.", List.of("다시 저어 주세요"))).isTrue();
        assertThat(VideoNumberGrounding.isGrounded("두세 번 뒤집어주세요.", List.of("뒤집어 주세요"))).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "자 이제 고기를 두세 번 뒤집어 주면서 | 2, 3",
            "한두 스푼                          | 1, 2",
            "세네 개                            | 3, 4",
            "서너 개                            | 3, 4",
            "너댓 개                            | 4, 5",
            "네다섯 장                          | 4, 5",
            "대여섯 개                          | 5, 6",
            "예닐곱 마리                        | 6, 7",
            "이삼 분                            | 2, 3",
            "삼사 분                            | 3, 4",
            "사오 분                            | 4, 5",
            "오륙 분                            | 5, 6",
            "육칠 분                            | 6, 7",
            "칠팔 분                            | 7, 8",
            "팔구 분                            | 8, 9",
            "이삼십 분                          | 20, 30",
            "밥 두 공기                         | 2",
            "두 숟갈                            | 2",
            "배추 한 포기                       | 1",
            "네 등분                            | 4",
            "사등분해서 썰어요                   | 4",
            "이 센티                            | 2",
            "마늘 세 톨                         | 3",
            "소주 한 잔                         | 1",
            "단호박 반 통                       | 0.5",
            "두 조각                            | 2",
            "한 그릇                            | 1",
            "쪽파 세 줄기                       | 3"
    })
    void groundsSpokenRangesAndEverydayCounters(String text, String expected) {
        assertThat(plain(VideoNumberGrounding.groundedNumbers(text))).isEqualTo(list(expected));
    }

    @Test
    void theDemonstrativeIBeforeACounterIsNotTheNumberTwo() {
        assertThat(plain(VideoNumberGrounding.groundedNumbers("이 컵으로 물을 한 컵 부어 주세요"))).containsExactly("1");
        assertThat(VideoNumberGrounding.missingNumbers("물 2스푼을 넣어주세요", List.of("이 스푼으로 한 번 떠서 넣어요")))
                .containsExactly(new BigDecimal("2"));
        for (var phrase : List.of("이 국자로", "이 팩을", "이 봉지에", "이 대로", "이 시간에", "이 도마에서", "이 분량이면",
                "이 초콜릿을", "팔도 비빔면", "백도 복숭아", "삼분의 일")) {
            assertThat(VideoNumberGrounding.groundedNumbers(phrase)).as(phrase).isEmpty();
        }
        // Units read the Sino-Korean way keep the number.
        assertThat(plain(VideoNumberGrounding.groundedNumbers("이 분 정도 끓여요"))).containsExactly("2");
        assertThat(plain(VideoNumberGrounding.groundedNumbers("물 이 리터"))).containsExactly("2");
        assertThat(plain(VideoNumberGrounding.groundedNumbers("이인분"))).containsExactly("2");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "설탕 ½컵          | 0.5",
            "1½컵              | 1, 1.5",
            "버터 ¼개          | 0.25",
            "물 ３컵           | 3",
            "１，０００원       | 1000",
            "1⁄2컵             | 0.5"
    })
    void readsFractionCharactersAndFullwidthDigits(String text, String expected) {
        assertThat(plain(VideoNumberGrounding.numbersIn(text))).isEqualTo(list(expected));
    }

    @Test
    void fractionCharactersAndFullwidthDigitsAreCheckedLikeAnyOtherNumber() {
        var speech = List.of("설탕 한 스푼 넣어 주세요");

        assertThat(VideoNumberGrounding.missingNumbers("설탕 ½컵을 넣어주세요.", speech))
                .containsExactly(new BigDecimal("0.5"));
        assertThat(VideoNumberGrounding.missingNumbers("설탕 ３컵을 넣어주세요.", speech))
                .containsExactly(new BigDecimal("3"));
        assertThat(VideoNumberGrounding.isGrounded("설탕 0.5컵을 넣어주세요.", List.of("설탕 ½컵"))).isTrue();
    }

    private static List<String> plain(java.util.Set<BigDecimal> numbers) {
        return numbers.stream().map(BigDecimal::toPlainString).toList();
    }

    private static List<String> list(String expected) {
        return Arrays.stream(expected.split(",")).map(String::trim).toList();
    }
}
