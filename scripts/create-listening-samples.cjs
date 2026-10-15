#!/usr/bin/env node
"use strict";

// Reviewed original scripts in brief.json; cached TTS only, no database writes or publication.
const fs = require("node:fs/promises");
const path = require("node:path");
const assert = require("node:assert/strict");
const {spawnSync} = require("node:child_process");
const {createHash} = require("node:crypto");
const {words, request, readWave, combine} = require("./create-b1-dictation.cjs");
const root = path.resolve(process.argv[3] || "data/generated-listening-samples-20261015");
const hash = value => createHash("sha256").update(value).digest("hex");
const escape = value => String(value ?? "").replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;").replaceAll('"',"&quot;");
const quote = value => "'"+String(value).replaceAll("'","''")+"'";

function buildSql(manifest) {
    validate(manifest);
    let sql="\\set ON_ERROR_STOP on\nBEGIN;\nSET LOCAL lock_timeout='3s';\nSET LOCAL statement_timeout='15s';\nLOCK TABLE lessons,lesson_sentences,library_courses,library_groups,library_lessons,library_lesson_metadata IN SHARE ROW EXCLUSIVE MODE;\n";
    for(const lesson of manifest.lessons){
        const expectedCourse=lesson.goal==="IELTS" ? "ielts-band-"+lesson.target.replaceAll(".","-") : lesson.goal==="TOEIC" ? "toeic-score-"+lesson.target : "communication-cefr-"+lesson.target.toLowerCase();
        assert.equal(lesson.courseId,expectedCourse);
        assert(lesson.slug.length<=80 && lesson.title.length<=140 && lesson.focus.length<=180);
        assert.equal(lesson.audioFile,lesson.slug+".wav");assert.match(lesson.sha256,/^[a-f0-9]{64}$/);
        sql+=`DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM audio_s3_aliases WHERE audio_url=${quote('/media/'+lesson.audioFile)} AND sha256=${quote(lesson.sha256)}) THEN RAISE EXCEPTION 'Upload verified audio to S3 before import'; END IF; END $$;\n`;
        const speakers=new Set(lesson.sentences.map(s=>s.speaker)).size;
        const difficulty={A1:1,A2:2,B1:3,B2:4}[lesson.level];assert(difficulty);
        const description=`${lesson.format}. ${lesson.focus}. Nội dung gốc, giọng AI Mỹ Cartesia; mức luyện tham khảo, chưa hiệu chuẩn điểm thi.`;
        assert(description.length<=500);
        sql+=`DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM library_courses WHERE id=${quote(lesson.courseId)} AND goal=${quote(lesson.goal)} AND published=TRUE) THEN RAISE EXCEPTION 'Course unavailable'; END IF; END $$;\n`;
        sql+=`INSERT INTO lessons(slug,title,description,level,topic,estimated_minutes,display_order,active,content_origin,speaker_count,accent_profile,vocabulary_difficulty,grammar_difficulty,sentence_difficulty,assessment_calibrated) VALUES (${quote(lesson.slug)},${quote(lesson.title)},${quote(description)},${quote(lesson.level)},${quote(lesson.goal==="COMMUNICATION" ? "Giao tiếp đời sống" : lesson.goal+" · "+lesson.target)},${Math.max(5,Math.ceil(lesson.sentences.length/2))},(SELECT COALESCE(MAX(display_order),0)+1 FROM lessons WHERE level=${quote(lesson.level)}),TRUE,'ADMIN_UPLOAD',${speakers},'General American / Cartesia synthetic voices',${difficulty},${difficulty},${difficulty},FALSE);\n`;
        let previous=0;
        for(const [i,s] of lesson.sentences.entries()){
            assert(Number.isFinite(s.audioStartSeconds)&&Number.isFinite(s.audioEndSeconds)&&s.audioStartSeconds>=previous&&s.audioEndSeconds>s.audioStartSeconds&&s.audioEndSeconds<=lesson.durationSeconds);
            previous=s.audioEndSeconds;
            sql+=`INSERT INTO lesson_sentences(lesson_id,position_index,transcript,audio_url,speech_rate,audio_start_seconds,audio_end_seconds) SELECT id,${i+1},${quote(s.text)},${quote('/media/'+lesson.audioFile)},${lesson.speed},${s.audioStartSeconds},${s.audioEndSeconds} FROM lessons WHERE slug=${quote(lesson.slug)};\n`;
        }
        const notes=`Original reviewed sample ${manifest.batchId}. WAV SHA-256 ${lesson.sha256}; 24kHz PCM mono, segment boundaries, non-silent audio, offline ASR and answer evidence checked. Synthetic US voices. Not score/CEFR calibrated. Exercises: /media/listening-samples-20261015/index.html#${lesson.slug}`;
        sql+=`INSERT INTO library_lesson_metadata(lesson_id,topic,focus,source_label,accent,speaker_count,audit_notes,reviewed,lesson_updated_at) SELECT id,${quote(lesson.title)},${quote(lesson.focus)},'DictaLab · Bài gốc / Cartesia','General American (AI)',${speakers},${quote(notes)},TRUE,updated_at FROM lessons WHERE slug=${quote(lesson.slug)};\n`;
        sql+=`INSERT INTO library_lessons(course_id,group_id,lesson_id,position_index,difficulty,purpose) SELECT ${quote(lesson.courseId)},${quote(lesson.courseId+'-1')},id,(SELECT COALESCE(MAX(position_index),0)+1 FROM library_lessons WHERE group_id=${quote(lesson.courseId+'-1')}),${difficulty},${quote(lesson.format+' · '+lesson.focus)} FROM lessons WHERE slug=${quote(lesson.slug)};\n`;
        if(lesson.goal!=="COMMUNICATION"){
            // Only empty scaffolding is removed: never discard a linked lesson or pretend two groups are covered.
            sql+=`DO $$ BEGIN IF EXISTS(SELECT 1 FROM library_lessons WHERE group_id=${quote(lesson.courseId+'-2')}) THEN RAISE EXCEPTION 'Expansion group has content; review required'; END IF; END $$;\n`;
            sql+=`DELETE FROM library_groups WHERE id=${quote(lesson.courseId+'-2')} AND course_id=${quote(lesson.courseId)};\n`;
            sql+=`UPDATE library_groups SET title='Bài nghe mẫu' WHERE id=${quote(lesson.courseId+'-1')} AND course_id=${quote(lesson.courseId)};\n`;
            sql+=`UPDATE library_courses SET target_label=${quote(lesson.format)},description='Bài luyện riêng theo cấu trúc tham khảo IELTS/TOEIC; mục tiêu học, chưa hiệu chuẩn điểm thi.',criteria=${quote(lesson.focus+'; độ khó dựa trên ngôn ngữ và nhiệm vụ, không chỉ tốc độ phát.')},coverage_note='Hiện có 1 bài mẫu đã kiểm tra. Chưa phải đề thi đầy đủ hoặc lộ trình bao phủ toàn bộ dạng bài; đang bổ sung nội dung.',ready=TRUE,revision=revision+1 WHERE id=${quote(lesson.courseId)};\n`;
        }
    }
    sql+="DO $$ BEGIN IF EXISTS(SELECT lesson_id FROM library_lessons x JOIN library_courses c ON c.id=x.course_id GROUP BY lesson_id HAVING count(DISTINCT c.goal)>1) THEN RAISE EXCEPTION 'Cross-goal classification detected'; END IF; END $$;\nCOMMIT;\n";
    return sql;
}

