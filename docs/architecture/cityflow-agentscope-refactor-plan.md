# CityFlow 基于 AgentScope 1.0 的架构改造计划

## 1. 改造目标

在保留现有数据库、领域模型、SessionState、多轮槽位、检索排序、PlanningSolver、Memory、Trace、Evaluation、Feedback 和前端接口的基础上，将当前“Java 主导业务执行 + LLM 负责理解和最终响应”的架构，逐步升级为：

```text
Java Supervisor / Workflow
负责业务流程、状态提交和硬约束边界

AgentScope ReActAgent
负责推荐 / 规划中的动态决策、Tool Use 和方案修复

RetrievalPipeline / PlanningSolver
负责事实检索、召回排序和确定性约束校验
```

最终目标架构：

```text
API / AG-UI / SSE
        ↓
CityAgentSupervisor
        ↓
WorkflowRouter
        ↓
RecommendWorkflow / AdjustWorkflow / PlanningWorkflow
        ↓
ContextWorker
        ↓
IntentAgent
        ↓
HardConstraints + UserGoal
        ↓
Java Clarify / State Patch
        ↓
┌───────────────────────────────┐
│ RecommendationAgent           │
│ PlanningAgent + PlanNotebook  │
│      AgentScope ReActAgent    │
└───────────────────────────────┘
        ↓
      Toolkit
        ↓
RetrievalTool / EvidenceTool
PreferenceTool / WeatherTool
TravelTool / PlanValidationTool
        ↓
RetrievalPipeline / PlanningSolver
        ↓
Structured Decision
        ↓
Final Validator
        ↓
ResponseGenerator
        ↓
Supervisor Commit SessionState
```

核心原则：

- Supervisor 控制一轮请求，不承担推荐、排序、规划等具体业务实现。
- Workflow 描述推荐、调整、规划三个稳定业务流程。
- AgentScope `ReActAgent` 负责“下一步做什么”，而不是接管业务事实。
- Tool 是 Agent 可调用的业务能力边界。
- RetrievalPipeline 保证单个候选合法并完成召回排序。
- PlanningSolver 保证多个候选组成的方案合法。
- SessionState 是业务真实状态，只由 Java 提交。
- EvidenceStore 保证 Agent 输出的实体全部可追溯。
- Agent 不允许修改用户已确认的硬约束。

---

## 2. 当前项目基础与改造范围

当前项目已经具备：

```text
Spring Boot + Java 21
AgentScope 1.0.11

CityAgentSupervisor

RecommendWorkflow
AdjustWorkflow
PlanningWorkflow
WorkflowRouter

ContextWorker
RetrievalWorker
PlanningWorker
ResponseWorker
MemoryWorker
WorkerDispatcher

ActivitySearchService
ActivityRankService
ActivityDiversityService

PlanningSolver
AgentExecutionHarness

SessionState
Memory
Trace
Evaluation
Feedback
```

因此本轮不重新搭建项目，采用原地渐进式架构迁移。

当前主要问题：

1. `CityAgentSupervisor` 仍直接依赖大量业务 Service 和 Worker，职责过重。
2. `RecommendResponseAgent` 和 `PlanResponseAgent` 虽然基于 AgentScope `ReActAgent`，但主要用于最终响应生成，没有真正使用 Toolkit 进行动态 Tool Use。
3. Retrieval 仍主要由 Java 主动调用，Agent 无法根据 Candidate Pool 质量主动调整检索方向。
4. Planning 目前主要由 Java 生成和筛选方案，Agent 缺少 `Plan -> Validate -> Repair` 的动态规划能力。
5. Retrieval 尚未形成完整的 `Hard Filter + BM25 + Vector + RRF + Reranker + Diversity` Pipeline。
6. Harness 已有模型调用预算、重复调用检测和熔断，但缺少 Agent Tool 白名单、Tool Budget、Evidence / Candidate 校验等业务治理。

本次改造不重写以下业务底座：

```text
数据库 / Mapper
Activity / Session / Venue
SessionState
SlotBundle
TimeConstraint
SET / ADD / REMOVE / CLEAR
Memory
Trace
Evaluation
Feedback
Weather
ActivitySearchService
ActivityRankService
ActivityDiversityService
PlanningSolver
```

---

## 3. 改造总原则

### 3.1 硬约束与软目标分离

明确区分：

```text
HardConstraints
= 必须满足

UserGoal
= 尽量满足
```

