# City Activity Agent

城市活动智能推荐 Agent。

## 项目介绍

City Activity Agent 是一个基于 AI 的城市活动推荐系统，根据用户偏好、时间、场景和预算生成活动建议。

当前普通活动条件采用九维槽位模型：

```text
city / location / experienceGoal / companion / budget /
activityType / style / duration / feature
```

其中 `duration` 只表示活动自身时长筛选标签；`feature` 表示室内、户外、近地铁、少排队、交通方便等客观属性。活动的明确预计耗时使用独立 `duration_minutes`，具体日期/时间与具体可参加场次使用 `activity_session`。

## 技术栈

- Java
- Spring Boot
- MyBatis
- MySQL
- LLM Agent

## 核心功能

- 城市活动推荐
- 活动搜索
- 活动排序
- 用户偏好匹配
- 多时段活动规划
- 具体场次约束规划
- 薄 Supervisor + Workflow/AgentRunService 分层编排
- 单轮调用预算、重复调用检测和 Agent 熔断
- 边界上下文压缩、MemoryMutationProposal + MemoryPolicy 长期偏好写入
- SQL 硬过滤、BM25 文本相关性、时间/天气/偏好重排与多样性控制
- Trace、回归评估和按会话推送的 SSE 执行事件

## 数据库初始化

首次部署建议按下面顺序执行：

```bash
mysql < database_init_final.sql
mysql city_db < src/main/resources/db/evaluation_loop_migration.sql
mysql city_db < src/main/resources/db/activity_venue_session_migration.sql
mysql city_db < src/main/resources/db/agent_memory_migration.sql
mysql city_db < src/main/resources/db/agent_ui_event_migration.sql
mysql city_db < src/main/resources/db/chat_request_idempotency_migration.sql
mysql city_db < src/main/resources/db/city_seed.sql
# 可选：补充更多带具体场次的演示活动
mysql city_db < src/main/resources/db/activity_catalog_seed.sql
```

`database_init_final.sql` 已直接使用九维槽位列和 `duration_minutes`。
`activity_venue_session_migration.sql` 会创建 `venue` 和 `activity_session`；只有执行该迁移并存在目标日期的 OPEN 场次时，具体 `sessionId/startAt/endAt` 才会作为 PlanAgent 的硬规划依据。
`agent_memory_migration.sql` 会创建带版本号和软删除标记的长期偏好表。偏好只作为排序软信号，本轮明确条件优先。
`agent_ui_event_migration.sql` 会创建可恢复 SSE 事件日志；未执行该迁移时聊天主链仍可运行，但 SSE 重连只能使用当前进程内的热缓存，应用重启后无法补发旧事件。
`chat_request_idempotency_migration.sql` 会创建 `/chat` 请求幂等表；只有客户端传 `Idempotency-Key` 时该能力才参与请求处理。
需要回滚长期记忆能力时，先备份偏好数据，再执行 `src/main/resources/db/rollback/agent_memory_rollback.sql`。

完整的 AgentScope ReAct 改造计划见 `docs/architecture/cityflow-agentscope-refactor-plan.md`，旧版完整重构计划见 `docs/architecture/cityflow-full-refactor-plan.md`，冻结基线见 `docs/baseline/p0-baseline-2026-09-22.md`。

## Agent 执行与实时事件

AgentScope ReAct Agent 负责 Reason -> Tool -> Observe 循环；CityFlow Guard 负责 Tool 白名单、调用预算、重复调用和事实校验。RecommendationAgent / PlanningAgent 已是唯一在线决策主链；IntentAgent 的模型调用继续通过 AgentExecutionHarness 承担请求级调用预算、重复调用检测和熔断。

内置前端会在发送聊天请求前建立 SSE，使用 `Idempotency-Key` 防止网络重试/刷新导致重复 Agent Run，并通过 `Last-Event-ID` 恢复运行事件。最终消息仍以 HTTP `ChatResponse` 为唯一渲染来源，避免 SSE 与同步响应重复显示。完整前端运行协议见 `docs/frontend/agentscope-chat-runtime.md`。

前端可先订阅会话事件，再发起聊天请求：

```text
GET  /api/v1/city/events/{sessionId}       # text/event-stream
POST /api/v1/city/chat
```

事件协议固定为 `RUN_STARTED / STEP_STARTED / STEP_COMPLETED / MESSAGE_COMPLETE / ERROR / RUN_FINISHED` 六类，并按 `userId + sessionId` 隔离。每个 SSE 事件 ID 使用 `traceId:eventSeq`。

发生刷新或网络断线后，客户端重新订阅时应携带标准 SSE 请求头：

```text
Last-Event-ID: trace_xxx:12
```

服务端优先从 `agent_ui_event` 持久化日志补发该游标之后的事件，再继续推送 live event；数据库日志暂不可用时退化到当前进程最近 256 条事件的热缓存。事件重放只读取日志，不会重新执行 LLM、Retrieval Tool 或 PlanningSolver，SSE 断开也不会取消正在执行的 Run。

为了避免刷新/网络重试重复触发同一轮 Agent，`POST /chat` 支持可选请求头：

```text
Idempotency-Key: <client-generated-unique-key>
```

同一用户下，相同 `Idempotency-Key` 必须对应同一请求内容。第一次请求会原子 claim 并执行；成功响应持久化后，后续相同请求直接返回原 `ChatResponse`，不会再次执行 Agent/Tool。若同一 key 仍为 `PENDING`，服务端采用 fail-closed 策略阻断自动重跑；只有业务调用明确失败时才释放 claim，避免业务已提交但响应快照异常时产生重复状态写入。

