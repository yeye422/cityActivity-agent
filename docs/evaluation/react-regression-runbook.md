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
reactDegradationRate <= 0.20
evidenceViolationRate == 0
```

同时重点检查：

```text
toolErrorRate
userGoalCoverage
candidateOutOfSetRate
planValidRate
planRepairSuccessRate
planValidationTimeConflictRate
planValidationBudgetViolationRate
sessionHallucinationRate
latencyP50Ms
latencyP95Ms
```

其中 Goal Coverage / Plan Valid 属于越高越好的回归指标；Tool Error、Candidate/Session/Evidence 违规属于越低越好的回归指标。Tool Call、Re-Retrieval、Travel Tool 和 Validation Failure 等行为指标用于定位链路。

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


## 7. 当前架构验收状态

仓库级 `mvn clean verify` 用于验证编译、单测和确定性边界；真实发布验收必须由有 MySQL、模型和地图/天气配置的运行环境完成。

推荐顺序：

```text
react-v1 首跑并通过 ReactReleaseGate
↓
Promote react-v1 Baseline
↓
再次执行 react-v1 并通过 RegressionGate
↓
执行 retrieval-v1 CURRENT_PIPELINE baseline
↓
固定结果后再决定是否启动 Vector / RRF 实验
```

legacy 架构 fallback 已删除；PERSONAL -> PUBLIC 仍是业务数据源降级，不计作 legacy fallback。


## 8. 一键发布验收

在已经启动且具备真实数据库、模型、地图和天气配置的服务环境中，可以用一个脚本串行执行完整门禁：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-release-verification.ps1
```

该脚本严格按以下顺序执行，任一步失败都会立即退出：

```text
react-v1 首跑
↓
首跑 passed=true 后 Promote Baseline
↓
react-v1 第二次回归
↓
retrieval-v1 CURRENT_PIPELINE baseline
```


## 8. GitHub Actions 手动门禁

`.github/workflows/release-gates.yml` 只支持 `workflow_dispatch`，不会在普通 push / pull_request 自动调用真实模型。

仓库需要配置：

```text
DASHSCOPE_API_KEY
AMAP_WEB_SERVICE_KEY
QWEATHER_API_HOST
QWEATHER_API_KEY
```

工作流使用临时 MySQL 初始化完整测试数据，随后执行 `scripts/run-release-verification.ps1`：

```text
react-v1 首跑 -> ReactReleaseGate
-> promote 临时 Baseline
-> react-v1 二跑 -> RegressionGate
-> retrieval-v1 CURRENT_PIPELINE baseline
```

运行日志和 Retrieval JSON 会上传为 `release-gate-reports` artifact。临时数据库中的 Baseline 只用于验证完整门禁流程；正式环境仍需在持久化 Evaluation DB 中建立正式 Baseline。
