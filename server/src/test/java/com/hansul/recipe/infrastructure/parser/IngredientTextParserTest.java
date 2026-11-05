package com.hansul.recipe.infrastructure.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.hansul.recipe.domain.SourceEvidence;
import com.hansul.recipe.domain.SourceEvidence.SourceType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class IngredientTextParserTest {

    private final IngredientTextParser parser = new IngredientTextParser();
    private final SourceEvidence evidence = new SourceEvidence(SourceType.JSON_LD, "fixture");

    @Test
    void separatesNameAndOriginalQuantityAndNormalizesNumericAmount() {
        var fraction = parser.parse(1, "대파 1/2개", evidence);
        var grams = parser.parse(2, "순두부찌개용 350g (한봉지)", evidence);

        assertThat(fraction.name()).isEqualTo("대파");
        assertThat(fraction.quantityText()).isEqualTo("1/2개");
        assertThat(fraction.normalizedAmount()).isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(fraction.normalizedUnit()).isEqualTo("개");

        assertThat(grams.name()).isEqualTo("순두부찌개용");
        assertThat(grams.quantityText()).isEqualTo("350g (한봉지)");
        assertThat(grams.normalizedAmount()).isEqualByComparingTo(new BigDecimal("350"));
        assertThat(grams.normalizedUnit()).isEqualTo("g");
    }

    @Test
    void removesInvisibleFormattingCharactersButPreservesRawText() {
        var parsed = parser.parse(1, "소금\u200B 약간", evidence);

        assertThat(parsed.rawText()).isEqualTo("소금\u200B 약간");
        assertThat(parsed.name()).isEqualTo("소금");
        assertThat(parsed.quantityText()).isEqualTo("약간");
        assertThat(parsed.normalizedAmount()).isNull();
    }

    @Test
    void keepsUnrecognizedQuantityTextSafeAsAnUnsplitOriginal() {
        var parsed = parser.parse(1, "후추 톡톡", evidence);

        assertThat(parsed.name()).isEqualTo("후추 톡톡");
        assertThat(parsed.quantityText()).isNull();
        assertThat(parsed.rawText()).isEqualTo("후추 톡톡");
    }

    @Test
    void malformedNumericQuantityDoesNotFailTheWholeRecipe() {
        var parsed = parser.parse(1, "물 1/0컵", evidence);

        assertThat(parsed.name()).isEqualTo("물");
        assertThat(parsed.quantityText()).isEqualTo("1/0컵");
        assertThat(parsed.normalizedAmount()).isNull();
        assertThat(parsed.normalizationWarning()).isNotBlank();
        assertThat(parsed.rawText()).isEqualTo("물 1/0컵");
    }
}
