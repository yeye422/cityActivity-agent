# AgentScope Chat Frontend Runtime

前端静态页面通过同步 `ChatResponse` + SSE 运行事件组合展示 AgentScope ReAct 执行过程。

## 调用顺序

每一轮用户消息严格执行：

```text
ensure/create session
↓
GET /api/v1/city/events/{sessionId}
↓
SSE connected
↓
generate Idempotency-Key
↓
POST /api/v1/city/chat
↓
SSE progress events
↓
HTTP ChatResponse
↓
render final assistant message once
```

最终业务消息以 HTTP `ChatResponse` 为唯一渲染来源。SSE 的 `MESSAGE_COMPLETE` 只表示运行阶段完成，不直接插入第二条 assistant message。

## SSE transport

浏览器使用 `fetch + ReadableStream`，而不是原生 `EventSource`，因为订阅必须携带：

```text
X-User-Id
Last-Event-ID
```

稳定事件类型：

```text
RUN_STARTED
STEP_STARTED
STEP_COMPLETED
MESSAGE_COMPLETE
ERROR
RUN_FINISHED
```

页面只把内部 Trace 事件归并成用户可理解的业务步骤，例如“理解需求”“检索并筛选活动”“生成活动计划”“校验活动安排”“调整计划冲突”。

不得向普通 UI 暴露模型 Chain-of-Thought。

## 请求幂等

每轮新消息生成一个新的 `Idempotency-Key`。网络失败或刷新恢复同一轮请求时必须复用原 key 和原 payload。

这保证：

```text
network retry != duplicate Agent run
page refresh != duplicate Agent run
```

## 断线恢复

客户端记录当前：

```text
sessionId
traceId
lastEventId
pending payload
Idempotency-Key
run steps
```

到 `sessionStorage`。

SSE 中断后按 500ms / 1s / 2s / 5s 退避重连，并携带 `Last-Event-ID`。重连只恢复事件，不重新发起聊天请求。

页面刷新后如果存在 pending request：

1. 恢复 session 和事件游标。
2. 重建 SSE。
3. 等待或 replay 到 `RUN_FINISHED`。
4. 使用相同 `Idempotency-Key` 重取 `ChatResponse`。
5. 服务端成功记录存在时直接返回原响应，不重复执行 Agent。

## Planning repair

`PLAN_VALIDATION_FAILED` 是允许继续 Repair 的中间业务状态，不属于 Run ERROR。

只有真正终止运行的失败事件或带 Exception 的 Trace Event 才映射成 SSE `ERROR`。

## Frontend checks

普通 CI 执行：

```bash
node --check src/main/resources/static/assets/js/api.js
node --check src/main/resources/static/assets/js/app.js
node scripts/test-frontend-runtime.mjs
```

运行合同测试至少覆盖：

- `POST /chat` 携带 `Idempotency-Key`
- 所有请求携带 `X-User-Id`
- SSE 使用 `Last-Event-ID`
- SSE 解析 `id/event/data`
- 页面保留 run progress / retry / restore 合同

真实浏览器 + 真模型联调仍应在 Release Gate 环境执行 Recommendation、Clarify、Planning Repair、Relaxation 和断网/刷新场景。


## 真环境浏览器 smoke

手动 `Release Gates` 在 Spring Boot 应用启动后还会执行：

```bash
REAL_BASE_URL=http://127.0.0.1:8080 npm run test:e2e:real
```

该用例不 mock API，会直接通过打包后的页面完成一轮真实聊天，并检查：

- 浏览器确实建立 SSE 请求；
- SSE 携带当前 `X-User-Id`；
- `POST /chat` 携带非空 `Idempotency-Key`；
- HTTP 返回成功；
- 页面最终新增 assistant 消息；
- UI 不出现 terminal run error。

这是一条真实浏览器 + Spring Boot + MySQL + DashScope 的 smoke，不替代 `react-v1` 质量评测，但能阻止“后端门禁通过、前端实际链路断裂”的发布。
