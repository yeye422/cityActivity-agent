package com.city.service.evaluation;

import com.city.enums.SourceMode;
import com.city.exception.CityException;
import com.city.model.ActivityItem;
import com.city.model.ActivitySearchRequest;
import com.city.model.SlotBundle;
import com.city.model.retrieval.RetrievalRequest;
import com.city.model.retrieval.RetrievalResult;
import com.city.service.retrieval.RetrievalPipeline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 直接运行当前 RetrievalPipeline 的离线基线评测。
 *
 * <p>不经过 IntentAgent / RecommendationAgent / LLM；人工 relevance 使用稳定 activityKey。
 * 后续 Vector / Hybrid 实现应继续复用相同 case 和指标，避免比较口径变化。</p>
 */
@Service
public class RetrievalBaselineEvaluationService {
    private static final String RESOURCE = "evaluation/retrieval-relevance-v1.json";

    private final ObjectMapper objectMapper;
    private final RetrievalPipeline retrievalPipeline;

    public RetrievalBaselineEvaluationService(ObjectMapper objectMapper,
                                               RetrievalPipeline retrievalPipeline) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.retrievalPipeline = Objects.requireNonNull(retrievalPipeline, "retrievalPipeline");
    }

    public Report run(Long userId, Integer requestedK) {
        int k = requestedK == null ? 5 : Math.max(1, Math.min(20, requestedK));
        CaseSet caseSet = loadCaseSet();
        List<CaseRun> caseRuns = new ArrayList<>();
        List<StableRetrievalQualityEvaluator.CaseResult> metricResults = new ArrayList<>();

        for (LoadedCase testCase : caseSet.cases()) {
            ActivitySearchRequest searchRequest = new ActivitySearchRequest(
                    testCase.sourceMode(),
                    userId,
                    testCase.slots(),
                    List.of()
            );
            RetrievalResult retrieval = retrievalPipeline.retrieve(new RetrievalRequest(
                    searchRequest,
                    testCase.query(),
                    null,
                    k
            ));
            StableRetrievalQualityEvaluator.CaseResult metrics =
                    StableRetrievalQualityEvaluator.evaluate(
                            new StableRetrievalQualityEvaluator.CaseDefinition(
                                    testCase.caseId(),
                                    testCase.relevance(),
                                    testCase.expectedNoResult()
                            ),
                            retrieval.finalCandidates(),
                            k
                    );
            metricResults.add(metrics);
            caseRuns.add(new CaseRun(
                    testCase.caseId(),
                    testCase.query(),
                    retrieval.rawCandidates().size(),
                    retrieval.rankedCandidates().size(),
                    metrics.topKActivityKeys(),
                    metrics.recallAtK(),
                    metrics.ndcgAtK(),
                    metrics.noResultFalsePositive()
            ));
        }

        return new Report(
                caseSet.version(),
                caseSet.evalSetHash(),
                "CURRENT_PIPELINE",
                k,
                StableRetrievalQualityEvaluator.summarize(metricResults),
                List.copyOf(caseRuns)
        );
    }

    CaseSet loadCaseSet() {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            String version = root.path("version").asText("").trim();
            if (version.isBlank()) throw new CityException("Retrieval 评测集缺少 version");
            JsonNode casesNode = root.path("cases");
            if (!casesNode.isArray()) throw new CityException("Retrieval 评测集 cases 必须为数组");

            List<JsonNode> fingerprintCases = new ArrayList<>();
            List<LoadedCase> cases = new ArrayList<>();
            for (JsonNode node : casesNode) {
                fingerprintCases.add(node.deepCopy());
                String caseId = node.path("id").asText("").trim();
                String query = node.path("query").asText("").trim();
                if (caseId.isBlank() || query.isBlank()) {
                    throw new CityException("Retrieval 评测 case 缺少 id/query");
                }
                cases.add(new LoadedCase(
                        caseId,
                        parseSourceMode(node.path("sourceMode").asText("PUBLIC")),
                        parseSlots(node.path("slots")),
                        query,
                        parseRelevance(node.path("relevance")),
                        node.path("expectedNoResult").asBoolean(false)
                ));
            }
            return new CaseSet(
                    version,
                    EvaluationSetFingerprint.sha256(objectMapper, fingerprintCases),
                    List.copyOf(cases)
            );
        } catch (CityException error) {
            throw error;
        } catch (Exception error) {
            throw new CityException("Retrieval 评测集读取失败", error);
        }
    }

    private Map<String, Double> parseRelevance(JsonNode node) {
        if (!node.isObject()) throw new CityException("Retrieval relevance 必须为对象");
        Map<String, Double> relevance = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getKey().isBlank() && entry.getValue().isNumber()) {
                relevance.put(entry.getKey(), Math.max(0.0, entry.getValue().asDouble()));
            }
        });
        return Map.copyOf(relevance);
    }

    private SlotBundle parseSlots(JsonNode node) {
        if (node == null || !node.isObject()) return SlotBundle.empty();
        return new SlotBundle(
                strings(node.path("city")),
                strings(node.path("location")),
                strings(node.path("experienceGoal")),
                strings(node.path("companion")),
                strings(node.path("budget")),
                strings(node.path("activityType")),
                strings(node.path("style")),
                strings(node.path("duration")),
                strings(node.path("feature"))
        );
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            String text = value.asText("").trim();
            if (!text.isBlank()) values.add(text);
        }
        return List.copyOf(values);
    }

    private SourceMode parseSourceMode(String value) {
        try {
            return SourceMode.valueOf(value == null ? "PUBLIC" : value.trim().toUpperCase());
        } catch (Exception error) {
            throw new CityException("Retrieval 评测 sourceMode 非法: " + value);
        }
    }

    record CaseSet(String version, String evalSetHash, List<LoadedCase> cases) { }

    record LoadedCase(
            String caseId,
            SourceMode sourceMode,
            SlotBundle slots,
            String query,
            Map<String, Double> relevance,
            boolean expectedNoResult
    ) { }

    public record CaseRun(
            String caseId,
            String query,
            int rawCandidateCount,
            int rankedCandidateCount,
            List<String> topKActivityKeys,
            Double recallAtK,
            Double ndcgAtK,
            Double noResultFalsePositive
    ) { }

    public record Report(
            String evalSetVersion,
            String evalSetHash,
            String strategy,
            int k,
            StableRetrievalQualityEvaluator.Summary summary,
            List<CaseRun> cases
    ) { }
}
