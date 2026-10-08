# CityFlow 变更记录

## 2026-09-23

- RecommendationAgent / PlanningAgent 收敛为唯一在线 AgentScope ReAct 决策主链，删除 feature flag 和 legacy fallback。
- CityAgentSupervisor 收敛为 Session/Trace/Route/Delegate 四边界依赖，状态保存统一由 DecisionCommitService 提交。
- 删除 Recommend/Plan ResponseAgent、Discovery/Retrieval/Planning/Response legacy Worker 及迁移期 WorkerDispatcher / AgentTask / ToolContractRegistry 合同层。
- 增加显式 PlanNotebook，记录 Discovery -> Proposal -> Validate -> Repair -> Validated 生命周期；未通过 validate_plan 不允许产出最终 Plan。
- 长期记忆接入 MemoryMutationProposal -> MemoryPolicy，只允许明确长期且稳定的偏好自动写入。
- ReAct 评测切换为单轨 success/degradation 语义，并补充 UserGoal Coverage、Plan Valid、Tool Error、候选/Session 违规、预算/时间冲突和 P50/P95 延迟指标。
- 真实数据库 + 真实模型的 react-v1 Baseline/RegressionGate 与 retrieval-v1 Baseline 仍需在具备运行配置的环境执行；未经该验收不声明线上质量提升。


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
