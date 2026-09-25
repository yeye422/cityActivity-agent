# Pinecone 双通道活动召回

当前检索结构：

1. MySQL 先执行城市、时间、预算、活动类型等业务硬过滤；
2. 对合法候选 ID，在同一个 Pinecone document index 中执行两次独立搜索：
   - `text` score：Pinecone FTS/BM25 词面通道；
   - `dense_vector` score：DashScope `text-embedding-v4` 语义通道；
3. Java 只对两个 Pinecone 排名执行 RRF；
4. 再叠加时间、天气、长期偏好和 Diversity。

Java 不再实现 BM25，也不再在 MySQL 中保存活动 embedding。

## 一次性创建索引

需要 Python 3.10+：

```bash
PINECONE_API_KEY=... \
PINECONE_INDEX_NAME=city-activities \
DASHSCOPE_EMBEDDING_DIMENSIONS=256 \
python scripts/create-pinecone-activity-index.py
```

脚本会输出 `PINECONE_INDEX_HOST`。

索引字段：

- `body`：Pinecone full-text-search；中文使用 2~4 字符 n-gram；
- `embedding`：256 维 cosine dense vector；
- `activity_id`、`source_type`、`owner_user_id`：document metadata。

## 应用配置

```text
PINECONE_ENABLED=true
PINECONE_API_KEY=...
PINECONE_INDEX_HOST=...
PINECONE_NAMESPACE=activities
```

应用启动时会把当前有效活动同步到 Pinecone；个人活动 CRUD 在数据库事务提交后同步更新 Pinecone。Pinecone 不可用时系统退化为 MySQL 硬过滤 + Java 业务重排，不恢复 Java BM25。
