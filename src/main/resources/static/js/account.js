(() => {
    "use strict";
    const bridge = () => window.DictaLabListeningBridge;
    const escape = value => String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;").replaceAll('"', "&quot;");
    const status = (root, message, error = false) => {
        const node = root.querySelector("[data-status]") || root.querySelector("#auth-status");
        if (node) { node.textContent = message; node.dataset.error = String(error); }
    };
    const submit = (form, action) => form.addEventListener("submit", async event => {
        event.preventDefault();
        const button = form.querySelector("button[type='submit']");
        button.disabled = true;
        status(form.closest(".account-page") || form.closest(".auth-card"), "Đang xử lý…");
        try { await action(Object.fromEntries(new FormData(form).entries())); }
        catch (error) { status(form.closest(".account-page") || form.closest(".auth-card"), error.message, true); }
        finally { button.disabled = false; }
    });

    function showPublic(route) {
        const panel = document.querySelector("#account-public-actions");
        const tabs = document.querySelector(".auth-tabs");
        tabs.hidden = true;
        document.querySelector("#login-form").hidden = true;
        document.querySelector("#register-form").hidden = true;
        document.querySelector("#forgot-password-link").hidden = true;
        panel.hidden = false;
        const token = new URLSearchParams(location.search).get("token");
        document.querySelector("#auth-heading").textContent = route === "verify-email" ? "Xác nhận email." : "Đặt lại mật khẩu.";
        panel.innerHTML = route === "verify-email" ? `
            <p id="public-confirm-status" role="status">Đang xác nhận liên kết…</p>
            <a class="text-button" href="/login">Về đăng nhập</a>` : token ? `
            <form id="reset-complete" class="auth-form"><label>Mật khẩu mới
                <input name="password" type="password" minlength="8" maxlength="72" autocomplete="new-password" required></label>
                <button class="primary-button" type="submit">Đặt mật khẩu mới</button></form>
            <a class="text-button" href="/login">Về đăng nhập</a>` : `
            <form id="reset-request" class="auth-form"><label>Email tài khoản
                <input name="email" type="email" maxlength="160" autocomplete="email" required></label>
                <button class="primary-button" type="submit">Gửi liên kết đặt lại</button></form>
            <a class="text-button" href="/login">Về đăng nhập</a>`;
        if (route === "verify-email") {
            if (!token) { panel.querySelector("#public-confirm-status").textContent = "Liên kết thiếu mã xác nhận."; return; }
            const change = new URLSearchParams(location.search).get("purpose") === "change";
            bridge().api(change ? "/api/auth/email/change/confirm" : "/api/auth/email/confirm",
                {method: "POST", body: {token}, redirectOnUnauthorized: false})
                .then(result => { panel.querySelector("#public-confirm-status").textContent = result.message; })
                .catch(error => { panel.querySelector("#public-confirm-status").textContent = error.message; });
        } else if (token) submit(panel.querySelector("#reset-complete"), async data => {
            const result = await bridge().api("/api/auth/password-reset/complete",
                {method: "POST", body: {token, password: data.password}, redirectOnUnauthorized: false});
            status(document.querySelector(".auth-card"), result.message);
            panel.querySelector("button").disabled = true;
        });
        else submit(panel.querySelector("#reset-request"), async data => {
            const result = await bridge().api("/api/auth/password-reset/request",
                {method: "POST", body: data, redirectOnUnauthorized: false});
            status(document.querySelector(".auth-card"), result.message);
        });
        panel.querySelector("input")?.focus();
    }

    async function show(route) {
        try {
            if (route === "account") await account();
            else if (route === "support") await support();
            else if (route === "admin-users") await users();
            else if (route === "admin-support") await tickets();
        } catch (error) {
            const root = document.querySelector(`#${route}-content`);
            root.innerHTML = `<p role="alert">${escape(error.message)}</p><button type="button" class="text-button">Thử lại</button>`;
            root.querySelector("button").addEventListener("click", () => show(route));
        }
    }

    async function account() {
        const user = await bridge().refreshUser();
        const root = document.querySelector("#account-content");
        root.innerHTML = `<p class="account-lead">${escape(user.email)} · ${user.emailVerified ? "Email đã xác minh" : "Email chưa xác minh"}</p>
            <div class="account-grid">
            <form id="account-name-form" class="account-panel"><h2>Thông tin cá nhân</h2>
                <label>Tên hiển thị<input name="displayName" minlength="2" maxlength="80" value="${escape(user.displayName)}" required></label>
                <button class="primary-button" type="submit">Lưu tên</button></form>
            <form id="account-password-form" class="account-panel"><h2>Mật khẩu</h2>
                <label>Mật khẩu hiện tại<input name="currentPassword" type="password" autocomplete="current-password" required></label>
                <label>Mật khẩu mới<input name="newPassword" type="password" autocomplete="new-password" minlength="8" maxlength="72" required></label>
                <p>Từ 8 ký tự, có chữ cái và chữ số.</p><button class="primary-button" type="submit">Đổi mật khẩu</button></form>
            <form id="account-email-form" class="account-panel"><h2>Đổi email</h2>
                <label>Email mới<input name="email" type="email" maxlength="160" required></label>
                <label>Mật khẩu hiện tại<input name="password" type="password" autocomplete="current-password" required></label>
                <button class="primary-button" type="submit">Gửi liên kết xác nhận</button></form>
            <div class="account-panel"><h2>Bảo vệ tài khoản</h2>
                ${user.emailVerified ? "" : '<button id="account-verify" class="text-button" type="button">Gửi email xác minh</button>'}
                <button id="account-logout-all" class="text-button" type="button">Đăng xuất mọi thiết bị</button>
                <a class="text-button" href="/support">Yêu cầu xóa tài khoản hoặc dữ liệu</a></div></div>
            <p data-status role="status" aria-live="polite"></p>
            ${user.role === "ADMIN" ? '<p><a href="/admin/users">Quản lý người dùng</a> · <a href="/admin/support">Xử lý hỗ trợ</a></p>' : ""}`;
        submit(root.querySelector("#account-name-form"), async data => {
            await bridge().api("/api/account/profile", {method: "PUT", body: data});
            await bridge().refreshUser(); status(root, "Đã lưu tên hiển thị.");
        });
        submit(root.querySelector("#account-password-form"), async data => {
            await bridge().api("/api/account/password", {method: "POST", body: data});
            bridge().requireLogin();
        });
        submit(root.querySelector("#account-email-form"), async data => {
            const result = await bridge().api("/api/account/email/change", {method: "POST", body: data});
            status(root, result.message);
        });
        root.querySelector("#account-verify")?.addEventListener("click", async () => {
            try { const result = await bridge().api("/api/account/email/verification", {method: "POST"}); status(root, result.message); }
            catch (error) { status(root, error.message, true); }
        });
        root.querySelector("#account-logout-all").addEventListener("click", async () => {
            try { await bridge().api("/api/account/logout-all", {method: "POST"}); bridge().requireLogin(); }
            catch (error) { status(root, error.message, true); }
        });
    }

    async function support(page = 0) {
        const result = await bridge().api(`/api/support/tickets?page=${page}`);
        const items = result.tickets;
        const root = document.querySelector("#support-content");
        const reference = new URLSearchParams(location.search);
        const referenceType = ["LISTENING", "DICTATION"].includes(reference.get("type")) ? reference.get("type") : null;
        const referenceId = referenceType && /^[0-9a-f-]{36}$/i.test(reference.get("id") || "") ? reference.get("id") : null;
        root.innerHTML = `<div class="account-grid"><form id="support-form" class="account-panel"><h2>Báo vấn đề</h2>
            ${referenceId ? `<p>Báo vấn đề cho bài ${escape(referenceId)}.</p>
                <input type="hidden" name="referenceType" value="${escape(referenceType)}">
                <input type="hidden" name="referenceId" value="${escape(referenceId)}">` : ""}
            <label>Loại vấn đề<select name="category"><option value="AUDIO">Audio không phát hoặc sai</option>
                <option value="TRANSCRIPT">Transcript hoặc đáp án sai</option><option value="SCORE">Điểm chấm chưa đúng</option>
                <option value="ACCOUNT_DELETE">Yêu cầu xóa tài khoản</option><option value="OTHER">Vấn đề khác</option></select></label>
            <label>Tiêu đề<input name="subject" maxlength="160" required></label>
            <label>Mô tả<textarea name="description" maxlength="3000" rows="5" required></textarea></label>
            <button class="primary-button" type="submit">Gửi yêu cầu</button></form>
            <div class="account-panel"><h2>Yêu cầu của bạn</h2>${items.length ? `<ul>${items.map(item => `
                <li><strong>#${item.ID ?? item.id} · ${escape(item.SUBJECT ?? item.subject)}</strong><br>
                ${escape(item.STATUS ?? item.status)} · ${escape(item.ADMIN_NOTE ?? item.admin_note ?? "Chưa có phản hồi")}</li>`).join("")}</ul>`
                : "<p>Chưa có yêu cầu nào ở trang này.</p>"}
                <div class="account-pager"><button id="support-prev" type="button" class="text-button" ${page === 0 ? "disabled" : ""}>Trang trước</button>
                    <button id="support-next" type="button" class="text-button" ${result.hasMore ? "" : "disabled"}>Trang sau</button></div></div></div>
            <p data-status role="status" aria-live="polite"></p>`;
        root.querySelector("#support-prev").addEventListener("click", () => support(page - 1));
        root.querySelector("#support-next").addEventListener("click", () => support(page + 1));
        submit(root.querySelector("#support-form"), async data => {
            await bridge().api("/api/support/tickets", {method: "POST", body: data});
            const url = new URL(location.href);
            url.searchParams.delete("type"); url.searchParams.delete("id");
            history.replaceState(history.state, "", url);
            await support(); status(document.querySelector("#support-content"), "Đã gửi yêu cầu hỗ trợ.");
        });
    }

    async function users(page = 0, q = "", role = "", enabled = "") {
        const data = await bridge().api(`/api/admin/users?${new URLSearchParams({page, q,
            ...(role ? {role} : {}), ...(enabled ? {enabled} : {})})}`);
        const root = document.querySelector("#admin-users-content");
        root.innerHTML = `<form id="users-search" class="account-search"><label>Tìm email hoặc tên
            <input name="q" value="${escape(q)}" maxlength="160"></label>
            <label>Vai trò<select name="role"><option value="">Tất cả</option><option>USER</option><option>ADMIN</option></select></label>
            <label>Trạng thái<select name="enabled"><option value="">Tất cả</option><option value="true">Đang hoạt động</option>
                <option value="false">Đã khóa</option></select></label><button class="text-button">Tìm</button></form>
            <p>${data.total} tài khoản · Trang ${page + 1}</p><ul class="account-user-list">${data.users.map(user => `
            <li><div><strong>${escape(user.displayName)}</strong><br>${escape(user.email)} · ${escape(user.role)}
                · ${user.enabled ? "Đang hoạt động" : "Đã khóa"}<br>
                ${user.emailVerified ? "Email đã xác minh" : "Email chưa xác minh"}<br>
                Tạo: ${new Date(user.createdAt).toLocaleDateString("vi-VN")}
                ${user.lastLoginAt ? ` · Đăng nhập: ${new Date(user.lastLoginAt).toLocaleDateString("vi-VN")}` : ""}</div>
                <div><button type="button" class="text-button" data-state="${user.id}" data-enabled="${user.enabled}">
                    ${user.enabled ? "Khóa" : "Mở khóa"}</button>
                <button type="button" class="text-button" data-role="${user.id}" data-current-role="${user.role}">
                    ${user.role === "ADMIN" ? "Hạ quyền" : "Cấp admin"}</button>
                <button type="button" class="text-button" data-reset="${user.id}">Gửi đặt lại mật khẩu</button>
                <button type="button" class="text-button" data-audit="${user.id}">Nhật ký</button></div>
                <div data-audit-holder="${user.id}"></div></li>`).join("")}</ul>
            <div class="account-pager"><button id="users-prev" type="button" class="text-button" ${page === 0 ? "disabled" : ""}>Trang trước</button>
                <button id="users-next" type="button" class="text-button" ${(page + 1) * 20 >= data.total ? "disabled" : ""}>Trang sau</button></div>
            <p data-status role="status" aria-live="polite"></p>`;
        const filter = root.querySelector("#users-search");
        filter.elements.role.value = role;
        filter.elements.enabled.value = enabled;
        submit(filter, async data => users(0, data.q, data.role, data.enabled));
        root.querySelector("#users-prev").addEventListener("click", () => users(page - 1, q, role, enabled));
        root.querySelector("#users-next").addEventListener("click", () => users(page + 1, q, role, enabled));
        root.querySelectorAll("[data-state]").forEach(button => button.addEventListener("click", async () => {
            const reason = prompt(button.dataset.enabled === "true" ? "Lý do khóa tài khoản:" : "Lý do mở khóa:");
            if (!reason?.trim()) return;
            try {
                await bridge().api(`/api/admin/users/${button.dataset.state}/state`, {method: "PUT",
                    body: {enabled: button.dataset.enabled !== "true", reason: reason.trim()}});
                await users(page, q, role, enabled);
            } catch (error) { status(root, error.message, true); }
        }));
        root.querySelectorAll("[data-role]").forEach(button => button.addEventListener("click", async () => {
            const newRole = button.dataset.currentRole === "ADMIN" ? "USER" : "ADMIN";
            if (!confirm(`Đổi vai trò tài khoản này thành ${newRole}?`)) return;
            try { await bridge().api(`/api/admin/users/${button.dataset.role}/role`, {method: "PUT", body: {role: newRole}}); await users(page, q, role, enabled); }
            catch (error) { status(root, error.message, true); }
        }));
        root.querySelectorAll("[data-reset]").forEach(button => button.addEventListener("click", async () => {
            if (!confirm("Gửi liên kết đặt lại mật khẩu tới email người dùng?")) return;
            try {
                const response = await bridge().api(`/api/admin/users/${button.dataset.reset}/password-reset`, {method: "POST"});
                status(root, response.message);
            } catch (error) { status(root, error.message, true); }
        }));
        root.querySelectorAll("[data-audit]").forEach(button => button.addEventListener("click", async () => {
            const holder = root.querySelector(`[data-audit-holder="${button.dataset.audit}"]`);
            try {
                const events = await bridge().api(`/api/admin/users/${button.dataset.audit}/audit`);
                holder.innerHTML = events.length ? `<ul>${events.map(event => `<li>${escape(event.ACTION ?? event.action)}
                    · ${escape(event.ACTOR_EMAIL ?? event.actor_email ?? "Hệ thống")}
                    · ${escape(event.DETAIL ?? event.detail ?? "")}</li>`).join("")}</ul>` : "<p>Chưa có thao tác.</p>";
            } catch (error) { status(root, error.message, true); }
        }));
    }

    async function tickets(filter = "OPEN", page = 0) {
        const result = await bridge().api(`/api/admin/support/tickets?${new URLSearchParams({status: filter, page})}`);
        const items = result.tickets;
        const root = document.querySelector("#admin-support-content");
        root.innerHTML = `<label>Trạng thái <select id="ticket-filter"><option>OPEN</option><option>IN_PROGRESS</option>
            <option>RESOLVED</option></select></label><ul class="account-user-list">${items.map(item => `<li>
            <div><strong>#${item.ID ?? item.id} · ${escape(item.SUBJECT ?? item.subject)}</strong>
                <p>${escape(item.EMAIL ?? item.email)} · ${escape(item.CATEGORY ?? item.category)}</p>
                <p>${escape(item.DESCRIPTION ?? item.description)}</p></div>
            <form data-ticket="${item.ID ?? item.id}"><label>Trạng thái
                <select name="status"><option>OPEN</option><option>IN_PROGRESS</option><option>RESOLVED</option></select></label>
                <label>Phản hồi<input name="adminNote" maxlength="1000" value="${escape(item.ADMIN_NOTE ?? item.admin_note ?? "")}"></label>
                <button class="text-button" type="submit">Lưu</button></form>
            ${(item.CATEGORY ?? item.category) === "SCORE" && (item.REFERENCE_TYPE ?? item.reference_type) === "LISTENING"
                ? `<button type="button" class="text-button" data-grade-ticket="${item.ID ?? item.id}">Kiểm tra phần nhớ ý</button>
                    <div data-grade-holder="${item.ID ?? item.id}"></div>` : ""}</li>`).join("")}</ul>
            <div class="account-pager"><button id="tickets-prev" type="button" class="text-button" ${page === 0 ? "disabled" : ""}>Trang trước</button>
                <button id="tickets-next" type="button" class="text-button" ${result.hasMore ? "" : "disabled"}>Trang sau</button></div>
            <p data-status role="status" aria-live="polite"></p>`;
        root.querySelector("#ticket-filter").value = filter;
        root.querySelector("#ticket-filter").addEventListener("change", event => tickets(event.target.value));
        root.querySelector("#tickets-prev").addEventListener("click", () => tickets(filter, page - 1));
        root.querySelector("#tickets-next").addEventListener("click", () => tickets(filter, page + 1));
        root.querySelectorAll("[data-ticket]").forEach(form => {
            const item = items.find(item => String(item.ID ?? item.id) === form.dataset.ticket);
            form.elements.status.value = item.STATUS ?? item.status ?? "OPEN";
            submit(form, async data => {
                await bridge().api(`/api/admin/support/tickets/${form.dataset.ticket}`, {method: "PUT", body: data});
                await tickets(filter, page);
            });
        });
        root.querySelectorAll("[data-grade-ticket]").forEach(button => button.addEventListener("click", async () => {
            const ticketId = button.dataset.gradeTicket;
            const holder = root.querySelector(`[data-grade-holder="${ticketId}"]`);
            try {
                const grade = await bridge().api(`/api/admin/support/tickets/${ticketId}/grading`);
                holder.innerHTML = grade.passes.map(pass => {
                    const passNumber = pass.PASS_NUMBER ?? pass.pass_number;
                    const existing = JSON.parse(pass.RECALL_RESULT_JSON ?? pass.recall_result_json ?? "null");
                    return `<form class="account-panel" data-grade-form="${passNumber}"><h3>Lượt ${passNumber}</h3>
                        <p><strong>Người học viết:</strong> ${escape(pass.RECALL_TEXT ?? pass.recall_text)}</p>
                        ${grade.facts.map(fact => {
                            const label = existing?.facts?.find(item => item.factId === fact.id);
                            return `<fieldset data-fact="${escape(fact.id)}"><legend>${escape(fact.text)}</legend>
                                <label>Đánh giá<select name="status-${escape(fact.id)}">
                                    ${["CAPTURED", "PARTIAL", "MISSED", "INCORRECT"].map(value => `<option
                                        ${label?.status === value ? "selected" : ""}>${value}</option>`).join("")}</select></label>
                                <label>Giải thích<input name="explanation-${escape(fact.id)}" maxlength="300"
                                    value="${escape(label?.explanationVi || "")}" required></label></fieldset>`;
                        }).join("")}
                        <button class="primary-button" type="submit">Lưu điểm đã rà soát</button></form>`;
                }).join("");
                holder.querySelectorAll("[data-grade-form]").forEach(form => submit(form, async () => {
                    const facts = grade.facts.map(fact => ({factId: fact.id,
                        status: form.elements.namedItem(`status-${fact.id}`).value,
                        explanationVi: form.elements.namedItem(`explanation-${fact.id}`).value}));
                    await bridge().api(`/api/admin/support/tickets/${ticketId}/grading/${form.dataset.gradeForm}`,
                        {method: "PUT", body: {result: JSON.stringify({facts})}});
                    status(root, "Đã lưu điểm rà soát. Hãy cập nhật phản hồi cho người học.");
                }));
            } catch (error) { status(root, error.message, true); }
        }));
        items.filter(item => (item.CATEGORY ?? item.category) === "ACCOUNT_DELETE").forEach(item => {
            const id = item.ID ?? item.id;
            const row = root.querySelector(`[data-ticket="${id}"]`)?.closest("li");
            if (!row) return;
            const button = document.createElement("button");
            button.type = "button";
            button.className = "text-button";
            button.textContent = "Xóa tài khoản và dữ liệu học";
            row.appendChild(button);
            button.addEventListener("click", async () => {
                const email = prompt("Xóa vĩnh viễn tài khoản này và toàn bộ lịch sử học. Nhập đúng email người dùng để xác nhận:");
                if (!email) return;
                try {
                    await bridge().api(`/api/admin/support/tickets/${id}/delete-account`, {method: "POST", body: {email}});
                    await tickets(filter, page);
                } catch (error) { status(root, error.message, true); }
            });
        });
    }

    window.DictaLabAccount = {show, showPublic};
})();