function validate(brief) {
    assert.equal(brief.lessons.length,9,"Expected the approved nine samples");
    assert.equal(brief.calibrated,false);
    const slugs = new Set();
    let calls=0,characters=0;
    for (const lesson of brief.lessons) {
        assert.match(lesson.slug,/^sample-[a-z0-9-]+$/);
        assert(!slugs.has(lesson.slug),"Duplicate lesson slug"); slugs.add(lesson.slug);
        assert(["IELTS","TOEIC","COMMUNICATION"].includes(lesson.goal));
        assert(lesson.courseId.startsWith(lesson.goal === "IELTS" ? "ielts-" : lesson.goal === "TOEIC" ? "toeic-" : "communication-"));
        assert(lesson.speed>=0.8 && lesson.speed<=1.1);
        assert(lesson.sentences.length>=8 && lesson.sentences.length<=30);
        for (const s of lesson.sentences) {
            assert(["A","B"].includes(s.speaker));
            assert(typeof s.text === "string" && s.text===s.text.trim() && /^[A-Z][\x20-\x7E]+[.!?]$/.test(s.text),"Invalid English text");
            assert(!/[<>\n]/.test(s.text) && words(s.text).length<=32);
            assert(s.pauseAfterSeconds == null || s.pauseAfterSeconds>=0 && s.pauseAfterSeconds<=15);
            calls++; characters+=s.text.length;
        }
        assert(lesson.questions.length>=4 && lesson.questions.length<=6);
        for (const q of lesson.questions) {
            assert(Number.isInteger(q.evidenceSentence));
            const s = lesson.sentences[q.evidenceSentence-1];
            assert(s && s.text.toLowerCase().includes(q.evidence.toLowerCase()),"Question evidence does not match transcript");
            if (q.options) assert(q.options.length>=3 && q.options.length<=4 && q.options[q.answer.charCodeAt(0)-65]);
            else assert(q.answer && q.answer.length<=60);
        }
    }
    for (const goal of ["IELTS","TOEIC","COMMUNICATION"]) assert.equal(brief.lessons.filter(l=>l.goal===goal).length,3);
    assert(calls<=180 && characters<=20000,"Batch exceeds the bounded TTS budget");
    return {maximumProviderCalls:calls,billableCharacters:characters};
}

