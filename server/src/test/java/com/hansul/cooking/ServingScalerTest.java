package com.hansul.cooking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ServingScalerTest {
    @Test
    void scalesIntegerAndFractionWithoutFloatingPointError() {
        assertEquals("175g", ServingScaler.scale("350g", 2, 1).displayText());
        assertEquals("90g", ServingScaler.scale("180g", 2, 1).displayText());
        assertEquals("1/4개", ServingScaler.scale("1/2개", 2, 1).displayText());
        assertEquals("1/8개", ServingScaler.scale("1/4개", 2, 1).displayText());
        assertEquals("약 1개", ServingScaler.scale("약 2개", 2, 1).displayText());
    }

    @Test
    void arithmeticBaselineDoesNotClaimToKnowNonlinearCookingQuantities() {
        ServingScaler.ScaledAmount water = ServingScaler.scale("550ml", 1, 2);
        assertEquals("1100ml", water.displayText());
        assertEquals("550ml", water.original());
        assertEquals(ServingScaler.Status.PROPORTIONAL_ESTIMATE, water.status());
        assertEquals(ServingScaler.Status.UNCHANGED, ServingScaler.scale("550ml", 1, 1).status());
    }

    @Test
    void scalesRangesAndRetainsPackagingNoteSeparately() {
        ServingScaler.ScaledAmount range = ServingScaler.scale("1~2스푼", 2, 1);
        assertEquals("1/2~1스푼", range.displayText());

        ServingScaler.ScaledAmount packaged = ServingScaler.scale("350g (한봉지)", 2, 1);
        assertEquals("175g", packaged.displayText());
        assertEquals("(한봉지)", packaged.originalNote());
    }

    @Test
    void acceptsDecimalServingCountsAndDisplaysReadableDecimal() {
        assertEquals("1.5개", ServingScaler.scale("2개", new BigDecimal("2"), new BigDecimal("1.5")).displayText());
    }

    @Test
    void preservesAmountsThatCannotBeScaledReliably() {
        ServingScaler.ScaledAmount qualitative = ServingScaler.scale("약간", 2, 1);
        assertEquals("약간", qualitative.displayText());
        assertEquals(ServingScaler.Status.UNPARSEABLE, qualitative.status());

        ServingScaler.ScaledAmount writtenNumber = ServingScaler.scale("한봉지", 2, 1);
        assertEquals("한봉지", writtenNumber.displayText());
        assertEquals(ServingScaler.Status.UNPARSEABLE, writtenNumber.status());
    }

    @Test
    void removesInvisibleFormattingCharactersWithoutChangingOriginal() {
        ServingScaler.ScaledAmount amount = ServingScaler.scale("1\u200B/2개", 2, 1);
        assertEquals("1/4개", amount.displayText());
        assertEquals("1\u200B/2개", amount.original());
    }

    @Test
    void rejectsInvalidServings() {
        assertThrows(IllegalArgumentException.class, () -> ServingScaler.scale("2개", 0, 1));
    }
}
