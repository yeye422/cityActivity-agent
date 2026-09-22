package com.city.service.orchestrator;

import com.city.enums.ClarifyField;
import com.city.enums.Intent;
import com.city.model.ChatResponse;
import com.city.model.DecisionResponseResult;
import com.city.model.IntentResult;
import com.city.model.RelaxationContext;
import com.city.model.RelaxationOption;
import com.city.model.ResponseResult;
import com.city.model.SessionState;
import com.city.model.SlotMutation;
import com.city.model.WeatherRecommendationContext;
import com.city.service.activity.RelaxationSearchService;
import com.city.service.clarify.ClarifyRuleService;
import com.city.service.plan.PlanningDecisionFacade;
import com.city.service.recommend.RecommendationDecisionFacade;
import com.city.service.trace.AgentTraceService;
import com.city.service.weather.WeatherRecommendationService;
import com.city.service.workflow.AdjustWorkflow;
import com.city.service.workflow.PlanningWorkflow;
import com.city.service.workflow.RecommendWorkflow;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Java 负责 Workflow/Clarify，ReAct Agent 负责动态推荐/规划决策；不回退旧 Worker/ResponseAgent。 */
@Service
public class CityFlowWorkflowExecutor {
    private final RecommendWorkflow recommendWorkflow;
    private final AdjustWorkflow adjustWorkflow;
    private final PlanningWorkflow planningWorkflow;
    private final RecommendationDecisionFacade recommendationDecisionFacade;
    private final PlanningDecisionFacade planningDecisionFacade;
    private final WeatherRecommendationService weatherRecommendationService;
    private final RelaxationSearchService relaxationSearchService;
    private final ClarifyRuleService clarifyRuleService;
    private final DecisionCommitService commitService;
    private final AgentTraceService traceService;

    public CityFlowWorkflowExecutor(RecommendWorkflow recommendWorkflow,
                                    AdjustWorkflow adjustWorkflow,
                                    PlanningWorkflow planningWorkflow,
                                    RecommendationDecisionFacade recommendationDecisionFacade,
                                    PlanningDecisionFacade planningDecisionFacade,
                                    WeatherRecommendationService weatherRecommendationService,
                                    RelaxationSearchService relaxationSearchService,
                                    ClarifyRuleService clarifyRuleService,
                                    DecisionCommitService commitService,
                                    AgentTraceService traceService) {
        this.recommendWorkflow = recommendWorkflow;
        this.adjustWorkflow = adjustWorkflow;
        this.planningWorkflow = planningWorkflow;
        this.recommendationDecisionFacade = recommendationDecisionFacade;
        this.planningDecisionFacade = planningDecisionFacade;
        this.weatherRecommendationService = weatherRecommendationService;
        this.relaxationSearchService = relaxationSearchService;
        this.clarifyRuleService = clarifyRuleService;
        this.commitService = commitService;
        this.traceService = traceService;
    }

    public ChatResponse recommend(String userInput,
                                  String traceId,
                                  SessionState state,
                                  IntentResult intent,
                                  boolean publicFallbackUsed) {
        RecommendWorkflow.Preparation preparation = recommendWorkflow.prepare(state, intent);
        recordMutation(state, intent, preparation.mutation());
        if (preparation.missingField() != null) {
            return clarify(traceId, preparation.state(), preparation.missingField());
        }
        return executeRecommendationDecision(
                userInput, traceId, preparation.state(), List.of(), publicFallbackUsed);
    }

    public ChatResponse adjust(String userInput,
                               String traceId,
                               SessionState state,
                               IntentResult intent,
                               boolean publicFallbackUsed) {
        AdjustWorkflow.Preparation preparation = adjustWorkflow.prepare(state, intent);
        recordMutation(state, intent, preparation.mutation());
        if (preparation.missingField() != null) {
            return clarify(traceId, preparation.state(), preparation.missingField());
        }

        SessionState workingState = preparation.state();
        String currentQueryKey = DecisionCommitService.recommendationQueryKey(workingState);
        boolean queryChanged = !currentQueryKey.equals(state.recommendationQueryKey());
        List<Long> excludeIds = queryChanged || state.lastRecommendedActivityIds() == null
                ? List.of()
                : state.lastRecommendedActivityIds();
        traceService.recordEvent("ADJUST_CONTEXT_RESOLVED", "ADJUST", intent,
                Map.of("queryChanged", queryChanged, "excludeActivityIds", excludeIds));
        return executeRecommendationDecision(
                userInput, traceId, workingState, excludeIds, publicFallbackUsed);
    }

