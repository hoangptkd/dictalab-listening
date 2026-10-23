const {test} = require('node:test');
const assert = require('node:assert/strict');
const {webcrypto, createHash, randomUUID} = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const Sync = require('../../main/resources/static/js/practice-sync.js');
const clone = value => structuredClone(value);
const hash = value => createHash('sha256').update(value).digest('hex');

function harness(shared = new Map()) {
    const answers = ['cat', 'dog', 'pig'];
    const server = {revision: 0, sentence: {sentenceId: 10, hasListened: true, replayCount: 1,
        hintCount: 0, revealCount: 0, requiresReplayAfterReveal: false, canComplete: false,
        words: answers.map((answer, index) => ({index, length: answer.length, correct: false, revealed: false}))},
        grading: {normalization: 'ascii-nfkc-v1', contentVersion: 'content-v1', salt: 'salt',
            hashes: answers.map((answer, index) => hash(`salt\n${index}\n${answer}`))}, completion: null};
    const requests = [], timers = new Map(), receipts = new Map(), attempts = [], held = new Set(), statuses = [];
    let nextTimer = 0;
    const h = {shared, requests, timers, server, receipts, attempts, statuses, failPut: false,
        failBefore: false, failAfter: false, hold: null, auth: 0, failAckStorage: false,
        store: () => ({get: async key => clone(shared.get(key)), put: async record => {
            if (h.failPut) throw new DOMException('Storage full', 'QuotaExceededError');
            shared.set(record.key, clone(record));
        }, remove: async key => shared.delete(key), close() {}})};
    const locks = {async request(name, options, callback) {
        if (held.has(name)) return callback(null);
        held.add(name); try {await callback({name});} finally {held.delete(name);}
    }};
    h.send = async (url, options) => {
        requests.push({url, options: clone(options)});
        if (h.rejectStatus) throw Object.assign(new Error('Rejected'), {status: h.rejectStatus});
        if (h.failBefore) throw new TypeError('Network offline');
        if (options.body) {
            if (h.hold) await h.hold;
            const {body, headers} = options;
            const key = headers['Idempotency-Key'];
            if (receipts.has(key)) {
                assert.equal(receipts.get(key).request, JSON.stringify(body));
                return clone(receipts.get(key).response);
            }
            if (body.baseRevision !== server.revision) throw Object.assign(new Error('Conflict'), {status: 409});
            assert.equal(Number(headers['X-Practice-Events']), body.events.length);
            for (const event of body.events) {
                assert.equal(event.sequence, server.revision + 1);
                if (event.check) {
                    const word = server.sentence.words[event.wordIndex];
                    word.userInput = event.check.answer;
                    word.correct = Sync.normalize(event.check.answer) === answers[event.wordIndex];
                    word.classificationAvailable ||= !word.correct;
                    attempts.push(clone(event));
                } else if (event.replay?.event === 'STARTED') {server.sentence.replayCount++; server.sentence.hasListened = true;}
                else if (event.replay?.event === 'COMPLETED') server.sentence.requiresReplayAfterReveal = false;
                else if (event.hint) {server.sentence.words[0].firstLetter = 'c'; server.sentence.hintCount++;}
                else if (event.classification) server.sentence.words[event.classification.wordIndex].errorType = event.classification.errorType;
                else if (event.complete) server.completion = {lessonCompleted: true, result: {sessionId: 's1'}};
                server.revision++;
            }
            server.sentence.canComplete = server.sentence.words.every(word => word.correct || word.revealed) && !server.sentence.requiresReplayAfterReveal;
            const response = clone(server);
            receipts.set(key, {request: JSON.stringify(body), response});
            if (h.failAfter) {h.failAfter = false; throw new TypeError('Response lost after commit');}
            if (h.failAckStorage) h.failPut = true;
            return response;
        }
        return clone(server);
    };
    h.make = (owner = 1, crypto = webcrypto) => new Sync({owner, sessionId: 's1', send: h.send,
        store: h.store(), locks, crypto: {subtle: crypto.subtle, randomUUID},
        schedule(fn, delay) {const key = ++nextTimer; timers.set(key, {fn, delay}); return key;},
        cancel(key) {timers.delete(key);}, random: () => .5,
        onStatus: status => statuses.push(status), onAuth: () => h.auth++});
    h.open = async () => {h.sync = h.make(); await h.sync.open({sessionId: 's1', lessonTitle: 'Test', sentence: server.sentence}); return h.sync;};
    return h;
}

