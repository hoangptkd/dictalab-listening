(() => {
    "use strict";
    const ui = () => window.DictaLabAdmin;
    const api = (path = "", options = {}) => window.DictaLabListeningBridge.api(`/api/admin/gemini${path}`, {cache: "no-store", timeoutMs: 25000, ...options});
    let generation = 0, snapshot, period = "day", source = "", before = "";
    const number = value => value == null ? "Không có dữ liệu" : Number(value).toLocaleString("vi-VN");
    const esc = value => ui().esc(value);
    const auditNames = {name: "Tên nguồn", projectId: "Project", model: "Model", rpmLimit: "RPM tối đa", operatingRpm: "RPM vận hành",
        tpmLimit: "Token/phút", rpdLimit: "Lượt/ngày", priority: "Thứ tự", enabled: "Bật/tắt", apiKey: "API key",
        defaultModel: "Model mặc định", globalRpmLimit: "RPM chung", globalRpdLimit: "Lượt/ngày chung", httpStatus: "Mã HTTP", modelAvailable: "Model khả dụng",
        sourceIds: "Các nguồn", platforms: "Nền tảng"};
    const auditValue = (key, value) => value == null ? "—" : typeof value === "boolean"
        ? (key === "enabled" ? (value ? "Bật" : "Tắt") : (value ? "Có" : "Không"))
        : Array.isArray(value) ? value.join(", ") : String(value);
    const fields = {rpmLimit: ["RPM tối đa", "rpm_limit", 15], operatingRpm: ["RPM vận hành (0: dùng RPM tối đa)", "operating_rpm", 13],
        tpmLimit: ["Token/phút (0: không đặt trần)", "tpm_limit", 0], rpdLimit: ["Lượt/ngày (0: không đặt trần)", "rpd_limit", 0], priority: ["Thứ tự", "priority", 0]};

    async function show() {
        const root = document.getElementById("admin-gemini-content"), request = ++generation;
        root.setAttribute("aria-busy", "true");
        root.innerHTML = '<p role="status">Đang tải nguồn Gemini…</p>';
        const query = new URLSearchParams({period});
        if (source) query.set("source", source);
        if (before) query.set("before", before);
        try {
            const data = await api(`?${query}`);
            if (request !== generation || document.body.dataset.route !== "admin-gemini") return;
            snapshot = data;
            render(root);
            return true;
        } catch (error) { if (request === generation) ui().failure(root, error.message, show); return false; }
        finally { if (request === generation) root.setAttribute("aria-busy", "false"); }
    }

    function render(root) {
        const data = snapshot;
        const sourceNames = new Map(data.sources.map(item => [item.id, item.name]));
        for (const id of [...data.usage.map(row => row.source_id), ...data.audit.map(row => row.source_id), source]) {
            if (id && !sourceNames.has(id)) sourceNames.set(id, `Đã xóa: ${id}`);
        }
        root.innerHTML = `<div class="gemini-toolbar"><p>Luân phiên các nguồn cùng model. Gặp 429 sẽ thử nguồn khác trong thời hạn hiện có.</p>
            <button type="button" class="primary-button compact" data-add ${data.pool ? "" : "disabled"}>Thêm nguồn</button>
            <button type="button" class="text-button" data-refresh>Làm mới</button></div>
            <p id="gemini-status" role="status" tabindex="-1"></p>
            <p>${data.limitsEnabled ? "Đang áp dụng giới hạn vận hành nội bộ." : "Giới hạn vận hành nội bộ đang tắt; quota thực tế của Google vẫn áp dụng."}
            ${data.pool ? `Model mặc định: ${esc(data.pool.default_model)}. Nhóm nguồn ${data.pool.enabled ? "đang bật" : "đang tắt"}.` : "Chưa có nhóm Gemini. Tạo nhóm bên dưới trước khi thêm nguồn."}</p>
            ${data.pool?.enabled && !data.sources.some(item => item.enabled && item.model === data.pool.default_model) ? '<p role="alert">Model mặc định chưa có nguồn đang bật. Yêu cầu dùng model này sẽ chưa thực hiện được.</p>' : ""}
            ${!data.quotaAvailable ? '<p role="alert">Chưa đọc được trạng thái quota. Bạn vẫn có thể xem cấu hình đã lưu.</p>' : ""}
            <div class="admin-panel"><h2>Các nguồn</h2>${data.sources.length ? `<div class="gemini-table-scroll" tabindex="0" role="region" aria-label="Danh sách nguồn Gemini"><table class="gemini-table">
                <thead><tr><th>Nguồn / project</th><th>Model / API key</th><th>Trạng thái</th><th>Sử dụng hiện tại</th><th>Thao tác</th></tr></thead>
                <tbody>${data.sources.map(item => `<tr><td><strong>${esc(item.name)}</strong><br>${esc(item.account_id)}<br><small>${esc(item.id)}</small></td>
                    <td>${esc(item.model)}<br><span aria-label="API key được che">${esc(item.key_mask)}</span></td>
                    <td>${item.enabled ? "Đã bật" : "Đã tắt"}${item.quota?.cooldown_ms > 0 ? `<br>Tạm nghỉ ${Math.ceil(item.quota.cooldown_ms / 1000)} giây` : ""}
                    ${item.last_error ? `<br><small>Lỗi gần nhất: ${item.last_error === "429" ? "429" : "Gọi API thất bại"}<br>${esc(ui().stamp(item.last_error_at))}</small>` : ""}</td>
                    <td>${item.quota ? `${number(item.quota.rpm_used)} lượt/phút<br>${number(item.quota.rpd_used)} lượt/ngày<br>${number(item.quota.tpm_reserved)} token/phút dự trù` : "Chưa có trạng thái"}<br><small>Giới hạn RPM: ${number(item.operating_rpm || item.rpm_limit)}<br>TPM: ${item.tpm_limit ? number(item.tpm_limit) : "Không đặt trần"}<br>Lượt/ngày: ${item.rpd_limit ? number(item.rpd_limit) : "Không đặt trần"}</small></td>
                    <td><button type="button" class="text-button" data-edit="${esc(item.id)}">Sửa nguồn</button>
                    <button type="button" class="text-button" data-probe="${esc(item.id)}">Kiểm tra kết nối</button>
                    <button type="button" class="text-button" data-delete="${esc(item.id)}" ${item.enabled ? 'disabled aria-describedby="gemini-delete-help"' : ""}>Xóa nguồn</button></td></tr>`).join("")}</tbody></table></div>
                    ${data.sources.some(item => item.enabled) ? '<p id="gemini-delete-help">Để xóa nguồn đang bật: chọn Sửa nguồn, bỏ chọn Bật nguồn rồi lưu.</p>' : ""}`
                : '<p class="admin-empty">Chưa có nguồn Gemini. Thêm nguồn đầu tiên để cấu hình API key.</p>'}</div>
            <section class="admin-panel" id="gemini-editor" aria-label="Chỉnh sửa nguồn" hidden></section>
            <details class="admin-panel" ${data.pool ? "" : "open"}><summary>${data.pool ? "Model mặc định, giới hạn chung và bật/tắt nhóm Gemini" : "Tạo nhóm Gemini"}</summary><form data-pool data-admin-dirty="gemini-pool">
                <label>Model mặc định<input name="defaultModel" required pattern="[a-zA-Z0-9_.\\-]{1,100}" maxlength="100" value="${esc(data.pool?.default_model || "")}"></label>
                <p>Tác vụ yêu cầu model cụ thể vẫn chỉ dùng nguồn đúng model đó. Đổi mặc định không tự đổi model của các tác vụ này.</p>
                <label>RPM chung (0: không đặt trần)<input type="number" name="globalRpmLimit" min="0" max="2147483647" step="1" required value="${data.pool?.global_rpm_limit ?? 0}"></label>
                <label>Lượt/ngày chung (0: không đặt trần)<input type="number" name="globalRpdLimit" min="0" max="2147483647" step="1" required value="${data.pool?.global_rpd_limit ?? 0}"></label>
                <label><input type="checkbox" name="enabled" ${data.pool?.enabled ? "checked" : ""}> Bật nhóm Gemini</label>
                <button class="text-button" type="submit">${data.pool ? "Lưu cấu hình chung" : "Tạo nhóm"}</button><button class="text-button" type="button" data-reset-pool>Hủy thay đổi</button><p data-error role="alert"></p></form></details>
            <section class="admin-panel"><h2>Thống kê sử dụng</h2>
                <form data-filters class="admin-toolbar"><label>Nguồn<select name="source"><option value="">Tất cả nguồn</option>${[...sourceNames].map(([id, name]) => `<option value="${esc(id)}" ${source === id ? "selected" : ""}>${esc(name)}</option>`).join("")}</select></label>
                <label>Gộp theo<select name="period"><option value="day" ${period === "day" ? "selected" : ""}>Ngày</option><option value="month" ${period === "month" ? "selected" : ""}>Tháng</option></select></label>
                <button class="text-button" type="submit">Áp dụng</button></form>
                <p>90 ngày gần nhất · Múi giờ ${esc(data.timezone)}. Token do API trả về; quota token dự trù được theo dõi riêng.</p>
                ${!data.usageEnabled ? '<p role="alert">Chưa bật ghi thống kê sử dụng trong dịch vụ AI.</p>' : ""}
                ${usageHtml(data.usage)}</section>
            <section class="admin-panel"><h2>Lịch sử thay đổi</h2>${data.audit.length ? `<ol class="admin-timeline">${data.audit.map(event => `<li><strong>${esc({created: "Thêm nguồn", updated: "Sửa nguồn", deleted: "Xóa nguồn", probe: "Kiểm tra kết nối", pool_created: "Tạo nhóm Gemini", pool_updated: "Sửa cấu hình chung", operator_updated: "Cập nhật từ CLI"}[event.action] || event.action)}</strong>
                <time>${esc(ui().stamp(event.created_at))}</time><p>${event.actor_id === 0 ? "Người vận hành CLI" : `Admin #${esc(event.actor_id)}`} · ${esc(event.source_id || "Nhóm Gemini")}</p>
                <dl>${Object.entries(event.changes).map(([key, value]) => `<dt>${esc(auditNames[key] || key)}</dt><dd>${key === "apiKey" ? (value === "added" ? "Đã thêm API key" : "Đã thay API key") : esc(typeof value === "object" && value !== null && "before" in value && "after" in value ? `${auditValue(key, value.before)} → ${auditValue(key, value.after)}` : auditValue(key, value))}</dd>`).join("")}</dl></li>`).join("")}</ol>` : '<p class="admin-empty">Chưa có thay đổi được ghi nhận.</p>'}
                ${before ? '<button type="button" class="text-button" data-latest>Lịch sử mới nhất</button>' : ""}
                ${data.nextAudit ? '<button type="button" class="text-button" data-older>Xem thay đổi cũ hơn</button>' : ""}</section>`;
        root.querySelector("[data-add]").onclick = () => edit(null);
        ui().track(root);
        const poolForm = root.querySelector("[data-pool]");
        root.querySelector("[data-reset-pool]")?.addEventListener("click", () => { poolForm.reset(); ui().clean(poolForm); });
        if (poolForm) poolForm.onsubmit = async event => {
            event.preventDefault();
            if (root.querySelector("#gemini-editor form") && ui().dirty(root.querySelector("#gemini-editor form"))) {
                status("Lưu hoặc hủy thay đổi của nguồn trước khi sửa cấu hình chung."); return;
            }
            const values = new FormData(poolForm);
            await ui().busy(root, async () => {
                try { await api("/pool/config", {method: "PUT", body: {revision: snapshot.revision, enabled: values.has("enabled"),
                    defaultModel: values.get("defaultModel").trim(),
                    globalRpmLimit: Number(values.get("globalRpmLimit")), globalRpdLimit: Number(values.get("globalRpdLimit"))}});
                    ui().clean(poolForm); await saved("Đã lưu cấu hình chung.");
                } catch (error) { poolForm.querySelector("[data-error]").textContent = error.message; }
            });
        };
        root.querySelector("[data-refresh]").onclick = () => { if (ui().canLeave()) show(); };
        root.querySelectorAll("[data-edit]").forEach(button => button.onclick = () => edit(data.sources.find(item => item.id === button.dataset.edit)));
        root.querySelectorAll("[data-delete]").forEach(button => button.onclick = async () => {
            const item = data.sources.find(item => item.id === button.dataset.delete);
            if (!confirm(`Xóa nguồn “${item.name}” và API key đã lưu? Không thể khôi phục key. Thống kê và lịch sử vẫn được giữ.`)) return;
            if (!ui().canLeave()) return;
            await ui().busy(root, async () => {
                try { await api(`/${encodeURIComponent(item.id)}?revision=${snapshot.revision}`, {method: "DELETE"});
                    await saved("Đã xóa nguồn. Thống kê và lịch sử vẫn được giữ.");
                } catch (error) { status(error.message); }
            });
        });
        root.querySelectorAll("[data-probe]").forEach(button => button.onclick = async () => {
            button.disabled = true;
            try { const result = await api(`/${button.dataset.probe}/probe`, {method: "POST"});
                status(result.modelAvailable ? "Kết nối thành công, model khả dụng." : `HTTP ${result.httpStatus}: chưa xác nhận model khả dụng.`);
            } catch (error) { status(error.message); } finally { button.disabled = false; }
        });
        root.querySelector("[data-filters]").onsubmit = event => {
            event.preventDefault(); if (!ui().canLeave()) return;
            const values = new FormData(event.currentTarget); source = values.get("source"); period = values.get("period"); before = ""; show();
        };
        root.querySelector("[data-older]")?.addEventListener("click", () => { if (ui().canLeave()) { before = data.nextAudit; show(); } });
        root.querySelector("[data-latest]")?.addEventListener("click", () => { if (ui().canLeave()) { before = ""; show(); } });
    }

    function usageHtml(rows) {
        if (!rows.length) return '<p class="admin-empty">Chưa có dữ liệu sử dụng trong kỳ này.</p>';
        const buckets = new Map();
        for (const row of rows) { const key = row.bucket.slice(0, 10); const value = buckets.get(key) || {attempts: 0, errors: 0}; value.attempts += Number(row.attempts); value.errors += Number(row.errors); buckets.set(key, value); }
        const max = Math.max(1, ...[...buckets.values()].map(row => row.attempts));
        return `<div class="gemini-chart" role="img" aria-label="Biểu đồ số lần gọi theo kỳ; số liệu chi tiết trong bảng bên dưới">${[...buckets].map(([date, row]) => `<div class="gemini-chart-row"><span>${esc(date)}</span><meter min="0" max="${max}" value="${row.attempts}">${row.attempts}</meter><span>${number(row.attempts)} lượt · ${number(row.errors)} lỗi</span></div>`).join("")}</div>
            <div class="gemini-table-scroll" tabindex="0" role="region" aria-label="Số liệu sử dụng Gemini"><table class="gemini-table"><thead><tr><th>Kỳ / nguồn</th><th>Yêu cầu kết thúc</th><th>Lần gọi</th><th>Thành công / lỗi / 429</th><th>Token vào / ra</th><th>Có số liệu token</th></tr></thead><tbody>${rows.map(row => `<tr><td>${esc(row.bucket.slice(0, 10))}<br>${esc(row.source_id)}<br><small>${esc(row.model)}</small></td><td>${number(row.requests)}</td><td>${number(row.attempts)}</td><td>${number(row.successes)} / ${number(row.errors)} / ${number(row.rate_limited)}</td><td>${number(row.input_tokens)} / ${number(row.output_tokens)}</td><td>${number(row.measured_attempts)} / ${number(row.attempts)} lần gọi</td></tr>`).join("")}</tbody></table></div>`;
    }

    async function saved(message) {
        const refreshed = await show();
        status(refreshed ? message : `${message} Chưa tải lại được danh sách; bấm Thử lại để kiểm tra cấu hình đã lưu.`);
    }

    function status(message) {
        if (document.body.dataset.route !== "admin-gemini") return;
        let node = document.getElementById("gemini-status");
        if (!node) { node = document.createElement("p"); node.id = "gemini-status"; node.setAttribute("role", "status"); node.tabIndex = -1;
            document.getElementById("admin-gemini-content").prepend(node); }
        node.textContent = message; node.focus({preventScroll: true});
    }

    function edit(item) {
        const poolForm = document.querySelector("[data-pool]");
        if (poolForm && ui().dirty(poolForm)) { status("Lưu hoặc hủy cấu hình chung trước khi sửa nguồn."); return; }
        if (!ui().canLeave()) return;
        const root = document.getElementById("gemini-editor"); root.hidden = false;
        root.innerHTML = `<h2>${item ? "Sửa nguồn" : "Thêm nguồn"}</h2><form data-admin-dirty="gemini-source" autocomplete="off">
            <div class="gemini-form-grid"><label>Mã nguồn<input name="id" required pattern="[a-zA-Z0-9_\\-]{1,64}" maxlength="64" value="${esc(item?.id || "")}" ${item ? "readonly" : ""}></label>
            <label>Tên hiển thị<input name="name" required maxlength="120" value="${esc(item?.name || "")}"></label>
            <label>Project ID<input name="projectId" required pattern="[a-zA-Z0-9_\\-]{1,100}" maxlength="100" value="${esc(item?.account_id || "")}"></label>
            <label>Model<input name="model" required maxlength="100" pattern="[a-zA-Z0-9_.\\-]{1,100}" value="${esc(item?.model || snapshot.pool?.default_model || "")}"></label>
            <label>API key${item ? " mới (để trống để giữ key hiện tại)" : ""}<input name="apiKey" type="password" autocomplete="new-password" maxlength="512" ${item ? "" : "required"}></label>
            ${Object.entries(fields).map(([name, [label, column, initial]]) => `<label>${label}<input name="${name}" type="number" min="${name === "rpmLimit" ? 1 : 0}" max="2147483647" step="1" required value="${esc(item?.[column] ?? initial)}"></label>`).join("")}</div>
            <label><input type="checkbox" name="enabled" ${item?.enabled ? "checked" : ""}> Bật nguồn</label>
            <p>Thay đổi áp dụng cho yêu cầu mới. Yêu cầu đang chạy tiếp tục dùng cấu hình đã nhận.</p>
            <button class="primary-button compact" type="submit">Lưu nguồn</button> <button class="text-button" type="button" data-cancel>Hủy</button>
            <p data-error role="alert"></p></form>`;
        const form = root.querySelector("form"); ui().track(root);
        root.querySelector("[data-cancel]").onclick = () => { if (ui().canLeave()) { root.hidden = true; root.replaceChildren(); } };
        form.onsubmit = async event => {
            if (poolForm && ui().dirty(poolForm)) { event.preventDefault(); status("Lưu hoặc hủy cấu hình chung trước khi lưu nguồn."); return; }
            event.preventDefault(); const values = new FormData(form), body = {revision: snapshot.revision};
            for (const name of ["name", "projectId", "model"]) body[name] = values.get(name).trim();
            for (const name of Object.keys(fields)) body[name] = Number(values.get(name));
            if (!body.name) { try { ui().fieldError(form, "name", "Nhập tên nguồn."); } catch (_) {} return; }
            if (body.operatingRpm > body.rpmLimit) { try { ui().fieldError(form, "operatingRpm", "RPM vận hành không được vượt RPM tối đa."); } catch (_) {} return; }
            body.enabled = values.has("enabled");
            if (values.get("apiKey")) body.apiKey = values.get("apiKey").trim();
            const id = values.get("id").trim();
            await ui().busy(document.getElementById("admin-gemini-content"), async () => {
                try { await api(`/${encodeURIComponent(id)}`, {method: item ? "PUT" : "POST", body});
                    form.elements.apiKey.value = ""; ui().clean(form); await saved("Đã lưu. Yêu cầu mới sẽ dùng cấu hình vừa cập nhật.");
                } catch (error) { body.apiKey = undefined; form.elements.apiKey.value = ""; form.querySelector("[data-error]").textContent = error.message; }
            });
        };
        root.scrollIntoView({block: "start", behavior: "auto"}); form.elements[item ? "name" : "id"].focus({preventScroll: true});
    }
    window.DictaLabGemini = {show};
})();