例如：

```text
用户：
“周六下午约会，预算 200，别太累，最好特别一点”
```

解析为：

```text
HardConstraints
- 周六下午
- budget <= 200

UserGoal
- DATE
- LOW_FATIGUE
- NOVELTY
- INTERACTION
```

预算、时间、城市、Session OPEN 等硬约束由 Java 保证；“约会、轻松、新鲜、有互动”等软目标由 Agent 参与决策。

### 3.2 Agent 不修改硬约束

Agent 调用 Retrieval Tool 时，只允许提供：

```text
retrievalIntent
preferredFeatures
emphasizeGoals
```

以下参数由 `VerifiedRequestContext` 注入：

```text
city
time
budget
participantCount
excludedCategories
excludedActivityIds
SessionState
```

因此 Agent 可以决定“怎么找”，但不能决定“预算是否还要遵守”。

### 3.3 新旧链路渐进替换

统一使用：

```text
Add
↓
Adapt
↓
Switch
↓
Verify
↓
Delete
```

禁止先删除旧链路，再等待新链路全部完成。

### 3.4 每个阶段保持可运行

每一阶段至少满足：

```text
编译通过
核心单测通过
现有接口可用
Regression Gate 不低于基线
```

---

# P0：冻结当前基线

## 目标

建立改造前可比较的功能和评测基线。

## 任务

### P0.1 固定高频场景

至少覆盖：

```text
普通推荐
条件不足澄清
预算调整
类别排除
换一批
PERSONAL -> PUBLIC fallback
单时段规划
多时段规划
无候选降级
Session 不可用
天气影响
长期偏好
多轮约束修改
```

### P0.2 保存基线指标

记录：

```text
Intent 准确率
Slot 准确率
推荐成功率
规划合法率
Fallback 率
平均 LLM 调用次数
平均 Token
P50 / P95 延迟
```

未知指标保持 `UNKNOWN`，不提前填入目标提升值。

### P0.3 建立改造分支

建议：

```text
refactor/agentscope-react
```

## 验收

- `mvn test` 通过。
- 核心 Regression Gate 通过。
- 当前推荐 / 规划行为完成测试快照。

---

# P1：建立 HardConstraints + UserGoal 语义模型

## 目标

避免所有语义都塞入 Slot，使后续 Retrieval 和 Agent 有明确边界。

## 新增模型

```java
record HardConstraints(
    String city,
    Set<String> districts,
    TimeConstraint time,
    Integer maxBudget,
    Integer participantCount,
    Set<String> excludedCategories,
    Set<Long> excludedActivityIds
) {}
```

```java
record UserGoal(
    Occasion occasion,
    List<Goal> goals,
    Map<Goal, Priority> priorities
) {}
```

调整 `ContextResult`：

```java
record ContextResult(
    Intent intent,
    SlotPatch slotPatch,
    TimeConstraint timeConstraint,
    HardConstraints hardConstraints,
    UserGoal userGoal,
    MemoryMutationProposal memoryProposal
) {}
```

## 兼容策略

现有九维 `SlotBundle` 不删除：

```text
SlotBundle
= 多轮业务状态

HardConstraints
= 本轮必须满足的条件

UserGoal
= Agent 用于软决策的目标
```

## 验收

- 原有 Slot 多轮能力不退化。
- 预算、时间、城市等不会进入 UserGoal。
- “有意思、轻松、约会、特别”等不再被 Java 固定规则过度解释。

---

# P2：统一 RetrievalPipeline

## 目标

把当前 `ActivitySearchService + ActivityRankService + ActivityDiversityService` 收敛到一个稳定 Retrieval 边界，为 RecommendationAgent / PlanningAgent 提供统一 Tool。

## P2.1 第一版 Pipeline

先复用已有能力：

```text
Hard Filter
↓
ActivitySearchService
↓
ActivityRankService
↓
ActivityDiversityService
↓
History Dedup
↓
LegalCandidateSet
```

定义：

```java
class RetrievalPipeline {
    RetrievalResult retrieve(RetrievalRequest request);
}
```

```java
record RetrievalRequest(
    HardConstraints hardConstraints,
    String retrievalIntent,
    List<String> preferredFeatures,
    Set<Long> excludedIds,
    Integer topK
) {}
```

## P2.2 第二版混合召回

在第一版稳定后逐步加入：

