package com.city.service.evaluation;

import com.city.exception.CityException;
import com.city.enums.Intent;
import com.city.enums.SourceMode;
import com.city.model.ChatRequest;
import com.city.model.ChatResponse;
import com.city.model.EvaluationReport;
import com.city.model.RegressionEvaluationRequest;
import com.city.model.RegressionEvaluationReport;
import com.city.model.EvaluationRunRow;
import com.city.mapper.EvaluationRunMapper;
import com.city.mapper.EvaluationCaseMapper;
import com.city.model.EvaluationCaseRow;
import com.city.model.PromoteEvaluationCaseRequest;
import com.city.model.SlotBundle;
import com.city.model.TraceLabelRequest;
import com.city.service.orchestrator.CityOrchestratorService;
import com.city.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** 执行固定对话集并将最终轮 Trace 送入离线评估，形成最小可运行回归闭环。 */
@Service
public class RegressionEvaluationService {
    private final ObjectMapper objectMapper;
    private final CityOrchestratorService orchestratorService;
    private final AgentTraceService traceService;
    private final EvaluationService evaluationService;
    private final EvaluationRunMapper evaluationRunMapper;
    private final EvaluationCaseMapper evaluationCaseMapper;
    private final String promptVersion;
    private final String ruleVersion;
    private final String modelVersion;

    public RegressionEvaluationService(ObjectMapper objectMapper,
                                       CityOrchestratorService orchestratorService,
                                       AgentTraceService traceService,
                                       EvaluationService evaluationService,
                                       EvaluationRunMapper evaluationRunMapper,
                                       EvaluationCaseMapper evaluationCaseMapper,
                                       @Value("${diet.prompt.version:v1}") String promptVersion,
                                       @Value("${diet.rule.version:v1}") String ruleVersion,
                                       @Value("${diet.llm.main-model:qwen-max}") String modelVersion) {
        this.objectMapper = objectMapper;
        this.orchestratorService = orchestratorService;
        this.traceService = traceService;
        this.evaluationService = evaluationService;
        this.evaluationRunMapper = evaluationRunMapper;
        this.evaluationCaseMapper = evaluationCaseMapper;
        this.promptVersion = promptVersion;
        this.ruleVersion = ruleVersion;
        this.modelVersion = modelVersion;
    }

    public synchronized RegressionEvaluationReport run(Long ownerUserId, RegressionEvaluationRequest request) {
        boolean judge = request != null && Boolean.TRUE.equals(request.getIncludeLlmJudge());
        int limit = request == null || request.getLimit() == null ? 50 : Math.max(1, Math.min(100, request.getLimit()));
        List<String> traceIds = new ArrayList<>();
        String evalSetVersion = "v1";
        try (InputStream input = new ClassPathResource("evaluation/city-dialogue-eval-set.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            evalSetVersion = root.path("version").asText("v1");
            JsonNode cases = root.path("cases");
            if (!cases.isArray()) throw new CityException("评测集格式错误：cases 必须是数组");
            List<JsonNode> allCases = new ArrayList<>();
            cases.forEach(allCases::add);
            for (EvaluationCaseRow row : evaluationCaseMapper.findBySetVersion(ownerUserId, evalSetVersion)) {
                allCases.add(objectMapper.readTree(row.getCaseJson()));
            }
            int count = 0;
            for (JsonNode testCase : allCases) {
                if (count++ >= limit) break;
                String sessionId = null;
                JsonNode messages = testCase.path("messages");
                if (!messages.isArray() || messages.isEmpty()) {
                    messages = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                            .put("message", testCase.path("message").asText(""))
                            .put("sourceMode", "PUBLIC"));
                }
                ChatResponse finalResponse = null;
                for (JsonNode message : messages) {
                    String text = message.path("message").asText("").trim();
                    if (text.isBlank()) throw new CityException("评测用例缺少 message：" + testCase.path("id").asText());
                    SourceMode sourceMode = parseSourceMode(message.path("sourceMode").asText("PUBLIC"));
                    finalResponse = orchestratorService.dietChat(ownerUserId,
                            new ChatRequest(sessionId, text, sourceMode, null));
                    sessionId = finalResponse.sessionId();
                }
                if (finalResponse == null || finalResponse.traceId() == null) {
                    throw new CityException("评测用例未生成 Trace：" + testCase.path("id").asText());
                }
                traceService.updateLabel(ownerUserId, finalResponse.traceId(), labelOf(testCase));
                traceIds.add(finalResponse.traceId());
            }
        } catch (CityException error) {
            throw error;
        } catch (Exception error) {
            throw new CityException("固定评测集执行失败", error);
        }
        EvaluationReport report = evaluationService.evaluateTraceIds(ownerUserId, traceIds, judge);
        EvaluationRunRow baseline = evaluationRunMapper.findLatest(ownerUserId, evalSetVersion);
        RegressionEvaluationReport result = compare(report, evalSetVersion, baseline);
        EvaluationRunRow current = new EvaluationRunRow();
        current.setRunId("eval_" + java.util.UUID.randomUUID().toString().replace("-", ""));
        current.setUserId(ownerUserId);
        current.setEvalSetVersion(evalSetVersion);
        current.setPromptVersion(promptVersion);
        current.setRuleVersion(ruleVersion);
        current.setModelVersion(modelVersion);
        current.setTotalTraces(report.totalTraces());
        current.setAvgScore(report.avgScore());
        current.setMetricSnapshot(toJson(report.metricAverages()));
        current.setBaselineRunId(baseline == null ? null : baseline.getRunId());
        current.setPassed(result.passed());
        evaluationRunMapper.insert(current);
        return result;
    }

