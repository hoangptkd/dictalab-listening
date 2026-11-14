(() => {
    "use strict";
    const esc = value => String(value ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
    const finite = value => typeof value === "number" && Number.isFinite(value);
    const num = (value, digits = 0) => finite(value) ? value.toLocaleString("vi-VN", {maximumFractionDigits:digits}) : "—";
    const pct = value => finite(value) ? `${num(value * 100, 1)}%` : "—";
    const rate = (passed, total) => finite(passed) && total > 0 ? passed / total : null;
    const stamp = value => value ? new Date(value).toLocaleString("vi-VN", {timeZone:"Asia/Bangkok",hour12:false}) : "—";
    const short = value => value ? value.slice(0, 10) : "—";
    const names = {questionFactsAligned:"Câu hỏi & đáp án",answerable:"Trả lời được từ bài nghe",rolesCorrect:"Vai người nói",
        translationFaithful:"Bản dịch chính xác",historyConsistent:"Mạch hội thoại",newMeanings:"Ý nghĩa mới",
        cefrAppropriate:"Phù hợp trình độ",reasoningGrounded:"Suy luận có căn cứ",noAnswerLeak:"Không lộ đáp án"};
    const labels = {RUNNING:["Đang chạy","live"],COMPLETED:["Hoàn tất","good"],STOPPED:["Dừng do lỗi","bad"],INTERRUPTED:["Gián đoạn","wait"],CANCELLED:["Đã dừng","wait"],
        PASS:["Đạt","good"],FAIL:["Không đạt","bad"],REVIEW_REQUIRED:["Chưa đủ đánh giá","wait"],MISSING:["Thiếu output","wait"],ERROR:["Lỗi","bad"],DONE:["Đã kiểm tra",""],
        improved:["Cải thiện","good"],unchanged:["Giữ nguyên",""],regressed:["Suy giảm","bad"],unresolved:["Chưa kết luận","wait"]};
    const badge = status => `<span class="badge ${labels[status]?.[1] || ""}">${esc(labels[status]?.[0] || status || "Chưa có dữ liệu")}</span>`;
    const empty = (title, description, compact = false) => `<div class="empty ${compact ? "compact" : ""}"><h3>${esc(title)}</h3><p>${esc(description)}</p></div>`;
    const legend = (a = "Lần đầu", b = "Sau Harness") => `<div class="legend"><span>${esc(a)}</span><span>${esc(b)}</span></div>`;

    function trendChart(runs) {
        if (!runs.length) return empty("Chưa có dữ liệu xu hướng", "Chạy đánh giá đầu tiên để ghi nhận chất lượng và mức can thiệp của Harness.", true);
        const compact = typeof window !== "undefined" && window.matchMedia("(max-width:760px)").matches;
        const W = compact ? 340 : 650, H = 253, left = 39, right = W - 27, top = 18, bottom = 207;
        const x = i => runs.length === 1 ? (left + right) / 2 : left + i * (right - left) / (runs.length - 1);
        const y = v => bottom - Math.max(0, Math.min(1, v)) * (bottom - top);
        let svg = [0,.25,.5,.75,1].map(v => `<line class="grid" x1="${left}" x2="${right}" y1="${y(v)}" y2="${y(v)}"/><text x="30" y="${y(v)+3}" text-anchor="end">${v*100}%</text>`).join("");
        for (const [side,color] of [["before","var(--before)"],["after","var(--after)"]]) {
            // Break at absent/incomplete observations; never bridge missing values with a trend line.
            let segment = [];
            const flush = () => { if (segment.length > 1) svg += `<polyline class="series-${side}" points="${segment.join(" ")}"/>`; segment = []; };
            runs.forEach((run,i) => {
                const m = run.metrics || {}, value = m[`${side}Rate`];
                if (!finite(value) || m[`${side}Evaluated`] !== m.planned || run.status !== "COMPLETED") { flush(); return; }
                segment.push(`${x(i)},${y(value)}`);
                svg += `<circle class="point" cx="${x(i)}" cy="${y(value)}" r="4" stroke="${color}"><title>${esc(run.label)} · ${side === "before" ? "Lần đầu" : "Sau Harness"}: ${pct(value)} (${m[`${side}Passed`]}/${m.planned})</title></circle>`;
            }); flush();
        }
        const every = Math.max(1, Math.ceil(runs.length / (compact ? 3 : 6)));
        runs.forEach((run,i) => { if (i % every === 0 || i === runs.length - 1) svg += `<text x="${x(i)}" y="231" text-anchor="middle">${esc(run.label.slice(0, 13))}</text>`; });
        return `<svg class="chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="Tỷ lệ đạt lần đầu và sau Harness qua ${runs.length} lần chạy"><title>Xu hướng chất lượng. Trục đứng từ 0 đến 100 phần trăm.</title>${svg}</svg>`;
    }

    function criteriaChart(a, b = null, compare = false) {
        if (!a?.criteria) return empty("Chưa có đánh giá theo tiêu chí", "Bật chấm ngữ nghĩa khi tạo lần chạy. Các mục chưa được chấm không được tính là đạt.", true);
        const keys = Object.keys(names), width = 680, start = 207, end = 593, row = 43;
        let mobile = "";
        let content = [0,.25,.5,.75,1].map(v => `<line class="grid" x1="${start + v*(end-start)}" x2="${start + v*(end-start)}" y1="15" y2="${keys.length*row+7}"/><text x="${start+v*(end-start)}" y="${keys.length*row+25}" text-anchor="middle">${v*100}%</text>`).join("");
        keys.forEach((key,i) => {
            const y = 21+i*row, av = a.criteria[key] || {}, bv = compare ? (b?.criteria?.[key] || {}) : av;
            const first = rate(av[compare ? "afterPassed" : "beforePassed"], av[compare ? "afterEvaluated" : "beforeEvaluated"]);
            const second = rate(bv.afterPassed, bv.afterEvaluated);
            mobile += `<div class="criterion-mobile"><strong>${esc(names[key])}</strong>${[[first,"before"],[second,"after"]].map(([value,side])=>`<div class="mini-bar"><span class="mini-track"><span class="mini-fill ${side}" style="width:${finite(value) ? Math.max(0,Math.min(1,value))*100 : 0}%"></span></span><span>${pct(value)}</span></div>`).join("")}</div>`;
            content += `<text x="0" y="${y+9}">${esc(names[key])}</text>`;
            for (const [value,offset,color,count] of [[first,0,"var(--before)",av[compare ? "afterEvaluated" : "beforeEvaluated"]],[second,12,"var(--after)",bv.afterEvaluated]]) {
                const bar = finite(value) ? (end-start)*Math.max(0,Math.min(1,value)) : 0;
                content += `<rect x="${start}" y="${y+offset}" width="${end-start}" height="7" rx="2" fill="#f1f4ef"/>`;
                if (finite(value)) content += `<rect x="${start}" y="${y+offset}" width="${bar}" height="7" rx="2" fill="${color}"><title>${esc(names[key])}: ${pct(value)} · ${num(count)} mẫu đã chấm</title></rect>`;
                content += `<text x="${end+12}" y="${y+offset+7}">${pct(value)}</text>`;
            }
        });
        return `<div class="criteria-desktop"><svg class="chart" viewBox="0 0 ${width} ${keys.length*row+39}" role="img" aria-label="So sánh tỷ lệ đạt theo 9 tiêu chí; gạch ngang là chưa được chấm"><title>Chất lượng theo tiêu chí, mẫu số là số mẫu đã được chấm ở từng tiêu chí.</title>${content}</svg></div><div class="criteria-mobile">${mobile}</div>`;
    }

    function regressionChart(counts) {
        const total = Object.values(counts).reduce((a,b) => a+b, 0);
        if (!total) return empty("Chưa có cặp test", "Chọn hai lần đánh giá để xem thay đổi.", true);
        let offset = 0;
        const colors = {improved:"#23836b",unchanged:"#9aa99a",regressed:"#be5146",unresolved:"#c79a49"};
        const bars = Object.entries(counts).map(([key,value]) => {
            const w = value / total * 630;
            const rect = `<rect x="${offset}" y="8" width="${w}" height="26" fill="${colors[key]}"><title>${labels[key][0]}: ${value}/${total}</title></rect>`; offset += w; return rect;
        }).join("");
        return `<svg class="chart" viewBox="0 0 630 43" role="img" aria-label="${Object.entries(counts).map(([k,v]) => `${labels[k][0]} ${v}`).join(', ')}">${bars}</svg><div class="comparison-key">${Object.entries(counts).map(([k,v]) => `<span>${badge(k)} ${num(v)}</span>`).join("")}</div>`;
    }
    const runnableVersion = (v, catalog) => v.pipeline === "scenario" && !!catalog?.available &&
        catalog.releases.some(r => r.promptHash === v.promptHash) && v.pythonBackendHash === catalog.backendHash;
    if (typeof module !== "undefined" && module.exports) module.exports = {esc,rate,trendChart,criteriaChart,regressionChart,runnableVersion};
    if (typeof document === "undefined") return;

    const $ = selector => document.querySelector(selector), main = $("#evaluation-main"), dialog = $("#evaluation-dialog");
    const params = new URLSearchParams(location.search);
    let view = ["overview","runs","compare","versions","detail"].includes(params.get("view")) ? params.get("view") : "overview";
    let selected = params.get("run") || "", data, detail, comparison, offline = false, busy = false, csrf, poll;
    let filterVersion = "", filterSuite = "", filterStatus = "", filterRuntime = "", filterLevel = "", filterCase = "", filterStep = "";
    let left = params.get("left") || "", right = params.get("right") || "", sequence = 0;
    const runtimeKey = r => JSON.stringify({pipeline:r.pipeline || "java",runtime:r.runtime || {}});
    const canRun = v => runnableVersion(v, data?.scenarioPipeline);
    const runSuites = versionId => data.versions.find(v=>v.id===versionId)?.pipeline === "scenario" ? (data.scenarioPipeline?.suites || []) : data.suites;
    const summaries = () => (data?.runs || []).filter(r => (!filterVersion || r.version === filterVersion) && (!filterSuite || r.suite === filterSuite) && (!filterStatus || r.status === filterStatus) && (!filterRuntime || runtimeKey(r) === filterRuntime));
    const suiteName = id => ({smoke:"Smoke",regression:"Regression",full:"Full evaluation"}[id] || id);
    const currentRows = () => (detail?.cases || []).filter(r => (!filterLevel || r.level === filterLevel) && (!filterCase || (r.after?.status || "MISSING") === filterCase)
        && (!filterStep || r.steps?.some(s => s.step === filterStep) || r.calls?.some(c => c.step === filterStep)));

    async function api(path = "", options = {}, retried = false) {
        const mutation = options.method && options.method !== "GET";
        if (mutation && !csrf) {
            const response = await fetch("/api/auth/csrf", {credentials:"same-origin",cache:"no-store",signal:AbortSignal.timeout(10000)});
            if (!response.ok) throw new Error("Phiên đăng nhập không hợp lệ. Hãy đăng nhập lại.");
            csrf = await response.json();
        }
        const response = await fetch(`/api/admin/ai-evaluation${path}`, {credentials:"same-origin",cache:"no-store",signal:AbortSignal.timeout(20000),...options,
            headers:{...(options.body ? {"Content-Type":"application/json"} : {}),...(mutation ? {[csrf.headerName]:csrf.token} : {})}});
        if (mutation && response.status === 403 && response.headers.get("X-CSRF-Error") === "1" && !retried) { csrf = null; return api(path,options,true); }
        const payload = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(response.status === 401 ? "Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại." : response.status === 403
            ? "Chỉ quản trị viên được truy cập Evaluation Studio." : payload.message || payload.detail || "Không đọc được Evaluation Studio local.");
        return payload;
    }
    function message(text) {
        const node = $("#evaluation-message"); node.textContent = text; node.hidden = false;
        clearTimeout(message.timer); message.timer = setTimeout(() => { node.hidden = true; }, 7000);
    }
    async function refresh(initial = false) {
        try {
            const next = await api(); data = next; offline = false;
            if (view === "detail" && selected) detail = await api(`/runs/${encodeURIComponent(selected)}`);
            $("#connection").textContent = next.activeRun ? "● Đang đánh giá · local" : "● Đã kết nối · local";
            if (!dialog.open) render(true);
        } catch (error) {
            offline = true; $("#connection").textContent = "Mất kết nối";
            if (initial || !data) main.innerHTML = `<div class="heading"><div><p class="eyebrow">LOCAL WORKSPACE</p><h1>AI Evaluation Studio</h1></div></div><div class="banner error" role="alert">${esc(error.message)}</div><button data-refresh>Thử lại</button>`;
            else if (!dialog.open) render(true);
        } finally {
            main.setAttribute("aria-busy","false"); clearTimeout(poll);
            if (data?.activeRun || offline) poll = setTimeout(() => { if (!document.hidden) refresh(); else refreshTimer(); }, 5000);
        }
    }
    function refreshTimer() { clearTimeout(poll); poll = setTimeout(() => { if (!document.hidden) refresh(); else refreshTimer(); }, 5000); }
    function navigate(next,id="") {
        view = next; selected = id; sequence++; comparison = null;
        const query = new URLSearchParams({view}); if (id) query.set("run",id); if (left) query.set("left",left); if (right) query.set("right",right);
        history.pushState({},"",`${location.pathname}?${query}`);
        if (next === "detail") { detail = null; loadDetail(id); } else render();
        main.focus({preventScroll:true});
    }
    async function loadDetail(id) {
        const request = ++sequence; main.setAttribute("aria-busy","true"); render();
        try { const response = await api(`/runs/${encodeURIComponent(id)}`); if (request === sequence) { detail = response; render(); } }
        catch (error) { if (request === sequence) { message(error.message); main.innerHTML = empty("Không tải được lần đánh giá",error.message); } }
        finally { main.setAttribute("aria-busy","false"); }
    }
    function heading(title, description, actions = true) {
        return `<div class="heading"><div><p class="eyebrow">AI QUALITY / EXPERIMENTS</p><h1>${esc(title)}</h1><p>${esc(description)}</p></div>${actions ? `<div class="actions"><button data-refresh>Làm mới</button><button data-new-version>+ Phiên bản</button><button class="primary" data-new-run ${!data?.versions.some(canRun) || offline ? "disabled" : ""}>Chạy đánh giá ↗</button></div>` : ""}</div>`;
    }
    function filters() {
        const runtimes=[...new Map(data.runs.map(r=>[runtimeKey(r),r.runtime])).entries()];
        return `<div class="filters"><label>Phiên bản<select data-filter="version"><option value="">Tất cả phiên bản</option>${data.versions.map(v=>`<option value="${esc(v.id)}" ${filterVersion === v.id ? "selected" : ""}>${esc(v.label)}</option>`).join("")}</select></label>
            <label>Bộ kiểm thử<select data-filter="suite"><option value="">Tất cả bộ test</option>${data.suites.map(s=>`<option value="${s.id}" ${filterSuite === s.id ? "selected" : ""}>${esc(s.name)}</option>`).join("")}</select></label>
            <label>Trạng thái<select data-filter="status"><option value="">Tất cả trạng thái</option>${["COMPLETED","RUNNING","STOPPED","INTERRUPTED","CANCELLED"].map(s=>`<option value="${s}" ${filterStatus === s ? "selected" : ""}>${labels[s][0]}</option>`).join("")}</select></label>
            <label>Cấu hình model<select data-filter="runtime"><option value="">Tất cả cấu hình</option>${runtimes.map(([key,r])=>`<option value="${esc(key)}" ${filterRuntime === key ? "selected" : ""}>${esc(r?.model)} · ${esc(r?.transport)}</option>`).join("")}</select></label>
            <span class="count">${summaries().length} lần đánh giá · quota từ <a href="/admin/gemini">Nguồn Gemini ↗</a></span></div>`;
    }
    function runTable(runs,limit) {
        if (!runs.length) return empty("Chưa có lần đánh giá", "Tạo phiên bản từ prompt hiện tại, sau đó chạy Smoke hoặc replay kết quả đã lưu.");
        return `<div class="table-scroll"><table><caption class="sr-only">Lịch sử đánh giá, tỷ lệ đạt trên toàn bộ mẫu đã lên kế hoạch</caption><thead><tr><th>Phiên bản / lần chạy</th><th>Bộ test</th><th>Lần đầu</th><th>Sau Harness</th><th>Sửa / Critical</th><th>API / thời gian</th><th>Trạng thái</th></tr></thead><tbody>${runs.slice(0,limit || runs.length).map(r=>{
            const m=r.metrics || {}; return `<tr><td><button class="table-link" data-detail="${r.id}">${esc(r.label)}</button><small>${stamp(r.createdAt)} · ${short(r.id)}</small></td>
                <td>${suiteName(r.suite)}<small>${num(r.planned)} mẫu · ${r.mode === "live" ? "Sinh mới" : "Replay"}</small></td>
                <td><span class="rate-before">${pct(m.beforeRate)}</span><small>${num(m.beforePassed)}/${num(m.planned)} · chấm ${num(m.beforeEvaluated)}</small></td>
                <td><span class="rate-after">${pct(m.afterRate)}</span><small>${num(m.afterPassed)}/${num(m.planned)} · chấm ${num(m.afterEvaluated)}</small></td>
                <td>${num(m.repairs)} / ${num(m.critical)}</td><td>${num(m.logicalCalls)} lượt<small>p95 ${num(m.p95,1)} s</small></td><td>${badge(r.status)}</td></tr>`;
        }).join("")}</tbody></table></div>`;
    }
    function overview() {
        const runs=summaries(), latest=runs[0], m=latest?.metrics || {};
        // One trend cohort must share dataset, judge and execution mode; counts are shown alongside it.
        const cohort=runs.filter(r => r.datasetHash === latest?.datasetHash && r.judgeHash === latest?.judgeHash
            && JSON.stringify(r.runtime) === JSON.stringify(latest?.runtime) && r.mode === latest?.mode && r.judge === latest?.judge).slice(0,14).reverse();
        return `${heading("Chất lượng hội thoại AI", "Theo dõi chất lượng thực, hiệu quả Harness và những thay đổi cần xem xét.")}
            <div class="banner">Pipeline cá nhân hóa: ${data.scenarioPipeline?.available ? `${data.scenarioPipeline.releases.map(r=>esc(r.id)).join(" / ")} · ${esc(data.scenarioPipeline.runtime.model)}` : esc(data.scenarioPipeline?.notice || "Chưa kết nối")}. Các pipeline cũ chỉ còn lịch sử để xem. Tạo phiên bản đánh giá không kích hoạt prompt cho người học.</div>${filters()}
            <div class="stats"><div class="stat"><span class="stat-label">ĐẠT LẦN ĐẦU</span><strong class="rate-before">${pct(m.beforeRate)}</strong><small>${num(m.beforePassed)} / ${num(m.planned)} mẫu kế hoạch</small></div>
            <div class="stat"><span class="stat-label">ĐẠT SAU HARNESS</span><strong class="rate-after">${pct(m.afterRate)}</strong><small>Đã chấm ${num(m.afterEvaluated)} / ${num(m.planned)} mẫu</small></div>
            <div class="stat ${m.critical ? "attention" : ""}"><span class="stat-label">LỖI NGHIÊM TRỌNG</span><strong>${num(m.critical)}</strong><small>Không bù trừ bằng điểm trung bình</small></div>
            <div class="stat"><span class="stat-label">CẦN HARNESS SỬA</span><strong>${pct(rate(m.repairs,m.planned))}</strong><small>${num(m.repairs)} / ${num(m.planned)} mẫu</small></div></div>
            <div class="chart-grid"><section class="panel"><div class="panel-head"><div><h2>Xu hướng chất lượng</h2><p>${cohort.length} lần chạy cùng điều kiện so sánh</p></div>${legend()}</div><div class="panel-body">${trendChart(cohort)}<p class="chart-note">Chỉ nối các lần chạy hoàn tất và đủ đánh giá. Tỷ lệ dùng toàn bộ mẫu kế hoạch.</p></div></section>
            <section class="panel"><div class="panel-head"><div><h2>Hiệu suất & độ phủ</h2><p>${esc(latest?.label || "Lần chạy gần nhất")}</p></div></div><div class="panel-body">
            <div class="metric-line"><span>Độ phủ chấm sau Harness</span><strong>${pct(rate(m.afterEvaluated,m.planned))}</strong></div>
            <div class="metric-line"><span>Lượt gọi AI / HTTP attempt</span><strong>${num(m.logicalCalls)} / ${num(m.httpAttempts)}</strong></div>
            <div class="metric-line"><span>Thời gian p50 / p95</span><strong>${num(m.p50,1)} / ${num(m.p95,1)} s</strong></div>
            <div class="metric-line"><span>Lỗi thực thi / mẫu hoàn tất</span><strong>${num(m.errors)} / ${num(m.done)}</strong></div>
            <div class="metric-line"><span>Test đổi kết quả giữa các lần lặp</span><strong>${m.repeatGroupsEvaluated ? `${num(m.unstableFixtures)} / ${num(m.repeatGroupsEvaluated)}` : "—"}</strong></div>
            <div class="metric-line"><span>Token input / output đã đo</span><strong>${num(m.inputTokens)} / ${num(m.outputTokens)}</strong></div>
            <p class="chart-note">Usage đo được ở ${num(m.usageMeasuredCalls)} / ${num(m.logicalCalls)} lượt gọi. Dấu — là chưa có dữ liệu; token giữ chỗ không phải chi phí thực.</p></div></section></div>
            <section class="panel"><div class="panel-head"><div><h2>Chất lượng theo tiêu chí</h2><p>Cùng evaluator cho output đầu tiên và output sau Harness</p></div>${legend()}</div><div class="panel-body">${criteriaChart(m)}<p class="chart-note">Tỷ lệ từng tiêu chí dùng số mẫu đã chấm. Xem độ phủ ở phía trên để tránh kết luận từ mẫu thiếu.</p></div></section>
            <section class="panel"><div class="panel-head"><div><h2>Các lần đánh giá gần đây</h2><p>Chọn một lần chạy để xem từng test và các bước xử lý</p></div><button data-view="runs">Xem tất cả</button></div>${runTable(runs,6)}</section>`;
    }
    function versionsView() {
        return `${heading("Phiên bản prompt", "Mỗi phiên bản giữ nguyên prompt, chính sách sửa, cấu hình model và dấu vết backend.")}
            <div class="banner">Nguồn và quota được quản lý tại <a href="/admin/gemini">Nguồn Gemini</a>. Tạo phiên bản chỉ lưu cấu hình thí nghiệm.</div>
            ${!data.versions.length ? empty("Tạo phiên bản đầu tiên", "Chọn pipeline và phiên bản prompt để đóng băng thí nghiệm.") : `<div class="version-grid">${data.versions.map(v=>`<article class="version-card"><div class="case-title"><h2>${esc(v.label)}</h2><span class="tag">${v.pipeline === "scenario" ? "Harness gốc · tối đa 9 lượt sinh" : v.repair ? "Sửa tối đa 1 lần" : "Đo lần đầu"}</span></div>
                <dl class="version-meta"><dt>Prompt</dt><dd>${esc(v.promptVersion)} · <span class="mono">${short(v.promptHash)}</span></dd><dt>Backend</dt><dd class="mono">${esc(v.backendHash)}</dd>
                <dt>Model</dt><dd>${esc(v.runtime.model)} · temperature ${v.runtime.temperature}</dd><dt>Ngày tạo</dt><dd>${stamp(v.createdAt)}</dd></dl>
                <details><summary>Chỉ dẫn bổ sung</summary><pre class="output">${esc(v.promptSuffix || "Dùng nguyên prompt hiện tại.")}</pre></details>${canRun(v) ? `<button data-new-run data-version="${v.id}">Chạy phiên bản này</button>` : '<p class="muted">Lịch sử · tạo phiên bản hiện hành để chạy mới.</p>'}</article>`).join("")}</div>`}`;
    }
    function outputView(row, stage) {
        const attempts=row?.attempts || [], raw=attempts[stage === "before" ? 0 : attempts.length-1]?.raw, result=row?.[stage];
        if (!raw) return empty("Không có output", row?.error || "Lần chạy chưa tạo được nội dung cho test này.", true);
        const lesson = raw.activities?.map(a=>`<section><h4>${esc(a.id)} · ${esc(a.titleVi || a.titleEn || "")}</h4>${a.segments.map(s=>`<p><strong>${esc(s.speakerId)}:</strong> ${esc(s.textEn)}</p>`).join("")}${(a.questions || []).map(q=>`<p><strong>${esc(q.promptVi)}</strong><br>${esc(q.answerVi)}</p>`).join("")}</section>`).join("");
        return `${badge(result?.status)} ${result?.critical ? '<span class="badge bad">Critical</span>' : ""}${lesson || `<p class="output utterance">${esc(raw.utteranceEn || JSON.stringify(raw))}</p>`}
            <p><strong>Câu hỏi:</strong> ${esc(raw.questionVi)}</p><p class="muted">${esc(raw.translationVi)}</p>
            ${(result?.issues || []).map(issue=>`<div class="issue ${issue.severity === "critical" ? "critical" : ""}"><strong>${esc(names[issue.criterion] || issue.criterion)} · ${esc(issue.severity)}</strong><p>${esc(issue.reason)}</p>${issue.evidence ? `<q>${esc(issue.evidence)}</q>` : ""}</div>`).join("")}
            ${result?.reason ? `<p class="banner error">${esc(result.reason)}</p>` : ""}
            ${result?.judgeReused ? '<small>Kết quả chấm được tái sử dụng cho đúng output, context và rubric.</small>' : ""}
            <details><summary>Output đầy đủ & kết quả từng tiêu chí</summary><pre class="output">${esc(JSON.stringify({task:raw,evaluation:result},null,2))}</pre></details>`;
    }
    function detailView() {
        if (!detail) return `${heading("Chi tiết lần đánh giá", "Đang tải bằng chứng…",false)}${empty("Đang tải", "Đang đọc output và các bước xử lý.",true)}`;
        const m=detail.metrics || {}, rows=currentRows();
        return `${heading(detail.label, `${suiteName(detail.suite)} · ${stamp(detail.createdAt)} · ${detail.mode === "live" ? "Sinh mới" : "Replay"}`,false)}
            <div class="actions" style="margin-bottom:20px"><button data-view="runs">← Các lần đánh giá</button>${badge(detail.status)}<button data-compare-run="${detail.id}">Đưa vào so sánh</button><button data-export>Xuất JSON</button>${detail.status === "RUNNING" ? `<button class="danger" data-stop="${detail.id}">Dừng sau lời gọi hiện tại</button>` : ""}</div>
            ${detail.notice ? `<div class="banner">${esc(detail.notice)}</div>` : ""}
            <div class="stats"><div class="stat"><span class="stat-label">LẦN ĐẦU / SAU HARNESS</span><strong>${pct(m.beforeRate)} / ${pct(m.afterRate)}</strong><small>Mẫu số ${num(m.planned)} mẫu kế hoạch</small></div><div class="stat"><span class="stat-label">ĐỘ PHỦ CHẤM</span><strong>${num(m.afterEvaluated)} / ${num(m.planned)}</strong><small>${num(m.recorded)} mẫu có bản ghi</small></div><div class="stat"><span class="stat-label">HARNESS CHẤP NHẬN</span><strong>${num(m.harnessAccepted)}</strong><small>Khác với chấm ngữ nghĩa độc lập</small></div><div class="stat"><span class="stat-label">LƯỢT GỌI AI</span><strong>${num(m.logicalCalls)}</strong><small>HTTP attempt ${num(m.httpAttempts)} · sửa ${num(m.repairs)}</small></div></div>
            <section class="panel"><div class="panel-head"><h2>Tiêu chí trước & sau</h2>${legend()}</div><div class="panel-body">${criteriaChart(m)}</div></section>
            <details><summary>Phiên bản & cấu hình đã đóng băng</summary><pre class="output">${esc(JSON.stringify({backendHash:detail.backendHash,promptHash:detail.promptHash,datasetHash:detail.datasetHash,runtime:detail.runtime,rubric:detail.rubric,repair:detail.repair,judge:detail.judge},null,2))}</pre></details>
            <div class="filters"><label>Trình độ / mục tiêu<select data-filter="level"><option value="">Tất cả mục tiêu</option>${[...new Set((detail.cases || []).map(r=>r.level))].map(l=>`<option value="${esc(l)}" ${filterLevel === l ? "selected" : ""}>${esc(l)}</option>`).join("")}</select></label>
            <label>Kết quả sau Harness<select data-filter="case"><option value="">Tất cả kết quả</option>${["PASS","FAIL","REVIEW_REQUIRED","MISSING"].map(s=>`<option value="${s}" ${filterCase === s ? "selected" : ""}>${labels[s][0]}</option>`).join("")}</select></label>
            <label>Bước xử lý<select data-filter="step"><option value="">Tất cả bước</option>${[...new Set((detail.cases || []).flatMap(r=>(r.steps || []).map(s=>s.step)))].map(s=>`<option value="${esc(s)}" ${filterStep === s ? "selected" : ""}>${esc(s)}</option>`).join("")}</select></label><span class="count">${rows.length} test</span></div>
            <section class="panel"><div class="panel-head"><h2>Bằng chứng từng test</h2></div>${!rows.length ? empty("Chưa có test phù hợp","Thay đổi bộ lọc hoặc chờ lần chạy ghi nhận kết quả.",true) : rows.map(row=>`<article class="case-card"><div class="case-title"><h3>${esc(row.key)} <span class="tag">${esc(row.level)} · lượt ${row.turn}</span></h3><span>${badge(row.status)} · ${num(row.seconds,1)} s</span></div>
                <details><summary>Xem output đầu tiên và sau Harness</summary><div class="side-by-side"><div><h3>Lần đầu</h3>${outputView(row,"before")}</div><div><h3>Sau Harness</h3>${outputView(row,"after")}</div></div></details>
                <details><summary>Các bước & lượt gọi API (${row.calls?.length || 0})</summary><pre class="output">${esc(JSON.stringify({steps:row.steps,calls:row.calls,error:row.error},null,2))}</pre></details>
                <details><summary>Input và lịch sử cố định</summary><pre class="output">${esc(JSON.stringify(row.input,null,2))}</pre></details></article>`).join("")}</section>`;
    }
    function compareView() {
        const options=id=>data.runs.map(r=>`<option value="${r.id}" ${id === r.id ? "selected" : ""}>${esc(r.label)} · ${suiteName(r.suite)} · ${stamp(r.createdAt)}</option>`).join("");
        return `${heading("So sánh phiên bản", "Ghép cặp từng test để nhận biết cải thiện, regression và các khác biệt về cấu hình.",false)}
            <form id="compare-form" class="compare-controls"><label>A · Baseline<select name="left" required><option value="">Chọn lần đánh giá</option>${options(left)}</select></label><label>B · Candidate<select name="right" required><option value="">Chọn lần đánh giá</option>${options(right)}</select></label><button class="primary" type="submit" ${data.runs.length < 2 ? "disabled" : ""}>So sánh</button></form>
            ${!comparison ? empty("Chọn baseline và candidate", "Biểu đồ đặt hai phiên bản trên cùng thang đo. Các test suy giảm sẽ hiện riêng.") : compareResult()}`;
    }
    function compareResult() {
        const a=data.runs.find(r=>r.id === left),b=data.runs.find(r=>r.id === right);
        if (!a || !b) return "";
        const causes={combined:"Prompt và backend, model hoặc chính sách sửa cùng thay đổi. Chỉ kết luận cho tổ hợp mới; chưa quy nguyên nhân riêng cho prompt.",prompt:"Chỉ prompt thay đổi trong các yếu tố đã ghi nhận.",configuration:"Cấu hình model hoặc đường gọi đã đổi. Kết quả thể hiện thay đổi của hệ thống.","backend-harness":"Backend hoặc chính sách sửa thay đổi; prompt giữ nguyên.",repeat:"Cùng cấu hình được chạy lại. Gemini không có seed cố định trong đường chạy này."};
        const warnings=comparison.warnings || [];
        return `${warnings.length ? `<div class="banner error"><strong>Chưa đủ điều kiện kết luận trực tiếp.</strong> ${warnings.map(esc).join(" · ")}</div>` : ""}<div class="banner">${esc(causes[comparison.attribution])}</div>
            ${comparison.criticalRegression ? '<div class="banner error"><strong>Có lỗi critical mới.</strong> Kết quả cần được xem xét dù tỷ lệ tổng tăng.</div>' : ""}
            <section class="panel"><div class="panel-head"><div><h2>Thay đổi theo từng test</h2><p>${esc(comparison.decision === "NO_REGRESSION" ? "Không thấy regression trong bằng chứng đã chấm" : comparison.decision === "REGRESSION" ? "Phát hiện regression" : "Chưa đủ bằng chứng để kết luận")}</p></div></div><div class="panel-body">${regressionChart(comparison.counts)}</div></section>
            <section class="panel"><div class="panel-head"><h2>Chất lượng sau Harness · A / B</h2>${legend(a.label,b.label)}</div><div class="panel-body">${criteriaChart(a.metrics,b.metrics,true)}</div></section>
            <section class="panel"><div class="panel-head"><h2>Chất lượng & hiệu suất</h2></div><div class="table-scroll"><table><thead><tr><th>Chỉ số</th><th>A · ${esc(a.label)}</th><th>B · ${esc(b.label)}</th></tr></thead><tbody>${[
                ["Đạt lần đầu",pct(a.metrics?.beforeRate),pct(b.metrics?.beforeRate)], ["Đạt sau Harness",pct(a.metrics?.afterRate),pct(b.metrics?.afterRate)],
                ["Độ phủ chấm",`${num(a.metrics?.afterEvaluated)}/${num(a.planned)}`,`${num(b.metrics?.afterEvaluated)}/${num(b.planned)}`],
                ["Harness sửa",num(a.metrics?.repairs),num(b.metrics?.repairs)],["Critical",num(a.metrics?.critical),num(b.metrics?.critical)],
                ["Lượt gọi AI",num(a.metrics?.logicalCalls),num(b.metrics?.logicalCalls)],["HTTP attempt",num(a.metrics?.httpAttempts),num(b.metrics?.httpAttempts)],
                ["Thời gian p95",`${num(a.metrics?.p95,1)} s`,`${num(b.metrics?.p95,1)} s`]
                ,["Lỗi thực thi",num(a.metrics?.errors),num(b.metrics?.errors)]
            ].map(values=>`<tr>${values.map((v,i)=>i ? `<td>${esc(v)}</td>` : `<th scope="row">${esc(v)}</th>`).join("")}</tr>`).join("")}</tbody></table></div></section>
            <section class="panel"><div class="panel-head"><h2>Đối chiếu output & nguyên nhân</h2></div>${[...comparison.cases].sort((x,y)=>(["regressed","unresolved","improved","unchanged"].indexOf(x.status)-["regressed","unresolved","improved","unchanged"].indexOf(y.status))).map(pair=>`<article class="case-card"><div class="case-title"><h3>${esc(pair.key)}</h3>${badge(pair.status)}</div><details ${pair.status === "regressed" ? "open" : ""}><summary>Đặt hai kết quả cạnh nhau</summary><div class="side-by-side"><div><h3>A · ${esc(a.label)}</h3>${outputView(pair.left,"after")}</div><div><h3>B · ${esc(b.label)}</h3>${outputView(pair.right,"after")}</div></div></details></article>`).join("")}</section>`;
    }
    function render(preserve = false) {
        if (!data) return;
        const opened = preserve ? [...main.querySelectorAll("details[open]")].map(n => [...main.querySelectorAll("details")].indexOf(n)) : [];
        $("#evaluation-nav").querySelectorAll("a").forEach(a=>{if (a.dataset.view === view || view === "detail" && a.dataset.view === "runs") a.setAttribute("aria-current","page");else a.removeAttribute("aria-current");});
        const body = view === "overview" ? overview() : view === "runs" ? `${heading("Các lần đánh giá", "Lịch sử bất biến của dữ liệu đầu vào, phiên bản và kết quả thực thi.")}${filters()}<section class="panel">${runTable(summaries())}</section>`
            : view === "versions" ? versionsView() : view === "compare" ? compareView() : detailView();
        main.innerHTML = `${offline ? '<div class="banner error" role="alert">Mất kết nối. Đang hiển thị snapshot cũ; tạm khóa tạo lần chạy.</div>' : ""}${body}`;
        opened.forEach(i => { const node = main.querySelectorAll("details")[i]; if (node) node.open = true; });
    }
    function openDialog(content) {
        dialog.returnFocus=document.activeElement; dialog.innerHTML=content;
        dialog.showModal(); dialog.querySelector("input,select,button")?.focus();
    }
    dialog.addEventListener("close",()=>{dialog.returnFocus?.focus();});
    function versionDialog() {
        openDialog(`<h2 id="dialog-title">Tạo phiên bản prompt</h2><p>Chỉ lưu thí nghiệm; không đổi prompt đang phục vụ người học.</p><form id="version-form"><label>Tên phiên bản<input name="label" maxlength="100" required placeholder="Ví dụ: V4.13 baseline"></label>
            <input type="hidden" name="pipeline" value="scenario"><p>Conversation AI · prompt hiện hành</p>
            <label data-scenario-version>Prompt<select name="release">${(data.scenarioPipeline?.releases || []).map(r=>`<option value="${esc(r.id)}">${esc(r.id)} · ${short(r.promptHash)}</option>`).join("")}</select></label>
            <p class="dialog-error" role="alert"></p><div class="dialog-actions"><button type="button" data-close>Hủy</button><button class="primary" type="submit">Lưu phiên bản</button></div></form>`);
    }
    function runDialog(versionId) {
        const versions = data?.versions.filter(canRun) || [];
        if (!versions.length) { versionDialog(); return; }
        openDialog(`<h2 id="dialog-title">Chạy đánh giá</h2><p>Nguồn và quota Gemini dùng cấu hình trong <a href="/admin/gemini">trang quản trị hiện có</a>. Lần chạy được ghi riêng với dữ liệu tổng hợp.</p>
            <form id="run-form"><label>Phiên bản<select name="version">${versions.map(v=>`<option value="${v.id}" ${v.id === versionId ? "selected" : ""}>${esc(v.label)}</option>`).join("")}</select></label>
            <label>Bộ kiểm thử<select name="suite"></select></label>
            <input type="hidden" name="mode" value="live">
            <label class="check"><input type="checkbox" name="judge" checked>Chấm ngữ nghĩa bằng AI (cùng model, ngữ cảnh chấm riêng; tái sử dụng verdict phù hợp)</label>
            <p id="run-cost" class="banner">Smoke: 3 mẫu. Mỗi mẫu thường cần tạo nội dung, Harness duyệt và chấm độc lập; sửa có thể phát sinh thêm lượt.</p><p class="dialog-error" role="alert"></p>
            <div class="dialog-actions"><button type="button" data-close>Hủy</button><button class="primary" type="submit">Bắt đầu đánh giá</button></div></form>`);
        dialog.querySelector("#run-form").requestId=crypto.randomUUID();
        syncRunDialog();
    }
    function syncRunDialog() {
        const id=dialog.querySelector('[name="version"]').value;
        const suites=runSuites(id) || [];
        dialog.querySelector('[name="suite"]').innerHTML=suites.map(s=>`<option value="${esc(s.id)}">${esc(s.name)} · ${s.samples} mẫu</option>`).join("");
        $("#run-cost").textContent="Smoke: 3 bài · mỗi bài EXTRACT + tối đa 9 lượt sinh/duyệt/sửa, thêm tối đa 2 lượt chấm độc lập. Không đo chất lượng GRADE hoặc TTS. Dừng sau bài đang chạy.";
    }

    document.addEventListener("click",async event=>{
        const button=event.target.closest("button,a"); if (!button) return;
        if (button.dataset.view) {event.preventDefault();navigate(button.dataset.view);}
        else if (button.hasAttribute("data-refresh")) refresh(true);
        else if (button.hasAttribute("data-new-version")) versionDialog();
        else if (button.hasAttribute("data-new-run")) runDialog(button.dataset.version);
        else if (button.hasAttribute("data-close")) dialog.close();
        else if (button.dataset.detail) navigate("detail",button.dataset.detail);
        else if (button.dataset.compareRun) {if (!left) left=button.dataset.compareRun;else right=button.dataset.compareRun;navigate("compare");}
        else if (button.dataset.stop && !busy) {
            busy=true;button.disabled=true;
            try {await api(`/runs/${button.dataset.stop}/stop`,{method:"POST"});message("Đã yêu cầu dừng. Lời gọi đang thực hiện được ghi nhận trước khi kết thúc.");await refresh();}
            catch(error){message(error.message);}finally{busy=false;button.disabled=false;}
        } else if (button.hasAttribute("data-export") && detail) {
            const url=URL.createObjectURL(new Blob([JSON.stringify(detail,null,2)],{type:"application/json;charset=utf-8"}));
            const link=document.createElement("a");link.href=url;link.download=`ai-evaluation-${detail.id}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
        }
    });
    document.addEventListener("change",event=>{
        const node=event.target;
        if(node.name==="version" && dialog.open) syncRunDialog();
        if (node.closest("#compare-form")) { comparison = null; main.querySelectorAll("#compare-form ~ *").forEach(n => n.remove()); }
        if (node.dataset.filter) {
            const key=node.dataset.filter;
            if(key==="version")filterVersion=node.value;if(key==="suite")filterSuite=node.value;if(key==="status")filterStatus=node.value;
            if(key==="runtime")filterRuntime=node.value;
            if(key==="level")filterLevel=node.value;if(key==="case")filterCase=node.value;if(key==="step")filterStep=node.value;
            const id=node.dataset.filter;render();main.querySelector(`[data-filter="${id}"]`)?.focus();
        }
        if(node.name==="suite" && dialog.open) {
            const suite=runSuites(dialog.querySelector('[name="version"]').value).find(s=>s.id===node.value);if(suite)$("#run-cost").textContent=`${suite.name}: ${suite.samples} mẫu. Quota từng nguồn vẫn áp dụng; mỗi mẫu có thể cần tạo, duyệt, chấm và sửa.`;
        }
    });
    document.addEventListener("submit",async event=>{
        const form=event.target;if(!["version-form","run-form","compare-form"].includes(form.id))return;event.preventDefault();
        if(busy)return;busy=true;const submit=form.querySelector('[type="submit"]');submit.disabled=true;
        try {
            const fields=new FormData(form);
            if(form.id==="version-form") {
                await api("/versions",{method:"POST",body:JSON.stringify({label:fields.get("label"),pipeline:fields.get("pipeline"),release:fields.get("release"),promptSuffix:"",repair:true})});
                dialog.close();await refresh();navigate("versions");message("Đã lưu phiên bản bất biến.");
            } else if(form.id==="run-form") {
                const payload={requestId:form.requestId,version:fields.get("version"),suite:fields.get("suite"),mode:fields.get("mode"),judge:fields.has("judge")};
                const run=await api("/runs",{method:"POST",body:JSON.stringify(payload)});dialog.close();await refresh();navigate("detail",run.id);
            } else {
                left=fields.get("left");right=fields.get("right");if(left===right)throw new Error("Chọn hai lần đánh giá khác nhau.");
                comparison = null;
                comparison=await api(`/compare?left=${encodeURIComponent(left)}&right=${encodeURIComponent(right)}`);render();
            }
        } catch(error) { const target=form.querySelector(".dialog-error");if(target)target.textContent=error.message;else message(error.message); }
        finally {busy=false;submit.disabled=false;}
    });
    window.addEventListener("popstate",()=>{const q=new URLSearchParams(location.search);view=q.get("view") || "overview";selected=q.get("run") || "";if(view==="detail")loadDetail(selected);else render();});
    document.addEventListener("visibilitychange",()=>{if(!document.hidden && data?.activeRun)refresh();});
    let resizeTimer;
    window.addEventListener("resize",()=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>{if(!dialog.open)render(true);},150);});
    refresh(true);
})();
