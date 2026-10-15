(() => {
    "use strict";
    const bridge = () => window.DictaLabListeningBridge;
    const escape = value => String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;").replaceAll('"', "&quot;");
    const goals = {IELTS: "IELTS", TOEIC: "TOEIC", COMMUNICATION: "Giao tiếp", ALL: "Tất cả bài"};
    let snapshot, request = 0, selected, listScroll = 0, adminSnapshot, adminLoading = false;
    const query = () => new URLSearchParams(location.search);
    const title = lesson => lesson.title.replace(/^[AB][12]\s*·\s*/, "");
    const metadata = id => snapshot.metadata.find(item => item.lessonId === id && item.reviewed);
    const members = course => snapshot.memberships.filter(item => item.courseId === course);
    const courseLessons = course => new Set(members(course).map(item => item.lessonId));
    const progress = course => {
        const ids = courseLessons(course);
        return {total: ids.size, done: snapshot.lessons.filter(item => ids.has(item.id) && item.completed).length};
    };
    function updateUrl(changes, replace = false) {
        const url = new URL(location.href);
        for (const [key, value] of Object.entries(changes)) {
            if (value == null || value === "") url.searchParams.delete(key); else url.searchParams.set(key, value);
        }
        history[replace ? "replaceState" : "pushState"]({}, "", url.pathname + url.search);
    }
    function context() {
        const params = query(), goal = goals[params.get("goal")] ? params.get("goal") : "ALL";
        const courses = snapshot.courses.filter(item => item.goal === goal && item.published);
        const course = courses.find(item => item.id === params.get("course")) || courses[0];
        return {params, goal, courses, course};
    }
    function restoreRetiredCourse() {
        const params = query(), old = params.get("course");
        if (!old) {
            const matching = snapshot.courses.find(item => item.goal === params.get("goal") &&
                members(item.id).some(m => m.lessonId === Number(params.get("lesson"))));
            if (matching) updateUrl({course: matching.id, group: snapshot.groups.some(g =>
                g.id === params.get("group") && g.courseId === matching.id) ? params.get("group") : null}, true);
            return;
        }
        if (snapshot.courses.some(item => item.id === old)) return;
        const aliases = {"ielts-foundation":"ielts-band-3-0", "ielts-5-0":"ielts-band-5-0",
            "ielts-6-0":"ielts-band-6-0", "ielts-7-0":"ielts-band-7-0", "ielts-8-0plus":"ielts-band-8-0plus",
            "toeic-foundation":"toeic-score-300", "toeic-450plus":"toeic-score-500", "toeic-650plus":"toeic-score-700",
            "toeic-800plus":"toeic-score-900plus", "toeic-900plus":"toeic-score-900plus",
            "communication-basic":"communication-cefr-a1", "communication-everyday":"communication-cefr-a2",
            "communication-work":"communication-cefr-b1", "communication-natural":"communication-cefr-b2"};
        if (!aliases[old]) return;
        const goal = old.startsWith("ielts-") ? "IELTS" : old.startsWith("toeic-") ? "TOEIC" : "COMMUNICATION";
        const lesson = Number(params.get("lesson"));
        const matching = snapshot.courses.find(item => item.goal === goal && members(item.id).some(m => m.lessonId === lesson));
        updateUrl({goal, course: matching?.id || aliases[old], group: null, page: null}, true);
    }
    function visibleLessons() {
        const {params, goal, course} = context();
        const links = course ? members(course.id) : [];
        const groups = snapshot.groups.filter(item => item.courseId === course?.id);
        const order = new Map(links.map(item => [item.lessonId, (groups.find(g => g.id === item.groupId)?.position || 0) * 10001 + item.position]));
        const term = (params.get("search") || "").trim().toLocaleLowerCase("vi");
        return snapshot.lessons.filter(lesson => {
            if (goal !== "ALL" && (!course?.ready || !order.has(lesson.id))) return false;
            const m = metadata(lesson.id), link = links.find(item => item.lessonId === lesson.id);
            if (params.get("group") && link?.groupId !== params.get("group")) return false;
            if (params.get("topic") && m?.topic !== params.get("topic")) return false;
            if (params.get("focus") && m?.focus !== params.get("focus")) return false;
            if (params.get("accent") && m?.accent !== params.get("accent")) return false;
            if (params.get("speakers") && String(m?.speakerCount) !== params.get("speakers")) return false;
            if (params.get("difficulty") && String(link?.difficulty) !== params.get("difficulty")) return false;
            const status = lesson.completed ? "completed" : lesson.inProgress ? "active" : "new";
            if (params.get("status") && params.get("status") !== status) return false;
            const duration = params.get("duration");
            if (duration === "short" && lesson.estimatedMinutes > 10) return false;
            if (duration === "medium" && (lesson.estimatedMinutes <= 10 || lesson.estimatedMinutes > 20)) return false;
            if (duration === "long" && lesson.estimatedMinutes <= 20) return false;
            return !term || [title(lesson), m?.topic, m?.focus, lesson.description].some(value => value?.toLocaleLowerCase("vi").includes(term));
        }).sort((a, b) => goal === "ALL" ? a.id - b.id : order.get(a.id) - order.get(b.id));
    }
    function selectOptions(values, current, label) {
        return `<option value="">${escape(label)}</option>` + [...new Set(values.filter(value => value != null && value !== ""))].map(value =>
            `<option value="${escape(value)}" ${String(value) === current ? "selected" : ""}>${escape(value)}</option>`).join("");
    }
    function navigation() {
        const root = document.querySelector("#library-navigation"), {params, goal, courses, course} = context();
        const active = snapshot.activeSessions.find(item => snapshot.lessons.some(lesson => lesson.id === item.lessonId));
        const continuing = snapshot.lessons.find(item => item.id === active?.lessonId);
        let changed = "";
        if (course) {
            const {total} = progress(course.id);
            try {
                const key = `dictalab-course-total:${course.id}`, old = localStorage.getItem(key);
                if (old != null && Number(old) !== total) changed = `Số bài ở mức này đã đổi từ ${old} thành ${total}; tỷ lệ được tính theo số bài hiện tại. Kết quả đã hoàn thành được giữ nguyên.`;
                localStorage.setItem(key, String(total));
            } catch (_) { /* Library remains usable without browser storage. */ }
        }
        root.innerHTML = `${continuing && bridge().currentUser() ? `<section class="library-resume" aria-label="Tiếp tục học"><div><p class="section-index">Tiếp tục học gần nhất</p><strong>${escape(title(continuing))}</strong><p>${continuing.completed ? `Đã hoàn thành 100% · Luyện lại ${continuing.progressPercent}%` : `Đang học ${continuing.progressPercent}%`} · ${escape(snapshot.courses.find(item => courseLessons(item.id).has(continuing.id))?.title || "Tất cả bài")}</p></div><a class="primary-button compact" href="/practice/${encodeURIComponent(active.sessionId)}">${continuing.completed ? "Tiếp tục luyện lại" : "Tiếp tục học"} →</a></section>` : ""}
            <nav class="library-goals" aria-label="Hướng luyện">${Object.entries(goals).map(([id, label]) => `<button type="button" data-library-goal="${id}" aria-pressed="${goal === id}">${escape(label)}</button>`).join("")}</nav>
            ${courses.length ? `<section class="library-difficulty" aria-label="Độ khó bài"><h2 class="section-index">Độ khó bài${goal === "COMMUNICATION" ? " · CEFR" : " · tham khảo"}</h2><div class="library-courses" role="group" aria-label="Chọn độ khó bài">${courses.map(item => {const p = progress(item.id); return `<button type="button" class="library-course" data-library-course="${item.id}" aria-pressed="${item.id === course?.id}"><strong>${escape(item.title)}</strong><span>${escape(item.targetLabel)}</span><span class="section-index">${item.ready ? bridge().currentUser() ? `${p.done}/${p.total} bài hoàn thành` : `${p.total} bài luyện` : "Đang bổ sung nội dung"}</span>${item.ready && bridge().currentUser() ? `<progress max="${p.total || 1}" value="${p.done}" aria-label="${escape(item.title)}: ${p.done}/${p.total} bài"></progress>` : ""}</button>`;}).join("")}</div></section>` : ""}
            <div class="library-path"><span>${escape(goals[goal])}${course ? ` › ${escape(course.title)}` : " · Luyện tự do"}${params.get("group") ? ` › ${escape(snapshot.groups.find(item => item.id === params.get("group"))?.title || "")}` : ""}</span><div><a href="/history">Lịch sử học</a><a href="/dashboard#dashboard-error-bank">Từ nghe lỡ &amp; chưa biết</a></div></div>
            ${course ? `<div class="library-course-note"><p>${escape(course.description)}</p><small>${escape(course.coverageNote)} ${goal === "TOEIC" ? "Mốc TOEIC tham khảo thang tổng điểm Listening & Reading; bài chỉ luyện nghe." : ""}</small>${changed ? `<p role="status">${escape(changed)}</p>` : ""}</div>` : ""}`;
        const audited = snapshot.metadata.filter(item => item.reviewed);
        const groupOptions = snapshot.groups.filter(item => item.courseId === course?.id);
        document.querySelector("#catalog-summary").innerHTML = `<form class="library-filters" role="search" aria-label="Tìm bài luyện">
            <label>Tìm bài<input name="search" type="search" value="${escape(params.get("search"))}" placeholder="Tên bài hoặc tình huống"></label>
            ${course?.ready ? `<label>Nhóm bài<select name="group">${selectOptions(groupOptions.map(item => item.id), params.get("group"), "Tất cả nhóm").replace(/>([^<]+)<\/option>/g, (all, id) => `>${escape(groupOptions.find(g => g.id === id)?.title || id)}</option>`)}</select></label>` : ""}
            <label>Chủ đề<select name="topic">${selectOptions(audited.map(item => item.topic), params.get("topic"), "Tất cả chủ đề")}</select></label>
            <label>Thời gian ước tính<select name="duration"><option value="">Mọi thời lượng</option>${[["short", "≤ 10 phút"],["medium", "11–20 phút"],["long", "> 20 phút"]].map(([v,t]) => `<option value="${v}" ${params.get("duration") === v ? "selected" : ""}>${t}</option>`).join("")}</select></label>
            ${bridge().currentUser() ? `<label>Trạng thái<select name="status"><option value="">Mọi trạng thái</option>${[["new","Chưa học"],["active","Đang học"],["completed","Đã hoàn thành"]].map(([v,t]) => `<option value="${v}" ${params.get("status") === v ? "selected" : ""}>${t}</option>`).join("")}</select></label>` : ""}
            <label>Trọng tâm nghe<select name="focus">${selectOptions(audited.map(item => item.focus), params.get("focus"), "Tất cả trọng tâm")}</select></label>
            ${course?.ready ? `<label>Thử thách trong mức<select name="difficulty">${selectOptions(members(course.id).map(item => item.difficulty).sort(), params.get("difficulty"), "Mọi mức thử thách")}</select></label>` : ""}
            ${audited.some(item => item.accent) ? `<label>Giọng đọc<select name="accent">${selectOptions(audited.map(item => item.accent), params.get("accent"), "Mọi giọng đọc")}</select></label>` : ""}
            ${audited.some(item => item.speakerCount) ? `<label>Số người nói<select name="speakers">${selectOptions(audited.map(item => item.speakerCount), params.get("speakers"), "Mọi số người nói")}</select></label>` : ""}
            <button class="text-button" type="button" data-library-reset>Xóa bộ lọc</button></form>`;
        root.onclick = event => {
            const goalButton = event.target.closest("[data-library-goal]"), courseButton = event.target.closest("[data-library-course]");
            if (bridge().library.busy() || (!goalButton && !courseButton)) return;
            updateUrl({goal: goalButton?.dataset.libraryGoal || goal, course: courseButton?.dataset.libraryCourse, group: null, lesson: null, page: null, difficulty: null});
            navigation(); renderList();
        };
        const filters = document.querySelector(".library-filters");
        filters.onsubmit = event => event.preventDefault();
        filters.onchange = event => {
            if (!event.target.name || event.target.name === "search" || bridge().library.busy()) return;
            updateUrl({[event.target.name]: event.target.value, lesson: null, page: null}, true); renderList();
        };
        let timer;
        filters.oninput = event => {
            if (event.target.name !== "search") return;
            clearTimeout(timer); timer = setTimeout(() => {
                if (bridge().library.busy()) return;
                updateUrl({search: event.target.value, lesson: null, page: null}, true); renderList();
            }, 180);
        };
        filters.querySelector("[data-library-reset]").onclick = () => {
            updateUrl(Object.fromEntries(["group","topic","focus","accent","speakers","duration","status","difficulty","search","lesson","page"].map(key => [key, null])));
            navigation(); renderList();
        };
    }
    function renderList() {
        const grid = document.querySelector("#lesson-grid"), detail = document.querySelector("#lesson-detail");
        const lessons = visibleLessons(), {course, goal} = context();
        bridge().library.setLessons(lessons.map(lesson => ({...lesson, topic: metadata(lesson.id)?.topic || lesson.topic})));
        grid.setAttribute("aria-busy", "false"); detail.setAttribute("aria-busy", "false");
        document.querySelector("#lesson-count").textContent = `${lessons.length} bài${bridge().currentUser() ? ` · ${lessons.filter(item => item.completed).length} đã hoàn thành` : ""}`;
        document.querySelector("#level-description").textContent = "Chọn hướng luyện và độ khó bài, xem chi tiết rồi nghe thử. Tiến độ được lưu khi đăng nhập.";
        grid.closest(".catalog-workspace").removeAttribute("data-detail-open");
        grid.nextElementSibling?.matches(".library-pagination") && grid.nextElementSibling.remove();
        const pages = Math.max(1, Math.ceil(lessons.length / 6));
        const requestedIndex = lessons.findIndex(item => item.id === Number(query().get("lesson")));
        const page = Math.max(1, Math.min(pages, requestedIndex >= 0 ? Math.floor(requestedIndex / 6) + 1 : Number(query().get("page")) || 1));
        if (page > 1 || query().has("page")) updateUrl({page},true);
        const offset = (page - 1) * 6, pageLessons = lessons.slice(offset, offset + 6);
        let lastGroup;
        grid.innerHTML = pageLessons.map((lesson, index) => {
            const link = members(course?.id).find(item => item.lessonId === lesson.id), m = metadata(lesson.id);
            const group = snapshot.groups.find(item => item.id === link?.groupId);
            const heading = goal !== "ALL" && group?.id !== lastGroup ? `<h3 class="library-group-heading">${escape(group?.title)}</h3>` : "";
            lastGroup = group?.id;
            return `${heading}<article class="catalog-list-item ${lesson.completed ? "is-completed" : ""}" data-catalog-lesson="${lesson.id}">
                <button type="button" class="catalog-select" data-select-lesson="${lesson.id}" aria-pressed="false" aria-controls="lesson-detail">
                    <span class="lesson-card-number" aria-hidden="true">${String(offset + index + 1).padStart(2,"0")}</span><span class="catalog-list-copy">
                    <span class="lesson-progress-heading">${bridge().currentUser() ? lesson.completed ? `✓ Đã hoàn thành · 100%${lesson.inProgress ? ` · Luyện lại ${lesson.progressPercent}%` : ""}` : lesson.inProgress ? `Đang học · ${lesson.progressPercent}%` : "Chưa bắt đầu" : "Xem trước bài luyện"}</span>
                    <span class="catalog-list-source">${escape(m?.topic || "Chưa phân loại")}${link ? ` · ${escape(course.title)}` : ""} · Mã bài ${lesson.id}</span>
                    <strong class="catalog-list-title">${escape(title(lesson))}</strong><span class="catalog-list-description">${escape(link?.purpose || m?.focus || lesson.description)}</span></span>
                    <span class="catalog-row-end"><span class="catalog-selected-label">Đang xem chi tiết</span><span class="catalog-list-meta">${lesson.sentenceCount} câu · ~${lesson.estimatedMinutes} phút</span></span>
                    <span class="catalog-list-preview"><span class="catalog-list-mobile-meta">${lesson.sentenceCount} câu · ~${lesson.estimatedMinutes} phút</span><span>Từ khó:</span> ${(lesson.previewWords || []).slice(0,3).map(item => `<span class="catalog-word">${escape(item.word)}</span>`).join(" ")}<span class="catalog-select-label">Chi tiết &amp; nghe thử →</span></span>
                </button></article>`;
        }).join("");
        if (!lessons.length) {
            grid.innerHTML = `<div class="library-empty"><h3>${course && !course.ready ? "Đang bổ sung nội dung" : "Không có bài phù hợp"}</h3><p>${course && !course.ready ? "Mức này chưa đủ bài phù hợp để công bố. Hãy chọn mức khác hoặc luyện những bài hiện có trong thư viện." : "Hãy đổi hoặc xóa bộ lọc để xem các bài khác."}</p><button type="button" class="primary-button compact" data-library-all>Tất cả bài luyện</button></div>`;
            detail.innerHTML = '<p>Chọn bài để xem mục đích, thông tin và nghe thử.</p>';
            grid.querySelector("[data-library-all]").onclick = () => { updateUrl({goal: "ALL", course: null, group: null, topic: null, status: null, search: null, duration: null, focus: null, difficulty: null, lesson: null}); navigation(); renderList(); };
            return;
        }
        if (pages > 1) {
            const pager = document.createElement("nav"); pager.className = "pagination library-pagination";
            pager.setAttribute("aria-label", "Phân trang bài luyện");
            pager.innerHTML = `<button type="button" class="text-button" data-page="${page-1}" ${page===1 ? "disabled" : ""}>← Trang trước</button><span role="status" tabindex="-1">Trang ${page}/${pages}</span><button type="button" class="text-button" data-page="${page+1}" ${page===pages ? "disabled" : ""}>Trang sau →</button>`;
            pager.onclick = event => {
                const button = event.target.closest("[data-page]"); if (!button || button.disabled || bridge().library.busy()) return;
                updateUrl({page: button.dataset.page, lesson: null}); selected = null; renderList();
                grid.scrollIntoView({block:"start"}); grid.nextElementSibling?.querySelector("[role=status]")?.focus({preventScroll:true});
            };
            grid.after(pager);
        }
        const wanted = Number(query().get("lesson")) || selected;
        bridge().library.select(pageLessons.find(item => item.id === wanted)?.id || pageLessons[0].id);
    }
    function decorateDetail(lesson) {
        selected = lesson.id;
        updateUrl({lesson: lesson.id}, true);
        const detail = document.querySelector("#lesson-detail"), m = metadata(lesson.id), {course} = context();
        const link = members(course?.id).find(item => item.lessonId === lesson.id);
        detail.querySelector("h3").textContent = title(lesson);
        const meta = detail.querySelector(".lesson-meta");
        if (meta) meta.innerHTML = `<span>Quy mô<strong>${lesson.sentenceCount} câu</strong></span><span>Thời gian ước tính<strong>~${lesson.estimatedMinutes} phút</strong></span><span>Nguồn<strong>${escape(m?.source || "Theo bài gốc")}</strong></span>`;
        const extra = document.createElement("div"); extra.className = "library-detail-context";
        extra.innerHTML = `${link ? `<p><strong>Độ khó bài:</strong> ${escape(goals[course.goal])} · ${escape(course.title)}${course.goal !== "COMMUNICATION" ? " (tham khảo)" : ""}</p><p><strong>Mục đích bài:</strong> ${escape(link.purpose)}</p>` : ""}${m ? `<p><strong>Trọng tâm nghe:</strong> ${escape(m.focus)}</p>` : ""}<small>CEFR tham khảo: ${escape(lesson.level)}${link ? ` · Thử thách trong mức: ${link.difficulty}/5` : ""}</small><div class="library-history-links"><a href="/history">Lịch sử học</a><a href="/dashboard#dashboard-error-bank">Từ nghe lỡ &amp; chưa biết</a></div>`;
        if (/^sample-(ielts|toeic|communication)-[a-z0-9-]+-20261015$/.test(lesson.slug)) {
            const sampleLink = document.createElement("a");
            sampleLink.href = `/audio/listening-samples-20261015/index.html#${encodeURIComponent(lesson.slug)}`;
            sampleLink.className = "text-button"; sampleLink.textContent = "Nghe toàn bài & làm câu hỏi →";
            extra.append(sampleLink);
        }
        if (/^b1-(ielts|toeic|communication)-[a-z0-9-]+-20261015$/.test(lesson.slug)) {
            const exerciseLink = document.createElement("a");
            exerciseLink.href = `/audio/b1-expansion-20261015/${encodeURIComponent(lesson.slug)}.html`;
            exerciseLink.className = "text-button"; exerciseLink.textContent = "Nghe toàn bài & làm câu hỏi →";
            extra.append(exerciseLink);
        }
        detail.querySelector(".catalog-method")?.before(extra);
    }
    async function show() {
        const root = document.querySelector("#library-navigation"); if (!root) return;
        const version = ++request;
        const requestedStart = query().get("start") === "1" ? Number(query().get("lesson")) : null;
        root.textContent = "Đang tải bài luyện theo độ khó…";
        try {
            const result = await bridge().api("/api/v1/library");
            if (version !== request || document.body.dataset.route !== "catalog") return;
            snapshot = result;
            restoreRetiredCourse();
            document.querySelector("#level-tabs").hidden = true;
            navigation(); renderList();
            if (requestedStart && bridge().currentUser()) {
                updateUrl({start: null}, true);
                if (selected === requestedStart) await bridge().library.start(requestedStart);
                else bridge().showToast("Bài đã chọn không còn trong bộ lọc hiện tại. Hãy chọn lại bài.",true);
            }
        } catch (error) {
            if (version !== request) return;
            root.innerHTML = `<p role="alert">Không tải được thư viện: ${escape(error.message)}</p><button type="button" class="text-button" data-retry>Thử lại</button>`;
            root.querySelector("[data-retry]").onclick = show;
            document.querySelector("#lesson-grid").replaceChildren();
            document.querySelector("#lesson-grid").setAttribute("aria-busy", "false");
            document.querySelector("#lesson-detail").innerHTML = '<p>Hãy thử tải lại thư viện.</p>';
        }
    }
    document.addEventListener("click", event => {
        if (event.target.closest("[data-select-lesson]") && window.matchMedia("(max-width: 760px)").matches) listScroll = window.scrollY;
        if (event.target.closest("[data-catalog-back]")) requestAnimationFrame(() => window.scrollTo({top: listScroll}));
    }, true);

    const adminFields = {
        courses: [["id","Mã mức luyện","id"],["goal","Hướng luyện","goal"],["title","Tên mức độ khó",180],["targetLabel","Nhãn mô tả mức",200],["description","Mô tả",1000],["criteria","Tiêu chí nội dung",3000],["coverageNote","Phạm vi & nội dung còn thiếu",1000],["position","Thứ tự","number"],["published","Công bố","check"],["ready","Đủ nội dung","check"]],
        groups: [["id","Mã nhóm","id"],["courseId","Mức luyện","course"],["title","Tên nhóm",180],["position","Thứ tự","number"]],
        lessons: [["courseId","Mức luyện","course"],["groupId","Nhóm","group"],["lessonId","Bài gốc","lesson"],["position","Thứ tự trong nhóm","number"],["difficulty","Thử thách tương đối (1–5)","difficulty"],["purpose","Mục đích bài",1000]],
        metadata: [["lessonId","Bài gốc","lesson"],["topic","Chủ đề",180],["focus","Trọng tâm nghe",180],["source","Nguồn",180],["accent","Giọng đọc đã xác minh",120],["speakerCount","Số người nói đã xác minh","optional-number"],["auditNotes","Ghi chú kiểm tra transcript/audio",3000],["reviewed","Đã kiểm tra nội dung hiện tại","check"]]
    };
    function adminForm(kind, item = {}) {
        const root = document.querySelector("#library-admin-editor");
        const fields = adminFields[kind].map(([key,label,type]) => {
            const value = item[key] ?? (key === "position" || key === "difficulty" ? 1 : "");
            let control;
            const options = type === "goal" ? Object.entries(goals).filter(([key]) => key !== "ALL").map(([id,title]) => ({id,title})) : type === "course" ? adminSnapshot.courses : type === "group" ? adminSnapshot.groups.filter(g => g.courseId === (item.courseId || adminSnapshot.courses[0]?.id)) : type === "lesson" ? adminSnapshot.lessons.map(l => ({id:l.id,title:l.title})) : null;
            if (options) control = `<select name="${key}" required>${options.map(o => `<option value="${escape(o.id)}" ${String(value) === String(o.id) ? "selected" : ""}>${escape(type === "course" ? `${goals[o.goal]} · ${o.title}${o.published ? "" : " (Lưu trữ)"}` : o.title)}</option>`).join("")}</select>`;
            else if (type === "check") control = `<input type="checkbox" name="${key}" ${value ? "checked" : ""}>`;
            else if (typeof type === "number" && type >= 1000) control = `<textarea name="${key}" maxlength="${type}" rows="3" required>${escape(value)}</textarea>`;
            else if (["number","difficulty","optional-number"].includes(type)) control = `<input type="number" name="${key}" min="1" max="${type === "difficulty" ? 5 : type === "optional-number" ? 20 : 10000}" value="${escape(value)}" ${type === "optional-number" ? "" : "required"}>`;
            else control = `<input name="${key}" maxlength="${type === "id" ? 80 : type}" value="${escape(value)}" ${type === "id" ? 'pattern="[a-z0-9-]+"' : ""} ${key === "accent" ? "" : "required"}>`;
            return `<label>${escape(label)}${control}</label>`;
        }).join("");
        root.innerHTML = `<form class="library-admin-form" data-kind="${kind}" data-admin-dirty="library-${kind}">${fields}<p class="field-help">IELTS/TOEIC là độ khó tham khảo, không quy đổi CEFR thành điểm thi. Giọng và số người nói để trống nếu chưa xác minh. Công bố mức luyện chỉ sau khi các nhóm đủ nội dung.</p><button type="submit" class="primary-button compact">Lưu</button><p data-status role="status"></p></form>`;
        const form = root.querySelector("form"), ui = window.DictaLabAdmin;
        ui?.track(root);
        form.elements.courseId?.addEventListener("change", () => {
            if (!form.elements.groupId) return;
            form.elements.groupId.innerHTML = adminSnapshot.groups.filter(g => g.courseId === form.elements.courseId.value).map(g => `<option value="${g.id}">${escape(g.title)}</option>`).join("");
        });
        form.onsubmit = event => {
            event.preventDefault();
            const action = async () => {
                const body = {};
                for (const [key,,type] of adminFields[kind]) {
                    const field = form.elements[key];
                    body[key] = type === "check" ? field.checked : ["number","difficulty","lesson","optional-number"].includes(type) ? field.value ? Number(field.value) : null : field.value.trim();
                }
                const status = form.querySelector("[data-status]");
                try {
                    await bridge().api(`/api/admin/library/${kind}`, {method:"PUT", body});
                    ui?.clean(form); status.textContent = "Đã lưu. Các thay đổi xuất hiện trong thư viện.";
                    adminSnapshot = await bridge().api("/api/admin/library"); renderAdminList();
                } catch (error) { status.textContent = error.message; }
            };
            if (ui) ui.busy(form, action); else action();
        };
    }
    function renderAdminList() {
        const root = document.querySelector("#library-admin-list");
        const sections = [["courses","Mức luyện và khóa lưu trữ",adminSnapshot.courses],["groups","Nhóm bài",adminSnapshot.groups],["lessons","Liên kết bài gốc",adminSnapshot.memberships],["metadata","Metadata đã kiểm tra",adminSnapshot.metadata]];
        root.innerHTML = sections.map(([kind,label,items]) => `<details><summary>${label} (${items.length})</summary><button type="button" class="text-button" data-library-new="${kind}">+ Thêm / sửa ${label.toLowerCase()}</button>${items.map((item,index) => `<div class="library-admin-row"><span>${escape(item.title || adminSnapshot.lessons.find(l => l.id === item.lessonId)?.title || `Bài ${item.lessonId}`)}${item.courseId ? ` · ${escape(item.courseId)}` : ""}${item.groupId ? ` › ${escape(item.groupId)}` : ""}${item.position ? ` · #${item.position}` : ""}${kind === "metadata" ? ` · ${item.reviewed ? "Đã kiểm tra" : "Cần kiểm tra lại"}` : ""}</span><button type="button" class="text-button" data-library-edit="${kind}" data-index="${index}">Sửa</button>${kind === "lessons" ? `<button type="button" class="text-button" data-library-unlink="${index}">Bỏ liên kết</button>` : ""}</div>`).join("")}</details>`).join("");
        root.onclick = async event => {
            const button = event.target.closest("button"); if (!button || window.DictaLabAdmin?.canLeave() === false) return;
            if (button.dataset.libraryNew) adminForm(button.dataset.libraryNew);
            if (button.dataset.libraryEdit) {
                const kind = button.dataset.libraryEdit; adminForm(kind, sections.find(s => s[0] === kind)[2][Number(button.dataset.index)]);
            }
            if (button.dataset.libraryUnlink != null) {
                const item = adminSnapshot.memberships[Number(button.dataset.libraryUnlink)];
                if (!confirm("Bỏ bài khỏi khóa này? Bài gốc, lịch sử và kết quả học vẫn được giữ.")) return;
                button.disabled = true;
                try { await bridge().api(`/api/admin/library/courses/${encodeURIComponent(item.courseId)}/lessons/${item.lessonId}`, {method:"DELETE"}); adminSnapshot = await bridge().api("/api/admin/library"); renderAdminList(); }
                catch (error) { bridge().showToast(error.message,true); button.disabled = false; }
            }
        };
    }
    async function showAdmin() {
        const panel = document.querySelector("#library-management"), root = document.querySelector("#library-admin-content");
        if (!panel || !bridge().isAdmin()) return;
        if (query().get("library") === "1") panel.open = true;
        if (!panel.open) { panel.ontoggle = () => { if (panel.open) showAdmin(); }; return; }
        if (adminLoading) return;
        if (root.querySelector("[data-admin-dirty]") && window.DictaLabAdmin?.canLeave() === false) return;
        adminLoading = true; root.setAttribute("aria-busy","true");
        try {
            adminSnapshot = await bridge().api("/api/admin/library");
            root.innerHTML = '<p>Mỗi bài chỉ thuộc một mục: IELTS, TOEIC hoặc Giao tiếp. Bỏ liên kết ở mục cũ trước khi chuyển. Liên kết tới bài gốc giữ nguyên lịch sử học. Vị trí trong mỗi nhóm phải khác nhau.</p><div id="library-admin-list"></div><div id="library-admin-editor"></div>';
            renderAdminList();
        } catch (error) { root.innerHTML = `<p role="alert">${escape(error.message)}</p><button type="button" class="text-button">Thử lại</button>`; root.querySelector("button").onclick = showAdmin; }
        finally { adminLoading = false; root.setAttribute("aria-busy","false"); }
    }
    window.DictaLabLibrary = {show, showAdmin, decorateDetail, returnUrl: id => {
        const url = new URL(location.href); url.searchParams.set("lesson",id); url.searchParams.set("start","1"); return url.pathname + url.search;
    }};
})();
