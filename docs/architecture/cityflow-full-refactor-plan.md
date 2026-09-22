# CityFlow 最终完整架构改造计划

建议按阶段实施，每个阶段独立验证，避免同时改编排、检索、上下文和传输层后难以定位问题。

整体改造遵循以下原则：

- **Supervisor 负责控制，不负责承载具体业务实现。**
- **Workflow 负责描述业务流程，Worker 负责完成封闭任务。**
- **Worker 不等于 LLM Agent。** Worker 内部可以使用 LLM、Java Service、检索 Pipeline 或确定性 Solver。
- **LLM 负责语义理解、偏好理解、候选选择和自然语言表达；Java 负责状态、权限、事实、检索、硬约束、校验和编排。**
- **Worker 之间不直接互调，不直接修改 `SessionState`，所有跨 Worker 协作经过 Supervisor / Workflow / Dispatcher。**
- **所有实体输出必须来自候选集或可追溯的 `EvidenceRef`，禁止模型自行制造活动、场次或场地。**

目标运行结构：

```text
API / SSE
   ↓
CityAgentSupervisor
   ↓
WorkflowRouter
   ↓
RecommendWorkflow / AdjustWorkflow / PlanningWorkflow
   ↓
AgentTask
   ↓
WorkerDispatcher
   ↓
AgentExecutionHarness
   ↓
ContextWorker / RetrievalWorker / ResponseWorker / MemoryWorker
   ↓
AgentResult + EvidenceRef
```

其中多时段规划采用：

```text
PlanningWorkflow
   ↓
时间窗口拆分
   ↓
N × RetrievalTask
   ↓
RetrievalWorker
   ↓
PlanningSolver
   ↓
合法 PlanCandidate
   ↓
ResponseWorker / PlanResponseAgent
```

## P0：冻结现状与统一项目契约

- 固定当前评测集、测试结果和核心链路基线。
- 记录推荐、调整、澄清、多时段规划、PERSONAL/PUBLIC 降级等现有行为。
- 清理遗留命名：
  - `MEAL_RECOMMENDATION` → `ACTIVITY_RECOMMENDATION`
  - `MEAL_ADJUST` → `ACTIVITY_ADJUST`
  - `dietChat` → `chat`
  - `diet` Prompt 及数据库对象统一为 `city/activity`
- 将九维槽位、`TimeConstraint`、活动—场次—场地关系设为唯一权威合同。
- 定义统一错误码、降级原因和事件类型。
- 建立数据库迁移与回滚脚本，不通过重新初始化数据库完成升级。

**验收：** 现有核心功能不回退，代码及对外描述不再出现饮食领域遗留概念。

## P1：建立统一 Agent 任务与证据合同

- 定义 `AgentTask`，包含任务类型、会话、上下文快照、权限、截止时间和证据引用。
- 定义 `AgentResult`，包含状态、结论、候选 ID、已验证实体、证据引用和警告。
- 定义 `EvidenceRef`，用于引用检索结果、天气、场次和规划证据。
- 规定所有 Worker 只返回结构化结果，不直接修改 `SessionState`。
- 规定 Supervisor 为会话状态的唯一写入者。
- 为活动、场次、场地、天气和地图工具声明只读/写入属性。
- 后续补充 `AgentTask` 的 `traceId`、`runId`、`parentTaskId`、执行预算等运行字段。
- 后续补充 `AgentResult` 的统一状态：`SUCCESS / PARTIAL / DEGRADED / FAILED / CANCELLED`，可恢复异常不得直接击穿整轮请求。

**验收：** Worker 输出中的活动或场次都能关联真实证据，不依赖自然语言解析结果反推业务对象。

## P2：收敛 Supervisor-Workers 架构

当前已形成 `CityAgentSupervisor + ContextWorker + DiscoveryWorker + PlanningWorker + ResponseWorker` 第一版，但当前仍存在 Supervisor 业务逻辑偏重、Worker 抽象层级不统一、Discovery 与 Planning 重复检索、`AgentTask / AgentResult` 尚未成为唯一执行协议等问题。