```text
Hard Filter
↓
BM25 / Keyword
+
Vector Retrieval
↓
RRF Fusion
↓
Dedicated Reranker
↓
Diversity
↓
History Dedup
```

新增：

```text
KeywordRetriever
VectorRetriever
RrfFusion
Reranker
```

## P2.3 Hard Filter 要求

进入 Agent 的候选必须已经满足：

```text
Activity 存在
Session 存在
Session OPEN
时间匹配
容量满足
预算满足
城市满足
用户排除项满足
```

定义：

```text
RetrievalPipeline
负责“这个活动能不能选”

RecommendationAgent
负责“合法活动里哪个更值得选”
```

## 验收

- Recommendation 与 Planning 共用 RetrievalPipeline。
- PERSONAL / PUBLIC 行为兼容。
- 历史推荐去重正常。
- Agent 不收到违反硬约束的 Candidate。

---

# P3：建立 Tool 边界和 VerifiedRequestContext

## 目标

将现有 Java 能力包装成 AgentScope Toolkit，而不是让 Agent 直接调用底层 Service。

## P3.1 VerifiedRequestContext

```java
record VerifiedRequestContext(
    Long userId,
    String sessionId,
    String runId,
    String traceId,
    HardConstraints hardConstraints,
    UserGoal userGoal,
    SessionSnapshot sessionSnapshot,
    AgentBudget budget
) {}
```

## P3.2 推荐 Toolkit

允许：

```text
searchActivities
searchAlternativeActivities
getActivityEvidence
getUserPreferences
getWeather
```

禁止：

```text
updateSessionState
writeMemory
rawSQL
createActivity
validatePlan
```

## P3.3 规划 Toolkit

允许：

```text
searchActivities
searchAlternativeActivities
getActivityEvidence
getTravelTime
getWeather
validatePlan
```

禁止：

```text
updateSessionState
writeMemory
rawSQL
```

## P3.4 过渡方式

第一阶段：

```text
RetrievalTool
↓
现有 RetrievalWorker
```

最终：

```text
RetrievalTool
↓
RetrievalPipeline
```

## 验收

- Agent 无法修改预算、时间、城市等硬约束。
- Tool 输出带 EvidenceRef。
- RecommendationAgent / PlanningAgent 使用不同 Toolkit。

---

# P4：RecommendationAgent ReAct 化

## 目标

将当前 `RecommendResponseAgent` 从“响应生成 Agent”升级为真正的推荐决策 Agent。

当前：

```text
Retrieval
↓
Rank
↓
RecommendResponseAgent
↓
生成推荐理由
```

目标：

```text
RecommendationWorker
↓
RecommendationAgent
AgentScope ReActAgent
↓
Reason
↓
RetrievalTool
↓
Observation
↓
Evaluate Candidate Pool
↓
必要时 Re-Retrieve
↓
Select
```

## P4.1 Agent 职责

负责：

```text
分析 UserGoal
比较合法候选
判断 Candidate Pool 是否覆盖核心 Goal
处理候选间 trade-off
必要时修改软检索意图再次 Retrieval
形成 RecommendationDecision
```

不负责：

```text
预算判断
Session OPEN 校验
数据库查询
硬过滤
状态写入
```

## P4.2 Candidate Pool 不足时的 Re-Retrieval

例如第一次结果：

```text
艺术展
电影院
摄影展
博物馆
```

如果用户核心目标为：

```text
INTERACTION
NOVELTY
```

Agent 可判断 Candidate Pool 合法但 Goal Coverage 不足，并输出新的软检索意图：

```text
“互动性强、有参与感和新鲜体验的双人活动”
```

HardConstraints 保持不变，再执行一次 Retrieval。

## P4.3 调用预算

第一版建议：

```text
maxIterations = 4
maxRetrievalCalls = 2
maxEvidenceCalls = 3
```

## P4.4 结构化输出

```java
record RecommendationDecision(
    List<Long> selectedActivityIds,
    List<CandidateAssessment> assessments,
    String decisionSummary,
    Double confidence
) {}
```

最终执行：

```text
RecommendationDecision
↓
CandidateValidator
↓
EvidenceStore
↓
ResponseGenerator
```

## 验收

- Agent 不输出 Candidate Set 外 activityId。
- 当前候选足够时不会无意义反复检索。
- Candidate Pool Goal Coverage 不足时可触发一次 Re-Retrieval。
- Retrieval 调用次数受 Budget 约束。

