# CityFlow 变更记录

## 2026-09-22

- 统一活动领域意图、配置、Prompt 和规划输出合同，移除饮食领域遗留命名。
- 建立 `AgentTask / AgentResult / EvidenceRef` 结构化任务与证据合同。
- 将 Worker 工具权限升级为类型化读写合同，活动发现与规划结果关联实体证据指纹。
- 增加模型调用预算、重复调用检测和三态熔断。
- 增加上下文边界压缩、显式长期偏好记忆和候选集 BM25 重排。
- 增加六类会话级 SSE 执行事件。
- 建立稳定错误码、降级原因和统一 HTTP 错误响应。
- 增加长期偏好数据库向前迁移与回滚脚本。
- 完整路线图见 `docs/architecture/cityflow-full-refactor-plan.md`。
- 冻结基线见 `docs/baseline/p0-baseline-2026-09-22.md`。

未经冻结评测集、重复实验或压测验证的性能与准确率指标均保持未知。