    public void promote(Long userId, PromoteEvaluationCaseRequest request) {
        if (request == null || request.caseDefinition() == null || !request.caseDefinition().isObject()) throw new CityException("评测用例内容不能为空");
        JsonNode definition = request.caseDefinition();
        String caseId = definition.path("id").asText("").trim();
        if (caseId.isBlank()) throw new CityException("评测用例必须包含 id");
        EvaluationCaseRow row = new EvaluationCaseRow();
        row.setUserId(userId); row.setCaseId(caseId); row.setEvalSetVersion("v1");
        row.setCaseJson(toJson(definition)); row.setSourceTraceId(request.traceId()); row.setCreatedBy(userId);
        evaluationCaseMapper.insert(row);
    }

    private RegressionEvaluationReport compare(EvaluationReport report, String version, EvaluationRunRow baseline) {
        if (baseline == null) {
            return new RegressionEvaluationReport(version, report, null, null, null, Map.of(), true);
        }
        Map<String, Double> baselineMetrics;
        try {
            baselineMetrics = objectMapper.readValue(baseline.getMetricSnapshot(),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Double.class));
        } catch (Exception ignored) {
            baselineMetrics = Map.of();
        }
        RegressionGate.Result gate = RegressionGate.evaluate(
                baseline.getAvgScore(), report.avgScore(), baselineMetrics, report.metricAverages());
        return new RegressionEvaluationReport(version, report, baseline.getRunId(), baseline.getAvgScore(),
                gate.scoreDelta(), gate.metricDeltas(), gate.passed());
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception error) { throw new CityException("评估指标序列化失败", error); }
    }

    private TraceLabelRequest labelOf(JsonNode testCase) throws Exception {
        Intent intent = parseEnum(Intent.class, testCase.path("expectedIntent").asText(null));
        String clarify = testCase.path("expectedClarifyAction").asText(null);
        if (clarify != null) clarify = clarify.trim();
        if (clarify != null && clarify.isBlank()) clarify = null;
        SlotBundle slots = testCase.path("expectedSlots").isObject()
                ? objectMapper.treeToValue(testCase.path("expectedSlots"), SlotBundle.class)
                : null;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("caseId", testCase.path("id").asText("unknown"));
        if (testCase.has("expectedOperations")) {
            meta.put("expectedOperations", objectMapper.convertValue(testCase.path("expectedOperations"), List.class));
        }
        if (testCase.has("expectedTime")) meta.put("expectedTime", testCase.path("expectedTime").asText());
        if (testCase.has("expectedMissingSlots")) {
            meta.put("expectedMissingSlots", objectMapper.convertValue(testCase.path("expectedMissingSlots"), List.class));
        }
        return new TraceLabelRequest(intent, slots, clarify, objectMapper.writeValueAsString(meta));
    }

    private SourceMode parseSourceMode(String value) {
        try { return SourceMode.valueOf(value.toUpperCase()); }
        catch (Exception ignored) { return SourceMode.PUBLIC; }
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try { return Enum.valueOf(type, value); }
        catch (Exception ignored) { return null; }
    }
}
