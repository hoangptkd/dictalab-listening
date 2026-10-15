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
        root().closest?.(".conversation-browser")?.classList.remove("has-active-session");
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
            <section class="conversation-card conversation-start">
            <div class="conversation-note"><span class="section-index">01 / Chọn một cuộc gặp</span>
                <h2>Bốn nhiệm vụ nhỏ.<br>Một bước tiến rõ ràng.</h2>
                <p>Nghe, trả lời, nhận phản hồi. Cuối phiên, thử một câu mới với thông tin khác để biết mình đã hiểu đến đâu.</p>
                <p>Transcript và bản dịch chỉ mở khi bạn cần. Chưa có trả lời bằng giọng nói.</p></div>
            <form id="conversation-setup-form">
                <label for="conversation-scenario">Bạn muốn luyện tình huống nào?</label>
                <select id="conversation-scenario" name="scenario">${Object.entries(data.scenarios).map(([id, label]) => `<option value="${escape(id)}">${escape(label)}</option>`).join("")}</select>
                <label for="conversation-level">Độ khó</label>
                <select id="conversation-level" name="difficulty"><option value="A1">A1 · Hai chi tiết, một thay đổi nhỏ</option><option value="A2">A2 · Nối chi tiết, phân biệt thông tin</option><option value="B1">B1 · Hiểu điều kiện, lý do và đổi kế hoạch</option></select>
                <p id="conversation-status" role="status">${data.enabled ? "Khoảng 5–8 phút · Gõ tiếng Việt" : "Hội thoại AI chưa được bật trên máy chủ."}</p>
                <button class="primary-button" ${data.enabled ? "" : "disabled"}>Bắt đầu nghe →</button>
            </form></section>
            <section class="conversation-card conversation-recent"><h2>Phiên gần đây</h2>${data.sessions.length ? `<ul>${data.sessions.map(item => `<li><a href="/conversation/${escape(item.id)}">${escape(data.scenarios[item.scenario])} · ${escape(item.difficulty)} <span>${item.completed ? "✓ Đã hoàn thành · Xem kết quả" : "Đang học · Tiếp tục"} →</span></a></li>`).join("")}</ul>` : '<p>Chưa có phiên hội thoại. Chọn tình huống để bắt đầu nghe.</p>'}</section></div>`;
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
        const helpOpen = root().querySelector("#conversation-assistance")?.open;
        root().closest?.(".conversation-browser")?.classList.toggle("has-active-session", !session.completed);
        if (pending) draft = attempts.at(-1).answerVi;
        root().innerHTML = `<div class="conversation-session-layout ${session.completed ? "" : "is-active-session"}">${session.completed ? `<aside class="conversation-card conversation-context"><p class="section-index">Cuộc gặp đang luyện</p><h2>${escape(session.scenarioVi)}</h2><p>${escape(session.difficulty)} · Bốn nhiệm vụ nghe và phản hồi</p><p>Nghe trọn câu, nắm ý chính và gõ điều mình hiểu bằng tiếng Việt. Mở transcript hoặc bản dịch khi cần.</p><a href="/conversation" id="conversation-back">← Chọn tình huống</a></aside>` : ""}<div class="conversation-session-main">${session.completed ? "" : `<a href="/conversation" id="conversation-back">← Chọn tình huống</a><details class="ui-disclosure conversation-guide"><summary>Hướng dẫn luyện hội thoại</summary><p>Bốn nhiệm vụ nghe và phản hồi</p><p>Nghe trọn câu, nắm ý chính và gõ điều mình hiểu bằng tiếng Việt. Mở transcript hoặc bản dịch khi cần.</p></details>`}<div class="conversation-session-header">
            <div><span>${escape(session.scenarioVi)} · ${escape(session.difficulty)}</span>
            ${!session.completed ? `<h2 id="conversation-task">${escape(turn?.prompt.taskVi || "Sẵn sàng cho câu nghe đầu tiên?")}</h2>` : ""}</div></div>
            <div class="conversation-progress" role="group" aria-label="Tiến độ phiên">${[1, 2, 3, 4].map(n => `<span class="${n <= session.turnNumber ? "is-current" : ""}">${n === 4 ? "Kiểm tra lại" : `Nhiệm vụ ${n}`}</span>`).join("")}</div>
            ${session.completed ? summary(session.summary) : `<div class="conversation-workspace">
                <section class="conversation-card conversation-listen" aria-labelledby="conversation-task">
                    <p class="section-index">${session.turnNumber === 4 ? "Câu mới / Kiểm tra lại" : `Nhiệm vụ ${session.turnNumber || 1} / 4`}</p>
                    ${turn ? `<p>${escape(turn.prompt.questionVi)}</p>
                        <div class="conversation-audio-mark" aria-hidden="true">▂ ▅ ▃ ▇ ▄ ▆ ▂ ▅ ▃</div>
                        <button type="button" class="primary-button" data-action="play">${turn.plays ? "Nghe lại" : "Nghe câu này"} ▶</button>
                        <div class="conversation-audio-tools"><button type="button" class="secondary-button" data-action="slow">Nghe chậm · 0,8×</button>
                        <button type="button" class="text-button" data-action="stop">Dừng audio</button></div>
                        <small>Mở hỗ trợ sẽ được ghi nhận trong kết quả.</small>
                        <details id="conversation-assistance" class="ui-disclosure" ${helpOpen || turn.prompt.utteranceEn || turn.prompt.translationVi || turn.clarification ? "open" : ""}><summary>Hỗ trợ: làm rõ, transcript, bản dịch</summary>
                        <div class="conversation-help"><button type="button" class="text-button" data-action="clarify">${turn.clarification ? "Nghe bản làm rõ" : "Nhờ AI nói rõ hơn"}</button>
                        <button type="button" class="text-button" data-action="transcript" aria-expanded="${!!turn.prompt.utteranceEn}" aria-controls="conversation-transcript">Mở transcript</button>
                        <button type="button" class="text-button" data-action="translation" aria-expanded="${!!turn.prompt.translationVi}" aria-controls="conversation-translation">Mở bản dịch</button></div>
                        <div id="conversation-transcript" ${turn.prompt.utteranceEn ? "" : "hidden"}><p lang="en">${escape(turn.prompt.utteranceEn)}</p>${turn.clarification?.utteranceEn ? `<p lang="en">${escape(turn.clarification.utteranceEn)}</p>` : ""}</div>
                        <div id="conversation-translation" ${turn.prompt.translationVi ? "" : "hidden"}><p>${escape(turn.prompt.translationVi)}</p>${turn.clarification?.translationVi ? `<p>${escape(turn.clarification.translationVi)}</p>` : ""}</div>
                        </details>` : '<button type="button" class="primary-button" data-action="next">Tạo câu nghe đầu tiên →</button>'}
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
            <p id="conversation-status" role="status">${escape(message)}</p></div></div>`;
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
        const input = {turnNumber: session.turnNumber, variant, slow};
        let data = await mutate(() => post("play", input), "Đang chuẩn bị giọng nói…");
        if (!data || token !== revision || playbackToken !== playbackRevision) return;
        try {
            if (!data.audioUrl) {
                root().querySelector("#conversation-status").textContent = "Giọng nói đang được chuẩn bị. Bạn vẫn có thể nhập câu trả lời.";
                data = await waitForAudio(data.audioStatus, input, token, playbackToken);
                if (!data || token !== revision || playbackToken !== playbackRevision) return;
            }
            root().querySelector("#conversation-status").textContent = slow ? "Đang nghe chậm · 0,8×" : "Đang phát tiếng Anh…";
            await bridge().playRecordedAudio(data.audioUrl, 0, null, slow ? 0.8 : 1);
            if (token === revision && playbackToken === playbackRevision) root().querySelector("#conversation-status").textContent = "Bạn có thể nghe lại hoặc gửi điều mình hiểu.";
        } catch (error) {
            if (token === revision && playbackToken === playbackRevision && error.name !== "AbortError") root().querySelector("#conversation-status").textContent = error.message;
        }
    }

    async function waitForAudio(status, input, token, playbackToken) {
        for (let attempt = 0; attempt < 90; attempt++) {
            if (!["PENDING", "RUNNING"].includes(status)) {
                throw new Error(status === "EXHAUSTED" ? "Đã hết lượt thử tạo giọng nói cho câu này. Bạn có thể mở hỗ trợ hoặc tiếp tục phiên khác."
                    : status === "BUSY" ? "Hàng đợi giọng nói đang đầy. Hãy thử nghe lại sau."
                    : "Chưa tạo được giọng nói. Hệ thống không tự gọi lại; hãy bấm nghe để chủ động thử lại.");
            }
            await new Promise(resolve => setTimeout(resolve, 1000));
            if (token !== revision || playbackToken !== playbackRevision) return null;
            const result = await bridge().api(`${path("audio-status")}?turnNumber=${input.turnNumber}&variant=${encodeURIComponent(input.variant)}`);
            if (token !== revision || playbackToken !== playbackRevision) return null;
            status = result.status;
            if (status === "READY") {
                // Record playback/support only once audio exists, not once per status poll.
                return mutate(() => post("play", input), "Giọng nói đã sẵn sàng…");
            }
        }
        throw new Error("Giọng nói vẫn đang chờ. Hãy bấm nghe lại sau; công việc đã được lưu.");
    }

    window.DictaLabConversation = {show, leave};
})();
