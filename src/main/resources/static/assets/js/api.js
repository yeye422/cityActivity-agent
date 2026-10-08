(function () {
    "use strict";

    const API_BASE = "/api/v1/city";
    const USER_ID_KEY = "city.userId";

    function getUserId() {
        return localStorage.getItem(USER_ID_KEY) || "1";
    }

    function setUserId(userId) {
        const normalized = String(userId || "1").trim() || "1";
        localStorage.setItem(USER_ID_KEY, normalized);
        return normalized;
    }

    async function request(path, options) {
        const config = options || {};
        const headers = new Headers(config.headers || {});
        headers.set("X-User-Id", getUserId());

        if (config.body !== undefined && !(config.body instanceof FormData)) {
            headers.set("Content-Type", "application/json");
        }

        const response = await fetch(`${API_BASE}${path}`, {
            ...config,
            headers,
            body: config.body === undefined || config.body instanceof FormData
                ? config.body
                : JSON.stringify(config.body)
        });

        if (!response.ok) {
            const detail = await readError(response);
            throw new Error(detail || `请求失败：${response.status}`);
        }

        if (response.status === 204) {
            return null;
        }

        const text = await response.text();
        if (!text) {
            return null;
        }

        try {
            return JSON.parse(text);
        } catch (error) {
            return text;
        }
    }

    async function readError(response) {
        const text = await response.text();
        if (!text) {
            return "";
        }

        try {
            const payload = JSON.parse(text);
            return payload.message || payload.error || text;
        } catch (error) {
            return text;
        }
    }

    function toQuery(params) {
        const search = new URLSearchParams();
        Object.entries(params || {}).forEach(([key, value]) => {
            if (value !== undefined && value !== null && value !== "") {
                search.set(key, value);
            }
        });
        const query = search.toString();
        return query ? `?${query}` : "";
    }

    function createIdempotencyKey() {
        if (window.crypto && typeof window.crypto.randomUUID === "function") {
            return window.crypto.randomUUID();
        }
        return `city-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    }

    function parseSseBlock(block) {
        const event = { id: "", event: "message", data: null, rawData: "" };
        const dataLines = [];
        String(block || "").split(/\r?\n/).forEach((line) => {
            if (!line || line.startsWith(":")) return;
            const split = line.indexOf(":");
            const field = split < 0 ? line : line.slice(0, split);
            let value = split < 0 ? "" : line.slice(split + 1);
            if (value.startsWith(" ")) value = value.slice(1);
            if (field === "id") event.id = value;
            else if (field === "event") event.event = value || "message";
            else if (field === "data") dataLines.push(value);
        });
        event.rawData = dataLines.join("\n");
        if (event.rawData) {
            try {
                event.data = JSON.parse(event.rawData);
            } catch (_) {
                event.data = event.rawData;
            }
        }
        return event;
    }

    async function subscribeAgentEvents(sessionId, options) {
        const config = options || {};
        const controller = new AbortController();
        const externalSignal = config.signal;
        const onExternalAbort = () => controller.abort(externalSignal && externalSignal.reason);
        if (externalSignal) {
            if (externalSignal.aborted) {
                controller.abort(externalSignal.reason);
            } else {
                externalSignal.addEventListener("abort", onExternalAbort, { once: true });
            }
        }

        const headers = new Headers(config.headers || {});
        headers.set("X-User-Id", getUserId());
        headers.set("Accept", "text/event-stream");
        if (config.lastEventId) {
            headers.set("Last-Event-ID", config.lastEventId);
        }

        const response = await fetch(
            `${API_BASE}/events/${encodeURIComponent(sessionId)}`,
            { method: "GET", headers, signal: controller.signal, cache: "no-store" }
        );
        if (!response.ok || !response.body) {
            const detail = await readError(response);
            throw new Error(detail || `实时连接失败：${response.status}`);
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        const done = (async () => {
            let buffer = "";
            try {
                while (true) {
                    const chunk = await reader.read();
                    if (chunk.done) break;
                    buffer += decoder.decode(chunk.value, { stream: true }).replace(/\r\n/g, "\n");
                    let boundary = buffer.indexOf("\n\n");
                    while (boundary >= 0) {
                        const block = buffer.slice(0, boundary);
                        buffer = buffer.slice(boundary + 2);
                        if (block.trim()) {
                            const event = parseSseBlock(block);
                            if (typeof config.onEvent === "function") config.onEvent(event);
                        }
                        boundary = buffer.indexOf("\n\n");
                    }
                }
            } catch (error) {
                if (!controller.signal.aborted && typeof config.onError === "function") {
                    config.onError(error);
                }
                if (!controller.signal.aborted) throw error;
            } finally {
                if (externalSignal) externalSignal.removeEventListener("abort", onExternalAbort);
                try { reader.releaseLock(); } catch (_) { /* noop */ }
            }
        })();

        return { abort: () => controller.abort(), done };
    }

    window.CityApi = {
        getUserId,
        setUserId,
        createSession: () => request("/sessions", { method: "POST" }),
        resolveLocation: (payload) => request("/location/resolve", { method: "POST", body: payload }),
        weather: (city) => request(`/weather${toQuery({ city })}`),
        createIdempotencyKey,
        subscribeAgentEvents,
        chat: (payload, options) => request("/chat", {
            method: "POST",
            body: payload,
            headers: options && options.idempotencyKey
                ? { "Idempotency-Key": options.idempotencyKey }
                : {}
        }),
        relaxedRecommendation: (payload) => request("/chat/relax", { method: "POST", body: payload }),
        listPersonalActivities: () => request("/activities/personal"),
        createPersonalActivity: (payload) => request("/activities/personal", { method: "POST", body: payload }),
        updatePersonalActivity: (activityId, payload) => request(`/activities/personal/${encodeURIComponent(activityId)}`, { method: "PUT", body: payload }),
        deletePersonalActivity: (activityId) => request(`/activities/personal/${encodeURIComponent(activityId)}`, { method: "DELETE" }),
        listPublicActivities: () => request("/activities/public"),
        listActivitySessions: (activityId, date) => request(`/activities/${encodeURIComponent(activityId)}/sessions${toQuery({ date })}`),
        slotOptions: () => request("/slot-options"),
        saveFeedback: (payload) => request("/feedback", { method: "POST", body: payload }),
        listTraces: (params) => request(`/debug/traces${toQuery(params)}`),
        getTrace: (traceId) => request(`/debug/traces/${encodeURIComponent(traceId)}`),
        listSessionTraces: (sessionId, limit) => request(`/debug/sessions/${encodeURIComponent(sessionId)}/traces${toQuery({ limit })}`),
        labelTrace: (traceId, payload) => request(`/debug/traces/${encodeURIComponent(traceId)}/label`, { method: "PUT", body: payload }),
        evaluate: (payload) => request("/evaluations", { method: "POST", body: payload }),
        regressionEvaluate: (payload) => request("/evaluations/regression", { method: "POST", body: payload }),
        promoteEvaluationCase: (payload) => request("/evaluations/cases/promote", { method: "POST", body: payload })
    };
})();




