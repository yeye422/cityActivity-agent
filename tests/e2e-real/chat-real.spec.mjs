import { test, expect } from "@playwright/test";

test("packaged frontend completes one real chat round with SSE and idempotency", async ({ page }) => {
  const userId = process.env.REAL_E2E_USER_ID || "999998";
  const sseRequests = [];
  let chatRequest = null;

  await page.addInitScript((id) => {
    localStorage.setItem("city.userId", id);
  }, userId);

  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.pathname.includes("/api/v1/city/events/")) {
      sseRequests.push(request);
    }
    if (url.pathname === "/api/v1/city/chat" && request.method() === "POST") {
      chatRequest = request;
    }
  });

  await page.goto("/#/city/chat");
  await page.locator('button[data-action="set-source"][data-source="PUBLIC"]').click();

  const assistantBefore = await page.locator(".message.assistant .bubble").count();
  await page.locator("#chatForm textarea[name=message]").fill("你好，请简单介绍你能做什么");
  const responsePromise = page.waitForResponse(
    (response) => {
      const url = new URL(response.url());
      return url.pathname === "/api/v1/city/chat"
        && response.request().method() === "POST";
    },
    { timeout: 90_000 }
  );
  await page.locator("#chatForm button[type=submit]").click();

  const response = await responsePromise;
  expect(response.ok()).toBeTruthy();

  await expect.poll(
    () => page.locator(".message.assistant .bubble").count(),
    { timeout: 15_000 }
  ).toBeGreaterThan(assistantBefore);

  expect(chatRequest).not.toBeNull();
  expect(chatRequest.headers()["idempotency-key"]).toBeTruthy();
  expect(chatRequest.headers()["x-user-id"]).toBe(userId);

  await expect.poll(() => sseRequests.length).toBeGreaterThan(0);
  expect(sseRequests[0].headers()["x-user-id"]).toBe(userId);

  await expect(page.locator(".run-error")).toHaveCount(0);
});
