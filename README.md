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
- Orchestrator + Worker 分层编排与能力边界
- 单轮调用预算、重复调用检测和 Agent 熔断
- 边界上下文压缩与跨会话偏好记忆
- SQL 硬过滤、BM25 文本相关性、时间/天气/偏好重排与多样性控制
- Trace、回归评估和按会话推送的 SSE 执行事件

## 数据库初始化

首次部署建议按下面顺序执行：

```bash
mysql < database_init_final.sql
mysql city_db < src/main/resources/db/evaluation_loop_migration.sql
mysql city_db < src/main/resources/db/activity_venue_session_migration.sql
mysql city_db < src/main/resources/db/agent_memory_migration.sql
mysql city_db < src/main/resources/db/city_seed.sql
# 可选：补充更多带具体场次的演示活动
mysql city_db < src/main/resources/db/activity_catalog_seed.sql
```

`database_init_final.sql` 已直接使用九维槽位列和 `duration_minutes`。
`activity_venue_session_migration.sql` 会创建 `venue` 和 `activity_session`；只有执行该迁移并存在目标日期的 OPEN 场次时，具体 `sessionId/startAt/endAt` 才会作为 PlanAgent 的硬规划依据。
`agent_memory_migration.sql` 会创建带版本号和软删除标记的长期偏好表。偏好只作为排序软信号，本轮明确条件优先。
需要回滚该能力时，先备份偏好数据，再执行 `src/main/resources/db/rollback/agent_memory_rollback.sql`。

完整的分阶段改造清单见 `docs/architecture/cityflow-full-refactor-plan.md`，冻结基线见 `docs/baseline/p0-baseline-2026-09-22.md`。

## Agent 执行与实时事件

所有模型调用统一经过 Harness：限制单轮调用次数、阻断重复输入循环，并按 Agent 维护 CLOSED / OPEN / HALF_OPEN 熔断状态。失败会进入既有 Java Parser 或模板降级路径，Trace 同时记录故障位置。

前端可先订阅会话事件，再发起聊天请求：

```text
GET  /api/v1/city/events/{sessionId}       # text/event-stream
POST /api/v1/city/chat
```

事件协议固定为 `RUN_STARTED / STEP_STARTED / STEP_COMPLETED / MESSAGE_COMPLETE / ERROR / RUN_FINISHED` 六类，并按 `userId + sessionId` 隔离。

长期偏好采用显式写入和删除，避免模型自行篡改用户画像：

```text
GET    /api/v1/city/preferences
POST   /api/v1/city/preferences
DELETE /api/v1/city/preferences/{id}?version={version}
```

已有旧数据库（仍使用 `mood / scene`，并把“室内/交通方便”等混在 `duration` 或 `location`）时，先执行：

```bash
mysql city_db < src/main/resources/db/activity_slot_model_v3.sql
mysql city_db < src/main/resources/db/activity_venue_session_migration.sql
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

固定回归评估接口：

```text
POST /api/v1/city/evaluations/regression
Body: {"includeLlmJudge":false,"limit":35}
```

该接口读取 `src/main/resources/evaluation/city-dialogue-eval-set.json`（当前 35 条，包含 `messages[]` 多轮用例），自动执行用例、标注本次 Trace、生成意图/槽位/澄清/缺失槽位/操作/时间/多轮一致性等评估报告，并将评估运行保存到 `evaluation_run`。Baseline 取同一评测集版本下最近一次通过的运行，避免失败运行污染后续基线；回归门禁会检查总分以及关键指标（含操作、时间、多轮一致性）是否回退。

线上高价值失败样本可在人工标注后通过 `POST /api/v1/city/evaluations/cases/promote` 晋级为数据库评测用例：请求体传入 `traceId` 和完整 `caseDefinition`（至少包含 `id`、`message` 或 `messages` 及 expected 标签）。下一次回归会自动合并固定 JSON 评测集与 `evaluation_case` 表中的晋级样本。

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

这些窗口只是候选召回锚点，不是活动时长。PlanAgent 会同时看到：

```text
具体场次 startAt/endAt（如果有）
明确 durationMinutes（如果有）
duration 标签推导的预计耗时（无明确分钟数时）
活动级 validStartTime/validEndTime 可安排窗口
九维槽位与 matchScore
```

优先级是：具体场次 > 明确耗时 > 标签估算 > 类型软估算。Java 最后验证 activityId、sessionId、重复活动和确定性时间冲突。

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
