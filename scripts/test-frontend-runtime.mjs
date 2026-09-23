import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const apiSource = fs.readFileSync("src/main/resources/static/assets/js/api.js", "utf8");
const appSource = fs.readFileSync("src/main/resources/static/assets/js/app.js", "utf8");

const storage = new Map([["city.userId", "42"]]);
const localStorage = {
    getItem(key) { return storage.has(key) ? storage.get(key) : null; },
    setItem(key, value) { storage.set(key, String(value)); },
    removeItem(key) { storage.delete(key); }
};

const window = {
    crypto: {
        randomUUID() { return "uuid-test-1"; }
    }
};

const sandbox = {
    window,
    localStorage,
    Headers,
    AbortController,
    TextDecoder,
    FormData,
    URLSearchParams,
    console,
    fetch: async () => {
        throw new Error("fetch mock not installed");
    }
};
vm.createContext(sandbox);
vm.runInContext(apiSource, sandbox, { filename: "api.js" });

assert.equal(typeof window.CityApi.chat, "function");
assert.equal(typeof window.CityApi.subscribeAgentEvents, "function");
assert.equal(window.CityApi.createIdempotencyKey(), "uuid-test-1");

let chatRequest = null;
sandbox.fetch = async (url, options) => {
    chatRequest = { url, options };
    return new Response(JSON.stringify({ sessionId: "session-1", speechText: "ok" }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
    });
};

await window.CityApi.chat(
    { sessionId: "session-1", message: "hello", sourceMode: "PUBLIC" },
    { idempotencyKey: "idem-123" }
);
assert.equal(chatRequest.url, "/api/v1/city/chat");
assert.equal(chatRequest.options.headers.get("X-User-Id"), "42");
assert.equal(chatRequest.options.headers.get("Idempotency-Key"), "idem-123");

const encoder = new TextEncoder();
let sseRequest = null;
sandbox.fetch = async (url, options) => {
    sseRequest = { url, options };
    const body = new ReadableStream({
        start(controller) {
            controller.enqueue(encoder.encode(
                'event: connected\n' +
                'data: {"sessionId":"session-1","replay":true}\n\n' +
                'id: trace-1:1\n' +
                'event: RUN_STARTED\n' +
                'data: {"type":"RUN_STARTED","traceId":"trace-1","sessionId":"session-1","sequence":1,"phase":"HTTP","eventName":"REQUEST_RECEIVED","payload":null}\n\n'
            ));
            controller.close();
        }
    });
    return new Response(body, {
        status: 200,
        headers: { "Content-Type": "text/event-stream" }
    });
};

const events = [];
const stream = await window.CityApi.subscribeAgentEvents("session-1", {
    lastEventId: "trace-0:9",
    onEvent(event) { events.push(event); }
});
await stream.done;

assert.equal(sseRequest.url, "/api/v1/city/events/session-1");
assert.equal(sseRequest.options.headers.get("X-User-Id"), "42");
assert.equal(sseRequest.options.headers.get("Last-Event-ID"), "trace-0:9");
assert.equal(sseRequest.options.headers.get("Accept"), "text/event-stream");
assert.equal(events.length, 2);
assert.equal(events[0].event, "connected");
assert.equal(events[1].event, "RUN_STARTED");
assert.equal(events[1].id, "trace-1:1");
assert.equal(events[1].data.eventName, "REQUEST_RECEIVED");

for (const required of [
    "renderAgentRunProgress",
    "retryPendingChat",
    "restoreChatRuntime",
    "subscribeAgentEvents",
    "RECONNECTING",
    "lastCompletedTraceId"
]) {
    assert.match(appSource, new RegExp(required), `app.js missing frontend runtime contract: ${required}`);
}

console.log("frontend runtime contract checks passed");
