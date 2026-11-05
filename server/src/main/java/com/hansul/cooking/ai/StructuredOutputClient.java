package com.hansul.cooking.ai;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/** The plan builder may use another cloud model or a local model through this port. */
public interface StructuredOutputClient {
    boolean isConfigured();

    JsonNode generate(String instructions, Object context, String schemaName, Map<String, Object> schema);
}
