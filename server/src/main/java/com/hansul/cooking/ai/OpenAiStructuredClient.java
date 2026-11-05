package com.hansul.cooking.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared structured-output transport for recipe annotation and future agent tasks. */
@Component
public class OpenAiStructuredClient implements StructuredOutputClient {
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;
    private final URI responsesUri;
    private final String apiKey;
    private final String model;

    @Autowired
    public OpenAiStructuredClient(
            JsonMapper jsonMapper,
            @Value("${hansul.ai.api-base-url:https://api.openai.com}") String apiBaseUrl,
            @Value("${hansul.ai.api-key:}") String apiKey,
            @Value("${hansul.ai.model:}") String model) {
        this(jsonMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                URI.create(apiBaseUrl).resolve("/v1/responses"),
                apiKey,
                model);
    }

    OpenAiStructuredClient(
            JsonMapper jsonMapper, HttpClient httpClient, URI responsesUri, String apiKey, String model) {
        this.jsonMapper = jsonMapper;
        this.httpClient = httpClient;
        this.responsesUri = responsesUri;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
    }

    @Override
    public JsonNode generate(
            String instructions,
            Object context,
            String schemaName,
            Map<String, Object> schema) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI provider is not configured");
        }
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("store", false);
        requestBody.put("instructions", instructions);
        requestBody.put("input", List.of(Map.of(
                "role", "user",
                "content", jsonMapper.writeValueAsString(context))));
        requestBody.put("text", Map.of("format", Map.of(
                "type", "json_schema",
                "name", schemaName,
                "strict", true,
                "schema", schema)));
        HttpRequest request = HttpRequest.newBuilder(responsesUri)
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(requestBody)))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider request failed");
            }
            JsonNode envelope = jsonMapper.readTree(response.body());
            if (!"completed".equals(envelope.path("status").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider response was incomplete");
            }
            for (JsonNode item : envelope.path("output")) {
                if (!"message".equals(item.path("type").asText())) {
                    continue;
                }
                for (JsonNode content : item.path("content")) {
                    if ("output_text".equals(content.path("type").asText())) {
                        return jsonMapper.readTree(content.path("text").asText());
                    }
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider returned no structured output");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider was interrupted", exception);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider could not be reached", exception);
        } catch (RuntimeException exception) {
            if (exception instanceof ResponseStatusException) {
                throw exception;
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider response was invalid", exception);
        }
    }
}
