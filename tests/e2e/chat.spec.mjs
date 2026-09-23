import { test, expect } from "@playwright/test";

const API_PREFIX = "/api/v1/city";

function responsePayload(traceId, text = "推荐完成") {
  return {
    sessionId: "session-e2e",
    speechText: text,
    responseType: "RECOMMENDATION",
    displayBlocks: [],
    missingSlots: [],
    traceId,
    relaxationOptions: [],
    appliedSlots: {},
    excludedSlots: {},
    timeConstraint: null
  };
}

function sseEvent({ id = "", event, data }) {
  return [
    id ? `id: ${id}` : "",
    `event: ${event}`,
    `data: ${JSON.stringify(data)}`,
    "",
    ""
  ].filter((line, index, all) => !(line === "" && index === 0)).join("\n");
}

async function installCommonMocks(page, handlers = {}) {
  const state = {
    chatKeys: [],
    chatBodies: [],
    sseLastEventIds: [],
    sseCount: 0
  };

  await page.route(`**${API_PREFIX}/**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.slice(API_PREFIX.length);

    if (path === "/sessions" && request.method() === "POST") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ sessionId: "session-e2e" })
      });
      return;
    }

    if (path === "/slot-options" && request.method() === "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ city: ["西安", "上海"] })
      });
      return;
    }

    if (path.startsWith("/events/") && request.method() === "GET") {
      state.sseCount += 1;
      state.sseLastEventIds.push(request.headers()["last-event-id"] || "");
      if (handlers.events) {
        await handlers.events(route, request, state);
        return;
      }
      const body =
        sseEvent({
          event: "connected",
          data: { sessionId: "session-e2e", replay: state.sseCount > 1 }
        }) +
        sseEvent({
          id: "trace-e2e:1",
          event: "RUN_STARTED",
          data: {
            type: "RUN_STARTED",
            traceId: "trace-e2e",
            sessionId: "session-e2e",
            sequence: 1,
            phase: "HTTP",
            eventName: "REQUEST_RECEIVED",
            payload: null
          }
        }) +
        sseEvent({
          id: "trace-e2e:2",
          event: "STEP_COMPLETED",
          data: {
            type: "STEP_COMPLETED",
            traceId: "trace-e2e",
            sessionId: "session-e2e",
            sequence: 2,
            phase: "INTENT",
            eventName: "INTENT_RECOGNIZED",
            payload: {}
          }
        });
      await route.fulfill({
        status: 200,
        headers: {
          "Content-Type": "text/event-stream",
          "Cache-Control": "no-cache"
        },
        body
      });
      return;
    }

    if (path === "/chat" && request.method() === "POST") {
      state.chatKeys.push(request.headers()["idempotency-key"] || "");
      state.chatBodies.push(request.postDataJSON());
      if (handlers.chat) {
        await handlers.chat(route, request, state);
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(responsePayload("trace-e2e"))
      });
      return;
    }

    if (path.startsWith("/weather") && request.method() === "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ available: false })
      });
      return;
    }

    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([])
    });
  });

  return state;
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("city.userId", "1");
  });
});

test("chat sends idempotency key, shows progress, and renders final response once", async ({ page }) => {
  let releaseChat;
  const chatGate = new Promise((resolve) => {
    releaseChat = resolve;
  });

  const state = await installCommonMocks(page, {
    chat: async (route) => {
      await chatGate;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(responsePayload("trace-normal", "推荐完成"))
      });
    }
  });

  await page.goto("/#/city/chat");
  const composer = page.locator("#chatForm textarea[name=message]");
  await composer.fill("周日下午想找一个轻松活动");
  await page.locator("#chatForm button[type=submit]").click();

  await expect(page.locator(".message.user .bubble")).toHaveText("周日下午想找一个轻松活动");
  await expect(page.locator(".agent-run-card")).toBeVisible();
  await expect(page.locator(".agent-run-card")).toContainText("理解你的需求");

  expect(state.chatKeys).toHaveLength(1);
  expect(state.chatKeys[0]).not.toBe("");
  expect(state.chatBodies[0].message).toBe("周日下午想找一个轻松活动");

  releaseChat();

  await expect(page.locator(".message.assistant .bubble", { hasText: "推荐完成" })).toHaveCount(1);
  await expect(page.locator(".agent-run-card")).toHaveCount(0);
});

test("SSE reconnect carries the latest Last-Event-ID", async ({ page }) => {
  const state = await installCommonMocks(page, {
    events: async (route, request, runtime) => {
      if (runtime.sseCount === 1) {
        await route.fulfill({
          status: 200,
          headers: { "Content-Type": "text/event-stream" },
          body:
            sseEvent({
              event: "connected",
              data: { sessionId: "session-e2e", replay: false }
            }) +
            sseEvent({
              id: "trace-reconnect:1",
              event: "RUN_STARTED",
              data: {
                type: "RUN_STARTED",
                traceId: "trace-reconnect",
                sessionId: "session-e2e",
                sequence: 1,
                phase: "HTTP",
                eventName: "REQUEST_RECEIVED",
                payload: null
              }
            }) +
            sseEvent({
              id: "trace-reconnect:2",
              event: "STEP_COMPLETED",
              data: {
                type: "STEP_COMPLETED",
                traceId: "trace-reconnect",
                sessionId: "session-e2e",
                sequence: 2,
                phase: "INTENT",
                eventName: "INTENT_RECOGNIZED",
                payload: {}
              }
            })
        });
        return;
      }

      await route.fulfill({
        status: 200,
        headers: { "Content-Type": "text/event-stream" },
        body:
          sseEvent({
            event: "connected",
            data: { sessionId: "session-e2e", replay: true }
          }) +
          sseEvent({
            id: "trace-reconnect:3",
            event: "STEP_COMPLETED",
            data: {
              type: "STEP_COMPLETED",
              traceId: "trace-reconnect",
              sessionId: "session-e2e",
              sequence: 3,
              phase: "SEARCH",
              eventName: "RETRIEVAL_TOOL_COMPLETED",
              payload: {}
            }
          })
      });
    },
    chat: async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 1200));
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(responsePayload("trace-reconnect", "重连后完成"))
      });
    }
  });

  await page.goto("/#/city/chat");
  await page.locator("#chatForm textarea[name=message]").fill("找个活动");
  await page.locator("#chatForm button[type=submit]").click();

  await expect.poll(() => state.sseCount).toBeGreaterThanOrEqual(2);
  expect(state.sseLastEventIds[0]).toBe("");
  expect(state.sseLastEventIds[1]).toBe("trace-reconnect:2");

  await expect(page.locator(".message.assistant .bubble", { hasText: "重连后完成" })).toHaveCount(1);
});

test("startup recovery replays SSE and reuses the original idempotency key", async ({ page }) => {
  await page.addInitScript(() => {
    const userId = "1";
    const sessionId = "session-e2e";
    const pendingRequest = {
      payload: {
        sessionId,
        message: "刷新前的请求",
        sourceMode: "PUBLIC",
        context: {
          city: "西安",
          location: "钟楼"
        }
      },
      idempotencyKey: "idem-refresh-001"
    };

    sessionStorage.setItem(
      `city.chat.session.${userId}`,
      JSON.stringify({
        sessionId,
        sourceMode: "PUBLIC",
        city: "西安",
        location: "钟楼"
      })
    );
    sessionStorage.setItem(
      `city.agent.run.${userId}.${sessionId}`,
      JSON.stringify({
        sessionId,
        status: "RUNNING",
        traceId: "trace-refresh",
        lastEventId: "trace-refresh:2",
        steps: [{ key: "intent", label: "理解你的需求", status: "COMPLETED" }],
        startedAt: Date.now(),
        pendingRequest
      })
    );
  });

  const state = await installCommonMocks(page, {
    events: async (route) => {
      await route.fulfill({
        status: 200,
        headers: { "Content-Type": "text/event-stream" },
        body:
          sseEvent({
            event: "connected",
            data: { sessionId: "session-e2e", replay: true }
          }) +
          sseEvent({
            id: "trace-refresh:3",
            event: "RUN_FINISHED",
            data: {
              type: "RUN_FINISHED",
              traceId: "trace-refresh",
              sessionId: "session-e2e",
              sequence: 3,
              phase: "HTTP",
              eventName: "REQUEST_FINISHED",
              payload: {}
            }
          })
      });
    },
    chat: async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(responsePayload("trace-refresh", "刷新恢复完成"))
      });
    }
  });

  await page.goto("/#/city/chat");

  await expect(page.locator('button[data-source="PUBLIC"]')).toHaveClass(/soft/);
  await expect(page.locator("#chatCity")).toHaveValue("西安");
  await expect(page.locator(".message.user .bubble")).toHaveText("刷新前的请求");

  await expect.poll(() => state.chatKeys.length).toBe(1);
  expect(state.sseLastEventIds[0]).toBe("trace-refresh:2");
  expect(state.chatKeys[0]).toBe("idem-refresh-001");
  expect(state.chatBodies[0].sourceMode).toBe("PUBLIC");
  expect(state.chatBodies[0].context.city).toBe("西安");

  await expect(page.locator(".message.assistant .bubble", { hasText: "刷新恢复完成" })).toHaveCount(1);
  await expect(page.locator(".agent-run-card")).toHaveCount(0);
});


test("clarification response remains a normal assistant turn", async ({ page }) => {
  const state = await installCommonMocks(page, {
    chat: async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          sessionId: "session-e2e",
          speechText: "",
          clarifyQuestion: "你想在哪个城市找活动？",
          responseType: "CLARIFICATION",
          displayBlocks: [],
          missingSlots: ["city"],
          traceId: "trace-clarify",
          relaxationOptions: [],
          appliedSlots: {},
          excludedSlots: {},
          timeConstraint: null
        })
      });
    }
  });

  await page.goto("/#/city/chat");
  await page.locator("#chatForm textarea[name=message]").fill("周末想出去玩");
  await page.locator("#chatForm button[type=submit]").click();

  await expect(page.locator(".message.assistant .bubble", { hasText: "你想在哪个城市找活动？" })).toHaveCount(1);
  await expect(page.locator(".message.assistant .chip", { hasText: "城市" })).toHaveCount(1);
  await expect(page.locator(".run-error")).toHaveCount(0);
  expect(state.chatKeys[0]).not.toBe("");
});

test("planning validation failure is rendered as repair progress instead of run error", async ({ page }) => {
  let releaseChat;
  const chatGate = new Promise((resolve) => {
    releaseChat = resolve;
  });

  await installCommonMocks(page, {
    events: async (route) => {
      const body =
        sseEvent({
          event: "connected",
          data: { sessionId: "session-e2e", replay: false }
        }) +
        sseEvent({
          id: "trace-plan:1",
          event: "RUN_STARTED",
          data: {
            type: "RUN_STARTED",
            traceId: "trace-plan",
            sessionId: "session-e2e",
            sequence: 1,
            phase: "HTTP",
            eventName: "REQUEST_RECEIVED",
            payload: null
          }
        }) +
        sseEvent({
          id: "trace-plan:2",
          event: "STEP_COMPLETED",
          data: {
            type: "STEP_COMPLETED",
            traceId: "trace-plan",
            sessionId: "session-e2e",
            sequence: 2,
            phase: "TOOL",
            eventName: "PLAN_VALIDATION_FAILED",
            payload: { valid: false }
          }
        }) +
        sseEvent({
          id: "trace-plan:3",
          event: "STEP_COMPLETED",
          data: {
            type: "STEP_COMPLETED",
            traceId: "trace-plan",
            sessionId: "session-e2e",
            sequence: 3,
            phase: "AGENT",
            eventName: "PLAN_REPAIRED",
            payload: {}
          }
        }) +
        sseEvent({
          id: "trace-plan:4",
          event: "STEP_COMPLETED",
          data: {
            type: "STEP_COMPLETED",
            traceId: "trace-plan",
            sessionId: "session-e2e",
            sequence: 4,
            phase: "TOOL",
            eventName: "PLAN_VALIDATION_PASSED",
            payload: { valid: true }
          }
        });
      await route.fulfill({
        status: 200,
        headers: { "Content-Type": "text/event-stream" },
        body
      });
    },
    chat: async (route) => {
      await chatGate;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(responsePayload("trace-plan", "规划完成"))
      });
    }
  });

  await page.goto("/#/city/chat");
  await page.locator("#chatForm textarea[name=message]").fill("帮我安排半天活动");
  await page.locator("#chatForm button[type=submit]").click();

  await expect(page.locator(".agent-run-card")).toContainText("校验活动安排");
  await expect(page.locator(".agent-run-card")).toContainText("调整计划冲突");
  await expect(page.locator(".run-error")).toHaveCount(0);
  await expect(page.locator(".agent-run-card")).not.toContainText("处理未完成");

  releaseChat();

  await expect(page.locator(".message.assistant .bubble", { hasText: "规划完成" })).toHaveCount(1);
  await expect(page.locator(".agent-run-card")).toHaveCount(0);
});

test("mobile chat keeps the live run card within the viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });

  let releaseChat;
  const chatGate = new Promise((resolve) => {
    releaseChat = resolve;
  });

  await installCommonMocks(page, {
    events: async (route) => {
      const body =
        sseEvent({
          event: "connected",
          data: { sessionId: "session-e2e", replay: false }
        }) +
        sseEvent({
          id: "trace-mobile:1",
          event: "STEP_COMPLETED",
          data: {
            type: "STEP_COMPLETED",
            traceId: "trace-mobile",
            sessionId: "session-e2e",
            sequence: 1,
            phase: "INTENT",
            eventName: "INTENT_RECOGNIZED",
            payload: {}
          }
        }) +
        sseEvent({
          id: "trace-mobile:2",
          event: "STEP_COMPLETED",
          data: {
            type: "STEP_COMPLETED",
            traceId: "trace-mobile",
            sessionId: "session-e2e",
            sequence: 2,
            phase: "SEARCH",
            eventName: "RETRIEVAL_TOOL_COMPLETED",
            payload: {}
          }
        }) +
        sseEvent({
          id: "trace-mobile:3",
          event: "STEP_COMPLETED",
          data: {
            type: "STEP_COMPLETED",
            traceId: "trace-mobile",
            sessionId: "session-e2e",
            sequence: 3,
            phase: "PLAN",
            eventName: "PLANNING_AGENT_DECIDED",
            payload: {}
          }
        });
      await route.fulfill({
        status: 200,
        headers: { "Content-Type": "text/event-stream" },
        body
      });
    },
    chat: async (route) => {
      await chatGate;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(responsePayload("trace-mobile", "移动端完成"))
      });
    }
  });

  await page.goto("/#/city/chat");
  await expect(page.locator(".mobile-bottom-nav")).toBeVisible();

  await page.locator("#chatForm textarea[name=message]").fill("移动端找活动");
  await page.locator("#chatForm button[type=submit]").click();

  const card = page.locator(".agent-run-card");
  await expect(card).toBeVisible();
  const box = await card.boundingBox();
  expect(box).not.toBeNull();
  expect(box.x).toBeGreaterThanOrEqual(0);
  expect(box.x + box.width).toBeLessThanOrEqual(390);

  expect(await page.locator(".run-step").count()).toBeGreaterThanOrEqual(3);
  await expect(page.locator(".run-step:visible")).toHaveCount(1);

  releaseChat();
  await expect(page.locator(".message.assistant .bubble", { hasText: "移动端完成" })).toHaveCount(1);
});
