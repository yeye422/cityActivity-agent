package com.city.service.evaluation;

import com.city.exception.CityException;
import com.city.mapper.EvaluationCaseMapper;
import com.city.model.EvaluationCaseRow;
import com.city.model.PromoteEvaluationCaseRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Objects;

/** 将人工确认的 BadCase 写入 default/react 对应的数据库评测集。 */
@Service
public class EvaluationCasePromotionService {
    private static final String DEFAULT_RESOURCE = "evaluation/city-dialogue-eval-set.json";
    private static final String REACT_RESOURCE = "evaluation/city-react-eval-set.json";

    private final EvaluationCaseMapper mapper;
    private final ObjectMapper objectMapper;

    public EvaluationCasePromotionService(EvaluationCaseMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void promote(Long userId, PromoteEvaluationCaseRequest request) {
        if (request == null || request.caseDefinition() == null || !request.caseDefinition().isObject()) {
            throw new CityException("评测用例内容不能为空");
        }
        JsonNode definition = request.caseDefinition();
        String caseId = definition.path("id").asText("").trim();
        if (caseId.isBlank()) throw new CityException("评测用例必须包含 id");
        if (!definition.hasNonNull("message") && !definition.path("messages").isArray()) {
            throw new CityException("评测用例必须包含 message 或 messages");
        }

        EvaluationCaseRow row = new EvaluationCaseRow();
        row.setUserId(userId);
        row.setCaseId(caseId);
        row.setEvalSetVersion(resolveEvalSetVersion(request.suite()));
        row.setCaseJson(toJson(definition));
        row.setSourceTraceId(request.traceId());
        row.setCreatedBy(userId);
        mapper.insert(row);
    }

    String resolveEvalSetVersion(String suite) {
        String resource = resolveResource(suite);
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            String version = root.path("version").asText("").trim();
            if (version.isBlank()) throw new CityException("评测集缺少 version: " + resource);
            return version;
        } catch (CityException error) {
            throw error;
        } catch (Exception error) {
            throw new CityException("评测集版本读取失败: " + resource, error);
        }
    }

    static String resolveResource(String suite) {
        if (suite == null || suite.isBlank() || "default".equalsIgnoreCase(suite.trim())) {
            return DEFAULT_RESOURCE;
        }
        if ("react".equalsIgnoreCase(suite.trim())) {
            return REACT_RESOURCE;
        }
        throw new CityException("未知评测 suite，仅支持 default / react");
    }

    private String toJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new CityException("评测用例序列化失败", error);
        }
    }
}
