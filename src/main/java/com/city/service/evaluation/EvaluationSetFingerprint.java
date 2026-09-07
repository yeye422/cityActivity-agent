package com.city.service.evaluation;

import com.city.exception.CityException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 对本次最终参与评测的 Case 集合生成稳定内容指纹。 */
final class EvaluationSetFingerprint {
    private EvaluationSetFingerprint() { }

    static String sha256(ObjectMapper objectMapper, List<JsonNode> cases) {
        try {
            List<JsonNode> sortedCases = new ArrayList<>(cases == null ? List.of() : cases);
            sortedCases.sort(Comparator.comparing(node -> node.path("id").asText("")));

            ArrayNode canonicalCases = objectMapper.createArrayNode();
            for (JsonNode testCase : sortedCases) {
                canonicalCases.add(canonicalize(objectMapper, testCase));
            }

            byte[] bytes = objectMapper.writeValueAsString(canonicalCases).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                int unsigned = value & 0xff;
                hex.append(Character.forDigit(unsigned >>> 4, 16));
                hex.append(Character.forDigit(unsigned & 0x0f, 16));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new CityException("评测集指纹生成失败", error);
        }
    }

    private static JsonNode canonicalize(ObjectMapper objectMapper, JsonNode node) {
        if (node == null || node.isNull()) {
            return objectMapper.getNodeFactory().nullNode();
        }
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            fieldNames.sort(String::compareTo);
            for (String fieldName : fieldNames) {
                result.set(fieldName, canonicalize(objectMapper, node.get(fieldName)));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                result.add(canonicalize(objectMapper, child));
            }
            return result;
        }
        return node.deepCopy();
    }
}
