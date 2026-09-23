# ReAct 专项回归 Runbook

本文档用于在真实数据库 + 真实模型配置下验证 CityFlow 的 RecommendationAgent / PlanningAgent 唯一在线主链。

GitHub CI 的 `mvn clean verify` 只验证编译和自动化测试，不代表真实模型调用已经通过专项门禁。

## 1. 前置条件

数据库至少执行：

```bash
mysql < database_init_final.sql
mysql city_db < src/main/resources/db/evaluation_loop_migration.sql
mysql city_db < src/main/resources/db/activity_venue_session_migration.sql
mysql city_db < src/main/resources/db/agent_memory_migration.sql
mysql city_db < src/main/resources/db/agent_ui_event_migration.sql
mysql city_db < src/main/resources/db/chat_request_idempotency_migration.sql
mysql city_db < src/main/resources/db/city_seed.sql
```

同时准备正常运行所需的模型和外部服务配置，然后直接启动：

```bash
mvn spring-boot:run
```

RecommendationAgent 与 PlanningAgent 已是唯一在线业务入口，不再存在 feature flag、legacy fallback 或 react-eval 专用 profile。

## 2. 执行 react-v1

```http
POST /api/v1/city/evaluations/regression
X-User-Id: 999999
Content-Type: application/json

{
  "suite": "react",
  "includeLlmJudge": false,
  "limit": 10
}
```

Windows 可执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-react-regression.ps1
```

## 3. 发布门禁

首次运行也必须通过 `ReactReleaseGate`：

```text
reactRouteCoverage >= 0.60
recommendationReactSuccessRate >= 0.80
planningReactSuccessRate >= 0.70
reactFallbackRate <= 0.20
evidenceViolationRate == 0
```

辅助指标包括 Tool Call、Re-Retrieval、Travel Tool、Plan Validation/Repair，用于定位行为但不单独判失败。

## 4. Baseline 与后续回归

只有 `passed=true` 的 Run 才允许提升：

```http
POST /api/v1/city/evaluations/regression/baseline
X-User-Id: 999999
Content-Type: application/json

{
  "runId": "eval_xxx",
  "baselineName": "react-v1-initial"
}
```

也可以：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-react-regression.ps1 -PromoteBaseline
```

Baseline 建立后，相同 `react-v1` 除绝对门禁外还会经过 `RegressionGate`。

## 5. BadCase 回流

人工确认失败 Trace 后，通过 `/api/v1/city/evaluations/cases/promote` 且 `suite=react` 写入 react-v1 数据库评测集。

## 6. Retrieval relevance

人工检索标注位于：

```text
src/main/resources/evaluation/retrieval-relevance-v1.json
```

稳定键格式：

```text
SOURCE|CITY|ACTIVITY_NAME
```

当前方案与未来 Vector / RRF 必须在相同 evalSetHash 与 K 下比较 Recall@K、NDCG@K、No-result false-positive。