P2 不再继续增加大量 Agent，而是将现有架构收敛为：

```text
Supervisor
   ↓
Workflow
   ↓
AgentTask
   ↓
WorkerDispatcher
   ↓
Harness
   ↓
Worker
   ↓
AgentResult
```

### P2.1：Supervisor 减肥与 Workflow 拆分

新增业务流程层：

```text
workflow/
├── Workflow
├── WorkflowContext
├── WorkflowResult
├── RecommendWorkflow
├── AdjustWorkflow
└── PlanningWorkflow
```

职责划分：

- `CityAgentSupervisor` 只负责：请求初始化、Session 加载、RunContext 创建、Workflow 路由、最终状态提交、Trace/Event 收口和统一异常处理。
- `RecommendWorkflow` 负责：语义理解 → Patch → Clarify → Retrieval → Response。
- `AdjustWorkflow` 负责：`SET / ADD / REMOVE / CLEAR`、换一批、条件修改和放宽条件确认。
- `PlanningWorkflow` 负责：语义理解 → Clarify → 时间窗口拆分 → 多个 RetrievalTask → PlanningSolver → Response。
- 风险校验、澄清规则、状态提交和场次真实性等确定性控制权继续保留在 Java 侧。

调整后 Supervisor 不应直接依赖以下业务实现：

- `ActivitySearchService`
- `ActivityRankService`
- `ActivityPlanService`
- `RecommendResponseAgentService`
- `PlanResponseAgentService`

**验收：** Supervisor 只负责运行控制和状态所有权，具体推荐、调整、规划流程均进入独立 Workflow。

### P2.2：建立统一 WorkerDispatcher

新增统一 Worker 接口：

```java
public interface AgentWorker {
    AgentTaskType taskType();
    AgentResult execute(AgentTask task);
}
```

新增：

```text
WorkerDispatcher.dispatch(AgentTask)
```

执行链统一为：

```text
AgentTask
   ↓
WorkerDispatcher
   ↓
ToolContract / Task Contract Validation
   ↓
AgentExecutionHarness
   ↓
AgentWorker.execute
   ↓
AgentResult
```

要求：

- Workflow 不再直接调用 `contextWorker.understand(...)`、`responseWorker.recommend(...)` 等具体方法。
- 所有跨 Worker 调用统一通过 Dispatcher。
- Dispatcher 只负责 Worker 定位和执行边界，不承载业务流程。
- Worker 之间禁止直接互调。
- Worker 禁止直接修改 `SessionState`。

**验收：** 新增 Worker 调用必须经过 `WorkerDispatcher.dispatch(AgentTask)`，Harness、Trace、权限和预算均可在同一执行边界生效。

### P2.3：明确 Worker 与 LLM Agent 的边界

当前在线链路中的 Worker 与模型 Agent 不做一一映射：

- `ContextWorker`：负责上下文理解，内部可调用 `IntentAgent`，再由 Java 修正结构化语义结果。
- `RetrievalWorker`：负责候选发现和证据收集，以 Java 检索 Pipeline 为主，不要求调用 LLM。
- `ResponseWorker`：负责受候选白名单约束的响应生成，内部调用推荐或规划响应 Agent。
- `MemoryWorker`：负责受策略控制的长期记忆读写，以确定性服务为主，不允许模型自由写入记忆。
- `PlanningSolver`：确定性规划求解组件，不定义为 LLM Agent。

设计原则：

```text
Worker = 可调度执行单元
Agent = Worker 内的一种 LLM 执行方式
Service / Solver = Worker 或 Workflow 内的确定性业务能力
```

**验收：** 不为“多 Agent”而增加无意义模型调用；所有硬约束均可由 Java 单元测试验证。

### P2.4：DiscoveryWorker 收敛为 RetrievalWorker

当前普通推荐和 Planning 均存在候选检索逻辑，后续统一收敛为 `RetrievalWorker`，避免维护两套检索链路。

