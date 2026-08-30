package com.city.service.plan;

import com.city.agent.factory.AgentFactory;
import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivityResponse;
import com.city.model.RecommendResult;
import com.city.model.RecommendedActivityOption;
import com.city.model.ResponseResult;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.service.recommend.RecommendResponseAgentService;
import com.city.service.trace.AgentTraceService;
import com.city.util.LlmJsonService;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多时段规划 Agent：Java 为每个时段提供已完成硬约束过滤和 Rank 的 TopK 候选，
 * Agent 只在对应候选池内做受约束的全局组合选择，并生成理由与最终口语回复。
 * Agent 输出会再次由 Java 校验；非法、重复或缺失选择退化为确定性 Rank fallback。
 */
@Service
public class PlanResponseAgentService {

    private final AgentFactory agentFactory;
    private final LlmJsonService llmJsonService;
    private final AgentTraceService agentTraceService;
    private final String modelName;

    public PlanResponseAgentService(
            AgentFactory agentFactory,
            LlmJsonService llmJsonService,
            AgentTraceService agentTraceService,
            @Value("${diet.llm.response-model:qwen-turbo}") String modelName
    ) {
        this.agentFactory = agentFactory;
        this.llmJsonService = llmJsonService;
        this.agentTraceService = agentTraceService;
        this.modelName = modelName;
    }

    public RecommendResponseAgentService.Result planAndRespond(
            String sessionId,
            String userInput,
            SourceMode sourceMode,
            SlotBundle sharedSlots,
            List<ActivityPlanService.PlannedActivity> plannedActivities,
            WeatherRecommendationContext weather
    ) {
        List<ActivityPlanService.PlannedActivity> safePlans = plannedActivities == null ? List.of() : plannedActivities;
        boolean hasCandidate = safePlans.stream().anyMatch(plan -> !candidatePool(plan).isEmpty());
        boolean needDisclaimer = needsDisclaimer(sharedSlots);

        if (!hasCandidate) {
            RecommendResult empty = RecommendResult.empty();
            return new RecommendResponseAgentService.Result(
                    empty,
                    ResponseResult.textOnly("当前这些时段里还没有找到足够合适的活动，我先不硬凑。如果你愿意，可以放宽一点活动类型、区域或其他偏好，我再帮你补一版。")
            );
        }

        try {
            ReActAgent agent = agentFactory.get(sessionId).planResponse();
            agent.getMemory().clear();
            Msg response = agentTraceService.callAgent(
                    sessionId,
                    "PlanResponseAgent",
                    modelName,
                    agent,
                    buildUserPrompt(userInput, sourceMode, sharedSlots, safePlans, weather)
            );
            ParsedOutput parsed = parseOutput(response.getTextContent(), safePlans, sharedSlots);
            RecommendResult recommend = new RecommendResult(parsed.options(), needDisclaimer);
            ResponseResult responseResult = new ResponseResult(
                    parsed.speechText(), toDisplayBlocks(recommend, parsed.selectedPlans()), "WAIT_USER");
            return new RecommendResponseAgentService.Result(recommend, responseResult);
        } catch (Exception ignored) {
            List<ActivityPlanService.PlannedActivity> fallbackPlans = deterministicFallback(safePlans);
            RecommendResult recommend = new RecommendResult(templateOptions(fallbackPlans, sharedSlots), needDisclaimer);
            return new RecommendResponseAgentService.Result(
                    recommend,
                    new ResponseResult(
                            templateSpeech(fallbackPlans, recommend),
                            toDisplayBlocks(recommend, fallbackPlans),
                            "WAIT_USER")
            );
        }
    }

