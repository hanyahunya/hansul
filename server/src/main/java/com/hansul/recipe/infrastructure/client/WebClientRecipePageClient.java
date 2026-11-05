package com.hansul.recipe.infrastructure.client;

import com.hansul.recipe.application.RecipeImportException;
import com.hansul.recipe.infrastructure.source.CanonicalRecipeUrl;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Exceptions;

@Component
public class WebClientRecipePageClient implements RecipePageClient {

    private final WebClient webClient;
    private final Duration responseTimeout;

    public WebClientRecipePageClient(
            @Qualifier("recipePageWebClient") WebClient webClient,
            RecipeImportProperties properties
    ) {
        this.webClient = webClient;
        this.responseTimeout = properties.responseTimeout();
    }

    @Override
    public String fetch(CanonicalRecipeUrl source) {
        try {
            var html = webClient.get()
                    .uri(source.uri())
                    .exchangeToMono(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            return response.releaseBody().then(
                                    reactor.core.publisher.Mono.error(new RecipeImportException(
                                            HttpStatus.BAD_GATEWAY,
                                            "RECIPE_SOURCE_HTTP_ERROR",
                                            "Recipe source returned HTTP " + response.statusCode().value()
                                    ))
                            );
                        }

                        var contentType = response.headers().contentType().orElse(null);
                        if (contentType == null || !MediaType.TEXT_HTML.isCompatibleWith(contentType)) {
                            return response.releaseBody().then(
                                    reactor.core.publisher.Mono.error(new RecipeImportException(
                                            HttpStatus.BAD_GATEWAY,
                                            "RECIPE_SOURCE_CONTENT_TYPE_ERROR",
                                            "Recipe source did not return HTML"
                                    ))
                            );
                        }
                        return response.bodyToMono(String.class);
                    })
                    .block(responseTimeout.plusSeconds(1));

            if (html == null || html.isBlank()) {
                throw new RecipeImportException(
                        HttpStatus.BAD_GATEWAY,
                        "RECIPE_SOURCE_EMPTY",
                        "Recipe source returned an empty page"
                );
            }
            return html;
        } catch (RecipeImportException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            var unwrapped = Exceptions.unwrap(exception);
            throw new RecipeImportException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "RECIPE_SOURCE_UNAVAILABLE",
                    "Recipe source could not be fetched",
                    unwrapped
            );
        }
    }
}
