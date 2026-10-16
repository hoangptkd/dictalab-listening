const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/conversation.js'), 'utf8');

function state(answer, evaluation) {
    return {id: 'session', scenarioVi: 'Quán', difficulty: 'A1', turnNumber: 1,
        turns: [{prompt: {taskVi: 'Nghe giờ hẹn', questionVi: '<script>alert(1)</script>'}, done: false,
            attempts: answer ? [{requestId: 'saved-request', answerVi: answer, ...(evaluation ? {evaluation} : {})}] : []}]};
}
function storage() {
    return {getItem(key) { return this[key] || null; }, setItem(key, value) { this[key] = value; }, removeItem(key) { delete this[key]; }};
}
function harness(api, timers = {setTimeout}, sessionStorage = storage()) {
    const nodes = new Map();
    const node = selector => {
        if (!nodes.has(selector)) nodes.set(selector, {value: '', dataset: {}, textContent: '', disabled: false, listeners: {},
            addEventListener(name, handler) { this.listeners[name] = handler; }, focus() {}, setCustomValidity() {}, reportValidity() {}});
        return nodes.get(selector);
    };
    const root = {innerHTML: '', setAttribute() {}, removeAttribute() {},
        querySelector(selector) {
            if (selector === 'textarea' && !this.innerHTML.includes('<textarea')) return null;
            if (selector === '#conversation-answer-form' && !this.innerHTML.includes('id="conversation-answer-form"')) return null;
            return node(selector);
        }, querySelectorAll(selector) {
            if (selector === '[data-action]') return [...root.innerHTML.matchAll(/data-action="([^"]+)"/g)].map(([,action]) => {
                const button = node(action); button.dataset.action = action; return button;
            });
            return [];
        }};
    let stopped = 0, played = 0;
    const window = {DictaLabListeningBridge: {api, currentUser: () => ({id: 1}), navigate() {},
        stopRecordedAudio() { stopped++; }, async playRecordedAudio() { played++; }}};
    vm.runInNewContext(source, {window, document: {querySelector: () => root}, sessionStorage, crypto: {randomUUID: () => 'new-request'}, ...timers});
    return {module: window.DictaLabConversation, root, node, stopped: () => stopped, played: () => played};
}
test('navigation ignores a late response and stops conversation audio', async () => {
    let resolve;
    const h = harness(() => new Promise(done => { resolve = done; }));
    const show = h.module.show('old'); h.module.leave(); resolve(state()); await show;
    assert.doesNotMatch(h.root.innerHTML, /Nghe giờ hẹn/); assert.ok(h.stopped() >= 2);
});
test('learner/AI strings are escaped and transcript is not displayed by default', async () => {
    const h = harness(async () => state('<img onerror=alert(1)>', {feedbackVi: '<b>fake</b>', clarificationVi: 'Giờ nào?'}));
    await h.module.show('session');
    assert.doesNotMatch(h.root.innerHTML, /<script>|<img|<b>fake/);
    assert.match(h.root.innerHTML, /&lt;img/);
    assert.match(h.root.innerHTML, /id="conversation-transcript" hidden/);
    assert.match(h.root.innerHTML, /id="conversation-translation" hidden/);
});

test('assistance is collapsed without requesting help and playback controls remain direct', async () => {
    const calls = [];
    const h = harness(async (url, options) => { calls.push({url, options}); return state(); });
    await h.module.show('session');
    const html = h.root.innerHTML;
    const details = html.match(/<details id="conversation-assistance"[^>]*>/)[0];
    assert.doesNotMatch(details, /\bopen\b/);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].options, undefined);
    for (const action of ['play', 'slow', 'stop']) {
        assert.ok(html.indexOf(`data-action="${action}"`) < html.indexOf('id="conversation-assistance"'));
    }
    assert.ok(html.indexOf('Mở hỗ trợ sẽ được ghi nhận') < html.indexOf('id="conversation-assistance"'));
});
test('saved ungraded response is read-only and retry uses its existing request id', async () => {
    const requests = [];
    const h = harness(async (url, options) => {
        requests.push({url, options});
        return options ? state('mười giờ', {feedbackVi: 'Đã hiểu', clarificationVi: ''}) : state('mười giờ');
    });
    await h.module.show('session');
    assert.match(h.root.innerHTML, /readonly/); assert.match(h.root.innerHTML, /Thử chấm lại/);
    h.node('textarea').value = 'mười giờ';
    h.node('#conversation-answer-form').listeners.submit({preventDefault() {}});
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(requests[1].options.body.requestId, 'saved-request');
    assert.equal(requests[1].options.body.answerVi, 'mười giờ');
});
test('audio prepared after navigating away is never played', async () => {
    let resolve;
    const h = harness(async (_url, options) => options ? new Promise(done => { resolve = done; }) : state());
    await h.module.show('session');
    const playing = h.node('play').listeners.click();
    h.module.leave(); resolve({...state(), audioUrl: '/private.wav'}); await playing;
    assert.equal(h.played(), 0);
});

