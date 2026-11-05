package com.hansul.app.config;

import com.hansul.recipe.infrastructure.client.RecipeImportProperties;
import io.netty.channel.ChannelOption;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration(proxyBeanMethods = false)
public class RecipeHttpClientConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    @Qualifier("recipePageWebClient")
    WebClient recipePageWebClient(WebClient.Builder builder, RecipeImportProperties properties) {
        var httpClient = HttpClient.create()
                .followRedirect(false)
                .option(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(properties.connectTimeout().toMillis())
                )
                .responseTimeout(properties.responseTimeout());

        return builder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(properties.maxResponseBytes()))
                .defaultHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml")
                .defaultHeader(
                        HttpHeaders.USER_AGENT,
                        "HansulRecipeImporter/0.1 (+https://hansul.app)"
                )
                .build();
    }
}