test('shared normalization fixtures follow Java trim, NFKC, apostrophe and ASCII case rules', () => {
    const fixtures = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../resources/practice-normalization.json'), 'utf8'));
    for (const fixture of fixtures)
        assert.equal(Sync.normalize(fixture.answer), fixture.supported ? fixture.normalized : null, fixture.answer);
});

test('correct and wrong feedback commit locally without waiting for HTTP or a debounce', async () => {
    const h = harness(), sync = await h.open();
    h.failBefore = true;
    assert.equal((await sync.check(0, 'cat')).correct, true);
    assert.equal((await sync.check(1, 'dig')).correct, false);
    assert.equal(h.requests.length, 1, 'local checks must not wait for a request');
    assert.equal(sync.view().words[0].correct, true);
    assert.equal(sync.view().words[1].classificationAvailable, true);
    assert.equal(h.shared.get('1:s1').events.length, 2, 'feedback must survive reload');
    assert.deepEqual([...h.timers.values()].map(timer => timer.delay), [2000]);
    await sync.close();
});

test('batch preserves wrong then corrected answers and sends them in one request', async () => {
    const h = harness(), sync = await h.open();
    await sync.check(0, 'cot'); await sync.check(0, 'cat'); await sync.check(1, 'dog');
    assert.equal(sync.view().words[0].classificationAvailable, true);
    await sync.flush();
    assert.deepEqual(h.attempts.map(event => event.check.answer), ['cot', 'cat', 'dog']);
    assert.equal(h.requests.filter(request => request.options.body).length, 1);
    assert.equal(sync.pending, 0);
    assert.equal(sync.view().words[0].classificationAvailable, true);
    await sync.close();
});

test('the first pending event owns the flush timer; later typing cannot keep resetting it', async () => {
    const h = harness(), sync = await h.open();
    await sync.check(0, 'cot');
    const [timer] = h.timers.keys();
    await sync.check(0, 'cut'); await sync.saveDraft({drafts: [[2, 'p']], elapsedSeconds: 12});
    assert.deepEqual([...h.timers.keys()], [timer]);
    assert.equal(h.shared.get('1:s1').draft.elapsedSeconds, 12);
    await sync.close();
});

test('a lost response retries exactly the same batch key and timing after reload', async () => {
    const h = harness(), sync = await h.open();
    await sync.check(0, 'cot', () => true, 123);
    h.failAfter = true;
    await assert.rejects(sync.flush());
    const uncertain = clone(h.shared.get('1:s1').batch);
    await sync.check(0, 'cat');
    await sync.close();
    const restored = h.make();
    await restored.open();
    const retries = h.requests.filter(request => request.options.headers?.['Idempotency-Key'] === uncertain.key);
    assert.equal(retries.length, 2);
    assert.deepEqual(retries[0].options.body, retries[1].options.body);
    assert.equal(h.attempts.length, 2);
    assert.deepEqual(h.attempts.map(event => event.check.answer), ['cot', 'cat']);
    assert.equal(restored.pending, 0);
    assert.equal(restored.view().words[0].correct, true);
    await restored.close();
});

test('offline events and raw drafts survive reopen without automatically grading the draft', async () => {
    const h = harness(), sync = await h.open();
    await sync.saveDraft({drafts: [[1, 'd'], [2, '']], elapsedSeconds: 42});
    await sync.check(0, 'cat'); h.failBefore = true;
    await assert.rejects(sync.flush()); await sync.close();
    const restored = h.make(); await restored.open();
    assert.equal(restored.view().words[0].correct, true);
    assert.deepEqual(restored.draft, {drafts: [[1, 'd'], [2, '']], elapsedSeconds: 42});
    assert.equal(restored.pending, 1);
    assert.equal(h.attempts.length, 0);
    h.failBefore = false; await restored.flush();
    assert.equal(h.attempts.length, 1);
    await restored.close();
});

