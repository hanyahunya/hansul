package com.hansul.recipe.video;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Lenient readers for model and API output (a missing or mistyped field reads as empty), and schema builders. */
final class VideoJson {

    private VideoJson() {
    }

    /** A strict object schema: every property required and nothing else allowed. */
    static Map<String, Object> objectSchema(Object... namesAndSchemas) {
        var properties = ordered(namesAndSchemas);
        return ordered(
                "type", "object",
                "properties", properties,
                "required", List.copyOf(properties.keySet()),
                "additionalProperties", false
        );
    }

    /** An unmodifiable map that keeps the given key order, so request JSON reads like the schema in the docs. */
    static Map<String, Object> ordered(Object... keysAndValues) {
        var map = new LinkedHashMap<String, Object>();
        for (var index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isString() ? value.stringValue() : "";
    }

    static Double number(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isNumber()) {
            return null;
        }
        var number = value.doubleValue();
        return Double.isFinite(number) ? number : null;
    }

    static long count(JsonNode node, String field) {
        var value = node.path(field);
        return value.isNumber() ? Math.max(0, value.longValue()) : 0;
    }

    static boolean isTrue(JsonNode node, String field) {
        var value = node.path(field);
        return value.isBoolean() && value.booleanValue();
    }
}