test('Stop cancels playback while the server is still preparing audio', async () => {
    let resolve;
    const h = harness(async (_url, options) => options ? new Promise(done => { resolve = done; }) : state());
    await h.module.show('session');
    const playing = h.node('play').listeners.click();
    await h.node('stop').listeners.click();
    resolve({...state(), audioUrl: '/private.wav'}); await playing;
    assert.equal(h.played(), 0);
});

test('queued audio uses read-only status polling and counts playback only when ready', async () => {
    const requests = [];
    let plays = 0;
    const h = harness(async (url, options) => {
        requests.push({url, options});
        if (options) return ++plays === 1 ? {...state(), audioStatus: 'PENDING'} : {...state(), audioUrl: '/private.wav'};
        if (url.includes('audio-status')) return {status: 'READY'};
        return state();
    }, {setTimeout: done => setImmediate(done)});
    await h.module.show('session');
    await h.node('play').listeners.click();
    assert.equal(h.played(), 1);
    assert.equal(plays, 2);
    assert.equal(requests.filter(r => r.url.includes('audio-status')).length, 1);
    assert.equal(requests.find(r => r.url.includes('audio-status')).options, undefined);
});

test('Stop while waiting in RabbitMQ cancels polling and never autoplays', async () => {
    let wake;
    const requests = [];
    const h = harness(async (url, options) => {
        requests.push(url); return options ? {...state(), audioStatus: 'PENDING'} : state();
    }, {setTimeout: done => {wake = done;}});
    await h.module.show('session');
    const pending = h.node('play').listeners.click();
    await new Promise(done => setImmediate(done));
    await h.node('stop').listeners.click();
    wake(); await pending;
    assert.equal(h.played(), 0);
    assert.equal(requests.some(url => url.includes('audio-status')), false);
});

test('failed queued audio does not automatically submit a paid retry', async () => {
    let posts = 0;
    const h = harness(async (_url, options) => {
        if (options) {posts++; return {...state(), audioStatus: 'EXHAUSTED'};}
        return state();
    });
    await h.module.show('session');
    await h.node('play').listeners.click();
    assert.equal(posts, 1); assert.equal(h.played(), 0);
    assert.match(h.node('#conversation-status').textContent, /hết lượt thử/);
});

test('opening an empty saved session creates its first task in the same session', async () => {
    const calls = [];
    const h = harness(async (url, options) => {
        calls.push({url, options});
        return options ? state() : {...state(), turnNumber: 0, turns: []};
    });
    await h.module.show('session');
    assert.equal(calls.length, 2);
    assert.equal(calls[1].url, '/api/v1/conversation/sessions/session/next');
    assert.equal(calls[1].options.body.turnNumber, 0);
    assert.match(h.root.innerHTML, /Nghe giờ hẹn/);
});

function doneTurn(captured = 2) {
    return {...state().turns[0], done: true, capturedFacts: captured, totalFacts: 2,
        attempts: Array.from({length: 3}, () => ({answerVi: 'đáp án của tôi', evaluation: {feedbackVi: 'Phản hồi'}})),
        review: {utteranceEn: 'At eleven, not ten.', translationVi: 'Mười một giờ, không phải mười giờ.',
            facts: [{textVi: '<img src=x>', evidenceEn: '<script>eleven</script>', status: 'CAPTURED'}]}};
}
const summary = {independentFacts: 2, assistedFacts: 0, totalFacts: 8, understoodVi: ['Nghe giờ'],
    practiceVi: ['Nghe địa điểm'], transferVi: 'Còn thiếu ý', nextActionVi: 'Luyện địa điểm'};

