(() => {
    "use strict";
    const base = "/api/v2/scenarios", bridge = () => window.DictaLabListeningBridge;
    const root = () => document.querySelector("#conversation-content");
    const escape = value => String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#039;");
    const labels = {CAPTURED: "Hiểu đúng", PARTIAL: "Hiểu một phần", MISSED: "Bỏ lỡ", INCORRECT: "Chưa đúng", UNCERTAIN: "Chưa đủ căn cứ"};
    const stageLabels = {EXTRACT: "Phân tích tình huống", GENERATE: "Tạo bài luyện", GRADE: "Chấm câu trả lời", TTS: "Tạo âm thanh"};
    const statusLabels = {COMPLETED: "Hoàn thành", ACTIVE: "Đang diễn ra", GRADED: "Đã chấm", PREPARING: "Đang chuẩn bị", PREPARATION_FAILED: "Lỗi chuẩn bị bài", GENERATION_FAILED: "Lỗi tạo bài", FAILED: "Tác vụ không hoàn tất", UNKNOWN: "Chưa xác định được kết quả", CANCELLED: "Đã huỷ", EXPIRED: "Đã hết hạn"};
    const profiles = {supported: "Có hỗ trợ", standard: "Tiêu chuẩn", challenge: "Thử thách", a1: "A1", a2: "A2", b1: "B1", b2: "B2", c1: "C1", c2: "C2"};
    const skills = {detail: "Nghe chi tiết", "main-idea": "Hiểu ý chính", reason: "Hiểu lý do", purpose: "Hiểu mục đích", "speaker-attitude": "Hiểu quan điểm", "supported-inference": "Nghe và suy luận"};
    const programNames = {"general-communication": "Giao tiếp", "toeic-listening": "TOEIC Listening", "ielts-listening": "IELTS Listening"};
    let revision = 0, owner = null, abort = null, timer = null, session = null, busy = false, playback = 0, cancelSequence = null, cleanupMedia = null;
    const memory = new Map();
    const storageKey = (suffix, user = owner) => `dictalab:scenario:${user}:${suffix}`;
    function read(suffix, fallback, user = owner) { const key = storageKey(suffix,user); try { return JSON.parse(sessionStorage.getItem(key)) ?? memory.get(key) ?? fallback; } catch (_) { return memory.get(key) ?? fallback; } }
    function save(suffix, value, user = owner) { const key = storageKey(suffix,user); memory.set(key,value); try { if (value === null) sessionStorage.removeItem(key); else sessionStorage.setItem(key, JSON.stringify(value)); } catch (_) { /* In-memory retry protection remains when storage is unavailable. */ } }
    const current = token => token === revision && owner === bridge().currentUser()?.id;
    const request = (path, options = {}) => bridge().api(base + path, {...options, signal: abort?.signal, cache: "no-store"});
    function status(message) { const node = root()?.querySelector("#scenario-status"); if (node) node.textContent = message; }
    function frame(html) { stopAudio(); root().innerHTML = `<div class="scenario-browser">${html}<p id="scenario-status" class="scenario-status" role="status" aria-live="polite"></p></div>`; }
    function focusStep(selector = '#scenario-step-heading') { const heading = root()?.querySelector(selector); heading?.focus(); heading?.scrollIntoView?.({block: 'nearest'}); }
    function busyControls(locked) {
        root()?.querySelectorAll('button').forEach(button => {
            if (button.id === 'scenario-stop-audio') return;
            if (locked && !button.disabled) { button.dataset.busyDisabled = 'true'; button.disabled = true; }
            else if (!locked && button.dataset.busyDisabled) { button.disabled = false; delete button.dataset.busyDisabled; }
        });
    }
    function loadError(error, id) {
        frame(`<h2 id="scenario-step-heading" tabindex="-1">Chưa tải được bài học</h2><p>${escape(error.message)}</p><button id="scenario-load-retry" type="button">Thử tải lại</button><a href="/conversation" data-session="">Về chọn bài</a>`);
        root().querySelector('#scenario-load-retry').addEventListener('click', () => show(id)); bindSessionLinks(); focusStep();
    }
    function stopAudio() { cancelSequence?.(); cancelSequence = null; playback++; cleanupMedia?.(); cleanupMedia = null; const audio = root()?.querySelector("audio"); if (audio) { audio.pause(); audio.removeAttribute("src"); audio.load(); } }
    function leave() { revision++; clearTimeout(timer); timer = null; abort?.abort(); abort = null; stopAudio(); busy = false; }
    async function mutation(path, body) {
        // Keep the same key after transport failure and across reload, until Core acknowledges registration.
        const actor = owner, activeSession = session, identity = path + "/" + JSON.stringify(body), pending = read("mutations", {}, actor);
        const key = pending[identity] || crypto.randomUUID(); pending[identity] = key; save("mutations", pending, actor);
        const result = await request(path, {method: "POST", headers: {"Idempotency-Key": key}, body});
        if (owner === actor && session === activeSession && session && Number.isSafeInteger(result.sessionVersion)) session.version = result.sessionVersion;
        if (result.operationId) save("operation", {...result, action: result.stage, sessionId: result.sessionId || path.match(/^\/sessions\/([^/]+)\//)?.[1]}, actor);
        delete pending[identity]; save("mutations", pending, actor); return result;
    }
    async function run(work) {
        if (busy) { status('Đang xử lý thao tác trước. Bạn có thể dừng nghe để tiếp tục.'); return; }
        busy = true; const token = revision, trigger = document.activeElement;
        root()?.setAttribute("aria-busy", "true");
        busyControls(true);
        try { await work(token); }
        catch (error) { if (current(token) && error.name !== "AbortError") status(error.status === 409 ? "Phiên đã thay đổi. Câu trả lời nháp vẫn được giữ; bấm tải lại bài để tiếp tục." : error.message); }
        finally { if (current(token)) {
            busy = false; root()?.removeAttribute("aria-busy"); busyControls(false);
            if (trigger?.isConnected && document.activeElement === document.body) trigger.focus({preventScroll: true});
        } }
    }
    async function show(id) {
        leave(); owner = bridge().currentUser()?.id; if (!owner || !root()) return;
        abort = new AbortController(); session = null; const token = revision;
        frame("<p>Đang tải bài học…</p>");
        try {
            const pending = read("operation", null);
            if (pending && (!id || pending.sessionId === id)) { await wait(pending, token); return; }
            const storedDraft = read("draft", null);
            const savedDraft = !pending && (storedDraft?.sourceSessionId || '') === (id || '') ? storedDraft : null;
            if (savedDraft?.draftId) {
                try {
                    const draft = await request(`/drafts/${encodeURIComponent(savedDraft.draftId)}`);
                    if (current(token) && draft.status === "READY") { await preview(draft, token); return; }
                } catch (_) { /* draft expired/gone — fall through to catalog */ }
                save("draft", null);
            }
            if (id) await openSession(id, token); else return await catalog(token);
        } catch (error) { if (current(token) && error.name !== "AbortError") loadError(error, id); }
    }
    async function catalog(token) {
        const [catalog, suggestions, history, goals, recommendations, legacy, savedPreferences] = await Promise.all([
            request("/catalog"), request("/suggestions"), request("/sessions?limit=10"), request("/learning-goals").catch(() => []), request("/recommendations").catch(() => ({suggestions: [], messageVi: 'Gợi ý tạm thời chưa tải được. Bạn vẫn có thể nhập nhu cầu hoặc tiếp tục bài đã lưu.'})),
            bridge().api("/api/v1/conversation/sessions?offset=0&limit=12", {signal: abort?.signal, cache: "no-store"}).catch(error => ({sessions: [], error: error.message})),
            request("/learning-preferences")]);
        if (!current(token)) return;
        if (!catalog.enabled) return false;
        const programs = catalog.practicePrograms || [], preferences = savedPreferences.preferences || [];
        const program = programs.find(p => p.code === read("program", preferences[0]?.programCode)) || programs[0];
        const storedPreference = program && preferences.find(p => p.programCode === program.code);
        const preference = storedPreference?.programVersion === program?.version ? storedPreference : null;
        const targetScale = program?.targetScales.find(s => s.component === 'LISTENING');
        const targetProfile = preference && program.difficultyProfiles.find(p => p.targetRange
            ? p.targetRange.scaleId === targetScale?.scaleId && Number(preference.target.value) >= Number(p.targetRange.min) && Number(preference.target.value) <= Number(p.targetRange.max)
            : p.id === preference.target.value);
        const recommended = recommendations.suggestions.filter(s => !s.focusSkill || !program || targetProfile?.skills.includes(s.focusSkill));
        const usefulRecommendations = recommended.some(r => r.focusSkill || r.reasonVi || !suggestions.suggestions.some(s => s.inputVi === r.inputVi));
        const available = program ? program.generationAvailable && Boolean(preference) : catalog.generationAvailable;
        const groups = [...new Map(suggestions.suggestions.map(s => [s.groupId || s.id, {id: s.groupId || s.id, title: s.groupTitleVi || s.titleVi}])).values()];
        const historyRows = rows => rows.map(s => `<p><a href="/conversation/${encodeURIComponent(s.sessionId)}" data-session="${escape(s.sessionId)}">${escape(s.titleVi)}</a> · ${escape(statusLabels[s.status] || s.status)}</p>`).join("");
        const legacyRows = rows => rows.map(s => `<p><a href="/conversation/${encodeURIComponent(s.id)}" data-session="${escape(s.id)}">Bài trước đây · ${escape(s.scenario)} · ${escape(s.difficulty)}</a></p>`).join("");
        frame(`${catalog.generationMode === "LOCAL_PREVIEW" ? '<p role="status">Thử nghiệm local · Program DRAFT, chưa được chuyên gia duyệt. Kết quả không phải đánh giá chứng chỉ.</p>' : ''}
            ${program ? personalizationHtml(programs, program, preference) : ''}<div class="scenario-hero"><form id="scenario-create" class="scenario-input-card">
            <label class="scenario-brief-label" for="scenario-input">Bạn muốn cải thiện điều gì?</label>
            <textarea id="scenario-input" class="scenario-brief" name="inputVi" rows="5" maxlength="2000" ${program ? '' : 'required'} aria-describedby="scenario-input-help"></textarea>
            <p id="scenario-input-help">Mô tả chủ đề hoặc nhu cầu bằng tiếng Việt. ${program ? 'Có thể để trống để AI đề xuất tình huống theo mục tiêu đã lưu.' : ''} Tránh nhập thông tin cá nhân hoặc khóa truy cập.</p>
            ${program ? `<p>${escape(program.displayNameVi)} · ${preference ? escape(profiles[preference.target.value] || preference.target.value) : 'Hãy lưu mục tiêu luyện nghe trước khi tạo bài.'}</p>` : `<fieldset class="scenario-profile-options"><legend>Mức luyện giao tiếp</legend>${(catalog.difficultyProfiles.length ? catalog.difficultyProfiles : ['supported','standard','challenge']).map((p,i) => `<label><input type="radio" name="profileId" value="${escape(p)}" ${i === 0 ? "checked" : ""}>${escape(profiles[p] || p)}</label>`).join("")}</fieldset>`}
            <p>Luyện kỹ năng theo mục tiêu; kết quả bài này không quy đổi thành điểm TOEIC hoặc band IELTS.</p>
            <button id="scenario-create-button" class="primary-button" ${available ? "" : "disabled"}>Phân tích và xem trước</button>
            ${available ? "" : "<p>Chương trình đang chờ duyệt bộ sinh bài. Bài đã lưu vẫn có thể mở.</p>"}
            </form>${usefulRecommendations ? `<aside class="scenario-suggestions-col"><h2>Gợi ý bài luyện</h2><p>${escape(recommendations.messageVi)}</p>
            ${recommended.slice(0,3).map((s,index) => `<button class="scenario-suggestion-card" type="button" data-recommendation="${index}"><strong>${escape(s.titleVi)}</strong><span>${escape(s.reasonVi || s.inputVi)}</span></button>`).join("")}
            ${goals.map(g => `<p class="scenario-goal-display">Mục tiêu tự khai · ${escape(programNames[g.programCode] || g.programCode)}: ${escape(profiles[g.target.value] || g.target.value)}</p>`).join("")}</aside>` : ""}</div>
            <section class="scenario-explorer"><h2>Gợi ý để bạn tùy chỉnh</h2><p>Chọn một gợi ý, rồi sửa mô tả theo nhu cầu của bạn. Bạn cũng có thể nhập tình huống hoàn toàn mới.</p>
            <div class="scenario-program-pills" role="group" aria-label="Nhóm gợi ý">${groups.map((g,i) => `<button class="scenario-pill" type="button" data-group="${escape(g.id)}" aria-pressed="${i === 0}">${escape(g.title)}</button>`).join("")}</div>
            <div class="scenario-group-grid">${suggestions.suggestions.map((s,index) => `<button class="scenario-group-card" data-suggestion="${index}" data-suggestion-group="${escape(s.groupId || s.id)}" ${index && (s.groupId || s.id) !== groups[0].id ? "hidden" : ""} type="button"><strong>${escape(s.titleVi)}</strong></button>`).join("")}</div></section>
            <section><h2>Bài đã tạo</h2><div id="scenario-history">${history.sessions.length ? historyRows(history.sessions) : "<p>Chưa có bài học mới.</p>"}</div><button type="button" id="scenario-history-more" ${history.nextCursor ? "" : "hidden"}>Xem thêm bài</button></section>
            <section><h2>Bài trước đây</h2><div id="scenario-legacy-history">${legacyRows(legacy.sessions || []) || "<p>Chưa có bài trước đây.</p>"}</div>${legacy.error ? `<p role="status">${escape(legacy.error)}</p>` : ""}<button type="button" id="scenario-legacy-more" ${legacy.hasMore ? "" : "hidden"}>Xem thêm bài trước đây</button></section>`);
        const input = root().querySelector("#scenario-input"); input.value = read("brief", "");
        input.addEventListener("input", () => save("brief", input.value));
        const savedFocus = read('briefFocus', null);
        let preferenceDirty = false, recommendedFocus = savedFocus?.programCode === program?.code && savedFocus?.brief === input.value && targetProfile?.skills.includes(savedFocus.skill) ? savedFocus.skill : null;
        const rememberFocus = () => save('briefFocus', recommendedFocus ? {skill: recommendedFocus, programCode: program.code, brief: input.value} : null);
        input.addEventListener('input', rememberFocus);
        if (recommendedFocus) status(`Bài tiếp theo tập trung: ${skills[recommendedFocus]}.`);
        if (program) bindPersonalization(program, preference, token, () => {
            preferenceDirty = true; root().querySelector('#scenario-create-button').disabled = true;
            status('Mục tiêu đã thay đổi. Hãy lưu mục tiêu trước khi tạo bài.');
        }, storedPreference?.version || 0);
        root().querySelectorAll("[data-group]").forEach(button => button.addEventListener("click", () => {
            root().querySelectorAll("[data-group]").forEach(item => item.setAttribute("aria-pressed", String(item === button)));
            root().querySelectorAll("[data-suggestion-group]").forEach(item => { item.hidden = item.dataset.suggestionGroup !== button.dataset.group; });
        }));
        root().querySelectorAll("[data-suggestion]").forEach(button => button.addEventListener("click", () => {
            recommendedFocus = null;
            rememberFocus();
            input.value = suggestions.suggestions[Number(button.dataset.suggestion)].inputVi; save("brief", input.value); input.focus();
        }));
        root().querySelectorAll("[data-recommendation]").forEach(button => button.addEventListener("click", () => {
            const suggestion = recommended[Number(button.dataset.recommendation)];
            recommendedFocus = suggestion.focusSkill;
            input.value = suggestion.inputVi; save("brief", input.value); input.focus();
            rememberFocus();
            if (skills[recommendedFocus]) status(`Bài tiếp theo tập trung: ${skills[recommendedFocus]}.`);
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
                const operation = await mutation("/drafts", {inputVi: String(form.get("inputVi") || '').trim(), programCode: selected.code, programVersion: selected.version, profileId: program ? '' : form.get("profileId"), ...(program && skills[recommendedFocus] ? {focusSkill:recommendedFocus} : {})});
                if (current(token)) await track({...operation, action: "EXTRACT"}, token);
            });
        });
        return true;
    }
    function personalizationHtml(programs, program, preference) {
        return `<details class="scenario-personalization" ${preference && !read('editingGoal', false) ? '' : 'open'}><summary id="scenario-personalization-title">${preference ? `${escape(program.displayNameVi)} · ${escape(profiles[preference.target.value] || preference.target.value)} — Đổi mục tiêu` : 'Mục tiêu luyện nghe của bạn'}</summary>
            <form id="scenario-personalization"><label for="scenario-program">Bạn luyện nghe để làm gì?</label>
            <select id="scenario-program" name="programCode">${programs.map(p => `<option value="${escape(p.code)}" ${p.code === program.code ? 'selected' : ''}>${escape(p.displayNameVi)}</option>`).join('')}</select>
            <div id="scenario-personalization-fields"></div><button type="submit" class="secondary-button">Lưu mục tiêu luyện nghe</button>
            <p>Bài luyện được tạo theo mức mục tiêu bạn chọn. Nội dung đang được kiểm chứng, không phải kết quả đánh giá trình độ.</p>
            ${program.generationAvailable ? '' : '<p role="status">Chương trình chưa mở tạo bài. Bạn có thể lưu mục tiêu và tiếp tục bài đã có.</p>'}</form></details>`;
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
            save('brief',root().querySelector('#scenario-input').value); save('program',event.target.value); save('editingGoal', true);
            run(async () => { await catalog(token); if (current(token)) focusStep('#scenario-target'); });
        });
        root().querySelector('#scenario-personalization').addEventListener('submit',event => {
            event.preventDefault(); const form = new FormData(event.currentTarget);
            run(async token => {
                await mutation('/learning-preferences',{programCode:program.code,programVersion:program.version,
                    target:{scaleId:scale.scaleId,component:'LISTENING',value:String(form.get('targetValue'))},
                    currentValue:'',focusSkill:'main-idea',expectedVersion});
                if (current(token)) { save('program',program.code); save('editingGoal', null); await catalog(token); focusStep('#scenario-input'); status('Đã lưu mục tiêu. Bạn có thể tạo bài luyện.'); }
            });
        });
    }
    function contextHtml(context) {
        return context ? `<p class="scenario-goal-display">${escape(context.programDisplayNameVi || programNames[context.programCode] || 'Chương trình luyện nghe')} · Mục tiêu: ${escape(profiles[context.target.value] || context.target.value)} · ${escape(skills[context.focusSkill] || context.focusSkill)} · ${escape(profiles[context.profile.id] || context.profile.id)}</p>` : '';
    }
    function bindSessionLinks() {
        root().querySelectorAll("[data-session]").forEach(link => {
            if (link.dataset.sessionBound) return; link.dataset.sessionBound = "true";
            link.addEventListener("click", event => {
            if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button) return;
            event.preventDefault(); if (link.hasAttribute('data-discard-draft')) save('draft', null);
            bridge().navigate("conversation", {sessionId: link.dataset.session});
        }); });
    }
    async function track(operation, token) { save("operation", operation); await wait(operation, token); }
    async function wait(pending, token, started = Date.now()) {
        if (!current(token)) return;
        frame(`<div class="scenario-waiting"><h2 id="scenario-step-heading" tabindex="-1">Đang xử lý: ${escape(stageLabels[pending.stage || pending.action] || pending.stage || pending.action)}</h2><p>Yêu cầu đã được lưu. Có thể rời trang rồi quay lại.</p><button id="scenario-check" type="button">Kiểm tra trạng thái</button></div>`);
        focusStep();
        const check = async () => {
            const op = await request(`/operations/${encodeURIComponent(pending.operationId)}`);
            if (!current(token)) return;
            if (op.status === "SUCCEEDED") {
                save("operation", null);
                if (pending.action === "EXTRACT") { save("draft", {draftId: op.draftId, sourceSessionId: pending.sessionId || ''}); if (pending.sessionId) save(`remix:${pending.sessionId}`, null); await preview(await request(`/drafts/${encodeURIComponent(op.draftId)}`), token); }
                else if (pending.action === "GENERATE") { save('draft', null); bridge().navigate("conversation", {sessionId: op.sessionId}); }
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
                frame(`<h2 id="scenario-step-heading" tabindex="-1">${escape(statusLabels[op.status] || op.status)}</h2><p>${op.status === "UNKNOWN" ? "Chưa xác định được kết quả từ nhà cung cấp. Hệ thống không tự gọi lại để tránh phí trùng." : "Tác vụ không hoàn tất. Mô tả và bài đã lưu vẫn được giữ. Bạn có thể sửa mô tả rồi chủ động tạo bài mới."}</p><p>Mã tác vụ: ${escape(pending.operationId)} · ${escape(op.errorCode || op.status)}</p><a href="/conversation" data-session="" data-discard-draft>Về mô tả đã lưu</a>${pending.sessionId ? `<p><a href="/conversation/${encodeURIComponent(pending.sessionId)}" data-session="${escape(pending.sessionId)}">Quay lại bài học</a></p>` : ''}`);
                bindSessionLinks(); focusStep();
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
        frame(`<h2 id="scenario-step-heading" tabindex="-1">${escape(s.titleVi)}</h2>${contextHtml(draft.learningContext)}<dl><dt>Bối cảnh</dt><dd>${escape(s.settingVi)}</dd><dt>Vai trò của bạn</dt><dd>${escape(s.learnerRoleVi)}</dd><dt>Mục tiêu</dt><dd>${escape(s.goalVi)}</dd></dl>
            <h3>Dữ kiện bạn cung cấp</h3><ul>${s.constraintsVi.map(a => `<li>${escape(a)}</li>`).join("")}</ul>
            <h3>Suy luận và giả định cần xác nhận</h3><ul>${s.assumptionsVi.map(a => `<li>${escape(a)}</li>`).join("")}</ul>
            <p>${s.characters.map(c => `${escape(c.name)}: ${escape(c.roleVi)}`).join(" · ")}</p>
            <div class="scenario-result-actions"><button class="primary-button" id="scenario-confirm">Xác nhận và tạo bài</button><a href="/conversation" data-session="" data-discard-draft>Viết tình huống khác</a></div>`);
        bindSessionLinks(); focusStep();
        root().querySelector("#scenario-confirm").addEventListener("click", () => run(async token => {
            const op = await mutation("/sessions", {draftId: draft.draftId, draftVersion: draft.version});
            save("draft", null);
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
            ${contextHtml(session.learningContext)}${session.status === 'COMPLETED' ? `<p>${escape(session.specification.settingVi)}</p><p>${escape(session.specification.goalVi)}</p>` : '<p>Nghe cuộc trao đổi, rồi trả lời theo điều bạn hiểu. Bối cảnh chi tiết được xem lại khi chữa bài.</p>'}<p>Trạng thái: ${escape(statusLabels[session.status] || session.status)}</p>
            ${session.activities.filter(a => a.status === "COMPLETED").map(a => `<button type="button" data-review="${escape(a.activityId)}">Chữa hoạt động ${a.ordinal}</button>`).join("")}
            ${review ? reviewHtml(review) : activity ? activityHtml(activity) : completedHtml()}`);
        bindSessionLinks(); root().querySelector("#scenario-refresh").addEventListener("click", () => run(token => openSession(session.sessionId, token)));
        root().querySelectorAll("[data-review]").forEach(button => button.addEventListener("click", () => run(() => {
            save(`review:${session.sessionId}`, button.dataset.review); renderSession();
        })));
        if (activity) bindAudio(activity);
        focusStep();
        if (review) root().querySelector("#scenario-next").addEventListener("click", () => run(() => { save(`review:${session.sessionId}`, null); renderSession(); }));
        else if (activity) bindActivity(activity);
        else {
        const remixKey = `remix:${session.sessionId}`, remix = root().querySelector('#scenario-remix-input');
        if (remix) { remix.value = read(remixKey, ''); remix.addEventListener('input', () => save(remixKey, remix.value)); }
        root().querySelector('#scenario-practice-similar')?.addEventListener('click', () => {
            const focus = followupSkill();
            const brief = `Tôi muốn luyện một tình huống tương tự: ${session.titleVi}.${focus ? ` Tập trung kỹ năng ${skills[focus]}.` : ''}`;
            save('brief', brief); save('program', session.programCode);
            save('briefFocus', focus ? {brief, programCode: session.programCode, skill: focus} : null);
            bridge().navigate('conversation', {});
        });
        root().querySelector('#scenario-review-needed')?.addEventListener('click', () => run(() => {
            const needed = session.activities.find(a => a.attempts.filter(t => t.status === 'GRADED').at(-1)?.result?.facts.some(f => ['PARTIAL','INCORRECT','MISSED'].includes(f.status)));
            if (needed) { save(`review:${session.sessionId}`, needed.activityId); renderSession(); }
        }));
        root().querySelector("#scenario-remix")?.addEventListener("submit", event => {
            event.preventDefault(); const form = new FormData(event.currentTarget);
            save(remixKey, form.get('inputVi'));
            run(async token => {
                const op = await mutation(`/sessions/${session.sessionId}/remixes`, {expectedVersion: session.version, inputVi: form.get("inputVi"), profileId: session.learningContext?.profile.id || form.get("profileId")});
                if (current(token)) await track({...op, action: "EXTRACT", sessionId: session.sessionId}, token);
            });
        });
        }
    }
    function audioHtml(a) {
        const names = new Map(session.specification.characters.map(c => [c.id, c.name]));
        const transcript = a.segments.map((s, i) => s.textEn ? `<p lang="en"><strong>Đoạn ${i + 1} · ${escape(names.get(s.speakerId) || s.speakerId)}:</strong> ${escape(s.textEn)}</p>` : '').join('') + (a.translations || []).map((t, i) => `<p><strong>Đoạn ${i + 1}:</strong> ${escape(t.textVi)}</p>`).join('');
        return `<section class="scenario-audio-panel"><h3 ${a.status !== 'COMPLETED' ? 'id="scenario-step-heading" tabindex="-1"' : ''}>Hoạt động ${a.ordinal} / ${session.activities.length}</h3>
            <div class="scenario-context-card">${session.specification.characters.map(c => `<span>${escape(c.name)}${a.status === 'COMPLETED' ? ` · ${escape(c.roleVi)}` : ''}</span>`).join(" / ")}</div>
            <div class="scenario-audio-controls"><button type="button" class="primary-button" id="scenario-play-all" ${a.segments.some(s => s.canPlay === false) ? 'disabled' : ''}>▶ Nghe toàn bộ hoạt động</button><button type="button" id="scenario-stop-audio">Dừng nghe</button></div>
            ${session.playbackRestrictionVi ? `<p>${escape(session.playbackRestrictionVi)} Audio đã lưu vẫn có thể nghe nếu nút đoạn được bật.</p>` : ''}
            <div class="scenario-segment-list">${a.segments.map((s, si) => `<button type="button" class="scenario-support-btn" data-play="${escape(s.segmentId)}" ${s.canPlay === false ? 'disabled' : ''}>Đoạn ${si + 1} · ${escape(names.get(s.speakerId) || s.speakerId)}</button>`).join("")}</div><audio id="scenario-audio" controls preload="none" aria-label="Audio đoạn hội thoại"></audio>
            ${transcript ? a.status === 'COMPLETED' ? `<details><summary>Xem bản chép và dịch</summary>${transcript}</details>` : transcript : ''}
            <p><span id="scenario-play-count">Lần yêu cầu phát: ${a.plays}.</span> ${a.transcriptOpened && a.hintOpened ? "Ghi nhận: đã dùng cả gợi ý và bản chép." : a.transcriptOpened ? "Ghi nhận: đã mở bản chép." : a.hintOpened ? "Ghi nhận: đã dùng gợi ý chiến lược, chưa mở bản chép." : "Chưa dùng gợi ý/bản chép trước khi hoàn thành."}</p></section>`;
    }
    function attemptsHtml(a) {
        return a.attempts.map(t => `<section><h4>Lần ${t.ordinal} · ${escape(labels[t.status] || statusLabels[t.status] || t.status)}</h4>
            <p>${t.support?.transcriptOpened && t.support?.hintOpened ? "Dùng cả gợi ý và bản chép khi nộp." : t.support?.transcriptOpened ? "Có mở bản chép khi nộp." : t.support?.hintOpened ? "Chỉ dùng gợi ý chiến lược khi nộp." : "Không dùng gợi ý/bản chép khi nộp."}</p>
            ${t.answers.map(answer => `<p>Câu ${escape(answer.questionId.replace(/^q/, ""))}: ${escape(answer.textVi)}</p>`).join("")}
            ${t.result ? t.result.facts.map(f => `<p>Câu ${escape(f.questionId.replace(/^q/, ""))}: ${escape(labels[f.status])}${f.evidenceVi ? ` — Bạn viết: "${escape(f.evidenceVi)}"` : ""}${["PARTIAL","INCORRECT","MISSED"].includes(f.status) ? ` <em>${escape(f.feedbackVi || 'Hãy nghe lại, đối chiếu đủ các ý câu hỏi yêu cầu trước khi trả lời lại.')}</em>` : f.status === 'UNCERTAIN' ? ' Chưa đủ căn cứ để kết luận. Bạn có thể làm rõ câu trả lời theo điều nghe được.' : ''}</p>`).join("") : ""}
            ${["FAILED", "UNKNOWN"].includes(t.status) ? `<p>${t.status === "UNKNOWN" ? "Chưa xác định kết quả; không tự gọi AI lại để tránh phí trùng." : "Chưa chấm được; đây không phải câu trả lời sai."} ${escape(t.gradeRetry?.errorCode)} · Mã tác vụ: ${escape(t.gradeRetry?.operationId)}</p>` : ""}
            ${a.status !== "COMPLETED" && t.gradeRetry?.allowed ? `<button type="button" data-retry-grade="${escape(t.attemptId)}">Thử chấm lại câu trả lời đã lưu (còn ${t.gradeRetry.remaining} lần)</button>` : ""}
            ${t.status === "FAILED" && t.gradeRetry?.remaining === 0 ? "<p>Đã hết lượt thử chấm lại. Giữ mã tác vụ để được hỗ trợ; không cần gửi lặp.</p>" : ""}</section>`).join("");
    }
    function reviewHtml(a) {
        const latest = a.attempts.filter(t => t.status === 'GRADED').at(-1);
        return `<h3 id="scenario-step-heading" tabindex="-1">Chữa hoạt động ${a.ordinal}</h3>${audioHtml(a)}
            ${a.questions.map(q => { const fact = latest?.result?.facts.find(f => f.questionId === q.questionId); return `<section class="scenario-review-question"><h4>${escape(q.promptVi)}</h4><p><strong>Bạn trả lời:</strong> ${escape(latest?.answers.find(answer => answer.questionId === q.questionId)?.textVi || 'Chưa có câu trả lời')}</p>
                <p><strong>${escape(labels[fact?.status] || 'Chưa có kết quả')}</strong> ${escape(fact?.feedbackVi || (fact?.status === 'UNCERTAIN' ? 'Chưa đủ căn cứ để kết luận từ câu trả lời này.' : ''))}</p><p>Đáp án: ${escape(q.answerVi)}</p>
                ${(q.evidence || []).map(e => { const index = a.segments.findIndex(s => s.segmentId === e.segmentId); return `<p>Bằng chứng ở đoạn ${index + 1}: <span lang="en">“${escape(e.quoteEn)}”</span></p>${index >= 0 ? `<button type="button" data-play="${escape(e.segmentId)}" ${a.segments[index].canPlay === false ? 'disabled' : ''}>Nghe bằng chứng · Đoạn ${index + 1}</button>` : ''}`; }).join("")}</section>`; }).join("")}
            <details><summary>Lịch sử trả lời và hỗ trợ</summary>${attemptsHtml(a)}</details>
            <button type="button" id="scenario-next">${session.status === "COMPLETED" ? "Xem tổng kết" : "Sang hoạt động tiếp theo"}</button>`;
    }
    function activityHtml(a) {
        const drafts = read(answerKey(a), {}), latest = a.attempts.at(-1);
        return `${audioHtml(a)}<section>
            <button type="button" data-help="HINT">Gợi ý cách nghe</button><button type="button" data-help="TRANSCRIPT">Mở bản chép và dịch (có hỗ trợ)</button>
            ${a.hintVi ? `<aside class="scenario-listening-hint"><h4>Gợi ý cách nghe</h4><p>${escape(a.hintVi)}</p></aside>` : ''}
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
            if (current(token)) { await openSession(session.sessionId, token); root().querySelector(`[data-help="${button.dataset.help}"]`)?.focus(); }
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
        root().querySelector('#scenario-stop-audio').addEventListener('click', () => { stopAudio(); status('Đã dừng nghe. Bạn có thể phát lại từng đoạn.'); });
        root().querySelectorAll("[data-play]").forEach(button => button.addEventListener("click", () => play(a.segments.filter(s => s.segmentId === button.dataset.play), false)));
        root().querySelector("#scenario-play-all")?.addEventListener("click", () => play(a.segments));
        const play = (segments, continuous = true) => run(async token => {
            stopAudio();
            let cancelled = false, settle = null;
            const cancel = () => { cancelled = true; settle?.(); };
            cancelSequence = cancel;
            const active = () => !cancelled && current(token);
            try {
                for (const [index, seg] of segments.entries()) {
                    if (!active()) return;
                    status(`Đang chuẩn bị đoạn ${index + 1}/${segments.length}…`);
                    let op = await mutation(`/sessions/${session.sessionId}/activities/${a.activityId}/play`, {expectedVersion: session.version, segmentId: seg.segmentId});
                    if (!active()) return;
                    if (op.status !== 'READY') {
                        const pending = {...op, action: 'TTS', sessionId: session.sessionId}; save('operation', pending);
                        const started = Date.now();
                        do {
                            op = await request(`/operations/${encodeURIComponent(pending.operationId)}`);
                            if (!active()) return;
                            if (['FAILED','UNKNOWN','CANCELLED','EXPIRED'].includes(op.status)) {
                                save('operation', null);
                                session = await request(`/sessions/${encodeURIComponent(session.sessionId)}`);
                                if (active()) status(`Không phát được đoạn ${index + 1}. Mã tác vụ: ${pending.operationId} · ${op.errorCode || op.status}. Không tự gửi lại yêu cầu.`);
                                return;
                            }
                            if (op.status === 'SUCCEEDED') { save('operation', null); break; }
                            if (Date.now() - started >= 300000) { status('Âm thanh còn đang chuẩn bị. Tải lại bài để kiểm tra tác vụ đã lưu; không cần tạo yêu cầu mới.'); return; }
                            await new Promise(resolve => {
                                const delay = setTimeout(() => { settle = null; resolve(); }, Math.max(1500, Math.min(10000, op.pollAfterMs || 2000)));
                                settle = () => { clearTimeout(delay); settle = null; resolve(); };
                            });
                        } while (active());
                    }
                    if (!active()) return;
                    session = await request(`/sessions/${encodeURIComponent(session.sessionId)}`);
                    if (!active()) return;
                    const count = root()?.querySelector('#scenario-play-count');
                    if (count) count.textContent = `Lần yêu cầu phát: ${session.activities.find(item => item.activityId === a.activityId)?.plays ?? a.plays}.`;
                    const audio = root()?.querySelector('#scenario-audio'); if (!audio) return;
                    let finish;
                    const ended = new Promise(resolve => {
                        const done = event => {
                            // Natural completion emits pause before ended; only a user pause stops the sequence.
                            if (event?.type === 'pause' && audio.ended) return;
                            ['ended','error','pause'].forEach(name => audio.removeEventListener(name, done)); settle = null; resolve(event?.type === 'ended');
                        };
                        finish = () => done(); settle = finish;
                        ['ended','error','pause'].forEach(name => audio.addEventListener(name, done));
                    });
                    const ordinal = a.segments.findIndex(s => s.segmentId === seg.segmentId) + 1;
                    const speaker = session.specification.characters.find(c => c.id === seg.speakerId)?.name;
                    const description = `đoạn ${ordinal}/${a.segments.length}${speaker ? ` · ${speaker}` : ''}`;
                    status(`Đang nghe ${description}. ${continuous ? 'Các thao tác khác sẽ mở lại khi nghe xong hoặc bấm Dừng nghe.' : ''}`);
                    if (!await playMedia(op.mediaId, description, continuous)) { finish(); return; }
                    if (!continuous) { finish(); return; }
                    if (!await ended || !active()) return;
                }
                if (active()) status(segments.length === a.segments.length ? 'Đã nghe xong hoạt động. Bạn có thể nghe lại từng đoạn.' : 'Đã nghe xong đoạn.');
            } finally {
                settle?.(); if (cancelSequence === cancel) cancelSequence = null;
            }
        });
    }
    async function playMedia(id, description = 'đoạn hội thoại', continuous = false) {
        const audio = root().querySelector("#scenario-audio"); if (!audio) return false;
        const token = revision, turn = ++playback;
        const active = () => current(token) && turn === playback && root()?.querySelector("#scenario-audio") === audio;
        cleanupMedia?.(); let pausedSequence = false;
        const events = {
            error: () => { if (active()) status("Không tải hoặc giải mã được audio. Bấm nghe lại đoạn này; audio đã lưu sẽ được dùng lại."); },
            pause: () => { if (active() && !audio.ended) { pausedSequence = continuous; status(`Đã tạm dừng ${description}.${continuous ? ' Chuỗi nghe đã dừng. Phát tiếp trên trình nghe chỉ tiếp tục đoạn hiện tại.' : ''}`); } },
            playing: () => { if (active()) status(`Đang nghe ${description}.${pausedSequence ? ' Chỉ phát đoạn hiện tại; chọn đoạn tiếp theo để nghe tiếp.' : continuous ? ' Các thao tác khác mở lại khi nghe xong hoặc bấm Dừng nghe.' : ''}`); },
            ended: () => { if (active() && (!continuous || pausedSequence)) status(`Đã nghe xong ${description}.`); }
        };
        Object.entries(events).forEach(([name, handler]) => audio.addEventListener(name, handler));
        cleanupMedia = () => Object.entries(events).forEach(([name, handler]) => audio.removeEventListener(name, handler));
        audio.src = `${base}/media/${encodeURIComponent(id)}`;
        try { await audio.play(); return active(); } catch (error) {
            if (!active() || error.name === "AbortError") return false;
            status(error.name === "NotAllowedError" ? "Trình duyệt chặn tự phát. Bấm nút phát trên trình nghe."
                : "Không phát được audio. Bấm nghe lại đoạn này; audio đã lưu sẽ được dùng lại.");
            return false;
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
            return `<p>${latest ? "Lần chấm thành công mới nhất" : "Lần trả lời đầu"}: ${facts.filter(f => f.status === "CAPTURED").length} câu hiểu đúng; có kết quả ${facts.length}/${total} câu; ${total - facts.length} câu chưa chấm được; ${facts.filter(f => f.status === "PARTIAL").length} hiểu một phần; ${facts.filter(f => f.status === "INCORRECT").length} chưa đúng; ${facts.filter(f => f.status === "MISSED").length} bỏ lỡ; ${facts.filter(f => f.status === "UNCERTAIN").length} chưa đủ căn cứ. ${attempts.filter(t => t?.support?.hintOpened).length} hoạt động dùng gợi ý chiến lược; ${attempts.filter(t => t?.support?.transcriptOpened).length} hoạt động mở bản chép (có thể trùng nhau).</p>`;
        };
        return `<h3 id="scenario-step-heading" tabindex="-1">Đã hoàn thành</h3>${summary(false)}${summary(true)}<p>Đây không phải điểm chứng chỉ. Kết quả có hỗ trợ không phải phép đo nghe độc lập.</p>
            <div class="scenario-result-actions">${followupSkill() ? '<button type="button" id="scenario-review-needed">Xem câu cần luyện</button>' : ''}<button type="button" id="scenario-practice-similar">Luyện tương tự</button></div><p>Luyện tương tự mở mô tả để bạn chỉnh và xem trước. Gợi ý dựa trên bài này, không phải đánh giá trình độ.</p>
            <form id="scenario-remix"><label for="scenario-remix-input">Bạn muốn biến thể như thế nào?</label><textarea id="scenario-remix-input" name="inputVi" class="scenario-answer-field" maxlength="1200" required rows="3"></textarea>
            ${session.learningContext ? `<p>Biến thể giữ mục tiêu và mức luyện của bài này: ${escape(profiles[session.learningContext.profile.id])}.</p>` : `<label for="scenario-remix-profile">Mức luyện</label><select id="scenario-remix-profile" name="profileId">${Object.keys(profiles).map(p => `<option value="${p}">${profiles[p]}</option>`).join("")}</select>`}<button class="primary-button">Xem trước biến thể mới</button></form>`;
    }
    function followupSkill() {
        for (const a of session.activities) {
            const latest = a.attempts.filter(t => t.status === 'GRADED').at(-1);
            const fact = latest?.result?.facts.find(f => ['PARTIAL','INCORRECT','MISSED'].includes(f.status));
            const skill = a.questions.find(q => q.questionId === fact?.questionId)?.skill;
            if (skill && session.learningContext?.profile.skills?.includes(skill)) return skill;
        }
        return null;
    }
    window.DictaLabScenario = {show, leave};
})();