---

# P5：切换 RecommendWorkflow 并拆分响应职责

## 目标

让推荐决策和自然语言表达彻底分离。

最终：

```text
RecommendationAgent
= 决策

ResponseGeneratorService
= 表达
```

新增：

```java
class ResponseGeneratorService {
    ChatResponse generateRecommendation(
        RecommendationDecision decision,
        UserGoal goal,
        List<EvidenceRef> evidence
    );
}
```

`ResponseGeneratorService` 不允许：

```text
重新检索
重新排序
更换 Activity
修改 Decision
```

完成新链路并通过 Regression Gate 后，再删除：

```text
RecommendResponseAgentService
ResponseWorker 中推荐相关逻辑
```

---

# P6：PlanningAgent + PlanNotebook

## 目标

在保留现有 PlanningSolver 的基础上，让 PlanningAgent 具备动态：

```text
Plan
→ Search
→ Propose
→ Validate
→ Repair
→ Validate
```

当前：

```text
PlanningWorkflow
↓
PlanningWorker
↓
PlanningSolver
↓
PlanResponseAgent
```

目标：

```text
PlanningWorkflow
↓
PlanningWorker
↓
PlanningAgent
AgentScope ReActAgent
+
PlanNotebook
↓
Planning Toolkit
↓
RetrievalTool / TravelTool / PlanValidationTool
↓
PlanningSolver
```

## P6.1 PlanningAgent 职责

负责：

```text
理解整体行程目标
划分规划阶段
决定各阶段软检索目标
搜索合法候选
组合 PlanProposal
调用 validatePlan
读取 Solver Feedback
决定保留哪些核心活动
修复冲突阶段
必要时重新 Retrieval
```

## P6.2 PlanNotebook

示例：

```text
[x] 找下午活动
[x] 找晚上活动
[ ] 组合 Plan
[ ] Validate
[ ] Repair
```

PlanNotebook 管“Agent 下一步还要做什么”。

PlanningSolver 管“这个用户行程到底是否合法”。

二者不能互相替代。

## P6.3 Planning Budget

建议：

```text
maxIterations = 8
maxRetrievalCalls = 4
maxPlanValidationCalls = 3
```

---

# P7：PlanValidationTool + Solver Feedback Repair

## 目标

让现有 PlanningSolver 从“最终筛选器”升级为 Agent 可调用的确定性验证器。

新增：

```java
@Tool
PlanValidationResult validatePlan(PlanProposal proposal)
```

内部直接调用：

```text
PlanningSolver
```

## P7.1 Solver 检查

保持 Java 确定性校验：

```text
Activity 是否存在
Session 是否存在
Session OPEN
容量
时间冲突
活动时长
交通时间
活动重复
总预算
用户排除项
```

## P7.2 Structured Violation

Solver 不只返回：

```text
INVALID
```

而应返回：

```java
record ConstraintViolation(
    ViolationType type,
    String stage,
    Long activityId,
    String message,
    RepairHint repairHint
) {}
```

例如：

```text
TRAVEL_TIME_CONFLICT
previousEnd = 17:20
nextStart = 18:00
travelTime = 55min
earliestFeasibleStart = 18:15
repairHint = CHANGE_NEXT_SESSION / SEARCH_NEARBY
```

PlanningAgent 根据反馈判断：

```text
问题来自下一阶段距离过远
→ 保留核心活动
→ 重新搜索下一阶段
```

## 验收

- PlanningAgent 可以根据 Solver Feedback 修复方案。
- 最终 Plan 必须再次经过 Solver。
- 不存在 Agent 自创 Activity / Session。
- 多时段规划无确定性时间冲突。

---

# P8：切换 PlanningWorkflow

## 目标

将线上 Planning 链切到新 AgentScope ReAct 链。

最终：

```text
PlanningWorkflow
↓
ContextWorker / IntentAgent
↓
HardConstraints + UserGoal
↓
Java Clarify
↓
PlanningWorker
↓
PlanningAgent + PlanNotebook
↓
RetrievalTool
↓
PlanProposal
↓
validatePlan Tool
↓
PlanningSolver
↓
INVALID -> Repair -> validatePlan
VALID -> PlanDecision
↓
Final Validator
↓
ResponseGenerator
```

新链路通过后，再删除旧 `PlanResponseAgent` 的决策职责。

---

# P9：EvidenceStore 与最终实体校验

## 目标

