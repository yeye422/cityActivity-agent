# City Activity Agent

城市活动智能推荐 Agent。

## 项目介绍

City Activity Agent 是一个基于 AI 的城市活动推荐系统，根据用户偏好、时间、场景和预算生成活动建议。

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
- Agent 编排

## 数据库初始化

首次部署执行：

```bash
mysql < database_init_final.sql
# 继续执行评估闭环、会话、Trace 和反馈表迁移
mysql city_db < src/main/resources/db/evaluation_loop_migration.sql
```

如果数据库已经存在但报 `Table 'city_db.activity_item' doesn't exist`，重新执行以上两条命令；评估迁移现在会在活动表缺失时自动补建表，但不会自动插入示例活动数据。

如果历史数据仍在 `diet_db.meal_item`，先执行活动表/评估迁移，再执行 `src/main/resources/db/diet_to_city_copy.sql`。该脚本采用跨库复制，不会删除 `diet_db` 数据；由于旧表没有城市和区域字段，迁移时暂以“西安/近地铁”填充，需按实际数据修正。

固定回归评估接口：

```text
POST /api/v1/city/evaluations/regression
Body: {"includeLlmJudge":false,"limit":35}
```

该接口读取 `src/main/resources/evaluation/city-dialogue-eval-set.json`（当前 35 条，包含 `messages[]` 多轮用例），自动执行用例、标注本次 Trace、生成意图/槽位/澄清/缺失槽位/操作/时间/多轮一致性等评估报告，并将评估运行保存到 `evaluation_run`。Baseline 取同一评测集版本下最近一次通过的运行，避免失败运行污染后续基线；回归门禁会检查总分以及关键指标（含操作、时间、多轮一致性）是否回退。

线上高价值失败样本可在人工标注后通过 `POST /api/v1/city/evaluations/cases/promote` 晋级为数据库评测用例：请求体传入 `traceId` 和完整 `caseDefinition`（至少包含 `id`、`message` 或 `messages` 及 expected 标签）。下一次回归会自动合并固定 JSON 评测集与 `evaluation_case` 表中的晋级样本。

## 本地运行

```bash
mvn spring-boot:run
```

## 配置

主要配置文件：

```
src/main/resources/application.yml
```

## 项目结构

```
src/
├── main/java        # 后端代码
├── main/resources   # 配置与 Mapper

database_init_final.sql # 最终数据库初始化脚本
```
