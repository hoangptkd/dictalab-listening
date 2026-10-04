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
function harness(api) {
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
    vm.runInNewContext(source, {window, document: {querySelector: () => root}, crypto: {randomUUID: () => 'new-request'}});
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
