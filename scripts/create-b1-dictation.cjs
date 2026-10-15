#!/usr/bin/env node
"use strict";

// Offline content preparation only: never publishes or connects to the database.
// Run generate, review transcript, then audio, review audio, then sql.
const fs = require("node:fs/promises");
const path = require("node:path");
const {createHash, randomUUID} = require("node:crypto");
const assert = require("node:assert/strict");
const SAMPLE_RATE = 24000;
const BYTES_PER_SECOND = SAMPLE_RATE * 2;
const topics = [
    {id: "changing-travel-plans", title: "Đổi kế hoạch chuyến đi", topic: "Du lịch", context: "Two friends change their weekend travel plans after a train cancellation and agree on a practical alternative."},
    {id: "returning-an-order", title: "Đổi trả đơn hàng", topic: "Mua sắm", context: "A customer explains a problem with a delivered product and a shop employee offers a reasonable exchange and collection arrangement."},
    {id: "planning-a-team-event", title: "Lên kế hoạch hoạt động nhóm", topic: "Công việc", context: "Two colleagues discuss a small team event, compare options within a budget, and divide the tasks."}
];
const instruction = `Write original CEFR B1 English listening-dictation content, not a quiz or an AI tutor chat.
Return only JSON with schema {"sentences":[{"speaker":"A","text":"One complete English sentence."}]}.
Exactly twelve sentences; speakers alternate A,B,A,B. Each text is exactly one sentence, eight to eighteen words.
Total one hundred thirty to one hundred eighty words. A coherent natural conversation with a problem,
explanation, negotiation and a clear outcome. Use everyday B1 vocabulary, a few natural contractions,
past or present perfect and varied clauses with because, although, if or when where appropriate.
No speaker names, digits, abbreviations, ellipses, dialogue tags, stage directions or markdown inside text.
Write numbers as spoken English words. Avoid rare jargon, politics, medical/legal advice and famous characters.
The scenario must make sense; each reply must respond to the previous speaker. No single-word replies.
Do not include Vietnamese in the English sentences. Do not copy an existing textbook dialogue.`;

