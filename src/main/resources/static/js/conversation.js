(() => {
    "use strict";
    const bridge = () => window.DictaLabListeningBridge;
    const root = () => document.querySelector("#conversation-content");
    const apiRoot = "/api/v1/conversation";
    let revision = 0, session = null, busy = false, draft = "", message = "";
    let playbackRevision = 0;
    let selection = {scenario: "cafe", difficulty: "A1"};
    const drafts = new Map();
    const escape = value => String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#039;");
    const draftKey = () => `${bridge().currentUser()?.id}:${session?.id}`;
    const path = action => `${apiRoot}/sessions/${encodeURIComponent(session.id)}/${action}`;
    const post = (action, body) => bridge().api(path(action), {method: "POST", body});
    const current = () => session?.turns?.at(-1);
    function stopAudio() { playbackRevision++; bridge().stopRecordedAudio(); }

    function leave() {
        if (session) drafts.set(draftKey(), root()?.querySelector("textarea")?.value ?? draft);
        revision++; busy = false;
        stopAudio();
    }

    async function show(id) {
        leave(); session = null; draft = ""; message = "";
        const token = revision;
        root().innerHTML = '<p role="status">Đang tải hội thoại…</p>';
        try {
            const data = await bridge().api(id ? `${apiRoot}/sessions/${encodeURIComponent(id)}` : apiRoot);
            if (token !== revision) return;
            if (id) { session = data; selection = {scenario: data.scenario, difficulty: data.difficulty}; draft = drafts.get(draftKey()) || ""; render(); }
            else renderCatalog(data);
        } catch (error) {
            if (token !== revision) return;
            root().innerHTML = `<p role="alert">${escape(error.message)}</p><button type="button" class="secondary-button">Thử tải lại</button>`;
            root().querySelector("button").addEventListener("click", () => show(id));
        }
    }

    function renderCatalog(data) {
        root().innerHTML = `<div class="conversation-setup">
            <div class="conversation-note"><span class="section-index">01 / Chọn một cuộc gặp</span>
                <h2>Bốn nhiệm vụ nhỏ.<br>Một bước tiến rõ ràng.</h2>
                <p>Nghe, trả lời, nhận phản hồi. Cuối phiên, thử một câu mới với thông tin khác để biết mình đã hiểu đến đâu.</p>
                <p>Transcript và bản dịch chỉ mở khi bạn cần. Chưa có trả lời bằng giọng nói.</p></div>
            <form id="conversation-setup-form" class="conversation-card">
                <label for="conversation-scenario">Bạn muốn luyện tình huống nào?</label>
                <select id="conversation-scenario" name="scenario">${Object.entries(data.scenarios).map(([id, label]) => `<option value="${escape(id)}">${escape(label)}</option>`).join("")}</select>
                <label for="conversation-level">Độ khó</label>
                <select id="conversation-level" name="difficulty"><option value="A1">A1 · Hai chi tiết, một thay đổi nhỏ</option><option value="A2">A2 · Nối chi tiết, phân biệt thông tin</option><option value="B1">B1 · Hiểu điều kiện, lý do và đổi kế hoạch</option></select>
                <p id="conversation-status" role="status">${data.enabled ? "Khoảng 5–8 phút · Gõ tiếng Việt" : "Hội thoại AI chưa được bật trên máy chủ."}</p>
                <button class="primary-button" ${data.enabled ? "" : "disabled"}>Bắt đầu nghe →</button>
            </form></div>
            ${data.sessions.length ? `<section class="conversation-recent"><h2>Tiếp tục phiên gần đây</h2><ul>${data.sessions.map(item => `<li><a href="/conversation/${escape(item.id)}">${escape(data.scenarios[item.scenario])} · ${escape(item.difficulty)} <span>Tiếp tục →</span></a></li>`).join("")}</ul></section>` : ""}`;
        root().querySelector("#conversation-scenario").value = selection.scenario;
        root().querySelector("#conversation-level").value = selection.difficulty;
        root().querySelector("form").addEventListener("submit", async event => {
            event.preventDefault(); if (busy) return;
            const token = revision, form = event.currentTarget, values = new FormData(form);
            busy = true; form.querySelector("button").disabled = true;
            form.querySelector("[role=status]").textContent = "Đang tạo phiên…";
            try {
                const data = await bridge().api(`${apiRoot}/sessions`, {method: "POST", body: Object.fromEntries(values)});
                if (token === revision) bridge().navigate("conversation", {sessionId: data.id});
            } catch (error) {
                if (token === revision) {
                    form.querySelector("[role=status]").textContent = error.message;
                    form.querySelector("button").disabled = false;
                }
            } finally { if (token === revision) busy = false; }
        });
        root().querySelectorAll(".conversation-recent a").forEach(link => link.addEventListener("click", event => {
            if (event.button || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
            event.preventDefault(); bridge().navigate("conversation", {sessionId: link.getAttribute("href").split("/").at(-1)});
        }));
    }

    function render() {
        const turn = current(), attempts = turn?.attempts || [], pending = attempts.at(-1) && !attempts.at(-1).evaluation;
        if (pending) draft = attempts.at(-1).answerVi;
        root().innerHTML = `<div class="conversation-session-header"><a href="/conversation" id="conversation-back">← Chọn tình huống</a>
            <span>${escape(session.scenarioVi)} · ${escape(session.difficulty)}</span></div>
            <div class="conversation-progress" role="group" aria-label="Tiến độ phiên">${[1, 2, 3, 4].map(n => `<span class="${n <= session.turnNumber ? "is-current" : ""}">${n === 4 ? "Kiểm tra lại" : `Nhiệm vụ ${n}`}</span>`).join("")}</div>
            ${session.completed ? summary(session.summary) : `<div class="conversation-workspace">
                <section class="conversation-card conversation-listen" aria-labelledby="conversation-task">
                    <p class="section-index">${session.turnNumber === 4 ? "Câu mới / Kiểm tra lại" : `Nhiệm vụ ${session.turnNumber || 1} / 4`}</p>
                    <h2 id="conversation-task">${escape(turn?.prompt.taskVi || "Sẵn sàng cho câu nghe đầu tiên?")}</h2>
                    ${turn ? `<p>${escape(turn.prompt.questionVi)}</p>
                        <div class="conversation-audio-mark" aria-hidden="true">▂ ▅ ▃ ▇ ▄ ▆ ▂ ▅ ▃</div>
                        <button type="button" class="primary-button" data-action="play">${turn.plays ? "Nghe lại" : "Nghe câu này"} ▶</button>
                        <div class="conversation-audio-tools"><button type="button" class="secondary-button" data-action="slow">Nghe chậm · 0,8×</button>
                        <button type="button" class="text-button" data-action="stop">Dừng audio</button></div>
                        <div class="conversation-help"><button type="button" class="text-button" data-action="clarify">${turn.clarification ? "Nghe bản làm rõ" : "Nhờ AI nói rõ hơn"}</button>
                        <button type="button" class="text-button" data-action="transcript" aria-expanded="${!!turn.prompt.utteranceEn}" aria-controls="conversation-transcript">Mở transcript</button>
                        <button type="button" class="text-button" data-action="translation" aria-expanded="${!!turn.prompt.translationVi}" aria-controls="conversation-translation">Mở bản dịch</button></div>
                        <div id="conversation-transcript" ${turn.prompt.utteranceEn ? "" : "hidden"}><p lang="en">${escape(turn.prompt.utteranceEn)}</p>${turn.clarification?.utteranceEn ? `<p lang="en">${escape(turn.clarification.utteranceEn)}</p>` : ""}</div>
                        <div id="conversation-translation" ${turn.prompt.translationVi ? "" : "hidden"}><p>${escape(turn.prompt.translationVi)}</p>${turn.clarification?.translationVi ? `<p>${escape(turn.clarification.translationVi)}</p>` : ""}</div>
                        <small>Mở hỗ trợ sẽ được ghi nhận trong kết quả.</small>` : '<button type="button" class="primary-button" data-action="next">Tạo câu nghe đầu tiên →</button>'}
                </section>
                <section class="conversation-card conversation-reply" aria-labelledby="conversation-reply-heading">
                    <h2 id="conversation-reply-heading">Bạn hiểu được gì?</h2>
                    <div class="conversation-chat">${attempts.map(a => `<div class="conversation-bubble learner"><span>Bạn</span><p>${escape(a.answerVi)}</p></div>
                        ${a.evaluation ? `<div class="conversation-bubble coach"><span>AI Coach</span><p>${escape(a.evaluation.feedbackVi)}</p>${!turn.done && a.evaluation.clarificationVi ? `<p><strong>${escape(a.evaluation.clarificationVi)}</strong></p>` : ""}</div>` : ""}`).join("")}</div>
                    ${turn && !turn.done ? `<form id="conversation-answer-form"><label for="conversation-answer">Trả lời bằng tiếng Việt</label>
                        <textarea id="conversation-answer" name="answerVi" rows="4" maxlength="1500" required aria-describedby="conversation-answer-hint conversation-status" ${pending ? "readonly" : ""}>${escape(draft)}</textarea>
                        <small id="conversation-answer-hint">Trả lời đủ các ý được hỏi, chú ý thông tin cuối cùng khi người nói đổi ý. Bạn có thể diễn đạt theo cách của mình.</small>
                        <button class="primary-button">${pending ? "Thử chấm lại câu đã lưu" : attempts.length ? "Gửi ý bổ sung →" : "Gửi điều mình hiểu →"}</button></form>`
                        : turn ? `<p>${attempts.length >= 3 ? "Đã đủ ba lượt phản hồi. Bạn có thể nghe lại hoặc mở hỗ trợ trước khi tiếp tục." : "Bạn đã nắm được các ý của nhiệm vụ này."}</p><button type="button" class="primary-button" data-action="next">${session.turnNumber === 3 ? "Thử câu mới để kiểm tra lại →" : "Nhiệm vụ tiếp theo →"}</button>`
                        : "<p>Bắt đầu bằng việc nghe. AI sẽ phản hồi sau khi bạn gửi ý hiểu.</p>"}
                </section></div>`}
            <p id="conversation-status" role="status">${escape(message)}</p>`;
        root().querySelector("#conversation-back").addEventListener("click", event => { event.preventDefault(); bridge().navigate("conversation"); });
        root().querySelectorAll("[data-action]").forEach(button => button.addEventListener("click", () => action(button.dataset.action)));
        const textarea = root().querySelector("textarea");
        textarea?.addEventListener("input", () => { draft = textarea.value; drafts.set(draftKey(), draft); });
        root().querySelector("#conversation-answer-form")?.addEventListener("submit", event => {
            event.preventDefault(); if (busy) return;
            draft = textarea.value;
            if (!draft.trim()) { textarea.setCustomValidity("Hãy ghi điều bạn nghe được."); textarea.reportValidity(); return; }
            textarea.setCustomValidity("");
            stopAudio();
            const attempt = pending ? attempts.at(-1) : {requestId: crypto.randomUUID(), answerVi: draft};
            mutate(() => post("answer", {turnNumber: session.turnNumber, requestId: attempt.requestId, answerVi: attempt.answerVi}), "AI đang đọc ý hiểu của bạn…", true);
        });
        textarea?.addEventListener("input", () => textarea.setCustomValidity(""));
    }

    function summary(data) {
        return `<section class="conversation-card conversation-summary"><p class="section-index">Hoàn thành phiên nghe</p><h2>Mang điều đã hiểu sang câu mới.</h2>
            <p>Bạn nắm được ${data.independentFacts}/${data.totalFacts} ý không mở hỗ trợ; ${data.assistedFacts} ý với hỗ trợ. Nghe lại ở tốc độ thường vẫn được tính không mở hỗ trợ.</p>
            <div class="conversation-summary-grid"><div><h3>Đã hiểu được</h3><ul>${data.understoodVi.map(t => `<li>${escape(t)}</li>`).join("") || "<li>Phiên này chưa đủ bằng chứng cho ý hiểu đầy đủ.</li>"}</ul></div>
            <div><h3>Cần luyện tiếp</h3><ul>${data.practiceVi.map(t => `<li>${escape(t)}</li>`).join("") || "<li>Thử thông tin dài hơn ở lần tiếp theo.</li>"}</ul></div></div>
            <p class="conversation-transfer">${escape(data.transferVi)}</p><p><strong>Bước tiếp theo:</strong> ${escape(data.nextActionVi)}</p>
            <button type="button" class="primary-button" data-action="restart">Luyện một phiên mới →</button></section>`;
    }

    async function mutate(operation, status, answerOperation = false) {
        if (busy) return null;
        const token = revision; busy = true; message = status;
        root().setAttribute("aria-busy", "true");
        const textarea = root().querySelector("textarea");
        if (textarea) textarea.readOnly = true;
        root().querySelectorAll("button").forEach(b => { if (b.dataset.action !== "stop") b.disabled = true; });
        root().querySelector("#conversation-status").textContent = status;
        try {
            const data = await operation();
            if (token !== revision) return null;
            session = data; message = "";
            if (answerOperation) { draft = ""; drafts.delete(draftKey()); }
            render();
            (root().querySelector("textarea") || root().querySelector('[data-action="next"]'))?.focus({preventScroll: true});
            return data;
        } catch (error) {
            if (token !== revision) return null;
            // A server-side pending answer may have been saved even when the provider failed.
            try {
                const data = await bridge().api(`${apiRoot}/sessions/${encodeURIComponent(session.id)}`);
                if (token !== revision) return null;
                session = data;
            } catch (_) { /* Preserve the visible draft if fetching the saved session also fails. */ }
            if (token === revision) { message = error.message; render(); }
            return null;
        } finally {
            if (token === revision) { busy = false; root().removeAttribute("aria-busy"); }
        }
    }

    async function action(kind) {
        if (kind === "stop") { stopAudio(); return; }
        if (kind === "restart") {
            if (!session.summary.practiceVi.length && session.difficulty !== "B1") {
                selection.difficulty = session.difficulty === "A1" ? "A2" : "B1";
            }
            bridge().navigate("conversation"); return;
        }
        if (busy) return;
        if (kind === "next") { stopAudio(); draft = ""; await mutate(() => post("next", {turnNumber: session.turnNumber}), "AI đang chuẩn bị nhiệm vụ nghe…"); return; }
        if (kind === "transcript" || kind === "translation") {
            await mutate(() => post("help", {turnNumber: session.turnNumber, kind}), "Đang mở hỗ trợ…"); return;
        }
        const token = revision;
        const playbackToken = ++playbackRevision;
        if (kind === "clarify" && !current().clarification) {
            if (!await mutate(() => post("help", {turnNumber: session.turnNumber, kind: "clarify"}), "AI đang nói lại rõ hơn…")) return;
        }
        if (token !== revision || playbackToken !== playbackRevision) return;
        const slow = kind === "slow", variant = kind === "clarify" ? "clarify" : "original";
        const data = await mutate(() => post("play", {turnNumber: session.turnNumber, variant, slow}), "Đang chuẩn bị giọng nói…");
        if (!data || token !== revision || playbackToken !== playbackRevision) return;
        try {
            root().querySelector("#conversation-status").textContent = slow ? "Đang nghe chậm · 0,8×" : "Đang phát tiếng Anh…";
            await bridge().playRecordedAudio(data.audioUrl, 0, null, slow ? 0.8 : 1);
            if (token === revision && playbackToken === playbackRevision) root().querySelector("#conversation-status").textContent = "Bạn có thể nghe lại hoặc gửi điều mình hiểu.";
        } catch (error) {
            if (token === revision && playbackToken === playbackRevision && error.name !== "AbortError") root().querySelector("#conversation-status").textContent = error.message;
        }
    }

    window.DictaLabConversation = {show, leave};
})();
