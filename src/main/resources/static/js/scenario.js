(() => {
    "use strict";
    const base = "/api/v2/scenarios", bridge = () => window.DictaLabListeningBridge;
    const root = () => document.querySelector("#conversation-content");
    const escape = value => String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#039;");
    const labels = {CAPTURED: "Hiểu đúng", PARTIAL: "Hiểu một phần", MISSED: "Bỏ lỡ", INCORRECT: "Chưa đúng", UNCERTAIN: "Chưa đủ căn cứ"};
    const stageLabels = {EXTRACT: "Phân tích tình huống", GENERATE: "Tạo bài luyện", GRADE: "Chấm câu trả lời", TTS: "Tạo âm thanh"};
    const statusLabels = {COMPLETED: "Hoàn thành", ACTIVE: "Đang diễn ra", PREPARING: "Đang chuẩn bị", PREPARATION_FAILED: "Lỗi chuẩn bị bài", GENERATION_FAILED: "Lỗi tạo bài", FAILED: "Tác vụ không hoàn tất", UNKNOWN: "Chưa xác định được kết quả", CANCELLED: "Đã huỷ", EXPIRED: "Đã hết hạn"};
    const profiles = {supported: "Có hỗ trợ", standard: "Tiêu chuẩn", challenge: "Thử thách", a1: "A1", a2: "A2", b1: "B1", b2: "B2", c1: "C1", c2: "C2"};
    const skills = {detail: "Nghe chi tiết", "main-idea": "Hiểu ý chính", reason: "Hiểu lý do", purpose: "Hiểu mục đích", "speaker-attitude": "Hiểu quan điểm", "supported-inference": "Nghe và suy luận"};
    let revision = 0, owner = null, abort = null, timer = null, session = null, busy = false, playback = 0;
    const memory = new Map();
    const storageKey = (suffix, user = owner) => `dictalab:scenario:${user}:${suffix}`;
    function read(suffix, fallback, user = owner) { const key = storageKey(suffix,user); try { return JSON.parse(sessionStorage.getItem(key)) ?? memory.get(key) ?? fallback; } catch (_) { return memory.get(key) ?? fallback; } }
    function save(suffix, value, user = owner) { const key = storageKey(suffix,user); memory.set(key,value); try { if (value === null) sessionStorage.removeItem(key); else sessionStorage.setItem(key, JSON.stringify(value)); } catch (_) { /* In-memory retry protection remains when storage is unavailable. */ } }
    const current = token => token === revision && owner === bridge().currentUser()?.id;
    const request = (path, options = {}) => bridge().api(base + path, {...options, signal: abort?.signal, cache: "no-store"});
    function status(message) { const node = root()?.querySelector("#scenario-status"); if (node) node.textContent = message; }
    function frame(html) { stopAudio(); root().innerHTML = `<div class="scenario-browser">${html}<p id="scenario-status" class="scenario-status" role="status" aria-live="polite"></p></div>`; }
    function stopAudio() { playback++; const audio = root()?.querySelector("audio"); if (audio) { audio.pause(); audio.removeAttribute("src"); audio.load(); } }
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
            const savedDraft = !id && !pending ? read("draft", null) : null;
            if (savedDraft?.draftId) {
                try {
                    const draft = await request(`/drafts/${encodeURIComponent(savedDraft.draftId)}`);
                    if (current(token) && draft.status === "READY") { await preview(draft, token); return; }
                } catch (_) { /* draft expired/gone — fall through to catalog */ }
                save("draft", null);
            }
            if (id) await openSession(id, token); else return await catalog(token);
        } catch (error) { if (current(token) && error.name !== "AbortError") status(error.message); }
    }
    async function catalog(token) {
        const [catalog, suggestions, history, goals, recommendations, legacy, savedPreferences] = await Promise.all([
            request("/catalog"), request("/suggestions"), request("/sessions?limit=10"), request("/learning-goals"), request("/recommendations"),
            bridge().api("/api/v1/conversation/sessions?offset=0&limit=12", {signal: abort?.signal, cache: "no-store"}).catch(error => ({sessions: [], error: error.message})),
            request("/learning-preferences")]);
        if (!current(token)) return;
        if (!catalog.enabled) return false;
        const programs = catalog.practicePrograms || [], preferences = savedPreferences.preferences || [];
        const program = programs.find(p => p.code === read("program", preferences[0]?.programCode)) || programs[0];
        const storedPreference = program && preferences.find(p => p.programCode === program.code);
        const preference = storedPreference?.programVersion === program?.version ? storedPreference : null;
        const available = program ? program.generationAvailable && Boolean(preference) : catalog.generationAvailable;
        const groups = [...new Map(suggestions.suggestions.map(s => [s.groupId || s.id, {id: s.groupId || s.id, title: s.groupTitleVi || s.titleVi}])).values()];
        const historyRows = rows => rows.map(s => `<p><a href="/conversation/${encodeURIComponent(s.sessionId)}" data-session="${escape(s.sessionId)}">${escape(s.titleVi)}</a> · ${escape(s.status)}</p>`).join("");
        const legacyRows = rows => rows.map(s => `<p><a href="/conversation/${encodeURIComponent(s.id)}" data-session="${escape(s.id)}">Bài trước đây · ${escape(s.scenario)} · ${escape(s.difficulty)}</a></p>`).join("");
        frame(`${catalog.generationMode === "LOCAL_PREVIEW" ? '<p role="status">Thử nghiệm local · Program DRAFT, chưa được chuyên gia duyệt. Kết quả không phải đánh giá chứng chỉ.</p>' : ''}
            ${program ? personalizationHtml(programs, program) : ''}<div class="scenario-hero"><form id="scenario-create" class="scenario-input-card">
            <label class="scenario-brief-label" for="scenario-input">Bạn muốn cải thiện điều gì?</label>
            <textarea id="scenario-input" class="scenario-brief" name="inputVi" rows="5" maxlength="2000" ${program ? '' : 'required'} aria-describedby="scenario-input-help"></textarea>
            <p id="scenario-input-help">Mô tả chủ đề hoặc nhu cầu bằng tiếng Việt. ${program ? 'Có thể để trống để AI đề xuất tình huống theo mục tiêu đã lưu.' : ''} Tránh nhập thông tin cá nhân hoặc khóa truy cập.</p>
            ${program ? `<p>${escape(program.displayNameVi)} · ${preference ? escape(profiles[preference.target.value] || preference.target.value) : 'Hãy lưu mục tiêu luyện nghe trước khi tạo bài.'}</p>` : `<fieldset class="scenario-profile-options"><legend>Mức luyện giao tiếp</legend>${(catalog.difficultyProfiles.length ? catalog.difficultyProfiles : ['supported','standard','challenge']).map((p,i) => `<label><input type="radio" name="profileId" value="${escape(p)}" ${i === 0 ? "checked" : ""}>${escape(profiles[p] || p)}</label>`).join("")}</fieldset>`}
            <p>Luyện kỹ năng theo mục tiêu; kết quả bài này không quy đổi thành điểm TOEIC hoặc band IELTS.</p>
            <button id="scenario-create-button" class="primary-button" ${available ? "" : "disabled"}>Phân tích và xem trước</button>
            ${available ? "" : "<p>Chương trình đang chờ duyệt bộ sinh bài. Bài đã lưu vẫn có thể mở.</p>"}
            </form>${recommendations.suggestions.length > 0 ? `<aside class="scenario-suggestions-col"><h2>Gợi ý bài luyện</h2><p>${escape(recommendations.messageVi)}</p>
            ${recommendations.suggestions.slice(0,3).map((s,index) => `<button class="scenario-suggestion-card" type="button" data-recommendation="${index}"><strong>${escape(s.titleVi)}</strong><span>${escape(s.reasonVi || s.inputVi)}</span></button>`).join("")}
            ${goals.map(g => `<p class="scenario-goal-display">Mục tiêu tự khai: ${escape(profiles[g.target.value] || g.target.value)}</p>`).join("")}</aside>` : ""}</div>
            <section class="scenario-explorer"><h2>Gợi ý để bạn tùy chỉnh</h2><p>Chọn một gợi ý, rồi sửa mô tả theo nhu cầu của bạn. Bạn cũng có thể nhập tình huống hoàn toàn mới.</p>
            <div class="scenario-program-pills" role="group" aria-label="Nhóm gợi ý">${groups.map((g,i) => `<button class="scenario-pill" type="button" data-group="${escape(g.id)}" aria-pressed="${i === 0}">${escape(g.title)}</button>`).join("")}</div>
            <div class="scenario-group-grid">${suggestions.suggestions.map((s,index) => `<button class="scenario-group-card" data-suggestion="${index}" data-suggestion-group="${escape(s.groupId || s.id)}" ${index && (s.groupId || s.id) !== groups[0].id ? "hidden" : ""} type="button"><strong>${escape(s.titleVi)}</strong></button>`).join("")}</div></section>
            <section><h2>Bài đã tạo</h2><div id="scenario-history">${history.sessions.length ? historyRows(history.sessions) : "<p>Chưa có bài học mới.</p>"}</div><button type="button" id="scenario-history-more" ${history.nextCursor ? "" : "hidden"}>Xem thêm bài</button></section>
            <section><h2>Bài trước đây</h2><div id="scenario-legacy-history">${legacyRows(legacy.sessions || []) || "<p>Chưa có bài trước đây.</p>"}</div>${legacy.error ? `<p role="status">${escape(legacy.error)}</p>` : ""}<button type="button" id="scenario-legacy-more" ${legacy.hasMore ? "" : "hidden"}>Xem thêm bài trước đây</button></section>`);
        const input = root().querySelector("#scenario-input"); input.value = read("brief", "");
        input.addEventListener("input", () => save("brief", input.value));
        let preferenceDirty = false;
        if (program) bindPersonalization(program, preference, token, () => {
            preferenceDirty = true; root().querySelector('#scenario-create-button').disabled = true;
            status('Mục tiêu đã thay đổi. Hãy lưu mục tiêu trước khi tạo bài.');
        }, storedPreference?.version || 0);
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
            event.preventDefault(); if (!available || preferenceDirty) return;
            const form = new FormData(event.currentTarget);
            run(async token => {
                const selected = program || catalog.generationPrograms[0];
                const operation = await mutation("/drafts", {inputVi: String(form.get("inputVi") || '').trim(), programCode: selected.code, programVersion: selected.version, profileId: program ? '' : form.get("profileId")});
                if (current(token)) await track({...operation, action: "EXTRACT"}, token);
            });
        });
        return true;
    }
    function personalizationHtml(programs, program) {
        return `<section class="scenario-personalization" aria-labelledby="scenario-personalization-title"><h2 id="scenario-personalization-title">Mục tiêu luyện nghe của bạn</h2>
            <form id="scenario-personalization"><label for="scenario-program">Bạn luyện nghe để làm gì?</label>
            <select id="scenario-program" name="programCode">${programs.map(p => `<option value="${escape(p.code)}" ${p.code === program.code ? 'selected' : ''}>${escape(p.displayNameVi)}</option>`).join('')}</select>
            <div id="scenario-personalization-fields"></div><button type="submit" class="secondary-button">Lưu mục tiêu luyện nghe</button>
            <p>Bài luyện được tạo theo mức mục tiêu bạn chọn. Nội dung đang được kiểm chứng, không phải kết quả đánh giá trình độ.</p>
            ${program.generationAvailable ? '' : '<p role="status">Chương trình chưa mở tạo bài. Bạn có thể lưu mục tiêu và tiếp tục bài đã có.</p>'}</form></section>`;
    }
    function bindPersonalization(program, preference, token, changed, expectedVersion) {
        const scale = program.targetScales.find(s => s.component === 'LISTENING');
        if (!scale) return;
        const defaultValue = scale.labels.length ? (scale.scaleId === 'cefr-listening' ? 'b1' : 'standard') : program.code === 'ielts-listening' ? '6.5' : '350';
        const value = preference?.target.value || defaultValue;
        const label = program.code === 'ielts-listening' ? 'Mục tiêu IELTS Listening band' : program.code === 'toeic-listening' ? 'Mục tiêu TOEIC Listening (5–495)' : 'Mức giao tiếp mong muốn (CEFR)';
        root().querySelector('#scenario-personalization-fields').innerHTML = `<label for="scenario-target">${label}</label>${scale.labels.length
            ? `<select id="scenario-target" name="targetValue" required aria-describedby="scenario-score-help">${scale.labels.map(v => `<option value="${escape(v)}" ${v === value ? 'selected' : ''}>${escape(profiles[v] || v)}</option>`).join('')}</select>`
            : `<input id="scenario-target" name="targetValue" type="number" min="${escape(scale.min)}" max="${escape(scale.max)}" step="${escape(scale.step)}" value="${escape(value)}" required aria-describedby="scenario-score-help">`}
            <p id="scenario-score-help">${scale.scaleId === 'cefr-listening' ? 'Chọn mức CEFR từ A1 đến C2.' : scale.labels.length ? 'Chọn mức luyện phù hợp.' : `Thang Listening ${escape(scale.min)}–${escape(scale.max)}, bước ${escape(scale.step)}. Không tự quy đổi từ điểm tổng.`}</p>
            <p id="scenario-profile-help" role="status"></p>`;
        const target = root().querySelector('#scenario-target'); target.value = value;
        const refreshProfile = () => {
            const level = target.value;
            const profile = program.difficultyProfiles.find(p => p.targetRange
                ? p.targetRange.scaleId === scale.scaleId && Number(level) >= Number(p.targetRange.min) && Number(level) <= Number(p.targetRange.max)
                : p.id === level);
            root().querySelector('#scenario-profile-help').textContent = profile ? `Mức luyện: ${profiles[profile.id] || profile.id}. ${profile.inferencePolicy === 'EXPLICIT_ONLY' ? 'Ưu tiên thông tin được nói rõ.' : 'Có thể luyện suy luận có căn cứ.'}` : 'Hãy nhập mức Listening hợp lệ.';
        };
        refreshProfile(); target.addEventListener('input',refreshProfile); target.addEventListener('change',refreshProfile);
        root().querySelector('#scenario-personalization').addEventListener('input',changed);
        root().querySelector('#scenario-personalization').addEventListener('change',changed);
        root().querySelector('#scenario-program').addEventListener('change',event => {
            save('brief',root().querySelector('#scenario-input').value); save('program',event.target.value); run(() => catalog(token));
        });
        root().querySelector('#scenario-personalization').addEventListener('submit',event => {
            event.preventDefault(); const form = new FormData(event.currentTarget);
            run(async token => {
                await mutation('/learning-preferences',{programCode:program.code,programVersion:program.version,
                    target:{scaleId:scale.scaleId,component:'LISTENING',value:String(form.get('targetValue'))},
                    currentValue:'',focusSkill:'main-idea',expectedVersion});
                if (current(token)) { save('program',program.code); await catalog(token); status('Đã lưu mục tiêu. Bạn có thể tạo bài luyện.'); }
            });
        });
    }
    function contextHtml(context) {
        return context ? `<p class="scenario-goal-display">${escape(context.programCode)} · Mục tiêu Listening: ${escape(profiles[context.target.value] || context.target.value)} · ${escape(skills[context.focusSkill])} · ${escape(profiles[context.profile.id])}</p>` : '';
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
        frame(`<div class="scenario-waiting"><h2>Đang xử lý: ${escape(stageLabels[pending.stage || pending.action] || pending.stage || pending.action)}</h2><p>Yêu cầu đã được lưu. Có thể rời trang rồi quay lại.</p><button id="scenario-check" type="button">Kiểm tra trạng thái</button></div>`);
        const check = async () => {
            const op = await request(`/operations/${encodeURIComponent(pending.operationId)}`);
            if (!current(token)) return;
            if (op.status === "SUCCEEDED") {
                save("operation", null);
                if (pending.action === "EXTRACT") { save("draft", {draftId: op.draftId, sessionId: pending.sessionId}); await preview(await request(`/drafts/${encodeURIComponent(op.draftId)}`), token); }
                else if (pending.action === "GENERATE") bridge().navigate("conversation", {sessionId: op.sessionId});
                else {
                    await openSession(pending.sessionId, token);
                    if (pending.action === "TTS" && current(token)) await playMedia(op.mediaId);
                }
            } else if (["FAILED", "UNKNOWN", "CANCELLED", "EXPIRED"].includes(op.status)) {
                save("operation", null);
                if (pending.sessionId && ["GRADE", "TTS"].includes(pending.action)) {
                    await openSession(pending.sessionId, token);
                    if (current(token)) status(`Tác vụ ${op.status}: ${op.errorCode || op.status}. Mã tác vụ: ${pending.operationId}. ${op.status === "UNKNOWN" ? "Chưa xác định kết quả; hệ thống không tự gọi lại để tránh phí trùng." : "Bài và câu trả lời đã lưu vẫn được giữ."}`);
                    return;
                }
                frame(`<h2>${escape(statusLabels[op.status] || op.status)}</h2><p>${op.status === "UNKNOWN" ? "Chưa xác định được kết quả từ nhà cung cấp. Hệ thống không tự gọi lại để tránh phí trùng." : "Tác vụ không hoàn tất. Bài và câu trả lời đã lưu vẫn được giữ."}</p><p>Mã: ${escape(op.status)}${op.errorCode && op.errorCode !== op.status ? " · " + escape(op.errorCode) : ""}</p><a href="/conversation${pending.sessionId ? "/" + encodeURIComponent(pending.sessionId) : ""}" data-session="${escape(pending.sessionId || "")}">Quay lại bài học</a>`);
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
        frame(`<h2>${escape(s.titleVi)}</h2>${contextHtml(draft.learningContext)}<dl><dt>Bối cảnh</dt><dd>${escape(s.settingVi)}</dd><dt>Vai trò của bạn</dt><dd>${escape(s.learnerRoleVi)}</dd><dt>Mục tiêu</dt><dd>${escape(s.goalVi)}</dd></dl>
            <h3>Dữ kiện bạn cung cấp</h3><ul>${s.constraintsVi.map(a => `<li>${escape(a)}</li>`).join("")}</ul>
            <h3>Suy luận và giả định cần xác nhận</h3><ul>${s.assumptionsVi.map(a => `<li>${escape(a)}</li>`).join("")}</ul>
            <p>${s.characters.map(c => `${escape(c.name)}: ${escape(c.roleVi)}`).join(" · ")}</p>
            <div class="scenario-result-actions"><button class="primary-button" id="scenario-confirm">Xác nhận và tạo bài</button><a href="/conversation" data-session="">Viết tình huống khác</a></div>`);
        bindSessionLinks();
        root().querySelector("#scenario-confirm").addEventListener("click", () => run(async token => {
            save("draft", null);
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
        const selected = read(`review:${session.sessionId}`, null);
        const review = session.activities.find(a => a.activityId === selected && a.status === "COMPLETED");
        const activity = review || session.activities.find(a => a.status !== "COMPLETED");
        frame(`<div class="scenario-session-topbar"><h2>${escape(session.titleVi)}</h2><a href="/conversation" data-session="">Tình huống khác</a><button id="scenario-refresh" type="button">Tải lại bài</button></div>
            ${contextHtml(session.learningContext)}<p>${escape(session.specification.settingVi)}</p><p>${escape(session.specification.goalVi)}</p><p>Trạng thái: ${escape(statusLabels[session.status] || session.status)}</p>
            ${session.activities.filter(a => a.status === "COMPLETED").map(a => `<button type="button" data-review="${escape(a.activityId)}">Chữa hoạt động ${a.ordinal}</button>`).join("")}
            ${review ? reviewHtml(review) : activity ? activityHtml(activity) : completedHtml()}`);
        bindSessionLinks(); root().querySelector("#scenario-refresh").addEventListener("click", () => run(token => openSession(session.sessionId, token)));
        root().querySelectorAll("[data-review]").forEach(button => button.addEventListener("click", () => run(() => {
            save(`review:${session.sessionId}`, button.dataset.review); renderSession();
        })));
        if (activity) bindAudio(activity);
        if (review) root().querySelector("#scenario-next").addEventListener("click", () => run(() => { save(`review:${session.sessionId}`, null); renderSession(); }));
        else if (activity) bindActivity(activity);
        else root().querySelector("#scenario-remix")?.addEventListener("submit", event => {
            event.preventDefault(); const form = new FormData(event.currentTarget);
            run(async token => {
                const op = await mutation(`/sessions/${session.sessionId}/remixes`, {expectedVersion: session.version, inputVi: form.get("inputVi"), profileId: session.learningContext?.profile.id || form.get("profileId")});
                if (current(token)) await track({...op, action: "EXTRACT"}, token);
            });
        });
    }
    function audioHtml(a) {
        const names = new Map(session.specification.characters.map(c => [c.id, c.name]));
        return `<section class="scenario-audio-panel"><h3>Hoạt động ${a.ordinal} / ${session.activities.length}</h3>
            <div class="scenario-context-card">${session.specification.characters.map(c => `<span>${escape(c.name)} · ${escape(c.roleVi)}</span>`).join(" / ")}</div>
            <div class="scenario-audio-controls"><button type="button" class="primary-button" id="scenario-play-all">▶ Nghe toàn bộ hội thoại</button></div>
            <div class="scenario-segment-list">${a.segments.map((s, si) => `<button type="button" class="scenario-support-btn" data-play="${escape(s.segmentId)}">Đoạn ${si + 1} · ${escape(names.get(s.speakerId) || s.speakerId)}</button>${s.textEn ? `<p lang="en" class="scenario-segment-text">${escape(s.textEn)}</p>` : ""}`).join("")}</div><audio id="scenario-audio" controls preload="none" aria-label="Audio đoạn hội thoại"></audio>
            ${a.translations ? a.translations.map((t, ti) => `<p><strong>Đoạn ${ti + 1}:</strong> ${escape(t.textVi)}</p>`).join("") : ""}
            <p>Lần yêu cầu phát: ${a.plays}. ${a.transcriptOpened && a.hintOpened ? "Ghi nhận: đã dùng cả gợi ý và bản chép." : a.transcriptOpened ? "Ghi nhận: đã mở bản chép." : a.hintOpened ? "Ghi nhận: đã dùng gợi ý chiến lược (không ảnh hưởng như mở bản chép)." : "Chưa dùng gợi ý/bản chép trước khi hoàn thành."}</p></section>`;
    }
    function attemptsHtml(a) {
        return a.attempts.map(t => `<section><h4>Lần ${t.ordinal} · ${escape(labels[t.status] || statusLabels[t.status] || t.status)}</h4>
            <p>${t.support?.transcriptOpened && t.support?.hintOpened ? "Dùng cả gợi ý và bản chép khi nộp." : t.support?.transcriptOpened ? "Có mở bản chép khi nộp." : t.support?.hintOpened ? "Chỉ dùng gợi ý chiến lược khi nộp." : "Không dùng gợi ý/bản chép khi nộp."}</p>
            ${t.answers.map(answer => `<p>Câu ${escape(answer.questionId.replace(/^q/, ""))}: ${escape(answer.textVi)}</p>`).join("")}
            ${t.result ? t.result.facts.map(f => `<p>${escape(f.questionId)}: ${escape(labels[f.status])}${f.evidenceVi ? ` — “${escape(f.evidenceVi)}”` : ""}</p>`).join("") : ""}
            ${["FAILED", "UNKNOWN"].includes(t.status) ? `<p>${t.status === "UNKNOWN" ? "Chưa xác định kết quả; không tự gọi AI lại để tránh phí trùng." : "Chưa chấm được; đây không phải câu trả lời sai."} ${escape(t.gradeRetry?.errorCode)} · Mã tác vụ: ${escape(t.gradeRetry?.operationId)}</p>` : ""}
            ${a.status !== "COMPLETED" && t.gradeRetry?.allowed ? `<button type="button" data-retry-grade="${escape(t.attemptId)}">Thử chấm lại câu trả lời đã lưu (còn ${t.gradeRetry.remaining} lần)</button>` : ""}
            ${t.status === "FAILED" && t.gradeRetry?.remaining === 0 ? "<p>Đã hết lượt thử chấm lại. Giữ mã tác vụ để được hỗ trợ; không cần gửi lặp.</p>" : ""}</section>`).join("");
    }
    function reviewHtml(a) {
        return `<h3>Chữa hoạt động ${a.ordinal}</h3>${audioHtml(a)}${attemptsHtml(a)}
            ${a.questions.map(q => `<section><h4>${escape(q.promptVi)}</h4><p>Đáp án: ${escape(q.answerVi)}</p>
                ${(q.evidence || []).map(e => `<p>Bằng chứng ở đoạn ${escape(e.segmentId)}: <span lang="en">“${escape(e.quoteEn)}”</span></p>`).join("")}</section>`).join("")}
            <button type="button" id="scenario-next">${session.status === "COMPLETED" ? "Xem tổng kết" : "Sang hoạt động tiếp theo"}</button>`;
    }
    function activityHtml(a) {
        const drafts = read(answerKey(a), {}), latest = a.attempts.at(-1);
        return `${audioHtml(a)}<section>
            <button type="button" data-help="HINT">Gợi ý cách nghe</button><button type="button" data-help="TRANSCRIPT">Mở bản chép và dịch (có hỗ trợ)</button>
            </section>
            <form id="scenario-answer"><p id="scenario-answer-help">Bạn có thể trả lời bằng tiếng Anh hoặc tiếng Việt. AI chấm theo ý hiểu.</p>${a.questions.map(q => `<div class="scenario-input-card"><label for="answer-${escape(q.questionId)}">${escape(q.promptVi)}</label><textarea id="answer-${escape(q.questionId)}" name="${escape(q.questionId)}" class="scenario-answer-field" rows="3" maxlength="1500" aria-describedby="scenario-answer-help">${escape(drafts[q.questionId] ?? latest?.answers.find(answer => answer.questionId === q.questionId)?.textVi ?? "")}</textarea></div>`).join("")}
            <button class="primary-button" ${a.attempts.length >= 3 || latest?.status === "PENDING" ? "disabled" : ""}>Lưu và chấm câu trả lời (${a.attempts.length}/3)</button>
            ${a.attempts.length >= 3 && !a.attempts.some(t => t.status === "GRADED") && !a.attempts.some(t => t.gradeRetry?.allowed) ? `<p class="scenario-unavailable-notice"><strong>Bài bị chặn:</strong> Cả ${a.attempts.length} lần chấm đều không có kết quả. Giữ mã tác vụ ở trên để liên hệ hỗ trợ. Không cần gửi yêu cầu mới.</p>` : ""}
            </form>
            ${attemptsHtml(a)}
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
        root().querySelectorAll("[data-retry-grade]").forEach(button => button.addEventListener("click", () => run(async token => {
            const op = await mutation(`/sessions/${session.sessionId}/activities/${a.activityId}/attempts/${button.dataset.retryGrade}/retry-grade`, {expectedVersion: session.version});
            if (current(token)) await track({...op, action: "GRADE", sessionId: session.sessionId}, token);
        })));
        root().querySelector("#scenario-complete")?.addEventListener("click", () => run(async token => {
            const result = await mutation(`/sessions/${session.sessionId}/activities/${a.activityId}/complete`, {expectedVersion: session.version});
            if (current(token)) { session = result; save(`review:${session.sessionId}`, a.activityId); renderSession(); }
        }));
    }
    function bindAudio(a) {
        root().querySelectorAll("[data-play]").forEach(button => button.addEventListener("click", () => run(async token => {
            const op = await mutation(`/sessions/${session.sessionId}/activities/${a.activityId}/play`, {expectedVersion: session.version, segmentId: button.dataset.play});
            if (!current(token)) return;
            if (op.status === "READY") { await openSession(session.sessionId, token); if (current(token)) await playMedia(op.mediaId); }
            else await track({...op, action: "TTS", sessionId: session.sessionId}, token);
        })));
        // OPT-02: Play-all button — requests each segment in order and plays sequentially
        root().querySelector("#scenario-play-all")?.addEventListener("click", () => run(async token => {
            for (const seg of a.segments) {
                if (!current(token)) return;
                const op = await mutation(`/sessions/${session.sessionId}/activities/${a.activityId}/play`, {expectedVersion: session.version, segmentId: seg.segmentId});
                if (!current(token)) return;
                if (op.status !== "READY") { await track({...op, action: "TTS", sessionId: session.sessionId}, token); return; }
                // Reload session to sync version before next mutation
                const loaded = await request(`/sessions/${encodeURIComponent(session.sessionId)}`);
                if (!current(token)) return;
                session = loaded;
                await playMedia(op.mediaId);
                // Wait for audio to finish before playing next segment
                await new Promise(resolve => {
                    const audio = root()?.querySelector("#scenario-audio");
                    if (!audio || audio.paused) { resolve(); return; }
                    audio.addEventListener("ended", resolve, {once: true});
                    audio.addEventListener("error", resolve, {once: true});
                });
            }
        }));
    }
    async function playMedia(id) {
        const audio = root().querySelector("#scenario-audio"); if (!audio) return;
        const token = revision, turn = ++playback;
        const active = () => current(token) && turn === playback && root()?.querySelector("#scenario-audio") === audio;
        audio.addEventListener("error", () => { if (active()) status("Không tải hoặc giải mã được audio. Bấm nghe lại đoạn này; audio đã lưu sẽ được dùng lại."); }, {once: true});
        audio.src = `${base}/media/${encodeURIComponent(id)}`;
        try { await audio.play(); } catch (error) {
            if (!active() || error.name === "AbortError") return;
            status(error.name === "NotAllowedError" ? "Trình duyệt chặn tự phát. Bấm nút phát trên trình nghe."
                : "Không phát được audio. Bấm nghe lại đoạn này; audio đã lưu sẽ được dùng lại.");
        }
    }
    function completedHtml() {
        if (session.status !== "COMPLETED") {
            const isTerminalError = ["PREPARATION_FAILED", "GENERATION_FAILED", "FAILED"].includes(session.status);
            return isTerminalError
                ? `<p>Bài gặp lỗi: ${escape(statusLabels[session.status] || session.status)}. Hãy tạo bài mới hoặc liên hệ hỗ trợ.</p><p><a href="/conversation" data-session="">Tạo bài mới</a></p>`
                : "<p>Bài đang chuẩn bị. Hãy tải lại để xem trạng thái đã lưu.</p>";
        }
        const summary = latest => {
            const attempts = session.activities.map(a => latest ? a.attempts.filter(t => t.status === "GRADED").at(-1) : a.attempts[0]);
            const facts = attempts.flatMap(t => t?.status === "GRADED" ? t.result?.facts || [] : []);
            const total = session.activities.reduce((n, a) => n + a.questions.length, 0);
            return `<p>${latest ? "Lần chấm thành công mới nhất" : "Lần trả lời đầu"}: ${facts.filter(f => f.status === "CAPTURED").length} câu hiểu đúng; có kết quả ${facts.length}/${total} câu; ${total - facts.length} câu chưa chấm được; ${facts.filter(f => f.status === "PARTIAL").length} hiểu một phần; ${facts.filter(f => f.status === "INCORRECT").length} chưa đúng; ${facts.filter(f => f.status === "MISSED").length} bỏ lỡ; ${facts.filter(f => f.status === "UNCERTAIN").length} chưa đủ căn cứ. ${attempts.filter(t => t?.support?.transcriptOpened || t?.support?.hintOpened).length} hoạt động có dùng hỗ trợ.</p>`;
        };
        return `<h3>Đã hoàn thành</h3>${summary(false)}${summary(true)}<p>Đây không phải điểm chứng chỉ. Kết quả có hỗ trợ không phải phép đo nghe độc lập.</p>
            <form id="scenario-remix"><label for="scenario-remix-input">Bạn muốn biến thể như thế nào?</label><textarea id="scenario-remix-input" name="inputVi" class="scenario-answer-field" maxlength="1200" required rows="3"></textarea>
            ${session.learningContext ? `<p>Biến thể giữ mục tiêu và mức luyện của bài này: ${escape(profiles[session.learningContext.profile.id])}.</p>` : `<label for="scenario-remix-profile">Mức luyện</label><select id="scenario-remix-profile" name="profileId">${Object.keys(profiles).map(p => `<option value="${p}">${profiles[p]}</option>`).join("")}</select>`}<button class="primary-button">Xem trước biến thể mới</button></form>`;
    }
    window.DictaLabScenario = {show, leave};
})();
