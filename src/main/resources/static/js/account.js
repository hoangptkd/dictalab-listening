(() => {
    "use strict";
    const bridge = () => window.DictaLabListeningBridge;
    const escape = value => String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;").replaceAll('"', "&quot;");
    const status = (root, message, error = false) => {
        const node = root.querySelector("[data-status]") || root.querySelector("#auth-status");
        if (node) { node.textContent = message; node.dataset.error = String(error); }
    };
    const submit = (form, action, idleDisabled = () => false) => form.addEventListener("submit", async event => {
        event.preventDefault();
        const button = event.submitter || form.querySelector("button[type='submit'],button:not([type])");
        if (!button || button.disabled) return;
        const data=Object.fromEntries(new FormData(form).entries());
        const page=form.closest(".account-page") || form.closest(".auth-card");
        button.disabled = true;
        status(form.closest(".account-page") || form.closest(".auth-card"), "Đang xử lý…");
        try { if (form.hasAttribute("data-admin-dirty")) await window.DictaLabAdmin.busy(form,()=>action(data)); else await action(data); }
        catch (error) { status(page, error.message, true); }
        finally { button.disabled = idleDisabled(); }
    });

    function enhancePasswords(root) {
        root.querySelectorAll('input[type="password"]').forEach(input => {
            if (input.closest(".password-field")) return;
            const label = input.closest("label");
            const wrapper = document.createElement("div");
            wrapper.className = "password-field";
            input.id ||= `${input.form.id}-${input.name}`;
            label.before(wrapper);
            wrapper.append(label);
            const button = document.createElement("button");
            button.type = "button";
            button.className = "text-button password-toggle";
            button.textContent = "Hiện";
            button.setAttribute("aria-label", `Hiện ${label.textContent.trim().toLowerCase()}`);
            button.setAttribute("aria-controls", input.id);
            button.setAttribute("aria-pressed", "false");
            button.addEventListener("click", () => {
                const visible = input.type === "password";
                input.type = visible ? "text" : "password";
                button.textContent = visible ? "Ẩn" : "Hiện";
                button.setAttribute("aria-pressed", String(visible));
            });
            wrapper.append(button);
        });
    }

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
        document.querySelector("#auth-subtitle").textContent = route === "verify-email"
            ? "Kiểm tra liên kết xác nhận email của bạn."
            : token ? "Nhập mật khẩu mới để bảo vệ tài khoản của bạn."
                : "Nhập email tài khoản để nhận liên kết đặt lại mật khẩu.";
        panel.innerHTML = route === "verify-email" ? `
            <p id="public-confirm-status" role="status">Đang xác nhận liên kết…</p>
            <a class="text-button" href="/login">Về đăng nhập</a>` : token ? `
            <form id="reset-complete" class="auth-form"><label>Mật khẩu mới
                <input name="password" type="password" minlength="8" maxlength="72" autocomplete="new-password" aria-describedby="reset-password-note" required></label>
                <p id="reset-password-note" class="password-note">Từ 8 ký tự, có ít nhất một chữ cái và một chữ số.</p>
                <button class="primary-button" type="submit">Đặt mật khẩu mới</button></form>
            <a class="text-button" href="/login">Về đăng nhập</a>` : `
            <form id="reset-request" class="auth-form"><label>Email tài khoản
                <input name="email" type="email" maxlength="160" autocomplete="email" required></label>
                <button class="primary-button" type="submit">Gửi liên kết đặt lại</button></form>
            <a class="text-button" href="/login">Về đăng nhập</a>`;
        enhancePasswords(panel);
        if (route === "reset-password" && !token) {
            try {
                const email = sessionStorage.getItem("dictalab-reset-email");
                sessionStorage.removeItem("dictalab-reset-email");
                if (email) panel.querySelector('[name="email"]').value = email;
            } catch (_) { /* Browser autofill and manual entry remain available. */ }
        }
        if (route === "verify-email") {
            if (!token) { panel.querySelector("#public-confirm-status").textContent = "Liên kết thiếu mã xác nhận."; return; }
            const change = new URLSearchParams(location.search).get("purpose") === "change";
            bridge().api(change ? "/api/auth/email/change/confirm" : "/api/auth/email/confirm",
                {method: "POST", body: {token}, redirectOnUnauthorized: false})
                .then(result => { panel.querySelector("#public-confirm-status").textContent = result.message; })
                .catch(error => { panel.querySelector("#public-confirm-status").textContent = error.message; });
        } else if (token) {
            let completed = false;
            submit(panel.querySelector("#reset-complete"), async data => {
                const result = await bridge().api("/api/auth/password-reset/complete",
                    {method: "POST", body: {token, password: data.password}, redirectOnUnauthorized: false});
                status(document.querySelector(".auth-card"), result.message);
                completed = true;
            }, () => completed);
        } else submit(panel.querySelector("#reset-request"), async data => {
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
                <button class="primary-button" type="submit" disabled>Lưu tên</button>
                <small>Thay đổi tên hiển thị để lưu.</small></form>
            <form id="account-password-form" class="account-panel"><h2>Mật khẩu</h2>
                <label>Mật khẩu hiện tại<input name="currentPassword" type="password" autocomplete="current-password" required></label>
                <label>Mật khẩu mới<input name="newPassword" type="password" autocomplete="new-password" minlength="8" maxlength="72" aria-describedby="account-password-note" required></label>
                <p id="account-password-note">Từ 8 ký tự, có chữ cái và chữ số.</p><button class="primary-button" type="submit">Đổi mật khẩu</button></form>
            <form id="account-email-form" class="account-panel"><h2>Đổi email</h2>
                <label>Email mới<input name="email" type="email" maxlength="160" required></label>
                <label>Mật khẩu hiện tại<input name="password" type="password" autocomplete="current-password" required></label>
                <button class="primary-button" type="submit">Gửi liên kết xác nhận</button></form>
            <div class="account-panel"><h2>Bảo vệ tài khoản</h2>
                ${user.emailVerified ? "" : '<button id="account-verify" class="text-button" type="button">Gửi email xác minh</button>'}
                <button id="account-logout-all" class="text-button" type="button">Đăng xuất mọi thiết bị</button>
                <a class="text-button" href="/support?category=ACCOUNT_DELETE">Yêu cầu xóa tài khoản hoặc dữ liệu</a></div></div>
            <p data-status role="status" aria-live="polite"></p>
            ${user.role === "ADMIN" ? '<p><a href="/admin/users">Quản lý người dùng</a> · <a href="/admin/support">Xử lý hỗ trợ</a></p>' : ""}`;
        enhancePasswords(root);
        const nameForm = root.querySelector("#account-name-form");
        const nameInput = nameForm.querySelector("input");
        const nameButton = nameForm.querySelector('button[type="submit"]');
        let savedName = user.displayName;
        let savingName = false;
        const nameUnchanged = () => nameInput.value.trim() === savedName;
        nameInput.addEventListener("input", () => { nameButton.disabled = savingName || nameUnchanged(); });
        submit(nameForm, async data => {
            savingName = true;
            try {
                await bridge().api("/api/account/profile", {method: "PUT", body: data});
                const updated = await bridge().refreshUser();
                savedName = updated.displayName;
                status(root, "Đã lưu tên hiển thị.");
            } finally { savingName = false; }
        }, nameUnchanged);
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
            <p>Phản hồi và trạng thái xử lý xuất hiện trong “Yêu cầu của bạn” trên trang này. Hiện chưa có thời hạn phản hồi cố định; bạn có thể quay lại để kiểm tra.</p>
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
                : page === 0 ? "<p>Bạn chưa gửi yêu cầu nào. Điền biểu mẫu để báo vấn đề hoặc yêu cầu hỗ trợ.</p>" : "<p>Trang này chưa có yêu cầu. Chọn Trang trước để xem các yêu cầu đã gửi.</p>"}
                <div class="account-pager"><button id="support-prev" type="button" class="text-button" ${page === 0 ? "disabled" : ""}>Trang trước</button>
                    <button id="support-next" type="button" class="text-button" ${result.hasMore ? "" : "disabled"}>Trang sau</button></div></div></div>
            <p data-status role="status" aria-live="polite"></p>`;
        const form = root.querySelector("#support-form");
        const deletionGuide = document.createElement("div");
        deletionGuide.className = "account-lead";
        deletionGuide.innerHTML = `<h3>Quy trình xóa tài khoản</h3><ol>
            <li>Gửi yêu cầu này từ tài khoản cần xóa; nêu rõ bạn muốn xóa tài khoản và dữ liệu học.</li>
            <li>Quản trị viên xem xét yêu cầu. Bạn theo dõi trạng thái và trao đổi tại “Yêu cầu của bạn”.</li>
            <li>Rời phòng gọi trước khi yêu cầu được thực hiện. Quản trị viên phải xác nhận đúng email để xóa.</li>
            <li>Khi xóa hoàn tất, tài khoản, dữ liệu học và yêu cầu hỗ trợ bị xóa; bạn sẽ không thể đăng nhập hoặc xem lại yêu cầu. Hãy lưu thông tin cần thiết trước khi gửi.</li>
            </ol><p>Việc xóa không thể hoàn tác qua ứng dụng. Yêu cầu xóa tài khoản quản trị cần được xử lý riêng.</p>`;
        form.querySelector('[name="category"]').closest("label").after(deletionGuide);
        const category = reference.get("category");
        if (Object.hasOwn(categories, category)) root.querySelector('[name="category"]').value = category;
        const categorySelect = form.querySelector('[name="category"]');
        deletionGuide.hidden = categorySelect.value !== "ACCOUNT_DELETE";
        categorySelect.addEventListener("change", () => { deletionGuide.hidden = categorySelect.value !== "ACCOUNT_DELETE"; });
        root.querySelector("#support-prev").addEventListener("click", () => support(page - 1));
        root.querySelector("#support-next").addEventListener("click", () => support(page + 1));
        submit(root.querySelector("#support-form"), async data => {
            await bridge().api("/api/support/tickets", {method: "POST", body: data});
            const url = new URL(location.href);
            url.searchParams.delete("type"); url.searchParams.delete("id"); url.searchParams.delete("category");
            history.replaceState(history.state, "", url);
            await support(); status(document.querySelector("#support-content"), "Đã gửi yêu cầu hỗ trợ.");
        });
    }

    let adminRequest = 0;
    const categories = {AUDIO:"Audio", TRANSCRIPT:"Transcript / đáp án", SCORE:"Điểm chấm", ACCOUNT_DELETE:"Xóa tài khoản", OTHER:"Khác"};
    const ticketStates = {OPEN:"Chờ xử lý", IN_PROGRESS:"Đang xử lý", RESOLVED:"Đã xử lý"};
    const selectOptions = (items, selected, all = true) => (all ? '<option value="">Tất cả</option>' : '') + Object.entries(items).map(([value,label]) => `<option value="${value}" ${value === selected ? "selected" : ""}>${escape(label)}</option>`).join("");
    function adminFilters(root, defaults, render) {
        const ui=window.DictaLabAdmin;
        const form=root.querySelector("[data-filters]");
        if(form) {
            submit(form, async data => { if(!ui.canLeave()) return; ui.url({...data,page:1}); await render(); });
            form.querySelector("[data-clear]").addEventListener("click",()=>{ if(ui.canLeave()) { ui.url({...defaults,page:1}); render(); } });
        }
        root.querySelectorAll("[data-page]").forEach(button=>button.addEventListener("click",()=>{ if(!ui.canLeave()) return;
            ui.url({...Object.fromEntries(new URLSearchParams(location.search)),page:button.dataset.page}); render(); }));
        root.querySelectorAll("[data-detail]").forEach(link=>link.addEventListener("click",event=>{
            if(event.button!==0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
            event.preventDefault(); if(!ui.canLeave()) return;
            history.pushState({},"",link.href); ui.rememberUrl(); render();
        }));
    }
    function detailUrl(key,id) {
        const params=new URLSearchParams(location.search); params.set(key,id); return `${location.pathname}?${params}`;
    }

    async function usage(root, days = 30) {
        const restoreFocus = root.contains(document.activeElement);
        let select = root.querySelector("select");
        if (!select) {
            root.innerHTML = `<h2>Tổng quan sử dụng</h2><label>Kỳ đo
            <select name="usageDays" disabled>${[7,30,90].map(value => `<option value="${value}" ${Number(days) === value ? "selected" : ""}>${value} ngày gần đây</option>`).join("")}</select></label>
            <div data-usage-result role="status">Đang tải tổng quan…</div>`;
            select = root.querySelector("select");
            select.addEventListener("change", () => usage(root, select.value));
        }
        select.value = String(days);
        select.disabled = true;
        root.setAttribute("aria-busy", "true");
        root.querySelector("[data-usage-result]").textContent = "Đang tải tổng quan…";
        try {
            const data = await bridge().api(`/api/admin/usage?days=${encodeURIComponent(days)}`);
            if (!root.isConnected) return;
            root.querySelector("[data-usage-result]").innerHTML = `<p>${escape(new Date(data.from).toLocaleString("vi-VN"))} → ${escape(new Date(data.to).toLocaleString("vi-VN"))}</p>
                <dl class="admin-detail-grid">${[
                    ["Tài khoản hiện có (toàn bộ thời gian)", data.registeredUsers],
                    ["Tài khoản đăng ký mới", data.newUsers],
                    ["Buổi chép chính tả cá nhân hoàn thành", data.completedDictationSessions],
                    ["Phút chép chính tả cá nhân", data.dictationMinutes],
                    ["Buổi nghe hiểu hoàn thành", data.completedListeningSessions],
                    ["Phiên hội thoại được tạo", data.conversationSessions]
                ].map(([label,value]) => `<div><dt>${label}</dt><dd>${Number(value)}</dd></div>`).join("")}</dl>
                <a class="text-button" href="/api/admin/usage/export?days=${encodeURIComponent(days)}" download>Xuất báo cáo CSV</a>`;
        } catch (error) {
            if (!root.isConnected) return;
            root.querySelector("[data-usage-result]").innerHTML = `<p role="alert">${escape(error.message)}</p><button class="text-button" type="button">Thử lại</button>`;
            root.querySelector("button").addEventListener("click", () => usage(root, days));
        } finally {
            select.disabled = false;
            root.setAttribute("aria-busy", "false");
            if (root.isConnected && restoreFocus) select.focus();
        }
    }
    const listUrl = key => { const params=new URLSearchParams(location.search); params.delete(key); return `${location.pathname}?${params}`; };

    async function users() {
        const ui=window.DictaLabAdmin, root=document.querySelector("#admin-users-content"), request=++adminRequest;
        const filter=ui.query({q:"",role:"",enabled:"",verified:"",sort:"created",page:1,user:""});
        root.setAttribute("aria-busy","true");
        try {
            if(filter.user) {
                const [user,events]=await Promise.all([bridge().api(`/api/admin/users/${encodeURIComponent(filter.user)}`),bridge().api(`/api/admin/users/${encodeURIComponent(filter.user)}/audit`)]);
                const related=await bridge().api(`/api/admin/support/tickets/page?status=&sort=newest&q=${encodeURIComponent(user.email)}`);
                const relatedItems=related.items.filter(item=>Number(item.user_id)===Number(user.id));
                if(request!==adminRequest || document.body.dataset.route!=="admin-users") return;
                const self=Number(user.id)===Number(bridge().currentUser().id);
                root.innerHTML=`<a class="text-button" data-detail href="${escape(listUrl('user'))}">← Danh sách người dùng</a>
                    <section class="admin-panel"><h2>${escape(user.displayName)}</h2><dl class="admin-detail-grid">
                    <div><dt>Email</dt><dd>${escape(user.email)}</dd></div><div><dt>Mã tài khoản</dt><dd>#${user.id}</dd></div>
                    <div><dt>Vai trò</dt><dd>${ui.badge(user.role==='ADMIN'?'Quản trị viên':'Học viên')}</dd></div>
                    <div><dt>Trạng thái</dt><dd>${ui.badge(user.enabled?'Hoạt động':'Đã khóa',user.enabled?'good':'warn')}</dd></div>
                    <div><dt>Email</dt><dd>${user.emailVerified?'Đã xác minh':'Chưa xác minh'}</dd></div>
                    <div><dt>Đăng ký</dt><dd>${ui.stamp(user.createdAt)}</dd></div><div><dt>Đăng nhập gần nhất</dt><dd>${ui.stamp(user.lastLoginAt)}</dd></div></dl>
                    <div class="admin-actions"><button class="text-button" type="button" data-user-action="state" ${self?'disabled':''}>${user.enabled?'Khóa':'Mở khóa'}</button>
                    <button class="text-button" type="button" data-user-action="role" ${self?'disabled':''}>${user.role==='ADMIN'?'Hạ quyền':'Cấp quyền quản trị'}</button>
                    <button class="text-button" type="button" data-user-action="reset">Gửi đặt lại mật khẩu</button>
                    <button class="text-button" type="button" data-user-action="revoke" ${self?'disabled':''}>Thu hồi các phiên đăng nhập</button></div>
                    ${self?'<p class="admin-summary">Đây là tài khoản của bạn. Quản lý mật khẩu và phiên tại trang tài khoản.</p>':''}</section>
                    <section class="admin-panel"><h2>Nhật ký tài khoản</h2>${ui.events(events.map(item=>({action:item.ACTION??item.action,actor_email:item.ACTOR_EMAIL??item.actor_email,detail:item.DETAIL??item.detail,created_at:item.CREATED_AT??item.created_at})))}</section>
                    <section class="admin-panel"><h2>Yêu cầu hỗ trợ gần đây</h2>${relatedItems.length?relatedItems.map(item=>`<p><a href="/admin/support?ticket=${item.id}">#${item.id} · ${escape(item.subject)}</a> · ${escape(ticketStates[item.status])}</p>`).join(''):'<p>Không có yêu cầu trong kết quả gần đây.</p>'}<a class="text-button" href="/admin/support?sort=newest&q=${encodeURIComponent(user.email)}">Tìm toàn bộ yêu cầu theo email →</a></section><p data-status role="status"></p>`;
                adminFilters(root,{},users);
                root.querySelectorAll("[data-user-action]").forEach(button=>button.addEventListener("click",()=>ui.busy(root,async()=>{
                    try {
                        const action=button.dataset.userAction;
                        if(action==='state' || action==='revoke') {
                            const reason=prompt(`${action==='revoke'?'Thu hồi phiên đăng nhập':user.enabled?'Khóa':'Mở khóa'} ${user.email}. Nhập lý do:`);
                            if(!reason?.trim()) return;
                            await bridge().api(`/api/admin/users/${user.id}/${action==='state'?'state':'revoke-sessions'}`,{method:action==='state'?'PUT':'POST',body:action==='state'?{enabled:!user.enabled,reason:reason.trim()}:{reason:reason.trim()}});
                        } else if(action==='role') {
                            const role=user.role==='ADMIN'?'USER':'ADMIN';
                            if(!confirm(`Đổi quyền của ${user.email} thành ${role==='ADMIN'?'quản trị viên':'học viên'}?`)) return;
                            await bridge().api(`/api/admin/users/${user.id}/role`,{method:'PUT',body:{role}});
                        } else {
                            if(!confirm(`Gửi liên kết đặt lại mật khẩu tới ${user.email}?`)) return;
                            await bridge().api(`/api/admin/users/${user.id}/password-reset`,{method:'POST'});
                        }
                        await users(); status(root,'Đã hoàn thành thao tác.');
                    } catch(error) { status(root,error.message,true); }
                })));
                return;
            }
            const result=await bridge().api(`/api/admin/users/page?${new URLSearchParams({...filter,page:Number(filter.page)-1})}`);
            if(request!==adminRequest || document.body.dataset.route!=="admin-users") return;
            ui.url({...filter,page:result.page+1},true);
            root.innerHTML=`<section id="admin-usage" class="account-panel" aria-label="Tổng quan sử dụng"></section>
                <form data-filters class="admin-toolbar"><label>Tìm email hoặc tên<input name="q" maxlength="160" value="${escape(filter.q)}" type="search"></label>
                <label>Vai trò<select name="role">${selectOptions({USER:'Học viên',ADMIN:'Quản trị viên'},filter.role)}</select></label>
                <label>Trạng thái<select name="enabled">${selectOptions({true:'Hoạt động',false:'Đã khóa'},filter.enabled)}</select></label>
                <label>Xác minh email<select name="verified">${selectOptions({true:'Đã xác minh',false:'Chưa xác minh'},filter.verified)}</select></label>
                <label>Sắp xếp<select name="sort">${selectOptions({created:'Đăng ký mới nhất',login:'Đăng nhập gần nhất',name:'Tên A–Z'},filter.sort,false)}</select></label>
                <div class="admin-actions"><button class="primary-button compact" type="submit">Áp dụng</button><button class="text-button" type="button" data-clear>Xóa lọc</button></div></form>
                <p class="admin-summary">${result.total} tài khoản phù hợp</p>${result.items.length?`<div class="admin-table-wrap"><table class="admin-table"><caption class="sr-only">Danh sách người dùng</caption><thead><tr><th scope="col">Người dùng</th><th scope="col">Vai trò</th><th scope="col">Trạng thái</th><th scope="col">Đăng ký</th><th scope="col">Thao tác</th></tr></thead><tbody>${result.items.map(user=>`<tr>
                <td><strong>${escape(user.display_name)}</strong><small>${escape(user.email)}</small></td><td data-label="Vai trò">${user.role==='ADMIN'?'Quản trị viên':'Học viên'}</td>
                <td data-label="Trạng thái">${ui.badge(user.enabled?'Hoạt động':'Đã khóa',user.enabled?'good':'warn')}<small>${user.email_verified?'Email đã xác minh':'Email chưa xác minh'}</small></td>
                <td data-label="Đăng ký">${ui.stamp(user.created_at)}</td><td><a class="text-button" data-detail href="${escape(detailUrl('user',user.id))}" aria-label="Quản lý ${escape(user.email)}">Quản lý →</a></td></tr>`).join('')}</tbody></table></div>`:'<p class="admin-empty">Không có tài khoản phù hợp. Hãy đổi điều kiện hoặc xóa bộ lọc.</p>'}${ui.pager(result)}<p data-status role="status"></p>`;
            adminFilters(root,{sort:'created'},users);
            usage(root.querySelector("#admin-usage"));
        } catch(error) { if(request===adminRequest && document.body.dataset.route==='admin-users') ui.failure(root,error.message,users); }
        finally { if(request===adminRequest) root.setAttribute('aria-busy','false'); }
    }

    async function tickets() {
        const ui=window.DictaLabAdmin, root=document.querySelector('#admin-support-content'), request=++adminRequest;
        const filter=ui.query({q:'',status:'OPEN',category:'',referenceType:'',from:'',to:'',sort:'oldest',page:1,ticket:''});
        root.setAttribute('aria-busy','true');
        try {
            if(filter.ticket) {
                const item=await bridge().api(`/api/admin/support/tickets/${encodeURIComponent(filter.ticket)}`);
                if(request!==adminRequest || document.body.dataset.route!=='admin-support') return;
                root.innerHTML=`<a data-detail class="text-button" href="${escape(listUrl('ticket'))}">← Danh sách yêu cầu</a>
                    <section class="admin-panel"><h2>#${item.id} · ${escape(item.subject)}</h2><dl class="admin-detail-grid">
                    <div><dt>Người gửi</dt><dd><a href="/admin/users?user=${item.user_id}">${escape(item.email)}</a></dd></div>
                    <div><dt>Loại vấn đề</dt><dd>${escape(categories[item.category])}</dd></div><div><dt>Ngày tạo</dt><dd>${ui.stamp(item.created_at)}</dd></div><div><dt>Cập nhật</dt><dd>${ui.stamp(item.updated_at)}</dd></div></dl>
                    <p class="admin-detail-description">${escape(item.description)}</p>
                    ${item.content_id?`<p><a href="${item.reference_type==='LISTENING'?'/admin/listening?edit=':'/admin/lessons/'}${item.content_id}">Xem nội dung bài liên quan →</a></p>`:''}
                    <form data-ticket="${item.id}" data-admin-dirty="ticket-${item.id}"><label>Trạng thái<select name="status">${selectOptions(ticketStates,item.status,false)}</select></label>
                    <label>Phản hồi cho người học<textarea name="adminNote" rows="5" maxlength="1000">${escape(item.admin_note)}</textarea></label>
                    <button class="primary-button compact" type="submit">Lưu phản hồi</button></form>
                    ${item.category==='SCORE' && item.reference_type==='LISTENING'?`<button type="button" class="text-button" data-grade-ticket="${item.id}">Kiểm tra phần nhớ ý</button><div data-grade-holder="${item.id}"></div>`:''}
                    ${item.category==='ACCOUNT_DELETE'?'<button type="button" class="text-button" data-delete-account>Xóa tài khoản và dữ liệu học</button>':''}</section>
                    <section class="admin-panel"><h2>Lịch sử xử lý</h2><div data-events>${ui.events(item.events)}</div></section><p data-status role="status" aria-live="polite"></p>`;
                const form=root.querySelector('[data-ticket]'); ui.track(root);
                submit(form,async data=>{
                    await bridge().api(`/api/admin/support/tickets/${item.id}`,{method:'PUT',body:{...data,version:item.edit_version}});
                    ui.clean(form);
                    const updated=await bridge().api(`/api/admin/support/tickets/${item.id}`);
                    item.edit_version=updated.edit_version;
                    root.querySelector('[data-events]').innerHTML=ui.events(updated.events);
                    status(root,'Đã lưu phản hồi.');
                });
                adminFilters(root,{},tickets); bindGrading(root);
                root.querySelector('[data-delete-account]')?.addEventListener('click',()=>ui.busy(root,async()=>{
                    const email=prompt(`Xóa vĩnh viễn tài khoản ${item.email} và toàn bộ lịch sử học. Nhập đúng email để xác nhận:`);
                    if(!email) return;
                    try { await bridge().api(`/api/admin/support/tickets/${item.id}/delete-account`,{method:'POST',body:{email}}); ui.clean(form); ui.url({...filter,ticket:''}); await tickets(); }
                    catch(error) { status(root,error.message,true); }
                }));
                return;
            }
            const result=await bridge().api(`/api/admin/support/tickets/page?${new URLSearchParams({...filter,page:Number(filter.page)-1})}`);
            if(request!==adminRequest || document.body.dataset.route!=='admin-support') return;
            ui.url({...filter,page:result.page+1},true);
            root.innerHTML=`<form class="admin-toolbar" data-filters><label>Tìm ID, tiêu đề, email<input name="q" value="${escape(filter.q)}" maxlength="160" type="search"></label>
                <label>Trạng thái<select name="status">${selectOptions(ticketStates,filter.status)}</select></label><label>Loại vấn đề<select name="category">${selectOptions(categories,filter.category)}</select></label>
                <label>Bài liên quan<select name="referenceType">${selectOptions({LISTENING:'Nghe hiểu',DICTATION:'Dictation'},filter.referenceType)}</select></label>
                <label>Từ ngày (UTC)<input type="date" name="from" value="${escape(filter.from)}"></label><label>Đến ngày (UTC)<input type="date" name="to" value="${escape(filter.to)}"></label>
                <label>Sắp xếp<select name="sort">${selectOptions({oldest:'Cũ nhất trước',newest:'Mới nhất trước'},filter.sort,false)}</select></label>
                <div class="admin-actions"><button type="submit" class="primary-button compact">Áp dụng</button><button type="button" class="text-button" data-clear>Xóa lọc</button></div></form>
                <p class="admin-summary">${result.total} yêu cầu phù hợp</p>${result.items.length?`<div class="admin-table-wrap"><table class="admin-table"><caption class="sr-only">Danh sách yêu cầu hỗ trợ</caption><thead><tr><th scope="col">Yêu cầu</th><th scope="col">Loại</th><th scope="col">Trạng thái</th><th scope="col">Ngày tạo</th><th scope="col">Thao tác</th></tr></thead><tbody>${result.items.map(item=>`<tr><td><strong>#${item.id} · ${escape(item.subject)}</strong><small>${escape(item.email)}</small></td><td data-label="Loại">${escape(categories[item.category])}</td><td data-label="Trạng thái">${ui.badge(ticketStates[item.status],item.status==='RESOLVED'?'good':'warn')}</td><td data-label="Ngày tạo">${ui.stamp(item.created_at)}</td><td><a class="text-button" data-detail href="${escape(detailUrl('ticket',item.id))}" aria-label="Xử lý yêu cầu ${item.id}">Xử lý →</a></td></tr>`).join('')}</tbody></table></div>`:'<p class="admin-empty">Không có yêu cầu phù hợp. Hãy đổi điều kiện hoặc xóa bộ lọc.</p>'}${ui.pager(result)}<p data-status role="status"></p>`;
            adminFilters(root,{status:'',sort:'oldest'},tickets);
        } catch(error) { if(request===adminRequest && document.body.dataset.route==='admin-support') ui.failure(root,error.message,tickets); }
        finally { if(request===adminRequest) root.setAttribute('aria-busy','false'); }
    }

    function bindGrading(root) {
        root.querySelectorAll("[data-grade-ticket]").forEach(button => button.addEventListener("click", async () => {
            if (!window.DictaLabAdmin.canLeave()) return;
            const ticketId = button.dataset.gradeTicket;
            const holder = root.querySelector(`[data-grade-holder="${ticketId}"]`);
            try {
                const grade = await bridge().api(`/api/admin/support/tickets/${ticketId}/grading`);
                holder.innerHTML = grade.passes.map(pass => {
                    const passNumber = pass.PASS_NUMBER ?? pass.pass_number;
                    const existing = JSON.parse(pass.RECALL_RESULT_JSON ?? pass.recall_result_json ?? "null");
                    return `<form class="account-panel" data-grade-form="${passNumber}" data-admin-dirty="grade-${passNumber}"><h3>Lượt ${passNumber}</h3>
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
                window.DictaLabAdmin.track(holder);
                holder.querySelectorAll("[data-grade-form]").forEach(form => submit(form, async () => {
                    const facts = grade.facts.map(fact => ({factId: fact.id,
                        status: form.elements.namedItem(`status-${fact.id}`).value,
                        explanationVi: form.elements.namedItem(`explanation-${fact.id}`).value}));
                    await bridge().api(`/api/admin/support/tickets/${ticketId}/grading/${form.dataset.gradeForm}`,
                        {method: "PUT", body: {result: JSON.stringify({facts})}});
                    window.DictaLabAdmin.clean(form);
                    status(root, "Đã lưu điểm rà soát. Hãy cập nhật phản hồi cho người học.");
                }));
            } catch (error) { status(root, error.message, true); }
        }));
    }

    window.DictaLabAccount = {show, showPublic, enhancePasswords};
})();
