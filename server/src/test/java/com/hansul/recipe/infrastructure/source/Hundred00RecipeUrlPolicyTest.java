package com.hansul.recipe.infrastructure.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Hundred00RecipeUrlPolicyTest {

    private final Hundred00RecipeUrlPolicy policy = new Hundred00RecipeUrlPolicy();

    @Test
    void acceptsDesktopAndMobileHostsAndDropsTrackingParts() {
        var desktop = policy.canonicalize(
                "https://www.10000recipe.com/recipe/6850292?srsltid=tracking#section"
        );
        var mobile = policy.canonicalize("https://m.10000recipe.com/recipe/6944989");

        assertThat(desktop.provider()).isEqualTo("10000recipe");
        assertThat(desktop.recipeId()).isEqualTo("6850292");
        assertThat(desktop.uri().toString())
                .isEqualTo("https://www.10000recipe.com/recipe/6850292");
        assertThat(mobile.uri().toString())
                .isEqualTo("https://www.10000recipe.com/recipe/6944989");
    }

    @Test
    void rejectsUrlsOutsideTheExactAllowlist() {
        assertThatThrownBy(() -> policy.canonicalize(
                "https://www.10000recipe.com.evil.test/recipe/6850292"
        )).isInstanceOf(InvalidRecipeUrlException.class);
        assertThatThrownBy(() -> policy.canonicalize(
                "http://www.10000recipe.com/recipe/6850292"
        )).isInstanceOf(InvalidRecipeUrlException.class);
        assertThatThrownBy(() -> policy.canonicalize(
                "https://www.10000recipe.com:443/recipe/6850292"
        )).isInstanceOf(InvalidRecipeUrlException.class);
        assertThatThrownBy(() -> policy.canonicalize(
                "https://www.10000recipe.com/recipe/6850292/extra"
        )).isInstanceOf(InvalidRecipeUrlException.class);
        assertThatThrownBy(() -> policy.canonicalize(
                "https://www.10000recipe.com/recipe/%36%38%35%30%32%39%32"
        )).isInstanceOf(InvalidRecipeUrlException.class);
        assertThatThrownBy(() -> policy.canonicalize(
                "https://www.10000recipe.com/recipe/6850292/"
        )).isInstanceOf(InvalidRecipeUrlException.class);
    }
}