function render(manifest) {
    const cards=manifest.lessons.map((l,index)=>`<article id="${escape(l.slug)}" data-goal="${l.goal}">
        <header class="lesson-heading"><span class="number">${String(index+1).padStart(2,"0")}</span><div><p class="eyebrow">${escape(l.goal === "COMMUNICATION" ? "Giao tiếp" : l.goal)} · ${l.goal === "IELTS" ? "Mục tiêu " : ""}${escape(l.target)}</p><h2>${escape(l.title)}</h2></div></header>
        <p class="format">${escape(l.format)}</p><p>${escape(l.focus)}</p>
        <div class="meta">${Math.round(l.durationSeconds)} giây · ${l.wordCount} từ · ${new Set(l.sentences.filter(s=>s.role!=="question").map(s=>s.speaker)).size} giọng AI</div>
        <audio controls preload="metadata" aria-label="Nghe ${escape(l.title)}" src="${escape(l.audioFile)}"></audio>
        <p><a href="${escape(l.mp3File || l.audioFile)}" download>Tải audio ${l.mp3File ? "MP3" : "WAV"}</a> <span aria-hidden="true"> · </span> <a href="${escape(l.audioFile)}" download>WAV gốc</a></p>
        <p class="instructions">${escape(l.instructions)}</p>
        <section class="questions" aria-label="Câu hỏi ${escape(l.title)}">${l.questions.map((q,n)=>`<label class="question"><span>${escape(q.prompt)}</span>${q.options ? `<span class="options">${q.options.map((o,i)=>`${String.fromCharCode(65+i)}. ${escape(o)}`).join("<br>")}</span>` : ""}${q.options || l.oralOnly ? `<select id="answer-${index}-${n}" aria-label="Đáp án câu ${n+1} của ${escape(l.title)}"><option value="">Chọn đáp án</option>${Array.from({length:q.options?.length || 3},(_,i)=>`<option>${String.fromCharCode(65+i)}</option>`).join("")}</select>` : `<input id="answer-${index}-${n}" autocomplete="off" placeholder="Nhập đáp án" aria-label="Đáp án câu ${n+1} của ${escape(l.title)}">`}</label>`).join("")}</section>
        <button type="button" data-check="${index}">Kiểm tra ${l.questions.length} câu</button><p id="result-${index}" role="status" aria-live="polite"></p>
        <details><summary>Đáp án và giải thích</summary><ol>${l.questions.map(q=>`<li><strong>${escape(q.answer)}</strong> — ${escape(q.explanation)}<br><small lang="en">“${escape(q.evidence)}” · Đoạn ${q.evidenceSentence}</small></li>`).join("")}</ol></details>
        <details><summary>Transcript và nghe từng đoạn</summary><ol class="transcript" lang="en">${l.sentences.map((s,n)=>`<li><button type="button" data-segment="${index},${n}" aria-label="Nghe đoạn ${n+1} của ${escape(l.title)}">${s.speaker} · ${n+1}</button><span>${escape(s.text)}</span></li>`).join("")}</ol></details>
    </article>`).join("");
    const data=JSON.stringify(manifest.lessons.map(l=>({sentences:l.sentences,questions:l.questions}))).replaceAll("<","\\u003c");
    return `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>DictaLab · 9 bài nghe mẫu</title>
    <style>:root{color-scheme:light;--ink:#20342e;--muted:#54635e;--paper:#f7f5ef;--line:#d6ddd4;--accent:#195b47}*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font:16px/1.65 'Segoe UI',sans-serif}main{max-width:1180px;margin:auto;padding:44px 28px}.hero{max-width:820px;margin-bottom:32px}.eyebrow{font:600 12px/1.6 'Segoe UI',sans-serif;letter-spacing:.12em;text-transform:uppercase;color:var(--accent);margin:0}h1{font:500 clamp(36px,5vw,62px)/1.12 Georgia,serif;letter-spacing:-.03em;margin:15px 0 20px}h2{font:500 25px/1.25 Georgia,serif;margin:5px 0}p{margin:10px 0}.intro{font-size:19px;color:var(--muted)}.notice{border-left:3px solid var(--accent);padding:9px 16px;background:#edf2e9}.filters{display:flex;gap:10px;flex-wrap:wrap;margin:28px 0}button,select,input{font:inherit}button{cursor:pointer;border:1px solid var(--accent);background:white;color:var(--accent);border-radius:5px;padding:8px 14px}button[aria-pressed=true],button[data-check]{background:var(--accent);color:white}:focus-visible{outline:3px solid #b35d20;outline-offset:3px}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:22px;align-items:start}article{background:#fff;border:1px solid var(--line);border-radius:8px;padding:24px;min-width:0}.lesson-heading{display:flex;gap:15px}.number{font:italic 30px/1 Georgia,serif;color:#697b70;min-width:36px}.format{font-weight:600;font-size:14px}.meta,small{font-size:13px;color:var(--muted)}audio{width:100%;margin-top:18px}a{color:var(--accent);text-underline-offset:4px}.instructions{font-size:14px;background:var(--paper);padding:14px;border-radius:5px}.question{display:block;margin:18px 0}.question>span:first-child{display:block;font-weight:600;font-size:14px}.options{display:block;font-size:14px;margin:8px 0}select,input{display:block;width:100%;padding:7px 10px;border:1px solid #82918a;border-radius:4px;margin-top:8px}details{border-top:1px solid var(--line);margin-top:18px;padding-top:12px}summary{cursor:pointer;color:var(--accent);font-weight:600}ol{padding-left:23px;font-size:14px}li{padding:6px 0}.transcript li{line-height:1.55}.transcript button{font-size:12px;padding:3px 8px;margin:0 8px 6px 0}.sources{margin-top:40px;border-top:1px solid var(--line);padding-top:18px}.sources li{padding:2px 0}[hidden]{display:none!important}@media(max-width:1000px){.grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:650px){main{padding:26px 16px}.grid{grid-template-columns:1fr}article{padding:20px}.intro{font-size:17px}}</style></head><body><main>
    <header class="hero"><p class="eyebrow">DictaLab · Listening lab / 15.10.2026</p><h1>Nghe đúng dạng.<br>Luyện theo từng mức.</h1><p class="intro">9 bài gốc, 3 hướng luyện. Nghe toàn bài, tự trả lời, rồi mở transcript để luyện từng đoạn.</p><p class="notice">Giọng tổng hợp Cartesia. IELTS/TOEIC là mục tiêu luyện tham khảo, chưa hiệu chuẩn điểm thi. Đây là trích đoạn mẫu, không phải đề thi đầy đủ.</p></header>
    <nav class="filters" aria-label="Lọc hướng luyện"><button type="button" data-filter="ALL" aria-pressed="true">Tất cả · 9 bài</button><button type="button" data-filter="IELTS" aria-pressed="false">IELTS · 4 / 5 / 6</button><button type="button" data-filter="TOEIC" aria-pressed="false">TOEIC · 300 / 500 / 700</button><button type="button" data-filter="COMMUNICATION" aria-pressed="false">Giao tiếp · A1 / A2 / B1</button></nav><p id="filter-status" role="status" aria-live="polite">Đang hiển thị 9 bài.</p><div class="grid">${cards}</div>
    <section class="sources"><h2>Nguồn tham khảo cấu trúc</h2><p>Nội dung tiếng Anh được soạn mới; các nguồn này hướng dẫn cấu trúc và kỹ năng cần luyện.</p><ul>${manifest.sources.map(s=>`<li><a href="${escape(s.url)}" target="_blank" rel="noopener noreferrer">${escape(s.title)}</a></li>`).join("")}</ul><p>Tổng ${manifest.lessons.length} audio · <a href="transcripts.md">Transcript và đáp án</a> · <a href="research.md">Tiêu chí phân mức</a></p></section></main>
    <script>const lessons=${data};const cards=[...document.querySelectorAll('article')];let stopping=null,stoppingAudio=null;
    document.addEventListener('play',event=>{if(event.target.tagName!=='AUDIO')return;document.querySelectorAll('audio').forEach(a=>{if(a!==event.target)a.pause()})},true);
    document.querySelectorAll('[data-filter]').forEach(button=>button.onclick=()=>{let count=0;cards.forEach(c=>{c.hidden=button.dataset.filter!=='ALL'&&c.dataset.goal!==button.dataset.filter;if(!c.hidden)count++;else c.querySelector('audio').pause()});document.querySelectorAll('[data-filter]').forEach(b=>b.setAttribute('aria-pressed',b===button));document.querySelector('#filter-status').textContent='Đang hiển thị '+count+' bài.'});
    const normal=value=>value.trim().toLowerCase().replace(/\\s+/g,' ');
    document.querySelectorAll('[data-check]').forEach(button=>button.onclick=()=>{const i=Number(button.dataset.check);let score=0;lessons[i].questions.forEach((q,n)=>{const value=normal(document.getElementById('answer-'+i+'-'+n).value);if([q.answer,...(q.accepted||[])].some(a=>normal(a)===value))score++});document.getElementById('result-'+i).textContent=score+'/'+lessons[i].questions.length+' câu đúng. Mở phần đáp án để xem giải thích.'});
    document.querySelectorAll('[data-segment]').forEach(button=>button.onclick=async()=>{if(stopping)stopping();const [i,n]=button.dataset.segment.split(',').map(Number),audio=cards[i].querySelector('audio'),s=lessons[i].sentences[n];audio.currentTime=s.audioStartSeconds;const stop=()=>{stopping=null;stoppingAudio=null;audio.removeEventListener('timeupdate',check);audio.pause()},check=()=>{if(audio.currentTime>=s.audioEndSeconds)stop()};stopping=stop;stoppingAudio=audio;audio.addEventListener('timeupdate',check);try{await audio.play()}catch(_){stop();document.getElementById('result-'+i).textContent='Không phát được audio. Hãy dùng nút tải WAV để nghe.'}});
    document.querySelectorAll('audio').forEach(audio=>audio.addEventListener('pause',()=>{if(audio.paused&&stoppingAudio===audio&&stopping)stopping()}));</script></body></html>`;
}

