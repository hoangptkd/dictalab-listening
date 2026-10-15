(() => {
    "use strict";
    const root = document.querySelector("#coach-panel");
    if (!root) return;
    const resultParent = root.parentNode, resultNext = root.nextSibling;
    const coachStatus = root.querySelector("#coach-status") || document.querySelector("#coach-status");
    const statusParent = coachStatus.parentNode, statusNext = coachStatus.nextSibling;
    const bridge = window.DictaLabListeningBridge;
    const $ = selector => selector === "#coach-status" ? coachStatus : root.querySelector(selector);
    const escape = value => String(value ?? "").replace(/[&<>"']/g, char => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
    }[char]));
    const normalize = value => String(value).normalize("NFKC").trim().replaceAll("’", "'").toLowerCase();
    let generation = 0, sessionId = null, report = null, bank = [], drill = [], drillIndex = 0;
    let playing = false;
    const saved = new Set();

    function leave() {
        generation++;
        sessionId = null;
        report = null;
        bank = [];
        learningBank = [];
        drill = [];
        saved.clear();
        bridge.stopRecordedAudio();
        $("#coach-bank-items").innerHTML = "";
        $("#coach-practice").innerHTML = "";
    }

    async function show(result) {
        leave();
        if (resultParent && root.parentNode !== resultParent) resultParent.insertBefore(root, resultNext);
        if (statusParent && coachStatus.parentNode !== statusParent) statusParent.insertBefore(coachStatus, statusNext);
        $("#coach-insights").hidden = false;
        const smartReview = $(".smart-review");
        if (smartReview) smartReview.hidden = false;
        sessionId = result.sessionId;
        const current = generation;
        $("#coach-insights").innerHTML = "";
        $("#smart-review-items").innerHTML = "";
        $("#smart-review-count").textContent = "";
        $("#coach-practice").hidden = true;
        $("#coach-error-bank").open = false;
        $("#coach-bank-items").innerHTML = "";
        $("#coach-status").textContent = "Đang chọn điểm cần luyện…";
        try {
            const data = await bridge.api(`/api/v1/practice-sessions/${encodeURIComponent(sessionId)}/coach`);
            if (current !== generation) return;
            render(data);
            if (data.source === "QUEUED") await pollReport(current, sessionId);
            else if (data.aiEnabled && data.source === "RULES" && data.reviewItems.length) await generate(current);
        } catch (error) {
            if (current !== generation) return;
            $("#coach-status").textContent = "Kết quả đã lưu. Chưa tải được Coach; bạn vẫn có thể xem Phân tích chi tiết.";
            $("#coach-insights").innerHTML = '<button class="text-button" type="button" data-coach-reload>Tải lại Coach</button>';
        }
    }

    async function generate(current = generation) {
        const id = sessionId;
        $("#coach-status").textContent = "Đã chọn lỗi để ôn. AI đang đọc buổi luyện; bạn có thể bắt đầu ngay.";
        $("#coach-insights").setAttribute("aria-busy", "true");
        try {
            const data = await bridge.api(`/api/v1/practice-sessions/${encodeURIComponent(id)}/coach`, {method: "POST"});
            if (current !== generation) return;
            render(data);
            $("#coach-insights").removeAttribute("aria-busy");
            if (data.source === "QUEUED") await pollReport(current, id);
        } catch (error) {
            if (current !== generation) return;
            $("#coach-status").textContent = "AI chưa sẵn sàng. Các gợi ý hiện tại dựa trên lỗi đã ghi nhận.";
        } finally {
            if (current === generation) $("#coach-insights").removeAttribute("aria-busy");
        }
    }

    async function pollReport(current, id) {
        // Read-only polling never repeats a paid AI request. Leaving the session cancels updates.
        for (let attempt = 0; attempt < 90; attempt++) {
            await new Promise(resolve => setTimeout(resolve, 2000));
            if (current !== generation || id !== sessionId) return;
            let data;
            try {
                data = await bridge.api(`/api/v1/practice-sessions/${encodeURIComponent(id)}/coach`);
            } catch (error) {
                if (current === generation && id === sessionId) {
                    $("#coach-status").textContent = "Chưa cập nhật được báo cáo. Các gợi ý hiện tại vẫn dùng được; hãy tải lại Coach sau.";
                    $("#coach-insights").insertAdjacentHTML("beforeend", '<button class="text-button" type="button" data-coach-reload>Tải lại Coach</button>');
                }
                return;
            }
            if (current !== generation || id !== sessionId) return;
            if (data.source !== "QUEUED") { render(data); return; }
        }
        if (current === generation) {
            $("#coach-status").textContent = "Báo cáo vẫn đang chờ. Bạn có thể luyện ngay và tải lại Coach sau.";
            $("#coach-insights").insertAdjacentHTML("beforeend", '<button class="text-button" type="button" data-coach-reload>Tải lại Coach</button>');
        }
    }

    function evidenceText(ids) {
        const examples = ids.map(id => report.reviewItems.find(item => item.id === id)).filter(Boolean)
            .map(item => `“${item.heard || "chưa nhập"}” → “${item.expected}”`);
        return [...new Set(examples)].join(" · ");
    }

    function render(data) {
        report = data;
        $("#coach-status").textContent = data.source === "AI" ? "AI Coach · gợi ý từ buổi luyện của bạn"
            : data.source === "QUEUED" ? "AI đang chuẩn bị báo cáo. Bạn có thể luyện các lỗi đã chọn ngay."
            : data.source === "RETRY_REQUIRED" ? "Chưa xác nhận được báo cáo AI. Hệ thống không tự gọi lại; bạn có thể chủ động thử lại."
            : data.source === "EXHAUSTED" ? "Đã hết lượt thử AI cho buổi này. Bạn vẫn có thể luyện các lỗi đã ghi nhận."
            : data.source === "BUSY" ? "AI đang bận. Bạn có thể luyện các lỗi đã chọn và thử AI sau."
                : data.source === "UNAVAILABLE" ? "AI chưa sẵn sàng. Gợi ý dưới đây dựa trên lỗi đã ghi nhận."
                    : "Gợi ý từ lỗi đã ghi nhận trong buổi luyện.";
        const weaknesses = data.weaknesses.slice(0, 3);
        const actions = data.nextActions.slice(0, 3);
        $("#coach-insights").innerHTML = `
            <div class="coach-section-heading"><div><p class="section-index">Một chút tập trung</p>
                <h2>${weaknesses.length ? "Điểm đáng luyện hôm nay" : "Chưa có điểm yếu đủ bằng chứng"}</h2></div></div>
            <div class="coach-weaknesses">${weaknesses.map((weakness, index) => `
                <article class="coach-weakness"><span class="coach-number">0${index + 1}</span>
                    <h3>${escape(weakness.title)}</h3><p>${escape(weakness.insight)}</p>
                    <p class="coach-evidence">${escape(evidenceText(weakness.evidenceIds))}</p></article>`).join("")}</div>
            ${!weaknesses.length ? '<p>Chưa có lỗi đáng ưu tiên trong dữ liệu buổi này. Hãy thử một bài mới cùng cấp độ.</p>' : ""}
            <div class="coach-progress"><span>Tiến bộ của bạn</span><p>${escape(data.progressInsight.text)}</p></div>
            ${actions.length ? `<section class="coach-next" aria-labelledby="coach-next-heading">
                <div><p class="section-index">Bước tiếp theo</p><h3 id="coach-next-heading">${escape(actions[0].instruction)}</h3></div>
                <button type="button" class="primary-button" data-coach-practice="${escape(actions[0].pattern)}">Luyện điểm này <span aria-hidden="true">→</span></button>
                ${actions.length > 1 ? `<details><summary>Thêm ${actions.length - 1} cách luyện</summary><ul>${actions.slice(1).map(action => `
                    <li>${escape(action.instruction)} <button class="text-button" type="button" data-coach-practice="${escape(action.pattern)}">Bắt đầu</button></li>`).join("")}</ul></details>` : ""}
            </section>` : ""}
            ${["BUSY", "UNAVAILABLE", "RETRY_REQUIRED"].includes(data.source) ? '<button class="text-button" type="button" data-coach-retry>Thử AI lại</button>' : ""}`;
        $("#smart-review-count").textContent = data.reviewItems.length ? `${data.reviewItems.length} lỗi ưu tiên · không cần ôn lại tất cả` : "Không có lỗi cần ưu tiên";
        $("#smart-review-items").innerHTML = data.reviewItems.map(item => itemCard(item, sessionId)).join("");
    }

    function itemCard(item, sourceSession, inBank = false) {
        const key = `${sourceSession}/${item.id}`;
        return `<article class="smart-review-item" data-coach-item="${escape(item.id)}" data-coach-session="${escape(sourceSession)}">
            <div class="smart-review-label"><span>Câu ${item.position}</span><strong>${escape(item.label)}</strong>
                ${item.occurrences > 1 ? `<small>Đã lỡ từ này ${item.occurrences} lần trong các bài được xét</small>` : ""}</div>
            <div class="coach-answer-pair"><div><span>Bạn đã nghe / nhập</span><p>${escape(item.heard || "Chưa nhập đáp án")}</p></div>
                <div><span>Đáp án đúng</span><p lang="en">${escape(item.expected)}</p></div></div>
            <p class="smart-review-context" lang="en">${escape(item.transcript)}</p>
            <p class="smart-review-explanation">${escape(item.explanation)}</p>
            <div class="coach-item-actions">
                ${item.audioUrl ? '<button type="button" class="text-button" data-coach-listen>▶ Nghe lại</button>' : '<span>Chưa có audio để nghe lại</span>'}
                <button type="button" class="text-button" data-coach-drill>Luyện điểm yếu</button>
                ${inBank ? "" : `<button type="button" class="text-button" data-coach-save ${saved.has(key) ? "disabled" : ""}>${saved.has(key) ? "Đã thêm Error Bank" : "+ Thêm Error Bank"}</button>`}
            </div><p class="coach-item-status" role="status"></p></article>`;
    }

    async function listen(item, button) {
        if (playing || !item?.audioUrl) return;
        const current = generation;
        playing = true;
        button.disabled = true;
        const text = button.textContent;
        button.textContent = "Đang phát…";
        try {
            await bridge.playRecordedAudio(item.audioUrl, item.audioStartSeconds, item.audioEndSeconds);
            if (item.reviewId && current === generation) {
                await bridge.api(`/api/v1/listening-learning/reviews/${encodeURIComponent(item.reviewId)}/played`, {method: "POST"});
                item.played = true;
            }
        }
        catch (error) {
            if (current === generation && error.name !== "AbortError") bridge.showToast(error.message, true);
        } finally {
            playing = false;
            button.disabled = false;
            button.textContent = text;
        }
    }

    function startDrill(items) {
        drill = items.filter(item => item.audioUrl).slice(0, 3);
        drillIndex = 0;
        if (!drill.length) { bridge.showToast("Lỗi này chưa có audio để luyện.", true); return; }
        renderDrill();
    }

    function renderDrill() {
        bridge.stopRecordedAudio();
        const item = drill[drillIndex];
        const panel = $("#coach-practice");
        panel.hidden = false;
        const tokens = /[\p{L}\p{N}]+(?:['’\-][\p{L}\p{N}]+)*/gu;
        let index = 0;
        const masked = item.reviewId ? item.prompt : item.transcript.replace(tokens, word => index++ === item.wordIndex ? "_____" : word);
        panel.innerHTML = `<div class="coach-section-heading"><h2>Luyện ${drillIndex + 1}/${drill.length} · ${escape(item.label)}</h2>
            <button type="button" class="text-button" data-coach-close>Đóng bài luyện</button></div>
            <p>Nghe cả câu, rồi điền từ còn thiếu. Bài ôn này không thay đổi điểm đã lưu.</p>
            ${item.reviewId ? `<p>${item.newContext ? "Audio khác · kiểm tra nghe trong ngữ cảnh mới." : "Audio ôn lại · không dùng để kết luận khả năng nghe ngữ cảnh mới."}</p>` : ""}
            <p class="coach-drill-context" lang="en">${escape(masked)}</p>
            <button class="text-button" type="button" data-drill-listen>▶ Nghe câu</button>
            <form id="coach-drill-form"><label for="coach-drill-answer">Từ bạn nghe được</label>
                <div class="coach-drill-input"><input id="coach-drill-answer" autocomplete="off" spellcheck="false" required maxlength="200">
                    <button class="primary-button" type="submit">Kiểm tra</button></div></form>
            <p id="coach-drill-feedback" role="status"></p>
            <button class="text-button" type="button" data-drill-reveal>Xem đáp án</button>
            <button class="text-button" type="button" data-drill-next hidden>${drillIndex + 1 < drill.length ? "Câu tiếp theo →" : "Hoàn tất lượt ôn"}</button>`;
        panel.scrollIntoView({block: "center", behavior: "auto"});
        $("[data-drill-listen]").focus({preventScroll: true});
        if (item.result) renderReviewResult(item.result);
    }

    function pendingReview(value) {
        const user = bridge.currentUser?.()?.id;
        if (!user) return null;
        const key = `dictalab.review.${user}`;
        try {
            if (value === null) sessionStorage.removeItem(key);
            else if (value !== undefined) sessionStorage.setItem(key, JSON.stringify(value));
            return JSON.parse(sessionStorage.getItem(key) || "null");
        } catch (_) { return null; }
    }

    function renderReviewResult(result) {
        $("#coach-drill-feedback").textContent = `${result.correct ? "Đúng rồi." : "Đáp án: " + result.expected + "."} Lần ôn tiếp: ${new Date(result.dueAt).toLocaleString("vi-VN")}.`;
        $("#coach-drill-answer").disabled = true;
        $("[data-drill-next]").hidden = false;
    }

    async function submitReview(reveal = false) {
        const current = generation, item = drill[drillIndex];
        if (!item.played) { $("#coach-drill-feedback").textContent = "Hãy nghe hết audio trước khi nộp."; return; }
        if (item.submitting || item.result) return;
        item.submitting = true;
        // Preserve an uncertain submission, including reveal, until its committed outcome is recovered.
        item.pendingAnswer ||= {answer: reveal ? "" : $("#coach-drill-answer").value, reveal};
        pendingReview({id: item.reviewId, body: item.pendingAnswer});
        try {
            const result = await bridge.api(`/api/v1/listening-learning/reviews/${encodeURIComponent(item.reviewId)}/answer`,
                {method: "POST", body: item.pendingAnswer});
            if (current !== generation) return;
            item.result = result;
            pendingReview(null); renderReviewResult(result);
        } catch (error) {
            if (current === generation) $("#coach-drill-feedback").textContent = error.message + " Nộp lại để xác nhận kết quả; lịch ôn không bị ghi trùng.";
            if (error.status && error.status < 500) { item.pendingAnswer = null; pendingReview(null); }
        } finally { item.submitting = false; }
    }

    root.addEventListener("submit", async event => {
        if (event.target.id !== "coach-drill-form") return;
        event.preventDefault();
        if (drill[drillIndex].reviewId) { await submitReview(); return; }
        const input = $("#coach-drill-answer");
        const correct = normalize(input.value) === normalize(drill[drillIndex].expected);
        input.setAttribute("aria-invalid", String(!correct));
        input.setAttribute("aria-describedby", "coach-drill-feedback");
        $("#coach-drill-feedback").textContent = correct ? "Đúng rồi. Đọc theo cả cụm một lần nữa nhé." : "Chưa khớp. Nghe lại và chú ý từ còn thiếu.";
        $("[data-drill-next]").hidden = !correct;
    });

    root.addEventListener("click", async event => {
        const button = event.target.closest("button");
        if (!button || button.disabled) return;
        if (button.hasAttribute("data-spaced-review")) {
            button.disabled = true;
            const current = generation;
            try {
                const pending = pendingReview();
                if (pending) {
                    const state = await bridge.api(`/api/v1/listening-learning/reviews/${encodeURIComponent(pending.id)}`);
                    if (current !== generation) return;
                    drill = [{...state.challenge, reviewId: pending.id, label: "Khôi phục lượt ôn", result: state.result, pendingAnswer: pending.body}];
                    drillIndex = 0; renderDrill();
                    if (state.result) pendingReview(null);
                    return;
                }
                const items = await bridge.api("/api/v1/listening-learning/reviews", {method: "POST"});
                if (current !== generation) return;
                if (!items.length) { bridge.showToast("Chưa có câu ôn đến hạn có audio phù hợp."); return; }
                drill = items.map(item => ({...item, reviewId: item.id, label: "Ôn nghe cách quãng"}));
                drillIndex = 0; renderDrill();
            } catch (error) {
                if (current === generation) {
                    if (error.status === 404) pendingReview(null);
                    bridge.showToast(error.message, true);
                }
            }
            finally { button.disabled = false; }
            return;
        }
        if (button.hasAttribute("data-learning-listen")) {
            const item = learningBank.find(item => String(item.id) === button.dataset.learningListen);
            if (item) await listen({audioUrl:item.audio_url,audioStartSeconds:item.audio_start_seconds,audioEndSeconds:item.audio_end_seconds},button);
            return;
        }
        if (button.hasAttribute("data-coach-reload")) { if (sessionId) await show({sessionId}); return; }
        if (button.hasAttribute("data-coach-retry")) {
            button.disabled = true;
            try { await generate(); } finally { button.disabled = false; }
            return;
        }
        if (button.hasAttribute("data-coach-practice")) {
            startDrill(report.reviewItems.filter(item => item.pattern === button.dataset.coachPractice)); return;
        }
        if (button.hasAttribute("data-coach-close")) {
            bridge.stopRecordedAudio(); $("#coach-practice").hidden = true;
            $("[data-coach-practice]")?.focus(); return;
        }
        if (button.hasAttribute("data-drill-listen")) { await listen(drill[drillIndex], button); return; }
        if (button.hasAttribute("data-drill-reveal")) {
            if (drill[drillIndex].reviewId) { await submitReview(true); return; }
            $("#coach-drill-feedback").textContent = `Đáp án: ${drill[drillIndex].expected}. Nghe lại rồi đọc theo cả câu.`;
            $("[data-drill-next]").hidden = false; return;
        }
        if (button.hasAttribute("data-drill-next")) {
            if (++drillIndex < drill.length) renderDrill();
            else {
                $("#coach-practice").innerHTML = '<h2 tabindex="-1">Đã hoàn tất lượt ôn.</h2><p>Giữ nhịp này và thử một bài mới khi sẵn sàng.</p>';
                $("#coach-practice h2").focus();
            }
            return;
        }
        const card = button.closest("[data-coach-item]");
        if (!card) return;
        const sourceSession = card.dataset.coachSession;
        const item = sourceSession === sessionId ? report.reviewItems.find(i => i.id === card.dataset.coachItem)
            || bank.find(entry => entry.sessionId === sourceSession && entry.item.id === card.dataset.coachItem)?.item
            : bank.find(entry => entry.sessionId === sourceSession && entry.item.id === card.dataset.coachItem)?.item;
        if (!item) return;
        if (button.hasAttribute("data-coach-listen")) { await listen(item, button); return; }
        if (button.hasAttribute("data-coach-drill")) { startDrill([item]); return; }
        if (button.hasAttribute("data-coach-save")) {
            button.disabled = true;
            const current = generation;
            try {
                await bridge.api("/api/v1/error-bank", {method: "POST", body: {sessionId: sourceSession, itemId: item.id}});
                if (current !== generation) return;
                saved.add(`${sourceSession}/${item.id}`);
                button.textContent = "Đã thêm Error Bank";
                card.querySelector(".coach-item-status").textContent = "Đã lưu để ôn ở những buổi sau.";
                refreshSavedButtons();
                if ($("#coach-error-bank").open) await loadBank();
            } catch (error) {
                if (current !== generation) return;
                button.disabled = false;
                card.querySelector(".coach-item-status").textContent = "Chưa lưu được. Hãy thử lại.";
            }
        }
    });

    let learningBank = [];
    async function openBank(target) {
        leave();
        target.appendChild(root);
        root.prepend(coachStatus);
        $("#coach-status").textContent = "Kho lỗi nghe cá nhân · Ôn từ đến hạn";
        $("#coach-insights").hidden = true;
        $(".smart-review").hidden = true;
        $("#coach-practice").hidden = true;
        $("#coach-error-bank").open = true;
        await loadBank();
    }
    async function loadBank() {
        const current = generation;
        $("#coach-bank-items").textContent = "Đang tải lỗi đã lưu…";
        try {
            const data = await bridge.api("/api/v1/error-bank");
            if (current !== generation) return;
            bank = data;
            data.forEach(entry => saved.add(`${entry.sessionId}/${entry.item.id}`));
            refreshSavedButtons();
            $("#coach-bank-items").innerHTML = data.length ? data.map(entry => itemCard(entry.item, entry.sessionId, true)).join("")
                : "<p>Chưa có lỗi đã lưu. Chọn “Thêm Error Bank” ở một lỗi bạn muốn ôn lại.</p>";
        } catch (error) {
            if (current === generation) $("#coach-bank-items").textContent = "Chưa tải được Error Bank. Đóng rồi mở lại để thử.";
        }
        if (current === generation) await loadAutomaticBank(current);
    }
    async function loadAutomaticBank(current) {
        try {
            const data = await bridge.api("/api/v1/listening-learning");
            if (current !== generation) return;
            learningBank = data.items;
            const observations = {NO_ANSWER: "Chưa nhập trước khi xem đáp án · chưa rõ nguyên nhân",
                SPELLING: "Khác biệt chữ viết · chưa đủ dữ liệu kết luận lỗi chính tả",
                SUBSTITUTION: "Nhập từ khác", WORD_FORM: "Khác dạng từ", WORD_ORDER: "Từ ở vị trí khác", OMISSION: "Đáp án trống"};
            const content = `<h3>Kho lỗi tự động</h3><p>${data.due} từ đến hạn ôn.${data.pending ? ` ${data.pending} câu đang chờ tổng hợp; bài làm đã lưu.` : ""}</p>
                ${data.failed ? `<p role="status">${data.failed} câu chưa tổng hợp được. Kết quả bài làm vẫn được giữ; hệ thống cần phục hồi tác vụ phụ.</p>` : ""}
                <button type="button" class="primary-button" data-spaced-review ${data.due || pendingReview() ? "" : "disabled"}>${pendingReview() ? "Khôi phục lượt ôn vừa gửi" : "Ôn nghe hôm nay"}</button>
                ${data.targets.slice(0, 10).map(target => `<p><strong lang="en">${escape(target.target)}</strong> · ${target.errors} lần cần ôn; ${target.comparable_opportunities} cơ hội nghe đầu độc lập đã ghi nhận.</p>`).join("")}
                ${(data.progress || []).filter(card => card.completed_reviews > 0).slice(0,10).map(card => `<p><strong lang="en">${escape(card.target)}</strong> · ${card.completed_reviews} lượt ôn đã lưu; mức lịch ôn ${card.stage}/4. Lần tiếp: ${new Date(card.due_at).toLocaleString("vi-VN")}. Đây là lịch ôn, không phải chứng nhận năng lực.</p>`).join("")}
                ${data.items.length ? data.items.map(item => `<article class="smart-review-item">
                    <p>${escape(observations[item.observation] || "Khác đáp án")}${item.possible_pattern ? " · Cấu trúc có thể cần chú ý, chưa xác nhận nguyên nhân nghe sai" : ""}</p>
                    ${item.learner_reported_error_type ? `<p>Bạn tự đánh giá: ${item.learner_reported_error_type === "UNKNOWN" ? "chưa biết từ" : "biết nhưng nghe lỡ"} · không phải kết luận tự động.</p>` : ""}
                    <div class="coach-answer-pair"><div><span>Lần nhập đầu</span><p lang="en">${escape(item.heard || "Chưa nhập")}</p></div><div><span>Đáp án</span><p lang="en">${escape(item.expected)}</p></div></div>
                    <p lang="en">${escape(item.transcript)}</p>${item.audio_url ? `<button type="button" class="text-button" data-learning-listen="${escape(item.id)}">▶ Nghe câu gốc</button>` : "<p>Chưa có audio.</p>"}</article>`).join("") : "<p>Chưa có lỗi tự động. Hoàn thành một câu luyện để ghi nhận.</p>"}`;
            $("#coach-bank-items").insertAdjacentHTML("afterbegin", content);
        } catch (error) {
            if (current === generation && error.status !== 503)
                $("#coach-bank-items").insertAdjacentHTML("afterbegin", "<p role=\"status\">Chưa tải được kho tự động. Đóng rồi mở lại để thử; lỗi lưu thủ công vẫn dùng được.</p>");
        }
    }
    function refreshSavedButtons() {
        root.querySelectorAll("[data-coach-save]").forEach(button => {
            const card = button.closest("[data-coach-item]");
            if (saved.has(`${card.dataset.coachSession}/${card.dataset.coachItem}`)) {
                button.disabled = true;
                button.textContent = "Đã thêm Error Bank";
            }
        });
    }
    $("#coach-error-bank").addEventListener("toggle", () => { if ($("#coach-error-bank").open && sessionId) loadBank(); });
    window.DictaLabCoach = {show, leave, openBank};
})();
