(() => {
    "use strict";
    const root=document.querySelector("#payment-content"),banner=document.querySelector("#payment-banner"),dialog=document.querySelector("#payment-dialog");
    const esc=v=>String(v??"").replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;").replaceAll('"',"&quot;");
    const money=v=>new Intl.NumberFormat("vi-VN",{style:"currency",currency:"VND"}).format(v??0);
    const stamp=v=>v?new Date(v).toLocaleString("vi-VN",{timeZone:"Asia/Ho_Chi_Minh"}):"—";
    const labels={CREATING:"Đang xác minh",PENDING:"Chờ thanh toán",PAID:"Đã thanh toán",CANCELLED:"Đã hủy",EXPIRED:"Hết hạn",OPEN:"Mới",INVESTIGATING:"Đang kiểm tra",RESOLVED:"Đã xử lý",ACCEPTED_EXCEPTION:"Ngoại lệ được chấp nhận",REQUESTED:"Chờ duyệt",APPROVED:"Đã duyệt",REJECTED:"Từ chối",UNKNOWN:"Đang xác minh kết quả",SUCCEEDED:"Đã hoàn",FAILED:"Thất bại"};
    const badge=s=>`<span class="payment-status" data-state="${esc(s)}">${esc(labels[s]||s)}</span>`;
    const endpoint="/api/admin/payments";
    let csrf,refreshing,me,tab="orders",page=0,filters={},generation=0;
    const has=p=>me?.permissions.includes(p);
    async function csrfToken(){const r=await fetch("/api/auth/csrf",{credentials:"same-origin",cache:"no-store"});if(!r.ok)throw new Error("Chưa lấy được thông tin bảo vệ yêu cầu.");csrf=await r.json();}
    async function api(path,options={}) {
        const mutation=options.method && options.method!=="GET";
        if(mutation && !csrf)await csrfToken();
        const request={credentials:"same-origin",cache:"no-store",...options,headers:{Accept:"application/json",...options.headers}};
        if(options.body!==undefined){request.body=JSON.stringify(options.body);request.headers["Content-Type"]="application/json";}
        if(mutation)request.headers[csrf.headerName]=csrf.token;
        let r=await fetch(path,request);
        if(r.status===401){
            refreshing??=(async()=>{await csrfToken();return fetch("/api/auth/refresh",{method:"POST",credentials:"same-origin",headers:{[csrf.headerName]:csrf.token}});})().finally(()=>{refreshing=null;});
            const refreshed=await refreshing;
            if(refreshed.ok){await csrfToken();if(mutation)request.headers[csrf.headerName]=csrf.token;r=await fetch(path,request);}
        }
        if(r.status===403 && r.headers.get("X-CSRF-Error")==="1"){await csrfToken();request.headers[csrf.headerName]=csrf.token;r=await fetch(path,request);}
        const body=r.headers.get("Content-Type")?.includes("application/json")?await r.json():null;
        if(!r.ok){if(r.status===401)location.assign("/login?next=/payments");throw new Error(body?.message||`Yêu cầu thất bại (${r.status}).`);}
        return body;
    }
    let commandKey;
    const get=path=>api(endpoint+path),post=(path,body)=>api(endpoint+path,{method:"POST",body,headers:{"Idempotency-Key":commandKey||crypto.randomUUID()}});
    function message(text,error=false){banner.textContent=text;banner.className=error?"payment-error":"";}
    function table(headers,rows){return `<div class="payment-table-wrap"><table class="payment-table"><thead><tr>${headers.map(h=>`<th scope="col">${esc(h)}</th>`).join("")}</tr></thead><tbody>${rows.join("")||`<tr><td colspan="${headers.length}">Chưa có dữ liệu.</td></tr>`}</tbody></table></div>`;}
    function pager(result){return `<nav class="payment-pager" aria-label="Phân trang"><button type="button" class="text-button" data-page="${result.page-1}" ${result.page===0?"disabled":""}>← Trước</button><span>Trang ${result.page+1} · ${result.total} mục</span><button type="button" class="text-button" data-page="${result.page+1}" ${(result.page+1)*result.size>=result.total?"disabled":""}>Sau →</button></nav>`;}
    function wirePager(){root.querySelectorAll("[data-page]").forEach(b=>b.addEventListener("click",()=>{page=Number(b.dataset.page);render();}));}
    async function busy(button,action){if(button.disabled)return;button.disabled=true;try{return await action();}catch(e){message(e.message,true);}finally{if(button.isConnected)button.disabled=false;}}
    function command(title,html,action){
        commandKey=crypto.randomUUID();
        document.querySelector("#payment-dialog-title").textContent=title;document.querySelector("#payment-dialog-body").innerHTML=`<div class="payment-form">${html}</div>`;
        document.querySelector("#payment-command-error").textContent="";
        const form=document.querySelector("#payment-command");form.onsubmit=async event=>{
            event.preventDefault();const button=form.querySelector('[type="submit"]');if(button.disabled)return;button.disabled=true;
            try{await action(Object.fromEntries(new FormData(form)));dialog.close();message("Đã lưu thao tác.");await render();}
            catch(e){document.querySelector("#payment-command-error").textContent=e.message;}finally{button.disabled=false;}
        };dialog.showModal();dialog.querySelector("input,textarea,select,button")?.focus();
    }
    const reasonField='<label>Lý do<textarea name="reason" maxlength="1000" required></textarea></label>';
    const otpField='<label>Mã từ ứng dụng xác thực<input name="otp" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" minlength="6" maxlength="6" required></label>';
    async function challenge(binding,otp){return (await post("/security/challenges",{binding,otp})).challenge;}
    async function render(){
        const version=++generation;root.setAttribute("aria-busy","true");message("");
        document.querySelectorAll("[data-tab]").forEach(b=>{if(b.dataset.tab===tab)b.setAttribute("aria-current","page");else b.removeAttribute("aria-current");});
        try{
            if(!has("PAYMENT_VIEW")){root.innerHTML='<div class="payment-empty"><h2>Chưa có quyền vận hành tài chính</h2><p>Quyền quản trị nội dung không tự cấp quyền xem hay xử lý giao dịch.</p><a href="/account">Về tài khoản</a></div>';return;}
            const renders={orders,cases,statements,refunds,reports,security};await renders[tab](version);
        }catch(e){if(version===generation){root.innerHTML=`<div class="payment-empty"><h2>Chưa tải được dữ liệu</h2><p role="alert">${esc(e.message)}</p><button type="button" data-retry class="primary-button">Thử lại</button></div>`;root.querySelector("[data-retry]").onclick=render;}}
        finally{if(version===generation)root.setAttribute("aria-busy","false");}
    }
    async function orders(version){
        const result=await get("/orders?"+new URLSearchParams({...filters,page}));if(version!==generation)return;
        root.innerHTML=`<form id="payment-filters" class="payment-filters"><label>Mã đơn<input name="q" type="search" inputmode="numeric" value="${esc(filters.q||"")}" maxlength="16"></label><label>Trạng thái<select name="status"><option value="">Tất cả</option>${Object.entries(labels).filter(([s])=>["CREATING","PENDING","PAID","CANCELLED","EXPIRED"].includes(s)).map(([s,label])=>`<option value="${s}" ${filters.status===s?"selected":""}>${label}</option>`).join("")}</select></label><label>Từ ngày<input type="date" name="from" value="${esc(filters.from||"")}"></label><label>Đến ngày<input type="date" name="to" value="${esc(filters.to||"")}"></label><label>Tối thiểu (đ)<input name="minAmount" type="number" min="0" step="1" value="${esc(filters.minAmount||"")}"></label><label>Tối đa (đ)<input name="maxAmount" type="number" min="0" step="1" value="${esc(filters.maxAmount||"")}"></label><button class="primary-button" type="submit">Lọc</button></form>
        ${table(["Mã đơn","Gói / chính sách","Số tiền","Trạng thái","Ngày tạo","Chi tiết"],result.items.map(o=>`<tr><td><strong>${esc(o.order_code)}</strong></td><td>${esc(o.duration_days)} ngày<br><small>${esc(o.policy_version)}</small></td><td class="payment-amount">${money(o.amount_vnd)}</td><td>${badge(o.status)}</td><td>${stamp(o.created_at)}</td><td><button type="button" class="text-button" data-order="${o.order_code}">Xem giao dịch →</button></td></tr>`))}${pager(result)}<p class="payment-note">Thời gian hiển thị theo giờ Việt Nam. Đây là chứng nhận thanh toán; hóa đơn thuế là chứng từ riêng.</p>`;
        root.querySelector("form").onsubmit=event=>{event.preventDefault();filters=Object.fromEntries([...new FormData(event.target)].filter(([,v])=>v!==""));page=0;render();};wirePager();
        root.querySelectorAll("[data-order]").forEach(b=>b.onclick=()=>busy(b,()=>orderDetail(b.dataset.order)));
    }
    async function orderDetail(code){
        const p=await get("/orders/"+code);
        root.innerHTML=`<button type="button" data-back class="text-button">← Danh sách giao dịch</button><div class="payment-card"><h2>Đơn ${esc(code)} · ${money(p.amount_vnd)}</h2><p>${badge(p.status)} · ${esc(p.duration_days)} ngày · ${esc(p.policy_version)}</p><div class="payment-actions">${has("PAYMENT_RECONCILE")?'<button type="button" data-reconcile class="primary-button">Yêu cầu đối soát</button>':""}${has("REFUND_REQUEST")&&p.status==="PAID"?'<button type="button" data-refund class="text-button">Lập hồ sơ hoàn tiền</button>':""}</div></div>
        <div class="payment-detail-grid"><div class="payment-card"><h3>Khoản tiền đã nhận</h3>${p.receipts.length?p.receipts.map(r=>`<p><strong>${money(r.amount_vnd)}</strong> · ${esc(r.reference)}<br><small>${r.bank_verified?"Đã đối chiếu chứng từ ngân hàng":"Chờ đối chiếu ngân hàng"} · ${stamp(r.effective_at)}</small>${has("PAYMENT_RECONCILE")&&r.bank_verified&&p.status!=="PAID"&&r.amount_vnd>=p.amount_vnd?`<br><button type="button" class="text-button" data-allocate="${r.id}">Xem & phân bổ theo giá đơn</button>`:""}</p>`).join(""):"<p>Chưa có receipt xác thực.</p>"}</div><div class="payment-card"><h3>Quyền lợi</h3>${p.grants.length?p.grants.map(g=>`<p>${stamp(g.starts_at)} → ${stamp(g.ends_at)}<br><small>${esc(g.source)} · đã điều chỉnh ${esc(g.revoked_seconds)} giây</small></p>`).join(""):"<p>Chưa có grant.</p>"}</div></div>
        <div class="payment-card"><h3>Dòng thời gian</h3><ol class="payment-timeline">${p.events.map(e=>`<li><strong>${esc(e.kind)}</strong> · ${esc(e.source)}<time>${stamp(e.recorded_at)}</time></li>`).join("")||"<li>Chưa có sự kiện.</li>"}</ol></div>
        <details class="payment-card"><summary>Sổ phụ · ${p.journals.length} dòng</summary>${table(["Loại","Tài khoản","Nợ","Có","Thời điểm"],p.journals.map(j=>`<tr><td>${esc(j.kind)}</td><td>${esc(j.account_code)}</td><td class="payment-amount">${money(j.debit_vnd)}</td><td class="payment-amount">${money(j.credit_vnd)}</td><td>${stamp(j.recorded_at)}</td></tr>`))}</details>
        <details class="payment-card"><summary>Đối soát & hồ sơ · ${p.cases.length} sai lệch / ${p.refunds.length} hoàn tiền</summary>${p.cases.map(c=>`<p>${badge(c.status)} ${esc(c.kind)} · ${esc(c.evidence)}</p>`).join("")}${p.refunds.map(r=>`<p>${badge(r.status)} ${money(r.amount_vnd)} · ${esc(r.reason)}</p>`).join("")}</details>`;
        root.querySelectorAll("[data-allocate]").forEach(b=>b.onclick=()=>{const r=p.receipts.find(r=>r.id===b.dataset.allocate);command("Phân bổ khoản tiền đã xác minh",`<p>Khoản nhận ${money(r.amount_vnd)}. Phân bổ ${money(p.amount_vnd)} cho ${p.duration_days} ngày. Phần thừa ${money(r.amount_vnd-p.amount_vnd)} tiếp tục là nghĩa vụ chưa phân bổ.</p>${reasonField}${otpField}`,async body=>post(`/orders/${code}/allocate-reviewed`,{receiptId:r.id,expectedVersion:p.edit_version,reason:body.reason,challenge:await challenge(r.allocationBinding,body.otp)}));});
        root.querySelector("[data-back]").onclick=render;
        root.querySelector("[data-reconcile]")?.addEventListener("click",()=>command("Yêu cầu đối soát",`<p>Đọc chứng cứ mới từ payOS cho đơn ${esc(code)}. Quyền Premium chỉ đổi nếu receipt được xác minh.</p>${reasonField}`,body=>post(`/orders/${code}/reconcile`,body)));
        root.querySelector("[data-refund]")?.addEventListener("click",()=>refundRequest(code,p.amount_vnd));
    }
    function refundRequest(code,amount){
        let previewed;
        command("Lập hồ sơ hoàn tiền",`<p>Hồ sơ cần nguồn tiền đã đối chiếu và người duyệt độc lập. ${me.refundsEnabled?"":"Thực thi hoàn tiền hiện đang tắt."}</p><label>Số tiền (đ)<input name="amountVnd" type="number" step="1" min="1" max="${amount}" value="${amount}" required></label><label>Fingerprint nguồn chuyển đã xác minh<input name="recipientFingerprint" pattern="[a-f0-9]{64}" maxlength="64" required></label><label>Nhãn người nhận đã che<input name="recipientMask" maxlength="80" placeholder="MB · ••••1234" required></label>${reasonField}<button type="button" class="text-button" data-preview>Xem tác động quyền lợi</button><p data-impact role="status"></p>`,async body=>{
            if(previewed!==Number(body.amountVnd))throw new Error("Hãy bấm Xem tác động quyền lợi trước khi gửi.");
            await post(`/orders/${code}/refund-requests`,{...body,amountVnd:Number(body.amountVnd)});
        });
        dialog.querySelector("[data-preview]").onclick=async event=>busy(event.target,async()=>{const form=document.querySelector("#payment-command"),impact=await get(`/orders/${code}/refund-preview?amountVnd=${Number(form.elements.amountVnd.value)}`);previewed=Number(form.elements.amountVnd.value);dialog.querySelector("[data-impact]").textContent=`Còn đủ điều kiện: ${money(impact.refundableVnd)}. Tối đa thu hồi ${impact.secondsToRevoke} giây chưa sử dụng; chính sách ${impact.policyVersion}.`;});
    }
    async function cases(version){
        const result=await get("/reconciliation-cases?page="+page);if(version!==generation)return;
        root.innerHTML=`<h2>Sai lệch cần xử lý</h2><p class="payment-note">Đóng case lưu quyết định và chứng cứ. Tiền và Premium được xử lý từ nguồn xác thực.</p>${table(["Loại / đơn","Trạng thái","Chứng cứ","Hạn xử lý","Thao tác"],result.items.map(c=>`<tr><td>${esc(c.kind)}<br>${esc(c.order_code??"Chưa khớp đơn")}</td><td>${badge(c.status)}</td><td>${esc(c.evidence)}${c.resolution?`<br><small>${esc(c.resolution)}</small>`:""}</td><td>${stamp(c.due_at)}</td><td>${has("PAYMENT_RECONCILE")&&["OPEN","INVESTIGATING"].includes(c.status)?`<button class="text-button" type="button" data-case="${esc(c.id)}">Xử lý</button>`:"—"}</td></tr>`))}${pager(result)}`;wirePager();
        root.querySelectorAll("[data-case]").forEach(b=>b.onclick=()=>{const c=result.items.find(c=>c.id===b.dataset.case);command("Xử lý sai lệch",`<label>Hành động<select name="action"><option value="CLAIM">Nhận xử lý</option><option value="RESOLVE">Đóng với chứng cứ</option><option value="ACCEPT_EXCEPTION">Chấp nhận ngoại lệ</option>${c.kind==="RETRY_EXHAUSTED"?'<option value="REPLAY">Xử lý lại inbox</option>':""}</select></label>${reasonField}<label>Chứng cứ / mã hồ sơ<textarea name="evidence" maxlength="1000" required minlength="10"></textarea></label>`,body=>post(`/reconciliation-cases/${c.id}/actions`,{...body,expectedVersion:c.version}));});
    }
    async function statements(version){
        if(!has("PAYMENT_RECONCILE"))throw new Error("Bạn chưa có quyền đối soát.");
        const imports=await get("/statements");if(version!==generation)return;
        root.innerHTML=`<h2>Đối chiếu ngân hàng</h2><p class="payment-note">CSV chuẩn hóa tối đa 256 KiB, 2.000 dòng. Chỉ dòng DICTALAB được đối chiếu; PERSONAL nằm ngoài sổ phụ. Dữ liệu thật cần nguồn sao kê đáng tin.</p><div class="payment-card"><form class="payment-form" id="statement-form"><label>Nguồn chứng từ<input name="source" maxlength="80" placeholder="Sao kê MB / ngày xuất" required></label><label>File CSV<input type="file" name="file" accept=".csv,text/csv" required></label><p class="payment-note">Cột bắt buộc: reference,amountVnd,direction,date,scope<br>Ngày YYYY-MM-DD · IN/OUT · DICTALAB/PERSONAL.</p><button class="primary-button" type="submit">Kiểm tra & xem trước</button></form></div><div id="statement-preview"></div>${table(["Ngày nhập","Nguồn","Số dòng","Trạng thái"],imports.map(i=>`<tr><td>${stamp(i.created_at)}</td><td><button type="button" class="text-button" data-statement="${i.id}">${esc(i.source)}</button></td><td>${i.line_count}</td><td>${esc(i.status)}</td></tr>`))}`;
        root.querySelectorAll("[data-statement]").forEach(b=>b.onclick=()=>busy(b,()=>showStatement(b.dataset.statement)));
        root.querySelector("form").onsubmit=event=>{event.preventDefault();const form=event.target,file=form.elements.file.files[0];busy(form.querySelector("button"),async()=>{
            if(file.size>262144)throw new Error("File vượt 256 KiB.");const p=await post("/statements/preview",{source:form.elements.source.value,csv:await file.text()});
            const preview=root.querySelector("#statement-preview");preview.innerHTML=`<div class="payment-card"><h3>Xem trước · ${p.line_count} dòng</h3>${table(["Tham chiếu","Số tiền","Chiều","Ngày","Phạm vi"],p.lines.slice(0,100).map(l=>`<tr><td>${esc(l.reference)}</td><td class="payment-amount">${money(l.amount_vnd)}</td><td>${esc(l.direction)}</td><td>${esc(l.business_date)}</td><td>${esc(l.scope)}</td></tr>`))}<p>Hiển thị tối đa 100 dòng đầu. Toàn bộ ${p.line_count} dòng đã được kiểm tra.</p>${p.status==="PREVIEW"?'<button type="button" class="primary-button" data-confirm>Xác nhận nhập</button>':'<p>File này đã được nhập.</p>'}</div>`;
            preview.querySelector("[data-confirm]")?.addEventListener("click",()=>command("Xác nhận nhập sao kê",`<p>${esc(p.source)} · ${p.line_count} dòng · checksum ${esc(p.file_hash.slice(0,12))}…</p>${reasonField}`,body=>post(`/statements/${p.id}/confirm`,{fileHash:p.file_hash,...body})));
        });};
    }
    async function refunds(version){
        const result=await get("/refunds?page="+page),items=result.items;if(version!==generation)return;
        root.innerHTML=`<h2>Hồ sơ hoàn tiền</h2><p class="payment-note">${me.refundsEnabled?"Chuyển hoàn thủ công, xác nhận bằng chứng ngân hàng và checker độc lập.":"Thực thi hoàn tiền đang tắt. Có thể tiếp nhận và xem hồ sơ; cần hoàn tất chính sách và người kiểm soát trước khi bật."} Kết quả UNKNOWN tiếp tục giữ chỗ số tiền.</p>${table(["Đơn","Số tiền","Trạng thái","Người nhận đã che","Lý do","Chi tiết"],items.map(r=>`<tr><td>${r.order_code}</td><td class="payment-amount">${money(r.amount_vnd)}</td><td>${badge(r.status)}</td><td>${esc(r.recipient_mask)}</td><td>${esc(r.reason)}</td><td><button type="button" class="text-button" data-refund="${r.id}">Xem hồ sơ</button></td></tr>`))}`;
        root.insertAdjacentHTML("beforeend",pager(result));wirePager();
        root.querySelectorAll("[data-refund]").forEach(b=>b.onclick=()=>busy(b,async()=>{
            const r=await get("/refunds/"+b.dataset.refund);const options=[];
            if(r.status==="REQUESTED"&&has("REFUND_APPROVE")){if(me.refundsEnabled)options.push(["approve","Duyệt & giữ chỗ",r.approveBinding]);options.push(["reject","Từ chối",null]);}
            if(["APPROVED","UNKNOWN"].includes(r.status)&&!r.evidence_line_key&&has("REFUND_EXECUTE")&&me.refundsEnabled)options.push(["execution-evidence",r.status==="UNKNOWN"?"Bổ sung chứng từ, không chuyển lại":"Ghi chứng cứ chuyển hoàn",r.executeBinding]);
            if(r.status==="APPROVED"&&has("REFUND_APPROVE"))options.push(["cancel-approval","Hủy phê duyệt trước khi thực hiện",r.cancelBinding]);
            if(r.status==="UNKNOWN"&&has("REFUND_APPROVE")&&me.refundsEnabled)options.push(["confirm","Checker xác nhận kết quả",r.confirmBinding]);
            if(!options.length){message(`Hồ sơ ${r.id}: ${labels[r.status]||r.status}. Chưa có thao tác được phép.`);return;}
            command("Hồ sơ hoàn tiền",`<p>Đơn ${r.order_code} · ${money(r.amount_vnd)} · ${esc(r.recipient_mask)}</p><p>Ảnh hưởng đã chốt: tối đa ${r.seconds_to_revoke} giây. Người lập không tự duyệt.</p><label>Thao tác<select name="action">${options.map(([key,label])=>`<option value="${key}">${label}</option>`).join("")}</select></label>${reasonField}${otpField}${["APPROVED","UNKNOWN"].includes(r.status)?'<label>Mã dòng chứng từ ngân hàng (để trống nếu kết quả chưa rõ)<input name="evidenceLineKey" maxlength="64"></label>':""}`,async body=>{
                const option=options.find(([key])=>key===body.action),token=option[2]?await challenge(option[2],body.otp):null;
                await post(`/refunds/${r.id}/${body.action}`,{expectedVersion:r.version,challenge:token,reason:body.reason,evidenceLineKey:body.evidenceLineKey,recipientFingerprint:r.recipient_hash});
            });
        }));
    }
    async function reports(version){
        if(!has("PAYMENT_AUDIT"))throw new Error("Bạn chưa có quyền báo cáo.");
        const [report,closes,reopens,audit]=await Promise.all([get("/reports"),get("/closes"),get("/close-reopens"),get("/audit?page="+page)]);if(version!==generation)return;
        root.innerHTML=`<h2>Sổ phụ & báo cáo</h2><p class="payment-note">Phạm vi: giao dịch DictaLab đã nhận diện. Đây là sổ phụ vận hành; không đại diện toàn bộ số dư ngân hàng hoặc sổ kế toán pháp định. Cutoff ${stamp(report.cutoff)}.</p><div class="payment-stat-grid">${[["Tiền đã nhận",report.receiptsVnd],["Tiền đã hoàn",report.refundsVnd],["Thu ròng",report.netReceiptsVnd]].map(([label,amount])=>`<div class="payment-card">${label}<strong>${money(amount)}</strong></div>`).join("")}</div><p>${report.journalImbalances===0?"✓ Các journal đã ghi sổ cân bằng.":"Cần kiểm tra journal lệch."} · ${report.unresolvedCases} case chưa xử lý · ${report.acceptedExceptions} ngoại lệ được chấp nhận.</p>
        ${table(["Tài khoản sổ phụ","Tổng Nợ","Tổng Có"],report.balances.map(b=>`<tr><td>${esc(b.account_code)}</td><td class="payment-amount">${money(b.debit_vnd)}</td><td class="payment-amount">${money(b.credit_vnd)}</td></tr>`))}<div class="payment-actions"><a class="text-button" href="/api/admin/payments/export">Xuất CSV 30 ngày</a><button type="button" class="text-button" data-close-day>Chốt ngày</button></div><h3>Snapshot đã khóa</h3>${table(["Ngày nghiệp vụ","Phiên bản","Checksum","Thao tác"],closes.map(c=>`<tr><td>${esc(c.business_date)}</td><td>${c.version}</td><td><code>${esc(c.digest.slice(0,20))}…</code></td><td><button type="button" class="text-button" data-reopen="${c.id}">Yêu cầu mở lại</button></td></tr>`))}`;
        root.insertAdjacentHTML("beforeend",`<h3>Yêu cầu mở lại</h3>${table(["Ngày","Lý do","Phê duyệt","Thao tác"],reopens.map(r=>`<tr><td>${esc(r.business_date)}</td><td>${esc(r.reason)}</td><td>${r.approved_at?stamp(r.approved_at):"Chờ người khác duyệt"}</td><td>${r.approved_at?`<button class="text-button" type="button" data-reclose="${r.id}">Chốt phiên bản mới</button>`:`<button class="text-button" type="button" data-approve-reopen="${r.id}">Duyệt mở lại</button>`}</td></tr>`))}<details class="payment-card"><summary>Nhật ký thao tác · trang ${page+1}</summary>${table(["Thời điểm","Hành động","Tài nguyên","Lý do"],audit.map(a=>`<tr><td>${stamp(a.created_at)}</td><td>${esc(a.action)}</td><td>${esc(a.resource_id)}</td><td>${esc(a.reason)}</td></tr>`))}<div class="payment-actions"><button type="button" class="text-button" data-audit-prev ${page===0?"disabled":""}>Trước</button><button type="button" class="text-button" data-audit-next ${audit.length<100?"disabled":""}>Sau</button></div></details>`);
        root.querySelector("[data-audit-prev]").onclick=()=>{page=Math.max(0,page-1);render();};
        root.querySelector("[data-audit-next]").onclick=()=>{page++;render();};
        root.querySelectorAll("[data-approve-reopen]").forEach(b=>b.onclick=()=>{const r=reopens.find(r=>r.id===b.dataset.approveReopen);command("Duyệt mở lại",`${reasonField}${otpField}`,async body=>post(`/close-reopens/${r.id}/approve`,{reason:body.reason,challenge:await challenge(r.binding,body.otp)}));});
        root.querySelectorAll("[data-reclose]").forEach(b=>b.onclick=()=>{const r=reopens.find(r=>r.id===b.dataset.reclose);command("Chốt phiên bản mới",`<p>Ngày ${esc(r.business_date)}. Snapshot cũ được giữ nguyên.</p>${reasonField}`,body=>post("/closes",{date:r.business_date,reopenId:r.id,...body}));});
        root.querySelector("[data-close-day]").onclick=()=>command("Chốt ngày nghiệp vụ",`<label>Ngày đã kết thúc<input name="date" type="date" required></label>${reasonField}`,body=>post("/closes",body));
        root.querySelectorAll("[data-reopen]").forEach(b=>b.onclick=()=>command("Yêu cầu mở lại snapshot",`<p>Snapshot cũ được giữ; phiên bản mới cần người duyệt độc lập.</p>${reasonField}`,body=>post(`/closes/${b.dataset.reopen}/reopen`,body)));
    }
    async function showStatement(id){
        const p=await get("/statements/"+id);
        command("Chứng từ đã nhập",`<p>${esc(p.source)} · ${esc(p.status)}</p>${table(["Tham chiếu","Số tiền","Chiều","Mã chứng từ"],p.lines.filter(l=>l.scope==="DICTALAB").map(l=>`<tr><td>${esc(l.reference)}</td><td>${money(l.amount_vnd)}</td><td>${esc(l.direction)}</td><td><code>${esc(l.line_key)}</code></td></tr>`))}`,async()=>{});
    }
    async function security(version){
        if(version!==generation)return;
        root.innerHTML=`<h2>Quyền & xác thực</h2><div class="payment-card"><h3>Quyền hiện tại</h3><p>${me.permissions.map(esc).join(" · ")}</p><p>Quyền được đọc lại trên máy chủ ở mỗi thao tác.</p></div><div class="payment-card"><h3>Xác thực tăng cường</h3><p>${me.totpEnabled?"TOTP đã bật. Mỗi mã chỉ được dùng một lần.":"Thiết lập ứng dụng xác thực trước khi duyệt hoặc xác nhận chi."}</p>${!me.totpEnabled?'<button type="button" class="primary-button" data-enroll>Thiết lập TOTP</button>':""}</div>${has("PAYMENT_MANAGE")?'<div class="payment-card"><h3>Cấp hoặc thu hồi quyền</h3><p>Dùng ID tài khoản đã xác minh. Người vận hành không tự đổi quyền của mình.</p><button type="button" class="text-button" data-permissions>Thay đổi quyền</button></div>':""}`;
        root.querySelector("[data-enroll]")?.addEventListener("click",()=>{
            command("Xác minh để thiết lập TOTP",'<label>Mật khẩu tài khoản<input type="password" name="password" autocomplete="current-password" maxlength="72" required></label>',async body=>{
                const enrollment=await post("/security/totp/enroll",body);
                setTimeout(()=>command("Thêm DictaLab vào ứng dụng xác thực",`<p>Nhập khóa sau vào ứng dụng xác thực. Khóa chỉ hiển thị trong phiên này.</p><div class="payment-secret">${esc(enrollment.secret)}</div>${otpField}`,async body=>{await post("/security/totp/confirm",body);me=await get("/me");}),0);
            });
        });
        root.querySelector("[data-permissions]")?.addEventListener("click",()=>command("Thay đổi quyền tài chính",`<label>ID tài khoản<input type="number" name="user" min="1" step="1" required></label><label>Các quyền, ngăn cách bằng dấu phẩy<input name="permissions" maxlength="300" placeholder="PAYMENT_VIEW,PAYMENT_RECONCILE"></label>${reasonField}${otpField}`,async body=>{
            const permissions=[...new Set(body.permissions.split(",").map(v=>v.trim()).filter(Boolean))].sort();
            const bytes=new TextEncoder().encode(`PERMISSIONS|${body.user}|${permissions.join(",")}`),binding=[...new Uint8Array(await crypto.subtle.digest("SHA-256",bytes))].map(b=>b.toString(16).padStart(2,"0")).join("");
            await post(`/permissions/${body.user}`,{permissions,reason:body.reason,challenge:await challenge(binding,body.otp)});
        }));
    }
    document.querySelectorAll("[data-tab]").forEach(b=>b.onclick=()=>{tab=b.dataset.tab;page=0;history.replaceState({},"","/payments?tab="+tab);render();});
    dialog.querySelector("[data-close]").onclick=()=>dialog.close();
    (async()=>{try{const requested=new URLSearchParams(location.search).get("tab");if(["orders","cases","statements","refunds","reports","security"].includes(requested))tab=requested;me=await get("/me");await render();}catch(e){root.innerHTML=`<div class="payment-empty"><p role="alert">${esc(e.message)}</p><a href="/login?next=/payments">Đăng nhập</a></div>`;root.setAttribute("aria-busy","false");}})();
})();
