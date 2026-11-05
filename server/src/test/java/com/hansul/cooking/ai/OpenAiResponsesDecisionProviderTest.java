package com.hansul.cooking.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hansul.cooking.CookingPlan;
import com.hansul.cooking.CookingState;
import com.hansul.cooking.TurnDecision;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class OpenAiResponsesDecisionProviderTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void sendsRecipeContextWithStrictSchemaAndDoesNotStoreProviderResponse() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/responses", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String decision = mapper.writeValueAsString(Map.of(
                    "addressing", "DIRECT",
                    "speechAct", "QUESTION_ONLY",
                    "replyText", "원문에 불 세기는 적혀 있지 않아요.",
                    "citedSourceSteps", List.of(1),
                    "photoSourceSteps", List.of(),
                    "reports", List.of(),
                    "timerOfferIds", List.of(),
                    "timerAcceptanceIds", List.of()));
            String response = mapper.writeValueAsString(Map.of(
                    "status", "completed",
                    "output", List.of(Map.of(
                            "type", "message",
                            "content", List.of(Map.of("type", "output_text", "text", decision))))));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            OpenAiResponsesDecisionProvider provider = new OpenAiResponsesDecisionProvider(
                    mapper,
                    HttpClient.newHttpClient(),
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"),
                    "test-only-key",
                    "test-model");
            TurnDecision decision = provider.decide(context());

            assertEquals(TurnDecision.Addressing.DIRECT, decision.addressing());
            assertEquals(TurnDecision.SpeechAct.QUESTION_ONLY, decision.speechAct());
            var request = mapper.readTree(receivedBody.get());
            assertFalse(request.path("store").asBoolean());
            assertEquals("json_schema", request.path("text").path("format").path("type").asText());
            assertEquals("test-model", request.path("model").asText());
            assertEquals("원문에 불 세기는?", mapper.readTree(request.path("input").get(0).path("content").asText())
                    .path("latestUserTranscript").asText());
            var sentContext = mapper.readTree(request.path("input").get(0).path("content").asText());
            assertEquals(2, sentContext.path("targetServings").asInt());
            assertEquals(1, sentContext.path("baseServings").asInt());
            assertEquals("550ml", sentContext.path("ingredients").get(0).path("originalQuantityText").asText());
            assertEquals("PROPORTIONAL_ESTIMATE", sentContext.path("ingredients").get(0).path("scalingStatus").asText());
            assertEquals("WEB_PAGE", sentContext.path("recipeSourceKind").asText());
            assertTrue(request.path("instructions").asText().contains("recipeSourceKind가 VIDEO_AI이면"));
            assertTrue(request.path("text").path("format").path("schema").path("required")
                    .toString().contains("ingredientReports"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingConfigurationFailsBeforeSendingAnyRequest() {
        OpenAiResponsesDecisionProvider provider = new OpenAiResponsesDecisionProvider(
                mapper,
                HttpClient.newHttpClient(),
                java.net.URI.create("http://127.0.0.1:1/v1/responses"),
                "",
                "");
        assertThrows(ResponseStatusException.class, () -> provider.decide(context()));
    }

    private static AgentTurnContext context() {
        CookingPlan plan = new CookingPlan(
                Map.of("step-1", new CookingPlan.Action("step-1", Set.of(1), Set.of(), Set.of())),
                Map.of(),
                Map.of(),
                Set.of(1));
        return new AgentTurnContext(
                "시험 요리",
                List.of(new AgentTurnContext.SourceStep(1, "재료를 익힌다.", false)),
                plan,
                CookingState.empty(),
                "원문에 불 세기는?",
                List.of(new AgentTurnContext.SourceIngredient("물", "550ml", "1100ml", "PROPORTIONAL_ESTIMATE")),
                BigDecimal.ONE, BigDecimal.valueOf(2));
    }
}
