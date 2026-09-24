package com.city.service.evaluation;

import com.city.enums.Intent;
import com.city.enums.SourceMode;
import com.city.exception.CityException;
import com.city.mapper.EvaluationCaseMapper;
import com.city.mapper.EvaluationRunMapper;
import com.city.model.ChatRequest;
import com.city.model.ChatResponse;
import com.city.model.EvaluationCaseRow;
import com.city.model.EvaluationReport;
import com.city.model.EvaluationRunRow;
import com.city.model.PromoteBaselineRequest;
import com.city.model.PromoteEvaluationCaseRequest;
import com.city.model.RegressionEvaluationReport;
import com.city.model.RegressionEvaluationRequest;
import com.city.model.RequestTraceRow;
import com.city.model.SlotBundle;
import com.city.model.TraceLabelRequest;
import com.city.model.TraceEvaluationResult;
import com.city.service.orchestrator.CityAgentSupervisor;
import com.city.service.trace.AgentTraceService;
import com.city.service.trace.BuildVersionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 执行固定对话集并将最终轮 Trace 送入离线评估，形成可追溯的回归闭环。 */
@Service
public class RegressionEvaluationService {
    private static final String DEFAULT_EVAL_SET_RESOURCE = "evaluation/city-dialogue-eval-set.json";
    private static final String REACT_EVAL_SET_RESOURCE = "evaluation/city-react-eval-set.json";

    private final ObjectMapper objectMapper;
    private final CityAgentSupervisor supervisor;
    private final AgentTraceService traceService;
    private final EvaluationService evaluationService;
    private final EvaluationRunMapper evaluationRunMapper;
    private final EvaluationCaseMapper evaluationCaseMapper;
    private final BuildVersionService buildVersionService;
    private final String promptVersion;
    private final String ruleVersion;
    private final String modelVersion;

    public RegressionEvaluationService(ObjectMapper objectMapper,
                                       CityAgentSupervisor supervisor,
                                       AgentTraceService traceService,
                                       EvaluationService evaluationService,
                                       EvaluationRunMapper evaluationRunMapper,
                                       EvaluationCaseMapper evaluationCaseMapper,
                                       BuildVersionService buildVersionService,
                                       @Value("${city.prompt.version:v1}") String promptVersion,
                                       @Value("${city.rule.version:v1}") String ruleVersion,
                                       @Value("${city.llm.main-model:qwen3.8-flash}") String modelVersion) {
        this.objectMapper = objectMapper;
        this.supervisor = supervisor;
        this.traceService = traceService;
        this.evaluationService = evaluationService;
        this.evaluationRunMapper = evaluationRunMapper;
        this.evaluationCaseMapper = evaluationCaseMapper;
        this.buildVersionService = buildVersionService;
        this.promptVersion = promptVersion;
        this.ruleVersion = ruleVersion;
        this.modelVersion = modelVersion;
    }

