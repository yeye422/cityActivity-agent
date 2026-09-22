# ReAct 专项回归 Runbook

本文档用于在真实数据库 + 真实模型配置下验证 CityFlow 的 RecommendationAgent / PlanningAgent ReAct 主链。

GitHub CI 的 `mvn clean verify` 只验证编译和自动化测试，不代表真实模型调用已经通过本专项门禁。

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

同时准备正常运行所需的模型和外部服务配置。

## 2. 使用 react-eval profile 启动

```bash
SPRING_PROFILES_ACTIVE=react-eval mvn spring-boot:run
```

Windows PowerShell：

```powershell
$env:SPRING_PROFILES_ACTIVE = "react-eval"
mvn spring-boot:run
```

`application-react-eval.yml` 会同时开启：

```text
city.agent.recommendation-react.enabled=true
city.agent.planning-react.enabled=true
```

如果任一开关未开启，`suite=react` 会在执行用例和调用模型前直接失败。

## 3. 执行 react-v1

直接调用接口：

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

Windows 可使用仓库脚本：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-react-regression.ps1
```

自定义地址：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-react-regression.ps1 `
  -BaseUrl "http://localhost:8080" `
  -UserId 999999 `
  -Limit 10
```

脚本会打印本次 `runId`、平均分和关键 ReAct 指标。

## 4. 首次运行绝对门禁

`react-v1` 即使尚无 Baseline，也必须通过 `ReactReleaseGate`：

```text
reactRouteCoverage >= 0.60
recommendationReactSuccessRate >= 0.80
planningReactSuccessRate >= 0.70
reactFallbackRate <= 0.20
evidenceViolationRate == 0
```

辅助指标：

```text
reactToolCallCount
retrievalToolCallCount
reRetrievalRate
travelToolCallCount
planValidationCallCount
planValidationFailureRate
planRepairSuccessRate
```

这些辅助指标用于分析链路行为，当前不会单独触发发布失败。

## 5. 提升 Baseline

只有返回：

```text
passed=true
```

的 Run 才允许提升。

接口：

```http
POST /api/v1/city/evaluations/regression/baseline
X-User-Id: 999999
Content-Type: application/json

{
  "runId": "eval_xxx",
  "baselineName": "react-v1-initial"
}
```

也可以执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-react-regression.ps1 -PromoteBaseline
```

脚本只会在本次 `passed=true` 时发起 Baseline 提升。

## 6. 第二次回归

Baseline 建立后再次运行相同 `react-v1` case。

此时除绝对 `ReactReleaseGate` 外，还会经过 `RegressionGate`，检查：

```text
总体评分
reactRouteCoverage
recommendationReactSuccessRate
planningReactSuccessRate
reactFallbackRate
evidenceViolationRate
```

是否相对 Baseline 发生明显回退。

## 7. React BadCase 回流

人工确认某条失败 Trace 值得长期保留时：

```http
POST /api/v1/city/evaluations/cases/promote
X-User-Id: 999999
Content-Type: application/json

{
  "traceId": "trace_xxx",
  "suite": "react",
  "caseDefinition": {
    "id": "react_badcase_xxx",
    "message": "周末在西安和朋友找个互动性强的活动",
    "expectedIntent": "ACTIVITY_RECOMMENDATION",
    "expectedSlots": {
      "city": ["西安"],
      "companion": ["朋友"]
    }
  }
}
```

该用例会写入 `react-v1` 对应的数据库评测集；`suite` 为空时仍写入默认 `v2`。

## 8. Retrieval relevance

人工检索标注位于：

```text
src/main/resources/evaluation/retrieval-relevance-v1.json
```

禁止把数据库自增 `activityId` 写入人工 relevance。稳定键格式为：

```text
SOURCE|CITY|ACTIVITY_NAME
```

例如：

```text
PUBLIC|西安|曲江艺术中心周末特展
```

运行时通过 `RetrievalEvaluationKey` 从 `ActivityItem` 生成相同 key，再由 `StableRetrievalQualityEvaluator` 计算：

```text
Recall@K
NDCG@K
No-result false-positive
```

只有在相同 `retrieval-v1` relevance case 上验证 Vector / RRF 相比当前方案有稳定收益后，才将其接入默认 RetrievalPipeline。

## 9. 删除 legacy 的最低条件

只有满足以下条件才进入旧链物理删除：

```text
mvn clean verify 通过
react-v1 通过 ReactReleaseGate
存在显式提升的 react-v1 Baseline
后续 react-v1 通过 RegressionGate
evidenceViolationRate = 0
fallback 率满足门禁
```

在此之前 `RecommendResponseAgentService / PlanResponseAgentService / RetrievalWorker / PlanningWorker / ResponseWorker` 继续只作为迁移期 fallback 保留。