test('stale asynchronous hash results cannot create attempts or change feedback', async () => {
    const h = harness(), sync = await h.open();
    let finish, current = true;
    sync.crypto = {randomUUID, subtle: {digest: () => new Promise(resolve => {finish = resolve;})}};
    const checking = sync.check(0, 'cat', () => current);
    await Promise.resolve(); current = false;
    finish(await webcrypto.subtle.digest('SHA-256', new TextEncoder().encode('salt\n0\ncat')));
    await assert.rejects(checking, {name: 'AbortError'});
    assert.equal(sync.pending, 0); assert.equal(sync.view().words[0].correct, false);
    assert.notEqual(sync.phase, 'storage');
    await sync.close();
});

test('a storage failure never reports accepted local feedback and retains the raw draft', async () => {
    const h = harness(), sync = await h.open();
    await sync.saveDraft({drafts: [[0, 'cat']], elapsedSeconds: 2});
    h.failPut = true;
    await assert.rejects(sync.check(0, 'cat'), {name: 'QuotaExceededError'});
    assert.equal(sync.view().words[0].correct, false);
    assert.equal(sync.phase, 'storage');
    assert.deepEqual(h.shared.get('1:s1').draft.drafts, [[0, 'cat']]);
    await sync.close();
});

test('failure to persist an acknowledgement retains its immutable batch for recovery', async () => {
    const h = harness(), sync = await h.open(); await sync.check(0, 'cat');
    h.failAckStorage = true;
    await assert.rejects(sync.flush());
    assert.equal(sync.phase, 'storage');
    assert.equal(h.shared.get('1:s1').events.length, 1);
    assert.ok(h.shared.get('1:s1').batch);
    h.failPut = false; h.failAckStorage = false; await sync.close();
    const restored = h.make(); await restored.open();
    assert.equal(restored.pending, 0); assert.equal(h.attempts.length, 1);
    await restored.close();
});

test('401 pauses the sender, keeps durable events and never auto-resumes for another owner', async () => {
    const h = harness(), sync = await h.open(); await sync.check(0, 'cat');
    h.rejectStatus = 401;
    await assert.rejects(sync.flush());
    assert.equal(sync.phase, 'auth'); assert.equal(h.auth, 1);
    assert.equal(h.shared.get('1:s1').events.length, 1);
    assert.equal(h.timers.size, 0);
    await sync.close(); h.rejectStatus = null;
    const another = h.make(2);
    assert.equal(await another.open(), null);
    assert.equal(h.shared.get('1:s1').events.length, 1);
});

test('a revision conflict stops retry and preserves events instead of rebasing first attempts', async () => {
    const h = harness(), sync = await h.open(); await sync.check(0, 'cot');
    h.server.revision = 10;
    await assert.rejects(sync.flush(), {status: 409});
    assert.equal(sync.phase, 'conflict'); assert.equal(sync.pending, 1); assert.equal(h.timers.size, 0);
    await sync.close();
    const restored = h.make(); await restored.open();
    assert.equal(restored.phase, 'conflict'); assert.equal(restored.pending, 1);
    await restored.close();
});

test('Web Locks prevents a second tab from writing the same owner/session', async () => {
    const h = harness(), sync = await h.open(), second = h.make();
    await assert.rejects(second.open(), /tab khác/);
    await sync.close();
    const reopened = h.make(); await reopened.open(); await reopened.close();
});

test('replay is persisted before subsequent checks and commands form ordered barriers', async () => {
    const h = harness(), sync = await h.open();
    h.failBefore = true;
    await sync.command({replay: {event: 'STARTED', playbackSpeed: .8}});
    await sync.check(0, 'cot');
    await assert.rejects(sync.flush());
    h.failBefore = false;
    await sync.command({hint: {type: 'FIRST_LETTER', wordIndex: 0}});
    const events = h.requests.filter(r => r.options.body).slice(-3).map(r => r.options.body.events);
    assert.equal(events[0][0].replay.event, 'STARTED');
    assert.equal(events[1][0].check.answer, 'cot');
    assert.equal(events[2][0].hint.type, 'FIRST_LETTER');
    assert.equal(sync.view().words[0].firstLetter, 'c');
    await sync.close();
});

