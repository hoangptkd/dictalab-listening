(() => {
    "use strict";
    let revision = 0, timer = null, controller = null;
    const labels = {PENDING: "Đang chờ xử lý", RUNNING: "Model đang sinh bài", DONE: "Đã xử lý xong",
        FAILED: "Job không thành công", UNKNOWN: "Chưa xác định kết quả; không tự gọi lại", EXPIRED: "Đã hết thời gian"};
    const escape = value => String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#039;");

    function leave() {
        revision++;
        clearTimeout(timer);
        controller?.abort();
        document.querySelector("#model-demo-panel")?.setAttribute("hidden", "");
    }

    async function mount(panel, sessionId, onApplied) {
        if (!panel) return;
        leave();
        controller = new AbortController();
        const token = revision;
        const api = (path, options = {}) => window.DictaLabListeningBridge.api("/api/v1/model-demo" + path,
            {...options, signal: controller.signal});
        let data;
        try { data = await api(""); } catch (_) { return; }
        if (token !== revision || !data.enabled) return;
        panel.hidden = false;
        panel.innerHTML = `<details class="conversation-card ui-disclosure"><summary>Thử model base và fine-tune full07</summary>
            <p>Đây là model thử nghiệm. Kiểm tra tự động chưa đánh giá đầy đủ độ đúng nghĩa.</p>
            <form id="model-demo-form">
              <label for="model-demo-mode">Cách chạy</label>
              <select id="model-demo-mode"><option value="compare">So sánh base / full07 cùng đầu vào</option>
                ${sessionId ? '<option value="practice">Tạo câu tiếp theo cho phiên luyện này</option>' : ""}</select>
              <label for="model-demo-level">Mức bài</label>
              <select id="model-demo-level">${data.levels.map(level => `<option>${escape(level)}</option>`).join("")}</select>
              <label for="model-demo-scenario">Tình huống</label>
              <select id="model-demo-scenario">${Object.entries(data.scenarios).map(([id, label]) =>
                `<option value="${escape(id)}">${escape(label)}</option>`).join("")}</select>
              <fieldset id="model-demo-practice" hidden><legend>Model cho phiên luyện</legend>
                <label for="model-demo-variant">Chọn model</label>
                <select id="model-demo-variant"><option value="full07">Local · full07</option>
                  <option value="base">Local · base</option><option value="cloud">API cloud</option></select>
                <label><input type="checkbox" id="model-demo-fallback"> Cho phép chuyển cloud khi local lỗi</label>
                <p>Cloud chỉ chạy khi máy chủ đã cấu hình ngân sách. Kết quả ghi rõ model thực tế.</p>
              </fieldset>
              <p id="model-demo-help">Chạy nền trên CPU có thể lâu. Bạn có thể rời trang và mở lại kết quả.</p>
              <button type="submit" aria-describedby="model-demo-help">Tạo job</button>
            </form>
            <p id="model-demo-status" role="status" aria-live="polite"></p>
            <div id="model-demo-result"></div>
            <h3>Job gần đây</h3><ul id="model-demo-history"></ul>
        </details>`;
        const form = panel.querySelector("form"), status = panel.querySelector("#model-demo-status");
        const result = panel.querySelector("#model-demo-result"), history = panel.querySelector("#model-demo-history");
        let requestId = null, active = null;
        const jobLabel = job => `${job.mode === "compare" ? "So sánh" : "Bài luyện"} · ${new Date(job.createdAt).toLocaleString("vi-VN")} · ${labels[job.status] || job.status}`;
        function renderHistory(jobs) {
            history.replaceChildren();
            for (const job of jobs) {
                const li = document.createElement("li"), button = document.createElement("button");
                button.type = "button";
                button.dataset.jobId = job.id;
                button.textContent = jobLabel(job);
                button.addEventListener("click", () => show(job.id));
                li.append(button); history.append(li);
            }
        }
        function updateMode() {
            const practice = form.querySelector("#model-demo-mode").value === "practice";
            form.querySelector("fieldset").hidden = !practice;
            // A session supplies its own scenario/level; the browser cannot rewrite its context.
            form.querySelector("#model-demo-level").disabled = practice;
            form.querySelector("#model-demo-scenario").disabled = practice;
            const cloud = form.querySelector("#model-demo-variant").value === "cloud";
            const fallback = form.querySelector("#model-demo-fallback");
            fallback.disabled = cloud;
            if (cloud) fallback.checked = false;
            requestId = null;
        }
        form.addEventListener("change", updateMode);
        updateMode();
        async function show(id) {
            clearTimeout(timer);
            if (active !== id) result.replaceChildren();
            active = id;
            try {
                const job = await api("/jobs/" + encodeURIComponent(id));
                if (token !== revision || active !== id) return;
                for (const button of history.querySelectorAll("button")) {
                    if (button.dataset.jobId === id) button.textContent = jobLabel(job);
                }
                const message = labels[job.status] || job.status;
                if (status.textContent !== message) status.textContent = message;
                if (["PENDING", "RUNNING"].includes(job.status)) {
                    timer = setTimeout(() => show(id), 3000);
                    return;
                }
                result.replaceChildren();
                if (job.error) {
                    const error = document.createElement("p");
                    error.textContent = "Mã lỗi: " + job.error;
                    result.append(error);
                }
                if (!job.result) return;
                const provenance = document.createElement("p");
                provenance.textContent = `Prompt: ${job.result.promptVersion} · Base revision: ${job.result.baseRevision} · Adapter: ${job.result.adapterSha256}`;
                result.append(provenance);
                for (const arm of job.result.arms || []) {
                    const section = document.createElement("section"), heading = document.createElement("h4");
                    heading.textContent = `${arm.variant}${arm.fallbackFrom ? ` (chuyển từ ${arm.fallbackFrom})` : ""} · ${arm.valid ? "Đạt kiểm tra cấu trúc" : "Chưa đạt"}`;
                    section.append(heading);
                    if (Number.isInteger(arm.wordCount) && arm.wordBounds) {
                        const length = document.createElement("p");
                        length.textContent = `Độ dài: ${arm.wordCount} từ · Giới hạn: ${arm.wordBounds.minWords}–${arm.wordBounds.maxWords} từ`;
                        section.append(length);
                    }
                    if (arm.translationGuidance === "source-guided-r13") {
                        const guidance = document.createElement("p");
                        guidance.textContent = "Dịch có trợ giúp glossary R13 theo từng mệnh đề và ý; vẫn cần kiểm tra nghĩa.";
                        section.append(guidance);
                    }
                    for (const call of arm.calls || []) {
                        const source = document.createElement("p");
                        source.textContent = `${call.purpose}: ${call.provider} / ${call.model}${call.latencyMs != null ? ` · ${call.latencyMs} ms` : ""}`;
                        section.append(source);
                    }
                    if (arm.error || arm.fallbackError || arm.validationErrors?.length) {
                        const error = document.createElement("p");
                        error.textContent = [arm.error, arm.fallbackError, ...(arm.validationErrors || [])].filter(Boolean).join(" · ");
                        section.append(error);
                    }
                    if (job.mode === "compare") {
                        const details = document.createElement("details"), summary = document.createElement("summary"), pre = document.createElement("pre");
                        summary.textContent = "Xem đầu ra";
                        pre.textContent = typeof arm.raw === "string" ? arm.raw : JSON.stringify(arm.candidate || arm.task || {}, null, 2);
                        details.append(summary, pre); section.append(details);
                    }
                    result.append(section);
                }
                if (job.mode === "practice" && job.sessionId === sessionId && job.result.arms?.some(arm => arm.valid)) {
                    const reviewNotice = document.createElement("p");
                    reviewNotice.textContent = "Áp dụng bài sẽ gọi AI kiểm tra nghĩa và tạo audio theo cấu hình hiện tại; các lượt này dùng quota và ngân sách của ứng dụng.";
                    result.append(reviewNotice);
                    const accept = document.createElement("button");
                    accept.type = "button"; accept.textContent = "Kiểm tra và dùng bài này";
                    accept.addEventListener("click", async () => {
                        accept.disabled = true;
                        try {
                            await api("/jobs/" + encodeURIComponent(id) + "/accept", {method: "POST", timeoutMs: 100000});
                            if (token === revision) onApplied();
                        } catch (error) { status.textContent = error.message; accept.disabled = false; }
                    });
                    result.append(accept);
                }
            } catch (error) { if (token === revision) status.textContent = error.message; }
        }
        form.addEventListener("submit", async event => {
            event.preventDefault();
            const button = form.querySelector('button[type="submit"]');
            button.disabled = true;
            requestId ||= crypto.randomUUID();
            const mode = form.querySelector("#model-demo-mode").value;
            try {
                const job = await api("/jobs", {method: "POST", body: {requestId,
                    sessionId: mode === "practice" ? sessionId : null,
                    scenario: form.querySelector("#model-demo-scenario").value, level: form.querySelector("#model-demo-level").value,
                    mode, variant: mode === "compare" ? "full07" : form.querySelector("#model-demo-variant").value,
                    allowFallback: mode === "practice" && form.querySelector("#model-demo-fallback").checked}});
                if (token !== revision) return;
                requestId = null;
                const fresh = await api(""); renderHistory(fresh.jobs);
                result.replaceChildren(); show(job.id);
            } catch (error) { if (token === revision) status.textContent = error.message; }
            finally { if (token === revision) button.disabled = false; }
        });
        renderHistory(data.jobs);
        const pending = data.jobs.find(job => ["PENDING", "RUNNING"].includes(job.status));
        if (pending) show(pending.id);
    }
    window.DictaLabModelDemo = {mount, leave};
})();
