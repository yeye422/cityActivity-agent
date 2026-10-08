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

禁止将数据库自增 `activityId` 写入人工 relevance，因为不同环境重建 seed 后 ID 可能变化。

## 2. 运行当前 Pipeline 基线

启动普通应用即可，不需要 `react-eval` profile：

```bash
mvn spring-boot:run
```

可以直接调用接口：

```http
POST /api/v1/city/evaluations/retrieval
X-User-Id: 999999
Content-Type: application/json

{
  "k": 5
}
```

也可以在 PowerShell 中使用仓库脚本：

```powershell
.\scripts\run-retrieval-baseline.ps1 -K 5
```

需要保存完整基线 JSON 时显式指定输出路径：

```powershell
.\scripts\run-retrieval-baseline.ps1 `
  -K 5 `
  -OutputPath .\artifacts\retrieval-current-k5.json
```

脚本默认只打印报告，不会自动写文件，也不会改变 Retrieval 策略。

`k` 默认 5，服务端限制在 1~20。

## 3. 返回结果

返回：

```text
evalSetVersion = retrieval-v1
evalSetHash
gitCommit
strategy = CURRENT_PIPELINE
k
summary
cases[]
```

其中：

```text
evalSetHash
```

是实际参与评测的 `cases` 内容指纹。即使版本号仍叫 `retrieval-v1`，只要人工 relevance、query 或 slots 发生变化，指纹就会变化。

```text
gitCommit
```

用于定位本次运行对应的代码版本。CI 的 pull request merge ref 与本地分支 commit 可能不同，因此正式基线应记录真实运行环境返回的该字段，而不是手工猜测提交号。

一个可复现的 Retrieval baseline 至少由以下四元组标识：

```text
(evalSetHash, gitCommit, strategy, k)
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

## 5. 基线可比性要求

两次 Retrieval 结果只有同时满足以下条件时才能直接比较：

```text
evalSetVersion 相同
evalSetHash 相同
k 相同
```

同时必须记录各自的：

```text
gitCommit
strategy
```

例如：

```text
retrieval-v1
hash = abc...
k = 5
CURRENT_PIPELINE @ commit A
vs
BM25_VECTOR_RRF @ commit B
```

如果 `evalSetHash` 不同，说明评测集内容已经发生变化，应重新建立所有待比较策略的基线，而不是直接计算前后分数提升。

## 6. Vector / RRF 实验规则

当前默认策略保持不变。

未来实验必须使用完全相同的：

```text
retrieval-v1 cases
evalSetHash
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
