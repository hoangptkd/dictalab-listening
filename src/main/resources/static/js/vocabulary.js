(() => {
    "use strict";
    const root   = document.querySelector("#vocabulary-content");
    const status = document.querySelector("#vocabulary-status");
    if (!root || !status) return;

    const bridge    = window.DictaLabListeningBridge;
    const base      = "/api/v1/vocabulary";
    const adminBase = "/api/admin/vocabulary";

    /* ── Utilities ─────────────────────────────────────────────── */
    const escape = v => String(v ?? "").replace(/[&<>"']/g, c =>
        ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));

    const btn = (action, label, extra = "") =>
        `<button type="button" class="text-button" data-vocab-action="${action}" ${extra}>${label}</button>`;

    const primaryBtn = (action, label, extra = "") =>
        `<button type="button" class="primary-button" data-vocab-action="${action}" ${extra}>${label}</button>`;

    /* ── State ─────────────────────────────────────────────────── */
    let generation = 0, importGeneration = 0, controller = null, busy = false, owner = null;
    let personal = [], visibleNotes = [], selectedDeck = null,
        currentNote = null, sourceWord = null, session = null,
        admin = false, catalog = false;
    const pending = new Map();

    /* ── API helpers ────────────────────────────────────────────── */
    function leave() {
        generation++; importGeneration++; controller?.abort(); controller = null; busy = false;
        personal = []; visibleNotes = []; selectedDeck = null;
        currentNote = null; sourceWord = null; session = null; owner = null;
        root.replaceChildren(); status.textContent = "";
    }

    async function request(path, options = {}) {
        const stamp = generation, user = owner;
        const result = await bridge.api(path, { ...options, signal: controller?.signal, timeoutMs: 20000 });
        if (stamp !== generation || user !== bridge.currentUser()?.id)
            throw new DOMException("Changed user or page", "AbortError");
        return result;
    }

    async function mutate(path, body = {}, method = "POST") {
        const identity = `${owner}:${method}:${path}:${JSON.stringify(body)}`;
        if (!pending.has(identity)) pending.set(identity, crypto.randomUUID());
        try {
            const result = await request(path, { method, body, headers: { "Idempotency-Key": pending.get(identity) } });
            pending.delete(identity); return result;
        } catch (error) {
            if (error.status && error.status < 500 && error.status !== 429) pending.delete(identity);
            throw error;
        }
    }

    async function run(work) {
        if (busy) return;
        const stamp = generation; busy = true;
        status.textContent = "Đang xử lý…";
        root.setAttribute("aria-busy", "true");
        try { await work(); if (stamp === generation) status.textContent = ""; }
        catch (error) { if (stamp === generation && error.name !== "AbortError") status.textContent = error.message; }
        finally { if (stamp === generation) { busy = false; root.removeAttribute("aria-busy"); } }
    }

    /* ── Shared components ──────────────────────────────────────── */
    const toolbar = () => `
        <nav class="vocab-toolbar" aria-label="Mục từ vựng">
            ${btn("home",    "Hôm nay")}
            ${btn("mine",    "Bộ của tôi")}
            ${btn("catalog", "Bộ có sẵn")}
            ${btn("sources", "Từ bài đã học")}
            ${bridge.isAdmin() ? btn("admin", "Quản trị") : ""}
        </nav>`;

    const levelBadge = lvl =>
        `<span class="vocab-level-badge ${escape(lvl)}">${escape(lvl)}</span>`;

    const deckOptions = () => personal.map(d =>
        `<option value="${escape(d.id)}">${escape(d.title)}</option>`).join("");

    /* Progress track html */
    const progressTrack = (value, max, label = "") => {
        const pct = max > 0 ? Math.round((value / max) * 100) : 0;
        return `<div class="vocab-progress-track" role="progressbar"
                     aria-valuenow="${value}" aria-valuemax="${max}"
                     aria-label="${escape(label)}">
                    <div class="vocab-progress-fill" style="width:${pct}%"></div>
                </div>`;
    };

    /* Streak row (7-day view) */
    const streakRow = (streakDays = 0, reviewedToday = false) => {
        const days = ["Hai","Ba","Tư","Năm","Sáu","Bảy","CN"];
        const today = new Date().getDay(); // 0=Sun
        const dots = days.map((d, i) => {
            const dayOfWeek = (i + 1) % 7;
            const todayDayOfWeek = today;
            const isToday = dayOfWeek === todayDayOfWeek;
            const todayIdxInRow = days.findIndex((_, j) => (j + 1) % 7 === todayDayOfWeek);
            const diff = todayIdxInRow - i;
            const done = diff >= 0 && diff < streakDays && !(isToday && !reviewedToday);
            const todayDone = isToday && reviewedToday;
            let cls = "streak-dot";
            if (todayDone || (isToday && diff === 0 && streakDays > 0)) cls += " done";
            else if (done) cls += " done";
            else if (isToday) cls += " today";
            return `<div class="${cls}" title="${d}" aria-label="${d}${done || todayDone ? " ✓" : ""}">${d}</div>`;
        }).join("");
        return `
        <div class="vocab-streak vocab-animate-in">
            <span class="streak-flame" aria-hidden="true">🔥</span>
            <div class="streak-text">
                <strong>${streakDays}</strong>
                <span>ngày liên tiếp</span>
            </div>
            <div class="streak-dots" aria-label="7 ngày gần đây">${dots}</div>
        </div>`;
    };

    /* Skeleton loader for deck grid */
    const skeletonGrid = (n = 4) => `
        <div class="vocab-grid" aria-hidden="true">
            ${Array.from({length: n}, () => `
                <div class="vocab-deck-card" style="pointer-events:none">
                    <div class="skel skel-line" style="width:35%"></div>
                    <div class="skel skel-head"></div>
                    <div class="skel skel-line" style="width:80%"></div>
                    <div class="skel skel-line" style="width:60%"></div>
                    <div class="skel skel-bar" style="margin-top:12px"></div>
                </div>`).join("")}
        </div>`;

    /* ── Pagination (always at bottom) ─────────────────────────── */
    const pagination = (page, action) => `
        <div class="vocab-pagination-anchor">
            <div class="vocab-pagination">
                <button type="button" class="text-button" data-vocab-action="${action}"
                    data-page="${page.page - 1}" ${page.page <= 1 ? "disabled" : ""}>← Trang trước</button>
                <span class="vocab-page-info">Trang ${page.page}</span>
                <button type="button" class="text-button" data-vocab-action="${action}"
                    data-page="${page.page + 1}" ${!page.hasMore ? "disabled" : ""}>Trang sau →</button>
            </div>
        </div>`;

    /* ── Session management ────────────────────────────────────── */
    async function refreshPersonal() {
        const decks = [];
        for (let page = 1; page <= 10; page++) {
            const result = await request(`${base}/decks?page=${page}`);
            decks.push(...result.items);
            if (!result.hasMore) break;
        }
        personal = decks;
    }

    async function show(sessionId) {
        leave(); controller = new AbortController(); owner = bridge.currentUser()?.id;
        if (!owner) return;
        await run(async () => {
            await refreshPersonal();
            if (sessionId) await loadSession(sessionId); else await home();
        });
    }

    /* ── Home view ─────────────────────────────────────────────── */
    async function home() {
        admin = false; catalog = false; selectedDeck = null;
        // Show skeleton while loading
        root.innerHTML = toolbar() + `
            <div class="vocab-stats">
                ${[0,1,2].map(() => `<div class="vocab-stat-card">
                    <div class="skel skel-num"></div>
                    <div class="skel skel-line" style="width:70%"></div>
                    <div class="skel skel-bar" style="margin-top:10px"></div>
                </div>`).join("")}
            </div>
            <div class="skel skel-card" style="margin-bottom:20px"></div>`;

        const summary = await request(`${base}/summary`);
        const reviewed  = summary.reviewedToday ?? 0;
        const due       = summary.due ?? 0;
        const newCards  = summary.new_cards ?? 0;
        const newLimit  = summary.newLimit ?? 10;
        const streakDays = summary.streakDays ?? (reviewed > 0 ? 1 : 0);
        const totalGoal  = due + Math.min(newCards, newLimit);
        const goalPct    = totalGoal > 0 ? Math.min(1, reviewed / totalGoal) : (reviewed > 0 ? 1 : 0);

        root.innerHTML = toolbar() + `

        <!-- Streak -->
        ${streakRow(streakDays, reviewed > 0)}

        <!-- Stats -->
        <div class="vocab-stats">
            <div class="vocab-stat-card vocab-animate-in">
                <div class="vocab-stat-number is-teal">${due}</div>
                <div class="vocab-stat-label">Thẻ cần ôn hôm nay</div>
                <div class="vocab-stat-bar" style="width:${due>0?'60%':'0%'}"></div>
            </div>
            <div class="vocab-stat-card vocab-animate-in">
                <div class="vocab-stat-number">${reviewed}</div>
                <div class="vocab-stat-label">Đã học hôm nay</div>
                <div class="vocab-stat-bar" style="width:${Math.round(goalPct*100)}%"></div>
            </div>
            <div class="vocab-stat-card vocab-animate-in">
                <div class="vocab-stat-number">${newCards}</div>
                <div class="vocab-stat-label">Thẻ mới trong thư viện</div>
                <div class="vocab-stat-bar" style="width:${newCards>0?'40%':'0%'}"></div>
            </div>
        </div>

        <!-- Daily goal progress -->
        <div class="vocab-goal vocab-animate-in" aria-label="Mục tiêu hôm nay">
            <div class="vocab-goal-header">
                <strong>Mục tiêu hôm nay</strong>
                <span>${reviewed} / ${totalGoal} thẻ</span>
            </div>
            ${progressTrack(reviewed, Math.max(totalGoal, 1), "Tiến độ học hôm nay")}
        </div>

        <!-- Main layout -->
        <div class="vocab-home-layout">
            <div class="vocab-home-main">
                <!-- Practice panel -->
                <section class="vocab-panel vocab-animate-in" aria-labelledby="practice-heading">
                    <h2 id="practice-heading" style="font-family:var(--display,serif);font-size:1.3rem;letter-spacing:-.02em">
                        Nhớ trước, xem đáp án sau.
                    </h2>
                    <p style="color:var(--ink-soft);font-size:.9rem;margin:.4rem 0 0">
                        Ôn từ đến hạn trước, sau đó học tối đa ${newLimit} thẻ mới mỗi ngày.
                    </p>
                    <div class="vocab-actions">
                        <button class="primary-button" data-vocab-action="start" data-mode="SRS">
                            Bắt đầu ôn →
                        </button>
                        ${btn("start", "Luyện thêm", 'data-mode="PRACTICE"')}
                        ${btn("start", "Dịch câu", 'data-mode="CONTEXT_TRANSLATION"')}
                    </div>
                    ${summary.activeSessions?.length ? `
                    <div style="margin-top:16px;padding-top:14px;border-top:1px solid var(--line)">
                        ${summary.activeSessions.map(s => `
                            <div style="display:flex;align-items:center;gap:10px;margin-bottom:8px">
                                <span style="font-size:.8rem;color:var(--ink-soft)">Phiên đang dở</span>
                                ${btn("resume", `Tiếp tục ${s.mode === "CONTEXT_TRANSLATION" ? "dịch câu" : "phiên học"}`, `data-id="${escape(s.id)}"`)}
                            </div>`).join("")}
                    </div>` : ""}
                </section>
            </div>

            <!-- Aside: Recent activity -->
            <aside class="vocab-home-aside">
                <div class="vocab-panel vocab-animate-in" aria-labelledby="recent-heading">
                    <h3 id="recent-heading" style="margin:0 0 14px;font-size:.95rem;letter-spacing:-.01em">
                        Lượt học gần đây
                    </h3>
                    ${summary.recent?.length ? `
                    <ul class="vocab-recent-list">
                        ${summary.recent.slice(0, 8).map(r => `
                        <li class="vocab-recent-item">
                            <span class="vocab-grade-pill ${r.grade}">
                                ${({AGAIN:"Quên",HARD:"Khó",GOOD:"Nhớ",EASY:"Dễ"})[r.grade] ?? r.grade}
                            </span>
                            <span style="font-size:.8rem;color:var(--ink-soft);flex:1">
                                ${r.schedule_changed ? "Ôn lịch" : "Luyện thêm"}
                            </span>
                            <span class="vocab-recent-meta">
                                ${new Date(r.reviewed_at).toLocaleTimeString("vi-VN", {hour:"2-digit",minute:"2-digit"})}
                            </span>
                        </li>`).join("")}
                    </ul>` : `
                    <div class="vocab-empty" style="padding:30px 10px">
                        <span class="vocab-empty-icon">📚</span>
                        <p>Chọn bộ từ có sẵn hoặc thêm từ bạn muốn nhớ.</p>
                    </div>`}
                </div>
            </aside>
        </div>`;

        requestAnimationFrame(() => {
            root.querySelectorAll(".vocab-progress-fill").forEach(el => {
                const w = el.style.width;
                el.style.width = "0%";
                requestAnimationFrame(() => { el.style.width = w; });
            });
            root.querySelectorAll(".vocab-stat-bar").forEach(el => {
                const w = el.style.width;
                el.style.width = "0%";
                requestAnimationFrame(() => { el.style.width = w; });
            });
        });
    }

    /* ── Deck list ────────────────────────────────────────────── */
    async function listDecks(page = 1) {
        selectedDeck = null;
        const endpoint = admin ? `${adminBase}/decks` : catalog ? `${base}/catalog/decks` : `${base}/decks`;
        const title    = admin ? "Quản trị bộ từ" : catalog ? "Bộ từ có sẵn" : "Bộ của tôi";

        // Skeleton
        root.innerHTML = toolbar() + `
            <div class="vocab-section-heading vocab-animate-in">
                <h2>${title}</h2>
            </div>
            ${skeletonGrid(6)}`;

        const result = await request(`${endpoint}?page=${page}`);

        const deckCards = result.items.length ? result.items.map(d => `
            <article class="vocab-deck-card vocab-animate-in" role="button" tabindex="0"
                     data-vocab-action="deck" data-id="${escape(d.id)}"
                     aria-label="Mở bộ từ ${escape(d.title)}">
                ${levelBadge(d.level)}
                ${admin ? `<span class="vocab-word-pos">${escape(d.status)}</span>` : ""}
                <h3>${escape(d.title)}</h3>
                <p>${escape(d.description)}</p>
                <div class="vocab-deck-count">${d.note_count} mục từ</div>
            </article>`).join("") :
            `<div class="vocab-empty" style="grid-column:1/-1">
                <span class="vocab-empty-icon">🗂️</span>
                <p>${catalog ? "Chưa có bộ từ công khai." : "Chưa có bộ từ. Tạo một bộ để bắt đầu."}</p>
             </div>`;

        root.innerHTML = toolbar() + `
            <div class="vocab-section-heading vocab-animate-in">
                <h2>${title}</h2>
            </div>

            ${!catalog ? `
            <details class="vocab-details vocab-animate-in">
                <summary>Tạo bộ từ mới</summary>
                <div class="vocab-details-body">
                    <form data-vocab-form="deck" class="vocab-form">
                        <label>Tên bộ <input name="title" required maxlength="120"></label>
                        <label>Mô tả   <input name="description" maxlength="1000"></label>
                        <label>Mức
                            <select name="level">
                                <option>A1</option><option>A2</option>
                                <option>B1</option><option>B2</option>
                            </select>
                        </label>
                        <div><button class="primary-button">Tạo bộ từ</button></div>
                    </form>
                </div>
            </details>` : ""}

            <div class="vocab-grid">${deckCards}</div>
            ${pagination(result, "decks-page")}`;
    }

    /* ── Open deck ────────────────────────────────────────────── */
    async function openDeck(id, page = 1) {
        importGeneration++;
        const deckImportStamp = importGeneration;
        // Skeleton
        root.innerHTML = toolbar() + `
            <div class="vocab-deck-header vocab-animate-in" style="background:var(--paper)">
                <div class="skel skel-line" style="width:25%"></div>
                <div class="skel skel-head" style="width:50%"></div>
                <div class="skel skel-line" style="width:80%"></div>
            </div>
            ${skeletonGrid(4)}`;

        const result = await request(`${admin ? adminBase : base}${catalog ? "/catalog" : ""}/decks/${encodeURIComponent(id)}?page=${page}`);
        selectedDeck  = result.deck;
        visibleNotes  = result.notes.items;

        const noteCards = result.notes.items.map(n => `
            <article class="vocab-word-card vocab-animate-in">
                <h3 lang="en">${escape(n.term)}</h3>
                ${n.part_of_speech ? `<span class="vocab-word-pos">${escape(n.part_of_speech)}</span>` : ""}
                <p class="vocab-word-meaning">${escape(n.meaning_vi)}</p>
                ${n.example_en ? `
                <p class="vocab-word-example" lang="en">${escape(n.example_en)}</p>
                <p class="vocab-word-example">${escape(n.example_vi)}</p>` : ""}
                <div class="vocab-mastery-bar" aria-hidden="true">
                    <div class="vocab-mastery-fill" style="width:${n.mastery_pct ?? 0}%"></div>
                </div>
                <div class="vocab-actions">
                    ${!catalog ? btn("edit-note", "Chi tiết / sửa", `data-id="${escape(n.id)}"`) : ""}
                    ${!admin && !catalog ? btn("remove-note", "Bỏ khỏi bộ", `data-id="${escape(n.id)}"`) : ""}
                </div>
            </article>`).join("") ||
            `<div class="vocab-empty" style="grid-column:1/-1">
                <span class="vocab-empty-icon">📝</span>
                <p>Chưa có từ trong bộ này.</p>
                ${!catalog && !admin ? btn("new-note","Thêm từ đầu tiên") : ""}
             </div>`;

        root.innerHTML = toolbar() + `

        <!-- Deck header -->
        <div class="vocab-deck-header vocab-animate-in">
            ${levelBadge(selectedDeck.level)}
            <h2>${escape(selectedDeck.title)}</h2>
            <p>${escape(selectedDeck.description)}</p>
            <div class="vocab-deck-meta">
                <div class="vocab-deck-meta-item">
                    <strong>${selectedDeck.note_count ?? result.notes.items.length}</strong> mục từ
                </div>
            </div>
        </div>

        <!-- Deck actions -->
        <div class="vocab-actions vocab-animate-in" style="margin-bottom:24px">
            ${catalog ? "" : `
                <button class="primary-button" data-vocab-action="start"
                    data-mode="SRS" data-deck="true">Ôn bộ này →</button>
                ${btn("start","Dịch câu",'data-mode="CONTEXT_TRANSLATION" data-deck="true"')}
                ${btn("new-note","Thêm từ")}`}
            ${admin ? `
                ${btn("publish", selectedDeck.status === "PUBLISHED" ? "Ẩn khỏi thư viện" : "Xuất bản")}
                ${btn("new-context","Thêm câu kết hợp")}` : ""}
        </div>

        ${catalog ? `
        <!-- Copy to my deck -->
        <div class="vocab-copy-panel vocab-animate-in">
            <form data-vocab-form="copy" class="vocab-form" style="margin:0;gap:12px">
                <label>Thêm vào bộ của tôi
                    <select name="deckId" required>
                        <option value="">Chọn bộ từ</option>
                        ${deckOptions()}
                    </select>
                </label>
                <div>
                    <button class="primary-button" ${personal.length ? "" : "disabled"}>
                        Thêm vào thư viện
                    </button>
                    ${!personal.length ? `<p style="font-size:.82rem;color:var(--ink-soft);margin:8px 0 0">
                        Hãy tạo bộ cá nhân trong <em>Bộ của tôi</em> trước.
                    </p>` : ""}
                </div>
            </form>
        </div>` : ""}

        ${!admin && !catalog ? `
        <!-- Edit deck details -->
        <details class="vocab-details vocab-animate-in">
            <summary>Sửa tên và mô tả</summary>
            <div class="vocab-details-body">
                <form data-vocab-form="edit-deck" class="vocab-form">
                    <label>Tên bộ <input name="title" required maxlength="120" value="${escape(selectedDeck.title)}"></label>
                    <label>Mô tả  <textarea name="description" maxlength="1000">${escape(selectedDeck.description)}</textarea></label>
                    <label>Mức
                        <select name="level">
                            ${["A1","A2","B1","B2"].map(l => `<option ${l===selectedDeck.level?"selected":""}>${l}</option>`).join("")}
                        </select>
                    </label>
                    <div><button class="primary-button">Lưu thay đổi</button></div>
                </form>
            </div>
        </details>

        <!-- CSV import/export -->
        <details class="vocab-details vocab-animate-in">
            <summary>Nhập và xuất CSV</summary>
            <div class="vocab-details-body">
                <p style="font-size:.85rem;color:var(--ink-soft);margin:0 0 12px">
                    UTF-8, tối đa 1.000 dòng và 2 MB. Các cột: term, meaning_vi, part_of_speech, example_en, example_vi, tags.
                </p>
                <form data-vocab-form="import" class="vocab-form" style="gap:12px">
                    <label>File CSV <input name="file" type="file" accept=".csv,text/csv" required></label>
                    <div><button class="primary-button">Xem trước</button></div>
                </form>
                <div id="vocab-import-preview" style="margin-top:12px"></div>
                <a class="text-button" href="${base}/decks/${encodeURIComponent(id)}/export"
                   style="display:inline-block;margin-top:12px">Xuất CSV ↓</a>
            </div>
        </details>` : ""}

        <!-- Word grid -->
        <div class="vocab-section-heading vocab-animate-in" style="margin-top:8px">
            <h2 style="font-size:1.2rem">Danh sách từ</h2>
            <span style="font-size:.82rem;color:var(--ink-soft)">Trang ${result.notes.page}</span>
        </div>
        <div class="vocab-grid">${noteCards}</div>
        ${pagination(result.notes, "notes-page")}`;

        requestAnimationFrame(() => {
            root.querySelectorAll(".vocab-mastery-fill").forEach(el => {
                const w = el.style.width; el.style.width = "0%";
                requestAnimationFrame(() => { el.style.width = w; });
            });
        });
    }

    /* ── Note form ─────────────────────────────────────────────── */
    function noteForm(note = null) {
        currentNote = note;
        const v = key => escape(note?.[key] ?? "");
        root.innerHTML = toolbar() + `
        <section class="vocab-panel vocab-animate-in" style="max-width:700px">
            <h2 style="font-family:var(--display,serif);font-size:1.4rem;margin:0 0 20px">
                ${note ? "Sửa mục từ" : "Thêm mục từ"}
            </h2>
            ${sourceWord ? `
            <div style="padding:14px;background:var(--paper);border-radius:var(--radius-sm);
                        border-left:3px solid var(--teal);margin-bottom:18px">
                <p style="margin:0 0 8px;font-size:.82rem;color:var(--ink-soft)">
                    Bài: ${escape(sourceWord.lesson_title)}
                </p>
                <blockquote lang="en" style="margin:0;font-size:.9rem;color:var(--ink)">
                    ${escape(sourceWord.transcript)}
                </blockquote>
                <p style="margin:8px 0 0;font-size:.8rem;color:var(--ink-soft)">
                    ${sourceWord.evidence === "UNKNOWN_WORD" ? "Bạn đã đánh dấu chưa biết từ." :
                      "Lỗi nghe chưa chứng minh bạn quên nghĩa. Hãy xác nhận nghĩa muốn học."}
                </p>
            </div>` : ""}

            <form data-vocab-form="note" class="vocab-form">
                ${!selectedDeck ? `
                <label>Bộ lưu
                    <select name="deckId" required>
                        <option value="">Chọn bộ</option>${deckOptions()}
                    </select>
                </label>` : ""}
                <label>Từ / cụm từ
                    <input name="term" lang="en" maxlength="200" required
                           value="${v("term") || escape(sourceWord?.term || "")}">
                </label>
                <label>Nghĩa tiếng Việt cụ thể
                    <textarea name="meaningVi" maxlength="1000" required>${v("meaning_vi")}</textarea>
                </label>
                <label>Từ loại
                    <input name="partOfSpeech" maxlength="40" value="${v("part_of_speech")}">
                </label>
                <label>Ví dụ tiếng Anh
                    <textarea name="exampleEn" lang="en" maxlength="1000">${v("example_en") || escape(sourceWord?.transcript || "")}</textarea>
                </label>
                <label>Bản dịch ví dụ
                    <textarea name="exampleVi" maxlength="1000">${v("example_vi")}</textarea>
                </label>
                <label>Đáp án tiếng Anh thay thế (mỗi dòng một đáp án)
                    <textarea name="alternatives" maxlength="4000">${escape((note?.alternatives || []).join("\n"))}</textarea>
                </label>
                <label>Nhãn
                    <input name="tags" maxlength="500" value="${v("tags")}">
                </label>
                ${!admin ? `
                <label class="vocab-check">
                    <input name="meaningRecall" type="checkbox">
                    Thêm chiều nhớ nghĩa (có lịch riêng)
                </label>` : ""}
                <p style="font-size:.82rem;color:var(--ink-soft);margin:0">
                    Để học một nghĩa khác, hãy tạo mục mới. Sửa nội dung giữ lịch học hiện tại.
                </p>
                <div><button class="primary-button">Lưu mục từ</button></div>
            </form>

            ${note?.cards ? `
            <div style="margin-top:24px;padding-top:20px;border-top:1px solid var(--line)">
                <h3 style="font-size:1rem;margin:0 0 12px">Thẻ học</h3>
                ${note.cards.map(c => `
                <div style="display:flex;align-items:center;gap:12px;padding:10px 0;
                             border-bottom:1px solid var(--line);font-size:.875rem">
                    <span>${c.card_type === "WORD_RECALL" ? "Nhớ từ" : "Nhớ nghĩa"}</span>
                    <span style="color:var(--ink-soft)">${new Date(c.due_at).toLocaleString("vi-VN")}</span>
                    ${btn("suspend", c.suspended ? "Tiếp tục học" : "Tạm ngưng",
                          `data-id="${escape(c.id)}" data-revision="${c.revision}" data-suspended="${c.suspended}"`)}
                </div>`).join("")}
            </div>` : ""}

            ${note?.sources?.length ? `
            <div style="margin-top:20px;padding-top:18px;border-top:1px solid var(--line)">
                <h3 style="font-size:1rem;margin:0 0 12px">Ngữ cảnh đã gặp</h3>
                ${note.sources.map(s => `
                <blockquote style="margin:0 0 14px;padding:12px 14px;background:var(--paper);
                                   border-left:3px solid var(--teal);border-radius:0 var(--radius-sm) var(--radius-sm) 0">
                    <p lang="en" style="margin:0 0 6px;font-size:.875rem">${escape(s.transcript)}</p>
                    <small style="color:var(--ink-soft)">${escape(s.lesson_title)}</small>
                </blockquote>`).join("")}
            </div>` : ""}
        </section>`;
    }

    /* ── Sources view ──────────────────────────────────────────── */
    async function sources(page = 1) {
        admin = false; catalog = false; selectedDeck = null;
        root.innerHTML = toolbar() + skeletonGrid(4);
        const result = await request(`${base}/suggestions?page=${page}`);

        const cards = result.items.map(s => `
            <article class="vocab-source-card vocab-animate-in">
                <h3 lang="en">${escape(s.term)}</h3>
                <p style="font-size:.82rem;color:var(--ink-soft);margin:0">${escape(s.lesson_title)}</p>
                <blockquote lang="en">${escape(s.transcript)}</blockquote>
                <p style="font-size:.8rem;color:var(--ink-soft);margin:0">
                    ${s.evidence === "UNKNOWN_WORD" ? "Đã đánh dấu chưa biết từ" : "Cần xác nhận nguyên nhân"}
                </p>
                <div class="vocab-actions">
                    ${btn("save-source", "Lưu vào từ vựng", `data-source="${escape(JSON.stringify(s))}"`)}</div>
            </article>`).join("") ||
            `<div class="vocab-empty" style="grid-column:1/-1">
                <span class="vocab-empty-icon">✅</span>
                <p>Chưa có lỗi từ các bài đã làm.</p>
             </div>`;

        root.innerHTML = toolbar() + `
        <div class="vocab-section-heading vocab-animate-in">
            <h2>Từ các bài đã học</h2>
        </div>
        <p class="vocab-animate-in" style="color:var(--ink-soft);font-size:.9rem;margin:0 0 24px">
            Lưu từ bạn muốn học sau khi xác nhận nghĩa. Sai khi nghe chưa đồng nghĩa với quên từ.
        </p>
        <div class="vocab-grid">${cards}</div>
        ${pagination(result, "sources-page")}`;
    }

    /* ── Study session ─────────────────────────────────────────── */
    async function loadSession(id) {
        session = await request(`${base}/sessions/${encodeURIComponent(id)}`);
        renderSession();
    }

    function renderSession() {
        const item = session.items.find(i => !i.gradedAt);
        if (!item || session.status !== "ACTIVE") {
            root.innerHTML = toolbar() + `
                <div class="vocab-done vocab-animate-in">
                    <span class="vocab-done-icon">${session.status === "ABANDONED" ? "⏹️" : "🎉"}</span>
                    <h2>${session.status === "ABANDONED" ? "Phiên đã hết hạn." : "Hoàn thành phiên!"}</h2>
                    <p>${escape(session.message || "Kết quả đã được lưu. Bạn có thể ôn lại khi đến hạn.")}</p>
                    <div class="vocab-actions" style="justify-content:center">
                        ${primaryBtn("home","Về tổng quan")}
                    </div>
                </div>`;
            return;
        }

        const answered   = item.answer !== null;
        const feedback   = item.feedback || {};
        const failed     = item.assisted || item.evaluation === "INCORRECT";
        const progress   = item.position;
        const total      = session.items.length;
        const progressPct = total > 0 ? Math.round((progress / total) * 100) : 0;

        const kindLabel = {
            CONTEXT_TRANSLATION: "Dịch sang tiếng Việt",
            WORD_RECALL:         "Nhớ và nhập từ tiếng Anh",
        }[item.kind] ?? "Nhớ và nhập nghĩa tiếng Việt";

        root.innerHTML = `
        <div class="vocab-study vocab-animate-in" role="main">
            <!-- Progress header -->
            <div class="vocab-session-header">
                <span class="vocab-session-pos">Mục ${progress + 1} / ${total}</span>
                <div class="vocab-session-progress" style="flex:1">
                    <div class="vocab-session-track">
                        <div class="vocab-session-fill" style="width:${progressPct}%"></div>
                    </div>
                </div>
                ${btn("abandon","Kết thúc phiên",'style="margin-left:8px"')}
            </div>

            <!-- Kind eyebrow -->
            <p class="eyebrow">${escape(kindLabel)}</p>

            <!-- Prompt -->
            <h2 ${item.kind !== "WORD_RECALL" ? 'lang="en"' : ""}>${escape(item.prompt)}</h2>

            ${!answered ? `
            <!-- Answer form -->
            <form data-vocab-form="answer" class="vocab-form" style="margin:0;gap:14px">
                <label>Đáp án của bạn
                    <textarea name="answer" class="vocab-answer-area"
                              maxlength="4000" required
                              ${item.kind === "WORD_RECALL" ? 'lang="en"' : ""}></textarea>
                </label>
                <div class="vocab-actions">
                    <button class="primary-button">Kiểm tra →</button>
                    ${btn("reveal","Không nhớ / xem đáp án")}
                </div>
            </form>` : `

            <!-- Feedback -->
            <div class="vocab-feedback vocab-animate-in">
                <div class="vocab-feedback-result ${item.evaluation === "CORRECT" ? "correct" : item.evaluation === "INCORRECT" ? "incorrect" : "ambiguous"}">
                    ${item.evaluation === "CORRECT" ? "✓ Khớp đáp án đã có." :
                      item.evaluation === "INCORRECT" ? "✗ Chưa nhớ độc lập." :
                      "⚠ Có nhiều cách diễn đạt. Hãy đối chiếu và tự đánh giá."}
                </div>
                <p style="font-size:.85rem;color:var(--ink-soft);margin:0 0 14px">
                    Đáp án bạn nhập: <em>${escape(item.answer || "Không nhớ")}</em>
                </p>

                ${item.kind === "CONTEXT_TRANSLATION" ? `
                <p style="margin:0 0 8px">${escape(feedback.translationVi ?? "")}</p>
                <p style="font-size:.82rem;color:var(--ink-soft);margin:0">
                    ${feedback.source === "PERSONAL_EXAMPLE" ? "Ví dụ một từ: chưa có tổ hợp nhiều từ phù hợp." : "Câu kết hợp nhiều từ đã học."}
                </p>` : `
                <h3 lang="en">${escape(feedback.term ?? "")}</h3>
                <p style="margin:0 0 6px">${escape(feedback.meaning_vi ?? "")}</p>
                <p lang="en" class="vocab-word-example">${escape(feedback.example_en ?? "")}</p>
                <p class="vocab-word-example">${escape(feedback.example_vi ?? "")}</p>`}

                <form data-vocab-form="grade" class="vocab-form" style="margin:0;gap:10px">
                    ${(feedback.targets || []).map(t => `
                    <label>${escape(t.term)} — ${escape(t.meaningVi)}
                        <select name="target:${escape(t.noteId)}">
                            <option value="REMEMBERED">Nhớ</option>
                            <option value="UNSURE">Chưa chắc</option>
                            <option value="FORGOTTEN">Quên</option>
                        </select>
                    </label>`).join("")}

                    <p style="font-size:.82rem;color:var(--ink-soft);margin:0">
                        ${item.kind === "CONTEXT_TRANSLATION" ?
                          "Đánh giá câu và từng từ được lưu riêng, không tăng lịch ôn các từ." :
                          "Khó nghĩa là vẫn nhớ được. Nếu không nhớ, hãy chọn Quên."}
                    </p>

                    <div class="vocab-grade-row">
                        ${[["AGAIN","Quên","again"],["HARD","Khó","hard"],["GOOD","Nhớ","good"],["EASY","Dễ","easy"]].map(([g, label, cls]) => `
                        <button type="submit" class="vocab-grade-btn ${cls}"
                                name="grade" value="${g}"
                                ${failed && g !== "AGAIN" ? "disabled" : ""}>
                            ${label}
                        </button>`).join("")}
                    </div>
                </form>
            </div>`}
        </div>`;

        if (!answered) {
            const input = root.querySelector('[name="answer"]');
            try {
                const draft = JSON.parse(sessionStorage.getItem(`dictalab.vocabulary.${owner}.${item.id}`) || "null");
                if (draft && Date.now() - draft.at < 86400000) input.value = draft.text;
            } catch {}
            input.focus();
        }

        requestAnimationFrame(() => {
            const fill = root.querySelector(".vocab-session-fill");
            if (fill) { fill.style.width = "0%"; requestAnimationFrame(() => { fill.style.width = `${progressPct}%`; }); }
        });
    }

    /* ── Context form (admin) ──────────────────────────────────── */
    async function contextForm() {
        const options = visibleNotes.map(n =>
            `<option value="${escape(n.id)}" data-term="${escape(n.term)}">${escape(n.term)} — ${escape(n.meaning_vi)}</option>`).join("");
        root.innerHTML = toolbar() + `
        <section class="vocab-panel vocab-animate-in" style="max-width:640px">
            <h2 style="font-family:var(--display,serif);font-size:1.4rem;margin:0 0 20px">Thêm câu kết hợp từ</h2>
            <form data-vocab-form="context" class="vocab-form">
                <label>Câu tiếng Anh <input name="prompt" required maxlength="1000" lang="en"></label>
                <label>Bản dịch tiếng Việt <textarea name="translationVi" required maxlength="1000"></textarea></label>
                ${[1,2].map(i => `
                <label>Từ mục tiêu ${i} <select name="note${i}" required>${options}</select></label>
                <label>Dạng xuất hiện trong câu ${i} <input name="surface${i}" required maxlength="200"></label>`).join("")}
                <label class="vocab-check">
                    <input name="published" type="checkbox">
                    Xuất bản câu sau khi đã kiểm tra nghĩa và ngữ pháp
                </label>
                <div><button class="primary-button">Lưu câu</button></div>
            </form>
        </section>`;
    }

    /* ── Event: input (draft autosave) ─────────────────────────── */
    root.addEventListener("input", event => {
        if (event.target.name !== "answer" || !session) return;
        const item = session.items.find(i => !i.gradedAt); if (!item) return;
        try { sessionStorage.setItem(`dictalab.vocabulary.${owner}.${item.id}`, JSON.stringify({ at: Date.now(), text: event.target.value })); } catch {}
    });

    root.addEventListener("change", event => {
        if (event.target.name === "file") {
            importGeneration++;
            root.querySelector("#vocab-import-preview")?.replaceChildren();
        }
    });

    /* ── Event: click ───────────────────────────────────────────── */
    root.addEventListener("click", event => {
        const card = event.target.closest(".vocab-deck-card[data-vocab-action='deck']");
        const target = event.target.closest("[data-vocab-action]") || card;
        if (!target) return;
        run(async () => {
            const action = target.dataset.vocabAction;
            if (action === "home") { await bridge.navigate("vocabulary"); return; }
            if (["mine","catalog","admin"].includes(action)) {
                admin = action === "admin"; catalog = action === "catalog"; selectedDeck = null;
                await listDecks();
            }
            else if (action === "decks-page")  await listDecks(Number(target.dataset.page));
            else if (action === "deck")         await openDeck(target.dataset.id);
            else if (action === "notes-page")   await openDeck(selectedDeck.id, Number(target.dataset.page));
            else if (action === "sources" || action === "sources-page") await sources(Number(target.dataset.page || 1));
            else if (action === "save-source")  { sourceWord = JSON.parse(target.dataset.source); selectedDeck = null; noteForm(); }
            else if (action === "new-note")     { sourceWord = null; noteForm(); }
            else if (action === "edit-note") {
                sourceWord = null;
                const note = admin ? visibleNotes.find(n => n.id === target.dataset.id) : await request(`${base}/notes/${target.dataset.id}`);
                noteForm(note);
            }
            else if (action === "remove-note")  { await mutate(`${base}/decks/${selectedDeck.id}/notes/${target.dataset.id}`, {}, "DELETE"); await openDeck(selectedDeck.id); }
            else if (action === "suspend")      { await mutate(`${base}/cards/${target.dataset.id}`, { suspended: target.dataset.suspended !== "true", revision: Number(target.dataset.revision) }, "PATCH"); noteForm(await request(`${base}/notes/${currentNote.id}`)); }
            else if (action === "publish")      { await mutate(`${adminBase}/decks/${selectedDeck.id}/publication`, { published: selectedDeck.status !== "PUBLISHED", revision: selectedDeck.revision }); await openDeck(selectedDeck.id); }
            else if (action === "start")        { session = await mutate(`${base}/sessions`, { mode: target.dataset.mode, deckId: target.dataset.deck ? selectedDeck.id : null }); if (session.id) await bridge.navigate("vocabulary", { sessionId: session.id }); else renderSession(); }
            else if (action === "resume")       await bridge.navigate("vocabulary", { sessionId: target.dataset.id });
            else if (action === "abandon")      { await mutate(`${base}/sessions/${session.id}/abandon`); await bridge.navigate("vocabulary"); }
            else if (action === "reveal")       { const item = session.items.find(i => !i.gradedAt); await mutate(`${base}/session-items/${item.id}/reveal`); await loadSession(session.id); }
            else if (action === "confirm-import") { await mutate(`${base}/imports/${target.dataset.id}/confirm`); await openDeck(selectedDeck.id); }
            else if (action === "new-context")  await contextForm();
        });
    });

    /* ── Event: submit ──────────────────────────────────────────── */
    root.addEventListener("submit", event => {
        const form = event.target.closest("[data-vocab-form]"); if (!form) return;
        event.preventDefault();
        const data = new FormData(form), kind = form.dataset.vocabForm, grade = event.submitter?.value;
        run(async () => {
            if (kind === "deck" || kind === "edit-deck") {
                const payload = { title: data.get("title"), description: data.get("description"), level: data.get("level"), revision: kind === "edit-deck" ? selectedDeck.revision : 0 };
                const result = await mutate(`${admin ? adminBase : base}/decks${kind === "edit-deck" ? "/" + selectedDeck.id : ""}`, payload, kind === "edit-deck" ? "PATCH" : "POST");
                if (!admin) await refreshPersonal(); await openDeck(result.id);
            }
            else if (kind === "copy") { await mutate(`${base}/catalog/decks/${selectedDeck.id}/add`, { deckId: data.get("deckId") }); admin = false; catalog = false; await openDeck(data.get("deckId")); }
            else if (kind === "note") {
                const payload = { term: data.get("term"), meaningVi: data.get("meaningVi"), partOfSpeech: data.get("partOfSpeech"), exampleEn: data.get("exampleEn"), exampleVi: data.get("exampleVi"), tags: data.get("tags"), alternatives: String(data.get("alternatives")).split("\n").map(x => x.trim()).filter(Boolean), revision: currentNote?.revision || 0, meaningRecall: data.has("meaningRecall") };
                const deck = selectedDeck?.id || data.get("deckId");
                if (sourceWord) await mutate(`${base}/decks/${deck}/from-practice`, { wordAttemptId: sourceWord.word_attempt_id, existingNoteId: null, note: payload });
                else await mutate(currentNote ? `${admin ? adminBase : base}/notes/${currentNote.id}` : `${admin ? adminBase : base}/decks/${deck}/notes`, payload, currentNote ? "PATCH" : "POST");
                sourceWord = null; await openDeck(deck);
            }
            else if (kind === "answer") { const item = session.items.find(i => !i.gradedAt); await mutate(`${base}/session-items/${item.id}/answer`, { answer: data.get("answer") }); await loadSession(session.id); }
            else if (kind === "grade") {
                const item = session.items.find(i => !i.gradedAt), targets = [];
                for (const [name, result] of data) if (name.startsWith("target:")) targets.push({ noteId: name.slice(7), result });
                await mutate(`${base}/session-items/${item.id}/grade`, { grade, targets });
                try { sessionStorage.removeItem(`dictalab.vocabulary.${owner}.${item.id}`); } catch {}
                await loadSession(session.id);
            }
            else if (kind === "import") {
                const previewGeneration = ++importGeneration;
                root.querySelector("#vocab-import-preview").replaceChildren();
                const file = data.get("file"); if (file.size > 2097152) throw new Error("File tối đa 2 MB.");
                let csv;
                try { csv = new TextDecoder("utf-8", { fatal: true }).decode(await file.arrayBuffer()); }
                catch { throw new Error("File phải dùng mã hóa UTF-8. Hãy lưu lại dưới dạng CSV UTF-8."); }
                const result = await mutate(`${base}/imports/preview`, { deckId: selectedDeck.id, csv });
                if (previewGeneration !== importGeneration) return;
                root.querySelector("#vocab-import-preview").innerHTML = result.errors.length ?
                    `<ul>${result.errors.map(e => `<li>${escape(e)}</li>`).join("")}</ul>` :
                    `<p>${result.count} mục từ hợp lệ. Các từ trùng chính xác sẽ được dùng lại.</p>
                     <ul>${result.preview.map(n => `<li>${escape(n.term)} — ${escape(n.meaningVi)}</li>`).join("")}</ul>
                     ${btn("confirm-import","Xác nhận nhập",`data-id="${escape(result.id)}"`)}`;
            }
            else if (kind === "context") {
                await mutate(`${adminBase}/contexts`, { prompt: data.get("prompt"), translationVi: data.get("translationVi"), level: selectedDeck.level, alternatives: [], targets: [1,2].map(i => ({ noteId: data.get(`note${i}`), surfaceForm: data.get(`surface${i}`) })), published: data.has("published"), revision: 0 });
                await openDeck(selectedDeck.id);
            }
        });
    });

    window.DictaLabVocabulary = { show, leave };
})();
