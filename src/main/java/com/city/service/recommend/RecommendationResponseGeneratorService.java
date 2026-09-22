package com.city.service.recommend;

import com.city.enums.SourceMode;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.RecommendationExecutionResult;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 新 RecommendationAgent 与旧响应格式之间的过渡适配层。
 *
 * <p>候选选择已经由 RecommendationAgent 完成；这里仅把已验证 selectedActivities 交给
 * RecommendResponseAgentService 生成理由和 speechText。旧响应 Agent 因而不再看到未被选中的候选。</p>
 */
@Service
public class RecommendationResponseGeneratorService {
    private final RecommendResponseAgentService legacyResponseService;

    public RecommendationResponseGeneratorService(RecommendResponseAgentService legacyResponseService) {
        this.legacyResponseService = Objects.requireNonNull(legacyResponseService, "legacyResponseService");
    }

    public RecommendResponseAgentService.Result generate(
            String sessionId,
            String userInput,
            SourceMode sourceMode,
            SlotBundle slots,
            RecommendationExecutionResult execution,
            WeatherRecommendationContext weather
    ) {
        Objects.requireNonNull(execution, "execution");
        return legacyResponseService.recommendAndRespond(
                sessionId,
                userInput,
                sourceMode,
                slots,
                execution.selectedActivities(),
                weather
        );
    }
}