所有 Agent 决策必须基于可追溯事实。

新增：

```java
class EvidenceStore {
    EvidenceRef save(...);
    boolean verifyCandidate(String runId, Long activityId);
    ActivityEvidence getVerifiedActivity(...);
}
```

记录：

```text
Activity Evidence
Session Evidence
Weather Evidence
Travel Evidence
Retrieval Evidence
```

执行：

```text
Tool Result
↓
EvidenceStore
↓
EvidenceRef
↓
Agent
↓
Structured Decision
↓
Final Validator
↓
EvidenceStore 再校验
```

要求：

- 所有推荐 Activity 必须来自当前 Run 的 Retrieval Evidence。
- 所有规划 Session 必须来自真实 Session Evidence。
- Agent 不得输出候选外实体。

---

# P10：Harness 收敛为 AgentScope Hook + CityFlow Guard

## 目标

不再自己实现 ReAct Loop，现有 Harness 转为业务治理层。

AgentScope 负责：

```text
Reason
Tool Call
Observation
ReAct Loop
PlanNotebook
```

CityFlow Guard 负责：

```text
Tool 白名单
Tool Call Budget
Candidate ID 校验
Evidence 校验
Plan 校验
Tool 参数检查
超时
熔断
重复调用检测
降级
```

现有 `AgentExecutionHarness` 中以下能力继续复用：

```text
模型调用预算
重复输入检测
Loop Detection
Circuit Breaker
Half-Open 恢复
```

逐步接入 AgentScope Hook：

```text
beforeReasoning
afterReasoning
beforeActing
afterActing
```

## 验收

- RecommendationAgent 无法调用 Planning 专属 Tool。
- PlanningAgent 无法修改 SessionState。
- Tool 调用超过预算会被终止。
- 重复 Retrieval 可被检测。

---

# P11：Memory 写入收敛

## 目标

保留现有 PreferenceMemoryService，但禁止 Agent 自由写长期记忆。

链路：

```text
IntentAgent
↓
MemoryMutationProposal
↓
MemoryPolicy
↓
MemoryWorker
↓
PreferenceMemoryService
```

允许：

```text
明确长期偏好
长期排除
明确黑名单
```

拒绝：

```text
本次预算
一次性日期
临时地址
一次性同行人
未确认弱偏好
```

## 验收

- 没有未经 Policy 的长期写入。
- 删除记忆后立即失效。
- 黑名单在 Retrieval Hard Filter 阶段生效。

---

# P12：彻底瘦身 CityAgentSupervisor

## 目标

这是最后执行的架构收口，不应在 Agent 链完成前提前大拆。

最终 Supervisor 只依赖：

```text
SessionStateService
WorkflowRouter
AgentRunService
BusinessTraceService
```

最终职责：

```text
load SessionState
↓
create Run
↓
route Workflow
↓
workflow.execute
↓
commit StatePatch
↓
complete Trace
```

从 Supervisor 移除：

```text
ActivitySearchService
ActivityRankService
ActivityDiversityService
RelaxationSearchService
WeatherRecommendationService
PlanningSolver
Recommend Agent
Plan Agent
具体推荐 / 规划业务逻辑
```

## 验收

- Supervisor 不包含具体推荐算法。
- Supervisor 不直接执行 Retrieval。
- Supervisor 不直接调用业务 Agent。
- Supervisor 是 SessionState 唯一最终提交者。

---

# P13：实时运行与恢复

## 目标

保留并强化 AG-UI + SSE 实时交互能力，使 Agent 多步 Tool Use 可被前端观察且断线可恢复。

运行模型：

```text
AgentRun
AgentRunEvent
eventSeq
Last-Event-ID
IdempotencyKey
```

链路：

```text
AgentScope Hook / Workflow
↓
AgentRunEventService
↓
Persistent Event Log
↓
SSE Adapter
↓
AG-UI Event
↓
Frontend
```

核心规则：

```text
SSE disconnect != Run cancel
```

重连：

```text
Last-Event-ID
↓
查询 eventSeq > Last-Event-ID
↓
补发缺失事件
```

不得重新执行已经完成的：

```text
LLM Call
Retrieval Tool
PlanningSolver
```

---

# P14：Trace 与 Evaluation 扩展

## Trace 新增事件