test('unsupported Unicode and absent crypto wait for server grading without guessing a verdict', async () => {
    const h = harness(), sync = await h.open();
    sync.crypto = {randomUUID};
    const result = await sync.check(0, 'cat');
    assert.equal(result.correct, true); assert.equal(sync.pending, 0); assert.equal(h.attempts.length, 1);
    await sync.close();
});

test('events typed during a slow batch are overlaid and cannot be erased by its older snapshot', async () => {
    const h = harness(), sync = await h.open(); await sync.check(0, 'cat');
    let release; h.hold = new Promise(resolve => {release = resolve;});
    const flight = sync.flush();
    for (let i = 0; i < 10; i++) await Promise.resolve();
    await sync.check(1, 'dog');
    assert.equal(sync.view().words[1].correct, true);
    h.hold = null; release(); await flight;
    assert.equal(sync.view().words[1].correct, true); assert.equal(sync.pending, 0);
    assert.equal(h.attempts.length, 2);
    await sync.close();
});

test('completion acknowledged before a disconnect waits for a fresh next sentence and is never submitted twice', async () => {
    const h = harness(), sync = await h.open();
    await sync.check(0, 'cat'); await sync.check(1, 'dog'); await sync.check(2, 'pig');
    await sync.command({complete: {timeSpentSeconds: 12}});
    const before = h.requests.length;
    assert.equal(sync.barrier, true);
    assert.equal((await sync.command({complete: {timeSpentSeconds: 20}})).lessonCompleted, true);
    assert.equal(h.requests.length, before);
    h.failBefore = true;
    await assert.rejects(sync.nextSentence());
    assert.equal(sync.view().sentenceId, 10);
    assert.ok(sync.completion);
    h.failBefore = false; h.server.completion = null; h.server.sentence.sentenceId = 11;
    assert.equal((await sync.nextSentence()).sentenceId, 11);
    assert.equal(sync.completion, null);
    await sync.close();
});

test('changed content cannot silently reinterpret a saved raw draft for the same sentence', async () => {
    const h = harness(), sync = await h.open();
    await sync.saveDraft({drafts: [[0, 'ca']], elapsedSeconds: 3}); await sync.close();
    h.server.grading.contentVersion = 'changed-content';
    const restored = h.make();
    assert.equal((await restored.open()).sentence.sentenceId, 10);
    assert.equal(restored.phase, 'conflict');
    assert.equal(restored.blocked, true);
    await assert.rejects(restored.check(0, 'cat'));
    assert.deepEqual(h.shared.get('1:s1').draft.drafts, [[0, 'ca']]);
    await restored.close(true);
    assert.equal(h.shared.has('1:s1'), false);
});

test('classification toggles retain the final choice instead of matching an older pending command', async () => {
    const h = harness(), sync = await h.open();
    await sync.check(0, 'cot'); await sync.flush();
    let release; h.hold = new Promise(resolve => {release = resolve;});
    const first = sync.command({classification: {wordIndex: 0, errorType: 'UNKNOWN'}});
    await sync.writes;
    const second = sync.command({classification: {wordIndex: 0, errorType: 'KNOWN_BUT_MISSED'}});
    await sync.writes;
    const third = sync.command({classification: {wordIndex: 0, errorType: 'UNKNOWN'}});
    await sync.writes;
    h.hold = null; release(); await Promise.all([first, second, third]);
    assert.equal(sync.view().words[0].errorType, 'UNKNOWN');
    const choices = h.requests.flatMap(r => r.options.body?.events || []).filter(event => event.classification);
    assert.deepEqual(choices.map(event => event.classification.errorType), ['UNKNOWN', 'KNOWN_BUT_MISSED', 'UNKNOWN']);
    await sync.close();
});
