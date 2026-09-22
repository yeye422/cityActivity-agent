# Retrieval Baseline Evaluation Runbook

本文档用于评估 CityFlow 当前 `RetrievalPipeline` 的离线检索质量，并为后续 Vector / RRF 实验提供固定比较基线。

该评测不经过 IntentAgent、RecommendationAgent 或 LLM，只直接执行：

```text
retrieval-relevance-v1
↓
ActivitySearchRequest（硬条件）
↓
RetrievalPipeline
↓
Stable Activity Key
↓
Recall@K / NDCG@K / No-result False Positive
```

## 1. 数据准备

至少执行：

```bash
mysql < database_init_final.sql
mysql city_db < src/main/resources/db/evaluation_loop_migration.sql
mysql city_db < src/main/resources/db/city_seed.sql
```

如需完整场次/场地数据，再执行对应 migration/seed。

人工 relevance 位于：

```text
src/main/resources/evaluation/retrieval-relevance-v1.json
```

当前稳定实体键：

```text
SOURCE|CITY|ACTIVITY_NAME
```

例如：

```text
PUBLIC|西安|曲江艺术中心周末特展
```

禁止将数据库自增 `activityId` 写入人工 relevance，因为不同环境重建数据后 ID 可能变化。

## 2. 运行当前 Pipeline 基线

启动普通应用即可，不需要 `react-eval` profile：

```bash
mvn spring-boot:run
```

执行：

```http
POST /api/v1/city/evaluations/retrieval
X-User-Id: 999999
Content-Type: application/json

{
  "k": 5
}
```

`k` 默认 5，服务端限制在 1~20。

## 3. 返回结果

返回：

```text
evalSetVersion = retrieval-v1
strategy = CURRENT_PIPELINE
k
summary
cases[]
```

`summary` 包含：

```text
totalCases
recallAtK
ndcgAtK
noResultFalsePositiveRate
```

每个 case 同时输出：

```text
rawCandidateCount
rankedCandidateCount
topKActivityKeys
recallAtK
ndcgAtK
noResultFalsePositive
```

这可以区分：

```text
硬过滤阶段没有召回
召回到了但排序靠后
错误召回本应无结果的场景
```

## 4. Hard Filter 与 Soft Query 分离

`retrieval-relevance-v1.json` 中：

```text
slots
```

只放确定性硬过滤条件，例如 `city`。

```text
query
```

表达软检索目标，例如：

```text
互动性
参与感
文艺
放松
新鲜感
```

避免把主观目标提前变成 SQL Hard Filter，否则无法公平评估 BM25 / Vector / Hybrid 的检索能力。

## 5. Vector / RRF 实验规则

当前默认策略保持不变。

未来实验必须使用完全相同的：

```text
retrieval-v1 cases
stable activity key
K
Recall@K
NDCG@K
No-result FP
```

分别运行：

```text
CURRENT_PIPELINE
VECTOR_ONLY
BM25_VECTOR_RRF
```

只有 Hybrid 在稳定 relevance 集上表现出明确收益，且 no-result false positive 没有明显恶化，才进入默认 `RetrievalPipeline`。

不要因为架构图需要“混合召回”而提前把 Vector / RRF 接入线上默认链路。