    private String buildUserPrompt(
            String userInput,
            SourceMode sourceMode,
            SlotBundle sharedSlots,
            List<ActivityPlanService.PlannedActivity> plannedActivities,
            WeatherRecommendationContext weather
    ) {
        StringBuilder activitySection = new StringBuilder();
        for (ActivityPlanService.PlannedActivity planned : plannedActivities) {
            List<ActivityItem> candidates = candidatePool(planned);
            activitySection.append("\n- period=").append(planned.period()).append(", candidates=[");
            for (int i = 0; i < candidates.size(); i++) {
                ActivityItem activity = candidates.get(i);
                if (i > 0) {
                    activitySection.append(", ");
                }
                activitySection.append("{")
                        .append("activityId=").append(activity.id())
                        .append(", name=").append(activity.name())
                        .append(", slots=").append(activity.slots())
                        .append(", validFrom=").append(activity.validFrom())
                        .append(", validTo=").append(activity.validTo())
                        .append(", validStartTime=").append(activity.validStartTime())
                        .append(", validEndTime=").append(activity.validEndTime())
                        .append(", matchScore=").append(activity.matchScore())
                        .append("}");
            }
            activitySection.append("]");
        }
        String weatherSummary = weather != null && weather.active()
                ? weather.summary()
                : "未启用天气排序";
        return """
                用户原话：%s
                数据源模式：%s
                用户共享条件：%s
                天气排序说明：%s
                Java 为各时段生成的已排序候选池：%s

                请只输出一个合法 JSON 对象，顶层只能包含 mealPlans 和 speechText。
                mealPlans 中每个实际选择项必须包含 period、activityId、reason。

                规划目标：
                - 在每个 period 自己的候选池中最多选择 1 个活动，不能跨时段选取候选。
                - 优先保证相关性，不要为了组合效果明显牺牲 matchScore。
                - 在相关性接近时，优先提高活动类型多样性，并尽量让 location 更连贯，减少区域来回切换。
                - 不允许不同 period 重复选择同一个 activityId。

                强制规则：
                - activityId 必须来自对应 period 的 candidates，禁止编造、换用其他时段活动或新增候选。
                - candidates=[] 的时段不要伪造 activityId，也不要为了凑满行程硬塞其他时段候选。
                - reason 和 speechText 只能使用候选明确提供的 facts、用户条件和天气说明，不要根据活动名称脑补地址、价格、具体玩法、距离、开放状态或主观体验。
                - 每个最终选择的时段用 1 句说明最有区分度的匹配理由。
                - 如果某个时段 candidates=[]，先自然介绍已经选到的安排，再说明该时段当前没有足够合适的候选，我先不硬凑；可以提示用户后续单独放宽该时段条件再补一个。
                - 不要把部分时段未匹配描述成整个规划失败，也不要只写“暂无匹配”“没有活动”这种生硬句子。
                - speechText 必须完整写出最终选择的各时段、活动名称和推荐理由；如果有缺失时段，也要自然说明。
                - 禁止输出“...”、示例占位理由、虚构 activityId 或候选中不存在的 period。
                - 最终只输出 JSON，不要输出 Markdown 代码块或 JSON 之外的文字。
                """.formatted(userInput, sourceMode, sharedSlots, weatherSummary, activitySection);
    }

