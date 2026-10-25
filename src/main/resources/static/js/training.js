(() => {
    "use strict";
    const $ = selector => document.querySelector(selector);
    const esc = value => String(value ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
    const finite = value => typeof value === "number" && Number.isFinite(value);
    const number = (value, digits = 0) => finite(value) ? value.toLocaleString("vi-VN", {maximumFractionDigits: digits}) : "—";
    const stamp = value => value ? new Date(value).toLocaleString("vi-VN", {timeZone:"Asia/Bangkok", hour12:false}) : "Chưa ghi nhận";
    const shortHash = value => value ? `${value.slice(0, 10)}…${value.slice(-6)}` : "Chưa có hash";
    const bytes = value => finite(value) ? `${number(value / 1024 ** 3, 1)} GiB` : "—";
    const paths = {
        dashboard:'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
        runs:'M4 6h16 M4 12h16 M4 18h16 M8 3v6 M16 9v6 M8 15v6',
        compare:'M8 3v18 M16 3v18 M4 7l4-4 4 4 M12 17l4 4 4-4',
        dataset:'M3 6c0-4 18-4 18 0s-18 4-18 0v12c0 4 18 4 18 0V6 M3 12c0 4 18 4 18 0',
        checkpoint:'M12 3l8 4v6c0 5-8 8-8 8s-8-3-8-8V7z M8 12l3 3 5-6',
        terminal:'M3 4h18v16H3z M7 9l3 3-3 3 M13 15h4',
        plus:'M12 5v14 M5 12h14', pause:'M8 5v14 M16 5v14',
        play:'M7 4l14 8-14 8z', check:'M4 12l5 5L20 6',
        clock:'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M12 7v5l4 2',
        alert:'M12 3L2 21h20z M12 9v5 M12 17v1',
        refresh:'M20 7v5h-5 M4 17v-5h5 M19 7a8 8 0 0 0-14-1 M5 17a8 8 0 0 0 14 1',
        cpu:'M6 6h12v12H6z M9 9h6v6H9z M9 2v4 M15 2v4 M9 18v4 M15 18v4 M2 9h4 M2 15h4 M18 9h4 M18 15h4',
        bell:'M5 17h14l-2-4V9a5 5 0 0 0-10 0v4z M10 20h4', close:'M6 6l12 12 M18 6L6 18',
    };
    const icon = name => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="${paths[name] || paths.runs}"/></svg>`;
    const statuses = {
        DRAFT:["Nháp",""], STARTING:["Đang tiền kiểm","teal"], RUNNING:["Đang train","teal"],
        PAUSE_REQUESTED:["Đang chờ lưu checkpoint","amber"], STOP_REQUESTED:["Đang chờ kết thúc","amber"],
        PAUSED_BY_USER:["Tạm dừng theo yêu cầu","amber"], STOPPED_BY_USER:["Đã kết thúc theo yêu cầu",""],
        FAILED:["Thất bại","red"], INTERRUPTED:["Bị gián đoạn","red"], SUCCEEDED:["Hoàn tất","green"], UNKNOWN:["Chưa xác minh","amber"],
        WAITING:["Chưa bắt đầu",""], VERIFYING:["Chờ bằng chứng kết thúc","amber"], SMOKE_PASSED:["Smoke đạt · Colab báo cáo","green"], REPORTED_COMPLETE:["Máy train báo hoàn tất","teal"],
    };
    const badge = status => { const [label,tone] = statuses[status] || statuses.UNKNOWN; return `<span class="badge ${tone}">${label}</span>`; };
    const tabs = [["overview","Tổng quan","dashboard"],["sources","Local & Google Colab","cpu"],["runs","Các lần train","runs"],["compare","So sánh","compare"],["datasets","Dataset","dataset"],["checkpoints","Checkpoint","checkpoint"],["resources","Tài nguyên & log","terminal"]];
    const allowedViews = new Set([...tabs.map(t => t[0]), "detail"]);
    const initial = new URLSearchParams(location.search);
    let view = allowedViews.has(initial.get("view")) ? initial.get("view") : "overview";
    let selectedId = initial.get("run") || "", detailTab = "metrics", data = null, detail = null, offline = false;
    let fetching = false, mutation = false, csrf = null, pollTimer, toastTimer, detailSequence = 0, navView = null;
    let filter = "", statusFilter = "all", sourceFilter = "all", compareIds = new Set(), comparisonDetails = new Map(), notices = [], lastStatuses = new Map();
    const remote = run => run?.transport === "https" || run?.source === "colab";
    const sourceName = run => run?.source === "colab" ? `Colab · ${run.phase === "smoke" ? "Smoke 1 bước" : run.phase === "full" ? "Full SFT" : "Chờ kết nối"}` : "Local · Full SFT";
    const connectionName = run => run.connection === "LIVE" ? "Đồng bộ trực tiếp" : run.connection === "STALE" ? "Snapshot cũ · mất cập nhật" : "Chưa nhận snapshot";
    const main = $("#studio-main"), dialog = $("#studio-dialog");
    const mobileMenu = matchMedia("(max-width:1023px)");
    function setMenu(open) {
        const sidebar=$("#studio-sidebar");
        sidebar.classList.toggle("open",open);
        sidebar.inert=mobileMenu.matches&&!open;
        $("#menu-toggle").setAttribute("aria-expanded",String(open));
    }
    const current = () => detail?.id === selectedId ? detail : data?.runs.find(r => r.id === selectedId);
    const verified = run => run?.checkpoints?.filter(c => c.verified) || [];
    const percent = run => finite(run?.plannedSteps) && run.plannedSteps > 0 ? Math.min(100, run.step / run.plannedSteps * 100) : 0;

    async function refreshCsrf() {
        const response = await fetch("/api/auth/csrf", {credentials:"same-origin",cache:"no-store",signal:AbortSignal.timeout(10000)});
        if (!response.ok) throw new Error("Không lấy được mã bảo vệ phiên đăng nhập.");
        csrf = await response.json();
    }
    async function request(path, options = {}, retried = false) {
        const isMutation = options.method && options.method !== "GET";
        if (isMutation && !csrf) await refreshCsrf();
        const response = await fetch(path, {...options, credentials:"same-origin",cache:"no-store",signal:AbortSignal.timeout(55000),
            headers:{...(options.body ? {"Content-Type":"application/json"} : {}),...(isMutation ? {[csrf.headerName]:csrf.token} : {})}});
        if (response.status === 401) {
            // Rotate cookies using the same cross-tab lock as the existing DictaLab application.
            if (!retried && navigator.locks) {
                await navigator.locks.request("dictalab-token-refresh", async () => {
                    const probe = await fetch("/api/auth/me", {credentials:"same-origin",cache:"no-store"});
                    if (probe.ok) return;
                    await refreshCsrf();
                    const refreshed = await fetch("/api/auth/refresh", {method:"POST",credentials:"same-origin",headers:{[csrf.headerName]:csrf.token},signal:AbortSignal.timeout(10000)});
                    if (!refreshed.ok) throw new Error("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
                    csrf = null;
                });
                return request(path, options, true);
            }
            throw new Error("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
        }
        if (isMutation && response.status === 403 && response.headers.get("X-CSRF-Error") === "1" && !retried) {
            csrf = null; return request(path, options, true);
        }
        const payload = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(payload.message || (response.status === 403 ? "Chỉ quản trị viên được truy cập Training Studio." : "Không kết nối được máy train."));
        return payload;
    }
    function toast(message) { $("#toast").textContent = message; $("#toast").hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => $("#toast").hidden = true, 6500); }
    function navigate(next, id = selectedId, replace = false) {
        view = next; selectedId = id || "";
        const query = new URLSearchParams({view}); if (selectedId) query.set("run",selectedId);
        history[replace ? "replaceState" : "pushState"]({}, "", `/admin/training?${query}`);
        setMenu(false);
        render(); if (selectedId && next !== "runs") loadDetail();
    }
    function renderNav() {
        if(navView!==view){navView=view;$("#studio-nav").innerHTML = tabs.map(([id,label,glyph]) => `<a class="nav-link" href="/admin/training?view=${id}" data-view="${id}" ${view === id || view === "detail" && id === "runs" ? 'aria-current="page"' : ""}>${icon(glyph)}${label}</a>`).join("");}
        $("#notifications-toggle").innerHTML = icon("bell") + (notices.length ? `<span id="notification-count">${notices.length}</span>` : "");
    }
    function heading(title, description, create = false) {
        return `<div class="page-heading"><div><h1>${esc(title)}</h1><p>${esc(description)}</p></div><div class="actions"><button type="button" data-action="refresh">${icon("refresh")}Cập nhật</button>${create ? `<button type="button" data-action="connect-colab" ${offline || !data?.capabilities?.colab ? "disabled" : ""}>Kết nối máy train</button><button type="button" class="primary" data-action="create" ${offline || !data?.capabilities?.create ? "disabled" : ""}>${icon("plus")}Tạo lần train local</button>` : ""}</div></div>`;
    }
    function stat(label, value, glyph, tone = "") { return `<div class="stat ${tone}"><div><span class="label">${label}</span><strong>${value}</strong></div>${icon(glyph)}</div>`; }
    function runControls(run) {
        if (!run) return "";
        if (remote(run)) return `<a class="text-link" href="${esc(run.notebookUrl || "/admin/training?view=sources")}" target="_blank" rel="noopener noreferrer">${run.source === "colab" ? "Mở notebook ↗" : "Nguồn HTTPS ↗"}</a><span class="badge ${run.connected ? "teal" : "amber"}">${esc(connectionName(run))}</span>`;
        const locked = offline || mutation || (data.externalWorkerCount>0&&!run.active);
        if (run.canPause) return `<button type="button" data-control="pause" data-id="${run.id}" ${locked ? "disabled" : ""}>${icon("pause")}Tạm dừng an toàn</button><button type="button" class="danger" data-control="stop" data-id="${run.id}" ${locked ? "disabled" : ""}>Kết thúc lần train</button>`;
        if (run.active) return `<span class="badge amber">${esc(statuses[run.status]?.[0] || "Đang xử lý")}</span>`;
        if (run.canResume) return `<button type="button" class="primary" data-control="resume" data-id="${run.id}" ${locked ? "disabled" : ""}>${icon("play")}Tiếp tục từ checkpoint</button>`;
        if (run.canStart) return `<button type="button" class="primary" data-control="start" data-id="${run.id}" ${locked ? "disabled" : ""}>${icon("play")}Bắt đầu train</button>`;
        return `<button type="button" data-action="clone" data-id="${run.id}" ${locked ? "disabled" : ""}>Dùng cấu hình để tạo mới</button>`;
    }
    function runBanner(run) {
        if (remote(run)) return `<div class="banner">${icon(run.connected ? "check" : "alert")}<div><strong>${esc(sourceName(run))} · ${esc(connectionName(run))}</strong><p>${run.phase === "smoke" ? "Smoke chỉ kiểm chứng một bước cập nhật LoRA. Chưa hoàn tất train đầy đủ 200 bước; không dùng loss smoke để xếp hạng model." : "Tiến độ do máy train gửi. Dashboard chưa điều khiển từ xa và không tự chuyển môi trường."} ${run.reportedStatus === "REPORTED_COMPLETE" ? "Máy train báo hoàn tất; máy chủ chưa nhận adapter/state để xác minh độc lập." : ""}</p><small class="muted">Snapshot: ${stamp(run.observedAt)} · Máy chủ nhận: ${stamp(run.receivedAt)} · UTC+7</small>${run.error ? `<p>${esc(run.error)}</p>` : ""}</div></div>`;
        if (!run || ["DRAFT","SUCCEEDED"].includes(run.status)) return "";
        let title = statuses[run.status]?.[0] || "Trạng thái chưa xác minh";
        let message = run.error || "";
        if (run.status === "PAUSED_BY_USER") message = verified(run).length ? "Đã giữ checkpoint hợp lệ. Có thể tiếp tục với cấu hình và dữ liệu gốc." : "Chưa xác minh được checkpoint để tiếp tục.";
        if (run.status === "PAUSE_REQUESTED" || run.status === "STOP_REQUESTED") message = "Worker vẫn đang tính. Hệ thống sẽ hoàn tất optimizer step, lưu checkpoint đầy đủ rồi mới dừng. CPU hiện tại từng cần khoảng 20–30 phút/step.";
        if (run.status === "RUNNING") message = "Đang chạy thật. Metrics cập nhật sau optimizer step; log ít đổi không có nghĩa là worker đã dừng.";
        return `<div class="banner ${["FAILED","INTERRUPTED"].includes(run.status) ? "error" : ""}">${icon(run.status === "RUNNING" ? "cpu" : "alert")}<div><strong>${esc(title)}</strong><p>${esc(message)}</p>${run.pausedAt ? `<small class="muted">Tạm dừng: ${stamp(run.pausedAt)} · UTC+7</small>` : ""}</div></div>`;
    }
    function metrics(run) {
        const m = run.metrics || {};
        return `<div class="metrics"><div class="metric"><span class="label">${run.phase === "smoke" ? "SMOKE · OPTIMIZER STEP" : "OPTIMIZER STEP"}</span><strong class="value">${number(run.step)} / ${run.plannedSteps ? number(run.plannedSteps) : "—"}</strong><progress aria-label="Tiến độ optimizer step" max="100" value="${percent(run)}"></progress><small>${number(percent(run),1)}% ${run.phase === "smoke" ? "smoke" : ""} · ${number(m.epoch,4)} epoch</small></div>
        <div class="metric"><span class="label">TRAINING LOSS</span><strong class="value">${number(m.loss,4)}</strong><small>Grad norm: ${number(m.grad_norm,4)}</small></div>
        <div class="metric"><span class="label">VALIDATION LOSS</span><strong class="value">${number(run.validation?.eval_loss,4)}</strong><small>${finite(run.validation?.eval_loss) ? "Đánh giá trên validation" : run.phase === "smoke" ? "Không đánh giá validation trong smoke" : "Chưa đánh giá · thực hiện cuối epoch"}</small></div>
        <div class="metric"><span class="label">LEARNING RATE</span><strong class="value">${finite(m.learning_rate) ? esc(m.learning_rate.toExponential(1)) : "—"}</strong><small>${m.learning_rate === 0 ? "Warmup bước đầu" : "LR đã ghi nhận"} · ETA: ${run.active ? "chưa đủ dữ liệu" : "không áp dụng"}</small></div></div>`;
    }
    function chart(series, title = "Training loss") {
        const valid = series.map(s => ({...s,points:s.points.filter(p => finite(p.step) && finite(p.loss))}));
        const all = valid.flatMap(s => s.points);
        if (!all.length) return `<div class="empty">${icon("runs")}<h3>${esc(title)}</h3><p>Chưa có điểm đo loss và step đã ghi nhận.</p></div>`;
        const maxX = Math.max(2,...all.map(p => p.step)), minY = Math.max(0,Math.min(...all.map(p => p.loss)) - .15), maxY = Math.max(...all.map(p => p.loss)) + .15;
        const x = value => 54 + value / maxX * 490, y = value => 205 - (value-minY)/(maxY-minY)*175;
        const lines = [0,.5,1].map(t => {const value=minY+(maxY-minY)*t,py=y(value);return `<line class="grid" x1="54" x2="550" y1="${py}" y2="${py}"/><text x="44" y="${py+4}" text-anchor="end">${number(value,2)}</text>`;}).join("");
        return `<svg class="chart" viewBox="0 0 600 250" role="img" aria-label="${esc(title)}: ${all.length} điểm đo; không suy diễn dữ liệu thiếu"><title>${esc(title)}</title>${lines}<text x="54" y="233">Step 0</text><text x="550" y="233" text-anchor="end">Step ${maxX}</text>${valid.map((s,i) => `${s.points.length>1 ? `<polyline class="line" style="stroke:${s.color || "var(--teal)"};${i % 2 ? "stroke-dasharray:5 4" : ""}" points="${s.points.map(p => `${x(p.step)},${y(p.loss)}`).join(" ")}"/>` : ""}${s.points.map(p=>`<circle class="point" style="stroke:${s.color || "var(--teal)"}" cx="${x(p.step)}" cy="${y(p.loss)}" r="4"><title>${esc(s.name)} · step ${p.step}: ${p.loss}</title></circle>`).join("")}`).join("")}</svg><p class="chart-caption">${all.length === 1 ? `1 điểm đo · step ${all[0].step}: ${number(all[0].loss,6)}. Chưa đủ dữ liệu để xác định xu hướng.` : `${all.length} điểm có step được ghi nhận. Không nội suy giá trị còn thiếu.`}</p>`;
    }
    function historyPoints(run) { return (run.history || [run.metrics]).filter(Boolean).map(m=>({step:m.step,loss:m.loss})); }
    function recovery(run) {
        if (remote(run)) return `<div class="checkpoint-summary"><h3 class="checkpoint-title">${icon("checkpoint")}Bằng chứng máy gửi</h3><dl class="kv"><dt>Optimizer cập nhật thật</dt><dd>${number(run.optimizerEvidence?.actualOptimizerUpdates)}</dd><dt>Adapter tensors đã đổi</dt><dd>${number(run.optimizerEvidence?.changedAdapterTensors)}</dd><dt>Gradient hữu hạn</dt><dd>${run.optimizerEvidence?.finiteLoggedGradients === true ? "Có · máy gửi báo cáo" : "Chưa xác nhận"}</dd><dt>Adapter SHA-256</dt><dd class="mono">${esc(shortHash(run.artifacts?.adapterHash))}</dd></dl><p class="notice">Checkpoint được hiển thị theo bằng chứng từ máy gửi. Máy chủ chưa có file để xác minh native. Không resume trực tiếp giữa CPU float32 và CUDA FP16.</p>${run.source==="colab" ? '<p class="notice">/content là vùng tạm; lưu notebook trên Drive không tự sao lưu checkpoint.</p>' : '<p class="notice">Checkpoint giữ trên máy local. Publisher không truyền file model hoặc state.</p>'}<button type="button" class="text-link" data-view="checkpoints" data-id="${run.id}">Xem bằng chứng checkpoint</button></div>`;
        const checkpoint = verified(run)[0];
        return `<div class="checkpoint-summary"><h3 class="checkpoint-title">${icon("checkpoint")}Khôi phục an toàn</h3>${checkpoint ? `<p class="checkpoint-id">${esc(checkpoint.name)}</p><span class="badge green">Hash SHA-256 và state khớp</span><ul>${["Adapter LoRA","Optimizer & scheduler","RNG & trainer state","Cấu hình và dataset gốc"].map(s=>`<li>${icon("check")}${s}</li>`).join("")}</ul><button type="button" class="text-link" data-view="checkpoints" data-id="${run.id}">Xem checkpoint và hash</button>` : `<p class="muted">Chưa có checkpoint hoàn chỉnh đã xác minh.</p>`}<p class="notice">Lưu sau mỗi optimizer step. Tạm dừng mới sẽ chờ lưu xong checkpoint.</p></div>`;
    }
    function runPanel(run) {
        if (!run) return `<section class="panel"><div class="empty"><h2>Chưa có lần train</h2><p>Tạo cấu hình từ model và dataset đã kiểm chứng để bắt đầu.</p></div></section>`;
        return `<section class="panel"><div class="panel-head"><div><div class="run-title"><h2>${esc(run.model?.split("/").pop())} · ${esc(run.name)}</h2>${badge(run.status)}</div><div class="run-meta"><span>${esc(run.device)} · ${esc(run.dtype)} · ${esc(run.method.toUpperCase())}</span><span class="badge">${esc(sourceName(run))}${run.gpu ? ` · ${esc(run.gpu)}` : ""}</span><span class="mono" title="${esc(run.runId)}">${esc(run.runId)}</span></div></div><div class="actions">${runControls(run)}</div></div>${metrics(run)}<div class="analysis-grid"><div class="chart-panel"><div class="chart-head"><h3>Training loss theo step</h3><button type="button" class="text-link" data-view="detail" data-id="${run.id}">Xem chi tiết</button></div>${chart([{name:run.name,points:historyPoints(run)}])}</div>${recovery(run)}</div></section>`;
    }
    function runsTable(runs) {
        return `<div class="table-scroll" tabindex="0" aria-label="Lịch sử train, cuộn ngang để xem thêm cột"><table class="runs-table"><thead><tr><th scope="col"><span class="small">Chọn</span></th><th scope="col">Lần train / Model</th><th scope="col">Trạng thái</th><th scope="col">Step</th><th scope="col">Training loss</th><th scope="col">Checkpoint</th><th scope="col">Bắt đầu · UTC+7</th></tr></thead><tbody>${runs.map(run=>`<tr class="${compareIds.has(run.id) ? "selected" : ""}"><td><input class="checkbox" type="checkbox" data-compare="${run.id}" aria-label="Chọn ${esc(run.name)} để so sánh" ${compareIds.has(run.id)?"checked":""}></td><td><button class="table-button" type="button" data-view="detail" data-id="${run.id}">${esc(run.name)}</button><span class="secondary">${esc(run.model)} · ${esc(sourceName(run))}</span></td><td>${badge(run.status)}${run.error ? `<span class="secondary">${esc(run.error)}</span>` : ""}</td><td>${number(run.step)} / ${run.plannedSteps ? number(run.plannedSteps) : "—"}</td><td>${number(run.metrics?.loss,4)}</td><td>${verified(run)[0] ? `<span class="teal">${esc(verified(run)[0].name)}</span><span class="secondary">Đã xác minh</span>` : remote(run) && run.checkpoints?.length ? `<span>${esc(run.checkpoints[0].name)}</span><span class="secondary">Máy gửi báo cáo · chưa xác minh</span>` : "Chưa có"}</td><td>${run.startedAt ? esc(stamp(run.startedAt)) : "Chưa chạy"}</td></tr>`).join("") || '<tr><td colspan="7"><div class="empty">Không có lần train phù hợp bộ lọc.</div></td></tr>'}</tbody></table></div>`;
    }
    function datasetCard(dataset) {
        if (!dataset) return `<p class="muted">Chưa có metadata dataset.</p>`;
        const counts = dataset.records || {};
        return `<h3>${esc(dataset.name)}</h3><span class="badge ${dataset.approved ? "green" : "amber"}">${dataset.approved ? "Phê duyệt khớp hash" : dataset.reportedApproved ? "Máy gửi báo approval khớp hash" : "Chưa xác minh phê duyệt"}</span><p class="notice">${dataset.source==="https" ? "Theo metadata máy gửi · " : ""}${number(dataset.articles)} bài · ${number(Object.values(counts).reduce((s,n)=>s+(finite(n)?n:0),0))} bản ghi (nội dung + bản dịch)</p><p class="mono" title="${esc(dataset.hash)}">${esc(shortHash(dataset.hash))}</p><div class="split-values">${["train","validation","test"].map(s=>`<div>${s}<b>${number(counts[s])}</b>bản ghi</div>`).join("")}</div>`;
    }
    function sourceCards() {
        const colab=data.runs.filter(r=>r.source==="colab"), live=colab.filter(r=>r.connected).length, localLive=data.runs.filter(r=>r.source==="local"&&remote(r)&&r.connected).length;
        return `<div class="source-grid"><section class="source-card"><div class="run-title">${icon("cpu")}<h2>Máy local</h2><span class="badge ${data.localConnected === false ? "amber" : "teal"}">${data.localConnected === false ? localLive ? `${localLive} nguồn HTTPS trực tiếp` : "Chưa kết nối" : "Đã kết nối runtime"}</span></div><p>CPU · LoRA float32 · checkpoint sau mỗi step</p><small>${data.localConnected === false ? localLive ? "Tiến độ CPU được publisher gửi qua HTTPS; điều khiển trên máy local." : "Máy chủ hiện tại không truy cập runtime CPU của bạn." : `${number(data.runs.filter(r=>!remote(r)&&r.active).length)} worker đang chạy · ${number(data.runs.filter(r=>!remote(r)&&r.status==="PAUSED_BY_USER").length)} phiên tạm dừng`}</small></section><section class="source-card"><div class="run-title">${icon("cpu")}<h2>Google Colab</h2><span class="badge ${live ? "teal" : "amber"}">${live ? `${live} kết nối đang cập nhật` : "Chưa đồng bộ trực tiếp"}</span></div><p>GPU của notebook · metrics qua HTTPS · polling 15 giây</p><small>${number(colab.length)} phiên đã đăng ký · mỗi kết nối dùng khóa riêng, không phụ thuộc Gmail đăng nhập dashboard</small></section></div>`;
    }
    function sourcesView() {
        const colab=data.runs.filter(remote);
        return heading("Local & Google Colab","Theo dõi hai môi trường trong cùng dashboard; cấu hình và checkpoint giữ riêng.",true)+sourceCards()+`<section class="panel"><div class="panel-body"><h2>Kết nối HTTPS có xác thực</h2><ol class="setup-steps"><li>Đăng ký một nguồn Local hoặc Colab. Máy chủ cấp khóa chỉ gửi metadata cho đúng phiên đó, hết hạn sau 7 ngày.</li><li><a href="/admin/colab_reporter.py" download="colab_reporter.py">Tải colab_reporter.py</a>, tải file lên Colab và chạy ô kết nối được cung cấp. Khóa nhập bằng getpass; không ghi vào notebook.</li><li>Reporter gửi step/loss/cấu hình/hash và số đo GPU/RAM mỗi 15 giây. Không gửi nội dung dataset hoặc raw log.</li><li>Quá 90 giây không nhận snapshot, phiên chuyển sang mất cập nhật. Thu hồi khóa chỉ ngắt đồng bộ, không dừng trainer.</li></ol><p class="notice">Tài khoản Colab và Drive có thể khác Gmail quản trị. Dashboard chỉ cấp khóa theo từng phiên, không yêu cầu chia sẻ hoặc đăng nhập Google vào DictaLab. Checkpoint cần được notebook sao lưu vào Drive; dashboard chỉ nhận metadata.</p></div></section><section class="panel"><div class="panel-head"><h2>Các nguồn qua HTTPS</h2></div><div class="panel-body">${colab.map(r=>`<div class="connection-row"><div><h3>${esc(r.name)}</h3><p>${esc(sourceName(r))} · ${esc(connectionName(r))}</p><small>Nhận gần nhất: ${stamp(r.receivedAt)} · Khóa hết hạn: ${stamp(r.expiresAt)}</small></div><div class="actions"><button type="button" data-view="detail" data-id="${r.id}">Xem phiên</button><button type="button" data-action="revoke-colab" data-id="${r.id}" ${r.revoked || offline ? "disabled" : ""}>${r.revoked ? "Đã thu hồi" : "Thu hồi khóa"}</button></div></div>`).join("") || '<p class="muted">Chưa đăng ký Colab. Kết nối chỉ gửi thông tin theo dõi, không khởi chạy train.</p>'}</div></section>`;
    }
    function overview() {
        const runs = data.runs, run = current(), resources = data.resources;
        return heading("Tổng quan train","Theo dõi SFT/LoRA, kiểm tra tiến độ và khôi phục từ checkpoint đã xác minh.",true)
            + `<div class="stats">${stat("Tổng lần train",number(runs.length),"runs")}${stat("Đang chạy",number(runs.filter(r=>r.active).length),"cpu")}${stat("Tạm dừng",number(runs.filter(r=>r.status==="PAUSED_BY_USER").length),"pause","attention")}${stat("Checkpoint đã xác minh",number(new Set(runs.flatMap(r=>verified(r).map(c=>`${r.runId}/${c.name}`))).size),"checkpoint","verified")}</div>`
            + sourceCards() + runBanner(run) + runPanel(run)
            + `<section class="panel"><div class="panel-head"><div><h2>Lịch sử các lần train</h2><p class="muted small">Chọn ít nhất 2 lần để so sánh. Khác số step không chứng minh model nào tốt hơn.</p></div><button data-action="compare" type="button" ${compareIds.size<2?"disabled":""}>${icon("compare")}So sánh (${compareIds.size})</button></div>${runsTable(runs.slice(0,20))}</section>`
            + `<div class="support-grid"><section class="panel"><div class="panel-body">${datasetCard(data.datasets.find(d=>d.hash===run?.datasetHash)||data.datasets[0])}<button class="text-link" type="button" data-view="datasets">Xem audit & phân bổ</button></div></section><section class="panel"><div class="panel-body"><h3>Model & cấu hình</h3>${run ? `<dl class="kv"><dt>Model</dt><dd>${esc(run.model)}</dd><dt>Revision</dt><dd class="mono">${esc(shortHash(run.revision))}</dd><dt>Thiết bị</dt><dd>${esc(run.device)} · ${esc(run.dtype)}</dd><dt>Mask</dt><dd>Last assistant only</dd></dl><button class="text-link" type="button" data-action="config" data-id="${run.id}">Xem toàn bộ cấu hình</button>` : "Chưa có cấu hình"}</div></section><section class="panel"><div class="panel-body"><h3>Tài nguyên máy local</h3><dl class="kv"><dt>CPU máy</dt><dd>${number(resources.cpuPercent,1)}%</dd><dt>RAM máy</dt><dd>${number(resources.ramPercent,1)}%</dd><dt>RAM còn trống</dt><dd>${bytes(resources.ramAvailable)}</dd><dt>Ổ đĩa trống</dt><dd>${bytes(resources.diskFree)}</dd></dl><p class="notice">${esc(resources.scope)}</p><button class="text-link" type="button" data-view="resources">Tài nguyên & log</button></div></section></div>`;
    }
    function listView() {
        const runs=data.runs.filter(r=>(!filter||`${r.name} ${r.model} ${r.runId}`.toLowerCase().includes(filter.toLowerCase()))&&(statusFilter==="all"||r.status===statusFilter)&&(sourceFilter==="all"||(r.source||"local")===sourceFilter));
        return heading("Các lần train","Tìm theo model, kiểm tra trạng thái và mở chi tiết từng lần chạy.",true)+`<section class="panel"><div class="toolbar"><div class="field search"><label for="run-search">Tìm lần train</label><input id="run-search" placeholder="Tên, model hoặc run ID…" value="${esc(filter)}"></div><div class="field"><label for="source-filter">Môi trường</label><select id="source-filter"><option value="all">Tất cả</option><option value="local" ${sourceFilter==="local"?"selected":""}>Local</option><option value="colab" ${sourceFilter==="colab"?"selected":""}>Google Colab</option></select></div><div class="field"><label for="status-filter">Trạng thái</label><select id="status-filter"><option value="all">Tất cả</option>${Object.entries(statuses).map(([s,[label]])=>`<option value="${s}" ${s===statusFilter?"selected":""}>${label}</option>`).join("")}</select></div><div class="field"><button type="button" data-action="compare" ${compareIds.size<2?"disabled":""}>So sánh (${compareIds.size})</button></div></div>${runsTable(runs)}</section>`;
    }
    function configView(run) {
        if (!run.config) return '<div class="panel-body muted">Đang đọc cấu hình…</div>';
        const c=run.config,t=c.training||{},l=c.lora||{};
        const kv=items=>`<dl class="kv">${items.map(([k,v])=>`<dt>${esc(k)}</dt><dd>${esc(v ?? "—")}</dd>`).join("")}</dl>`;
        return `${run.configBound?"":"<div class=\"banner\"><div><strong>Chưa ràng buộc được cấu hình gốc của phiên này</strong><p>File cấu hình đã thay đổi sau lần chạy. Các tham số dưới đây thuộc file tham chiếu hiện tại, chưa được xác minh là cấu hình đã dùng khi train.</p></div></div>"}<div class="panel-body config-grid"><section><h3>Model & dữ liệu</h3>${kv([["Model",c.base_model],["Revision",c.base_revision],["Dataset hash",run.datasetHash],["Config hash",run.configHash],["Thiết bị",c.device],["Compute dtype",c.quantization?.compute_dtype],["Quantization 4-bit",c.quantization?.load_in_4bit===undefined ? "Chưa ghi nhận" : c.quantization.load_in_4bit ? "Có" : "Không"],["Max sequence",c.max_seq_length],["Seed",c.seed],["Loss mask",c.loss_mask_policy],["Packing",String(c.packing)],["Hidden thinking",String(c.enable_thinking)]])}</section><section><h3>LoRA & tối ưu</h3>${kv([["Rank / alpha",`${l.r} / ${l.alpha}`],["Dropout",l.dropout],["Epochs",t.epochs],["Batch / tích lũy",`${t.per_device_batch_size} / ${t.gradient_accumulation_steps}`],["Learning rate",t.learning_rate],["Warmup ratio",t.warmup_ratio],["Scheduler",t.scheduler],["Gradient checkpointing",String(t.gradient_checkpointing)],["Lưu checkpoint",`Mỗi ${t.checkpoint_steps||"epoch"} step`]])}</section><details class="full-config"><summary>JSON cấu hình kỹ thuật</summary><pre class="log" tabindex="0">${esc(JSON.stringify(c,null,2))}</pre></details></div>`;
    }
    function checkpointView(run) {
        if (remote(run)) return `<div class="panel-body"><h3>Checkpoint tại máy gửi</h3><p class="notice">${esc(sourceName(run))} · Máy chủ chỉ nhận metadata. Kiểm tra hash ở máy gửi không thay thế xác minh file/state trên máy nhận; không bật resume local.</p>${run.checkpoints?.length ? run.checkpoints.map(c=>`<section class="panel"><div class="panel-body"><h3>${esc(c.name)} · step ${number(c.step)}</h3><span class="badge amber">${c.integrityReported ? "Máy gửi báo hash/state khớp" : "Máy gửi chưa xác nhận toàn vẹn"}</span><p class="notice">${number(c.fileCount)} file trong inventory · Chưa truyền file checkpoint tới máy chủ</p></div></section>`).join("") : '<p>Chưa có checkpoint chứa adapter, optimizer, scheduler, RNG và trainer state được báo cáo. Adapter smoke không đủ để resume full SFT.</p>'}<dl class="kv"><dt>Final adapter SHA-256</dt><dd class="mono">${esc(run.artifacts?.adapterHash || "Chưa có")}</dd><dt>Adapter config SHA-256</dt><dd class="mono">${esc(run.artifacts?.adapterConfigHash || "Chưa có")}</dd></dl><p class="notice">Muốn chuyển GPU → CPU cần tải checkpoint, xác minh tính tương thích/config/environment trước. Hệ thống hiện không tự chuyển hoặc gộp state.</p></div>`;
        return `<div class="panel-body">${run.checkpoints?.length ? run.checkpoints.map(c=>`<section class="panel"><div class="panel-head"><div><h3>${esc(c.name)} · optimizer step ${c.step}</h3><span class="badge ${c.verified ? "green" : "amber"}">${c.verified ? "Đã xác minh SHA-256 và state" : "Chưa xác minh toàn vẹn"}</span></div>${c.name===verified(run)[0]?.name?runControls(run):""}</div>${c.error?`<p class="panel-body">${esc(c.error)}</p>`:""}${c.files ? `<div class="table-scroll"><table><thead><tr><th>Tệp</th><th>Dung lượng</th><th>SHA-256</th></tr></thead><tbody>${c.files.map(f=>`<tr><td>${esc(f.name)}</td><td>${number(f.bytes/1024**2,2)} MiB</td><td class="mono">${esc(f.sha256)}</td></tr>`).join("")}</tbody></table></div>`:""}</section>`).join("") : '<div class="empty"><h3>Chưa có checkpoint hoàn chỉnh</h3><p>Step ghi trong log không thay thế adapter, optimizer, scheduler, RNG và trainer state.</p></div>'}<p class="notice">Resume dùng checkpoint hợp lệ mới nhất thuộc cùng output và khớp config/data/review/environment. Không dùng checkpoint của lần khác.</p></div>`;
    }
    function detailView() {
        const run=current(); if(!run)return heading("Chi tiết lần train","Chọn một lần train từ danh sách.")+`<button data-view="runs" type="button">Mở danh sách</button>`;
        const content=detailTab==="config"?configView(run):detailTab==="checkpoints"?checkpointView(run):detailTab==="logs"?`<pre class="log" tabindex="0" aria-label="Log trainer đã lọc thông tin nhạy cảm">${esc(run.log||"Chưa có log. Đang đọc dữ liệu…")}</pre>`:detailTab==="dataset"?`<div class="panel-body">${datasetCard(data.datasets.find(d=>d.hash===run.datasetHash))}<p class="notice">Test chỉ kiểm tra tính toàn vẹn, không tokenize cho train và không chọn checkpoint.</p></div>`:`${metrics(run)}<div class="analysis-grid"><div class="chart-panel">${chart([{name:run.name,points:historyPoints(run)}])}<dl class="kv"><dt>Token accuracy nội bộ</dt><dd>${finite(run.metrics?.mean_token_accuracy)?number(run.metrics.mean_token_accuracy*100,2)+"%":"—"}</dd><dt>Entropy</dt><dd>${number(run.metrics?.entropy,4)}</dd><dt>Token đã ghi nhận</dt><dd>${number(run.metrics?.num_tokens)}</dd></dl><p class="notice">Token accuracy không đại diện cho chất lượng tổng thể model.</p></div>${recovery(run)}</div>`;
        return heading(run.name,`${run.model} · ${run.runId}`)+runBanner(run)+`<section class="panel"><div class="panel-head"><div class="run-title">${badge(run.status)}<span class="muted small">Bắt đầu: ${stamp(run.startedAt)} · UTC+7</span></div><div class="actions">${runControls(run)}</div></div><div class="tabs" role="tablist" aria-label="Thông tin lần train">${[["metrics","Metrics"],["config","Cấu hình"],["checkpoints","Checkpoint"],["dataset","Dataset"],["logs","Log & sự kiện"]].map(([id,label])=>`<button role="tab" id="run-tab-${id}" aria-controls="run-tab-panel" tabindex="${detailTab===id?0:-1}" type="button" data-tab="${id}" aria-selected="${detailTab===id}">${label}</button>`).join("")}</div><div role="tabpanel" id="run-tab-panel" aria-labelledby="run-tab-${detailTab}">${content}</div></section>`;
    }
    function compareView() {
        const runs=data.runs.filter(r=>compareIds.has(r.id)).map(r=>comparisonDetails.get(r.id)||r).map(r=>r.configBound?r:{...r,config:undefined});const row=(name,fn)=>`<tr><th scope="row">${name}</th>${runs.map(r=>`<td>${fn(r)}</td>`).join("")}</tr>`;
        const configRows=row("Môi trường / giai đoạn",r=>esc(sourceName(r)))+row("Thiết bị / dtype",r=>`${esc(r.device)} / ${esc(r.dtype)}`)
            +row("LoRA rank / alpha",r=>`${number(r.config?.lora?.r)} / ${number(r.config?.lora?.alpha)}`)
            +row("LoRA dropout",r=>number(r.config?.lora?.dropout,3))
            +row("Epochs",r=>number(r.config?.training?.epochs,2))
            +row("Batch / tích lũy",r=>`${number(r.config?.training?.per_device_batch_size)} / ${number(r.config?.training?.gradient_accumulation_steps)}`)
            +row("Learning rate gốc",r=>finite(r.config?.training?.learning_rate)?esc(r.config.training.learning_rate.toExponential(1)):"—")
            +row("Max sequence / seed",r=>`${number(r.config?.max_seq_length)} / ${number(r.config?.seed)}`);
        return heading("So sánh các lần train","Đối chiếu cấu hình, tiến độ và các chỉ số có bằng chứng.")+ (runs.length<2?`<section class="panel"><div class="empty">${icon("compare")}<h2>Chọn ít nhất 2 lần train</h2><p>Chọn trong danh sách để xem cùng nhau. Không tự xếp hạng khi thiếu validation.</p><button data-view="runs" type="button">Chọn lần train</button></div></section>`:`<div class="banner">${icon("alert")}<div><strong>Đọc metrics cùng số step và dataset</strong><p>Training loss khác nhau ở số step khác nhau chưa chứng minh chất lượng model. Chỉ chọn best checkpoint bằng validation.</p></div></div><section class="panel"><div class="table-scroll"><table><thead><tr><th>Thông tin</th>${runs.map(r=>`<th>${esc(r.name)}</th>`).join("")}</tr></thead><tbody>${row("Model",r=>esc(r.model))}${row("Trạng thái",r=>badge(r.status))}${row("Optimizer step",r=>`${number(r.step)} / ${number(r.plannedSteps)}`)}${row("Training loss",r=>number(r.metrics?.loss,4))}${row("Validation loss",r=>finite(r.validation?.eval_loss)?number(r.validation.eval_loss,4):"Chưa đánh giá")}${row("Dataset hash",r=>esc(shortHash(r.datasetHash)))}${row("Cấu hình hash",r=>r.configBound?esc(shortHash(r.configHash)):"Chưa xác minh hash gốc")}${row("Checkpoint",r=>esc(verified(r)[0]?.name||(remote(r)&&r.checkpoints?.length ? `${r.checkpoints[0].name} · máy gửi báo cáo` : "Chưa có")))}${configRows}</tbody></table></div></section><section class="panel"><div class="panel-body"><h2>Training loss theo step</h2>${chart(runs.map((r,i)=>({name:r.name,points:historyPoints(r),color:["#42d3b0","#bddfd7","#a9bbc8","#8eaba5"][i%4]})))}<div class="legend">${runs.map((r,i)=>`<span style="--series:${["#42d3b0","#bddfd7","#a9bbc8","#8eaba5"][i%4]}">${esc(r.name)}</span>`).join("")}</div></div></section>`);
    }
    function datasetsView() {
        return heading("Dataset & bằng chứng","Snapshot, split, reviewer và hash gốc được giữ nguyên.")+data.datasets.map(d=>`<section class="panel"><div class="panel-body">${datasetCard(d)}<p class="mono">SHA-256: ${esc(d.hash)}</p><div class="config-grid"><div><h3>Kiểm tra dữ liệu</h3><dl class="kv"><dt>Final audit</dt><dd>${d.auditPassed?"Đạt":"Chưa xác minh"}</dd><dt>Holdout</dt><dd>${d.holdoutApproved?"Đạt":d.reportedHoldout?"Máy gửi báo đạt":"Chưa xác minh"}</dd><dt>Near duplicate</dt><dd>${d.nearDuplicateApproved?"Đạt":d.reportedNearDuplicate?"Máy gửi báo đạt":"Chưa xác minh"}</dd><dt>Human approved</dt><dd>${d.humanApproved?"Có":"Không"}</dd>${Object.entries(d.reviewers).map(([k,v])=>`<dt>${esc(k)}</dt><dd>${number(v)} bài</dd>`).join("")}</dl></div><div><h3>Phân bổ bài theo trình độ</h3>${Object.keys(d.articlesByLevel).length ? "" : '<p class="notice">Chưa có bản phân bổ bài theo trình độ tại máy chủ.</p>'}<div class="table-scroll"><table><thead><tr><th>Tập</th><th>A1</th><th>A2</th><th>B1</th><th>B2</th><th>Tổng bài</th></tr></thead><tbody>${Object.entries(d.articlesByLevel).map(([split,levels])=>`<tr><td>${esc(split)}</td>${["A1","A2","B1","B2"].map(l=>`<td>${number(levels[l])}</td>`).join("")}<td>${number(Object.values(levels).reduce((s,n)=>s+n,0))}</td></tr>`).join("")}</tbody></table></div></div></div><p class="notice">Không chuyển split, không chỉnh target, không thu thập hoặc duyệt lại nội dung từ dashboard.</p></div></section>`).join("");
    }
    function resourceView() {
        const run=current(), isRemote=remote(run), r=isRemote ? run.resources||{} : data.resources;
        return heading("Tài nguyên & log","Chọn phiên để xem đúng máy đo; VRAM GPU và RAM hệ thống được hiển thị riêng.")+`<section class="panel"><div class="toolbar"><label>Lần train <select id="log-run">${data.runs.map(x=>`<option value="${x.id}" ${x.id===selectedId?"selected":""}>${esc(sourceName(x))} · ${esc(x.name)}</option>`).join("")}</select></label></div><div class="panel-body"><h2>${isRemote && run.source==="colab" ? "Google Colab" : "Máy local"}</h2><p class="notice">${isRemote ? `${esc(connectionName(run))} · Snapshot ${stamp(run.observedAt)}. Số đo toàn máy gửi; không phải riêng trainer.` : esc(r.scope)}</p></div></section><div class="stats">${stat("CPU toàn máy",number(r.cpuPercent,1)+"%","cpu")}${stat("RAM toàn máy",number(r.ramPercent,1)+"%","cpu")}${stat("RAM còn trống",bytes(r.ramAvailable),"cpu")}${stat("Ổ đĩa còn trống",bytes(r.diskFree),"dataset")}</div><section class="panel"><div class="panel-body"><dl class="kv"><dt>GPU</dt><dd>${esc(isRemote ? run.gpu||(run.device==="cpu" ? "Không dùng GPU · CPU" : "Chưa có số đo GPU") : r.gpuReason||"Chưa có telemetry")}</dd><dt>VRAM đang dùng / tổng</dt><dd>${bytes(r.gpuUsedBytes)} / ${bytes(r.gpuTotalBytes)}</dd><dt>GPU utilization</dt><dd>${number(r.gpuUtilization,1)}%</dd><dt>Peak VRAM trainer</dt><dd>${bytes(r.peakAllocatedBytes)}</dd><dt>CPU logical cores</dt><dd>${number(r.cpuCores)}</dd><dt>RAM hệ thống tổng</dt><dd>${bytes(r.ramTotal)}</dd>${!isRemote ? `<dt>Worker local phát hiện</dt><dd>${number(data.externalWorkerCount)}</dd>` : ""}</dl><p class="notice">Peak VRAM lấy từ báo cáo trainer; không phải VRAM hiện tại. Không suy ra thời gian train từ tốc độ smoke một mẫu.</p></div></section><section class="panel"><div class="panel-head"><h2>${isRemote ? "Bằng chứng máy gửi" : "Log trainer local"}</h2>${isRemote ? runControls(run) : ""}</div><pre class="log" tabindex="0" aria-label="Log trainer">${esc(run?.log||"Đang đọc metadata…")}</pre><div class="panel-body"><p class="notice">Local hiển thị cuối log đã lọc credential. Nguồn HTTPS chỉ gửi metrics có cấu trúc; log gốc giữ trên máy train.</p></div></section>`;
    }
    function render() {
        renderNav();
        if(!data)return;
        const content={overview,sources:sourcesView,runs:listView,detail:detailView,compare:compareView,datasets:datasetsView,checkpoints:()=>heading("Checkpoint & khôi phục","Toàn vẹn adapter, optimizer, scheduler, RNG và trainer state.")+`<section class="panel"><div class="toolbar"><label>Lần train <select id="checkpoint-run">${data.runs.map(r=>`<option value="${r.id}" ${r.id===selectedId?"selected":""}>${esc(r.name)}</option>`).join("")}</select></label></div>${current()?checkpointView(current()):'<div class="empty">Chưa có lần train.</div>'}</section>`,resources:resourceView};
        main.innerHTML=(offline?`<div class="banner error">${icon("alert")}<div><strong>Mất kết nối máy train</strong><p>Đang xem bản chụp cũ. Chưa xác minh tiến trình; thao tác điều khiển đang khóa.</p></div></div>`:"")+(data.warnings||[]).map(w=>`<p class="banner">${esc(w)}</p>`).join("")+(content[view]||overview)();
        main.setAttribute("aria-busy","false");
    }
    async function loadDetail() {
        if(!selectedId||offline)return;
        const id=selectedId,seq=++detailSequence;
        try{const result=await request(`/api/admin/training/runs/${encodeURIComponent(id)}`);if(seq===detailSequence&&selectedId===id){detail=result;updateMain();}}
        catch(error){if(seq===detailSequence)toast(error.message);}
    }
    function updateMain() {
        const focused=document.activeElement,inside=main.contains(focused);
        if(dialog.open||(inside&&focused.matches("input,select,textarea")))return;
        const identity=inside?[...focused.attributes].filter(a=>a.name==="id"||a.name.startsWith("data-")):[];
        render();
        if(identity.length){const replacement=[...main.querySelectorAll("button,a,[tabindex]")].find(node=>identity.every(a=>node.getAttribute(a.name)===a.value));replacement?.focus({preventScroll:true});}
    }
    function pushNotices(runs) {
        for(const run of runs){const previous=lastStatuses.get(run.id);if(previous&&previous!==run.status){notices.unshift({text:`${run.name}: ${statuses[run.status]?.[0]||run.status}`,time:new Date().toISOString()});notices=notices.slice(0,40);}lastStatuses.set(run.id,run.status);}
        $("#notifications").innerHTML=`<h2>Thông báo</h2>${notices.length?notices.map(n=>`<div class="notification-event">${esc(n.text)}<small>${stamp(n.time)}</small></div>`).join(""):'<p class="muted">Chưa có sự kiện mới trong phiên xem này.</p>'}`;
    }
    async function refresh(quiet = false) {
        if(fetching||mutation)return;fetching=true;
        try{
            const result=await request("/api/admin/training");data=result;offline=false;pushNotices(result.runs);
            if(view==="compare")for(const id of compareIds)comparisonDetails.set(id,await request(`/api/admin/training/runs/${encodeURIComponent(id)}`));
            if(!result.runs.some(r=>r.id===selectedId))selectedId=result.runs.find(r=>r.active)?.id||result.runs.find(r=>r.status==="PAUSED_BY_USER")?.id||result.runs[0]?.id||"";
            $("#connection").textContent="Đã kết nối dashboard";$("#connection").className="connection live";
            $("#observed-at").textContent=`Đọc lúc ${stamp(result.observedAt)} · UTC+7`;
            updateMain();
            if(selectedId&&!dialog.open)await loadDetail();
        }catch(error){offline=true;$("#connection").textContent="Chưa kết nối";$("#connection").className="connection offline";if(data)render();else{main.innerHTML=heading("Training Studio","Giám sát và điều khiển SFT/LoRA trong khu vực quản trị.")+`<section class="panel"><div class="empty">${icon("alert")}<h2>Máy train chưa sẵn sàng</h2><p>${esc(error.message)}</p><div class="actions" style="justify-content:center"><button type="button" data-action="refresh">Thử kết nối lại</button><a href="/login?next=%2Fadmin%2Ftraining">Đăng nhập lại</a></div></div></section>`;main.setAttribute("aria-busy","false");}if(!quiet)toast(error.message);}
        finally{fetching=false;clearTimeout(pollTimer);pollTimer=setTimeout(poll,15000);}
    }
    function poll(){if(document.hidden||dialog.open){pollTimer=setTimeout(poll,15000);return;}refresh(true);}
    function modal(title, body, actions) {
        $("#dialog-content").innerHTML=`<div class="dialog-heading"><h2 id="dialog-title">${esc(title)}</h2><button type="button" class="icon-button" data-close aria-label="Đóng">${icon("close")}</button></div><div class="dialog-body">${body}<p class="dialog-error" id="dialog-error" role="alert"></p></div><div class="dialog-actions">${actions}</div>`;dialog.showModal();
    }
    function openColab() {
        modal("Kết nối máy train qua HTTPS",`<form id="colab-form"><div class="field"><label for="agent-source">Môi trường</label><select id="agent-source" name="source"><option value="colab">Google Colab · GPU</option><option value="local">Máy local · CPU</option></select></div><div class="field"><label for="colab-name">Tên phiên</label><input id="colab-name" name="name" maxlength="100" placeholder="Qwen T4 · full SFT 04" required></div><div class="field" id="colab-notebook-field"><label for="colab-url">URL notebook trên Drive</label><input id="colab-url" name="notebookUrl" type="url" placeholder="https://colab.research.google.com/drive/…" required></div><p class="notice">Có thể kết nối notebook từ bất kỳ tài khoản Colab/Drive nào. Khóa chỉ gửi metadata cho một phiên trong 7 ngày, không cấp quyền đọc dataset hoặc điều khiển train. Với local, để trống URL notebook. Tạo kết nối không khởi chạy trainer.</p></form>`,`<button type="button" data-close>Hủy</button><button type="submit" form="colab-form" class="primary">Tạo khóa kết nối</button>`);
    }
    function showColabKey(result) {
        const isLocal=result.id.startsWith("local-");
        const campaign="/content/qwen-sft-env04/data/fine-tuning/reviewed-500-v14-low-b1-20261024";
        const cell=result.id.startsWith("local-") ? `# Chạy trong thư mục dự án ở máy local
# Liệt kê mã phiên: python -m training.studio_publisher --list
python -m training.studio_publisher --endpoint ${JSON.stringify(result.endpoint)} --run-id MA_PHIEN_LOCAL
# Khóa được hỏi qua getpass, không ghi trong lệnh. Ctrl+C chỉ dừng đồng bộ.` : `import colab_reporter\nreporter = colab_reporter.connect(\n    endpoint=${JSON.stringify(result.endpoint)},\n    root="/content/qwen-sft-env04",\n    config="${campaign}/training-config-colab-04.yaml",\n    status_file="/content/sft-t4-full-04-status.json",\n    log="/content/sft-t4-full-04.log",\n    phase="full",\n)\n# reporter.state: xem kết nối; reporter.stop(): chỉ dừng đồng bộ`;
        modal("Khóa gửi tiến độ · chỉ hiển thị một lần",`<p>${esc(result.message)}</p><div class="field"><label for="colab-key">Khóa riêng của phiên</label><input id="colab-key" type="password" readonly value="${esc(result.token)}" autocomplete="off"><button type="button" id="copy-colab-key">Sao chép khóa</button></div><p>Hết hạn: ${stamp(result.expiresAt)} · UTC+7</p><ol class="setup-steps">${isLocal ? "<li>Dùng Python venv CPU hiện có trong checkout đã cập nhật.</li><li>Chạy lệnh --list để lấy mã phiên, thay MA_PHIEN_LOCAL trong lệnh bên dưới rồi chạy publisher. Nhập khóa khi getpass hỏi.</li><li>connected=True xác nhận máy chủ đã nhận snapshot. Ctrl+C chỉ dừng publisher.</li>" : '<li><a href="/admin/colab_reporter.py" download="colab_reporter.py">Tải colab_reporter.py</a> và đưa file vào Colab.</li><li>Chạy ô dưới đây trước ô train full. Nhập khóa qua getpass; không dán vào mã notebook.</li><li>Kiểm tra reporter.state báo connected=True. Điều chỉnh đường dẫn nếu runtime Colab đã thay đổi.</li>'}</ol><pre class="log" tabindex="0">${esc(cell)}</pre><p class="notice">Local dùng Python trong venv CPU đã cấu hình của dự án để chạy lệnh publisher. Colab dùng đường dẫn full SFT env04 từ bản sao lưu đã kiểm tra; cần đối chiếu runtime hiện tại. Để theo dõi smoke 04, đăng ký phiên riêng rồi dùng config training-config-colab-smoke-04.yaml, status/log sft-t4-smoke-04 và phase="smoke". Không chạy lại smoke để lấy metrics.</p>`,`<button type="button" data-close>Đã lưu khóa an toàn</button>`);
    }
    function openCreate(sourceId=selectedId) {
        const runs=data.runs.filter(r=>!remote(r)&&(r.configBound||r.canResume));if(!runs.length){toast("Chưa có cấu hình gốc đã xác minh để tạo mới.");return;}
        const source=runs.find(r=>r.id===sourceId)||runs[0];
        const field=(id,label,value,min,max,step="1")=>`<div class="field"><label for="new-${id}">${label}</label><input id="new-${id}" name="${id}" type="number" value="${value}" min="${min}" max="${max}" step="${step}" required></div>`;
        modal("Tạo lần train",`<form id="create-form"><div class="field"><label for="new-name">Tên lần train <span class="required">*</span></label><input id="new-name" name="name" maxlength="100" placeholder="Ví dụ: Qwen LoRA · thử nghiệm 04" required></div><div class="field"><label for="new-source">Model & dataset đã duyệt <span class="required">*</span></label><select id="new-source" name="sourceRunId">${runs.map(r=>`<option value="${r.id}" ${r.id===source.id?"selected":""}>${esc(r.model)} · ${esc(r.name)} · dataset ${esc(shortHash(r.datasetHash))}</option>`).join("")}</select><small>${esc(data.capabilities.modelNote)}</small></div><div class="confirm-note">Preset CPU float32 · LoRA r16 / alpha32 · 2 epochs · batch1 / tích lũy8. Lưu checkpoint mỗi step. Không truncate và không dùng test để chọn model.</div><details><summary>Tham số nâng cao</summary><div class="form-grid">${field("epochs","Epochs",2,.1,20,.1)}${field("learningRate","Learning rate",.0001,.0000001,.01,.0000001)}${field("batchSize","Batch mỗi thiết bị",1,1,16)}${field("accumulation","Gradient accumulation",8,1,128)}${field("rank","LoRA rank",16,1,128)}${field("alpha","LoRA alpha",32,1,256)}${field("dropout","LoRA dropout",.05,0,.9,.01)}${field("maxLength","Max sequence length",4608,4608,16384)}${field("warmup","Warmup ratio",.05,0,1,.01)}${field("seed","Seed",17,0,2147483647)}</div></details><p class="notice">Tạo cấu hình nháp mới, output riêng. Chưa chạy trainer; bạn kiểm tra và xác nhận ở bước tiếp theo.</p></form>`,`<button type="button" data-close>Hủy</button><button type="submit" form="create-form" class="primary">Tạo cấu hình nháp</button>`);
    }
    async function openControl(action,id) {
        if(offline||mutation)return;
        try{
            const run=await request(`/api/admin/training/runs/${encodeURIComponent(id)}`);detail=run;selectedId=id;
            const starting=action==="start"||action==="resume",label={start:"Bắt đầu train",resume:"Tiếp tục train",pause:"Tạm dừng an toàn",stop:"Kết thúc lần train"}[action];
            modal(label,`<p><strong>${esc(run.name)}</strong> · ${esc(run.model)}</p><dl class="kv"><dt>Trạng thái</dt><dd>${badge(run.status)}</dd><dt>Step đã ghi nhận</dt><dd>${number(run.step)} / ${number(run.plannedSteps)}</dd><dt>Checkpoint đã xác minh</dt><dd>${esc(verified(run)[0]?.name||"Chưa có")}</dd><dt>Dataset hash</dt><dd class="mono">${esc(shortHash(run.datasetHash))}</dd></dl><div class="confirm-note">${starting?(action==="resume"?"Tiếp tục cùng config/output và checkpoint hợp lệ; giữ nguyên dataset/split/reviewer/hash. Không chạy lại từ base.":"Khởi chạy SFT/LoRA thật từ base được ghim revision, output riêng. Các gate dữ liệu và loss mask vẫn bắt buộc."):"Hoàn tất optimizer step đang tính → lưu checkpoint đầy đủ → dừng. Trong thời gian chờ, CPU vẫn chạy. Một step hiện có thể mất 20–30 phút."}</div>${starting?'<label class="check-field"><input class="checkbox" id="allow-memory" type="checkbox"><span>Tôi xác nhận chạy trên CPU hiện tại và cho phép thử dưới ước lượng RAM. Giữ kiểm tra dữ liệu/loss mask; không tự đổi model hoặc cài dependency.</span></label>':""}<p class="notice">Không triển khai model, không shutdown máy. Thao tác được ghi cùng tài khoản quản trị.</p>`,`<button type="button" data-close>Hủy</button><button type="button" class="${action==="stop"?"danger":"primary"}" id="confirm-control" data-action-name="${action}" data-id="${id}" data-hash="${run.configHash}" ${starting?"disabled":""}>${label}</button>`);
        }catch(error){toast(error.message);}
    }
    async function performMutation(scope, operation) {
        if(mutation)return;mutation=true;scope.setAttribute("aria-busy","true");const buttons=[...scope.querySelectorAll("button")];buttons.forEach(b=>b.disabled=true);
        try{const result=await operation();dialog.close();if(result.id){selectedId=result.id;detail=null;view="detail";}toast(result.message||"Đã ghi nhận yêu cầu.");}
        catch(error){$("#dialog-error").textContent=error.message;}
        finally{mutation=false;scope.setAttribute("aria-busy","false");buttons.forEach(b=>b.disabled=false);const confirmation=$("#confirm-control");if(confirmation&&$("#allow-memory"))confirmation.disabled=!$("#allow-memory").checked;await refresh(true);if(!dialog.open)navigate(view,selectedId,true);}
    }
    document.addEventListener("click",async event=>{
        const button=event.target.closest("[data-view],[data-action],[data-control],[data-tab],[data-close]");if(!button||button.disabled)return;
        if(button.dataset.view){if(event.ctrlKey||event.metaKey||event.shiftKey)return;event.preventDefault();navigate(button.dataset.view,button.dataset.id||selectedId);}
        if(button.dataset.tab){detailTab=button.dataset.tab;render();$(`#run-tab-${detailTab}`).focus();}
        if(button.hasAttribute("data-close")&&!mutation)dialog.close();
        if(button.dataset.control)openControl(button.dataset.control,button.dataset.id);
        const action=button.dataset.action;
        if(action==="connect-colab")openColab();
        if(action==="revoke-colab")modal("Thu hồi khóa nguồn train",`<p>Ngừng nhận snapshot mới cho phiên này. Trainer trên máy gửi vẫn chạy; bằng chứng đã nhận vẫn giữ.</p>`,`<button type="button" data-close>Hủy</button><button type="button" id="confirm-revoke" data-id="${button.dataset.id}" class="danger">Thu hồi khóa</button>`);
        if(action==="refresh"){if(document.activeElement instanceof HTMLElement)document.activeElement.blur();await refresh();render();}
        if(action==="create"||action==="clone")openCreate(button.dataset.id);
        if(action==="compare"){
            if(compareIds.size<2)return;
            const selected=[...compareIds];try{for(const id of selected)comparisonDetails.set(id,await request(`/api/admin/training/runs/${encodeURIComponent(id)}`));navigate("compare");}catch(error){toast(error.message);}
        }
        if(action==="config"){detailTab="config";navigate("detail",button.dataset.id);}
    });
    main.addEventListener("change",event=>{
        const node=event.target;
        if(node.dataset.compare){if(node.checked&&compareIds.size>=4){node.checked=false;toast("Chọn tối đa 4 lần train để biểu đồ dễ đọc.");return;}node.checked?compareIds.add(node.dataset.compare):compareIds.delete(node.dataset.compare);render();}
        if(node.id==="status-filter"){statusFilter=node.value;render();}
        if(node.id==="source-filter"){sourceFilter=node.value;render();}
        if(node.id==="log-run"||node.id==="checkpoint-run"){selectedId=node.value;detail=null;render();loadDetail();}
    });
    main.addEventListener("input",event=>{if(event.target.id!=="run-search")return;const input=event.target;filter=input.value;const cursor=input.selectionStart;render();const replacement=$("#run-search");replacement.focus();replacement.setSelectionRange(cursor,cursor);});
    dialog.addEventListener("change",event=>{
        if(event.target.id==="allow-memory")$("#confirm-control").disabled=!event.target.checked;
        if(event.target.id==="agent-source"){
            const local=event.target.value==="local",url=$("#colab-url");
            $("#colab-notebook-field").hidden=local;url.disabled=local;url.required=!local;
        }
    });
    dialog.addEventListener("cancel",event=>{if(mutation)event.preventDefault();});
    dialog.addEventListener("click",event=>{const button=event.target.closest("#confirm-control");if(!button||button.disabled)return;performMutation(dialog,()=>request(`/api/admin/training/runs/${button.dataset.id}/${button.dataset.actionName}`,{method:"POST",body:JSON.stringify({confirm:true,configHash:button.dataset.hash,allowBelowMemoryEstimate:!!$("#allow-memory")?.checked})}));});
    dialog.addEventListener("submit",async event=>{
        const form=event.target;if(!["create-form","colab-form"].includes(form.id))return;
        event.preventDefault();if(!form.reportValidity()||mutation)return;
        const fields=new FormData(form);
        if(form.id==="colab-form"){
            mutation=true;const submit=dialog.querySelector('[type="submit"]');submit.disabled=true;
            try{const result=await request("/api/admin/training/colab",{method:"POST",body:JSON.stringify(Object.fromEntries(fields))});dialog.close();selectedId=result.id;detail=null;view="sources";showColabKey(result);}
            catch(error){$("#dialog-error").textContent=error.message;submit.disabled=false;}
            finally{mutation=false;await refresh(true);}return;
        }
        const parameters={};for(const [k,v]of fields)if(!["name","sourceRunId"].includes(k))parameters[k]=Number(v);
        performMutation(dialog,()=>request("/api/admin/training/runs",{method:"POST",body:JSON.stringify({name:fields.get("name"),sourceRunId:fields.get("sourceRunId"),parameters})}));
    });
    dialog.addEventListener("click",async event=>{
        if(event.target.id==="copy-colab-key")try{await navigator.clipboard.writeText($("#colab-key").value);toast("Đã sao chép khóa. Không đưa khóa vào notebook hoặc Git.");}catch(error){$("#colab-key").type="text";$("#colab-key").select();}
        if(event.target.id==="confirm-revoke")performMutation(dialog,()=>request(`/api/admin/training/colab/${event.target.dataset.id}/revoke`,{method:"POST",body:"{}"}));
    });
    dialog.addEventListener("close",()=>{if(!dialog.open){$("#dialog-content").replaceChildren();updateMain();}});
    main.addEventListener("keydown",event=>{
        const tab=event.target.closest('[role="tab"]');if(!tab||!["ArrowLeft","ArrowRight","Home","End"].includes(event.key))return;
        const buttons=[...main.querySelectorAll('[role="tab"]')],index=buttons.indexOf(tab);
        const next=event.key==="Home"?0:event.key==="End"?buttons.length-1:(index+(event.key==="ArrowRight"?1:-1)+buttons.length)%buttons.length;
        event.preventDefault();buttons[next].click();
    });
    $("#menu-toggle").addEventListener("click",()=>setMenu(!$("#studio-sidebar").classList.contains("open")));
    document.addEventListener("click",event=>{if(!event.target.closest("#studio-sidebar,#menu-toggle"))setMenu(false);});
    document.addEventListener("keydown",event=>{if(event.key==="Escape"&&$("#studio-sidebar").classList.contains("open")){setMenu(false);$("#menu-toggle").focus();}});
    mobileMenu.addEventListener("change",()=>setMenu(false));setMenu(false);
    $("#notifications-toggle").addEventListener("click",()=>{$("#notifications").hidden=!$("#notifications").hidden;$("#notifications-toggle").setAttribute("aria-expanded",String(!$("#notifications").hidden));});
    window.addEventListener("popstate",()=>{const params=new URLSearchParams(location.search);view=allowedViews.has(params.get("view"))?params.get("view"):"overview";selectedId=params.get("run")||selectedId;render();loadDetail();});
    window.addEventListener("pagehide",()=>clearTimeout(pollTimer));
    renderNav();request("/api/auth/me").then(user=>{$("#studio-account").textContent=user.displayName||"Quản trị viên";if(user.role!=="ADMIN")throw new Error("Chỉ quản trị viên được truy cập Training Studio.");return refresh();}).catch(error=>{main.innerHTML=`<div class="empty"><h1>Không mở được Training Studio</h1><p>${esc(error.message)}</p><a href="/login?next=%2Fadmin%2Ftraining">Đăng nhập quản trị →</a></div>`;main.setAttribute("aria-busy","false");});
})();