    public ChatResponse plan(String userInput,
                             String traceId,
                             SessionState state,
                             IntentResult intent,
                             boolean publicFallbackUsed) {
        PlanningWorkflow.Preparation preparation = planningWorkflow.prepare(state, intent);
        recordMutation(state, intent, preparation.mutation());
        if (preparation.missingField() != null) {
            return clarify(traceId, preparation.state(), preparation.missingField());
        }

        SessionState workingState = preparation.state();
        WeatherRecommendationContext weather = weatherRecommendationService.resolve(
                workingState.slots(), workingState.timeConstraint());
        traceService.recordEvent("PLAN_WEATHER_CONTEXT_RESOLVED", "RANK", workingState, weather);
        try {
            DecisionResponseResult result = planningDecisionFacade.plan(
                    userInput, traceId, workingState, preparation.windows(), weather, List.of());
            traceService.recordEvent("PLAN_RESULT_BUILT", "PLAN", workingState, result.recommend());
            return commitService.commitDecision(
                    userInput, traceId, workingState, result, publicFallbackUsed);
        } catch (RuntimeException error) {
            traceService.recordError("PLANNING_DEGRADED", "PLAN", workingState, error);
            return commitService.commitText(
                    traceId, workingState, Intent.ACTIVITY_PLAN,
                    ResponseResult.textOnly("规划服务暂时无法完成这次组合，请稍后重试或调整一个时间条件。"), false);
        }
    }

    private ChatResponse executeRecommendationDecision(String userInput,
                                                       String traceId,
                                                       SessionState state,
                                                       List<Long> excludeIds,
                                                       boolean publicFallbackUsed) {
        WeatherRecommendationContext weather = weatherRecommendationService.resolve(
                state.slots(), state.timeConstraint());
        traceService.recordEvent("WEATHER_CONTEXT_RESOLVED", "RANK", state, weather);
        try {
            DecisionResponseResult result = recommendationDecisionFacade.recommend(
                    userInput, traceId, state, excludeIds, weather);
            traceService.recordEvent("RECOMMEND_RESULT_BUILT", "RECOMMEND", state, result.recommend());
            if (result.recommend().recommendations().isEmpty()) {
                List<RelaxationOption> options = relaxationSearchService.options(
                        state.sourceMode(), state.userId(), state.slots(), state.excludedSlots(),
                        excludeIds, state.timeConstraint());
                if (!options.isEmpty()) {
                    String queryKey = DecisionCommitService.recommendationQueryKey(state);
                    RelaxationContext context = new RelaxationContext(
                            state.sourceMode(), queryKey, excludeIds,
                            options.stream().map(RelaxationOption::level).toList());
                    String message = "没有找到完全匹配的活动。可以选择一个放宽方案，我只会放宽软偏好，不会改动城市、时间、预算和显式排除条件。";
                    traceService.recordEvent("RELAXATION_OPTIONS_READY", "SEARCH", state, options);
                    return commitService.commitRelaxation(traceId, state, message, options, context);
                }
            }
            return commitService.commitDecision(
                    userInput, traceId, state, result, publicFallbackUsed);
        } catch (RuntimeException error) {
            traceService.recordError("RECOMMENDATION_DEGRADED", "RECOMMEND", state, error);
            return commitService.commitText(
                    traceId, state,
                    state.currentIntent() == null ? Intent.ACTIVITY_RECOMMENDATION : state.currentIntent(),
                    ResponseResult.textOnly("推荐服务暂时无法完成这次决策，请稍后重试或补充一个活动偏好。"), false);
        }
    }

    private ChatResponse clarify(String traceId, SessionState state, ClarifyField field) {
        String question = clarifyRuleService.questionFor(field);
        traceService.recordEvent("CLARIFY_DECISION", "CLARIFY", state,
                Map.of("action", "ASK", "missingSlots", List.of(field.key()), "questionToAsk", question));
        return commitService.commitClarification(traceId, state, field, question);
    }

    private void recordMutation(SessionState state, IntentResult intent, SlotMutation mutation) {
        traceService.recordEvent("SLOT_MUTATION_APPLIED", "SLOT", intent.operations(),
                Map.of("included", mutation.included(),
                        "excluded", mutation.excluded(),
                        "unconstrained", mutation.unconstrained()));
    }
}
