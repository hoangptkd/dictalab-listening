(() => {
    "use strict";
    const originals = new WeakMap();
    const esc = value => String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");
    const values = form => [...form.querySelectorAll("input,select,textarea")].filter(node => node.type !== "file")
        .map(node => [node.name || node.id, node.type === "checkbox" || node.type === "radio" ? String(node.checked) : node.value]);
    const dirty = form => originals.has(form) && JSON.stringify(values(form)) !== originals.get(form);
    const forms = () => [...document.querySelectorAll("[data-admin-dirty]")].filter(form => form.isConnected && !form.closest("[hidden]"));
    const track = root => root.querySelectorAll("[data-admin-dirty]").forEach(form => originals.set(form, JSON.stringify(values(form))));
    const clean = form => { if (form) originals.set(form, JSON.stringify(values(form))); };
    function canLeave() {
        if (!forms().some(dirty)) return true;
        if (!confirm("Bạn có thay đổi chưa lưu. Rời trang và bỏ các thay đổi này?")) return false;
        forms().forEach(clean); return true;
    }
    function capture(root) {
        return [...root.querySelectorAll("[data-admin-dirty]")].filter(dirty)
            .map(form => {
                const baseline = new Map(JSON.parse(originals.get(form)));
                return {key: form.dataset.adminDirty, values: values(form).filter(([key,value]) => baseline.get(key) !== value)};
            });
    }
    function restore(root, snapshots) {
        for (const snapshot of snapshots) {
            const form = [...root.querySelectorAll("[data-admin-dirty]")].find(node => node.dataset.adminDirty === snapshot.key);
            if (!form) continue;
            for (const [key, value] of snapshot.values) {
                const node = [...form.querySelectorAll("input,select,textarea")].find(n => (n.name || n.id) === key);
                if (node) {
                    if (node.type === "checkbox" || node.type === "radio") node.checked = value === "true";
                    else node.value = value;
                }
            }
        }
    }
    async function busy(scope, action) {
        if (scope.dataset.busy === "true") return;
        const controls = [...scope.querySelectorAll("button,input,select,textarea")];
        const disabled = controls.map(control => control.disabled);
        scope.dataset.busy = "true"; scope.setAttribute("aria-busy", "true");
        controls.forEach(control => { control.disabled = true; });
        try { return await action(); }
        finally { controls.forEach((control, i) => { control.disabled = disabled[i]; }); scope.dataset.busy = "false"; scope.setAttribute("aria-busy", "false"); }
    }
    function query(defaults) {
        const params = new URLSearchParams(location.search);
        return Object.fromEntries(Object.entries(defaults).map(([k,v]) => [k, params.get(k) ?? v]));
    }
    function url(params, replace = false) {
        const url = new URL(location.href);
        url.search = new URLSearchParams(Object.entries(params).filter(([,v]) => v !== "" && v != null)).toString();
        history[replace ? "replaceState" : "pushState"]({}, "", url);
        rememberUrl();
    }
    let currentUrl = location.href;
    const rememberUrl = () => { currentUrl = location.href; };
    function pager(result) {
        const pages = Math.max(1, Math.ceil(result.total / result.size));
        return `<nav class="pagination admin-pagination" aria-label="Phân trang danh sách">
            <button class="text-button" type="button" data-page="${result.page}" ${result.page === 0 ? "disabled" : ""}>← Trang trước</button>
            <span role="status" tabindex="-1">Trang ${result.page + 1}/${pages} · ${result.total} mục</span>
            <button class="text-button" type="button" data-page="${result.page + 2}" ${result.page + 1 >= pages ? "disabled" : ""}>Trang sau →</button></nav>`;
    }
    const stamp = value => value ? new Date(value).toLocaleString("vi-VN") : "Chưa có";
    const badge = (text, tone = "") => `<span class="admin-badge ${tone}">${esc(text)}</span>`;
    function events(items) {
        const names={CREATE_DRAFT:"Tạo bản nháp",UPDATE_METADATA:"Sửa thông tin bài",ADD_SENTENCE:"Thêm câu",UPDATE_SENTENCE:"Sửa transcript",DELETE_SENTENCE:"Xóa câu",REORDER_SENTENCES:"Đổi thứ tự câu",UPLOAD_AUDIO:"Tải audio",PUBLISH:"Xuất bản",UNPUBLISH:"Gỡ xuất bản",CREATE_MANUAL_DRAFT:"Tạo nháp thủ công",CREATE_AI_DRAFT:"Tạo nháp bằng AI",COPY_DRAFT:"Tạo bản sửa mới",UPDATE_DRAFT:"Sửa bản nháp",RETIRE:"Ngừng phát hành / bỏ nháp",OPEN:"Chờ xử lý",IN_PROGRESS:"Đang xử lý",RESOLVED:"Đã xử lý",PREVIOUS_RESPONSE:"Phản hồi trước khi có nhật ký",CHANGE_ROLE:"Đổi quyền",DISABLE_ACCOUNT:"Khóa tài khoản",ENABLE_ACCOUNT:"Mở khóa",SEND_PASSWORD_RESET:"Gửi đặt lại mật khẩu",REVOKE_SESSIONS:"Thu hồi phiên đăng nhập",CHANGE_PASSWORD:"Đổi mật khẩu",MANUAL_RECALL_GRADE:"Chấm lại phần nhớ ý"};
        return items.length ? `<ol class="admin-timeline">${items.map(item => `<li><strong>${esc(names[item.action] || item.action)}</strong>
            <time>${esc(stamp(item.created_at ?? item.CREATED_AT))}</time><p>${esc(item.actor_email ?? item.ACTOR_EMAIL ?? "Hệ thống")} · ${esc(item.detail ?? item.DETAIL ?? "")}</p></li>`).join("")}</ol>` : '<p class="admin-empty">Chưa có thao tác được ghi nhận.</p>';
    }
    function failure(root, message, retry) {
        root.setAttribute("aria-busy", "false");
        root.innerHTML = `<div class="admin-empty"><p role="alert">${esc(message)}</p><button type="button" class="primary-button compact" data-retry>Thử lại</button></div>`;
        root.querySelector("[data-retry]").addEventListener("click", retry);
    }
    function fieldError(form, name, message) {
        const field = form.elements.namedItem(name);
        if (field) {
            field.closest("details")?.setAttribute("open", ""); field.setCustomValidity(message); field.setAttribute("aria-invalid", "true");
            const id=`admin-error-${form.dataset.adminDirty || form.id}-${name}`;
            document.getElementById(id)?.remove();
            const note=document.createElement("small"); note.id=id; note.textContent=message; note.setAttribute("role","alert");
            field.closest("label").appendChild(note); field.setAttribute("aria-describedby",id);
            requestAnimationFrame(()=>{ field.reportValidity(); field.focus(); });
            field.addEventListener("input", () => { field.setCustomValidity(""); field.removeAttribute("aria-invalid"); field.removeAttribute("aria-describedby"); note.remove(); }, {once: true});
        }
        throw new Error(message);
    }
    if (document.body.dataset.area === "admin") {
        document.addEventListener("click", event => {
            const link = event.target.closest("a[href]");
            if (!link || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || link.getAttribute("href").startsWith("#")) return;
            if (!canLeave()) { event.preventDefault(); event.stopImmediatePropagation(); }
        }, true);
        window.addEventListener("beforeunload", event => { if (forms().some(dirty)) { event.preventDefault(); event.returnValue = ""; } });
        window.addEventListener("popstate", event => {
            if (!canLeave()) { history.pushState({}, "", currentUrl); event.stopImmediatePropagation(); }
            else rememberUrl();
        });
    }
    window.DictaLabAdmin = {esc, track, clean, dirty, canLeave, capture, restore, busy, query, url, pager, stamp, badge, events, failure, fieldError, rememberUrl};
})();