统一输入 `RetrievalRequest`，至少包含：

- `userId`
- `sourceMode`
- `SlotBundle`
- `TimeConstraint`
- `timeWindow`
- `excludedIds`
- `topK`
- `weather`
- `retrievalStrategy`

统一输出 `RetrievalResult`，至少包含：

- `candidates`
- `evidenceRefs`
- `retrievalTrace`
- `degradation`

普通推荐：

```text
RecommendWorkflow
   ↓
RetrievalTask
   ↓
RetrievalWorker
   ↓
CandidateSet
```

多时段规划：

```text
PlanningWorkflow
   ↓
拆分时间窗口
   ↓
N × RetrievalTask
   ↓
Dispatcher 并行执行
   ↓
N × RetrievalResult
```

PERSONAL / PUBLIC 并行召回仍由 Retrieval 层支持。

**验收：** 普通推荐与规划使用相同 Retrieval Pipeline，差异只来自 `RetrievalRequest` 参数，不复制搜索和排序逻辑。

### P2.5：Planning 收敛为 Workflow + Retrieval + Solver + Response

`PlanningWorker` 不再同时承担窗口拆分、搜索、排序、合法性校验和响应生成。

目标结构：

```text
PlanningWorkflow
   ↓
Resolve Windows
   ↓
N × RetrievalWorker
   ↓
PlanningSolver
   ↓
List<PlanCandidate>
   ↓
ResponseWorker / PlanResponseAgent
```

`PlanningSolver` 必须使用 Java 负责：

- 时间冲突；
- 活动重复；
- 场次 `OPEN` 状态；
- 剩余容量；
- 活动持续时间；
- 场地移动时间；
- 距离约束；
- 累计预算；
- 用户排除项；
- `SKIP` 可行性。

`PlanResponseAgent` 只允许：

- 从合法 `PlanCandidate` 中选择或排序；
- 根据用户偏好解释方案；
- 生成自然语言。

禁止模型自行创建候选外 Activity、Session 或 Venue。

**验收：** 时间冲突、预算、场次状态、距离和重复检查均可脱离 LLM 独立测试；模型无法输出候选集外实体。

### P2.6：MemoryWorker 接入统一执行体系

为已有 `MEMORY_MAINTENANCE` 任务类型补充实际 Worker。

记忆写入链路：

```text
ContextWorker
   ↓
MemoryMutation
   ↓
Workflow / Supervisor Policy Check
   ↓
MemoryTask
   ↓
WorkerDispatcher
   ↓
MemoryWorker
   ↓
PreferenceMemoryService
```

允许默认写入：

- 用户明确表达的稳定长期偏好；
- 用户明确表达的长期排除项；
- 用户明确维护的黑名单。

默认禁止写入：

- 本轮临时预算；
- 单次日期或时间；
- 临时地址；
- 第三方隐私信息；
- 未经确认的弱推断偏好。

**验收：** LLM 只能提出结构化 MemoryMutation，实际长期记忆写入必须经过确定性策略校验和版本控制。

## P3：建设 Agent Harness

Harness 统一挂载在 `WorkerDispatcher` 执行边界，而不是只包裹模型调用。

新增 `RunContext`，至少记录：

- `runId`
- `traceId`
- `startTime`
- 模型调用次数；
- 工具调用次数；
- Token 使用量；
- 已用执行时间；
- Task / Tool / Evidence 调用历史。

完善以下能力：

- 循环检测从当前 Agent 输入哈希升级为“执行器＋规范化参数＋证据指纹”。
- 设置单轮最大模型调用、工具调用、Token 及执行时间预算。
- 增加调用顺序断言：
  - 未完成必要澄清不得进入 PlanningSolver；
  - 未检索不得生成具体活动；
  - 未验证场次不得输出具体参加时间；
  - 无 Evidence 不得输出对应业务实体。
