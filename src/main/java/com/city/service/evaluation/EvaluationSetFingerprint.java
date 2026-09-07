package com.city.service.evaluation;

import com.city.exception.CityException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
            List<JsonNode> normalized = new ArrayList<>(cases == null ? List.of() : cases);
            normalized.sort(Comparator.comparing(node -> node.path("id").asText("")));
            byte[] bytes = objectMapper.writeValueAsString(normalized).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new CityException("评测集指纹生成失败", error);
        }
    }
}
