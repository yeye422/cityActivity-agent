# CityFlow AgentScope ReAct 重构计划

> 本文档描述 CityFlow 从旧 Supervisor/Worker + ResponseAgent 结构迁移到 AgentScope Java 1.x 受控 ReAct 架构的分阶段计划。迁移原则是：新链先并行接入、可观测、可回退；只有在固定回归与运行指标证明稳定后才删除旧链。

## 一、最终架构原则

CityFlow 的最终线上职责边界：

```text
API / SSE
↓
CityAgentSupervisor
↓
WorkflowRouter
↓
RecommendWorkflow / AdjustWorkflow / PlanningWorkflow
↓
ContextWorker + IntentAgent
↓
HardConstraints + UserGoal + StatePatch
↓
Java Clarify / State Boundary
↓
RecommendationAgent / PlanningAgent
AgentScope ReAct Runtime
↓
受限 Toolkit
↓
RetrievalPipeline / Travel Service / PlanningSolver
↓
Evidence / Final Validator
↓
ResponseGeneratorService
↓
Supervisor Commit SessionState
```

核心分工：

```text
AgentScope ReActAgent
= Reason -> Tool -> Observe 循环

CityFlow Java
= 状态、权限、硬约束、事实、检索、确定性校验、提交

Agent
= 软目标理解、候选权衡、动态检索、规划策略、冲突修复
```

不得把数据库事实、硬约束判断或最终状态写入交给模型。

---

# P1：冻结旧链与语义边界

## 目标

在不改变线上行为的前提下明确新链需要的输入边界。

新增：

```text
HardConstraints
UserGoal
SemanticContext
VerifiedRequestContext
```

HardConstraints 包括：

```text
city
date / time
budget
sourceMode
explicit exclusions
OPEN Session / capacity 等确定性限制
```

UserGoal 表达：

```text
约会感
互动性
新鲜感
轻松
低疲劳
社交
多样性
```

原则：九维槽位继续存在，但不能把所有自然语言偏好都强行塞成硬槽位。

---

# P2：统一 RetrievalPipeline

## 目标

推荐和规划共用同一个确定性 Retrieval Pipeline。

```text
HardFilter
↓
Keyword / BM25
↓
Vector Retriever（评测证明有效后接入）
↓
RRF（评测证明有效后接入）
↓
Reranker
↓
Diversity
↓
History De-dup
```

当前先复用已有：

```text
ActivitySearchService
ActivityRankService
ActivityDiversityService
```

并统一封装到：

```text
RetrievalPipeline
```

Agent 不直接执行数据库查询。

---

# P3：ToolExecutionContext + 隐藏硬约束

## 目标

让模型只控制软检索意图，不允许修改服务器验证后的业务约束。

```text
RecommendationAgent
↓
search_activities(retrievalIntent)
↓
ToolExecutionContext
↓
VerifiedRequestContext
↓
RetrievalPipeline
```

模型可传：

```text
“更有互动性和参与感的活动”
```

模型不可传/覆盖：

```text
city
budget
date
time
sourceMode
explicit exclusions
```

迁移阶段：

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

当前新 ReAct 主链已经直接使用 `RecommendationDecision + verified activities` 生成响应，不再调用第二个 Response LLM。旧 `RecommendResponseAgentService` 仅为 legacy fallback 保留。

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

当前新 ReAct 主链已经使用合法 `acceptedPlan + decisionSummary` 确定性生成响应，不再调用第二个 `PlanResponseAgent`。旧 PlanResponseAgent 仅为 legacy fallback 保留。

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

当前实现已收敛为：

```text
RunEvidenceStore
↓
CandidateEvidenceRegistry / PlanningEvidenceRegistry
↓
DecisionEvidenceValidator
```

其中 Registry 保留 Tool Budget 和窗口绑定语义，`RunEvidenceStore` 统一保存当前 Run 的 Activity / Session / Travel 权威事实，最终推荐和规划共用 `DecisionEvidenceValidator` 再做一次实体门禁。

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

当前已实现：

```text
traceId:eventSeq SSE id
Last-Event-ID replay
agent_ui_event MySQL 持久化事件日志
每会话 256 条内存热缓存
/chat Idempotency-Key + 原子 claim
SUCCESS response replay
PENDING fail-closed
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

当前已前置接入的运行指标：

```text
reactRouteCoverage
recommendationReactSuccessRate
planningReactSuccessRate
reactFallbackRate
reactToolCallCount
retrievalToolCallCount
reRetrievalRate
travelToolCallCount
planValidationCallCount
planValidationFailureRate
planRepairSuccessRate
evidenceViolationRate
```

这些指标直接从现有 `trace_json.events` 聚合并并入固定评测的 `metricSnapshot`。

## P14.1 通用回归与 ReAct 专项回归隔离

通用评测：

```text
suite=default
resource=evaluation/city-dialogue-eval-set.json
version=v2
```

ReAct 专项评测：

```text
SPRING_PROFILES_ACTIVE=react-eval
suite=react
resource=evaluation/city-react-eval-set.json
version=react-v1
```

两套 suite 使用各自 `version + evalSetHash` 查找 Baseline，禁止互相污染。

`react` suite 执行前会校验：

```text
recommendation-react.enabled == true
planning-react.enabled == true
```

任一未开启则在实际执行评测用例、调用模型前直接拒绝。

## P14.2 ReAct 三层发布门禁

第一层：运行配置门禁

```text
两个 ReAct feature flag 必须开启
```

第二层：首次运行绝对质量门禁 `ReactReleaseGate`

```text
reactRouteCoverage >= 0.60
recommendationReactSuccessRate >= 0.80
planningReactSuccessRate >= 0.70
reactFallbackRate <= 0.20
evidenceViolationRate == 0
```

即使 `react-v1` 还没有 Baseline，未达到上述条件也不能标记为 passed，更不能提升成 Baseline。

第三层：后续相对 Baseline 门禁 `RegressionGate`

```text
总分不得显著下降
reactRouteCoverage 不得下降超过阈值
ReAct success rate 不得下降超过阈值
fallback / evidence violation 不得上升超过阈值
```

Tool Call / Re-Retrieval / Validation Failure 目前只用于观测，不直接作为发布失败条件。

## Retrieval 评测

当前新增纯函数 `RetrievalQualityEvaluator`：

```text
Recall@K
NDCG@K
No-result false positive
```

计划比较：

```text
BM25 only
Vector only
BM25 + Vector + RRF
```

混合召回只有在稳定人工 relevance case 上的离线评测证明有效后才作为默认方案。演示库 activityId 使用自增 ID，因此评测数据不能硬编码环境相关 ID；应使用稳定 case label / activity key，并在运行时映射到真实实体。

---

# 删除旧链的前置条件

只有同时满足以下条件才开始物理删除 legacy：

```text
1. JDK 21 mvn clean verify 通过
2. react-v1 固定专项评测通过 ReactReleaseGate
3. 已存在一个明确提升的 react-v1 Baseline
4. 后续回归通过 RegressionGate
5. evidenceViolationRate = 0
6. 推荐 / 规划新链 fallback 率达到门禁要求
```

在此之前继续保留：

```text
RecommendResponseAgentService
PlanResponseAgentService
RetrievalWorker
PlanningWorker
ResponseWorker
```

它们只承担迁移期 fallback，不代表最终架构。