- 增加工具权限白名单，限制只读 Worker 调用写工具。
- 将权限校验下沉到真实 Tool Invocation Boundary，而不是只校验任务声明。
- 增加工具参数、结果及 LLM 结构化输出 Schema 校验。
- 增加外部工具结果的提示词注入过滤。
- 为天气、地图、向量、Reranker 和模型分别配置超时、重试和熔断。
- 失败时返回 `PARTIAL / DEGRADED` 等可恢复结果，由 Workflow 收口，不直接抛弃整轮任务。

**验收：** Task、Worker、Tool、Model 共用统一治理边界；重复调用能提前收敛；单个外部服务故障不会导致整个推荐链路失败。

## P4：分层上下文与证据管理

- 将上下文拆分为静态合同、任务状态、工作集、证据、摘要和原始历史。
- 建立 `context_evidence` / `EvidenceStore`，持久化完整候选、场次、天气和排序证据。
- Worker 之间优先传递 `EvidenceRef`，而不是持续复制完整 Tool Result。
- 对进入模型的活动结果进行字段投影和大小限制。
- 大结果只注入摘要与 `evidenceRef`，通过事实回查工具分页读取。
- 将当前字符数限制升级为真实上下文 Token 预算和压缩触发阈值。
- 摘要生成后校验活动 ID、场次 ID、预算和时间来源。
- 保护当前轮、未完成工具调用、当前候选及用户最新约束。
- 增加 checkpoint 与恢复机制。
- 将 Prompt Cache 作为独立实验项，确认网关真实支持后再启用。

建议至少记录：

- 平均 Input Token；
- P95 Input Token；
- 压缩前后事实保持率；
- Evidence lookup 命中率。

**验收：** 长对话上下文有明确上限；压缩后九维槽位、时间、选中活动和场次事实不丢失。

## P5：长期记忆体系

- 长期记忆通过 `MemoryWorker` 接入统一 Task / Dispatcher / Harness 体系。
- 区分长期偏好、当前任务约束和行为证据。
- 只有用户明确授权或满足明确记忆策略时才写入长期偏好。
- 过滤临时预算、具体日期、地址电话和第三人称信息。
- 建立偏好事实的来源、类别、置信度、版本和更新时间。
- 支持偏好新增、修改、删除及 CAS 冲突检测。
- 负向偏好和黑名单全量注入，不受 TopK 限制。
- 正向偏好根据当前活动需求进行语义召回。
- 对点击、收藏、拒绝等弱行为信号设置 TTL，不自动升级为硬约束。
- 删除后同步清除向量索引，避免旧候选复活。

**验收：** 临时要求不会污染长期偏好；跨会话可以正确复用、修改和删除稳定偏好；未经策略校验的 MemoryMutation 无法落库。

## P6：混合召回与精排

P6 全部能力统一落在 `RetrievalWorker / RetrievalPipeline`，普通推荐和 Planning 共用。

- 建设活动 BM25 索引。
- 建设活动及场地 Embedding 向量索引。
- PERSONAL 与 PUBLIC 数据分别检索并保留来源。
- 先执行城市、日期、开放状态、预算和排除项等硬过滤。
- 使用加权 RRF 融合词项和向量结果。
- 使用专用 Reranker 精排候选，禁止聊天模型充当默认 Reranker。
- 将天气、距离、偏好和场次可参加性作为排序特征。
- 在精排后执行多样性控制和历史推荐去重。
- 保留每次召回策略、候选数、过滤原因和降级原因。
- 无结果时生成明确的 Relaxation 选项，必须由用户确认后执行。

**验收：** 推荐与规划使用同一 Retrieval Pipeline；推荐结果全部来自候选集；明确日期下的结果存在可参加场次；所有条件放宽均可追踪。

## P7：强化多时段规划

P7 不再维护独立检索实现，而是在 P2.5 的 Planning 架构基础上强化 Solver 能力。

