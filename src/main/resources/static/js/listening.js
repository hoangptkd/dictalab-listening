(() => {
    "use strict";

    const bridge = () => window.DictaLabListeningBridge;
    const root = () => document.querySelector("#listening-content");
    let sessionId = null;
    let reportTimer = null;
    let playing = false;
    let revision = 0;
    let saveTimer = null;
    let draftSave = Promise.resolve();
    let flushDraft = null;
    let reviewPlayback = 0;

    function leave() {
        root()?.querySelector("audio")?.pause();
        flushDraft?.();
        flushDraft = null;
        revision++;
        reviewPlayback++;
        window.clearTimeout(reportTimer);
        window.clearTimeout(saveTimer);
        bridge().stopRecordedAudio();
    }

    async function loadView(path) {
        flushDraft?.();
        flushDraft = null;
        const current = ++revision;
        reviewPlayback++;
        bridge().stopRecordedAudio();
        window.clearTimeout(reportTimer);
        window.clearTimeout(saveTimer);
        try {
            const data = await bridge().api(path);
            return current === revision && ["listening", "admin-listening"].includes(document.body.dataset.route) ? data : null;
        } catch (error) {
            if (current === revision) {
                bridge().showToast(error.message, true);
                root().innerHTML = '<p role="alert">Không tải được nội dung. Vui lòng thử lại.</p><button type="button" class="text-button">Thử lại</button>';
                root().querySelector("button").addEventListener("click", () => show(sessionId));
            }
            return null;
        }
    }

    function escape(value) {
        return String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;").replaceAll('"', "&quot;");
    }

    function followLink(link, action) {
        link?.addEventListener("click", event => {
            if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            action();
        });
    }

    async function show(id) {
        window.clearTimeout(reportTimer);
        if (playing && id !== sessionId) bridge().stopRecordedAudio();
        sessionId = id || null;
        const query = new URLSearchParams(location.search);
        if (document.body.dataset.route === "admin-listening") {
            if (bridge().isAdmin()) await admin(query.get("edit"));
            return;
        }
        if (!id) {
            if (query.get("view") === "history") await history(Number(query.get("page") || 1));
            else await catalog();
            return;
        }
        if (query.get("view") === "review") {
            await review();
            return;
        }
        try {
            const state = await loadView(`/api/v1/listening/sessions/${encodeURIComponent(id)}`);
            if (!state) return;
            if (state.stage === "COMPLETE") await report();
            else renderStage(state);
        } catch (error) {
            root().textContent = error.message;
        }
    }

    async function catalog() {
        const units = await loadView("/api/v1/listening/units");
        if (!units) return;
        const levelNumbers = {};
        const numberedUnits = units.map(unit => ({...unit,
            number: levelNumbers[unit.level] = (levelNumbers[unit.level] || 0) + 1}));
        root().innerHTML = units.length ? `
            <label for="listening-level">Trình độ</label>
            <select id="listening-level">
                <option value="">Tất cả (${units.length})</option>
                ${["A1", "A2", "B1", "B2"].map(level => `<option value="${level}">${level} (${units.filter(unit => unit.level === level).length})</option>`).join("")}
            </select>
            <p class="catalog-order-note">Thứ tự theo bài nguồn đã biên tập. Số bài được đánh riêng trong từng cấp độ, không biểu thị độ khó.</p>
            <div class="listening-grid">${numberedUnits.map(unit => `
                <article class="listening-card" data-level="${escape(unit.level)}">
                    <span class="level-stamp">${escape(unit.level)}</span>
                    <h2>${escape(`Bài nghe ${unit.level} · Bài ${String(unit.number).padStart(2, "0")}`)}</h2>
                    <p>${Math.ceil(Number(unit.duration_seconds) / 60)} phút · ${Number(unit.prior_sessions) ? "Đã có lịch sử học" : "Chưa ghi nhận học"}</p>
                    <label><input type="checkbox" class="listening-heard-before"> Tôi đã nghe audio này trước đây</label>
                    <div class="listening-actions">
                        <button class="primary-button" type="button" data-unit="${unit.id}" data-mode="ONE_PASS">Bắt đầu đánh giá</button>
                    </div>
                </article>`).join("")}</div>`
            : "<p>Chưa có bài nghe hiểu được xuất bản.</p>";
        root().insertAdjacentHTML("afterbegin", `
            <a class="text-button" href="/listening?view=history" id="listening-history">Lịch sử nghe hiểu</a>`);
        const levelSelect = root().querySelector("#listening-level");
        if (levelSelect) {
            levelSelect.value = new URLSearchParams(location.search).get("level") || "";
            if (levelSelect.selectedIndex < 0) levelSelect.value = "";
        }
        const filter = () => {
            root().querySelectorAll(".listening-card").forEach(card => {
                card.hidden = Boolean(levelSelect.value) && card.dataset.level !== levelSelect.value;
            });
            bridge().paginateList(root().querySelector(".listening-grid"));
        };
        if (levelSelect) filter();
        levelSelect?.addEventListener("change", () => {
            const url = new URL(location.href);
            url.searchParams.set("level", levelSelect.value);
            url.searchParams.set("page", "1");
            window.history.replaceState(window.history.state, "", url);
            filter();
        });
        root().querySelectorAll("[data-unit]").forEach(button => button.addEventListener("click", async () => {
            button.disabled = true;
            try {
                const state = await bridge().api("/api/v1/listening/sessions", {
                    method: "POST", body: {unitId: Number(button.dataset.unit), mode: button.dataset.mode,
                        heardBefore: button.closest(".listening-card").querySelector(".listening-heard-before").checked}
                });
                await bridge().navigate("listening", {sessionId: state.sessionId});
            } catch (error) { bridge().showToast(error.message, true); button.disabled = false; }
        }));
        followLink(root().querySelector("#listening-history"), () => bridge().navigate("listening", {view: "history"}));
    }

    function contentEditor(content) {
        const facts = content?.facts || Array.from({length: 4}, () => ({text: ""}));
        const questions = content?.questions || Array.from({length: 5}, () =>
            ({type: "DETAIL", text: "", options: ["", "", ""], correct: 0, explanation: ""}));
        return `<label>Các ý chính, mỗi dòng một ý
            <textarea name="facts" rows="6" required>${escape(facts.map(item => item.text).join("\n"))}</textarea></label>
            <div class="listening-editor-questions">${Array.from({length: 8}, (_, index) => {
                const q = questions[index] || {type: "DETAIL", text: "", options: ["", "", ""], correct: 0, explanation: ""};
                return `<details class="listening-editor-question" ${index === 0 ? "open" : ""}><summary>Câu hỏi ${index + 1}${index < 5 ? " · bắt buộc" : " · tùy chọn"}</summary>
                    <label>Nội dung câu hỏi<input name="q${index}text" maxlength="1000" value="${escape(q.text)}"></label>
                    <label>Loại câu hỏi<select name="q${index}type">${["MAIN_IDEA", "DETAIL", "INFERENCE", "SPEAKER_INTENT"]
                        .map(type => `<option value="${type}" ${q.type === type ? "selected" : ""}>${({MAIN_IDEA:"Ý chính",DETAIL:"Chi tiết",INFERENCE:"Suy luận",SPEAKER_INTENT:"Ý định người nói"})[type]}</option>`).join("")}</select></label>
                    ${[0, 1, 2, 3].map(choice => `<label>Lựa chọn ${choice + 1}<input name="q${index}option${choice}"
                        maxlength="1000" value="${escape(q.options?.[choice] || "")}"></label>`).join("")}
                    <label>Đáp án đúng<select name="q${index}correct">${[0, 1, 2, 3].map(choice => `<option value="${choice}"
                        ${q.correct === choice ? "selected" : ""}>Lựa chọn ${choice + 1}</option>`).join("")}</select></label>
                    <label>Giải thích<textarea name="q${index}explanation" rows="2" maxlength="2000">${escape(q.explanation)}</textarea></label></details>`;
            }).join("")}</div>`;
    }

    function readContent(form) {
        const invalid=(name,message)=>{
            if(typeof window!=="undefined" && window.DictaLabAdmin) window.DictaLabAdmin.fieldError(form,name,message);
            throw new Error(message);
        };
        const facts = form.elements.namedItem("facts").value.split(/\r?\n/).map(value => value.trim()).filter(Boolean)
            .map((text, index) => ({id: `F${index + 1}`, text}));
        if(facts.length<4 || facts.length>12) invalid("facts","Cần từ 4 đến 12 ý chính, mỗi dòng một ý.");
        const questions = [];
        for (let index = 0; index < 8; index++) {
            const text = form.elements.namedItem(`q${index}text`).value.trim();
            if (!text) { if(index<5) invalid(`q${index}text`,`Câu ${index+1}: vui lòng nhập nội dung câu hỏi.`); continue; }
            const choices = [0, 1, 2, 3].map(choice => ({index:choice,text:form.elements.namedItem(`q${index}option${choice}`).value.trim()})).filter(choice => choice.text);
            const options = choices.map(choice=>choice.text);
            const correct = choices.findIndex(choice => choice.index === Number(form.elements.namedItem(`q${index}correct`).value));
            if (correct < 0) invalid(`q${index}correct`,`Câu ${index+1}: đáp án đúng đang trỏ vào một lựa chọn trống.`);
            if(options.length<3) invalid(`q${index}option0`,`Câu ${index+1}: cần ít nhất 3 lựa chọn.`);
            if(new Set(options.map(value=>value.toLowerCase())).size!==options.length) invalid(`q${index}option0`,`Câu ${index+1}: các lựa chọn không được trùng nhau.`);
            if(!form.elements.namedItem(`q${index}explanation`).value.trim()) invalid(`q${index}explanation`,`Câu ${index+1}: cần giải thích đáp án.`);
            questions.push({id: `Q${questions.length + 1}`, text,
                type: form.elements.namedItem(`q${index}type`).value, options,
                correct,
                explanation: form.elements.namedItem(`q${index}explanation`).value.trim()});
        }
        if(!questions.some(q=>q.type==="MAIN_IDEA") || !questions.some(q=>q.type==="DETAIL")) invalid("q0type","Cần ít nhất một câu hỏi ý chính và một câu hỏi chi tiết.");
        return {facts, questions};
    }

    async function admin(editId) {
        const ui=window.DictaLabAdmin, current=++revision;
        root().querySelector("audio")?.pause();
        bridge().stopRecordedAudio();
        const filters=ui.query({q:'',level:'',status:'',lessonId:'',sort:'updated',page:1,edit:editId||'',create:''});
        root().setAttribute('aria-busy','true');
        const go=params=>{ if(!ui.canLeave()) return; ui.url(params); admin(params.edit); };
        const field=(name,label,options,value)=>`<label>${label}<select name="${name}">${options.map(([key,text])=>`<option value="${key}" ${String(key)===String(value)?'selected':''}>${escape(text)}</option>`).join('')}</select></label>`;
        const states=[['','Tất cả'],['DRAFT','Bản nháp'],['PUBLISHED','Đã xuất bản'],['RETIRED','Đã ngừng']];
        const reportError=message=>{ const node=root().querySelector('[data-status]'); if(node){node.textContent=message; node.dataset.error='true';} };
        function links() {
            root().querySelectorAll('[data-listening-admin-link]').forEach(link=>followLink(link,()=>{
                if(!ui.canLeave()) return; window.history.pushState({},'',link.href); ui.rememberUrl(); admin(new URL(link.href).searchParams.get('edit'));
            }));
        }
        try {
            if(filters.edit) {
                const [selected,events]=await Promise.all([bridge().api(`/api/admin/listening/units/${encodeURIComponent(filters.edit)}`),bridge().api(`/api/admin/listening/units/${encodeURIComponent(filters.edit)}/events`)]);
                if(current!==revision || document.body.dataset.route!=='admin-listening') return;
                const editable=!selected.published && !selected.retired;
                const back=new URL(location.href); back.searchParams.delete('edit'); back.searchParams.delete('create');
                root().innerHTML=`<a class="text-button" data-listening-admin-link href="${escape(back.pathname+back.search)}">← Danh sách bài nghe hiểu</a>
                    <section class="admin-panel"><h2>${editable?'Duyệt bản nháp':'Xem nội dung'} #${selected.id}</h2>
                    <p>${ui.badge(selected.published?'Đã xuất bản':selected.retired?'Đã ngừng':'Bản nháp',selected.published?'good':'warn')} · Level ${escape(selected.level)} · ${Math.round(selected.duration_seconds)} giây</p>
                    <audio id="admin-unit-audio" controls preload="metadata" src="${escape(selected.audio_url)}"></audio>
                    <details><summary>Transcript theo đoạn</summary><ol class="admin-transcript">${selected.transcript.map((sentence,index)=>`<li data-chunk="${index}"><button class="text-button" type="button" data-segment="${index}">${Number(sentence.audio_start_seconds).toFixed(1)}s · Nghe đoạn</button> ${escape(sentence.transcript)}</li>`).join('')}</ol></details>
                    <form id="listening-edit-unit" class="listening-admin-form" data-admin-dirty="unit-${selected.id}"><fieldset ${editable?'':'disabled'}>
                    <legend class="sr-only">Thông tin, ý chính và câu hỏi</legend><label>Tiêu đề<input name="title" value="${escape(selected.title)}" maxlength="140" required></label>
                    <p class="admin-summary">4–12 ý chính, 5–8 câu hỏi; cần có câu hỏi ý chính và chi tiết. Chọn đáp án theo đúng lựa chọn đã nhập.</p>
                    ${contentEditor(JSON.parse(selected.content_json))}</fieldset>
                    ${editable?'<div class="admin-actions"><button class="primary-button compact" type="submit">Lưu bản nháp</button><button class="text-button" id="listening-publish" type="button">Xuất bản</button><button class="text-button" data-retire type="button">Bỏ bản nháp</button></div>':`<div class="admin-actions"><button class="primary-button compact" type="button" data-copy>Tạo bản sửa mới</button>${selected.published?'<button class="text-button" type="button" data-retire>Ngừng phát hành</button>':''}</div>`}</form>
                    <p data-status role="status" aria-live="polite"></p></section><details class="admin-panel"><summary>Nhật ký nội dung</summary>${ui.events(events)}</details>`;
                const form=root().querySelector('#listening-edit-unit'); ui.track(root()); links();
                const audio=root().querySelector('audio'); let segmentEnd=null;
                let audioAuthRetried=false;
                audio.addEventListener('loadedmetadata',()=>{audioAuthRetried=false;});
                audio.addEventListener('error',async()=>{
                    if(audioAuthRetried || current!==revision) return;
                    audioAuthRetried=true;
                    try {await bridge().api('/api/auth/me'); if(current===revision) audio.load();}
                    catch(error) {reportError(error.message);}
                });
                audio.addEventListener('timeupdate',()=>{
                    root().querySelectorAll('[data-chunk]').forEach(node=>{
                        const sentence=selected.transcript[Number(node.dataset.chunk)];
                        node.classList.toggle('is-current',audio.currentTime>=sentence.audio_start_seconds && audio.currentTime<sentence.audio_end_seconds);
                    });
                    if(segmentEnd!==null && audio.currentTime>=segmentEnd) {audio.pause();segmentEnd=null;}
                });
                root().querySelectorAll('[data-segment]').forEach(button=>button.addEventListener('click',async()=>{
                    const sentence=selected.transcript[Number(button.dataset.segment)];
                    try {
                        await bridge().api('/api/auth/me');
                        audio.currentTime=sentence.audio_start_seconds;
                        segmentEnd=sentence.audio_end_seconds;
                        await audio.play();
                    } catch(error){segmentEnd=null;reportError(error.message);}
                }));
                const save=async()=>{
                    const body={title:form.elements.title.value,content:readContent(form)};
                    const result=await bridge().api(`/api/admin/listening/units/${selected.id}`,{method:'PUT',headers:{'X-Edit-Version':selected.edit_version},body});
                    selected.edit_version=result.edit_version; ui.clean(form);
                };
                if(editable) {
                    form.addEventListener('submit',event=>{ event.preventDefault(); ui.busy(form,async()=>{try {await save(); const status=root().querySelector('[data-status]'); status.textContent='Đã lưu bản nháp.'; delete status.dataset.error;} catch(error){reportError(error.message);} }); });
                    root().querySelector('#listening-publish').addEventListener('click',()=>ui.busy(form,async()=>{
                        if(!confirm('Xuất bản bản này? Các bản đã phát hành cùng audio sẽ ngừng dùng cho phiên mới. Lịch sử học vẫn được giữ.')) return;
                        try {await save(); await bridge().api(`/api/admin/listening/units/${selected.id}/publication`,{method:'PUT',headers:{'X-Edit-Version':selected.edit_version}}); await admin(selected.id);}
                        catch(error){reportError(error.message);}
                    }));
                }
                root().querySelector('[data-copy]')?.addEventListener('click',()=>ui.busy(form,async()=>{
                    try {const result=await bridge().api(`/api/admin/listening/units/${selected.id}/copy`,{method:'POST'}); go({...filters,edit:result.id});}
                    catch(error){reportError(error.message);}
                }));
                root().querySelector('[data-retire]')?.addEventListener('click',()=>ui.busy(form,async()=>{
                    if(!confirm(editable?'Bỏ bản nháp này? Các thay đổi chưa lưu sẽ bị bỏ. Bạn có thể tạo bản nháp khác cho audio này.':'Ngừng phát hành bài cho phiên mới? Lịch sử học vẫn được giữ.')) return;
                    try {await bridge().api(`/api/admin/listening/units/${selected.id}/retire`,{method:'PUT',headers:{'X-Edit-Version':selected.edit_version}});ui.clean(form);go({...filters,edit:'',create:''});}
                    catch(error){reportError(error.message);}
                }));
                return;
            }
            const [result,sources]=await Promise.all([bridge().api(`/api/admin/listening/units/page?${new URLSearchParams({...filters,page:Number(filters.page)-1})}`),bridge().api('/api/admin/listening/sources')]);
            if(current!==revision || document.body.dataset.route!=='admin-listening') return;
            ui.url({...filters,page:result.page+1},true);
            const sourceOptions=sources.map((source,index)=>`<option value="${index}">#${index+1} · ${escape(source.level)} · ${escape(source.lesson_title)} · ${escape(source.audio_url.split('/').pop())} · ${source.sentence_count} câu</option>`).join('');
            const sourceLessons=[...new Map(sources.map(source=>[source.lesson_id,source.lesson_title])).entries()];
            const createUrl=new URL(location.href);createUrl.searchParams.set('create','1');
            root().innerHTML=`<div class="admin-actions"><a class="primary-button compact" data-listening-admin-link href="${escape(createUrl.pathname+createUrl.search)}">+ Bài nghe hiểu</a><a href="/listening" class="text-button">Mở trang học viên →</a></div>
                ${filters.create?`<section class="admin-panel"><h2>Tạo bài nghe hiểu</h2><form id="listening-create-unit" class="listening-admin-form"><label>Chọn bài và audio<select name="source" required>${sourceOptions}</select></label><button type="submit" class="primary-button compact" ${sources.length?'':'disabled'}>Tạo bản nháp bằng AI</button></form>
                ${sources.length?'':'<p>Chưa có audio phù hợp. Cần bài đã xuất bản với ít nhất 5 câu có mốc thời gian.</p>'}
                <details><summary>Tạo thủ công</summary><form id="listening-manual-unit" class="listening-admin-form" data-admin-dirty="manual-unit"><label>Chọn bài và audio<select name="source" required>${sourceOptions}</select></label><label>Tiêu đề<input name="title" maxlength="140" required></label><p>4–12 ý chính và 5–8 câu hỏi; cần câu hỏi ý chính và chi tiết.</p>${contentEditor()}<button class="primary-button compact" type="submit" ${sources.length?'':'disabled'}>Tạo bản nháp thủ công</button></form></details><p data-status role="status"></p></section>`:''}
                <form class="admin-toolbar" id="listening-admin-filters"><label>Tìm tên hoặc ID<input type="search" name="q" value="${escape(filters.q)}" maxlength="160"></label>
                ${field('level','Level',[['','Tất cả'],...['A1','A2','B1','B2'].map(value=>[value,value])],filters.level)}${field('status','Trạng thái',states,filters.status)}
                ${field('lessonId','Bài nguồn',[['','Tất cả'],...sourceLessons],filters.lessonId)}${field('sort','Sắp xếp',[['updated','Mới nhất trước'],['title','Tên A–Z']],filters.sort)}
                <div class="admin-actions"><button class="primary-button compact" type="submit">Áp dụng</button><button class="text-button" type="button" data-clear>Xóa lọc</button></div></form>
                <p class="admin-summary">${result.total} bài phù hợp</p>${result.items.length?`<div class="admin-table-wrap"><table class="admin-table"><caption class="sr-only">Bài nghe hiểu</caption><thead><tr><th scope="col">Bài nghe hiểu</th><th scope="col">Level</th><th scope="col">Trạng thái</th><th scope="col">Audio</th><th scope="col">Thao tác</th></tr></thead><tbody>${result.items.map(unit=>{
                    const url=new URL(location.href);url.searchParams.set('edit',unit.id);url.searchParams.delete('create');
                    return `<tr><td><strong>${escape(unit.title)}</strong><small>#${unit.id} · ${escape(unit.lesson_title)}</small></td><td data-label="Level">${escape(unit.level)}</td><td data-label="Trạng thái">${ui.badge(unit.published?'Đã xuất bản':unit.retired?'Đã ngừng':'Bản nháp',unit.published?'good':unit.retired?'muted':'warn')}</td><td data-label="Audio">${Math.round(unit.duration_seconds)} giây</td><td><a class="text-button" data-listening-admin-link href="${escape(url.pathname+url.search)}" aria-label="Xem bài ${escape(unit.title)}">${!unit.published&&!unit.retired?'Duyệt và sửa':'Xem nội dung'} →</a></td></tr>`;
                }).join('')}</tbody></table></div>`:'<p class="admin-empty">Không có bài phù hợp. Hãy đổi bộ lọc hoặc tạo bài mới.</p>'}${ui.pager(result)}${filters.create?'':'<p data-status role="status"></p>'}`;
            links(); ui.track(root());
            const filterForm=root().querySelector('#listening-admin-filters');
            filterForm.addEventListener('submit',event=>{event.preventDefault();go({...Object.fromEntries(new FormData(filterForm)),page:1});});
            filterForm.querySelector('[data-clear]').addEventListener('click',()=>go({page:1}));
            root().querySelectorAll('[data-page]').forEach(button=>button.addEventListener('click',()=>go({...filters,page:button.dataset.page})));
            const create=async(form,manual)=>{
                const source=sources[Number(form.elements.source.value)];
                if(!source) throw new Error('Chưa có nguồn audio phù hợp.');
                const body=manual?{lessonId:Number(source.lesson_id),audioUrl:source.audio_url,title:form.elements.title.value,content:readContent(form)}:{lessonId:Number(source.lesson_id),audioUrl:source.audio_url,durationSeconds:Number(source.duration_seconds)};
                const result=await bridge().api(manual?'/api/admin/listening/units/manual':'/api/admin/listening/units',{method:'POST',body});
                ui.clean(form);go({...filters,create:'',edit:result.id});
            };
            root().querySelectorAll('#listening-create-unit,#listening-manual-unit').forEach(form=>form.addEventListener('submit',event=>{event.preventDefault();ui.busy(form,async()=>{try{await create(form,form.id==='listening-manual-unit');}catch(error){reportError(error.message);}});}));
        } catch(error) { if(current===revision && document.body.dataset.route==='admin-listening') ui.failure(root(),error.message,()=>admin(filters.edit)); }
        finally { if(current===revision) root().setAttribute('aria-busy','false'); }
    }

    function renderStage(state) {
        if (state.stage === "PASS1_RESULT") {
            passOneResult();
            return;
        }
        const pass = state.passNumber;
        const header = `<div class="listening-stage-head"><span class="level-stamp">Lượt ${pass}/${state.mode === "TWO_PASS" ? 2 : 1}</span>
            <h2>${escape(state.title)}</h2></div>`;
        if (state.stage === "READY") {
            root().innerHTML = `${header}<p>Audio sẽ phát từ đầu đến cuối đúng một lần. Kiểm tra âm lượng trước khi bắt đầu.</p>
                <button class="primary-button" type="button" id="listening-play">Bắt đầu nghe</button>`;
            root().querySelector("#listening-play").addEventListener("click", () => play(state));
        } else if (state.stage === "PLAYING") {
            root().innerHTML = `${header}<p role="status">Lượt nghe đã bắt đầu. Nếu trang đã tải lại hoặc audio bị dừng, lượt này không thể phát lại.</p>
                <button class="text-button" type="button" id="listening-abandon">Kết thúc bài bị gián đoạn</button>`;
            root().querySelector("#listening-abandon").addEventListener("click", abandon);
        } else if (state.stage === "RECALL") {
            root().innerHTML = `${header}<form id="listening-recall-form">
                <label for="listening-recall">Bạn nhớ hoặc hiểu được những ý gì? Viết bằng tiếng Anh hoặc tiếng Việt. Phần này đánh giá ý hiểu, không chấm ngữ pháp hay chính tả.</label>
                <textarea id="listening-recall" maxlength="6000" rows="8">${escape(state.recallDraft || "")}</textarea>
                <p id="listening-save-status" role="status"></p>
                <button class="primary-button" type="submit">Nộp phần nhớ lại</button></form>`;
            const form = root().querySelector("#listening-recall-form");
            const recallId = sessionId;
            form.querySelector("textarea").addEventListener("input", () => {
                window.clearTimeout(saveTimer);
                flushDraft = () => {
                    const text = form.querySelector("textarea").value;
                    draftSave = draftSave.then(async () => {
                        try {
                            await bridge().api(`/api/v1/listening/sessions/${encodeURIComponent(recallId)}/recall`,
                                {method: "PUT", body: {text}});
                            form.querySelector("#listening-save-status").textContent = "Đã lưu bản nháp.";
                        } catch (_error) { form.querySelector("#listening-save-status").textContent = "Chưa lưu được bản nháp."; }
                    });
                };
                saveTimer = window.setTimeout(() => {
                    flushDraft?.();
                    flushDraft = null;
                }, 800);
            });
            form.addEventListener("submit", async event => {
                event.preventDefault();
                window.clearTimeout(saveTimer);
                flushDraft = null;
                form.querySelector("button").disabled = true;
                form.querySelector("textarea").readOnly = true;
                await draftSave;
                if (recallId !== sessionId || !form.isConnected) return;
                await mutate(`/api/v1/listening/sessions/${encodeURIComponent(recallId)}/recall`,
                    {text: form.querySelector("textarea").value});
                if (form.isConnected) form.querySelector("textarea").readOnly = false;
            });
        } else if (state.stage === "QUESTIONS") {
            const saved = state.answersDraft || {};
            root().innerHTML = `${header}<form id="listening-questions-form">
                <p>Trả lời theo những gì bạn đã nghe. Đáp án và điểm sẽ hiện sau khi hoàn thành.</p>
                ${state.questions.map((question, index) => `
                    <fieldset class="listening-question"><legend>${index + 1}. ${escape(question.text)}</legend>
                    ${question.options.map((option, choice) => `
                        <label><input type="radio" name="${escape(question.id)}" value="${choice}"
                            ${saved[question.id] === choice ? "checked" : ""}> ${escape(option)}</label>`).join("")}
                    </fieldset>`).join("")}
                <button class="primary-button" type="submit">Nộp câu trả lời</button></form>`;
            root().querySelector("#listening-questions-form").addEventListener("submit", async event => {
                event.preventDefault();
                window.clearTimeout(saveTimer);
                flushDraft = null;
                const answers = {};
                new FormData(event.currentTarget).forEach((value, key) => { answers[key] = Number(value); });
                await draftSave;
                await mutate(`/api/v1/listening/sessions/${encodeURIComponent(sessionId)}/answers`, {answers});
            });
            const form = root().querySelector("#listening-questions-form");
            form.insertAdjacentHTML("beforeend", '<p id="listening-save-status" role="status"></p>');
            form.addEventListener("change", () => {
                window.clearTimeout(saveTimer);
                flushDraft = () => {
                    const answers = {};
                    new FormData(form).forEach((value, key) => { answers[key] = Number(value); });
                    const id = sessionId;
                    draftSave = draftSave.then(async () => {
                        try {
                            await bridge().api(`/api/v1/listening/sessions/${encodeURIComponent(id)}/answers`,
                                {method: "PUT", body: {answers}});
                            if (form.isConnected) form.querySelector("#listening-save-status").textContent = "Đã lưu lựa chọn.";
                        } catch (_error) {
                            if (form.isConnected) form.querySelector("#listening-save-status").textContent = "Chưa lưu được lựa chọn.";
                        }
                    });
                };
                saveTimer = window.setTimeout(() => { flushDraft?.(); flushDraft = null; }, 600);
            });
        } else if (state.stage === "ABANDONED") {
            root().innerHTML = `${header}<p role="status">Bài nghe đã kết thúc do gián đoạn. Kết quả này không được chấm.</p>
                <a class="text-button" href="/listening" id="listening-new">Chọn bài khác</a>`;
            followLink(root().querySelector("#listening-new"), () => bridge().navigate("listening"));
        }
        const heading = root().querySelector("h2");
        if (heading) { heading.tabIndex = -1; heading.focus({preventScroll: true}); }
    }

    async function passOneResult() {
        window.clearTimeout(reportTimer);
        try {
            const data = await loadView(`/api/v1/listening/sessions/${encodeURIComponent(sessionId)}/pass-1-result`);
            if (!data) return;
            root().innerHTML = `<h2>${escape(data.title)} · Kết quả lượt 1</h2>
                <table class="listening-score-table"><caption class="sr-only">Điểm lượt nghe đầu tiên</caption>
                    <thead><tr><th scope="col">Chỉ số</th><th scope="col">Kết quả</th></tr></thead><tbody>
                    <tr><th scope="row">Hiểu câu hỏi</th><td>${Number(data.comprehensionScore).toFixed(1)}%</td></tr>
                    <tr><th scope="row">Nhớ ý</th><td>${data.recallScore == null ? (data.gradingStatus === "FAILED" ? (data.gradingAvailable ? "Chưa chấm được" : "AI chưa được cấu hình") : "Đang chấm") : Number(data.recallScore).toFixed(1) + "%"}</td></tr></tbody></table>
                <p>Chi tiết từng ý và đáp án sẽ mở sau khi bạn kết thúc bài.</p>
                ${data.gradingStatus === "FAILED" && data.gradingAvailable ? '<button class="text-button" id="listening-retry-grading" type="button">Thử chấm lại phần nhớ ý</button>' : ""}
                <p>Bạn có thể kết thúc để xem review, hoặc nghe toàn bộ audio thêm đúng một lần. Phần nhớ lại ở lượt 1 sẽ được giữ làm bản nháp để bạn sửa.</p>
                <button class="primary-button" id="listening-second-pass" type="button">Nghe thêm lượt 2</button>
                <button class="text-button" id="listening-finish-pass-1" type="button">Kết thúc sau lượt 1</button>`;
            root().querySelector("#listening-second-pass").addEventListener("click", async () => {
                window.clearTimeout(reportTimer);
                root().querySelectorAll("#listening-second-pass, #listening-finish-pass-1").forEach(button => { button.disabled = true; });
                await mutate(`/api/v1/listening/sessions/${encodeURIComponent(sessionId)}/second-pass`, {});
            });
            root().querySelector("#listening-finish-pass-1").addEventListener("click", async () => {
                window.clearTimeout(reportTimer);
                root().querySelectorAll("#listening-second-pass, #listening-finish-pass-1").forEach(button => { button.disabled = true; });
                await mutate(`/api/v1/listening/sessions/${encodeURIComponent(sessionId)}/finish-after-pass-1`, {});
            });
            root().querySelector("#listening-retry-grading")?.addEventListener("click", async () => {
                try {
                    await bridge().api(`/api/v1/listening/sessions/${encodeURIComponent(sessionId)}/grading/1/retry`, {method: "POST"});
                    await passOneResult();
                } catch (error) { bridge().showToast(error.message, true); }
            });
            if (data.gradingStatus === "PENDING" || data.gradingStatus === "RUNNING") {
                reportTimer = window.setTimeout(() => {
                    if (!document.hidden && document.body.dataset.route === "listening") passOneResult();
                }, 3000);
            }
        } catch (error) { bridge().showToast(error.message, true); }
    }

    async function play(state) {
        if (playing) return;
        const playId = sessionId;
        const current = revision;
        const button = root().querySelector("#listening-play");
        button.disabled = true;
        try {
            await bridge().loadAudioBuffer(state.audioUrl);
            if (current !== revision) return;
            const started = await bridge().api(`/api/v1/listening/sessions/${encodeURIComponent(playId)}/play`,
                {method: "POST"});
            if (current !== revision) return;
            playing = true;
            root().innerHTML = `<p role="status">Đang nghe lượt ${started.passNumber}. Giữ trang này mở đến khi audio kết thúc.</p>
                <progress id="listening-progress" max="100" value="0" aria-label="Tiến độ bài nghe"></progress>`;
            const startedAt = performance.now();
            const timer = window.setInterval(() => {
                const progress = root().querySelector("#listening-progress");
                if (progress) progress.value = Math.min(100, (performance.now() - startedAt) * 100 /
                    (Number(state.durationSeconds) * 1000));
            }, 250);
            try {
                await bridge().playRecordedAudio(started.audioUrl);
                const finished = await bridge().api(`/api/v1/listening/sessions/${encodeURIComponent(playId)}/play/complete`,
                    {method: "POST", body: {token: started.playbackToken}});
                if (current === revision && document.body.dataset.route === "listening") renderStage(finished);
            } finally { window.clearInterval(timer); playing = false; }
        } catch (error) {
            if (current !== revision || document.body.dataset.route !== "listening") return;
            root().innerHTML = `<p role="alert">${escape(error.message)}. Lượt nghe có thể đã bị gián đoạn.</p>
                <button class="text-button" id="listening-refresh" type="button">Tải lại trạng thái</button>
                <button class="text-button" id="listening-abandon" type="button">Kết thúc bài bị gián đoạn</button>`;
            root().querySelector("#listening-refresh").addEventListener("click", () => show(sessionId));
            root().querySelector("#listening-abandon").addEventListener("click", abandon);
        }
    }

    async function abandon() {
        bridge().stopRecordedAudio();
        await mutate(`/api/v1/listening/sessions/${encodeURIComponent(sessionId)}/abandon`, {});
    }

    async function mutate(path, body) {
        const current = ++revision;
        window.clearTimeout(reportTimer);
        const buttons = [...root().querySelectorAll("button")];
        buttons.forEach(button => { button.disabled = true; });
        try {
            const state = await bridge().api(path, {method: "POST", body});
            if (current !== revision) return;
            if (state.stage === "COMPLETE") await report();
            else renderStage(state);
        } catch (error) {
            if (current === revision) bridge().showToast(error.message, true);
        } finally {
            buttons.filter(button => button.isConnected).forEach(button => { button.disabled = false; });
        }
    }

    async function report() {
        window.clearTimeout(reportTimer);
        const data = await loadView(`/api/v1/listening/sessions/${encodeURIComponent(sessionId)}/report`);
        if (!data) return;
        const passes = data.passes;
        const first = passes[0];
        const second = passes[1];
        const metric = (row, key) => row && row[key] != null ? `${Number(row[key]).toFixed(1)}%`
            : row?.grading_status === "FAILED" ? "Chưa chấm được" : "Đang chấm";
        const delta = key => second && first[key] != null && second[key] != null
            ? `${(Number(second[key]) - Number(first[key])).toFixed(1)} điểm %` : "—";
        const weakest = Object.entries(data.questionTypes || {}).sort((a, b) => a[1][0] - b[1][0])[0];
        root().innerHTML = `<h2>${escape(data.title)} · Kết quả</h2>
            <p>${data.firstExposure ? "Chưa ghi nhận học audio này trong ứng dụng; chưa xác minh được đã nghe ở nơi khác." : "Audio đã có lịch sử học hoặc người học khai báo đã nghe; kết quả dùng để luyện tập."}</p>
            <table class="listening-score-table"><caption class="sr-only">So sánh kết quả các lượt nghe</caption>
            <thead><tr><th scope="col">Chỉ số</th><th scope="col">Lượt 1</th><th scope="col">Lượt 2</th><th scope="col">Thay đổi</th></tr></thead><tbody>
            <tr><th scope="row">Hiểu câu hỏi</th><td>${metric(first,"comprehension_score")}</td><td>${second ? metric(second,"comprehension_score") : "—"}</td><td>${delta("comprehension_score")}</td></tr>
            <tr><th scope="row">Nhớ ý</th><td>${metric(first,"recall_score")}</td><td>${second ? metric(second,"recall_score") : "—"}</td><td>${delta("recall_score")}</td></tr></tbody></table>
            <p>Lượt 1 là chỉ số chính. Lượt 2 giúp chẩn đoán sau khi đã thấy câu hỏi ở lượt 1.</p>
            <h3>Độ chính xác theo loại câu hỏi</h3>
            <ul>${Object.entries(data.questionTypes || {}).map(([type, scores]) => `<li>${escape(type)}: lượt 1 ${scores[0].toFixed(1)}%${scores.length > 1 ? `, lượt 2 ${scores[1].toFixed(1)}%` : ""}</li>`).join("")}</ul>
            <h3>Các ý nhớ lại</h3>
            ${passes.map(pass => `<section class="listening-facts"><h4>Lượt ${pass.pass_number}</h4>${pass.recallResult?.facts
                ? `<ul>${pass.recallResult.facts.map(fact => `<li><strong>${escape(fact.status)}</strong> · ${escape(data.facts.find(item => item.id === fact.factId)?.text || fact.factId)} — ${escape(fact.explanationVi)}</li>`).join("")}</ul>`
                : `<p>${pass.grading_status === "FAILED" ? "Chưa chấm được phần nhớ lại." : "Đang chấm phần nhớ lại."}</p>`}
                ${pass.grading_status === "FAILED" && data.gradingAvailable ? `<button type="button" class="text-button" data-retry-pass="${pass.pass_number}">Thử chấm lại</button>` : ""}</section>`).join("")}
            ${data.difficultSegments?.length ? `<h3>Đoạn khó đã đánh dấu</h3><ul>${data.difficultSegments.map(item => `<li>${escape(item.transcript)}</li>`).join("")}</ul>` : ""}
            ${weakest ? `<p><strong>Gợi ý:</strong> Ở lượt đầu, loại câu hỏi ${escape(weakest[0])} có độ chính xác thấp nhất (${weakest[1][0].toFixed(1)}%). Hãy đối chiếu đoạn liên quan trong phần review.</p>` : ""}
            <a class="primary-button" id="listening-open-review" href="/listening/${encodeURIComponent(sessionId)}?view=review">Xem transcript và nghe lại</a>
            <a class="text-button" href="/support?type=LISTENING&id=${encodeURIComponent(sessionId)}">Báo điểm hoặc nội dung chưa đúng</a>
            <a class="text-button" id="listening-new" href="/listening">Chọn bài khác</a>`;
        followLink(root().querySelector("#listening-open-review"), () =>
            bridge().navigate("listening", {sessionId, view: "review"}));
        followLink(root().querySelector("#listening-new"), () => bridge().navigate("listening"));
        root().querySelectorAll("[data-retry-pass]").forEach(button => button.addEventListener("click", async () => {
            button.disabled = true;
            try {
                await bridge().api(`/api/v1/listening/sessions/${encodeURIComponent(sessionId)}/grading/${button.dataset.retryPass}/retry`, {method: "POST"});
                await report();
            } catch (error) { bridge().showToast(error.message, true); button.disabled = false; }
        }));
        if (passes.some(pass => pass.grading_status === "PENDING" || pass.grading_status === "RUNNING")) {
            reportTimer = window.setTimeout(() => {
                if (!document.hidden && document.body.dataset.route === "listening") report();
            }, 5000);
        }
    }

    async function review() {
        window.clearTimeout(reportTimer);
        const data = await loadView(`/api/v1/listening/sessions/${encodeURIComponent(sessionId)}/review`);
        if (!data) return;
        root().innerHTML = `<h2>Review · ${escape(data.title)}</h2>
            <a class="text-button" id="listening-back-report" href="/listening/${encodeURIComponent(sessionId)}">← Về kết quả</a>
            <a class="text-button" href="/support?type=LISTENING&id=${encodeURIComponent(sessionId)}">Báo transcript hoặc audio sai</a>
            <button class="text-button" id="listening-full-audio" type="button">Nghe toàn bài</button>
            <label>Tốc độ <select id="listening-speed"><option value="1">1×</option><option value="0.75">0,75×</option><option value="1.25">1,25×</option></select></label>
            <ol class="listening-transcript">${data.sentences.map(sentence => `<li data-start="${Number(sentence.audio_start_seconds)}" data-end="${Number(sentence.audio_end_seconds)}">
                <p>${escape(sentence.transcript)}</p>
                <button type="button" class="text-button" data-segment="${sentence.id}">Nghe câu</button>
                <button type="button" class="text-button" data-mark="${sentence.id}" data-kind="NOT_HEARD">Không nghe ra</button>
                <button type="button" class="text-button" data-mark="${sentence.id}" data-kind="HEARD_NOT_UNDERSTOOD">Nghe được, chưa hiểu</button>
                </li>`).join("")}</ol>
            <h3>Đáp án và giải thích</h3>${data.content.questions.map(q => `<p><strong>${escape(q.text)}</strong><br>
                ${escape(q.options[q.correct])} · ${escape(q.explanation)}</p>`).join("")}
            <h3>Đoạn đã đánh dấu</h3><ul>${data.marks.map(mark => `<li>Câu ${mark.sentence_id}: ${mark.difficulty_type === "NOT_HEARD" ? "Không nghe ra" : "Chưa hiểu nghĩa"}</li>`).join("")}</ul>`;
        followLink(root().querySelector("#listening-back-report"), () => bridge().navigate("listening", {sessionId}));
        root().querySelector("#listening-full-audio").addEventListener("click", () => playReview(data.audioUrl));
        root().querySelectorAll("[data-segment]").forEach(button => button.addEventListener("click", () => {
            const sentence = data.sentences.find(item => String(item.id) === button.dataset.segment);
            playReview(data.audioUrl, sentence.audio_start_seconds, sentence.audio_end_seconds);
        }));
        root().querySelectorAll("[data-mark]").forEach(button => button.addEventListener("click", async () => {
            try {
                await bridge().api(`/api/v1/listening/sessions/${encodeURIComponent(sessionId)}/review-marks`, {
                    method: "POST", body: {sentenceId: Number(button.dataset.mark), type: button.dataset.kind, note: ""}
                });
                await review();
            } catch (error) { bridge().showToast(error.message, true); }
        }));
    }

    async function playReview(url, start, end) {
        const current = revision;
        const playback = ++reviewPlayback;
        const speed = Number(root().querySelector("#listening-speed").value);
        try { await bridge().loadAudioBuffer(url); }
        catch (error) { bridge().showToast(error.message, true); return; }
        if (current !== revision || playback !== reviewPlayback) return;
        const started = performance.now();
        const offset = Number(start) || 0;
        const highlight = () => {
            if (playback !== reviewPlayback || current !== revision) return;
            const position = offset + (performance.now() - started) * speed / 1000;
            root().querySelectorAll(".listening-transcript li").forEach(row => {
                row.classList.toggle("is-current", Number(row.dataset.start) <= position && position < Number(row.dataset.end));
            });
        };
        highlight();
        const timer = window.setInterval(highlight, 100);
        try { await bridge().playRecordedAudio(url, start, end, speed); }
        catch (error) { if (error.name !== "AbortError") bridge().showToast(error.message, true); }
        finally {
            window.clearInterval(timer);
            if (playback === reviewPlayback) root().querySelectorAll(".listening-transcript li").forEach(row => row.classList.remove("is-current"));
        }
    }

    async function history(page = 1) {
        page = Number.isSafeInteger(page) && page >= 1 ? page : 1;
        const result = await loadView(`/api/v1/listening/history/page?page=${page - 1}&size=10`);
        if (!result) return;
        const items = result.items;
        root().innerHTML = `<h2>Lịch sử nghe hiểu</h2>
            <a class="text-button" id="listening-back-catalog" href="/listening">← Chọn bài</a>
            <div id="listening-history-list">${items.length ? items.map(item => `<article class="listening-card"><h3>${escape(item.title)}</h3>
                <p>${item.mode === "TWO_PASS" ? "Hai lượt nghe" : "Một lượt nghe"} · ${escape(({READY:"Sẵn sàng nghe",PLAYING:"Đang nghe",RECALL:"Ghi lại ý hiểu",QUESTIONS:"Trả lời câu hỏi",GRADING:"Đang chấm",PASS1_RESULT:"Kết quả lượt đầu",COMPLETE:"Đã hoàn thành"})[item.stage] || "Đang học")} · Hiểu lần đầu: ${item.comprehension_score == null ? "—" : Number(item.comprehension_score).toFixed(1) + "%"}</p>
                <a class="text-button" href="/listening/${encodeURIComponent(item.id)}" data-session="${escape(item.id)}">Mở bài</a></article>`).join("")
            : "<p>Trang này chưa có bài nghe hiểu.</p>"}</div>
            <nav aria-label="Phân trang lịch sử nghe hiểu"><button type="button" id="listening-history-prev" ${page === 1 ? "disabled" : ""}>Trang trước</button>
            <span role="status">Trang ${page} · ${result.total} bài</span>
            <button type="button" id="listening-history-next" ${page * result.size >= result.total ? "disabled" : ""}>Trang sau</button></nav>`;
        for (const [id, target] of [["listening-history-prev", page - 1], ["listening-history-next", page + 1]]) {
            root().querySelector(`#${id}`).addEventListener("click", () => bridge().navigate("listening", {view: "history", page: target}));
        }
        followLink(root().querySelector("#listening-back-catalog"), () => bridge().navigate("listening"));
        root().querySelectorAll("[data-session]").forEach(link => followLink(link, () =>
            bridge().navigate("listening", {sessionId: link.dataset.session})));
    }

    window.DictaLabListening = {show, leave};
})();