```text
AGENT_STARTED
AGENT_REASONING_STARTED
TOOL_CALLED
TOOL_COMPLETED
CANDIDATE_POOL_EVALUATED
RE_RETRIEVAL_TRIGGERED
RECOMMENDATION_DECIDED
PLAN_PROPOSED
PLAN_VALIDATION_FAILED
PLAN_REPAIRED
PLAN_VALIDATED
```

## Retrieval 评测

```text
Recall@K
NDCG@K
No-result false positive
```

比较：

```text
BM25 only
Vector only
BM25 + Vector + RRF
```

混合召回只有在离线评测证明有效后才作为默认方案。

## RecommendationAgent 评测

```text
UserGoal Coverage
Re-Retrieval Rate
Candidate 外实体率
平均 Tool Call
Agent 决策成功率
```

## Planning 评测

```text
Plan Valid Rate
Repair Success Rate
时间冲突率
预算违规率
Session 幻觉率
```

## 系统评测

```text
Token
P50 / P95 latency
Fallback Rate
Tool Error Rate
Circuit Breaker Rate
```

---

# P15：删除兼容代码

只有在新链路全部通过 Regression Gate 后再删除旧实现。

候选删除项：

```text
RecommendResponseAgentBuilder
PlanResponseAgentBuilder
RecommendResponseAgentService
PlanResponseAgentService
DiscoveryWorker
旧 ResponseWorker
过渡期 RetrievalWorker
Supervisor 中遗留的业务实现
```

`EvaluationJudgeAgent` 保留用于离线评测。

删除前必须确认：

```text
无线上引用
无测试引用
新链路功能等价或更优
Regression Gate 全部通过
```

---

## 4. 最终推荐链

```text
用户
↓
Supervisor
↓
RecommendWorkflow
↓
ContextWorker
↓
IntentAgent
↓
HardConstraints + UserGoal
↓
Java Clarify
↓
RecommendationWorker
↓
RecommendationAgent
↓
RetrievalTool
↓
RetrievalPipeline
↓
Legal TopK
↓
Agent Evaluate Candidate Pool
↓
候选池足够？
├─ Yes -> Select
└─ No
    ↓
  调整软 RetrievalIntent
    ↓
  RetrievalTool
    ↓
  新 CandidateSet
↓
RecommendationDecision
↓
CandidateValidator / EvidenceStore
↓
ResponseGenerator
↓
WorkflowResult
↓
Supervisor Commit SessionState
```

原则：

```text
Recall
负责“找得到”

Rerank
负责“基本排得对”

RecommendationAgent
负责“结合本次 UserGoal 最终怎么选，以及当前 Candidate Pool 是否值得继续决策”
```

---

## 5. 最终规划链

```text
用户
↓
Supervisor
↓
PlanningWorkflow
↓
ContextWorker
↓
IntentAgent
↓
HardConstraints + UserGoal
↓
Java Clarify
↓
PlanningWorker
↓
PlanningAgent + PlanNotebook
↓
RetrievalTool
↓
Legal CandidateSet
↓
PlanProposal
↓
validatePlan Tool
↓
PlanningSolver
↓
VALID？
├─ Yes
│   ↓
│ PlanDecision
│
└─ No
    ↓
  Structured ConstraintViolation
    ↓
  PlanningAgent
    ↓
  Repair
    ↓
  Re-Retrieve / Change Session
    ↓
  validatePlan
↓
Final Validator
↓
ResponseGenerator
↓
Supervisor Commit SessionState
```

原则：

```text
RetrievalPipeline
保证“单个 Activity 能不能选”

PlanningSolver
保证“这些 Activity 能不能一起选”

PlanningAgent
负责“为了完成整体目标，怎样组合和修复方案”
```

---

## 6. 推荐实施顺序

严格按以下顺序执行：

```text
P0  冻结评测基线
↓
P1  HardConstraints + UserGoal
↓
P2  RetrievalPipeline
↓
P3  Tool + VerifiedRequestContext
↓
P4  RecommendationAgent ReAct 化
↓
P5  切换 RecommendWorkflow
↓
P6  PlanningAgent + PlanNotebook
↓
P7  validatePlan Tool + Solver Feedback Repair
↓
P8  切换 PlanningWorkflow
↓
P9  EvidenceStore
↓
P10 Hook / Guard
↓
P11 Memory Policy 收敛
↓
P12 Supervisor 瘦身
↓
P13 SSE / Run Recovery
↓
P14 完整 Evaluation + Regression Gate
↓
P15 删除兼容代码
```

