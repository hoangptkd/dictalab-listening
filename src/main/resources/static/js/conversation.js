(() => {
    "use strict";
    const bridge = () => window.DictaLabListeningBridge;
    const root = () => document.querySelector("#conversation-content");
    const apiRoot = "/api/v1/conversation";
    let revision = 0, session = null, busy = false, draft = "", message = "";
    let playbackRevision = 0;
    let selection = {scenario: "cafe", difficulty: "A1"};
    let catalog = null, historyOffset = 0, historyFilter = "all", pageAbort = null, recommendationSource = null;
    const request = (url, options = {}) => bridge().api(url, pageAbort ? {...options, signal: pageAbort.signal} : Object.keys(options).length ? options : undefined);
    const preferenceKey = () => `dictalab:conversation-selection:${bridge().currentUser()?.id}`;
    function rememberSelection() {
        try { localStorage.setItem(preferenceKey(), JSON.stringify(selection)); } catch (_) { /* Optional preferences. */ }
    }
    function restoreSelection() {
        try {
            const value = JSON.parse(localStorage.getItem(preferenceKey()) || "null");
            if (value && ["A1", "A2", "B1"].includes(value.difficulty)) selection = value;
        } catch (_) { /* Keep defaults when storage is unavailable. */ }
    }
    const drafts = new Map();
    const escape = value => String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#039;");
    const draftPrefix = "dictalab:conversation-draft:";
    const draftKey = () => `${draftPrefix}${bridge().currentUser()?.id}:${session?.id}:${session?.turnNumber}`;
    function saveDraft(value) {
        if (!session || current()?.done) return;
        drafts.set(draftKey(), value);
        try {
            if (value) sessionStorage.setItem(draftKey(), JSON.stringify({value, expires: Date.now() + 86400000}));
            else sessionStorage.removeItem(draftKey());
        } catch (_) { /* Keep the in-memory draft when browser storage is unavailable. */ }
    }
    function readDraft() {
        try {
            const saved = JSON.parse(sessionStorage.getItem(draftKey()) || "null");
            if (saved?.expires > Date.now()) return saved.value;
            sessionStorage.removeItem(draftKey());
        } catch (_) { /* Memory fallback also works in private browsing. */ }
        return drafts.get(draftKey()) || "";
    }
    function clearDrafts() {
        drafts.clear(); draft = ""; session = null;
        try {
            Object.keys(sessionStorage).filter(key => key.startsWith(draftPrefix)).forEach(key => sessionStorage.removeItem(key));
        } catch (_) { /* Storage is optional. */ }
    }
    const dateLabel = value => {
        const date = new Date(value);
        return Number.isNaN(date.getTime()) ? "" : date.toLocaleString("vi-VN", {day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit"});
    };
    const path = action => `${apiRoot}/sessions/${encodeURIComponent(session.id)}/${action}`;
    const post = (action, body) => request(path(action), {method: "POST", body,
        ...(action === "next" ? {timeoutMs: session.generationTimeoutMs || 60000} : {})});
    const current = () => session?.turns?.at(-1);
    function stopAudio() { playbackRevision++; bridge().stopRecordedAudio(); }

    function leave() {
        if (session) saveDraft(root()?.querySelector("textarea")?.value ?? draft);
        revision++; busy = false;
        stopAudio();
        pageAbort?.abort(); pageAbort = null;
    }

    async function show(id) {
        leave(); session = null; draft = ""; message = "";
        if (typeof AbortController !== "undefined") pageAbort = new AbortController();
        root().closest?.(".conversation-browser")?.classList.remove("has-session");
        const token = revision;
        root().innerHTML = '<p role="status">Đang tải phiên nghe…</p>';
        try {
            const data = await request(id ? `${apiRoot}/sessions/${encodeURIComponent(id)}` : apiRoot);
            if (token !== revision) return;
            if (id) {
                session = data; selection = {scenario: data.scenario, difficulty: data.difficulty}; draft = readDraft(); render();
                if (!session.turnNumber && !session.completed) await action("next");
            }
            else { restoreSelection(); catalog = data; historyOffset = 0; recommendationSource = null; renderCatalog(data); }
        } catch (error) {
            if (token !== revision) return;
            root().innerHTML = `<p role="alert">${escape(error.message)}</p><button type="button" class="secondary-button">Thử tải lại</button>`;
            root().querySelector("button").addEventListener("click", () => show(id));
        }
    }

    function renderCatalog(data) {
        const scenarios = data.scenarios || {}, groups = data.scenarioGroups || [];
        if (!scenarios[selection.scenario]) selection.scenario = Object.keys(scenarios)[0];
        const selectedGroup = groups.find(group => group.scenarios.includes(selection.scenario));
        const recommendation = data.progress?.completedSessions ? data.progress.recommendation : null;
        const continuing = data.sessions.find(item => !item.completed);
        root().innerHTML = `<div class="conversation-setup">
            <section class="conversation-card conversation-start">
            ${continuing ? `<p class="conversation-continue"><a href="/conversation/${escape(continuing.id)}">Tiếp tục ${escape(scenarios[continuing.scenario])} · ${escape(continuing.difficulty)} →</a></p>` : ""}
            ${recommendation ? `<div class="conversation-recommendation"><button type="button" class="secondary-button" id="conversation-recommended">${recommendation.due ? "Ôn lại" : "Gợi ý"}: ${escape(scenarios[recommendation.scenario])} · ${escape(recommendation.difficulty)}</button></div>` : ""}
            <form id="conversation-setup-form" aria-label="Chọn bài luyện nghe">
                ${groups.length ? `<label for="conversation-group">Nhóm tình huống</label><select id="conversation-group">${groups.map(group => `<option value="${escape(group.id)}" ${group.id === selectedGroup?.id ? "selected" : ""}>${escape(group.label)}</option>`).join("")}</select>` : ""}
                <label for="conversation-scenario">Tình huống</label>
                <select id="conversation-scenario" name="scenario">${Object.entries(scenarios).filter(([id]) => !selectedGroup || selectedGroup.scenarios.includes(id)).map(([id, label]) => `<option value="${escape(id)}">${escape(label)}</option>`).join("")}</select>
                <label for="conversation-level">Độ khó</label>
                <select id="conversation-level" name="difficulty"><option value="A1">A1 · Câu ngắn, thông tin trực tiếp</option><option value="A2">A2 · Nối chi tiết, theo dõi thay đổi</option><option value="B1">B1 · Ý chính, lý do và điều kiện</option></select>
                <p id="conversation-status" role="status">${data.enabled ? "" : "Luyện nghe tình huống chưa được bật trên máy chủ."}</p>
                <button class="primary-button" ${data.enabled ? "" : "disabled"}>Bắt đầu nghe →</button>
            </form></section>
            <section class="conversation-card conversation-recent"><h2>Lịch sử</h2><select id="conversation-history-filter" aria-label="Lọc lịch sử trong trang này"><option value="all">Tất cả</option><option value="active">Đang luyện</option><option value="completed">Đã hoàn thành</option></select><div id="conversation-history-list"></div><div class="conversation-history-pages" id="conversation-history-pages"><button type="button" class="secondary-button" id="conversation-history-prev">Trang trước</button><span id="conversation-history-page"></span><button type="button" class="secondary-button" id="conversation-history-next">Trang sau</button></div><p id="conversation-history-status" role="status"></p></section></div>`;
        root().querySelector("#conversation-scenario").value = selection.scenario;
        root().querySelector("#conversation-level").value = selection.difficulty;
        root().querySelector("#conversation-group")?.addEventListener("change", event => {
            selection.scenario = groups.find(group => group.id === event.target.value).scenarios[0]; recommendationSource = null; rememberSelection(); renderCatalog(data);
            root().querySelector("#conversation-group")?.focus({preventScroll: true});
        });
        root().querySelector("#conversation-scenario").addEventListener("change", event => { selection.scenario = event.target.value; recommendationSource = null; rememberSelection(); });
        root().querySelector("#conversation-level").addEventListener("change", event => { stopAudio(); selection.difficulty = event.target.value; recommendationSource = null; rememberSelection(); });
        root().querySelector("#conversation-recommended")?.addEventListener("click", () => {
            selection = {scenario: recommendation.scenario, difficulty: recommendation.difficulty}; recommendationSource = recommendation.sourceSessionId || null; rememberSelection(); renderCatalog(data);
            root().querySelector("#conversation-scenario").focus({preventScroll: true});
        });
        root().querySelector("#conversation-history-filter").value = historyFilter;
        root().querySelector("#conversation-history-filter").addEventListener("change", event => { historyFilter = event.target.value; renderHistory(data.sessions, data.hasMore ?? data.sessions.length === 12); });
        root().querySelector("#conversation-history-prev").addEventListener("click", () => loadHistory(-12));
        root().querySelector("#conversation-history-next").addEventListener("click", () => loadHistory(12));
        renderHistory(data.sessions, data.hasMore ?? data.sessions.length === 12);
        root().querySelector("form").addEventListener("submit", async event => {
            event.preventDefault(); if (busy) return;
            const token = revision, form = event.currentTarget, values = new FormData(form);
            selection = {scenario: values.get("scenario"), difficulty: values.get("difficulty")}; rememberSelection();
            busy = true; form.querySelector('button:not([type="button"])').disabled = true;
            form.querySelector("[role=status]").textContent = "Đang tạo phiên…";
            try {
                const data = await request(`${apiRoot}/sessions`, {method: "POST", body: {...Object.fromEntries(values), ...(recommendationSource ? {sourceSessionId: recommendationSource} : {})}});
                if (token === revision) bridge().navigate("conversation", {sessionId: data.id});
            } catch (error) {
                if (token === revision) {
                    form.querySelector("[role=status]").textContent = error.message;
                    form.querySelector('button:not([type="button"])').disabled = false;
                }
            } finally { if (token === revision) busy = false; }
        });
        bindSessionLinks();
    }

    function bindSessionLinks() {
        root().querySelectorAll('a[href^="/conversation/"]').forEach(link => {
            if (link.dataset.conversationBound) return;
            link.dataset.conversationBound = "true";
            link.addEventListener("click", event => {
            if (event.button || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
            event.preventDefault(); bridge().navigate("conversation", {sessionId: link.getAttribute("href").split("/").at(-1)});
            });
        });
    }

    function renderHistory(items, hasMore) {
        const visible = items.filter(item => historyFilter === "all" || (historyFilter === "completed") === !!item.completed);
        root().querySelector("#conversation-history-list").innerHTML = visible.length ? `<ul>${visible.map(item => `<li><a href="/conversation/${escape(item.id)}"><span>${escape(catalog.scenarios[item.scenario])} · ${escape(item.difficulty)}<small>${escape(dateLabel(item.created_at))} · ${item.turnNumber || 0}/4 câu đã mở</small></span><span>${item.completed ? "Xem kết quả" : "Tiếp tục"} →</span></a></li>`).join("")}</ul>` : "<p>Chưa có phiên phù hợp trong trang này.</p>";
        root().querySelector("#conversation-history-prev").disabled = historyOffset === 0;
        root().querySelector("#conversation-history-next").disabled = !hasMore;
        root().querySelector("#conversation-history-page").textContent = `Trang ${historyOffset / 12 + 1}`;
        root().querySelector("#conversation-history-pages").hidden = historyOffset === 0 && !hasMore;
        bindSessionLinks();
    }

    async function loadHistory(delta) {
        if (busy) return;
        const token = revision, offset = Math.max(0, historyOffset + delta); busy = true;
        root().querySelector("#conversation-history-status").textContent = "Đang tải lịch sử…";
        root().querySelector("#conversation-history-prev").disabled = true; root().querySelector("#conversation-history-next").disabled = true;
        try {
            const data = await request(`${apiRoot}/sessions?offset=${offset}&limit=12`);
            if (token !== revision) return;
            historyOffset = offset; catalog.sessions = data.sessions; catalog.hasMore = data.hasMore;
            renderHistory(data.sessions, data.hasMore); root().querySelector("#conversation-history-status").textContent = "";
        } catch (error) {
            if (token === revision) { renderHistory(catalog.sessions, catalog.hasMore ?? catalog.sessions.length === 12); root().querySelector("#conversation-history-status").textContent = error.message; }
        } finally { if (token === revision) busy = false; }
    }

    function render() {
        const turn = current(), attempts = turn?.attempts || [], pending = attempts.at(-1) && !attempts.at(-1).evaluation;
        const helpOpen = root().querySelector("#conversation-assistance")?.open;
        const openDetails = [...root().querySelectorAll("details[open][id]")].map(node => node.id);
        const reportDrafts = [...root().querySelectorAll(".conversation-report textarea")].map(node => [node.id, node.value]);
        root().closest?.(".conversation-browser")?.classList.add("has-session");
        if (pending) draft = attempts.at(-1).answerVi;
        root().innerHTML = `<div class="conversation-session-layout ${session.completed ? "" : "is-active-session"}"><div class="conversation-session-main"><a href="/conversation" id="conversation-back">← Chọn tình huống</a>${session.completed ? "" : '<button type="button" class="text-button" data-action="pause">Lưu và tạm dừng</button>'}<div class="conversation-session-header">
            <div><span>${escape(session.scenarioVi)} · ${escape(session.difficulty)}</span>
            ${!session.completed ? `<h2 id="conversation-task">${escape(turn?.prompt.taskVi || "Đã tạo phiên luyện tập")}</h2>` : ""}</div></div>
            ${session.practiceFocus ? `<p class="conversation-focus">${escape(session.practiceFocus)}</p>` : ""}
            ${turn?.warmup ? '<p class="conversation-focus">Câu khởi động · Các câu sau theo mức đã chọn.</p>' : ""}
            <div class="conversation-progress" role="group" aria-label="Tiến độ phiên">${[1, 2, 3, 4].map(n => `<span ${n === session.turnNumber && !session.completed ? 'aria-current="step"' : ""} class="${n <= session.turnNumber ? "is-current" : ""}">${session.turns[n - 1]?.done ? "✓ " : ""}${n === 4 ? "Câu mới" : `Bước ${n}`}</span>`).join("")}</div>
            ${session.completed ? summary(session.summary) + reviewHistory() : `<div class="conversation-workspace">
                <section class="conversation-card conversation-listen" aria-labelledby="conversation-task">
                    ${turn ? `<p>${escape(turn.prompt.questionVi)}</p>
                        <button type="button" class="primary-button" data-action="play">${turn.plays ? "Nghe lại" : "Nghe câu này"}</button>
                        <div class="conversation-audio-tools"><button type="button" class="secondary-button" data-action="slow">Nghe chậm · 0,8×</button>
                        <button type="button" class="secondary-button" data-action="toggle-audio" disabled>Tạm dừng audio</button><button type="button" class="text-button" data-action="stop">Dừng audio</button></div>
                        <div class="conversation-audio-timeline"><input id="conversation-audio-seek" aria-label="Vị trí audio" type="range" min="0" max="0" step="0.1" value="0" disabled><output id="conversation-audio-time">0:00 / --:--</output></div>
                        <details id="conversation-assistance" class="ui-disclosure" ${helpOpen ? "open" : ""}><summary>Hỗ trợ</summary><p>Kết quả ghi nhận hỗ trợ đã dùng.</p>
                        <div class="conversation-help"><button type="button" class="text-button" data-action="clarify">${turn.clarification ? "Nghe bản làm rõ" : "Nhờ AI nói rõ hơn"}</button>
                        <button type="button" class="text-button" data-action="transcript" aria-expanded="${!!turn.prompt.utteranceEn}" aria-controls="conversation-transcript">Mở transcript</button>
                        <button type="button" class="text-button" data-action="translation" aria-expanded="${!!turn.prompt.translationVi}" aria-controls="conversation-translation">Mở bản dịch</button></div>
                        <div id="conversation-transcript" ${turn.prompt.utteranceEn ? "" : "hidden"}><p lang="en">${escape(turn.prompt.utteranceEn)}</p>${turn.clarification?.utteranceEn ? `<p lang="en">${escape(turn.clarification.utteranceEn)}</p>` : ""}</div>
                        <div id="conversation-translation" ${turn.prompt.translationVi ? "" : "hidden"}><p>${escape(turn.prompt.translationVi)}</p>${turn.clarification?.translationVi ? `<p>${escape(turn.clarification.translationVi)}</p>` : ""}</div>
                        ${!turn.done && !pending ? '<p>Mở lời giải sẽ kết thúc câu này, không tính là tự hiểu.</p><button type="button" class="text-button" data-action="review">Xem lời giải</button>' : ""}
                        </details>` : `<p>Phiên này đã được lưu. Bạn không cần tạo phiên mới.</p>
                        <p id="conversation-status" role="status">${escape(message || "Câu nghe đầu tiên chưa sẵn sàng.")}</p>
                        <button type="button" class="primary-button" data-action="next" aria-describedby="conversation-status">${message ? "Thử lại" : "Tạo câu nghe đầu tiên"}</button>`}
                </section>
                <section class="conversation-card conversation-reply" aria-labelledby="conversation-reply-heading">
                    <h2 id="conversation-reply-heading">Trả lời bằng tiếng Việt</h2>
                    ${turn && !turn.done ? `<p class="conversation-feedback-count">Đã nắm ${turn.capturedFacts || 0}/${turn.totalFacts || 2} ý · Còn ${3 - attempts.length} lượt bổ sung</p>` : ""}
                    <div class="conversation-chat">${attempts.map((a, i) => `${i < attempts.length - 1 ? `<details class="ui-disclosure"><summary>Lượt trả lời ${i + 1}</summary>` : ""}<div class="conversation-bubble learner"><span>Bạn</span><p>${escape(a.answerVi)}</p></div>
                        ${a.evaluation ? `<div class="conversation-bubble coach"><span>AI Coach</span><p>${escape(a.evaluation.feedbackVi)}</p>${!turn.done && a.evaluation.clarificationVi ? `<p><strong>${escape(a.evaluation.clarificationVi)}</strong></p>` : ""}</div>` : ""}${i < attempts.length - 1 ? "</details>" : ""}`).join("")}</div>
                    ${turn && !turn.done ? `<form id="conversation-answer-form"><label for="conversation-answer">Trả lời bằng tiếng Việt</label>
                        <textarea id="conversation-answer" name="answerVi" rows="4" maxlength="1500" required aria-describedby="conversation-status" ${pending ? "readonly" : ""}>${escape(draft)}</textarea>
                        <div class="conversation-answer-actions"><button class="primary-button">${pending ? "Thử chấm lại câu đã lưu" : attempts.length ? "Gửi ý bổ sung →" : "Gửi câu trả lời →"}</button></div></form>`
                        : turn ? `${reviewCard(turn, session.turnNumber, true)}<button type="button" class="primary-button" data-action="next">${session.turnNumber === 3 ? "Thử câu mới để kiểm tra lại →" : "Bước tiếp theo →"}</button>${session.turnNumber === 3 ? '<p>Không tạo được câu mới hoặc muốn dừng tại đây?</p><button type="button" class="text-button" data-action="finish">Kết thúc với ba câu · Chưa kiểm tra vận dụng</button>' : ""}`
                        : "<p>Bắt đầu bằng việc nghe. AI sẽ phản hồi sau khi bạn gửi ý hiểu.</p>"}
                </section></div>${reviewHistory(true)}`}
            ${turn || session.completed ? `<p id="conversation-status" role="status" aria-live="polite" aria-atomic="true">${escape(message)}</p>` : ""}</div></div>`;
        root().querySelector("#conversation-back").addEventListener("click", event => { if (event.button || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return; event.preventDefault(); bridge().navigate("conversation"); });
        root().querySelectorAll("[data-action]").forEach(button => button.addEventListener("click", () => action(button.dataset.action)));
        openDetails.forEach(id => { const detail = root().querySelector(`#${id}`); if (detail) detail.open = true; });
        reportDrafts.forEach(([id, value]) => { const input = root().querySelector(`#${id}`); if (input) input.value = value; });
        root().querySelector("#conversation-audio-seek")?.addEventListener("input", event => { bridge().seekRecordedAudio?.(Number(event.target.value)); updateAudioControls(); });
        const textarea = root().querySelector("textarea");
        textarea?.addEventListener("input", () => { draft = textarea.value; saveDraft(draft); textarea.setCustomValidity(""); });
        root().querySelector("#conversation-answer-form")?.addEventListener("submit", event => {
            event.preventDefault(); if (busy) return;
            draft = textarea.value;
            if (!draft.trim()) { textarea.setCustomValidity("Hãy ghi điều bạn nghe được."); textarea.reportValidity(); return; }
            textarea.setCustomValidity("");
            stopAudio();
            const attempt = pending ? attempts.at(-1) : {requestId: crypto.randomUUID(), answerVi: draft};
            mutate(() => post("answer", {turnNumber: session.turnNumber, requestId: attempt.requestId, answerVi: attempt.answerVi}), "AI đang đọc ý hiểu của bạn…", true);
        });
        updateAudioControls();
    }

    function updateAudioControls() {
        if (!bridge().getRecordedAudioState || !root()?.querySelector("#conversation-audio-seek")) return;
        const state = bridge().getRecordedAudioState(), duration = Number.isFinite(state.duration) ? state.duration : 0;
        const time = seconds => `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, "0")}`;
        const seek = root().querySelector("#conversation-audio-seek");
        seek.max = duration; seek.value = state.currentTime || 0; seek.disabled = !duration;
        root().querySelector("#conversation-audio-time").textContent = `${time(state.currentTime || 0)} / ${duration ? time(duration) : "--:--"}`;
        const toggle = root().querySelector('[data-action="toggle-audio"]');
        toggle.disabled = !duration; toggle.textContent = state.paused ? "Tiếp tục audio" : "Tạm dừng audio";
    }

    function trackAudio(token, playbackToken) {
        if (!bridge().getRecordedAudioState || token !== revision || playbackToken !== playbackRevision) return;
        updateAudioControls();
        setTimeout(() => trackAudio(token, playbackToken), 250);
    }

    function reviewCard(turn, number, open = false) {
        if (!turn.review) return "";
        const correct = turn.capturedFacts === turn.totalFacts;
        const labels = {CAPTURED: "Đã hiểu", PARTIAL: "Hiểu một phần", INCORRECT: "Cần sửa", MISSED: "Chưa trả lời", UNCERTAIN: "Chưa rõ ý"};
        return `<details id="conversation-review-${number}" class="ui-disclosure conversation-review" ${open ? "open" : ""}><summary>Câu ${number} · ${correct ? "Đã nắm đủ ý" : "Đối chiếu và học lại"}</summary>
            <p>${escape(turn.prompt.questionVi)}</p><ul class="conversation-review-facts">${turn.review.facts.map(f => `<li><span>${escape(labels[f.status] || "Chưa rõ ý")}</span><strong>${escape(f.textVi)}</strong><q lang="en">${escape(f.evidenceEn)}</q></li>`).join("")}</ul>
            <button type="button" class="secondary-button" data-action="replay-${number}">↻ Nghe lại câu ${number}</button>
            <details id="conversation-review-script-${number}" class="conversation-review-script"><summary>Toàn bộ transcript và bản dịch</summary><p lang="en">${escape(turn.review.utteranceEn)}</p><p>${escape(turn.review.translationVi)}</p></details>
            ${turn.attempts?.length ? `<details id="conversation-review-answers-${number}"><summary>Câu trả lời của bạn</summary>${turn.attempts.map(a => `<p>${escape(a.answerVi)}</p>${a.evaluation ? `<p>${escape(a.evaluation.feedbackVi)}</p>` : ""}`).join("")}</details><details id="conversation-report-panel-${number}" class="conversation-report"><summary>Tôi nghĩ AI chấm chưa đúng</summary><p>Góp ý được lưu để kiểm tra, không tự thay đổi kết quả. Không nhập thông tin nhạy cảm.</p><label for="conversation-report-${number}">Bạn thấy ý nào chưa được chấm phù hợp?</label><textarea id="conversation-report-${number}" rows="2" maxlength="1000"></textarea><button type="button" class="secondary-button" data-action="report-${number}">Gửi góp ý câu ${number}</button><p id="conversation-report-status-${number}" role="status"></p></details>` : ""}</details>`;
    }

    function reviewHistory(previousOnly = false) {
        const turns = session.turns.filter((t, i) => t.done && (!previousOnly || i < session.turns.length - 1));
        return turns.length ? `<section class="conversation-review-history"><h3>Xem lại từng câu</h3>${turns.map(t => reviewCard(t, session.turns.indexOf(t) + 1)).join("")}</section>` : "";
    }

    function summary(data) {
        return `<section class="conversation-card conversation-summary"><h2>${session.transferSkipped ? "Đã lưu kết quả ba câu" : "Hoàn thành phiên nghe"}</h2>
            <p>Tự hiểu: ${data.independentFacts}/${data.totalFacts} ý · Có hỗ trợ: ${data.assistedFacts} ý</p>
            <div class="conversation-summary-grid"><div><h3>Đã hiểu được</h3><ul>${data.understoodVi.map(t => `<li>${escape(t)}</li>`).join("") || "<li>Phiên này chưa đủ bằng chứng cho ý hiểu đầy đủ.</li>"}</ul></div>
            <div><h3>Cần luyện tiếp</h3><ul>${data.practiceVi.map(t => `<li>${escape(t)}</li>`).join("") || "<li>Thử thông tin dài hơn ở lần tiếp theo.</li>"}</ul></div></div>
            <p class="conversation-transfer">${escape(data.transferVi)}</p>
            ${data.practiceVi.length ? '<button type="button" class="primary-button" data-action="targeted">Luyện đúng điểm cần cải thiện →</button>' : ""}
            <button type="button" class="${data.practiceVi.length ? "secondary-button" : "primary-button"}" data-action="restart">Luyện tiếp · ${escape(session.difficulty)}</button><div class="conversation-next-level" role="group" aria-label="Độ khó cho phiên tiếp theo">${session.difficulty !== "A1" ? '<button type="button" class="text-button" data-action="easier">Phiên sau dễ hơn</button>' : ""}${session.difficulty !== "B1" ? '<button type="button" class="text-button" data-action="harder">Phiên sau khó hơn</button>' : ""}</div></section>`;
    }

    async function mutate(operation, status, answerOperation = false, focusAction = null) {
        if (busy) return null;
        const token = revision, oldDraftKey = draftKey(), oldTurn = session.turnNumber; busy = true; message = status;
        root().setAttribute("aria-busy", "true");
        const textarea = root().querySelector("textarea");
        if (textarea) textarea.readOnly = true;
        root().querySelectorAll("button").forEach(b => { if (!["stop", "pause"].includes(b.dataset.action)) b.disabled = true; });
        root().querySelector("#conversation-status").textContent = status;
        if (!current()) root().querySelector('[data-action="next"]').textContent = "Đang tạo câu nghe…";
        try {
            const data = await operation();
            if (token !== revision) return null;
            session = data; message = answerOperation ? current()?.attempts?.at(-1)?.evaluation?.feedbackVi || "Câu trả lời đã được lưu." : "";
            if (answerOperation || data.turnNumber !== oldTurn || current()?.done) {
                draft = ""; drafts.delete(oldDraftKey);
                try { sessionStorage.removeItem(oldDraftKey); } catch (_) { /* Optional storage. */ }
            }
            render();
            if (focusAction) (root().querySelector(`[data-action="${focusAction}"]`) || root().querySelector('[data-action="next"]') || root().querySelector('[data-action="restart"]'))?.focus({preventScroll: true});
            else if (answerOperation || data.turnNumber !== oldTurn) {
                const focus = root().querySelector("#conversation-task");
                focus?.setAttribute?.("tabindex", "-1"); focus?.focus({preventScroll: true});
            }
            return data;
        } catch (error) {
            if (token !== revision) return null;
            // A server-side pending answer may have been saved even when the provider failed.
            try {
                const data = await request(`${apiRoot}/sessions/${encodeURIComponent(session.id)}`);
                if (token !== revision) return null;
                if (data.turnNumber !== oldTurn || data.completed || data.turns.at(-1)?.done) {
                    draft = ""; drafts.delete(oldDraftKey);
                    try { sessionStorage.removeItem(oldDraftKey); } catch (_) { /* Optional storage. */ }
                }
                session = data;
            } catch (_) { /* Preserve the visible draft if fetching the saved session also fails. */ }
            if (token === revision) {
                // A lost response may hide a successfully saved first task; do not ask to generate it again.
                message = oldTurn === 0 && session.turnNumber > 0 ? ""
                    : !current() ? `Chưa tải được câu nghe đầu tiên. ${error.message}` : error.message;
                render();
                if (!current()) root().querySelector('[data-action="next"]')?.focus({preventScroll: true});
                else if (focusAction) root().querySelector(`[data-action="${focusAction}"]`)?.focus({preventScroll: true});
            }
            return null;
        } finally {
            if (token === revision) { busy = false; root().removeAttribute("aria-busy"); }
        }
    }

    async function action(kind) {
        if (kind === "stop") { stopAudio(); updateAudioControls(); root().querySelector("#conversation-status").textContent = "Đã dừng phát hoặc chờ phát. Câu nghe và câu trả lời vẫn được giữ."; return; }
        if (kind === "pause") { saveDraft(root().querySelector("#conversation-answer")?.value ?? draft); bridge().navigate("conversation"); return; }
        if (kind === "toggle-audio") {
            if (bridge().getRecordedAudioState?.().paused) bridge().resumeRecordedAudio?.(); else bridge().pauseRecordedAudio?.();
            updateAudioControls(); return;
        }
        if (["restart", "easier", "harder"].includes(kind)) {
            const levels = ["A1", "A2", "B1"], index = levels.indexOf(session.difficulty);
            selection = {scenario: session.scenario, difficulty: levels[Math.max(0, Math.min(2, index + (kind === "harder" ? 1 : kind === "easier" ? -1 : 0)))]};
            rememberSelection();
            bridge().navigate("conversation"); return;
        }
        if (busy) return;
        if (kind.startsWith("report-")) {
            const number = Number(kind.slice(7)), input = root().querySelector(`#conversation-report-${number}`), status = root().querySelector(`#conversation-report-status-${number}`);
            if (!input.value.trim()) { status.textContent = "Hãy mô tả ngắn ý cần kiểm tra."; input.focus(); return; }
            const token = revision, button = root().querySelector(`[data-action="${kind}"]`); button.disabled = true; status.textContent = "Đang lưu góp ý…";
            try {
                await post("report", {turnNumber: number, reasonVi: input.value.trim()});
                if (token === revision) { status.textContent = "Đã lưu góp ý để kiểm tra. Cảm ơn bạn."; input.readOnly = true; }
            } catch (error) { if (token === revision) { status.textContent = error.message; button.disabled = false; } }
            return;
        }
        if (kind === "targeted") {
            const data = await mutate(() => request(`${apiRoot}/sessions`, {method: "POST", body: {
                scenario: session.scenario, difficulty: session.difficulty, sourceSessionId: session.id
            }}), "Đang tạo phiên luyện theo mục tiêu…");
            if (data) bridge().navigate("conversation", {sessionId: data.id});
            return;
        }
        if (kind === "next") { stopAudio(); draft = ""; await mutate(() => post("next", {turnNumber: session.turnNumber}), "AI đang chuẩn bị nhiệm vụ nghe…"); return; }
        if (["transcript", "translation", "review", "finish"].includes(kind)) {
            await mutate(() => post("help", {turnNumber: session.turnNumber, kind}), "Đang mở hỗ trợ…", false, kind); return;
        }
        const token = revision;
        const playbackToken = ++playbackRevision;
        if (kind === "clarify" && !current().clarification) {
            if (!await mutate(() => post("help", {turnNumber: session.turnNumber, kind: "clarify"}), "AI đang nói lại rõ hơn…", false, kind)) return;
        }
        if (token !== revision || playbackToken !== playbackRevision) return;
        const slow = kind === "slow", variant = kind === "clarify" ? "clarify" : "original";
        const input = {turnNumber: kind.startsWith("replay-") ? Number(kind.slice(7)) : session.turnNumber, variant, slow};
        let data = await mutate(() => post("play", input), "Đang chuẩn bị giọng nói…", false, kind);
        if (!data || token !== revision || playbackToken !== playbackRevision) return;
        try {
            if (!data.audioUrl) {
                root().querySelector("#conversation-status").textContent = "Giọng nói đang được chuẩn bị. Bạn vẫn có thể nhập câu trả lời.";
                data = await waitForAudio(data.audioStatus, input, token, playbackToken, kind);
                if (!data || token !== revision || playbackToken !== playbackRevision) return;
            }
            root().querySelector("#conversation-status").textContent = slow ? "Đang nghe chậm · 0,8×" : "Đang phát tiếng Anh…";
            const playing = bridge().playRecordedAudio(data.audioUrl, 0, null, slow ? 0.8 : 1);
            trackAudio(token, playbackToken);
            await playing;
            updateAudioControls();
            if (token === revision && playbackToken === playbackRevision) root().querySelector("#conversation-status").textContent = "Bạn có thể nghe lại hoặc gửi điều mình hiểu.";
        } catch (error) {
            if (token === revision && playbackToken === playbackRevision && error.name !== "AbortError") root().querySelector("#conversation-status").textContent = error.message;
        } finally {
            if (playbackToken === playbackRevision) playbackRevision++;
        }
    }

    async function waitForAudio(status, input, token, playbackToken, focusAction) {
        for (let attempt = 0; attempt < 90; attempt++) {
            if (!["PENDING", "RUNNING"].includes(status)) {
                throw new Error(status === "EXHAUSTED" ? "Đã hết lượt thử tạo giọng nói cho câu này. Bạn có thể mở hỗ trợ hoặc tiếp tục phiên khác."
                    : status === "BUSY" ? "Hàng đợi giọng nói đang đầy. Hãy thử nghe lại sau."
                    : "Chưa tạo được giọng nói. Hệ thống không tự gọi lại; hãy bấm nghe để chủ động thử lại.");
            }
            await new Promise(resolve => setTimeout(resolve, 1000));
            if (token !== revision || playbackToken !== playbackRevision) return null;
            const result = await request(`${path("audio-status")}?turnNumber=${input.turnNumber}&variant=${encodeURIComponent(input.variant)}`);
            if (token !== revision || playbackToken !== playbackRevision) return null;
            status = result.status;
            if (status === "READY") {
                // Record playback/support only once audio exists, not once per status poll.
                return mutate(() => post("play", input), "Giọng nói đã sẵn sàng…", false, focusAction);
            }
        }
        throw new Error("Giọng nói vẫn đang chờ. Hãy bấm nghe lại sau; công việc đã được lưu.");
    }

    window.DictaLabConversation = {show, leave, clearDrafts};
})();