function words(text) { return text.match(/[\p{L}\p{N}]+(?:['’\-][\p{L}\p{N}]+)*/gu) || []; }
function validateTranscript(sentences) {
    assert.equal(sentences?.length, 12, "Expected twelve sentences");
    const unique = new Set();
    for (const [index, sentence] of sentences.entries()) {
        assert.equal(sentence.speaker, index % 2 === 0 ? "A" : "B");
        assert.equal(typeof sentence.text, "string");
        const text = sentence.text;
        assert(text === text.trim() && /^[A-Z][\x20-\x7E]+[.!?]$/.test(text), "Invalid English text");
        assert(!/[.!?]/.test(text.slice(0, -1)) && !/[0-9<>\n:]/.test(text), "Expected one spoken sentence");
        assert(words(text).length >= 8 && words(text).length <= 18, "Sentence word count");
        assert(!unique.has(text.toLowerCase()), "Duplicate sentence");
        unique.add(text.toLowerCase());
    }
    const count = sentences.reduce((sum, sentence) => sum + words(sentence.text).length, 0);
    assert(count >= 130 && count <= 180, "Total word count");
    return count;
}

async function request(url, key, body, headers = {}, limit = 100000) {
    const response = await fetch(url, {method: "POST", signal: AbortSignal.timeout(60000),
        headers: {Authorization: `Bearer ${key}`, "Content-Type": "application/json", ...headers},
        body: JSON.stringify(body)});
    if (!response.ok) { await response.body?.cancel(); throw new Error(`Provider HTTP ${response.status}`); }
    const chunks = []; let length = 0;
    for await (const chunk of response.body) {
        length += chunk.length;
        if (length > limit) throw new Error("Provider response exceeds limit");
        chunks.push(chunk);
    }
    return Buffer.concat(chunks);
}

function readWave(wave) {
    assert(wave.length >= 44 && wave.toString("ascii", 0, 4) === "RIFF"
        && wave.toString("ascii", 8, 12) === "WAVE", "Invalid WAV header");
    const riffSize = wave.readUInt32LE(4);
    assert(riffSize === 0xffffffff || riffSize + 8 === wave.length, "Truncated WAV");
    let format, pcm;
    for (let offset = 12; offset + 8 <= wave.length;) {
        const tag = wave.toString("ascii", offset, offset + 4), size = wave.readUInt32LE(offset + 4);
        // Cartesia's complete HTTP body may retain streaming RIFF/data size sentinels.
        const start = offset + 8, end = tag === "data" && size === 0xffffffff ? wave.length : start + size;
        assert(end <= wave.length, "Truncated WAV chunk");
        if (tag === "fmt ") {
            assert(size >= 16);
            format = {encoding: wave.readUInt16LE(start), channels: wave.readUInt16LE(start + 2),
                rate: wave.readUInt32LE(start + 4), bits: wave.readUInt16LE(start + 14)};
        }
        if (tag === "data") { assert(!pcm, "Duplicate PCM chunk"); pcm = wave.subarray(start, end); }
        offset = end + (size % 2);
    }
    assert.deepEqual(format, {encoding: 1, channels: 1, rate: SAMPLE_RATE, bits: 16});
    assert(pcm?.length > 0 && pcm.length % 2 === 0, "Empty/unaligned PCM");
    return pcm;
}

function writeWave(pcm) {
    const header = Buffer.alloc(44);
    header.write("RIFF"); header.writeUInt32LE(pcm.length + 36, 4); header.write("WAVE", 8);
    header.write("fmt ", 12); header.writeUInt32LE(16, 16); header.writeUInt16LE(1, 20);
    header.writeUInt16LE(1, 22); header.writeUInt32LE(SAMPLE_RATE, 24);
    header.writeUInt32LE(BYTES_PER_SECOND, 28); header.writeUInt16LE(2, 32); header.writeUInt16LE(16, 34);
    header.write("data", 36); header.writeUInt32LE(pcm.length, 40);
    return Buffer.concat([header, pcm]);
}

function combine(sentences, segments) {
    assert.equal(sentences.length, segments.length);
    const chunks = [], aligned = []; let bytes = 0;
    for (const [index, pcm] of segments.entries()) {
        const duration = pcm.length / BYTES_PER_SECOND;
        assert(duration >= 0.5 && duration <= 20, "Unexpected sentence duration");
        const start = bytes / BYTES_PER_SECOND;
        chunks.push(pcm); bytes += pcm.length;
        aligned.push({...sentences[index], position: index + 1,
            audioStartSeconds: start, audioEndSeconds: bytes / BYTES_PER_SECOND});
        if (index + 1 < segments.length) {
            const seconds = sentences[index].pauseAfterSeconds ?? 0.2;
            assert(Number.isFinite(seconds) && seconds >= 0 && seconds <= 15, "Invalid pause");
            const gap = Buffer.alloc(Math.round(seconds * SAMPLE_RATE) * 2);
            chunks.push(gap); bytes += gap.length;
        }
    }
    return {wave: writeWave(Buffer.concat(chunks)), aligned, durationSeconds: bytes / BYTES_PER_SECOND};
}

const quote = value => "'" + String(value).replaceAll("'", "''") + "'";
function buildSql(manifest) {
    assert.equal(manifest.lessons.length, 3);
    let sql = "\\set ON_ERROR_STOP on\nBEGIN;\n";
    for (const lesson of manifest.lessons) {
        lesson.wordCount = validateTranscript(lesson.sentences);
        assert.match(lesson.slug, /^ai-b1-[a-z0-9-]+$/);
        assert.match(lesson.audioFile, /^[0-9a-f-]{36}\.wav$/);
        assert(lesson.sha256?.length === 64 && lesson.durationSeconds > 0);
        sql += `DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM audio_s3_aliases WHERE audio_url=${quote('/media/' + lesson.audioFile)} AND sha256=${quote(lesson.sha256)}) THEN RAISE EXCEPTION 'Upload verified audio to S3 before import'; END IF; END $$;\n`;
        sql += `INSERT INTO lessons(slug,title,description,level,topic,estimated_minutes,display_order,active,content_origin,speaker_count,accent_profile,vocabulary_difficulty,grammar_difficulty,sentence_difficulty,assessment_calibrated)
VALUES (${quote(lesson.slug)},${quote(lesson.title)},${quote(lesson.description)},'B1',${quote(lesson.topic)},8,
(SELECT COALESCE(MAX(display_order),0)+1 FROM lessons WHERE level='B1' AND content_origin<>'BRITISH_COUNCIL_PERSONAL'),FALSE,'ADMIN_UPLOAD',2,'English / AI voices',3,3,3,FALSE);\n`;
        let previous = 0;
        for (const [index, sentence] of lesson.sentences.entries()) {
            const start = sentence.audioStartSeconds, end = sentence.audioEndSeconds;
            assert(Number.isFinite(start) && Number.isFinite(end) && start >= previous && end > start
                && end <= lesson.durationSeconds + 0.00001, "Invalid segment boundary");
            previous = end;
            sql += `INSERT INTO lesson_sentences(lesson_id,position_index,transcript,audio_url,speech_rate,audio_start_seconds,audio_end_seconds)
SELECT id,${index + 1},${quote(sentence.text)},${quote('/media/' + lesson.audioFile)},1.0,${start},${end}
FROM lessons WHERE slug=${quote(lesson.slug)};\n`;
        }
    }
    return sql + "COMMIT;\n"; // No upsert: a second import fails without changing an existing lesson.
}

async function main() {
    const [mode, output] = process.argv.slice(2);
    if (mode === "self-test") {
        const pcm = Buffer.alloc(BYTES_PER_SECOND);
        assert.deepEqual(readWave(writeWave(pcm)), pcm);
        const streaming = writeWave(pcm);
        streaming.writeUInt32LE(0xffffffff,4); streaming.writeUInt32LE(0xffffffff,40);
        assert.deepEqual(readWave(streaming),pcm);
        assert.throws(() => readWave(writeWave(pcm).subarray(0, 100)));
        const combined = combine([{text: "First."}, {text: "Second."}], [pcm, pcm]);
        assert.equal(combined.aligned[1].audioStartSeconds, 1.2);
        assert.equal(combined.durationSeconds, 2.2);
        assert.equal(readWave(combined.wave).length, Math.round(BYTES_PER_SECOND * 2.2));
        assert.equal(quote("It's safe"), "'It''s safe'");
        assert.throws(() => validateTranscript([]));
        const sentences = Array.from({length:12}, (_, index) => ({speaker:index % 2 ? "B" : "A",
            text:`We should check the ${["first","second","third","fourth","fifth","sixth","seventh","eighth","ninth","tenth","eleventh","twelfth"][index]} option before making our final decision today.`}));
        validateTranscript(sentences);
        const lesson = {slug:"ai-b1-self-test", title:"Test", topic:"Test", description:"It's generated", audioFile:"00000000-0000-0000-0000-000000000000.wav", sha256:"0".repeat(64), ...combine(sentences, Array(12).fill(pcm))};
        lesson.sentences = lesson.aligned;
        const sql = buildSql({lessons: Array(3).fill(lesson)});
        assert(sql.startsWith("\\set ON_ERROR_STOP on\nBEGIN;") && sql.endsWith("COMMIT;\n"));
        assert(!/UPDATE|DELETE|ON CONFLICT/.test(sql));
        lesson.sentences[1].audioStartSeconds = 0;
        assert.throws(() => buildSql({lessons:Array(3).fill(lesson)}));
        console.log("SELF_TEST_OK: WAV, exact boundaries, transcript validation, insert-only draft SQL"); return;
    }
    assert(["generate", "audio", "sql"].includes(mode) && output,
        "Usage: node scripts/create-b1-dictation.cjs generate|audio|sql <output-directory>");
    const root = path.resolve(output), manifestPath = path.join(root, "manifest.json");
    await fs.mkdir(root, {recursive: true});
    if (mode === "generate") {
        assert(process.env.SHOPAIKEY_API_KEY, "Missing ShopAIKey key");
        try { await fs.access(manifestPath); throw new Error("Manifest already exists; review/resume it instead"); }
        catch (error) { if (error.code !== "ENOENT") throw error; }
        const model = process.env.DICTALAB_CONVERSATION_MODEL || "gemini-2.5-flash";
        const manifest = {level: "B1", generationModel:model, generatedAt:new Date().toISOString(), lessons:[]};
        for (const topic of topics) {
            let parsed, count, context = topic.context;
            // One cached repair of invalid content, never an automatic network retry.
            for (let attempt = 0; attempt < 2; attempt++) {
                const cache = path.join(root, `${topic.id}${attempt ? '-repair' : ''}-response.json`);
                let body;
                try { body = JSON.parse(await fs.readFile(cache,"utf8")); }
                catch (error) {
                    if (error.code !== "ENOENT") throw error;
                    body = JSON.parse((await request(process.env.SHOPAIKEY_URL || "https://api.shopaikey.com/v1/chat/completions",
                        process.env.SHOPAIKEY_API_KEY, {model, temperature:0, max_tokens:2200,
                            messages:[{role:"system",content:instruction},{role:"user",content:context}]})).toString("utf8"));
                    await fs.writeFile(cache, JSON.stringify(body,null,2)+"\n", "utf8");
                }
                const content = body.choices?.[0]?.message?.content;
                assert(typeof content === "string" && content.length <= 12000, "Missing model content");
                try {
                    parsed = JSON.parse(content.slice(content.indexOf("{"), content.lastIndexOf("}") + 1));
                    count = validateTranscript(parsed.sentences); break;
                } catch (error) {
                    if (attempt) throw error;
                    context = `${topic.context}\nRepair the following rejected output: ${content}\nValidation failure: ${error.message}. Each array entry must contain only ONE sentence with no internal period, question mark or exclamation mark. Combine interjections with commas. Keep twelve alternating entries and all length limits.`;
                    console.log(`REPAIR ${topic.id}: ${error.message}`);
                }
            }
            manifest.lessons.push({slug:`ai-b1-${topic.id}-20261002`, title:`B1 · ${topic.title}`, topic:topic.topic,
                description:"Hội thoại B1 nguyên bản do ShopAIKey tạo, giọng nói tổng hợp Cartesia. Luyện nghe hai người nói và chép từng câu; mức B1 chưa được hiệu chỉnh bằng bài kiểm tra chuẩn hóa.",
                wordCount:count, sentences:parsed.sentences});
            console.log(`GENERATED ${topic.id}: ${count} words, twelve sentences`);
        }
        await fs.writeFile(manifestPath, JSON.stringify(manifest, null, 2) + "\n", {encoding:"utf8", flag:"wx"});
        console.log("REVIEW_REQUIRED: inspect manifest.json before audio generation"); return;
    }
    const manifest = JSON.parse(await fs.readFile(manifestPath, "utf8"));
    assert.equal(manifest.level, "B1"); assert.equal(manifest.lessons.length, 3);
    if (mode === "sql") {
        for (const lesson of manifest.lessons) {
            const audio = await fs.readFile(path.join(root, lesson.audioFile));
            readWave(audio);
            assert.equal(createHash("sha256").update(audio).digest("hex"), lesson.sha256);
        }
        await fs.writeFile(path.join(root, "draft-import.sql"), buildSql(manifest), "utf8");
        console.log("SQL_READY: drafts only; import after backup and verified media copy"); return;
    }
    assert(process.env.CARTESIA_API_KEY, "Missing Cartesia key");
    manifest.audioModel = process.env.CARTESIA_MODEL || "sonic-3.6";
    manifest.voices = {A:process.env.CARTESIA_VOICE || "db6b0ed5-d5d3-463d-ae85-518a07d3c2b4",
        B:"47c38ca4-5f35-497b-b1a3-415245fb35e1"};
    await fs.mkdir(path.join(root, "segments"), {recursive:true});
    for (const lesson of manifest.lessons) {
        lesson.wordCount = validateTranscript(lesson.sentences);
        const segments = [];
        for (const [index, sentence] of lesson.sentences.entries()) {
            const fingerprint = createHash("sha256").update(JSON.stringify({text:sentence.text,
                voice:manifest.voices[sentence.speaker],model:manifest.audioModel,speed:0.95})).digest("hex").slice(0,16);
            const file = path.join(root, "segments", `${lesson.slug}-${index + 1}-${fingerprint}.wav`);
            let wave;
            try { wave = await fs.readFile(file); }
            catch (error) {
                if (error.code !== "ENOENT") throw error;
                wave = await request("https://api.cartesia.ai/tts/bytes", process.env.CARTESIA_API_KEY,
                    {model_id:manifest.audioModel, transcript:sentence.text, voice:manifest.voices[sentence.speaker], language:"en",
                        generation_config:{speed:0.95}, output_format:{container:"wav", encoding:"pcm_s16le", sample_rate:SAMPLE_RATE}},
                    {"Cartesia-Version":process.env.CARTESIA_VERSION || "2026-08-14"}, 3000000);
                readWave(wave);
                await fs.writeFile(file, wave, {flag:"wx"});
            }
            segments.push(readWave(wave));
            console.log(`AUDIO ${lesson.slug} ${index + 1}/12`);
        }
        const combined = combine(lesson.sentences, segments);
        lesson.audioFile ||= `${randomUUID()}.wav`;
        await fs.writeFile(path.join(root, lesson.audioFile), combined.wave);
        lesson.sentences = combined.aligned; lesson.durationSeconds = combined.durationSeconds;
        lesson.sha256 = createHash("sha256").update(combined.wave).digest("hex");
        lesson.speechRateWpm = Math.round(lesson.wordCount * 60 / lesson.durationSeconds);
        await fs.writeFile(manifestPath, JSON.stringify(manifest,null,2)+"\n", "utf8");
        console.log(`ASSEMBLED ${lesson.slug}: ${lesson.durationSeconds.toFixed(2)} seconds, ${lesson.speechRateWpm} WPM`);
    }
    console.log("REVIEW_REQUIRED: verify audio before SQL import/publication");
}

module.exports = {words, request, readWave, combine};
if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