不建议一开始同时改：

```text
Supervisor
Retrieval
RecommendationAgent
PlanningAgent
SSE
Memory
```

否则无法判断回归来自哪一层。

---

## 7. 最终代码结构建议

```text
com.city

├── controller
│   └── chat / activity / evaluation / ...
│
├── service
│   ├── orchestrator
│   │   └── CityAgentSupervisor
│   │
│   ├── workflow
│   │   ├── WorkflowRouter
│   │   ├── RecommendWorkflow
│   │   ├── AdjustWorkflow
│   │   └── PlanningWorkflow
│   │
│   ├── worker
│   │   ├── ContextWorker
│   │   ├── RecommendationWorker
│   │   ├── PlanningWorker
│   │   └── MemoryWorker
│   │
│   ├── retrieval
│   │   ├── RetrievalPipeline
│   │   ├── HardConstraintFilter
│   │   ├── KeywordRetriever
│   │   ├── VectorRetriever
│   │   ├── RrfFusion
│   │   ├── Reranker
│   │   └── DiversityService
│   │
│   ├── plan
│   │   ├── PlanningSolver
│   │   └── TravelTimeService
│   │
│   ├── memory
│   ├── session
│   ├── trace
│   ├── evaluation
│   └── response
│
├── agent
│   ├── builder / factory
│   ├── IntentAgent
│   ├── RecommendationAgent
│   └── PlanningAgent
│
├── tool
│   ├── RetrievalTool
│   ├── EvidenceTool
│   ├── PreferenceTool
│   ├── WeatherTool
│   ├── TravelTool
│   └── PlanValidationTool
│
├── guard
│   ├── AgentGuardHook
│   ├── ToolAccessGuard
│   ├── AgentBudgetGuard
│   ├── CandidateValidator
│   └── PlanDecisionValidator
│
├── evidence
│   └── EvidenceStore
│
└── model
    ├── HardConstraints
    ├── UserGoal
    ├── RecommendationDecision
    ├── PlanProposal
    └── PlanValidationResult
```

---

## 8. 最终验收标准

完成改造后至少满足：

1. Supervisor 不再承担具体推荐 / 规划实现。
2. RecommendationAgent 能完成 `Retrieval -> Evaluate -> Re-Retrieve -> Select`。
3. PlanningAgent 能完成 `Plan -> Validate -> Repair -> Validate`。
4. Agent 无法修改 HardConstraints。
5. 所有推荐 Activity 都来自 Retrieval Evidence。
6. 所有规划 Session 都真实且 OPEN。
7. Planning 硬约束完全脱离 LLM 也可以单测。
8. Worker / Agent 不直接提交 SessionState。
9. 长期记忆必须经过 MemoryPolicy。
10. SSE 重连不会重新执行已经完成的 Agent / Tool。
11. Agent Tool Call 有预算和白名单控制。
12. 混合召回是否启用由离线 Retrieval Evaluation 决定，而不是为了技术栈强制启用。
13. Recommendation / Planning 核心指标不得低于旧基线。
14. Regression Gate 全部通过后才能删除旧链路。

---

## 9. 改造策略总结

本次不是 Rewrite，也不是继续在 `CityAgentSupervisor` 中 Patch。

采用：

```text
保留现有业务底座
↓
增加 HardConstraints / UserGoal 边界
↓
建立统一 RetrievalPipeline
↓
把现有 Java 能力包装为受限 Tool
↓
利用 AgentScope 1.0 ReActAgent 建立真正的 Recommendation / Planning 决策层
↓
逐条替换推荐和规划旧链路
↓
最后瘦身 Supervisor 并删除兼容层
```

最终系统应从：

```text
LLM 理解
↓
Java 完成绝大多数决策
↓
LLM 写最终回答
```

升级为：

```text
LLM / IntentAgent 理解用户目标
↓
Java 固定 HardConstraints
↓
AgentScope Recommendation / Planning Agent
自主进行多步检索、判断和方案修复
↓
RetrievalPipeline / PlanningSolver
提供真实数据和确定性校验
↓
Structured Decision
↓
Java Validator + ResponseGenerator
↓
Supervisor Commit SessionState
```

这条路线优先复用当前项目已经完成的 Session、检索、排序、规划、Trace、Evaluation 和 Harness 能力，同时真正发挥 AgentScope 1.0 的 ReAct、Tool Use 和 Planning 能力。