    private ParsedOutput parseOutput(String content,
                                     List<ActivityPlanService.PlannedActivity> plannedActivities,
                                     SlotBundle sharedSlots) {
        JsonNode root = llmJsonService.parseObject(content);
        Map<String, ActivityPlanService.PlannedActivity> plansByPeriod = new LinkedHashMap<>();
        for (ActivityPlanService.PlannedActivity planned : plannedActivities) {
            plansByPeriod.put(planned.period(), planned);
        }

        Map<String, AgentSelection> requestedByPeriod = new LinkedHashMap<>();
        JsonNode plansNode = root.path("mealPlans");
        if (plansNode.isArray()) {
            plansNode.forEach(node -> {
                String period = node.path("period").asText("").trim();
                long activityId = node.path("activityId").asLong(0L);
                String reason = node.path("reason").asText("").trim();
                ActivityPlanService.PlannedActivity expected = plansByPeriod.get(period);
                ActivityItem selected = findCandidate(expected, activityId);
                if (selected != null && !reason.equals("...")) {
                    requestedByPeriod.put(period, new AgentSelection(selected, reason));
                }
            });
        }

        List<ActivityPlanService.PlannedActivity> selectedPlans = new ArrayList<>();
        List<RecommendedActivityOption> options = new ArrayList<>();
        Set<Long> usedIds = new LinkedHashSet<>();
        boolean adjusted = false;

        for (ActivityPlanService.PlannedActivity planned : plannedActivities) {
            AgentSelection requested = requestedByPeriod.get(planned.period());
            ActivityItem selected = requested == null ? null : requested.activity();
            String reason = requested == null ? "" : requested.reason();

            if (selected == null || usedIds.contains(selected.id())) {
                selected = fallbackCandidate(planned, usedIds);
                reason = "";
                if (!candidatePool(planned).isEmpty()) {
                    adjusted = true;
                }
            }

            if (selected == null) {
                selectedPlans.add(new ActivityPlanService.PlannedActivity(
                        planned.period(), null, planned.querySlots(), candidatePool(planned)));
                continue;
            }

            usedIds.add(selected.id());
            ActivityPlanService.PlannedActivity selectedPlan = new ActivityPlanService.PlannedActivity(
                    planned.period(), selected, planned.querySlots(), candidatePool(planned));
            selectedPlans.add(selectedPlan);
            if (reason.isBlank()) {
                reason = templateReason(selectedPlan, sharedSlots);
            }
            options.add(toOption(selected, reason, planned.querySlots()));
        }

        String speechText = root.path("speechText").asText("").trim();
        if (speechText.isBlank() || speechText.equals("...") || adjusted) {
            speechText = templateSpeech(
                    selectedPlans, new RecommendResult(options, needsDisclaimer(sharedSlots)));
        }
        return new ParsedOutput(options, speechText, selectedPlans);
    }

    private ActivityItem findCandidate(ActivityPlanService.PlannedActivity planned, long activityId) {
        if (planned == null || activityId <= 0) {
            return null;
        }
        return candidatePool(planned).stream()
                .filter(activity -> activity.id() != null && activity.id() == activityId)
                .findFirst()
                .orElse(null);
    }

    private ActivityItem fallbackCandidate(ActivityPlanService.PlannedActivity planned, Set<Long> usedIds) {
        if (planned == null) {
            return null;
        }
        if (planned.activity() != null
                && planned.activity().id() != null
                && !usedIds.contains(planned.activity().id())) {
            return planned.activity();
        }
        return candidatePool(planned).stream()
                .filter(activity -> activity.id() != null && !usedIds.contains(activity.id()))
                .findFirst()
                .orElse(null);
    }

    private List<ActivityPlanService.PlannedActivity> deterministicFallback(
            List<ActivityPlanService.PlannedActivity> plannedActivities) {
        List<ActivityPlanService.PlannedActivity> result = new ArrayList<>();
        Set<Long> usedIds = new LinkedHashSet<>();
        for (ActivityPlanService.PlannedActivity planned : plannedActivities) {
            ActivityItem selected = fallbackCandidate(planned, usedIds);
            if (selected != null) {
                usedIds.add(selected.id());
            }
            result.add(new ActivityPlanService.PlannedActivity(
                    planned.period(), selected, planned.querySlots(), candidatePool(planned)));
        }
        return result;
    }

    private List<ActivityItem> candidatePool(ActivityPlanService.PlannedActivity planned) {
        if (planned == null) {
            return List.of();
        }
        if (planned.candidates() != null && !planned.candidates().isEmpty()) {
            return planned.candidates();
        }
        return planned.activity() == null ? List.of() : List.of(planned.activity());
    }

    private List<RecommendedActivityOption> templateOptions(
            List<ActivityPlanService.PlannedActivity> plannedActivities,
            SlotBundle sharedSlots) {
        List<RecommendedActivityOption> options = new ArrayList<>();
        for (ActivityPlanService.PlannedActivity planned : plannedActivities) {
            if (!planned.matched()) {
                continue;
            }
            options.add(toOption(
                    planned.activity(),
                    templateReason(planned, sharedSlots),
                    planned.querySlots()));
        }
        return options;
    }

    private RecommendedActivityOption toOption(ActivityItem activity, String reason, SlotBundle querySlots) {
        SlotBundle displaySlots = querySlots != null ? querySlots : activity.slots();
        return new RecommendedActivityOption(
                activity.id(), activity.sourceType(), activity.name(), reason, activity.matchScore(), displaySlots);
    }