- 将日期和日内时段建模为独立约束。
- 每个规划窗口通过 `RetrievalWorker` 独立召回候选及可参加场次。
- 支持不同窗口并行检索。
- 由 `PlanningSolver` 统一检查：
  - 时间冲突；
  - 活动重复；
  - 场次状态；
  - 活动时长；
  - 场地移动时间；
  - 距离可达性；
  - 预算累计。
- 允许某个窗口明确 `SKIP`，不为填满计划编造活动。
- Solver 输出合法 `PlanCandidate`，Plan Agent 只负责候选方案选择和解释。
- 输出活动、场次、场地及选择理由的完整证据链。
- 天气只做软排序，不能擅自过滤户外活动。

**验收：** 规划结果无确定性时间冲突，不混淆活动时长、有效时段和具体场次；所有硬约束可在无 LLM 环境下独立验证。

## P8：实时事件流与运行恢复

P8 与 P3 `RunContext` 对齐，运行与传输分离：网络断开不代表运行取消。

- 定义 AG-UI 及 CityFlow 领域事件合同。
- 使用 POST 启动运行、SSE 推送增量事件。
- 为每轮生成 `runId`，为事件生成单调递增 `eventSeq`。
- 持久化 `AgentRun`、`AgentRunEvent` 和最新 `RunSnapshot`。
- 支持 `Last-Event-ID` 断线续传，仅回放 `eventSeq > lastEventSeq` 的事件。
- 引入请求幂等键及 request hash：相同请求复用原运行，不同请求返回冲突。
- 支持明确取消，不把网络断开等同于取消。
- 增加会话租约、revision 和 fencing token，替换仅进程内有效的会话锁。
- 页面刷新、SSE 重连或服务恢复不得重新触发已经完成的模型和业务 Tool 调用。

**验收：** 页面刷新或网络断开不会重复调用模型和工具；多实例不会并发覆盖同一会话状态；进程重启后可从持久化 Run/Event 状态恢复。

## P9：可观测性升级

保留现有 Business Trace，同时增加 OpenTelemetry，不用标准 Span 取代业务事件审计。

- Business Trace 继续记录：意图、槽位变化、澄清原因、候选过滤、降级、最终选择及评测关联。
- 接入 OpenTelemetry，建立 HTTP → Supervisor → Workflow → Worker → Tool / Model Span 树。
- 对接 Langfuse 或兼容 OTLP 平台。
- 记录 Token、耗时、错误类型、候选数量、过滤数量及降级策略。
- 记录 Prompt、规则、数据集和代码版本。
- 使用哈希后的用户及会话标识进行关联。
- 导出前删除用户原文、工具参数、异常正文和敏感字段。
- 按 Workflow、Worker、工具、模型和检索阶段统计 P50/P95、错误率和回退率。
- 将 Trace 与最终反馈、人工标注和评测用例关联。

**验收：** 任一 BadCase 均可从业务 Trace 定位决策原因，并可从 OTEL Span 定位具体 Workflow、Worker、Tool 或 Model 的性能和异常阶段。

## P10：评测与发布门禁

- 扩充固定单轮、多轮、多时段、长上下文和异常链路评测集。
- 增加 Retrieval Recall@K、NDCG@K 和无结果误判率。
- 增加活动—场次—场地真实性指标。
- 增加 Workflow / Dispatcher / Harness 的循环、工具失控、熔断、权限和顺序约束测试。
- 增加 PlanningSolver 时间冲突、预算、距离、Session 状态和重复活动专项测试。
- 增加上下文压缩前后的 Token、事实保持率和延迟对照。
- 增加跨会话记忆命中、误召回及删除即时失效测试。
- 增加 SSE 续传、运行恢复、幂等和多实例状态竞争测试。
- 将 BadCase 按 `traceId` 审核后再晋级评测集。
- 扩展 Regression Gate，对关键指标设置硬门禁。
- 所有收益使用冻结数据集、重复实验和原始报告证明。
- 未测量指标保持未知，不提前写入简历。

## 推荐开发顺序

避免同时改动所有层，按以下顺序推进：

