package com.hansul.recipe.infrastructure.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hansul.recipe-import")
public record RecipeImportProperties(
        Duration connectTimeout,
        Duration responseTimeout,
        int maxResponseBytes
) {
    public RecipeImportProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        responseTimeout = responseTimeout == null ? Duration.ofSeconds(8) : responseTimeout;
        maxResponseBytes = maxResponseBytes <= 0 ? 4 * 1024 * 1024 : maxResponseBytes;
    }
}
