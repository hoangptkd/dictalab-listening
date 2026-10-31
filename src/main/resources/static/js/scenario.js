(() => {
    "use strict";
    const base = "/api/v2/scenarios", bridge = () => window.DictaLabListeningBridge;
    const root = () => document.querySelector("#conversation-content");
    const escape = value => String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#039;");
    const labels = {CAPTURED: "Hiểu đúng", PARTIAL: "Hiểu một phần", MISSED: "Bỏ lỡ", INCORRECT: "Chưa đúng", UNCERTAIN: "Chưa đủ căn cứ"};
    const profiles = {supported: "Có hỗ trợ", standard: "Tiêu chuẩn", challenge: "Thử thách"};
    let revision = 0, owner = null, abort = null, timer = null, session = null, busy = false;
    const memory = new Map();
    const storageKey = (suffix, user = owner) => `dictalab:scenario:${user}:${suffix}`;
    function read(suffix, fallback, user = owner) { const key = storageKey(suffix,user); try { return JSON.parse(sessionStorage.getItem(key)) ?? memory.get(key) ?? fallback; } catch (_) { return memory.get(key) ?? fallback; } }
    function save(suffix, value, user = owner) { const key = storageKey(suffix,user); memory.set(key,value); try { if (value === null) sessionStorage.removeItem(key); else sessionStorage.setItem(key, JSON.stringify(value)); } catch (_) { /* In-memory retry protection remains when storage is unavailable. */ } }
    const current = token => token === revision && owner === bridge().currentUser()?.id;
    const request = (path, options = {}) => bridge().api(base + path, {...options, signal: abort?.signal, cache: "no-store"});
    function status(message) { const node = root()?.querySelector("#scenario-status"); if (node) node.textContent = message; }
    function frame(html) { stopAudio(); root().innerHTML = `<div class="scenario-browser">${html}<p id="scenario-status" class="scenario-status" role="status" aria-live="polite"></p></div>`; }
    function stopAudio() { const audio = root()?.querySelector("audio"); if (audio) { audio.pause(); audio.removeAttribute("src"); audio.load(); } }
    function leave() { revision++; clearTimeout(timer); timer = null; abort?.abort(); abort = null; stopAudio(); busy = false; }
    async function mutation(path, body) {
        // Keep the same key after transport failure and across reload, until Core acknowledges registration.
        const actor = owner, identity = path + "/" + JSON.stringify(body), pending = read("mutations", {}, actor);
        const key = pending[identity] || crypto.randomUUID(); pending[identity] = key; save("mutations", pending, actor);
        const result = await request(path, {method: "POST", headers: {"Idempotency-Key": key}, body});
        if (result.operationId) save("operation", {...result, action: result.stage, sessionId: result.sessionId || path.match(/^\/sessions\/([^/]+)\//)?.[1]}, actor);
        delete pending[identity]; save("mutations", pending, actor); return result;
    }
    async function run(work) {
        if (busy) return;
        busy = true; const token = revision;
        root()?.setAttribute("aria-busy", "true");
        try { await work(token); }
        catch (error) { if (current(token) && error.name !== "AbortError") status(error.status === 409 ? "Phiên đã thay đổi. Câu trả lời nháp vẫn được giữ; bấm tải lại bài để tiếp tục." : error.message); }
        finally { if (current(token)) { busy = false; root()?.removeAttribute("aria-busy"); } }
    }
    async function show(id) {
        leave(); owner = bridge().currentUser()?.id; if (!owner || !root()) return;
        abort = new AbortController(); session = null; const token = revision;
        frame("<p>Đang tải bài học…</p>");
        try {
            const pending = read("operation", null);
            if (pending && (!id || pending.sessionId === id)) { await wait(pending, token); return; }
            if (id) await openSession(id, token); else return await catalog(token);
        } catch (error) { if (current(token) && error.name !== "AbortError") status(error.message); }
    }
    async function catalog(token) {
        const [catalog, suggestions, history, goals, recommendations, legacy] = await Promise.all([
            request("/catalog"), request("/suggestions"), request("/sessions?limit=10"), request("/learning-goals"), request("/recommendations"),
            bridge().api("/api/v1/conversation/sessions?offset=0&limit=12", {signal: abort?.signal, cache: "no-store"}).catch(error => ({sessions: [], error: error.message}))]);
        if (!current(token)) return;
        if (!catalog.enabled) return false;
        const available = catalog.generationAvailable;
        const groups = [...new Map(suggestions.suggestions.map(s => [s.groupId || s.id, {id: s.groupId || s.id, title: s.groupTitleVi || s.titleVi}])).values()];
        const historyRows = rows => rows.map(s => `<p><a href="/conversation/${encodeURIComponent(s.sessionId)}" data-session="${escape(s.sessionId)}">${escape(s.titleVi)}</a> · ${escape(s.status)}</p>`).join("");
        const legacyRows = rows => rows.map(s => `<p><a href="/conversation/${encodeURIComponent(s.id)}" data-session="${escape(s.id)}">Bài trước đây · ${escape(s.scenario)} · ${escape(s.difficulty)}</a></p>`).join("");
        frame(`${catalog.generationMode === "LOCAL_PREVIEW" ? '<p role="status">Thử nghiệm local · Program DRAFT, chưa được chuyên gia duyệt. Kết quả không phải đánh giá chứng chỉ.</p>' : ''}<div class="scenario-hero"><form id="scenario-create" class="scenario-input-card">
            <label class="scenario-brief-label" for="scenario-input">Bạn muốn luyện tình huống nào?</label>
            <textarea id="scenario-input" class="scenario-brief" name="inputVi" rows="5" maxlength="2000" required aria-describedby="scenario-input-help"></textarea>
            <p id="scenario-input-help">Mô tả bằng tiếng Việt. Tránh nhập thông tin cá nhân hoặc khóa truy cập.</p>
            <fieldset class="scenario-profile-options"><legend>Mức luyện giao tiếp</legend>${(catalog.difficultyProfiles.length ? catalog.difficultyProfiles : Object.keys(profiles)).map((p,i) => `<label><input type="radio" name="profileId" value="${escape(p)}" ${i === 0 ? "checked" : ""}>${escape(profiles[p] || p)}</label>`).join("")}</fieldset>
            <p>TOEIC và IELTS giữ mục tiêu theo thang điểm riêng; bài giao tiếp này không phải bài thi quy đổi band.</p>
            <button class="primary-button" ${available ? "" : "disabled"}>Phân tích và xem trước</button>
            ${available ? "" : "<p>Chương trình đang chờ duyệt bộ sinh bài. Bài đã lưu vẫn có thể mở.</p>"}
            </form><aside class="scenario-suggestions-col"><h2>Gợi ý bài luyện</h2><p>${escape(recommendations.messageVi)}</p>
            ${recommendations.suggestions.slice(0,3).map((s,index) => `<button class="scenario-suggestion-card" type="button" data-recommendation="${index}"><strong>${escape(s.titleVi)}</strong><span>${escape(s.reasonVi || s.inputVi)}</span></button>`).join("")}
            ${goals.map(g => `<p class="scenario-goal-display">Mục tiêu tự khai: ${escape(g.programCode)} · ${escape(g.target.component)} · ${escape(g.target.value)}</p>`).join("")}</aside></div>
            <section class="scenario-explorer"><h2>Gợi ý để bạn tùy chỉnh</h2><p>Chọn một gợi ý, rồi sửa mô tả theo nhu cầu của bạn. Bạn cũng có thể nhập tình huống hoàn toàn mới.</p>
            <div class="scenario-program-pills" role="group" aria-label="Nhóm gợi ý">${groups.map((g,i) => `<button class="scenario-pill" type="button" data-group="${escape(g.id)}" aria-pressed="${i === 0}">${escape(g.title)}</button>`).join("")}</div>
            <div class="scenario-group-grid">${suggestions.suggestions.map((s,index) => `<button class="scenario-group-card" data-suggestion="${index}" data-suggestion-group="${escape(s.groupId || s.id)}" ${index && (s.groupId || s.id) !== groups[0].id ? "hidden" : ""} type="button"><strong>${escape(s.titleVi)}</strong></button>`).join("")}</div></section>
            <section><h2>Bài đã tạo</h2><div id="scenario-history">${history.sessions.length ? historyRows(history.sessions) : "<p>Chưa có bài học mới.</p>"}</div><button type="button" id="scenario-history-more" ${history.nextCursor ? "" : "hidden"}>Xem thêm bài</button></section>
            <section><h2>Bài trước đây</h2><div id="scenario-legacy-history">${legacyRows(legacy.sessions || []) || "<p>Chưa có bài trước đây.</p>"}</div>${legacy.error ? `<p role="status">${escape(legacy.error)}</p>` : ""}<button type="button" id="scenario-legacy-more" ${legacy.hasMore ? "" : "hidden"}>Xem thêm bài trước đây</button></section>`);
        const input = root().querySelector("#scenario-input"); input.value = read("brief", "");
        input.addEventListener("input", () => save("brief", input.value));
        root().querySelectorAll("[data-group]").forEach(button => button.addEventListener("click", () => {
            root().querySelectorAll("[data-group]").forEach(item => item.setAttribute("aria-pressed", String(item === button)));
            root().querySelectorAll("[data-suggestion-group]").forEach(item => { item.hidden = item.dataset.suggestionGroup !== button.dataset.group; });
        }));
        root().querySelectorAll("[data-suggestion]").forEach(button => button.addEventListener("click", () => {
            input.value = suggestions.suggestions[Number(button.dataset.suggestion)].inputVi; save("brief", input.value); input.focus();
        }));
        root().querySelectorAll("[data-recommendation]").forEach(button => button.addEventListener("click", () => {
            input.value = recommendations.suggestions[Number(button.dataset.recommendation)].inputVi; save("brief", input.value); input.focus();
        }));
        let cursor = history.nextCursor, legacyOffset = 0;
        root().querySelector("#scenario-history-more").addEventListener("click", () => run(async token => {
            if (!cursor) return;
            const page = await request(`/sessions?limit=10&before=${encodeURIComponent(cursor.before)}&beforeId=${encodeURIComponent(cursor.beforeId)}`);
            if (!current(token)) return;
            root().querySelector("#scenario-history").insertAdjacentHTML("beforeend", historyRows(page.sessions)); cursor = page.nextCursor;
            root().querySelector("#scenario-history-more").hidden = !cursor; bindSessionLinks();
        }));
        root().querySelector("#scenario-legacy-more").addEventListener("click", () => run(async token => {
            const page = await bridge().api(`/api/v1/conversation/sessions?offset=${legacyOffset+12}&limit=12`, {signal: abort?.signal, cache: "no-store"});
            if (!current(token)) return;
            legacyOffset += 12; root().querySelector("#scenario-legacy-history").insertAdjacentHTML("beforeend", legacyRows(page.sessions));
            root().querySelector("#scenario-legacy-more").hidden = !page.hasMore; bindSessionLinks();
        }));
        bindSessionLinks();
        root().querySelector("#scenario-create").addEventListener("submit", event => {
            event.preventDefault(); if (!available) return;
            const form = new FormData(event.currentTarget);
            run(async token => {
                const program = catalog.generationPrograms[0];
                const operation = await mutation("/drafts", {inputVi: String(form.get("inputVi")).trim(), programCode: program.code, programVersion: program.version, profileId: form.get("profileId")});
                if (current(token)) await track({...operation, action: "EXTRACT"}, token);
            });
        });
        return true;
    }
    function bindSessionLinks() {
        root().querySelectorAll("[data-session]").forEach(link => {
            if (link.dataset.sessionBound) return; link.dataset.sessionBound = "true";
            link.addEventListener("click", event => {
            if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button) return;
            event.preventDefault(); bridge().navigate("conversation", {sessionId: link.dataset.session});
        }); });
    }
    async function track(operation, token) { save("operation", operation); await wait(operation, token); }
    async function wait(pending, token, started = Date.now()) {
        if (!current(token)) return;
        frame(`<div class="scenario-waiting"><h2>Đang xử lý ${escape(pending.stage || pending.action)}</h2><p>Yêu cầu đã được lưu. Có thể rời trang rồi quay lại.</p><button id="scenario-check" type="button">Kiểm tra trạng thái</button></div>`);
        const check = async () => {
            const op = await request(`/operations/${encodeURIComponent(pending.operationId)}`);
            if (!current(token)) return;
            if (op.status === "SUCCEEDED") {
                save("operation", null);
                if (pending.action === "EXTRACT") await preview(await request(`/drafts/${encodeURIComponent(op.draftId)}`), token);
                else if (pending.action === "GENERATE") bridge().navigate("conversation", {sessionId: op.sessionId});
                else {
                    await openSession(pending.sessionId, token);
                    if (pending.action === "TTS" && current(token)) await playMedia(op.mediaId);
                }
            } else if (["FAILED", "UNKNOWN", "CANCELLED", "EXPIRED"].includes(op.status)) {
                save("operation", null);
                frame(`<h2>Tác vụ ${escape(op.status)}</h2><p>${op.status === "UNKNOWN" ? "Chưa xác định được kết quả từ nhà cung cấp. Hệ thống không tự gọi lại để tránh phí trùng." : "Tác vụ không hoàn tất. Bài và câu trả lời đã lưu vẫn được giữ."}</p><p>Mã: ${escape(op.errorCode || op.status)}</p><a href="/conversation${pending.sessionId ? "/" + encodeURIComponent(pending.sessionId) : ""}" data-session="${escape(pending.sessionId || "")}">Quay lại bài học</a>`);
                bindSessionLinks();
            } else if (Date.now() - started < 300000) timer = setTimeout(() => { check().catch(error => { if (current(token)) status(error.message); }); }, Math.max(1500, Math.min(10000, op.pollAfterMs || 2000)));
            else status("Tác vụ còn đang chạy. Bấm kiểm tra trạng thái để tiếp tục, không cần gửi yêu cầu mới.");
        };
        root().querySelector("#scenario-check").addEventListener("click", () => { clearTimeout(timer); run(check); });
        await check();
    }
    async function preview(draft, token) {
        if (!current(token)) return;
        if (draft.status !== "READY") { frame("<h2>Bản nháp chưa sẵn sàng</h2><p>Nội dung không được chấp nhận hoặc đã hết hạn.</p>"); return; }
        const s = draft.specification;
        frame(`<h2>${escape(s.titleVi)}</h2><dl><dt>Bối cảnh</dt><dd>${escape(s.settingVi)}</dd><dt>Vai trò của bạn</dt><dd>${escape(s.learnerRoleVi)}</dd><dt>Mục tiêu</dt><dd>${escape(s.goalVi)}</dd></dl>
            <h3>Giả định để tạo bài</h3><ul>${s.assumptionsVi.map(a => `<li>${escape(a)}</li>`).join("")}</ul>
            <p>${s.characters.map(c => `${escape(c.name)}: ${escape(c.roleVi)}`).join(" · ")}</p>
            <button class="primary-button" id="scenario-confirm">Xác nhận và tạo bài</button><a href="/conversation" data-session="">Viết tình huống khác</a>`);
        bindSessionLinks();
        root().querySelector("#scenario-confirm").addEventListener("click", () => run(async token => {
            const op = await mutation("/sessions", {draftId: draft.draftId, draftVersion: draft.version});
            if (current(token)) await track({...op, action: "GENERATE"}, token);
        }));
    }
    async function openSession(id, token) {
        const loaded = await request(`/sessions/${encodeURIComponent(id)}`); if (!current(token)) return;
        stopAudio(); session = loaded; renderSession();
    }
    function answerKey(activity) { return `answers:${session.sessionId}:${activity.activityId}`; }
    function renderSession() {
        const activity = session.activities.find(a => a.status !== "COMPLETED");
        frame(`<div class="scenario-session-topbar"><h2>${escape(session.titleVi)}</h2><a href="/conversation" data-session="">Tình huống khác</a><button id="scenario-refresh" type="button">Tải lại bài</button></div>
            <p>${escape(session.specification.settingVi)}</p><p>${escape(session.specification.goalVi)}</p><p>Trạng thái: ${escape(session.status)}</p>
            ${activity ? activityHtml(activity) : completedHtml()}`);
        bindSessionLinks(); root().querySelector("#scenario-refresh").addEventListener("click", () => run(token => openSession(session.sessionId, token)));
        if (activity) bindActivity(activity);
        else root().querySelector("#scenario-remix")?.addEventListener("submit", event => {
            event.preventDefault(); const form = new FormData(event.currentTarget);
            run(async token => {
                const op = await mutation(`/sessions/${session.sessionId}/remixes`, {expectedVersion: session.version, inputVi: form.get("inputVi"), profileId: form.get("profileId")});
                if (current(token)) await track({...op, action: "EXTRACT"}, token);
            });
        });
    }
    function activityHtml(a) {
        const drafts = read(answerKey(a), {}), latest = a.attempts.at(-1);
        return `<section class="scenario-audio-panel"><h3>Hoạt động ${a.ordinal} / ${session.activities.length}</h3>
            <div class="scenario-context-card">${session.specification.characters.map(c => `<span>${escape(c.name)} · ${escape(c.roleVi)}</span>`).join(" / ")}</div>
            <div>${a.segments.map(s => `<button type="button" data-play="${escape(s.segmentId)}">Nghe đoạn ${escape(s.segmentId)} · ${escape(s.speakerId)}</button>${s.textEn ? `<p lang="en">${escape(s.textEn)}</p>` : ""}`).join("")}</div><audio id="scenario-audio" controls preload="none" aria-label="Audio đoạn hội thoại"></audio>
            <button type="button" data-help="HINT">Gợi ý cách nghe</button><button type="button" data-help="TRANSCRIPT">Mở bản chép và dịch (có hỗ trợ)</button>
            ${a.translations ? a.translations.map(t => `<p>${escape(t.segmentId)}: ${escape(t.textVi)}</p>`).join("") : ""}
            <p>Lượt nghe: ${a.plays}. ${a.transcriptOpened || a.hintOpened ? "Kết quả sẽ ghi nhận đã dùng hỗ trợ." : "Chưa dùng gợi ý/bản chép."}</p></section>
            <form id="scenario-answer"><p id="scenario-answer-help">Bạn có thể trả lời bằng tiếng Anh hoặc tiếng Việt. AI chấm theo ý hiểu.</p>${a.questions.map(q => `<div class="scenario-input-card"><label for="answer-${escape(q.questionId)}">${escape(q.promptVi)}</label><textarea id="answer-${escape(q.questionId)}" name="${escape(q.questionId)}" rows="3" maxlength="1500" aria-describedby="scenario-answer-help">${escape(drafts[q.questionId] ?? latest?.answers.find(answer => answer.questionId === q.questionId)?.textVi ?? "")}</textarea></div>`).join("")}
            <button class="primary-button" ${a.attempts.length >= 3 || latest?.status === "PENDING" ? "disabled" : ""}>Lưu và chấm câu trả lời (${a.attempts.length}/3)</button></form>
            ${a.attempts.map(t => `<section><h4>Lần ${t.ordinal} · ${escape(t.status)}</h4>${t.result ? t.result.facts.map(f => `<p>${escape(f.questionId)}: ${escape(labels[f.status])}${f.evidenceVi ? ` — “${escape(f.evidenceVi)}”` : ""}</p>`).join("") : ""}</section>`).join("")}
            ${a.attempts.some(t => t.status === "GRADED") && latest?.status !== "PENDING" ? '<button type="button" id="scenario-complete">Hoàn thành hoạt động và xem đáp án</button>' : ""}`;
    }
    function bindActivity(a) {
        const form = root().querySelector("#scenario-answer");
        form.addEventListener("input", () => save(answerKey(a), Object.fromEntries(new FormData(form))));
        form.addEventListener("submit", event => {
            event.preventDefault(); const values = Object.fromEntries(new FormData(form)); save(answerKey(a), values);
            run(async token => {
                const op = await mutation(`/sessions/${session.sessionId}/activities/${a.activityId}/answers`, {expectedVersion: session.version, answers: a.questions.map(q => ({questionId: q.questionId, textVi: values[q.questionId] || ""}))});
                if (current(token)) await track({...op, action: "GRADE", sessionId: session.sessionId}, token);
            });
        });
        root().querySelectorAll("[data-help]").forEach(button => button.addEventListener("click", () => run(async token => {
            const result = await mutation(`/sessions/${session.sessionId}/activities/${a.activityId}/help`, {expectedVersion: session.version, kind: button.dataset.help});
            if (current(token)) { await openSession(session.sessionId, token); if (result.hintVi) status(result.hintVi); }
        })));
        root().querySelectorAll("[data-play]").forEach(button => button.addEventListener("click", () => run(async token => {
            const op = await mutation(`/sessions/${session.sessionId}/activities/${a.activityId}/play`, {expectedVersion: session.version, segmentId: button.dataset.play});
            if (!current(token)) return;
            if (op.status === "READY") { await openSession(session.sessionId, token); if (current(token)) await playMedia(op.mediaId); }
            else await track({...op, action: "TTS", sessionId: session.sessionId}, token);
        })));
        root().querySelector("#scenario-complete")?.addEventListener("click", () => run(async token => {
            const result = await mutation(`/sessions/${session.sessionId}/activities/${a.activityId}/complete`, {expectedVersion: session.version});
            if (current(token)) { session = result; renderSession(); }
        }));
    }
    async function playMedia(id) {
        const audio = root().querySelector("#scenario-audio"); if (!audio) return;
        audio.src = `${base}/media/${encodeURIComponent(id)}`;
        try { await audio.play(); } catch (_) { status("Audio đã sẵn sàng. Bấm nút phát trên trình nghe."); }
    }
    function completedHtml() {
        if (session.status !== "COMPLETED") return "<p>Bài đang chuẩn bị hoặc gặp lỗi. Hãy tải lại để xem trạng thái đã lưu.</p>";
        const facts = session.activities.flatMap(a => a.attempts[0]?.result?.facts || []), captured = facts.filter(f => f.status === "CAPTURED").length;
        return `<h3>Đã hoàn thành</h3><p>Lần trả lời đầu: hiểu đúng ${captured}/${facts.length} ý đã được chấm. Đây không phải điểm chứng chỉ.</p>
            ${session.activities.map(a => `<details><summary>Hoạt động ${a.ordinal}: bản chép và đáp án</summary>${a.segments.map(s => `<p lang="en">${escape(s.textEn)}</p>`).join("")}${a.questions.map(q => `<p>${escape(q.promptVi)} — ${escape(q.answerVi)}</p>`).join("")}</details>`).join("")}
            <form id="scenario-remix"><label for="scenario-remix-input">Bạn muốn biến thể như thế nào?</label><textarea id="scenario-remix-input" name="inputVi" maxlength="1200" required rows="3"></textarea>
            <label for="scenario-remix-profile">Mức luyện</label><select id="scenario-remix-profile" name="profileId">${Object.keys(profiles).map(p => `<option value="${p}">${profiles[p]}</option>`).join("")}</select><button class="primary-button">Xem trước biến thể mới</button></form>`;
    }
    window.DictaLabScenario = {show, leave};
})();