    private String templateReason(ActivityPlanService.PlannedActivity planned, SlotBundle sharedSlots) {
        String name = planned.activity().name();
        if (sharedSlots != null && !sharedSlots.budget().isEmpty()) {
            return name + "符合你当前的预算条件，适合作为" + planned.period() + "的候选。";
        }
        if (sharedSlots != null && !sharedSlots.style().isEmpty()) {
            return name + "比较贴近你想要的" + String.join("、", sharedSlots.style()) + "风格，适合安排在" + planned.period() + "。";
        }
        return name + "和你当前条件匹配度较高，适合安排在" + planned.period() + "。";
    }

    private String templateSpeech(
            List<ActivityPlanService.PlannedActivity> plannedActivities,
            RecommendResult recommendResult) {
        List<ActivityPlanService.PlannedActivity> matchedPlans = plannedActivities.stream()
                .filter(ActivityPlanService.PlannedActivity::matched)
                .toList();
        List<String> missingPeriods = plannedActivities.stream()
                .filter(planned -> !planned.matched())
                .map(ActivityPlanService.PlannedActivity::period)
                .toList();

        StringBuilder builder = new StringBuilder();
        if (!matchedPlans.isEmpty()) {
            builder.append("我先按当前能匹配到的时段给你排一下：");
            for (ActivityPlanService.PlannedActivity planned : matchedPlans) {
                String reason = recommendResult.recommendations().stream()
                        .filter(option -> option.itemId().equals(planned.activity().id()))
                        .map(RecommendedActivityOption::reason)
                        .findFirst()
                        .orElse(planned.activity().name());
                builder.append("\n- ").append(planned.period()).append("：")
                        .append(planned.activity().name()).append("（").append(reason).append("）");
            }
        }

        if (!missingPeriods.isEmpty()) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            if (missingPeriods.size() == 1) {
                builder.append(missingPeriods.get(0))
                        .append("这段当前候选里没有足够合适的活动，我先不硬凑。")
                        .append("如果你愿意，我可以只针对")
                        .append(missingPeriods.get(0))
                        .append("放宽部分条件，再补一个合适的安排。");
            } else {
                builder.append(String.join("、", missingPeriods))
                        .append("这几个时段当前候选里没有足够合适的活动，我先不硬凑。")
                        .append("如果你愿意，我可以只针对这些时段放宽部分条件，再继续补齐。");
            }
        } else if (!matchedPlans.isEmpty()) {
            builder.append("\n如果想换其中某一段，可以直接告诉我具体时段和新偏好。");
        }

        if (recommendResult.needDisclaimer()) {
            builder.append("\n这些建议仅供周末娱乐参考，具体安排请根据实际情况调整。");
        }
        return builder.toString();
    }

    /** 规划卡片始终以 Agent 合法选中的原始 ActivityItem 为事实来源。 */
    private List<ActivityResponse> toDisplayBlocks(
            RecommendResult recommendResult,
            List<ActivityPlanService.PlannedActivity> plans) {
        if (recommendResult == null || recommendResult.recommendations() == null
                || plans == null || plans.isEmpty()) {
            return List.of();
        }
        Map<Long, ActivityItem> byId = new LinkedHashMap<>();
        for (ActivityPlanService.PlannedActivity planned : plans) {
            if (planned != null && planned.matched()) {
                ActivityItem activity = planned.activity();
                byId.putIfAbsent(activity.id(), activity);
            }
        }
        return recommendResult.recommendations().stream()
                .map(option -> option == null ? null : byId.get(option.itemId()))
                .filter(activity -> activity != null)
                .map(ActivityResponse::from)
                .toList();
    }

    private boolean needsDisclaimer(SlotBundle slots) {
        return slots != null && slots.budget().stream().anyMatch(value ->
                value.contains("减脂") || value.contains("低糖") || value.contains("控碳水") || value.contains("养胃"));
    }

    private record AgentSelection(ActivityItem activity, String reason) {
    }

    private record ParsedOutput(
            List<RecommendedActivityOption> options,
            String speechText,
            List<ActivityPlanService.PlannedActivity> selectedPlans
    ) {
    }
}
