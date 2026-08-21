(function () {
    "use strict";
    const app = document.getElementById("app");
    const toast = document.getElementById("toast");
    const userIdInput = document.getElementById("userIdInput");

    // 数据缓存管理器
    class CacheManager {
        constructor(ttl = 5 * 60 * 1000) {
            this.cache = new Map();
            this.ttl = ttl;
        }

        set(key, value) {
            this.cache.set(key, {
                value,
                timestamp: Date.now()
            });
        }

        get(key) {
            const item = this.cache.get(key);
            if (!item) return null;

            if (Date.now() - item.timestamp > this.ttl) {
                this.cache.delete(key);
                return null;
            }

            return item.value;
        }

        clear() {
            this.cache.clear();
        }
    }

    const cache = new CacheManager();

    const SLOT_LABELS = {
        city: "城市",
        location: "位置区域",
        activityTime: "活动时间",
        mood: "活动氛围",
        scene: "同行人",
        budget: "预算",
        activityType: "活动类型",
        style: "活动风格",
        duration: "活动时长"
    };
    const SLOT_TONES = {
        city: "city",
        location: "location",
        activityTime: "time",
        mood: "mood",
        scene: "scene",
        budget: "budget",
        activityType: "type",
        style: "style",
        duration: "duration"
    };
    const INTENTS = [
        "MEAL_RECOMMENDATION",
        "CLARIFY_NEEDED",
        "MEAL_ADJUST",
        "ACTIVITY_PLAN",
        "HEALTH_RISK",
        "OTHER"
    ];
    const PUBLIC_FILTER_KEYS = ["city", "location", "activityTime", "activityType"];
    const QUICK_PROMPTS = [
        "周六和朋友在西安，预算 200 元内，不想太累",
        "北京周六晚上想看轻松一点的演出",
        "上海周日一个人想找室内活动",
        "换一批，不要户外",
        "帮我安排西安周六下午到晚上的活动"
    ];
    const SOURCE_LABELS = {
        PERSONAL: "个人库",
        PUBLIC: "公共库"
    };
    const WELCOME_MESSAGE = "你好，我是城市活动推荐助手。告诉我时间、同行人、预算或想要的氛围，我会整理成可比较的活动推荐。";
    const state = {
        home: { loaded: false, personalCount: 0, publicCount: 0 },
        slotOptions: null,
        personalActivities: [],
        publicActivities: [],
        publicFilters: {
            city: "",
            location: "",
            activityTime: "",
            activityType: ""
        },
        editingActivity: null,
        chat: {
            sourceMode: "PERSONAL",
            sessionId: null,
            sending: false,
            messages: [
                {
                    role: "assistant",
                    text: WELCOME_MESSAGE
                }
            ]
        },
        traces: {
            rows: [],
            selected: null,
            loading: false,
            filters: defaultTraceFilters()
        },
        evaluation: {
            report: null,
            loading: false,
            form: defaultRangeForm()
        }
    };
    function defaultRangeForm() {
        const end = new Date();
        const start = new Date(end.getTime() - 24 * 60 * 60 * 1000);
        return {
            startAt: toLocalInputValue(start),
            endAt: toLocalInputValue(end),
            limit: 50,
            includeLlmJudge: false
        };
    }
    function defaultTraceFilters() {
        const range = defaultRangeForm();
        return {
            startAt: range.startAt,
            endAt: range.endAt,
            onlyUnlabeled: false,
            limit: 50,
            sessionId: ""
        };
    }
    function toLocalInputValue(date) {
        const pad = (value) => String(value).padStart(2, "0");
        return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
    }
    function escapeHtml(value) {
        return String(value ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#039;");
    }
    function safeJson(value) {
        if (value === null || value === undefined || value === "") {
            return "";
        }
        try {
            const parsed = typeof value === "string" ? JSON.parse(value) : value;
            return JSON.stringify(parsed, null, 2);
        } catch (error) {
            return String(value);
        }
    }
    function showToast(message, type) {
        toast.textContent = message;
        toast.className = `toast show ${type === "error" ? "error" : ""}`;
        window.clearTimeout(showToast.timer);
        showToast.timer = window.setTimeout(() => {
            toast.className = "toast";
        }, 3200);
    }
    function setLoading(button, loadingText) {
        if (!button) {
            return () => {};
        }
        const oldText = button.textContent;
        button.disabled = true;
        button.textContent = loadingText || "处理中...";
        return () => {
            button.disabled = false;
            button.textContent = oldText;
        };
    }
    async function guard(action, successMessage) {
        try {
            const result = await action();
            if (successMessage) {
                showToast(successMessage);
            }
            return result;
        } catch (error) {
            showToast(error.message || "操作失败", "error");
            throw error;
        }
    }
    function currentRoute() {
        return (location.hash || "#/city").slice(1).split("?")[0] || "/city";
    }
    function navigate(route) {
        location.hash = route;
    }
    function setActiveNav(route) {
        document.querySelectorAll("[data-nav]").forEach((item) => {
            item.classList.toggle("active", item.dataset.nav === route);
        });
        document.querySelectorAll("[data-mobile-nav]").forEach((item) => {
            item.classList.toggle("active", item.dataset.mobileNav === route);
        });
    }
    function render() {
        const route = currentRoute();
        setActiveNav(route);
        if (route === "/city") {
            renderHome();
        } else if (route === "/city/chat") {
            renderChat();
        } else if (route === "/city/activities/personal") {
            renderPersonalActivities();
        } else if (route === "/city/activities/public") {
            renderPublicActivities();
        } else if (route === "/admin/traces") {
            renderTraces();
        } else if (route === "/admin/evaluations") {
            renderEvaluations();
        } else {
            navigate("/city");
        }
        app.focus({ preventScroll: true });
    }
    function renderHome() {
        const recentActivities = latestRecommendedActivities();
        app.innerHTML = `
            <section class="workspace">
                <div class="section lead-panel">
                    <div class="eyebrow">城市活动 Copilot</div>
                    <h1>把一句周末想法，整理成能直接选择的活动方案。</h1>
                    <form id="homePromptForm" class="prompt-box">
                        <textarea name="message" placeholder="例如：周六下午和朋友在西安，预算 200 元内，想轻松一点" required></textarea>
                        <button class="btn primary cta" type="submit">开始推荐</button>
                    </form>
                    <div class="quick-strip">
                        ${QUICK_PROMPTS.slice(0, 4).map((text) => `<button class="chip" data-action="quick-message" data-message="${escapeHtml(text)}">${escapeHtml(text)}</button>`).join("")}
                    </div>
                </div>
                <aside class="section summary-panel">
                    <div class="card-title">
                        <div>
                            <h2>当前工作区</h2>
                            <p>${escapeHtml(SOURCE_LABELS[state.chat.sourceMode])} · 用户 ${escapeHtml(CityApi.getUserId())}</p>
                        </div>
                    </div>
                    <div class="stats compact">
                        ${statCard("我的收藏", state.home.loaded ? state.home.personalCount : "加载中", "个性化推荐来源")}
                        ${statCard("公共活动", state.home.loaded ? state.home.publicCount : "加载中", "快速体验来源")}
                    </div>
                    <div class="button-row">
                        <a class="btn soft" href="#/city/activities/personal">管理收藏</a>
                        <a class="btn ghost" href="#/city/activities/public">浏览活动库</a>
                    </div>
                    ${renderRecommendationPreview()}
                </aside>
            </section>
            <section class="section recommendations-panel">
                <div class="card-title">
                    <div>
                        <h2>最近推荐</h2>
                        <p>${recentActivities.length ? "来自最近一次对话，可继续反馈或换一批。" : "还没有推荐结果，可以先从上方输入需求。"}</p>
                    </div>
                    <a class="btn primary cta" href="#/city/chat">进入对话推荐</a>
                </div>
                ${recentActivities.length ? renderActivityList(recentActivities, { feedback: true, sessionId: state.chat.sessionId }) : renderEmptyState("写下时间、预算、同行人或活动氛围，推荐结果会出现在这里。")}
            </section>
        `;
        loadHomeStats();
    }
    function statCard(label, value, desc) {
        return `
            <div class="stat-card">
                <span class="muted">${escapeHtml(label)}</span>
                <strong>${escapeHtml(value)}</strong>
                <p class="muted">${escapeHtml(desc)}</p>
            </div>
        `;
    }
    function renderRecommendationPreview() {
        return `
            <div class="preview-card">
                <div class="preview-topline">
                    <span class="badge">示例结果</span>
                    <span class="score">匹配 86%</span>
                </div>
                <h3>周六下午城市艺术展</h3>
                <p class="muted">适合朋友同行，预算轻，室内不累，能快速形成半日安排。</p>
                <div class="chips">
                    <span class="chip selected tag-chip tag-city">城市 西安</span>
                    <span class="chip selected tag-chip tag-location">区域 曲江</span>
                    <span class="chip selected tag-chip tag-activityTime">时间 周六下午</span>
                    <span class="chip selected tag-chip tag-budget">预算 200内</span>
                    <span class="chip selected tag-chip tag-scene">同行 朋友</span>
                </div>
            </div>
        `;
    }
    async function loadHomeStats() {
        if (state.home.loaded) {
            return;
        }
        try {
            const [personal, publicActivities] = await Promise.all([
                CityApi.listPersonalActivities(),
                CityApi.listPublicActivities()
            ]);
            state.home = {
                loaded: true,
                personalCount: personal.length,
                publicCount: publicActivities.length
            };
            if (currentRoute() === "/city") {
                renderHome();
            }
        } catch (error) {
            showToast(error.message || "首页数据加载失败", "error");
        }
    }
    function latestRecommendedActivities() {
        const message = [...state.chat.messages].reverse().find((item) => item.activities && item.activities.length);
        return message ? message.activities : [];
    }
    function renderEmptyState(message) {
        return `<div class="empty">${escapeHtml(message)}</div>`;
    }
    function renderChat() {
        const latestActivities = latestRecommendedActivities();
        app.innerHTML = `
            <section class="chat-layout">
                <div class="section chat-window">
                    <div class="card-title">
                        <div>
                            <div class="eyebrow">对话推荐</div>
                            <h2>描述你的周末需求</h2>
                            <p>${state.chat.sessionId ? `会话 ${escapeHtml(state.chat.sessionId)}` : "发送第一条消息后自动创建会话"}</p>
                        </div>
                        <div class="inline-actions">
                            <button class="btn ${state.chat.sourceMode === "PERSONAL" ? "soft" : "ghost"}" data-action="set-source" data-source="PERSONAL">个人库</button>
                            <button class="btn ${state.chat.sourceMode === "PUBLIC" ? "soft" : "ghost"}" data-action="set-source" data-source="PUBLIC">公共库</button>
                            <button class="btn ghost" data-action="new-session">新会话</button>
                        </div>
                    </div>
                    <div id="messages" class="messages">${state.chat.messages.map(renderMessage).join("")}</div>
                    <form id="chatForm" class="composer">
                        <textarea name="message" placeholder="例如：周六和朋友在西安，预算 200 元内，不想太累" required></textarea>
                        <button class="btn primary cta" type="submit">${state.chat.sending ? "发送中..." : "发送"}</button>
                    </form>
                </div>
                <aside class="section result-panel">
                    <div class="card-title">
                        <div>
                            <div class="eyebrow">推荐结果</div>
                            <h2>${latestActivities.length ? "可选活动" : "等待需求"}</h2>
                            <p>${latestActivities.length ? "选择喜欢、采纳或不合适，系统会记录你的偏好。" : "推荐结果会在这里集中展示，便于比较。"}</p>
                        </div>
                    </div>
                    ${latestActivities.length ? renderActivityList(latestActivities, { feedback: true, sessionId: state.chat.sessionId }) : renderEmptyState("先发送一句需求，例如“周日一个人想找室内活动”。")}
                    <div class="subtle-divider"></div>
                    <div class="support-panel">
                        <div class="card-title">
                            <div>
                                <h3>快捷问题</h3>
                            </div>
                        </div>
                        <div class="chips">
                            ${QUICK_PROMPTS.map((text) => `<button class="chip" data-action="quick-message" data-message="${escapeHtml(text)}">${escapeHtml(text)}</button>`).join("")}
                        </div>
                    </div>
                    <div class="support-panel">
                        <h3>数据来源</h3>
                        <p class="muted">${state.chat.sourceMode === "PERSONAL" ? "当前优先使用你的收藏活动。" : "当前使用系统公共活动库。"}</p>
                        <div class="button-row">
                            <a class="btn soft" href="#/city/activities/personal">维护收藏</a>
                            <a class="btn ghost" href="#/city/activities/public">看公共库</a>
                        </div>
                    </div>
                </aside>
            </section>
        `;
        scrollMessagesToBottom();
    }
    function renderMessage(message) {
        // 打字指示器
        if (message.typing) {
            return `
                <article class="message assistant">
                    <div class="bubble">
                        <div class="typing-indicator">
                            <div class="typing-dot"></div>
                            <div class="typing-dot"></div>
                            <div class="typing-dot"></div>
                        </div>
                    </div>
                </article>
            `;
        }

        const missingSlots = message.missingSlots && message.missingSlots.length
            ? `<div class="chips">${message.missingSlots.map((slot) => `<span class="chip selected">${escapeHtml(SLOT_LABELS[slot] || slot)}</span>`).join("")}</div>`
            : "";
        const trace = message.traceId
            ? `<span>traceId：<a href="#/admin/traces" data-action="open-trace" data-trace-id="${escapeHtml(message.traceId)}">${escapeHtml(message.traceId)}</a></span>`
            : "";
        return `
            <article class="message ${message.role}">
                <div class="bubble">${escapeHtml(message.text)}</div>
                ${missingSlots}
                ${trace ? `<div class="message-meta">${trace}</div>` : ""}
            </article>
        `;
    }
    function scrollMessagesToBottom() {
        const messages = document.getElementById("messages");
        if (messages) {
            messages.scrollTop = messages.scrollHeight;
        }
    }
    async function submitChat(form) {
        const messageInput = form.elements.message;
        const message = messageInput.value.trim();
        if (!message) {
            return;
        }
        messageInput.value = "";
        await sendChatMessage(message);
    }
    async function handleHomePrompt(form) {
        const message = String(form.elements.message.value || "").trim();
        if (!message) {
            return;
        }
        navigate("/city/chat");
        await sendChatMessage(message);
    }
    async function sendChatMessage(message) {
        if (!message || state.chat.sending) {
            return;
        }
        state.chat.messages.push({ role: "user", text: message });
        state.chat.sending = true;

        // 添加打字指示器
        state.chat.messages.push({ role: "assistant", text: "", typing: true });
        renderChat();

        try {
            if (!state.chat.sessionId) {
                const session = await CityApi.createSession();
                state.chat.sessionId = session.sessionId;
            }
            const response = await CityApi.chat({
                sessionId: state.chat.sessionId,
                message,
                sourceMode: state.chat.sourceMode,
                context: {}
            });
            state.chat.sessionId = response.sessionId || state.chat.sessionId;

            // 移除打字指示器
            state.chat.messages.pop();

            state.chat.messages.push({
                role: "assistant",
                text: response.clarifyQuestion || response.speechText || "我已经处理完这轮请求。",
                responseType: response.responseType,
                activities: response.displayBlocks || [],
                missingSlots: response.missingSlots || [],
                traceId: response.traceId,
                sessionId: response.sessionId || state.chat.sessionId
            });
        } catch (error) {
            // 移除打字指示器
            state.chat.messages.pop();
            showToast(error.message || "聊天请求失败", "error");
            state.chat.messages.push({ role: "assistant", text: "这轮请求失败了，请稍后重试。" });
        } finally {
            state.chat.sending = false;
            renderChat();
        }
    }
    function resetChat() {
        state.chat.sessionId = null;
        state.chat.messages = [
            {
                role: "assistant",
                text: WELCOME_MESSAGE
            }
        ];
        renderChat();
    }
    async function renderPersonalActivities() {
        if (!state.slotOptions) {
            app.innerHTML = `<section class="section"><div class="empty">标签字典加载中...</div></section>`;
            await ensureSlotOptions();
            if (currentRoute() !== "/city/activities/personal") {
                return;
            }
        }
        await ensurePersonalActivities();
        if (currentRoute() !== "/city/activities/personal") {
            return;
        }
        app.innerHTML = `
            <section class="split activity-management">
                <div class="section">
                    <div class="card-title">
                        <div>
                            <div class="eyebrow">个人活动库</div>
                            <h2>我的收藏活动</h2>
                            <p>维护收藏活动，智能推荐时可切换到个人库。</p>
                        </div>
                        <button class="btn primary cta" data-action="new-activity">新增收藏活动</button>
                    </div>
                    <div id="personalActivityList">${renderActivityList(state.personalActivities, { editable: true })}</div>
                </div>
                <aside class="section">
                    ${renderActivityForm()}
                </aside>
            </section>
        `;
    }
    function renderActivityForm() {
        const activity = state.editingActivity || emptyActivity();
        const title = activity.id ? "编辑收藏活动" : "新增收藏活动";
        return `
            <div class="card-title">
                <div>
                    <div class="eyebrow">${activity.id ? "编辑" : "新增"}</div>
                    <h3>${title}</h3>
                    <p>给活动补上城市、区域、时间、预算和类型标签，后续推荐会更准。</p>
                </div>
            </div>
            <form id="activityForm" class="form-grid">
                <input type="hidden" name="activityId" value="${escapeHtml(activity.id || "")}">
                <div class="field full">
                    <label for="activityName">活动名称</label>
                    <input id="activityName" name="name" value="${escapeHtml(activity.name || "")}" placeholder="例如：周末艺术展" required>
                </div>
                ${Object.entries(SLOT_LABELS).map(([key, label]) => renderSlotPicker(key, label, activity[key] || [])).join("")}
                <div class="field full">
                    <div class="button-row">
                        <button class="btn primary cta" type="submit">${activity.id ? "保存修改" : "创建活动"}</button>
                        <button class="btn ghost" type="button" data-action="cancel-edit">清空</button>
                    </div>
                </div>
            </form>
        `;
    }
    function renderSlotPicker(key, label, selected) {
        const options = state.slotOptions && state.slotOptions[key] ? state.slotOptions[key] : [];
        const selectedSet = new Set(selected || []);
        const required = key === "activityTime";
        return `
            <div class="field">
                <label for="slot-${escapeHtml(key)}">${escapeHtml(label)}${required ? "（必选）" : ""}</label>
                <select
                    id="slot-${escapeHtml(key)}"
                    class="slot-select"
                    name="${escapeHtml(key)}"
                    multiple
                    size="5"
                    ${required ? "required" : ""}
                >
                    ${options.map((option) => {
                        const isSelected = selectedSet.has(option);
                        return `<option value="${escapeHtml(option)}" ${isSelected ? "selected" : ""}>${escapeHtml(option)}</option>`;
                    }).join("")}
                </select>
            </div>
        `;
    }
    function emptyActivity() {
        return {
            name: "",
            city: [],
            location: [],
            activityTime: [],
            mood: [],
            scene: [],
            budget: [],
            activityType: [],
            style: [],
            duration: []
        };
    }
    function renderActivityList(activities, options) {
        if (!activities.length) {
            return renderEmptyState((options && options.emptyMessage) || "暂无活动。可以先添加几个常去的地方。");
        }
        return `<div class="activity-grid">${activities.map((activity) => renderActivityCard(activity, options || {})).join("")}</div>`;
    }

    function renderSkeletonCards(count = 4) {
        const skeletons = Array.from({ length: count }, () => `
            <div class="skeleton-card">
                <div class="skeleton-header"></div>
                <div class="skeleton-text"></div>
                <div class="skeleton-chips">
                    <div class="skeleton-chip"></div>
                    <div class="skeleton-chip"></div>
                    <div class="skeleton-chip"></div>
                </div>
            </div>
        `).join("");
        return `<div class="activity-grid">${skeletons}</div>`;
    }

    function renderActivityCard(activity, options) {
        const editable = options && options.editable;
        const feedback = options && options.feedback;
        const tags = activityTagItems(activity);
        const itemId = activity.id || activity.itemId;
        return `
            <article class="activity-card">
                <header>
                    <div>
                        <h3>${escapeHtml(activity.name)}</h3>
                        <p class="muted">${escapeHtml(activity.sourceType || "活动")}</p>
                    </div>
                    ${activity.matchScore ? `<span class="score">匹配 ${Math.round(activity.matchScore * 100)}%</span>` : ""}
                </header>
                <p class="activity-reason">${escapeHtml(activityReason(activity))}</p>
                ${tags.length ? `<div class="chips">${tags.map((tag) => `<span class="chip selected tag-chip tag-${escapeHtml(tag.key)}">${escapeHtml(tag.text)}</span>`).join("")}</div>` : `<p class="muted">暂无标签</p>`}
                ${editable ? `
                    <div class="button-row">
                        <button class="btn soft" data-action="edit-activity" data-id="${escapeHtml(itemId)}">编辑</button>
                        <button class="btn ghost" data-action="delete-activity" data-id="${escapeHtml(itemId)}">删除</button>
                    </div>
                ` : ""}
                ${feedback ? `
                    <div class="button-row">
                        <button class="btn primary cta" data-action="feedback" data-action-value="ADOPT" data-item-id="${escapeHtml(itemId)}" data-session-id="${escapeHtml(options.sessionId || "")}">采纳</button>
                        <button class="btn soft" data-action="feedback" data-action-value="LIKE" data-item-id="${escapeHtml(itemId)}" data-session-id="${escapeHtml(options.sessionId || "")}">喜欢</button>
                        <button class="btn ghost" data-action="feedback" data-action-value="DISLIKE" data-item-id="${escapeHtml(itemId)}" data-session-id="${escapeHtml(options.sessionId || "")}">不合适</button>
                    </div>
                ` : ""}
            </article>
        `;
    }
    function activityTags(activity) {
        return activityTagItems(activity).map((tag) => tag.text);
    }
    function activityTagItems(activity) {
        return Object.keys(SLOT_LABELS).flatMap((key) => slotValues(activity, key).map((value) => ({
            key: SLOT_TONES[key] || key,
            text: `${shortSlotLabel(key)} ${value}`
        })));
    }
    function slotValues(activity, key) {
        const direct = activity[key];
        const nested = activity.matchedSlots && activity.matchedSlots[key];
        const slots = activity.slots && activity.slots[key];
        const value = direct || nested || slots || [];
        return Array.isArray(value) ? value.filter(Boolean) : [value].filter(Boolean);
    }
    function shortSlotLabel(key) {
        const labels = {
            city: "城市",
            location: "区域",
            activityTime: "时间",
            mood: "氛围",
            scene: "同行",
            budget: "预算",
            activityType: "类型",
            style: "风格",
            duration: "时长"
        };
        return labels[key] || SLOT_LABELS[key] || key;
    }
    function activityReason(activity) {
        const explicit = activity.reason || activity.recommendReason || activity.description || activity.summary;
        if (explicit) {
            return explicit;
        }
        const highlights = activityTagItems(activity).slice(0, 3).map((tag) => tag.text).join("、");
        return highlights ? `匹配你的 ${highlights} 偏好，适合作为本轮活动备选。` : "这项活动可以作为本轮推荐备选，补充标签后会更容易比较。";
    }
    async function ensurePersonalActivities(force) {
        if (!force && state.personalActivities.length) {
            return;
        }

        // 先尝试从缓存获取
        if (!force) {
            const cached = cache.get('personalActivities');
            if (cached) {
                state.personalActivities = cached;
                if (currentRoute() === "/city/activities/personal") {
                    document.getElementById("personalActivityList").innerHTML = renderActivityList(state.personalActivities, { editable: true });
                }
                return;
            }
        }

        // 显示骨架屏
        if (currentRoute() === "/city/activities/personal") {
            const listEl = document.getElementById("personalActivityList");
            if (listEl) listEl.innerHTML = renderSkeletonCards(6);
        }

        try {
            state.personalActivities = await CityApi.listPersonalActivities();
            cache.set('personalActivities', state.personalActivities);
            state.home.loaded = false;
            if (currentRoute() === "/city/activities/personal") {
                document.getElementById("personalActivityList").innerHTML = renderActivityList(state.personalActivities, { editable: true });
            }
        } catch (error) {
            showToast(error.message || "我的收藏活动加载失败", "error");
        }
    }
    async function ensureSlotOptions() {
        if (state.slotOptions) {
            return;
        }
        try {
            state.slotOptions = await CityApi.slotOptions();
        } catch (error) {
            showToast(error.message || "槽位字典加载失败", "error");
            throw error;
        }
    }
    async function saveActivity(form) {
        const { id, payload } = activityPayloadFromForm(form);
        if (!payload.name) {
            showToast("请填写活动名称", "error");
            return;
        }
        if (!payload.activityTime.length) {
            showToast("请至少选择一个活动时间标签", "error");
            return;
        }
        const restore = setLoading(form.querySelector("button[type=submit]"), "保存中...");
        try {
            await guard(async () => {
                if (id) {
                    return CityApi.updatePersonalActivity(id, payload);
                }
                return CityApi.createPersonalActivity(payload);
            }, id ? "活动已更新" : "活动已创建");
            state.editingActivity = null;
            await ensurePersonalActivities(true);
            renderPersonalActivities();
        } finally {
            restore();
        }
    }
    function activityPayloadFromForm(form) {
        const formData = new FormData(form);
        const payload = {
            name: String(formData.get("name") || "").trim()
        };
        Object.keys(SLOT_LABELS).forEach((key) => {
            payload[key] = formData.getAll(key).filter(Boolean);
        });
        return {
            id: String(formData.get("activityId") || "").trim(),
            payload
        };
    }
    function editActivity(id) {
        const activity = state.personalActivities.find((item) => String(item.id) === String(id));
        if (!activity) {
            showToast("没有找到要编辑的活动", "error");
            return;
        }
        state.editingActivity = JSON.parse(JSON.stringify(activity));
        renderPersonalActivities();
    }
    async function deleteActivity(id) {
        const activity = state.personalActivities.find((item) => String(item.id) === String(id));
        if (!activity || !window.confirm(`确定删除“${activity.name}”？`)) {
            return;
        }
        await guard(async () => {
            await CityApi.deletePersonalActivity(id);
            await ensurePersonalActivities(true);
            renderPersonalActivities();
        }, "活动已删除");
    }
    function renderPublicActivities() {
        const filtered = filteredPublicActivities();
        app.innerHTML = `
            <section class="section">
                <div class="card-title">
                    <div>
                        <div class="eyebrow">公共活动库</div>
                        <h2>城市活动库</h2>
                        <p>公共活动按城市和区域组织，不再默认固定单一城市。</p>
                    </div>
                    <a class="btn primary" href="#/city/chat">去智能推荐</a>
                </div>
                ${renderPublicFilters()}
                <div class="library-summary">
                    <strong>${escapeHtml(filtered.length)}</strong>
                    <span class="muted">/ ${escapeHtml(state.publicActivities.length)} 个活动符合当前筛选</span>
                </div>
                <div id="publicActivityList">${renderActivityList(filtered, { emptyMessage: "没有匹配的活动。可以放宽城市、区域或活动类型。" })}</div>
            </section>
        `;
        ensurePublicActivityLibraryData();
    }
    function renderPublicFilters() {
        return `
            <form id="publicFilterForm" class="filter-panel" aria-label="筛选城市活动库">
                ${PUBLIC_FILTER_KEYS.map((key) => renderPublicFilterSelect(key)).join("")}
                <div class="filter-actions">
                    <button class="btn primary" type="submit">筛选</button>
                    <button class="btn ghost" type="button" data-action="reset-public-filters">重置</button>
                </div>
            </form>
        `;
    }
    function renderPublicFilterSelect(key) {
        const options = publicFilterOptions(key);
        const value = state.publicFilters[key] || "";
        return `
            <label class="filter-field">
                <span>${escapeHtml(SLOT_LABELS[key] || key)}</span>
                <select name="${escapeHtml(key)}">
                    <option value="">全部</option>
                    ${options.map((option) => `<option value="${escapeHtml(option)}" ${value === option ? "selected" : ""}>${escapeHtml(option)}</option>`).join("")}
                </select>
            </label>
        `;
    }
    function publicFilterOptions(key) {
        const dictionary = state.slotOptions && state.slotOptions[key] ? state.slotOptions[key] : [];
        const fromActivities = uniqueSlotValues(state.publicActivities, key);
        return dictionary.length ? dictionary : fromActivities;
    }
    function uniqueSlotValues(activities, key) {
        return [...new Set((activities || []).flatMap((activity) => slotValues(activity, key)))].filter(Boolean);
    }
    function filteredPublicActivities() {
        return state.publicActivities.filter((activity) => PUBLIC_FILTER_KEYS.every((key) => {
            const value = state.publicFilters[key];
            return !value || slotValues(activity, key).includes(value);
        }));
    }
    function applyPublicFilters(form) {
        const formData = new FormData(form);
        PUBLIC_FILTER_KEYS.forEach((key) => {
            state.publicFilters[key] = String(formData.get(key) || "");
        });
        renderPublicActivities();
    }
    function resetPublicFilters() {
        PUBLIC_FILTER_KEYS.forEach((key) => {
            state.publicFilters[key] = "";
        });
        renderPublicActivities();
    }
    async function ensurePublicActivities(force) {
        if (!force && state.publicActivities.length) {
            return;
        }

        // 先尝试从缓存获取
        if (!force) {
            const cached = cache.get('publicActivities');
            if (cached) {
                state.publicActivities = cached;
                if (currentRoute() === "/city/activities/public") {
                    renderPublicActivities();
                }
                return;
            }
        }

        // 显示骨架屏
        if (currentRoute() === "/city/activities/public") {
            const listEl = document.getElementById("publicActivityList");
            if (listEl) listEl.innerHTML = renderSkeletonCards(6);
        }

        try {
            state.publicActivities = await CityApi.listPublicActivities();
            cache.set('publicActivities', state.publicActivities);
            state.home.loaded = false;
            if (currentRoute() === "/city/activities/public") {
                renderPublicActivities();
            }
        } catch (error) {
            showToast(error.message || "城市活动库加载失败", "error");
        }
    }
    async function ensurePublicActivityLibraryData() {
        try {
            const needsOptions = !state.slotOptions;
            const needsActivities = !state.publicActivities.length;
            await Promise.all([ensureSlotOptions(), ensurePublicActivities()]);
            if (currentRoute() === "/city/activities/public" && (needsOptions || needsActivities)) {
                renderPublicActivities();
            }
        } catch (error) {
            showToast(error.message || "城市活动库加载失败", "error");
        }
    }
    function renderTraces() {
        const selected = state.traces.selected;
        app.innerHTML = `
            <section class="split">
                <div class="section">
                    <div class="card-title">
                        <div>
                            <h2>Trace 调试</h2>
                            <p>按时间范围或会话查询请求链路，查看意图修正、槽位和推荐事件。</p>
                        </div>
                    </div>
                    <form id="traceFilterForm" class="form-grid">
                        <div class="field">
                            <label>开始时间</label>
                            <input type="datetime-local" name="startAt" value="${escapeHtml(state.traces.filters.startAt)}" required>
                        </div>
                        <div class="field">
                            <label>结束时间</label>
                            <input type="datetime-local" name="endAt" value="${escapeHtml(state.traces.filters.endAt)}" required>
                        </div>
                        <div class="field">
                            <label>会话 ID（可选）</label>
                            <input name="sessionId" value="${escapeHtml(state.traces.filters.sessionId)}" placeholder="填写后按会话查询">
                        </div>
                        <div class="field">
                            <label>数量上限</label>
                            <input type="number" min="1" max="500" name="limit" value="${escapeHtml(state.traces.filters.limit)}">
                        </div>
                        <div class="field">
                            <label>标注状态</label>
                            <select name="onlyUnlabeled">
                                <option value="false" ${!state.traces.filters.onlyUnlabeled ? "selected" : ""}>全部</option>
                                <option value="true" ${state.traces.filters.onlyUnlabeled ? "selected" : ""}>仅未标注</option>
                            </select>
                        </div>
                        <div class="field">
                            <span>&nbsp;</span>
                            <button class="btn primary" type="submit">${state.traces.loading ? "查询中..." : "查询 Trace"}</button>
                        </div>
                    </form>
                    <div class="subtle-divider"></div>
                    ${renderTraceTable()}
                </div>
                <aside class="section">
                    ${selected ? renderTraceDetail(selected) : `<div class="empty">选择一条 Trace 查看详情和标注表单。</div>`}
                </aside>
            </section>
        `;
    }
    function renderTraceTable() {
        if (!state.traces.rows.length) {
            return `<div class="empty">暂无 Trace 数据。可以先在聊天页发起几轮对话。</div>`;
        }
        return `
            <div class="table-wrap">
                <table>
                    <thead>
                        <tr>
                            <th>Trace ID</th>
                            <th>会话</th>
                            <th>状态</th>
                            <th>事件</th>
                            <th>耗时</th>
                            <th>创建时间</th>
                            <th>标注</th>
                            <th>操作</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${state.traces.rows.map((row) => `
                            <tr>
                                <td>${escapeHtml(row.traceId)}</td>
                                <td>${escapeHtml(row.sessionId)}</td>
                                <td>${escapeHtml(row.status || "-")}</td>
                                <td>${escapeHtml(row.eventCount ?? "-")}</td>
                                <td>${row.durationMs ? `${escapeHtml(row.durationMs)} ms` : "-"}</td>
                                <td>${escapeHtml(row.createdAt || "-")}</td>
                                <td>${row.expectedIntent ? `<span class="badge">${escapeHtml(row.expectedIntent)}</span>` : "<span class=\"muted\">未标注</span>"}</td>
                                <td><button class="btn soft" data-action="select-trace" data-trace-id="${escapeHtml(row.traceId)}">查看</button></td>
                            </tr>
                        `).join("")}
                    </tbody>
                </table>
            </div>
        `;
    }
    function renderTraceDetail(trace) {
        return `
            <div class="card-title">
                <div>
                    <h3>Trace 详情</h3>
                    <p>${escapeHtml(trace.traceId)}</p>
                </div>
            </div>
            <div class="grid">
                <div>
                    <span class="badge">${escapeHtml(trace.status || "UNKNOWN")}</span>
                    <p class="muted">Session：${escapeHtml(trace.sessionId || "-")} · Events：${escapeHtml(trace.eventCount ?? "-")} · Duration：${escapeHtml(trace.durationMs ?? "-")} ms</p>
                </div>
                <details open>
                    <summary>Trace JSON</summary>
                    <pre class="json-box">${escapeHtml(safeJson(trace.traceJson))}</pre>
                </details>
                <form id="traceLabelForm" class="form-grid">
                    <input type="hidden" name="traceId" value="${escapeHtml(trace.traceId)}">
                    <div class="field">
                        <label>预期意图</label>
                        <select name="expectedIntent">
                            <option value="">不标注</option>
                            ${INTENTS.map((intent) => `<option value="${intent}" ${trace.expectedIntent === intent ? "selected" : ""}>${intent}</option>`).join("")}
                        </select>
                    </div>
                    <div class="field">
                        <label>澄清动作</label>
                        <select name="expectedClarifyAction">
                            <option value="">不标注</option>
                            <option value="ASK" ${trace.expectedClarifyAction === "ASK" ? "selected" : ""}>ASK</option>
                            <option value="READY" ${trace.expectedClarifyAction === "READY" ? "selected" : ""}>READY</option>
                        </select>
                    </div>
                    <div class="field full">
                        <label>预期槽位 JSON</label>
                        <textarea name="expectedSlots" placeholder='{"activityTime":["周六下午"],"activityType":["展览"]}'>${escapeHtml(safeJson(trace.expectedSlots))}</textarea>
                    </div>
                    <div class="field full">
                        <label>备注</label>
                        <textarea name="labelNote" placeholder="标注说明">${escapeHtml(trace.labelNote || "")}</textarea>
                    </div>
                    <div class="field full">
                        <button class="btn primary" type="submit">保存标注</button>
                    </div>
                </form>
            </div>
        `;
    }
    async function searchTraces(form) {
        const formData = new FormData(form);
        state.traces.filters = {
            startAt: formData.get("startAt"),
            endAt: formData.get("endAt"),
            sessionId: formData.get("sessionId").trim(),
            onlyUnlabeled: formData.get("onlyUnlabeled") === "true",
            limit: Number(formData.get("limit") || 50)
        };
        state.traces.loading = true;
        renderTraces();
        try {
            if (state.traces.filters.sessionId) {
                state.traces.rows = await CityApi.listSessionTraces(state.traces.filters.sessionId, state.traces.filters.limit);
            } else {
                state.traces.rows = await CityApi.listTraces({
                    startAt: state.traces.filters.startAt,
                    endAt: state.traces.filters.endAt,
                    onlyUnlabeled: state.traces.filters.onlyUnlabeled,
                    limit: state.traces.filters.limit
                });
            }
            state.traces.selected = state.traces.rows[0] || null;
        } catch (error) {
            showToast(error.message || "Trace 查询失败", "error");
        } finally {
            state.traces.loading = false;
            renderTraces();
        }
    }
    async function selectTrace(traceId) {
        await guard(async () => {
            state.traces.selected = await CityApi.getTrace(traceId);
            renderTraces();
        });
    }
    async function saveTraceLabel(form) {
        const formData = new FormData(form);
        const traceId = formData.get("traceId");
        const slotsText = formData.get("expectedSlots").trim();
        let expectedSlots = null;
        if (slotsText) {
            try {
                expectedSlots = JSON.parse(slotsText);
            } catch (error) {
                showToast("预期槽位必须是合法 JSON", "error");
                return;
            }
        }
        const payload = {
            expectedIntent: formData.get("expectedIntent") || null,
            expectedSlots,
            expectedClarifyAction: formData.get("expectedClarifyAction") || null,
            labelNote: formData.get("labelNote").trim()
        };
        await guard(async () => {
            await CityApi.labelTrace(traceId, payload);
            state.traces.selected = await CityApi.getTrace(traceId);
            const index = state.traces.rows.findIndex((row) => row.traceId === traceId);
            if (index >= 0) {
                state.traces.rows[index] = state.traces.selected;
            }
            renderTraces();
        }, "Trace 标注已保存");
    }
    function renderEvaluations() {
        app.innerHTML = `
            <section class="section">
                <div class="card-title">
                    <div>
                        <h2>评估报告</h2>
                        <p>基于已落库 Trace 生成规则评分、可选 LLM Judge 和反馈归因指标。</p>
                    </div>
                </div>
                <form id="evaluationForm" class="form-grid">
                    <div class="field">
                        <label>开始时间</label>
                        <input type="datetime-local" name="startAt" value="${escapeHtml(state.evaluation.form.startAt)}" required>
                    </div>
                    <div class="field">
                        <label>结束时间</label>
                        <input type="datetime-local" name="endAt" value="${escapeHtml(state.evaluation.form.endAt)}" required>
                    </div>
                    <div class="field">
                        <label>数量上限</label>
                        <input type="number" min="1" max="500" name="limit" value="${escapeHtml(state.evaluation.form.limit)}">
                    </div>
                    <div class="field">
                        <label>LLM Judge</label>
                        <select name="includeLlmJudge">
                            <option value="false" ${!state.evaluation.form.includeLlmJudge ? "selected" : ""}>关闭</option>
                            <option value="true" ${state.evaluation.form.includeLlmJudge ? "selected" : ""}>开启</option>
                        </select>
                    </div>
                    <div class="field full">
                        <button class="btn primary" type="submit">${state.evaluation.loading ? "评估中..." : "生成评估报告"}</button>
                    </div>
                </form>
            </section>
            <section class="section" style="margin-top: 18px;">
                ${renderEvaluationReport()}
            </section>
        `;
    }
    function renderEvaluationReport() {
        const report = state.evaluation.report;
        if (!report) {
            return `<div class="empty">暂无报告。选择时间范围后生成评估。</div>`;
        }
        return `
            <div class="grid three">
                ${statCard("Trace 总数", report.totalTraces, "本次纳入评估的请求数")}
                ${statCard("已标注", report.labeledTraces, "有人工标签的 Trace 数")}
                ${statCard("平均分", report.avgScore === null || report.avgScore === undefined ? "-" : Number(report.avgScore).toFixed(2), "综合评分")}
            </div>
            <div class="subtle-divider"></div>
            <div class="grid two">
                <div>
                    <h3>指标均值</h3>
                    ${renderMetrics(report.metricAverages)}
                </div>
                <div>
                    <h3>报告范围</h3>
                    <p class="muted">${escapeHtml(report.startAt)} 至 ${escapeHtml(report.endAt)}</p>
                </div>
            </div>
            <div class="subtle-divider"></div>
            ${renderEvaluationTable(report.traceResults || [])}
        `;
    }
    function renderMetrics(metrics) {
        const entries = Object.entries(metrics || {});
        if (!entries.length) {
            return `<div class="empty">暂无指标</div>`;
        }
        return `<div class="chips">${entries.map(([key, value]) => `<span class="chip selected">${escapeHtml(key)}：${Number(value).toFixed(2)}</span>`).join("")}</div>`;
    }
    function renderEvaluationTable(rows) {
        if (!rows.length) {
            return `<div class="empty">暂无 Trace 明细</div>`;
        }
        return `
            <div class="table-wrap">
                <table>
                    <thead>
                        <tr>
                            <th>Trace ID</th>
                            <th>会话</th>
                            <th>综合分</th>
                            <th>规则分</th>
                            <th>LLM 分</th>
                            <th>反馈分</th>
                            <th>指标 / 明细</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${rows.map((row) => `
                            <tr>
                                <td>${escapeHtml(row.traceId)}</td>
                                <td>${escapeHtml(row.sessionId)}</td>
                                <td>${formatScore(row.score)}</td>
                                <td>${formatScore(row.ruleScore)}</td>
                                <td>${formatScore(row.llmJudgeScore)}</td>
                                <td>${formatScore(row.userFeedbackScore)}</td>
                                <td>
                                    <details>
                                        <summary>查看 JSON</summary>
                                        <pre class="json-box">${escapeHtml(JSON.stringify({ metrics: row.metrics, detail: row.detail }, null, 2))}</pre>
                                    </details>
                                </td>
                            </tr>
                        `).join("")}
                    </tbody>
                </table>
            </div>
        `;
    }
    function formatScore(value) {
        return value === null || value === undefined ? "-" : Number(value).toFixed(2);
    }
    async function runEvaluation(form) {
        const formData = new FormData(form);
        state.evaluation.form = {
            startAt: formData.get("startAt"),
            endAt: formData.get("endAt"),
            limit: Number(formData.get("limit") || 50),
            includeLlmJudge: formData.get("includeLlmJudge") === "true"
        };
        state.evaluation.loading = true;
        renderEvaluations();
        try {
            state.evaluation.report = await CityApi.evaluate(state.evaluation.form);
        } catch (error) {
            showToast(error.message || "评估失败", "error");
        } finally {
            state.evaluation.loading = false;
            renderEvaluations();
        }
    }
    async function saveFeedback(button) {
        const card = button.closest('.activity-card');
        const action = button.dataset.actionValue;
        const oldText = button.textContent;
        const itemId = Number(button.dataset.itemId);
        if (!itemId) {
            showToast("没有找到活动 ID，暂时无法记录反馈", "error");
            return;
        }
        button.disabled = true;
        button.textContent = action === "ADOPT" ? "采纳中..." : "记录中...";

        // 添加反馈动画
        if (card && (action === 'LIKE' || action === 'ADOPT')) {
            card.classList.add('feedback-liked');
            setTimeout(() => card.classList.remove('feedback-liked'), 500);
        } else if (card && action === 'DISLIKE') {
            card.classList.add('feedback-disliked');
        }

        try {
            await guard(async () => {
                await CityApi.saveFeedback({
                    sessionId: button.dataset.sessionId || state.chat.sessionId,
                    itemId,
                    action: button.dataset.actionValue,
                    rating: button.dataset.actionValue === "DISLIKE" ? 2 : 5,
                    reason: ""
                });
            }, "反馈已记录");
            button.textContent = action === "ADOPT" ? "已采纳" : "已记录";

            // 如果是不喜欢，延迟后移除卡片
            if (card && action === 'DISLIKE') {
                setTimeout(() => {
                    if (card.parentElement) {
                        card.remove();
                    }
                }, 400);
            }
        } catch (error) {
            button.disabled = false;
            button.textContent = oldText;
        }
    }
    function handleClick(event) {
        const target = event.target.closest("[data-action]");
        if (!target) {
            return;
        }
        const action = target.dataset.action;
        if (action === "set-source") {
            state.chat.sourceMode = target.dataset.source;
            resetChat();
        } else if (action === "new-session") {
            resetChat();
        } else if (action === "quick-message") {
            const input = document.querySelector("#chatForm textarea[name=message], #homePromptForm textarea[name=message]");
            if (input) {
                input.value = target.dataset.message;
                input.focus();
            }
        } else if (action === "feedback") {
            saveFeedback(target);
        } else if (action === "new-activity") {
            state.editingActivity = emptyActivity();
            renderPersonalActivities();
        } else if (action === "edit-activity") {
            editActivity(target.dataset.id);
        } else if (action === "delete-activity") {
            deleteActivity(target.dataset.id);
        } else if (action === "cancel-edit") {
            state.editingActivity = null;
            renderPersonalActivities();
        } else if (action === "reset-public-filters") {
            resetPublicFilters();
        } else if (action === "select-trace") {
            selectTrace(target.dataset.traceId);
        } else if (action === "open-trace") {
            state.traces.filters.sessionId = "";
            navigate("/admin/traces");
            selectTrace(target.dataset.traceId);
        }
    }
    function handleSubmit(event) {
        const form = event.target;
        if (form.id === "chatForm") {
            event.preventDefault();
            submitChat(form);
        } else if (form.id === "homePromptForm") {
            event.preventDefault();
            handleHomePrompt(form);
        } else if (form.id === "activityForm") {
            event.preventDefault();
            if (!form.checkValidity()) {
                form.reportValidity();
                return;
            }
            saveActivity(form);
        } else if (form.id === "publicFilterForm") {
            event.preventDefault();
            applyPublicFilters(form);
        } else if (form.id === "traceFilterForm") {
            event.preventDefault();
            searchTraces(form);
        } else if (form.id === "traceLabelForm") {
            event.preventDefault();
            saveTraceLabel(form);
        } else if (form.id === "evaluationForm") {
            event.preventDefault();
            runEvaluation(form);
        }
    }
    function initUserField() {
        userIdInput.value = CityApi.setUserId(CityApi.getUserId());
        userIdInput.addEventListener("change", () => {
            CityApi.setUserId(userIdInput.value);

            // 清除缓存
            cache.clear();

            state.home.loaded = false;
            state.personalActivities = [];
            state.publicActivities = [];
            state.traces.rows = [];
            state.traces.selected = null;
            resetChat();
            showToast("用户 ID 已切换");
            render();
        });
    }
    window.addEventListener("hashchange", render);
    app.addEventListener("click", handleClick);
    app.addEventListener("submit", handleSubmit);
    initUserField();
    if (!location.hash) {
        navigate("/city");
    } else {
        render();
    }
})();


