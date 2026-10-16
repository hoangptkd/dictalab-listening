(() => {
    "use strict";
    const escape = value => String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;").replaceAll('"', "&quot;");
    const money = value => new Intl.NumberFormat("vi-VN", {style: "currency", currency: "VND"}).format(value);
    const date = value => value ? new Date(value).toLocaleString("vi-VN") : "—";
    const labels = {CREATING: "Đang xác minh", PENDING: "Chờ thanh toán", PAID: "Đã thanh toán", EXPIRED: "Hết hạn", CANCELLED: "Đã hủy"};
    const checkout = value => {
        try { const url = new URL(value); return url.protocol === "https:" && url.hostname === "pay.payos.vn"
            && !url.username && !url.password && (!url.port || url.port === "443") ? url.href : null; }
        catch (_) { return null; }
    };

    async function mount(root) {
        const api = (...args) => window.DictaLabListeningBridge.api(...args);
        const section = document.createElement("section");
        section.className = "account-panel billing-panel";
        section.setAttribute("aria-labelledby", "billing-heading");
        root.prepend(section);
        let busy = false;
        let message = "";
        let messageError = false;
        async function render() {
            try {
                const [plan, orders] = await Promise.all([api("/api/billing/status"), api("/api/billing/orders")]);
                if (!root.isConnected) return;
                section.innerHTML = `<div class="billing-summary"><div><p class="section-index">Gói học của bạn</p>
                    <h2 id="billing-heading">${plan.premium ? "Premium đang hoạt động" : "Thêm thời gian cho việc học."}</h2>
                    <p>${plan.administrator ? "Quản trị viên có đầy đủ quyền sử dụng." : plan.premium
                        ? `Có hiệu lực đến ${escape(date(plan.premiumUntil))}. Mua thêm sẽ cộng dồn thời gian.`
                        : "Bắt đầu miễn phí. Nâng cấp khi bạn muốn luyện nhiều hơn."}</p></div>
                    <div class="billing-price"><strong>${escape(money(plan.priceVnd))}</strong><span> / ${escape(plan.durationDays)} ngày</span></div></div>
                    <div class="billing-benefits"><p><strong>Miễn phí</strong><br>${escape(plan.freeDailyLessons)} bài mới/ngày · giữ lịch sử và tiến trình.</p>
                    <p><strong>Premium</strong><br>Luyện không giới hạn · tối đa ${escape(plan.premiumDailyAi)} lượt AI/ngày.</p></div>
                    <p>${plan.enabled ? `Hôm nay: ${escape(plan.lessonsUsed)} bài miễn phí · ${escape(plan.aiUsed)}/${escape(plan.premiumDailyAi)} lượt AI. Hạn mức đặt lại lúc 00:00 giờ Việt Nam.`
                        : "Thanh toán đang được thiết lập. Hiện chưa áp dụng hạn mức học."}</p>
                    <p>Thanh toán QR chuyển khoản qua payOS. Mỗi lần mua có hiệu lực ${escape(plan.durationDays)} ngày; bạn chủ động gia hạn, không tự động trừ tiền.</p>
                    ${plan.administrator ? "" : `<button type="button" class="primary-button" data-buy ${!plan.enabled || busy ? "disabled" : ""}>${plan.premium ? "Gia hạn Premium" : "Mua Premium"}</button>`}
                    <p data-billing-status role="status" aria-live="polite" ${messageError ? 'data-error="true"' : ""}>${escape(message)}</p>
                    ${orders.length ? `<details class="ui-disclosure" ${message ? "open" : ""}><summary>Lịch sử thanh toán (${orders.length} đơn gần nhất)</summary>
                        <ol class="billing-orders">${orders.map(order => `<li><div><strong>Đơn ${escape(order.orderCode)} · ${escape(money(order.amountVnd))}</strong>
                        <p>${escape(labels[order.status] || order.status)} · ${escape(order.durationDays)} ngày · ${escape(date(order.createdAt))}</p></div>
                        <div class="billing-order-actions">${order.status === "PENDING" && checkout(order.checkoutUrl) ? `<a class="text-button" href="${escape(checkout(order.checkoutUrl))}">Mở QR thanh toán</a>` : ""}
                        ${plan.enabled && order.status !== "PAID" ? `<button class="text-button" type="button" data-check="${escape(order.orderCode)}" ${busy ? "disabled" : ""}>Kiểm tra thanh toán</button>` : ""}</div></li>`).join("")}</ol></details>` : ""}`;
                section.querySelector("[data-buy]")?.addEventListener("click", async () => {
                    if (busy) return;
                    busy = true;
                    section.querySelector("[data-buy]").disabled = true;
                    section.querySelector("[data-billing-status]").textContent = "Đang chuẩn bị trang thanh toán…";
                    try {
                        const order = await api("/api/billing/orders", {method: "POST"});
                        const url = checkout(order.checkoutUrl);
                        if (!url) throw new Error("Đơn đang đồng bộ. Hãy kiểm tra trạng thái trước khi tạo đơn mới.");
                        location.assign(url);
                    } catch (error) { message = error.message; messageError = true; busy = false; await render(); }
                });
                section.querySelectorAll("[data-check]").forEach(button => button.addEventListener("click", () => check(button.dataset.check)));
            } catch (error) {
                section.innerHTML = `<h2 id="billing-heading">Gói Premium</h2><p role="alert">${escape(error.message)}</p><button type="button" class="text-button">Thử lại</button>`;
                section.querySelector("button").addEventListener("click", render);
            }
        }
        async function check(code) {
            if (busy) return;
            busy = true;
            section.querySelectorAll("button").forEach(button => { button.disabled = true; });
            try {
                const order = await api(`/api/billing/orders/${encodeURIComponent(code)}/check`, {method: "POST"});
                message = order.status === "PAID" ? "Thanh toán đã xác nhận. Thời hạn Premium đã được cập nhật."
                    : `Trạng thái đơn: ${labels[order.status] || order.status}. Bạn có thể kiểm tra lại sau.`;
                messageError = false;
            } catch (error) { message = error.message; messageError = true; }
            finally { busy = false; await render(); }
        }
        await render();
        const code = new URLSearchParams(location.search).get("orderCode");
        if (code && /^[1-9][0-9]{0,15}$/.test(code)) await check(code);
    }
    window.DictaLabBilling = {mount};
})();