    public synchronized RegressionEvaluationReport run(Long ownerUserId, RegressionEvaluationRequest request) {
        boolean judge = request != null && Boolean.TRUE.equals(request.getIncludeLlmJudge());
        int limit = request == null || request.getLimit() == null ? 50 : Math.max(1, Math.min(100, request.getLimit()));
        String evalSetResource = resolveEvalSetResource(request == null ? null : request.getSuite());
        List<String> traceIds = new ArrayList<>();
        String evalSetVersion = "v1";
        String evalSetHash;
        try (InputStream input = new ClassPathResource(evalSetResource).getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            evalSetVersion = root.path("version").asText("v1");
            JsonNode cases = root.path("cases");
            if (!cases.isArray()) throw new CityException("评测集格式错误：cases 必须是数组");

            List<JsonNode> allCases = new ArrayList<>();
            cases.forEach(allCases::add);
            for (EvaluationCaseRow row : evaluationCaseMapper.findBySetVersion(ownerUserId, evalSetVersion)) {
                allCases.add(objectMapper.readTree(row.getCaseJson()));
            }
            List<JsonNode> selectedCases = allCases.stream().limit(limit).toList();
            evalSetHash = EvaluationSetFingerprint.sha256(objectMapper, selectedCases);

            for (JsonNode testCase : selectedCases) {
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
                    finalResponse = supervisor.chat(ownerUserId,
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
            throw new CityException("固定评测集执行失败: " + evalSetResource, error);
        }

        EvaluationReport report = evaluationService.evaluateTraceIds(ownerUserId, traceIds, judge);
        report = enrichRuntimeMetrics(report, traceService.findByTraceIds(ownerUserId, traceIds));

        ReactReleaseGate.Result absoluteReactGate = null;
        if (isReactSuite(evalSetVersion)) {
            absoluteReactGate = ReactReleaseGate.evaluate(report.metricAverages());
            report = withReactReleaseGateMetrics(report, absoluteReactGate);
        }

        String runId = "eval_" + UUID.randomUUID().toString().replace("-", "");
        String gitCommit = buildVersionService.gitCommit();
        EvaluationRunRow baseline = evaluationRunMapper.findBaseline(ownerUserId, evalSetVersion, evalSetHash);
        RegressionEvaluationReport result = compare(
                report, runId, evalSetVersion, evalSetHash, gitCommit, baseline);
        if (absoluteReactGate != null && !absoluteReactGate.passed() && result.passed()) {
            result = withPassed(result, false);
        }

        EvaluationRunRow current = new EvaluationRunRow();
        current.setRunId(runId);
        current.setUserId(ownerUserId);
        current.setEvalSetVersion(evalSetVersion);
        current.setEvalSetHash(evalSetHash);
        current.setGitCommit(gitCommit);
        current.setPromptVersion(promptVersion);
        current.setRuleVersion(ruleVersion);
        current.setModelVersion(modelVersion);
        current.setTotalTraces(report.totalTraces());
        current.setAvgScore(report.avgScore());
        current.setMetricSnapshot(toJson(report.metricAverages()));
        current.setBaselineRunId(baseline == null ? null : baseline.getRunId());
        current.setBaseline(false);
        current.setBaselineName(null);
        current.setPassed(result.passed());
        evaluationRunMapper.insert(current);
        return result;
    }

    public void promote(Long userId, PromoteEvaluationCaseRequest request) {
        if (request == null || request.caseDefinition() == null || !request.caseDefinition().isObject()) {
            throw new CityException("评测用例内容不能为空");
        }
        JsonNode definition = request.caseDefinition();
        String caseId = definition.path("id").asText("").trim();
        if (caseId.isBlank()) throw new CityException("评测用例必须包含 id");
        EvaluationCaseRow row = new EvaluationCaseRow();
        row.setUserId(userId);
        row.setCaseId(caseId);
        row.setEvalSetVersion(currentEvalSetVersion());
        row.setCaseJson(toJson(definition));
        row.setSourceTraceId(request.traceId());
        row.setCreatedBy(userId);
        evaluationCaseMapper.insert(row);
    }

    /** 将一个已通过的 EvaluationRun 显式提升为其评测集指纹下的唯一 Baseline。 */
    @Transactional
    public synchronized EvaluationRunRow promoteBaseline(Long userId, PromoteBaselineRequest request) {
        if (request == null || request.runId() == null || request.runId().isBlank()) {
            throw new CityException("runId 不能为空");
        }
        EvaluationRunRow target = evaluationRunMapper.findByRunId(userId, request.runId().trim());
        if (target == null) {
            throw new CityException("评测 Run 不存在");
        }
        if (!Boolean.TRUE.equals(target.getPassed())) {
            throw new CityException("未通过 Regression Gate 的 Run 不能设为 Baseline");
        }
        if (target.getEvalSetHash() == null || target.getEvalSetHash().isBlank()) {
            throw new CityException("该 Run 缺少 evalSetHash，不能设为新版 Baseline");
        }

        String baselineName = normalizeBaselineName(request.baselineName(), target);
        evaluationRunMapper.clearBaseline(userId, target.getEvalSetVersion(), target.getEvalSetHash());
        int updated = evaluationRunMapper.markBaseline(userId, target.getRunId(), baselineName);
        if (updated != 1) {
            throw new CityException("Baseline 标记失败");
        }
        return evaluationRunMapper.findByRunId(userId, target.getRunId());
    }

    static String resolveEvalSetResource(String suite) {
        if (suite == null || suite.isBlank() || "default".equalsIgnoreCase(suite.trim())) {
            return DEFAULT_EVAL_SET_RESOURCE;
        }
        if ("react".equalsIgnoreCase(suite.trim())) {
            return REACT_EVAL_SET_RESOURCE;
        }
        throw new CityException("未知评测 suite，仅支持 default / react");
    }

    private boolean isReactSuite(String evalSetVersion) {
        return evalSetVersion != null && evalSetVersion.toLowerCase().startsWith("react-");
    }

    private EvaluationReport withReactReleaseGateMetrics(EvaluationReport report,
                                                         ReactReleaseGate.Result gate) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        if (report.metricAverages() != null) metrics.putAll(report.metricAverages());
        metrics.put("reactReleaseGatePass", gate.passed() ? 1.0 : 0.0);
        metrics.put("reactReleaseGateFailureCount", (double) gate.failures().size());
        return new EvaluationReport(
                report.startAt(), report.endAt(), report.totalTraces(), report.labeledTraces(),
                report.avgScore(), Map.copyOf(metrics), report.traceResults());
    }

    private RegressionEvaluationReport withPassed(RegressionEvaluationReport report, boolean passed) {
        return new RegressionEvaluationReport(
                report.runId(), report.evalSetVersion(), report.evalSetHash(), report.gitCommit(),
                report.promptVersion(), report.ruleVersion(), report.modelVersion(), report.report(),
                report.baselineRunId(), report.baselineScore(), report.scoreDelta(), report.metricDeltas(), passed);
    }

    private String currentEvalSetVersion() {
        try (InputStream input = new ClassPathResource(DEFAULT_EVAL_SET_RESOURCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            return root.path("version").asText("v1");
        } catch (Exception error) {
            throw new CityException("评测集版本读取失败", error);
        }
    }

    private RegressionEvaluationReport compare(EvaluationReport report,
                                               String runId,
                                               String version,
                                               String evalSetHash,
                                               String gitCommit,
                                               EvaluationRunRow baseline) {
        if (baseline == null) {
            return new RegressionEvaluationReport(
                    runId, version, evalSetHash, gitCommit, promptVersion, ruleVersion, modelVersion,
                    report, null, null, null, Map.of(), true);
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
        return new RegressionEvaluationReport(
                runId, version, evalSetHash, gitCommit, promptVersion, ruleVersion, modelVersion,
                report, baseline.getRunId(), baseline.getAvgScore(),
                gate.scoreDelta(), gate.metricDeltas(), gate.passed());
    }

    private EvaluationReport enrichRuntimeMetrics(EvaluationReport report,
                                                  List<RequestTraceRow> traces) {
        if (report == null) return null;

        AgentRuntimeMetricsExtractor extractor = new AgentRuntimeMetricsExtractor(objectMapper);
        Map<String, Double> merged = new LinkedHashMap<>();
        if (report.metricAverages() != null) {
            merged.putAll(report.metricAverages());
        }
        merged.putAll(extractor.aggregate(traces));

        Map<String, Map<String, Double>> runtimeByTraceId = new LinkedHashMap<>();
        if (traces != null) {
            for (RequestTraceRow trace : traces) {
                if (trace == null || trace.getTraceId() == null || trace.getTraceId().isBlank()) continue;
                runtimeByTraceId.put(trace.getTraceId(), extractor.perTrace(trace));
            }
        }

        List<TraceEvaluationResult> enrichedTraceResults = report.traceResults() == null
                ? List.of()
                : report.traceResults().stream()
                        .map(result -> {
                            Map<String, Double> metrics = new LinkedHashMap<>();
                            if (result.metrics() != null) metrics.putAll(result.metrics());
                            Map<String, Double> runtime = runtimeByTraceId.get(result.traceId());
                            if (runtime != null) {
                                runtime.forEach((name, value) -> {
                                    if (value != null) metrics.put(name, value);
                                });
                            }
                            return new TraceEvaluationResult(
                                    result.traceId(),
                                    result.sessionId(),
                                    result.createdAt(),
                                    result.score(),
                                    result.ruleScore(),
                                    result.llmJudgeScore(),
                                    result.userFeedbackScore(),
                                    metrics,
                                    result.detail()
                            );
                        })
                        .toList();

        return new EvaluationReport(
                report.startAt(),
                report.endAt(),
                report.totalTraces(),
                report.labeledTraces(),
                report.avgScore(),
                Map.copyOf(merged),
                enrichedTraceResults
        );
    }

    private String normalizeBaselineName(String requestedName, EvaluationRunRow target) {
        if (requestedName != null && !requestedName.isBlank()) {
            String trimmed = requestedName.trim();
            if (trimmed.length() > 128) {
                throw new CityException("baselineName 长度不能超过 128");
            }
            return trimmed;
        }
        String commit = target.getGitCommit();
        if (commit == null || commit.isBlank()) {
            commit = "unknown";
        }
        if (commit.length() > 12) {
            commit = commit.substring(0, 12);
        }
        return "baseline-" + target.getEvalSetVersion() + "-" + commit;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new CityException("评估指标序列化失败", error);
        }
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
        try {
            return SourceMode.valueOf(value.toUpperCase());
        } catch (Exception ignored) {
            return SourceMode.PUBLIC;
        }
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