长期偏好支持用户显式写入/删除，以及 IntentAgent 只在用户明确表达长期偏好时提出 `MemoryMutationProposal`；自动写入仍必须经过 Java `MemoryPolicy`，预算、城市、地点、同行人、活动时长等一次性上下文禁止自动持久化：

```text
GET    /api/v1/city/preferences
POST   /api/v1/city/preferences
DELETE /api/v1/city/preferences/{id}?version={version}
```

已有旧数据库（仍使用 `mood / scene`，并把“室内/交通方便”等混在 `duration` 或 `location`）时，先执行：

```bash
mysql city_db < src/main/resources/db/activity_slot_model_v3.sql
mysql city_db < src/main/resources/db/activity_venue_session_migration.sql
mysql city_db < src/main/resources/db/agent_ui_event_migration.sql
mysql city_db < src/main/resources/db/chat_request_idempotency_migration.sql
```

`activity_slot_model_v3.sql` 会完成：

```text
mood  -> experience_goal
scene -> companion
新增 feature
location 中的“近地铁” -> feature
duration 中的“室内/户外/近距离/少排队/交通方便” -> feature
半天/全天继续保留为 duration 时长标签
```

迁移完成后，接口、会话状态与活动数据均只接受 `experienceGoal/companion`，不再读取旧的 `mood/scene` 字段。

如果数据库已经存在但报 `Table 'city_db.activity_item' doesn't exist`，重新执行初始化脚本；评估迁移会在活动表缺失时自动补建相关表，但不会自动插入完整演示活动数据。

## 固定回归评估

通用回归：

```text
POST /api/v1/city/evaluations/regression
Body: {"suite":"default","includeLlmJudge":false,"limit":35}
```

`suite=default` 读取 `src/main/resources/evaluation/city-dialogue-eval-set.json`，当前版本为 `v2`，用于通用意图、槽位、澄清、多轮操作等回归。

ReAct 专项回归直接执行：

```text
POST /api/v1/city/evaluations/regression
Body: {"suite":"react","includeLlmJudge":false,"limit":10}
```

`suite=react` 读取独立的 `src/main/resources/evaluation/city-react-eval-set.json`，当前版本为 `react-v1`，重点覆盖软目标权衡、Re-Retrieval、多时段规划、Solver Validate/Repair 和多轮上下文。ReAct 发布判断分三层：

```text
1. ReactReleaseGate（首次运行也生效）
   reactRouteCoverage >= 0.60
   recommendationReactSuccessRate >= 0.80
   planningReactSuccessRate >= 0.70
   reactDegradationRate <= 0.20
   evidenceViolationRate == 0

2. RegressionGate（已有 Baseline 后）
   检测总分、route coverage、success、goal coverage、plan valid、
   degradation、tool error、candidate/session/evidence violation 的相对回退
```

`react-v1` 使用独立 `version + evalSetHash` 查找 Baseline，不与默认 `v2` 混用。首次专项评测即使没有 Baseline，也必须先通过 `ReactReleaseGate` 才能标记为 passed；未通过的 Run 不能提升为 Baseline。

RecommendationAgent 和 PlanningAgent 已是唯一在线决策主链；固定回归会写入现有 Evaluation/Regression Gate，并统计 ReAct route coverage、success/degradation、Tool Call/Error、Re-Retrieval、UserGoal Coverage、Plan Validation/Repair/Valid、候选/Session/Evidence 违规以及 P50/P95 延迟等运行指标。

回归执行会自动运行用例、标注本次 Trace、生成意图/槽位/澄清/缺失槽位/操作/时间/多轮一致性等报告，并将评估运行保存到 `evaluation_run`。Baseline 取同一评测集版本和指纹下已显式提升的基线运行。

线上高价值失败样本可在人工标注后通过 `POST /api/v1/city/evaluations/cases/promote` 晋级为数据库默认评测用例：请求体传入 `traceId` 和完整 `caseDefinition`（至少包含 `id`、`message` 或 `messages` 及 expected 标签）。下一次 default 回归会自动合并固定 JSON 评测集与 `evaluation_case` 表中的晋级样本。

## 规划时间模型

用户要求半天/一天规划时，Java 会先把可用时间拆成较细候选窗口，例如：

```text
08:00-10:00
10:00-12:00
12:00-14:00
14:00-16:00
16:00-18:00
18:00-20:00
20:00-23:00
```

这些窗口只是候选召回锚点，不是活动时长。PlanningAgent 会同时使用真实场次、活动预计耗时和服务器硬约束；显式 `PlanNotebook` 记录 Discovery / Proposal / Validate / Repair / Validated 生命周期，最终仍通过 Java PlanningSolver 做确定性复核。

优先级是：具体场次 > 明确耗时 > 标签估算 > 类型软估算。Java 最后验证 activityId、sessionId、重复活动、预算、交通证据和确定性时间冲突。

## 本地运行

```bash
mvn spring-boot:run
```

## 配置

主要配置文件：

```text
src/main/resources/application.yml
```

## 项目结构

```text
src/
├── main/java        # 后端代码
├── main/resources   # 配置、Prompt、Mapper、数据库迁移

database_init_final.sql # 全新数据库初始化脚本
```


### Frontend browser E2E

内置前端浏览器回归使用 Playwright Chromium。测试完全 mock City API，不消耗模型额度，也不依赖数据库：

```bash
npm install
npx playwright install chromium
npm run test:e2e
```

覆盖正常聊天单次渲染、SSE `Last-Event-ID` 重连、刷新恢复时复用原 `Idempotency-Key`、HTTP 失败后同 key 安全重试、Clarify、Planning Validate/Repair、Relaxation 以及移动端运行卡布局。普通 CI 的 `Frontend E2E` job 会独立执行这些浏览器测试。
