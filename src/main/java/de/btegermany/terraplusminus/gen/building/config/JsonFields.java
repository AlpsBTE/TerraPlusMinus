package de.btegermany.terraplusminus.gen.building.config;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Small helpers for reading typed fields from building dataset config entries.
 * Required accessors throw {@link IllegalArgumentException}, optional ones return the fallback
 * when the field is missing or has the wrong type.
 */
final class JsonFields {
    private JsonFields() {
    }

    static JsonNode requiredObject(JsonNode node, String field) {
        JsonNode value = get(node, field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Missing object field: " + field);
        }
        return value;
    }

    static String requiredText(JsonNode node, String field) {
        JsonNode value = get(node, field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Missing text field: " + field);
        }
        return value.asText();
    }

    static double requiredDouble(JsonNode node, String field) {
        JsonNode value = get(node, field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("Missing numeric field: " + field);
        }
        return value.asDouble();
    }

    static String optionalText(JsonNode node, String field, String fallback) {
        JsonNode value = get(node, field);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    static boolean optionalBoolean(JsonNode node, String field, boolean fallback) {
        JsonNode value = get(node, field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    static double optionalDouble(JsonNode node, String field, double fallback) {
        JsonNode value = get(node, field);
        return value != null && value.isNumber() ? value.asDouble() : fallback;
    }

    private static JsonNode get(JsonNode node, String field) {
        return node == null ? null : node.get(field);
    }
}
