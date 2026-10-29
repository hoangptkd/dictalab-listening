(() => {
    "use strict";
    const root = document.querySelector("#vocabulary-content");
    const status = document.querySelector("#vocabulary-status");
    if (!root || !status) return;
    const bridge = window.DictaLabListeningBridge;
    const base = "/api/v1/vocabulary", adminBase = "/api/admin/vocabulary";
    const escape = value => String(value ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
    const button = (action,label,extra="") => `<button type="button" class="text-button" data-vocab-action="${action}" ${extra}>${label}</button>`;
    let generation = 0, importGeneration = 0, controller = null, busy = false, owner = null;
    let personal = [], visibleNotes = [], selectedDeck = null, currentNote = null, sourceWord = null, session = null, admin = false, catalog = false;
    const pending = new Map();
    function leave() {
        generation++; controller?.abort(); controller = null; busy = false;
        personal = []; visibleNotes = []; selectedDeck = null; currentNote = null; sourceWord = null; session = null; owner = null;
        root.replaceChildren(); status.textContent = "";
    }
    async function request(path,options={}) {
        const stamp = generation, user = owner;
        const result = await bridge.api(path,{...options,signal:controller?.signal,timeoutMs:20000});
        if (stamp !== generation || user !== bridge.currentUser()?.id) throw new DOMException("Changed user or page","AbortError");
        return result;
    }
    async function mutate(path,body={},method="POST") {
        const identity = `${owner}:${method}:${path}:${JSON.stringify(body)}`;
        if (!pending.has(identity)) pending.set(identity,crypto.randomUUID());
        try {
            const result = await request(path,{method,body,headers:{"Idempotency-Key":pending.get(identity)}});
            pending.delete(identity); return result;
        } catch (error) {
            if (error.status && error.status < 500 && error.status !== 429) pending.delete(identity);
            throw error;
        }
    }
    async function run(work) {
        if (busy) return;
        const stamp = generation; busy = true; status.textContent = "Đang xử lý…";
        root.setAttribute("aria-busy","true");
        try { await work(); if(stamp===generation) status.textContent = ""; }
        catch(error) { if(stamp===generation && error.name!=="AbortError") status.textContent = error.message; }
        finally { if(stamp===generation) {busy=false;root.removeAttribute("aria-busy");} }
    }
    const toolbar = () => `<nav class="vocab-toolbar" aria-label="Mục từ vựng">${button("home","Hôm nay")}${button("mine","Bộ của tôi")}${button("catalog","Bộ có sẵn")}${button("sources","Từ bài đã học")}${bridge.isAdmin()?button("admin","Quản trị bộ từ"):""}</nav>`;
    const deckOptions = () => personal.map(d=>`<option value="${escape(d.id)}">${escape(d.title)}</option>`).join("");
    async function refreshPersonal() {
        const decks=[];
        for(let page=1;page<=10;page++) {
            const result=await request(`${base}/decks?page=${page}`); decks.push(...result.items);
            if(!result.hasMore) break;
        }
        personal=decks;
    }
    const pagination = (page,action) => `<div class="vocab-actions">${page.page>1?button(action,"Trang trước",`data-page="${page.page-1}"`):""}<span>Trang ${page.page}</span>${page.hasMore?button(action,"Trang sau",`data-page="${page.page+1}"`):""}</div>`;
    async function show(sessionId) {
        leave(); controller = new AbortController(); owner = bridge.currentUser()?.id;
        if(!owner) return;
        await run(async()=> {
            await refreshPersonal();
            if(sessionId) await loadSession(sessionId); else await home();
        });
    }
    async function home() {
        admin=false; catalog=false;
        const summary = await request(`${base}/summary`);
        root.innerHTML = toolbar()+`<div class="vocab-stats"><article><strong>${summary.due}</strong><span>Thẻ ôn đến hạn</span></article><article><strong>${summary.new_cards}</strong><span>Thẻ mới trong thư viện</span></article><article><strong>${summary.reviewedToday}</strong><span>Lượt đã học hôm nay</span></article></div>
            <section class="vocab-panel"><h2>Nhớ trước, xem đáp án sau.</h2><p>Ôn từ đến hạn trước, sau đó học tối đa ${summary.newLimit} thẻ mới mỗi ngày. Lịch ngày tính theo giờ Việt Nam/Thái Lan.</p>
            <div class="vocab-actions"><button class="primary-button" data-vocab-action="start" data-mode="SRS">Bắt đầu ôn</button>${button("start","Luyện thêm không đổi lịch",'data-mode="PRACTICE"')}${button("start","Dịch câu ngắn",'data-mode="CONTEXT_TRANSLATION"')}</div>
            ${summary.activeSessions.map(s=>`<p>${button("resume",`Tiếp tục ${s.mode==="CONTEXT_TRANSLATION"?"dịch câu":"phiên học"}`,`data-id="${escape(s.id)}"`)}</p>`).join("")}</section>
            <section class="vocab-panel"><h2>Lượt học gần đây</h2>${summary.recent.length?`<ul>${summary.recent.slice(0,8).map(r=>`<li>${({AGAIN:"Quên",HARD:"Khó",GOOD:"Nhớ",EASY:"Dễ"})[r.grade]} · ${r.schedule_changed?"Ôn theo lịch":"Luyện thêm"} · ${new Date(r.reviewed_at).toLocaleString("vi-VN")}${r.grading_source==="SELF"?" · tự đánh giá":""}</li>`).join("")}</ul>`:"<p>Chọn một bộ có sẵn hoặc thêm những từ bạn muốn nhớ.</p>"}</section>`;
    }
    async function listDecks(page=1) {
        const endpoint = admin?`${adminBase}/decks`:catalog?`${base}/catalog/decks`:`${base}/decks`;
        const result = await request(`${endpoint}?page=${page}`);
        root.innerHTML = toolbar()+`<h2>${admin?"Quản trị bộ từ":catalog?"Bộ từ có sẵn":"Bộ của tôi"}</h2>
            ${!catalog?`<form data-vocab-form="deck" class="vocab-panel vocab-form"><h3>Tạo bộ từ</h3><label>Tên bộ<input name="title" required maxlength="120"></label><label>Mô tả<input name="description" maxlength="1000"></label><label>Mức<select name="level"><option>A1</option><option>A2</option><option>B1</option><option>B2</option></select></label><button class="primary-button">Tạo bộ từ</button></form>`:""}
            <div class="vocab-grid">${result.items.map(d=>`<article class="vocab-panel"><span class="eyebrow">${escape(d.level)}${admin?" · "+escape(d.status):""}</span><h3>${escape(d.title)}</h3><p>${escape(d.description)}</p><p>${d.note_count} mục từ</p>${button("deck","Mở bộ từ",`data-id="${escape(d.id)}"`)}</article>`).join("") || '<p class="vocab-panel">Chưa có bộ từ. Tạo một bộ để bắt đầu.</p>'}</div>${pagination(result,"decks-page")}`;
    }
    async function openDeck(id,page=1) {
        const result=await request(`${admin?adminBase:base}${catalog?"/catalog":""}/decks/${encodeURIComponent(id)}?page=${page}`);
        selectedDeck=result.deck;
        visibleNotes=result.notes.items;
        root.innerHTML = toolbar()+`<section class="vocab-panel"><span class="eyebrow">${escape(selectedDeck.level)}</span><h2>${escape(selectedDeck.title)}</h2><p>${escape(selectedDeck.description)}</p>
            ${catalog?`<form data-vocab-form="copy" class="vocab-form"><label>Thêm vào bộ của tôi<select name="deckId" required><option value="">Chọn bộ từ</option>${deckOptions()}</select></label><button class="primary-button" ${personal.length?"":"disabled"}>Thêm vào thư viện</button>${personal.length?"":"<p>Hãy tạo bộ cá nhân trong Bộ của tôi trước.</p>"}</form>`:`<div class="vocab-actions">${button("new-note","Thêm từ")}${admin?button("publish",selectedDeck.status==="PUBLISHED"?"Ẩn khỏi thư viện công khai":"Xuất bản"):button("start","Ôn bộ này",'data-mode="SRS" data-deck="true"')}${!admin?button("start","Dịch câu trong bộ",'data-mode="CONTEXT_TRANSLATION" data-deck="true"'):button("new-context","Thêm câu kết hợp từ")}</div>
            <details><summary>Sửa tên và mô tả</summary><form data-vocab-form="edit-deck" class="vocab-form"><label>Tên bộ<input name="title" required maxlength="120" value="${escape(selectedDeck.title)}"></label><label>Mô tả<textarea name="description" maxlength="1000">${escape(selectedDeck.description)}</textarea></label><label>Mức<select name="level">${["A1","A2","B1","B2"].map(l=>`<option ${l===selectedDeck.level?"selected":""}>${l}</option>`).join("")}</select></label><button class="primary-button">Lưu thay đổi</button></form></details>`}</section>
            ${!admin&&!catalog?`<details class="vocab-panel"><summary>Nhập và xuất CSV</summary><p>UTF-8, tối đa 1.000 dòng và 2 MB. Các cột: term,meaning_vi,part_of_speech,example_en,example_vi,tags.</p><form data-vocab-form="import" class="vocab-form"><label>File CSV<input name="file" type="file" accept=".csv,text/csv" required></label><button class="primary-button">Xem trước</button></form><a class="text-button" href="${base}/decks/${encodeURIComponent(id)}/export">Xuất CSV</a><div id="vocab-import-preview"></div></details>`:""}
            <div class="vocab-grid">${result.notes.items.map(n=>`<article class="vocab-panel"><h3 lang="en">${escape(n.term)}</h3><p>${escape(n.meaning_vi)}</p><small>${escape(n.part_of_speech)}</small>${n.example_en?`<p lang="en">${escape(n.example_en)}</p><p>${escape(n.example_vi)}</p>`:""}${!catalog?button("edit-note","Chi tiết / sửa",`data-id="${escape(n.id)}"`):""}${!admin&&!catalog?button("remove-note","Bỏ khỏi bộ",`data-id="${escape(n.id)}"`):""}</article>`).join("")||"<p>Chưa có từ trong bộ.</p>"}</div>${pagination(result.notes,"notes-page")}`;
    }
    function noteForm(note=null) {
        currentNote=note;
        const v=key=>escape(note?.[key]??"");
        root.innerHTML=toolbar()+`<section class="vocab-panel"><h2>${note?"Sửa mục từ":"Thêm mục từ"}</h2>${sourceWord?`<p>Bài: ${escape(sourceWord.lesson_title)}</p><blockquote lang="en">${escape(sourceWord.transcript)}</blockquote><p>${sourceWord.evidence==="UNKNOWN_WORD"?"Bạn đã đánh dấu chưa biết từ.":"Lỗi nghe chưa chứng minh bạn quên nghĩa. Hãy xác nhận nghĩa muốn học."}</p>`:""}
            <form data-vocab-form="note" class="vocab-form">
            ${!selectedDeck?`<label>Bộ lưu<select name="deckId" required><option value="">Chọn bộ</option>${deckOptions()}</select></label>`:""}
            <label>Từ / cụm từ<input name="term" lang="en" maxlength="200" required value="${v("term")||escape(sourceWord?.term||"")}"></label>
            <label>Nghĩa tiếng Việt cụ thể<textarea name="meaningVi" maxlength="1000" required>${v("meaning_vi")}</textarea></label>
            <label>Từ loại<input name="partOfSpeech" maxlength="40" value="${v("part_of_speech")}"></label>
            <label>Ví dụ tiếng Anh<textarea name="exampleEn" lang="en" maxlength="1000">${v("example_en")||escape(sourceWord?.transcript||"")}</textarea></label>
            <label>Bản dịch ví dụ<textarea name="exampleVi" maxlength="1000">${v("example_vi")}</textarea></label>
            <label>Đáp án tiếng Anh thay thế (mỗi dòng một đáp án)<textarea name="alternatives" maxlength="4000">${escape((note?.alternatives||[]).join("\n"))}</textarea></label>
            <label>Nhãn<input name="tags" maxlength="500" value="${v("tags")}"></label>
            ${!admin?'<label class="vocab-check"><input name="meaningRecall" type="checkbox"> Thêm chiều nhớ nghĩa (có lịch riêng)</label>':""}
            <p>Để học một nghĩa khác, hãy tạo mục mới. Sửa nội dung giữ lịch học hiện tại.</p><button class="primary-button">Lưu mục từ</button></form>
            ${note?.cards?`<h3>Thẻ học</h3>${note.cards.map(c=>`<p>${c.card_type==="WORD_RECALL"?"Nhớ từ":"Nhớ nghĩa"} · ${new Date(c.due_at).toLocaleString("vi-VN")} ${button("suspend",c.suspended?"Tiếp tục học":"Tạm ngưng",`data-id="${escape(c.id)}" data-revision="${c.revision}" data-suspended="${c.suspended}"`)}</p>`).join("")}`:""}
            ${note?.sources?.length?`<h3>Ngữ cảnh đã gặp</h3>${note.sources.map(s=>`<blockquote><p lang="en">${escape(s.transcript)}</p><small>${escape(s.lesson_title)}</small></blockquote>`).join("")}`:""}</section>`;
    }
    async function sources(page=1) {
        admin=false; catalog=false; selectedDeck=null;
        const result=await request(`${base}/suggestions?page=${page}`);
        root.innerHTML=toolbar()+`<h2>Từ các bài đã học</h2><p>Lưu từ bạn muốn học sau khi xác nhận nghĩa. Sai khi nghe chưa đồng nghĩa với quên từ.</p><div class="vocab-grid">${result.items.map(s=>`<article class="vocab-panel"><h3 lang="en">${escape(s.term)}</h3><p>${escape(s.lesson_title)}</p><blockquote lang="en">${escape(s.transcript)}</blockquote><p>${s.evidence==="UNKNOWN_WORD"?"Đã đánh dấu chưa biết từ":"Cần xác nhận nguyên nhân"}</p>${button("save-source","Lưu vào từ vựng",`data-source="${escape(JSON.stringify(s))}"`)}</article>`).join("")||"<p>Chưa có lỗi từ các bài đã làm.</p>"}</div>${pagination(result,"sources-page")}`;
    }
    async function loadSession(id) { session=await request(`${base}/sessions/${encodeURIComponent(id)}`); renderSession(); }
    function renderSession() {
        const item=session.items.find(i=>!i.gradedAt);
        if(!item||session.status!=="ACTIVE") {
            root.innerHTML=toolbar()+`<section class="vocab-panel"><h2>${session.status==="ABANDONED"?"Phiên đã hết hạn hoặc được kết thúc.":"Đã hoàn thành phiên."}</h2><p>${escape(session.message||"Kết quả đã được lưu. Bạn có thể quay lại ôn khi đến hạn.")}</p>${button("home","Về tổng quan")}</section>`;return;
        }
        const answered=item.answer!==null;
        const feedback=item.feedback||{},failed=item.assisted||item.evaluation==="INCORRECT";
        root.innerHTML=`<section class="vocab-study vocab-panel"><div class="vocab-actions"><span>Mục ${item.position+1} / ${session.items.length}</span>${button("abandon","Kết thúc phiên")}</div><progress value="${item.position}" max="${session.items.length}" aria-label="Tiến độ phiên"></progress>
            <p class="eyebrow">${item.kind==="CONTEXT_TRANSLATION"?"Dịch sang tiếng Việt":item.kind==="WORD_RECALL"?"Nhớ và nhập từ tiếng Anh":"Nhớ và nhập nghĩa tiếng Việt"}</p><h2 ${item.kind!=="WORD_RECALL"?'lang="en"':""}>${escape(item.prompt)}</h2>
            ${!answered?`<form data-vocab-form="answer" class="vocab-form"><label>Đáp án của bạn<textarea name="answer" maxlength="4000" required ${item.kind==="WORD_RECALL"?'lang="en"':""}></textarea></label><div class="vocab-actions"><button class="primary-button">Kiểm tra</button>${button("reveal","Không nhớ / xem đáp án")}</div></form>`:
            `<div class="vocab-feedback"><p>Đáp án bạn nhập: ${escape(item.answer||"Không nhớ")}</p><strong>${item.evaluation==="CORRECT"?"Khớp đáp án đã có.":item.evaluation==="INCORRECT"?"Chưa nhớ độc lập.":"Có nhiều cách diễn đạt. Hãy đối chiếu và tự đánh giá."}</strong>
            ${item.kind==="CONTEXT_TRANSLATION"?`<p>${escape(feedback.translationVi)}</p><p>${feedback.source==="PERSONAL_EXAMPLE"?"Ví dụ một từ: chưa có tổ hợp nhiều từ phù hợp.":"Câu kết hợp nhiều từ đã học."}</p>`:`<h3 lang="en">${escape(feedback.term)}</h3><p>${escape(feedback.meaning_vi)}</p><p lang="en">${escape(feedback.example_en)}</p><p>${escape(feedback.example_vi)}</p>`}
            <form data-vocab-form="grade" class="vocab-form">${(feedback.targets||[]).map(t=>`<label>${escape(t.term)} — ${escape(t.meaningVi)}<select name="target:${escape(t.noteId)}"><option value="REMEMBERED">Nhớ</option><option value="UNSURE">Chưa chắc</option><option value="FORGOTTEN">Quên</option></select></label>`).join("")}
            <p>${item.kind==="CONTEXT_TRANSLATION"?"Đánh giá câu và từng từ được lưu riêng, không tăng lịch ôn các từ.":"Khó nghĩa là vẫn nhớ được. Nếu không nhớ, hãy chọn Quên."}</p><div class="vocab-actions">${["AGAIN","HARD","GOOD","EASY"].map(g=>`<button type="submit" class="${g==="GOOD"?"primary-button":"text-button"}" name="grade" value="${g}" ${failed&&g!=="AGAIN"?"disabled":""}>${({AGAIN:"Quên",HARD:"Khó",GOOD:"Nhớ",EASY:"Dễ"})[g]}</button>`).join("")}</div></form></div>`}</section>`;
        if(!answered) {
            const input=root.querySelector('[name="answer"]');
            try { const draft=JSON.parse(sessionStorage.getItem(`dictalab.vocabulary.${owner}.${item.id}`)||"null"); if(draft&&Date.now()-draft.at<86400000) input.value=draft.text; } catch {}
            input.focus();
        }
    }
    async function contextForm() {
        const options=visibleNotes.map(n=>`<option value="${escape(n.id)}" data-term="${escape(n.term)}">${escape(n.term)} — ${escape(n.meaning_vi)}</option>`).join("");
        root.innerHTML=toolbar()+`<section class="vocab-panel"><h2>Thêm câu kết hợp từ</h2><form data-vocab-form="context" class="vocab-form"><label>Câu tiếng Anh<input name="prompt" required maxlength="1000" lang="en"></label><label>Bản dịch tiếng Việt<textarea name="translationVi" required maxlength="1000"></textarea></label>${[1,2].map(i=>`<label>Từ mục tiêu ${i}<select name="note${i}" required>${options}</select></label><label>Dạng xuất hiện trong câu ${i}<input name="surface${i}" required maxlength="200"></label>`).join("")}<label class="vocab-check"><input name="published" type="checkbox"> Xuất bản câu sau khi đã kiểm tra nghĩa và ngữ pháp</label><button class="primary-button">Lưu câu</button></form></section>`;
    }
    root.addEventListener("input",event=> {
        if(event.target.name!=="answer"||!session) return;
        const item=session.items.find(i=>!i.gradedAt); if(!item) return;
        try { sessionStorage.setItem(`dictalab.vocabulary.${owner}.${item.id}`,JSON.stringify({at:Date.now(),text:event.target.value})); } catch {}
    });
    root.addEventListener("change",event=> {
        if(event.target.name==="file") { importGeneration++;root.querySelector("#vocab-import-preview")?.replaceChildren(); }
    });
    root.addEventListener("click",event=> {
        const target=event.target.closest("[data-vocab-action]"); if(!target) return;
        run(async()=> {
            const action=target.dataset.vocabAction;
            if(action==="home") { await bridge.navigate("vocabulary"); return; }
            if(["mine","catalog","admin"].includes(action)) {admin=action==="admin";catalog=action==="catalog";selectedDeck=null;await listDecks();}
            else if(action==="decks-page") await listDecks(Number(target.dataset.page));
            else if(action==="deck") await openDeck(target.dataset.id);
            else if(action==="notes-page") await openDeck(selectedDeck.id,Number(target.dataset.page));
            else if(action==="sources"||action==="sources-page") await sources(Number(target.dataset.page||1));
            else if(action==="save-source") {sourceWord=JSON.parse(target.dataset.source);selectedDeck=null;noteForm();}
            else if(action==="new-note") {sourceWord=null;noteForm();}
            else if(action==="edit-note") { sourceWord=null; const note=admin?visibleNotes.find(n=>n.id===target.dataset.id):await request(`${base}/notes/${target.dataset.id}`);noteForm(note); }
            else if(action==="remove-note") { await mutate(`${base}/decks/${selectedDeck.id}/notes/${target.dataset.id}`,{},"DELETE");await openDeck(selectedDeck.id); }
            else if(action==="suspend") {await mutate(`${base}/cards/${target.dataset.id}`,{suspended:target.dataset.suspended!=="true",revision:Number(target.dataset.revision)},"PATCH");noteForm(await request(`${base}/notes/${currentNote.id}`));}
            else if(action==="publish") {await mutate(`${adminBase}/decks/${selectedDeck.id}/publication`,{published:selectedDeck.status!=="PUBLISHED",revision:selectedDeck.revision});await openDeck(selectedDeck.id);}
            else if(action==="start") {session=await mutate(`${base}/sessions`,{mode:target.dataset.mode,deckId:target.dataset.deck?selectedDeck.id:null});if(session.id) await bridge.navigate("vocabulary",{sessionId:session.id});else renderSession();}
            else if(action==="resume") await bridge.navigate("vocabulary",{sessionId:target.dataset.id});
            else if(action==="abandon") {await mutate(`${base}/sessions/${session.id}/abandon`);await bridge.navigate("vocabulary");}
            else if(action==="reveal") {const item=session.items.find(i=>!i.gradedAt);await mutate(`${base}/session-items/${item.id}/reveal`);await loadSession(session.id);}
            else if(action==="confirm-import") {await mutate(`${base}/imports/${target.dataset.id}/confirm`);await openDeck(selectedDeck.id);}
            else if(action==="new-context") await contextForm();
        });
    });
    root.addEventListener("submit",event=> {
        const form=event.target.closest("[data-vocab-form]");if(!form) return;event.preventDefault();
        const data=new FormData(form),kind=form.dataset.vocabForm,grade=event.submitter?.value;
        run(async()=> {
            if(kind==="deck"||kind==="edit-deck") {
                const payload={title:data.get("title"),description:data.get("description"),level:data.get("level"),revision:kind==="edit-deck"?selectedDeck.revision:0};
                const result=await mutate(`${admin?adminBase:base}/decks${kind==="edit-deck"?"/"+selectedDeck.id:""}`,payload,kind==="edit-deck"?"PATCH":"POST");
                if(!admin) await refreshPersonal();await openDeck(result.id);
            } else if(kind==="copy") {await mutate(`${base}/catalog/decks/${selectedDeck.id}/add`,{deckId:data.get("deckId")});admin=false;catalog=false;await openDeck(data.get("deckId"));}
            else if(kind==="note") {
                const payload={term:data.get("term"),meaningVi:data.get("meaningVi"),partOfSpeech:data.get("partOfSpeech"),exampleEn:data.get("exampleEn"),exampleVi:data.get("exampleVi"),tags:data.get("tags"),alternatives:String(data.get("alternatives")).split("\n").map(x=>x.trim()).filter(Boolean),revision:currentNote?.revision||0,meaningRecall:data.has("meaningRecall")};
                const deck=selectedDeck?.id||data.get("deckId");
                if(sourceWord) await mutate(`${base}/decks/${deck}/from-practice`,{wordAttemptId:sourceWord.word_attempt_id,existingNoteId:null,note:payload});
                else await mutate(currentNote?`${admin?adminBase:base}/notes/${currentNote.id}`:`${admin?adminBase:base}/decks/${deck}/notes`,payload,currentNote?"PATCH":"POST");
                sourceWord=null;await openDeck(deck);
            } else if(kind==="answer") {const item=session.items.find(i=>!i.gradedAt);await mutate(`${base}/session-items/${item.id}/answer`,{answer:data.get("answer")});await loadSession(session.id);}
            else if(kind==="grade") {
                const item=session.items.find(i=>!i.gradedAt),targets=[];
                for(const [name,result] of data) if(name.startsWith("target:")) targets.push({noteId:name.slice(7),result});
                await mutate(`${base}/session-items/${item.id}/grade`,{grade,targets});
                try{sessionStorage.removeItem(`dictalab.vocabulary.${owner}.${item.id}`);}catch{} await loadSession(session.id);
            } else if(kind==="import") {
                const previewGeneration=++importGeneration;
                root.querySelector("#vocab-import-preview").replaceChildren();
                const file=data.get("file");if(file.size>2097152) throw new Error("File tối đa 2 MB.");
                let csv;
                try { csv=new TextDecoder("utf-8",{fatal:true}).decode(await file.arrayBuffer()); }
                catch { throw new Error("File phải dùng mã hóa UTF-8. Hãy lưu lại dưới dạng CSV UTF-8."); }
                const result=await mutate(`${base}/imports/preview`,{deckId:selectedDeck.id,csv});
                if(previewGeneration!==importGeneration) return;
                root.querySelector("#vocab-import-preview").innerHTML=result.errors.length?`<ul>${result.errors.map(e=>`<li>${escape(e)}</li>`).join("")}</ul>`:`<p>${result.count} mục từ hợp lệ. Các từ trùng chính xác sẽ được dùng lại.</p><ul>${result.preview.map(n=>`<li>${escape(n.term)} — ${escape(n.meaningVi)}</li>`).join("")}</ul>${button("confirm-import","Xác nhận nhập",`data-id="${escape(result.id)}"`)}`;
            } else if(kind==="context") {
                await mutate(`${adminBase}/contexts`,{prompt:data.get("prompt"),translationVi:data.get("translationVi"),level:selectedDeck.level,alternatives:[],targets:[1,2].map(i=>({noteId:data.get(`note${i}`),surfaceForm:data.get(`surface${i}`)})),published:data.has("published"),revision:0});await openDeck(selectedDeck.id);
            }
        });
    });
    window.DictaLabVocabulary={show,leave};
})();
