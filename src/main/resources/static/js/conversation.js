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
    const post = (action, body) => bridge().api(path(action), {method: "POST", body});
    const current = () => session?.turns?.at(-1);
    function stopAudio() { playbackRevision++; bridge().stopRecordedAudio(); }

    function leave() {
        if (session) saveDraft(root()?.querySelector("textarea")?.value ?? draft);
        revision++; busy = false;
        stopAudio();
    }

    async function show(id) {
        leave(); session = null; draft = ""; message = "";
        root().closest?.(".conversation-browser")?.classList.remove("has-session");
        const token = revision;
        root().innerHTML = '<p role="status">Đang tải hội thoại…</p>';
        try {
            const data = await bridge().api(id ? `${apiRoot}/sessions/${encodeURIComponent(id)}` : apiRoot);
            if (token !== revision) return;
            if (id) {
                session = data; selection = {scenario: data.scenario, difficulty: data.difficulty}; draft = readDraft(); render();
                if (!session.turnNumber && !session.completed) await action("next");
            }
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
                <h2>Một tình huống.<br>Nghe → hiểu → thử lại.</h2>
                <p>Ba bước trong cùng cuộc gặp, rồi một câu mới để kiểm tra. Có lời giải sau mỗi câu. Gõ tiếng Việt, không cần dịch từng từ.</p></div>
            <form id="conversation-setup-form">
                <label for="conversation-scenario">Bạn muốn luyện tình huống nào?</label>
                <select id="conversation-scenario" name="scenario">${Object.entries(data.scenarios).map(([id, label]) => `<option value="${escape(id)}">${escape(label)}</option>`).join("")}</select>
                <label for="conversation-level">Độ khó</label>
                <select id="conversation-level" name="difficulty"><option value="A1">A1 · Hai chi tiết, một thay đổi nhỏ</option><option value="A2">A2 · Nối chi tiết, phân biệt thông tin</option><option value="B1">B1 · Hiểu điều kiện, lý do và đổi kế hoạch</option></select>
                <p id="conversation-status" role="status">${data.enabled ? "Khoảng 5–8 phút · Gõ tiếng Việt" : "Hội thoại AI chưa được bật trên máy chủ."}</p>
                <button class="primary-button" ${data.enabled ? "" : "disabled"}>Bắt đầu nghe →</button>
            </form></section>
            <section class="conversation-card conversation-recent"><h2>Phiên gần đây</h2>${data.sessions.length ? `<ul>${data.sessions.map(item => `<li><a href="/conversation/${escape(item.id)}"><span>${escape(data.scenarios[item.scenario])} · ${escape(item.difficulty)}<small>${escape(dateLabel(item.created_at))} · ${item.turnNumber || 0}/4 câu đã mở</small></span><span>${item.completed ? "Xem kết quả" : "Tiếp tục"} →</span></a></li>`).join("")}</ul>` : '<p>Chưa có phiên hội thoại. Chọn tình huống để bắt đầu nghe.</p>'}</section></div>`;
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
        root().closest?.(".conversation-browser")?.classList.add("has-session");
        if (pending) draft = attempts.at(-1).answerVi;
        root().innerHTML = `<div class="conversation-session-layout ${session.completed ? "" : "is-active-session"}"><div class="conversation-session-main"><a href="/conversation" id="conversation-back">← Chọn tình huống</a>${session.completed ? "" : `<details class="ui-disclosure conversation-guide"><summary>Hướng dẫn luyện hội thoại</summary><p>Bốn nhiệm vụ nghe và phản hồi</p><p>Nghe trọn câu, nắm ý chính và gõ điều mình hiểu bằng tiếng Việt. Mở transcript hoặc bản dịch khi cần.</p></details>`}<div class="conversation-session-header">
            <div><span>${escape(session.scenarioVi)} · ${escape(session.difficulty)}</span>
            ${!session.completed ? `<h2 id="conversation-task">${escape(turn?.prompt.taskVi || "Sẵn sàng cho câu nghe đầu tiên?")}</h2>` : ""}</div></div>
            ${session.practiceFocus ? `<p class="conversation-focus">Mục tiêu phiên này: ${escape(session.practiceFocus)}</p>` : ""}
            <div class="conversation-progress" role="group" aria-label="Tiến độ phiên">${[1, 2, 3, 4].map(n => `<span ${n === session.turnNumber && !session.completed ? 'aria-current="step"' : ""} class="${n <= session.turnNumber ? "is-current" : ""}">${session.turns[n - 1]?.done ? "✓ " : ""}${n === 4 ? "Câu mới" : `Bước ${n}`}</span>`).join("")}</div>
            ${session.completed ? summary(session.summary) + reviewHistory() : `<div class="conversation-workspace">
                <section class="conversation-card conversation-listen" aria-labelledby="conversation-task">
                    <p class="section-index">${session.turnNumber === 4 ? "Câu mới / Kiểm tra lại" : `Nhiệm vụ ${session.turnNumber || 1} / 4`}</p>
                    ${turn ? `<p>${escape(turn.prompt.questionVi)}</p>
                        <div class="conversation-audio-mark" aria-hidden="true">▂ ▅ ▃ ▇ ▄ ▆ ▂ ▅ ▃</div>
                        <button type="button" class="primary-button" data-action="play">${turn.plays ? "Nghe lại" : "Nghe câu này"} ▶</button>
                        <div class="conversation-audio-tools"><button type="button" class="secondary-button" data-action="slow">Nghe chậm · 0,8×</button>
                        <button type="button" class="text-button" data-action="stop">Dừng audio</button></div>
                        <small>Mở hỗ trợ sẽ được ghi nhận trong kết quả.</small>
                        <details id="conversation-assistance" class="ui-disclosure" ${helpOpen ? "open" : ""}><summary>Cần một chút hỗ trợ?</summary>
                        <div class="conversation-help"><button type="button" class="text-button" data-action="clarify">${turn.clarification ? "Nghe bản làm rõ" : "Nhờ AI nói rõ hơn"}</button>
                        <button type="button" class="text-button" data-action="transcript" aria-expanded="${!!turn.prompt.utteranceEn}" aria-controls="conversation-transcript">Mở transcript</button>
                        <button type="button" class="text-button" data-action="translation" aria-expanded="${!!turn.prompt.translationVi}" aria-controls="conversation-translation">Mở bản dịch</button></div>
                        <div id="conversation-transcript" ${turn.prompt.utteranceEn ? "" : "hidden"}><p lang="en">${escape(turn.prompt.utteranceEn)}</p>${turn.clarification?.utteranceEn ? `<p lang="en">${escape(turn.clarification.utteranceEn)}</p>` : ""}</div>
                        <div id="conversation-translation" ${turn.prompt.translationVi ? "" : "hidden"}><p>${escape(turn.prompt.translationVi)}</p>${turn.clarification?.translationVi ? `<p>${escape(turn.clarification.translationVi)}</p>` : ""}</div>
                        ${!turn.done && !pending ? '<p>Chưa nghe ra? Mở lời giải để học lại. Câu này sẽ kết thúc, không được tính là tự hiểu thêm.</p><button type="button" class="text-button" data-action="review">Xem lời giải và kết thúc câu này</button>' : ""}
                        </details>` : '<button type="button" class="primary-button" data-action="next">Thử tạo lại câu đầu tiên →</button>'}
                </section>
                <section class="conversation-card conversation-reply" aria-labelledby="conversation-reply-heading">
                    <h2 id="conversation-reply-heading">Bạn hiểu được gì?</h2>
                    ${turn && !turn.done ? `<p class="conversation-feedback-count">Đã nắm ${turn.capturedFacts || 0}/${turn.totalFacts || 2} ý · Còn ${3 - attempts.length} lượt bổ sung</p>` : ""}
                    <div class="conversation-chat">${attempts.map((a, i) => `${i < attempts.length - 1 ? `<details class="ui-disclosure"><summary>Lượt trả lời ${i + 1}</summary>` : ""}<div class="conversation-bubble learner"><span>Bạn</span><p>${escape(a.answerVi)}</p></div>
                        ${a.evaluation ? `<div class="conversation-bubble coach"><span>AI Coach</span><p>${escape(a.evaluation.feedbackVi)}</p>${!turn.done && a.evaluation.clarificationVi ? `<p><strong>${escape(a.evaluation.clarificationVi)}</strong></p>` : ""}</div>` : ""}${i < attempts.length - 1 ? "</details>" : ""}`).join("")}</div>
                    ${turn && !turn.done ? `<form id="conversation-answer-form"><label for="conversation-answer">Trả lời bằng tiếng Việt</label>
                        <textarea id="conversation-answer" name="answerVi" rows="4" maxlength="1500" required aria-describedby="conversation-answer-hint conversation-status" ${pending ? "readonly" : ""}>${escape(draft)}</textarea>
                        <small id="conversation-answer-hint">Trả lời đủ các ý được hỏi, chú ý thông tin cuối cùng khi người nói đổi ý. Bạn có thể diễn đạt theo cách của mình.</small>
                        <div class="conversation-answer-actions"><button class="primary-button">${pending ? "Thử chấm lại câu đã lưu" : attempts.length ? "Gửi ý bổ sung →" : "Gửi điều mình hiểu →"}</button><button type="button" class="secondary-button" data-action="replay-${session.turnNumber}">↻ Nghe lại câu gốc</button></div></form>`
                        : turn ? `${reviewCard(turn, session.turnNumber, true)}<button type="button" class="primary-button" data-action="next">${session.turnNumber === 3 ? "Thử câu mới để kiểm tra lại →" : "Bước tiếp theo →"}</button>${session.turnNumber === 3 ? '<p>Không tạo được câu mới hoặc muốn dừng tại đây?</p><button type="button" class="text-button" data-action="finish">Kết thúc với ba câu · Chưa kiểm tra vận dụng</button>' : ""}`
                        : "<p>Bắt đầu bằng việc nghe. AI sẽ phản hồi sau khi bạn gửi ý hiểu.</p>"}
                </section></div>${reviewHistory(true)}`}
            <p id="conversation-status" role="status">${escape(message)}</p></div></div>`;
        root().querySelector("#conversation-back").addEventListener("click", event => { event.preventDefault(); bridge().navigate("conversation"); });
        root().querySelectorAll("[data-action]").forEach(button => button.addEventListener("click", () => action(button.dataset.action)));
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
    }

    function reviewCard(turn, number, open = false) {
        if (!turn.review) return "";
        const correct = turn.capturedFacts === turn.totalFacts;
        const labels = {CAPTURED: "Đã hiểu", PARTIAL: "Hiểu một phần", INCORRECT: "Cần sửa", MISSED: "Chưa trả lời", UNCERTAIN: "Chưa rõ ý"};
        return `<details class="ui-disclosure conversation-review" ${open ? "open" : ""}><summary>Câu ${number} · ${correct ? "Đã nắm đủ ý" : "Đối chiếu và học lại"}</summary>
            <p>${escape(turn.prompt.questionVi)}</p><ul class="conversation-review-facts">${turn.review.facts.map(f => `<li><span>${escape(labels[f.status] || "Chưa rõ ý")}</span><strong>${escape(f.textVi)}</strong><q lang="en">${escape(f.evidenceEn)}</q></li>`).join("")}</ul>
            <button type="button" class="secondary-button" data-action="replay-${number}">↻ Nghe lại câu ${number}</button>
            <p>Nghe lại và chú ý các cụm trên. Sau đó thử tự nhắc lại ý trước khi sang câu mới.</p>
            <details class="conversation-review-script"><summary>Toàn bộ transcript và bản dịch</summary><p lang="en">${escape(turn.review.utteranceEn)}</p><p>${escape(turn.review.translationVi)}</p></details>
            ${turn.attempts?.length ? `<details><summary>Câu trả lời của bạn</summary>${turn.attempts.map(a => `<p>${escape(a.answerVi)}</p>${a.evaluation ? `<p>${escape(a.evaluation.feedbackVi)}</p>` : ""}`).join("")}</details>` : ""}</details>`;
    }

    function reviewHistory(previousOnly = false) {
        const turns = session.turns.filter((t, i) => t.done && (!previousOnly || i < session.turns.length - 1));
        return turns.length ? `<section class="conversation-review-history"><h3>Xem lại từng câu</h3>${turns.map(t => reviewCard(t, session.turns.indexOf(t) + 1)).join("")}</section>` : "";
    }

    function summary(data) {
        return `<section class="conversation-card conversation-summary"><p class="section-index">${session.transferSkipped ? "Đã lưu kết quả ba câu" : "Hoàn thành phiên nghe"}</p><h2>${session.transferSkipped ? "Xem lại điều đã học." : "Mang điều đã hiểu sang câu mới."}</h2>
            <p>Bạn nắm được ${data.independentFacts}/${data.totalFacts} ý không mở hỗ trợ; ${data.assistedFacts} ý với hỗ trợ. Nghe lại ở tốc độ thường vẫn được tính không mở hỗ trợ.</p>
            <div class="conversation-summary-grid"><div><h3>Đã hiểu được</h3><ul>${data.understoodVi.map(t => `<li>${escape(t)}</li>`).join("") || "<li>Phiên này chưa đủ bằng chứng cho ý hiểu đầy đủ.</li>"}</ul></div>
            <div><h3>Cần luyện tiếp</h3><ul>${data.practiceVi.map(t => `<li>${escape(t)}</li>`).join("") || "<li>Thử thông tin dài hơn ở lần tiếp theo.</li>"}</ul></div></div>
            <p class="conversation-transfer">${escape(data.transferVi)}</p><p><strong>Bước tiếp theo:</strong> ${escape(data.nextActionVi)}</p>
            <p>Đây là kết quả của ${session.turns.length} câu trong phiên, không phải đánh giá trình độ CEFR.</p>
            ${data.practiceVi.length ? '<button type="button" class="primary-button" data-action="targeted">Luyện đúng điểm cần cải thiện →</button>' : ""}
            <button type="button" class="${data.practiceVi.length ? "secondary-button" : "primary-button"}" data-action="restart">Chọn phiên tiếp theo →</button></section>`;
    }

    async function mutate(operation, status, answerOperation = false) {
        if (busy) return null;
        const token = revision, oldDraftKey = draftKey(), oldTurn = session.turnNumber; busy = true; message = status;
        root().setAttribute("aria-busy", "true");
        const textarea = root().querySelector("textarea");
        if (textarea) textarea.readOnly = true;
        root().querySelectorAll("button").forEach(b => { if (b.dataset.action !== "stop") b.disabled = true; });
        root().querySelector("#conversation-status").textContent = status;
        try {
            const data = await operation();
            if (token !== revision) return null;
            session = data; message = "";
            if (answerOperation || data.turnNumber !== oldTurn || current()?.done) {
                draft = ""; drafts.delete(oldDraftKey);
                try { sessionStorage.removeItem(oldDraftKey); } catch (_) { /* Optional storage. */ }
            }
            render();
            (root().querySelector("textarea") || root().querySelector('[data-action="next"]'))?.focus({preventScroll: true});
            return data;
        } catch (error) {
            if (token !== revision) return null;
            // A server-side pending answer may have been saved even when the provider failed.
            try {
                const data = await bridge().api(`${apiRoot}/sessions/${encodeURIComponent(session.id)}`);
                if (token !== revision) return null;
                if (data.turnNumber !== oldTurn || data.completed || data.turns.at(-1)?.done) {
                    draft = ""; drafts.delete(oldDraftKey);
                    try { sessionStorage.removeItem(oldDraftKey); } catch (_) { /* Optional storage. */ }
                }
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
        if (kind === "targeted") {
            const data = await mutate(() => bridge().api(`${apiRoot}/sessions`, {method: "POST", body: {
                scenario: session.scenario, difficulty: session.difficulty, sourceSessionId: session.id
            }}), "Đang tạo phiên luyện theo mục tiêu…");
            if (data) bridge().navigate("conversation", {sessionId: data.id});
            return;
        }
        if (kind === "next") { stopAudio(); draft = ""; await mutate(() => post("next", {turnNumber: session.turnNumber}), "AI đang chuẩn bị nhiệm vụ nghe…"); return; }
        if (["transcript", "translation", "review", "finish"].includes(kind)) {
            await mutate(() => post("help", {turnNumber: session.turnNumber, kind}), "Đang mở hỗ trợ…"); return;
        }
        const token = revision;
        const playbackToken = ++playbackRevision;
        if (kind === "clarify" && !current().clarification) {
            if (!await mutate(() => post("help", {turnNumber: session.turnNumber, kind: "clarify"}), "AI đang nói lại rõ hơn…")) return;
        }
        if (token !== revision || playbackToken !== playbackRevision) return;
        const slow = kind === "slow", variant = kind === "clarify" ? "clarify" : "original";
        const input = {turnNumber: kind.startsWith("replay-") ? Number(kind.slice(7)) : session.turnNumber, variant, slow};
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

    window.DictaLabConversation = {show, leave, clearDrafts};
})();