test('correct third attempt celebrates captured meanings and provides an escaped review', async () => {
    const h = harness(async () => ({...state(), turns: [doneTurn()]}));
    await h.module.show('session');
    assert.match(h.root.innerHTML, /Đã nắm đủ ý/);
    assert.doesNotMatch(h.root.innerHTML, /Đã đủ ba lượt|<img|<script>/);
    assert.match(h.root.innerHTML, /&lt;script&gt;eleven/);
    assert.match(h.root.innerHTML, /data-action="replay-1"/);
});

test('summary reviews all tasks and replays an earlier task rather than the current one', async () => {
    const calls = [];
    const completed = {...state(), turnNumber: 4, completed: true, summary,
        turns: [doneTurn(), doneTurn(), doneTurn(), doneTurn()]};
    const h = harness(async (url, options) => {
        calls.push({url, options}); return {...completed, ...(options ? {audioUrl: '/first.wav'} : {})};
    });
    await h.module.show('session');
    assert.equal((h.root.innerHTML.match(/data-action="replay-/g) || []).length, 4);
    await h.node('replay-1').listeners.click();
    assert.equal(calls[1].options.body.turnNumber, 1);
    assert.equal(h.played(), 1);
});

test('targeted practice creates a new session with the owned source id', async () => {
    let input;
    const completed = {...state(), scenario: 'cafe', completed: true, summary, turns: [doneTurn()]};
    const h = harness(async (url, options) => {
        if (options) { input = {url, body: options.body}; return {...state(), id: 'followup', turnNumber: 0, turns: []}; }
        return completed;
    });
    await h.module.show('session');
    await h.node('targeted').listeners.click();
    assert.equal(input.url, '/api/v1/conversation/sessions');
    assert.equal(input.body.sourceSessionId, 'session');
    assert.equal(input.body.scenario, 'cafe');
    assert.equal(input.body.difficulty, 'A1');
});

test('draft survives reload and is isolated by task number', async () => {
    const saved = storage();
    const first = harness(async () => state(), {setTimeout}, saved);
    await first.module.show('session');
    first.node('textarea').value = 'Tôi nghe thấy mười một giờ';
    first.node('textarea').listeners.input();
    const reloaded = harness(async () => state(), {setTimeout}, saved);
    await reloaded.module.show('session');
    assert.match(reloaded.root.innerHTML, /Tôi nghe thấy mười một giờ/);
    const advanced = harness(async () => ({...state(), turnNumber: 2}), {setTimeout}, saved);
    await advanced.module.show('session');
    assert.doesNotMatch(advanced.root.innerHTML, /Tôi nghe thấy mười một giờ/);
    reloaded.module.clearDrafts();
    assert.equal(Object.keys(saved).filter(key => key.startsWith('dictalab:conversation-draft:')).length, 0);
});

test('expired drafts are not restored', async () => {
    const saved = storage();
    saved.setItem('dictalab:conversation-draft:1:session:1', JSON.stringify({value: 'expired private answer', expires: 1}));
    const h = harness(async () => state(), {setTimeout}, saved);
    await h.module.show('session');
    assert.doesNotMatch(h.root.innerHTML, /expired private answer/);
});

test('successful answer clears the persisted draft', async () => {
    const saved = storage();
    const h = harness(async (_url, options) => options ? state('quán', {feedbackVi: 'Đã chấm'}) : state(), {setTimeout}, saved);
    await h.module.show('session');
    h.node('textarea').value = 'quán'; h.node('textarea').listeners.input();
    assert.ok(saved.getItem('dictalab:conversation-draft:1:session:1'));
    h.node('#conversation-answer-form').listeners.submit({preventDefault() {}});
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(saved.getItem('dictalab:conversation-draft:1:session:1'), null);
});

test('learner can explicitly end after three tasks without inventing a transfer result', async () => {
    const calls = [];
    const three = {...state(), turnNumber: 3, turns: [doneTurn(), doneTurn(), doneTurn()]};
    const h = harness(async (_url, options) => {
        if (options) { calls.push(options.body); return {...three, completed: true, transferSkipped: true, summary}; }
        return three;
    });
    await h.module.show('session');
    await h.node('finish').listeners.click();
    assert.equal(calls[0].kind, 'finish');
    assert.equal(calls[0].turnNumber, 3);
    assert.match(h.root.innerHTML, /Đã lưu kết quả ba câu/);
    assert.doesNotMatch(h.root.innerHTML, /Hoàn thành phiên nghe/);
    assert.doesNotMatch(h.root.innerHTML, /✓ Câu mới/);
});