async function main() {
    const mode=process.argv[2];
    assert(["validate","audio","export","render","sql","self-test"].includes(mode),"Usage: node scripts/create-listening-samples.cjs validate|audio|export|render|sql|self-test [directory]");
    const brief=JSON.parse(await fs.readFile(path.join(root,"brief.json"),"utf8"));
    const budget=validate(brief);
    if(mode==="validate"){console.log(JSON.stringify(budget));return;}
    if(mode==="self-test"){
        const pcm=Buffer.alloc(48000,1),joined=combine([{text:"First.",pauseAfterSeconds:5},{text:"Second."}],[pcm,pcm]);
        assert.equal(joined.durationSeconds,7);assert.equal(joined.aligned[1].audioStartSeconds,6);assert.equal(readWave(joined.wave).length,336000);
        assert.throws(()=>combine([{pauseAfterSeconds:99},{}],[pcm,pcm]));
        const invalid=structuredClone(brief);invalid.lessons[0].goal="TOEIC";assert.throws(()=>validate(invalid));
        const preview={...brief,lessons:brief.lessons.map(l=>({...l,durationSeconds:30,wordCount:50,audioFile:l.slug+".wav",sha256:"0".repeat(64),sentences:l.sentences.map((s,i)=>({...s,audioStartSeconds:i,audioEndSeconds:i+1}))}))};
        assert.equal((render(preview).match(/<audio /g)||[]).length,9);assert(!render(preview).includes("undefined"));
        const sql=buildSql(preview);assert(sql.startsWith("\\set ON_ERROR_STOP on\nBEGIN;")&&sql.endsWith("COMMIT;\n"));
        assert.equal((sql.match(/INSERT INTO lessons\(/g)||[]).length,9);assert.equal((sql.match(/INSERT INTO lesson_sentences\(/g)||[]).length,budget.maximumProviderCalls);
        assert(!sql.includes("ON CONFLICT")&&!sql.includes("DELETE FROM lessons")&&!sql.includes("UPDATE lessons"));
        const overlap=structuredClone(preview);overlap.lessons[0].sentences[1].audioStartSeconds=0;assert.throws(()=>buildSql(overlap));
        const course=structuredClone(preview);course.lessons[0].courseId="ielts-band-5-0";assert.throws(()=>buildSql(course));
        console.log("SELF_TEST_OK: nine goals, evidence, TTS budget, pauses, reusable WAV, local player");return;
    }
    const manifestPath=path.join(root,"manifest.json");
    if(mode==="render" || mode==="export" || mode==="sql"){
        const manifest=JSON.parse(await fs.readFile(manifestPath,"utf8"));validate(manifest);
        for(const l of manifest.lessons)assert.equal(hash(await fs.readFile(path.join(root,l.audioFile))),l.sha256);
        if(mode==="sql"){
            await fs.writeFile(path.join(root,"publish-import.sql"),buildSql(manifest),"utf8");
            console.log("SQL_READY: nine new lessons, reviewed metadata, single goal memberships; transaction and empty-group guards");return;
        }
        if(mode==="export"){
            for(const lesson of manifest.lessons){
                const mp3File=lesson.slug+".mp3",output=path.join(root,mp3File);
                const result=spawnSync("ffmpeg",["-hide_banner","-loglevel","error","-y","-i",path.join(root,lesson.audioFile),"-codec:a","libmp3lame","-q:a","2",output],{encoding:"utf8",windowsHide:true});
                assert.equal(result.status,0,"MP3 conversion failed: "+(result.error?.message || result.stderr));
                const check=spawnSync("ffmpeg",["-hide_banner","-loglevel","error","-i",output,"-f","null","-"],{encoding:"utf8",windowsHide:true});
                assert.equal(check.status,0,"MP3 decoding failed");
                lesson.mp3File=mp3File;lesson.mp3Sha256=hash(await fs.readFile(output));
            }
            await fs.writeFile(manifestPath,JSON.stringify(manifest,null,2)+"\n","utf8");
            const transcripts=["# DictaLab — 9 bài nghe mẫu\n\nTranscript gốc, câu hỏi và đáp án. IELTS/TOEIC là nhãn mục tiêu luyện, chưa hiệu chuẩn điểm thi.\n",...manifest.lessons.map(l=>`## ${l.goal} ${l.target} — ${l.title}\n\n${l.format}\n\n${l.instructions}\n\n### Transcript\n\n${l.sentences.map((s,i)=>`${i+1}. **${s.speaker}** (${s.audioStartSeconds.toFixed(2)}–${s.audioEndSeconds.toFixed(2)}s): ${s.text}`).join("\n")}\n\n### Câu hỏi\n\n${l.questions.map((q,i)=>`${i+1}. ${q.prompt}${q.options ? "\n"+q.options.map((o,n)=>`   - ${String.fromCharCode(65+n)}. ${o}`).join("\n") : ""}`).join("\n\n")}\n\n### Đáp án\n\n${l.questions.map((q,i)=>`${i+1}. **${q.answer}** — ${q.explanation} (Đoạn ${q.evidenceSentence}: “${q.evidence}”)`).join("\n")}`)].join("\n\n");
            await fs.writeFile(path.join(root,"transcripts.md"),transcripts+"\n","utf8");
            console.log("EXPORT_OK: nine MP3 files decoded and hashed; transcripts and answers saved");
        }
        await fs.writeFile(path.join(root,"index.html"),render(manifest),"utf8");console.log("PLAYER_READY: "+path.join(root,"index.html"));return;
    }
    if(!process.env.CARTESIA_API_KEY && typeof process.loadEnvFile === "function")process.loadEnvFile(path.resolve(".env"));
    assert(process.env.CARTESIA_API_KEY,"Missing Cartesia runtime configuration");
    const model=process.env.CARTESIA_MODEL || "sonic-3.6",version=process.env.CARTESIA_VERSION || "2026-08-14";
    const voices={A:process.env.CARTESIA_VOICE || "db6b0ed5-d5d3-463d-ae85-518a07d3c2b4",B:"47c38ca4-5f35-497b-b1a3-415245fb35e1"};
    const manifest={...brief,audioModel:model,audioApiVersion:version,voices,budget,lessons:[]};
    await fs.mkdir(path.join(root,"segments"),{recursive:true});
    let completed=0;
    for(const lesson of brief.lessons){
        const segments=[];
        for(let offset=0;offset<lesson.sentences.length;offset+=2){
            const group=await Promise.all(lesson.sentences.slice(offset,offset+2).map(async(s,localIndex)=>{
                const payload={model_id:model,transcript:s.text,voice:voices[s.speaker],language:"en",generation_config:{speed:lesson.speed},output_format:{container:"wav",encoding:"pcm_s16le",sample_rate:24000}};
                const name=hash(JSON.stringify({payload,version})),file=path.join(root,"segments",name+".wav");
                let wave;
                try{wave=await fs.readFile(file)}catch(e){
                    if(e.code!=="ENOENT")throw e;
                    wave=await request("https://api.cartesia.ai/tts/bytes",process.env.CARTESIA_API_KEY,payload,{"Cartesia-Version":version},3000000);
                    readWave(wave);await fs.writeFile(file,wave,{flag:"wx"});
                }
                const pcm=readWave(wave);completed++;
                console.log(`TTS ${completed}/${budget.maximumProviderCalls}: ${lesson.goal} ${lesson.target}, segment ${offset+localIndex+1}`);
                return pcm;
            }));segments.push(...group);
        }
        const combined=combine(lesson.sentences,segments),audioFile=lesson.slug+".wav";
        await fs.writeFile(path.join(root,audioFile),combined.wave);
        manifest.lessons.push({...lesson,sentences:combined.aligned,audioFile,sha256:hash(combined.wave),durationSeconds:combined.durationSeconds,wordCount:lesson.sentences.reduce((n,s)=>n+words(s.text).length,0)});
        await fs.writeFile(manifestPath,JSON.stringify(manifest,null,2)+"\n","utf8");
        console.log(`AUDIO_READY ${lesson.goal} ${lesson.target}: ${combined.durationSeconds.toFixed(2)} seconds`);
    }
    console.log("ALL_NINE_READY: run offline ASR verification before delivery");
}
module.exports = {render};
if (require.main === module) main().catch(error=>{console.error(error.message);process.exitCode=1});
