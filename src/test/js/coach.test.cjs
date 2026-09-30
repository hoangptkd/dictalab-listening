const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/coach.js'), 'utf8');

function harness(api) {
    const elements = new Map();
    const $ = selector => {
        if (!elements.has(selector)) elements.set(selector, {innerHTML: '', textContent: '', hidden: false,
            setAttribute() {}, removeAttribute() {}, addEventListener() {}, scrollIntoView() {}, focus() {}});
        return elements.get(selector);
    };
    const listeners = {};
    const root = {querySelector: $, addEventListener: (name, handler) => { listeners[name] = handler; }};
    const window = {DictaLabListeningBridge: {api, stopRecordedAudio() {}, showToast() {}}};
    vm.runInNewContext(source, {window, document: {querySelector: () => root}});
    return {module: window.DictaLabCoach, $, listeners};
}

const report = (label = 'Real evidence', aiEnabled = false) => ({source: 'RULES', aiEnabled,
    weaknesses: [{pattern: 'KNOWN_MISSED', title: label, insight: 'Short insight', evidenceIds: ['1:0']}],
    progressInsight: {status: 'INSUFFICIENT', text: 'Not enough history'},
    nextActions: [{pattern: 'KNOWN_MISSED', instruction: 'Listen again'}],
    reviewItems: [{id: '1:0', pattern: 'KNOWN_MISSED', heard: '<img onerror=alert(1)>', expected: 'market',
        transcript: 'The market is open.', wordIndex: 1, position: 1, label, explanation: 'Listen in context',
        occurrences: 2, audioUrl: '/test.mp3'}]});

test('coach ignores stale responses after navigation or logout', async () => {
    let resolve;
    const h = harness(() => new Promise(done => { resolve = done; }));
    const pending = h.module.show({sessionId: 'old'});
    h.module.leave();
    resolve(report('Old account'));
    await pending;
    assert.equal(h.$('#coach-insights').innerHTML, '');
});

test('coach escapes all evidence and keeps primary action and technical-free review', async () => {
    const h = harness(async () => report('<script>bad</script>'));
    await h.module.show({sessionId: 'current'});
    assert.doesNotMatch(h.$('#coach-insights').innerHTML, /<script>/);
    assert.match(h.$('#coach-insights').innerHTML, /data-coach-practice/);
    assert.doesNotMatch(h.$('#smart-review-items').innerHTML, /<img/);
    assert.match(h.$('#smart-review-items').innerHTML, /&lt;img/);
    assert.match(h.$('#smart-review-items').innerHTML, /data-coach-save/);
});

test('late AI responses cannot overwrite a newer session', async () => {
    let resolve;
    const h = harness(async (url, options) => {
        if (options?.method === 'POST') return new Promise(done => { resolve = done; });
        return report(url.includes('old') ? 'Old' : 'New', url.includes('old'));
    });
    const pending = h.module.show({sessionId: 'old'});
    await new Promise(done => setImmediate(done));
    await h.module.show({sessionId: 'new'});
    resolve({...report('Old AI'), source: 'AI'});
    await pending;
    assert.match(h.$('#coach-insights').innerHTML, /New/);
    assert.doesNotMatch(h.$('#coach-insights').innerHTML, /Old AI/);
});

test('AI failure preserves the deterministic review', async () => {
    const h = harness(async (_url, options) => {
        if (options?.method === 'POST') throw new Error('Offline');
        return report('Useful fallback', true);
    });
    await h.module.show({sessionId: 'current'});
    assert.match(h.$('#coach-insights').innerHTML, /Useful fallback/);
    assert.match(h.$('#smart-review-items').innerHTML, /market/);
    const retry = {disabled: false, hasAttribute: name => name === 'data-coach-retry'};
    await h.listeners.click({target: {closest: () => retry}});
    assert.equal(retry.disabled, false);
});

test('practice masks the right token and checks the answer without changing session telemetry', async () => {
    const requests = [];
    const h = harness(async (...args) => { requests.push(args); return report(); });
    await h.module.show({sessionId: 'current'});
    const button = {disabled: false, dataset: {coachPractice: 'KNOWN_MISSED'}, hasAttribute: name => name === 'data-coach-practice'};
    await h.listeners.click({target: {closest: () => button}});
    assert.match(h.$('#coach-practice').innerHTML, /The _____ is open\./);
    h.$('#coach-drill-answer').value = 'wrong';
    h.listeners.submit({target: {id: 'coach-drill-form'}, preventDefault() {}});
    assert.equal(h.$('[data-drill-next]').hidden, true);
    h.$('#coach-drill-answer').value = ' MARKET ';
    h.listeners.submit({target: {id: 'coach-drill-form'}, preventDefault() {}});
    assert.equal(h.$('[data-drill-next]').hidden, false);
    assert.equal(requests.length, 1);
});