```text
1. P2.1  Supervisor → Workflow 拆分
2. P2.2  WorkerDispatcher + AgentTask / AgentResult 统一
3. P2.4  DiscoveryWorker → RetrievalWorker
4. P2.5  PlanningSolver，移除 Planning 内重复检索
5. P3    Harness 挂到 Dispatcher / Tool Invocation Boundary
6. P2.6 + P5  MemoryWorker 与长期记忆体系
7. P6    Vector + RRF + 专用 Reranker
8. P4    EvidenceStore + Token Budget + Checkpoint
9. P8    Durable Run / Event / Resume / Idempotency
10. P9   OpenTelemetry / OTLP
11. P10  专项评测与完整 Regression Gate
```

前三步优先完成，因为 Harness、Planning、Memory、Recovery 都依赖稳定的执行边界；若先继续扩展 P3/P8，后续会因 Dispatcher 和 Workflow 边界变化产生二次重构。

## 最终发布门禁

- 推荐中不存在候选集外活动。
- 明确日期下不存在无效或关闭场次。
- 多时段规划不存在确定性时间冲突。
- Planning 的硬约束不依赖 LLM 判断。
- 长期记忆不存在未授权写入。
- Worker 不直接修改 `SessionState`，Worker 之间不存在绕过 Dispatcher 的直接协作。
- 断线恢复不重复执行模型或业务工具。
- 核心准确率不低于基线。
- 普通推荐与复杂规划的 P95 处于预设范围。
- 全量单元、集成、数据库迁移和前端恢复测试通过。

## 当前实施状态（2026-09-22）

- 已完成：领域意图/配置/Prompt 主命名统一，九维槽位与活动—场次—场地模型，多轮状态、Trace 和回归门禁基础。
- 已完成：`CityAgentSupervisor + ContextWorker + DiscoveryWorker + PlanningWorker + ResponseWorker` 第一版；Worker 不直接写 `SessionState`，推荐/规划响应已受候选空间约束。
- 已完成：类型化 `AgentTask`、`AgentResult`、`EvidenceRef`、工具读写合同和 Worker 派发 Trace；活动发现与多时段规划结果已携带活动、场次、场地和天气证据指纹。
- 待收敛：Supervisor 仍直接承载较多业务流程，尚未拆分 `RecommendWorkflow / AdjustWorkflow / PlanningWorkflow`。
- 待收敛：`AgentTask / AgentResult` 尚未成为所有 Worker 的唯一执行协议，当前仍存在具体 Worker 方法直接调用；尚未建立统一 `WorkerDispatcher`。
- 待收敛：`DiscoveryWorker` 与 Planning 内候选发现存在职责重叠，尚未统一为 `RetrievalWorker / RetrievalPipeline`。
- 待收敛：Planning 已具备多窗口检索、Session 校验和 Evidence 收集，但尚未完整拆分为 `Retrieval + PlanningSolver + Response`，旅行时间、累计预算等 Solver 约束仍需补齐。
- 部分完成：Harness 已具备调用预算、重复调用检测、模型熔断；尚缺 Tool Budget、调用顺序断言、真实 Tool Boundary 权限校验、Schema 和注入过滤。
- 部分完成：上下文具备轮数、单消息及总字符边界压缩；尚缺 EvidenceStore、Token Budget、checkpoint 和事实保持校验。
- 部分完成：显式长期偏好 CRUD、版本和软删除；`MEMORY_MAINTENANCE` 任务类型已存在，但尚未形成统一 `MemoryWorker` 执行链；行为记忆、TTL、语义偏好召回及向量删除同步仍缺失。
- 部分完成：SQL 硬过滤、候选集 BM25、时间/天气/偏好重排和多样性；尚缺向量索引、RRF 和专用 Reranker。
- 部分完成：六类 SSE 事件；尚缺 run/event 持久化、续传、幂等、取消和分布式租约。
- 待实施：OpenTelemetry/OTLP、专项 Retrieval/Planning/Memory/Recovery 评测以及完整发布门禁。
