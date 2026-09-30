(() => {
    "use strict";
    const root = document.querySelector("#coach-panel");
    if (!root) return;
    const bridge = window.DictaLabListeningBridge;
    const $ = selector => root.querySelector(selector);
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
        drill = [];
        saved.clear();
        bridge.stopRecordedAudio();
    }

    async function show(result) {
        leave();
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
            if (data.aiEnabled && data.source !== "AI" && data.reviewItems.length) await generate(current);
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
        } catch (error) {
            if (current !== generation) return;
            $("#coach-status").textContent = "AI chưa sẵn sàng. Các gợi ý hiện tại dựa trên lỗi đã ghi nhận.";
        } finally {
            if (current === generation) $("#coach-insights").removeAttribute("aria-busy");
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
            ${["BUSY", "UNAVAILABLE"].includes(data.source) ? '<button class="text-button" type="button" data-coach-retry>Thử AI lại</button>' : ""}`;
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
        try { await bridge.playRecordedAudio(item.audioUrl, item.audioStartSeconds, item.audioEndSeconds); }
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
        const masked = item.transcript.replace(tokens, word => index++ === item.wordIndex ? "_____" : word);
        panel.innerHTML = `<div class="coach-section-heading"><h2>Luyện ${drillIndex + 1}/${drill.length} · ${escape(item.label)}</h2>
            <button type="button" class="text-button" data-coach-close>Đóng bài luyện</button></div>
            <p>Nghe cả câu, rồi điền từ còn thiếu. Bài ôn này không thay đổi điểm đã lưu.</p>
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
    }

    root.addEventListener("submit", event => {
        if (event.target.id !== "coach-drill-form") return;
        event.preventDefault();
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
    window.DictaLabCoach = {show, leave};
})();
