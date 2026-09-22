package com.city.service.evaluation;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.SlotBundle;
import com.city.model.retrieval.RetrievalRequest;
import com.city.model.retrieval.RetrievalResult;
import com.city.service.retrieval.RetrievalPipeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalBaselineEvaluationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldLoadStableRetrievalCaseSetWithHardCityAndSoftQuery() {
        RetrievalBaselineEvaluationService service =
                new RetrievalBaselineEvaluationService(objectMapper, mock(RetrievalPipeline.class));

        RetrievalBaselineEvaluationService.CaseSet caseSet = service.loadCaseSet();

        assertEquals("retrieval-v1", caseSet.version());
        assertNotNull(caseSet.evalSetHash());
        assertEquals(64, caseSet.evalSetHash().length());
        assertEquals(6, caseSet.cases().size());

        RetrievalBaselineEvaluationService.LoadedCase first = caseSet.cases().getFirst();
        assertEquals(SourceMode.PUBLIC, first.sourceMode());
        assertEquals(List.of("西安"), first.slots().city());
        assertFalse(first.query().contains("西安"));
        assertFalse(first.relevance().isEmpty());
    }

    @Test
    void shouldRunCurrentPipelineAndAggregatePerfectBaseline() {
        RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
        when(pipeline.retrieve(any())).thenAnswer(invocation -> {
            RetrievalRequest request = invocation.getArgument(0);
            String query = request.queryText();
            String city = request.searchRequest().slots().city().isEmpty()
                    ? ""
                    : request.searchRequest().slots().city().getFirst();

            List<ActivityItem> ranked;
            if (query.contains("情侣")) {
                ranked = List.of(
                        activity(101L, "西安", "曲江艺术中心周末特展"),
                        activity(102L, "西安", "小寨独立影院观影"),
                        activity(103L, "西安", "钟楼商圈桌游主题夜")
                );
            } else if (query.contains("互动性")) {
                ranked = List.of(
                        activity(104L, "西安", "高新室内攀岩体验课"),
                        activity(103L, "西安", "钟楼商圈桌游主题夜")
                );
            } else if (query.contains("安静放松")) {
                ranked = List.of(
                        activity(101L, "西安", "曲江艺术中心周末特展"),
                        activity(102L, "西安", "小寨独立影院观影")
                );
            } else if ("北京".equals(city)) {
                ranked = List.of(activity(201L, "北京", "朝阳小剧场开放麦"));
            } else if ("上海".equals(city)) {
                ranked = List.of(activity(301L, "上海", "徐汇摄影艺术展"));
            } else {
                ranked = List.of();
            }
            return new RetrievalResult(ranked, ranked, ranked, List.of(), List.of());
        });

        RetrievalBaselineEvaluationService service =
                new RetrievalBaselineEvaluationService(objectMapper, pipeline);

        RetrievalBaselineEvaluationService.Report report = service.run(999999L, 5);

        assertEquals("retrieval-v1", report.evalSetVersion());
        assertNotNull(report.evalSetHash());
        assertEquals(64, report.evalSetHash().length());
        assertEquals("CURRENT_PIPELINE", report.strategy());
        assertEquals(5, report.k());
        assertEquals(6, report.cases().size());
        assertEquals(6, report.summary().totalCases());
        assertEquals(1.0, report.summary().recallAtK(), 1e-9);
        assertEquals(1.0, report.summary().ndcgAtK(), 1e-9);
        assertEquals(0.0, report.summary().noResultFalsePositiveRate(), 1e-9);
    }

    @Test
    void shouldClampRequestedK() {
        RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
        when(pipeline.retrieve(any())).thenReturn(
                new RetrievalResult(List.of(), List.of(), List.of(), List.of(), List.of()));
        RetrievalBaselineEvaluationService service =
                new RetrievalBaselineEvaluationService(objectMapper, pipeline);

        assertEquals(1, service.run(1L, -10).k());
        assertEquals(20, service.run(1L, 100).k());
    }

    private ActivityItem activity(Long id, String city, String name) {
        SlotBundle slots = new SlotBundle(
                List.of(city), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        return new ActivityItem(
                id, SourceMode.PUBLIC, null, name, slots,
                null, null, null, null, 120, 0.8);
    }
}
