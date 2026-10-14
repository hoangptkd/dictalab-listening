const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../../scripts/load/practice-ux.js'), 'utf8')
    .replace(/^import .*;\r?\n/gm, '').replace('export default function', 'function workload').replace(/\bexport /g, '');

function harness(overrides = {}) {
    const metrics = {}, calls = [], sleeps = [], receipts = new Map();
    let setupCount, cleanupCount;
    const sentence = {sentenceId: 10, audioUrl: '/audio/fixture.mp3', audioStartSeconds: 0, audioEndSeconds: 3,
        hasListened: false, canComplete: false, words: [0, 1, 2].map(index => ({index, length: 3,
            userInput: '', correct: false, prefilled: false, revealed: false}))};
    const runtime = {At: new Date().toISOString(), MaxRuntimeSeconds: 3600, Database: 'dictalab_capacity_fixture',
        Accounts: 50, RateLimitEnabled: true, JwtIsolated: true, RedisDedicated: true,
        ProviderMode: 'fake', ArtifactSHA256: 'a'.repeat(64), ...overrides.runtime};
    class Metric {constructor(name) {this.name = name; metrics[name] = [];} add(value) {metrics[this.name].push(value);}}
    const context = {__ENV: {RUNTIME_MANIFEST: 'fixture.json', ...overrides.env}, users: 50,
        base: 'http://127.0.0.1:18282', __VU: 1, __ITER: 0, Date,
        open: () => JSON.stringify(runtime), Counter: Metric, Rate: Metric,
        setupAuth: n => {setupCount = n; return Array.from({length: n}, (_, id) => ({id}));},
        cleanupAuth: tokens => {cleanupCount = tokens.length;},
        sameJson: (a, b) => JSON.stringify(a) === JSON.stringify(b),
        sleep: n => sleeps.push(n), loadAudio: url => calls.push({name: 'audio', url}), segmentSeconds: () => 3,
        fail: message => {throw Error(message);},
        client: () => (method, url, body, name, predicate, key) => {
            calls.push({method, url, body, name, key});
            let result;
            if (key && receipts.has(key)) result = receipts.get(key);
            else {
                if (name === 'catalog') result = [{id: 1, previewAudioUrl: sentence.audioUrl}];
                else if (name === 'start' || name === 'resume' || name === 'typing-recovery') result = {sessionId: 's', sentence};
                else if (name === 'typing-check') {
                    const index = Number(url.match(/words\/(\d+)/)[1]); sentence.words[index].userInput = body.answer; result = sentence;
                } else if (name === 'reveal') {sentence.requiresReplayAfterReveal = true; result = sentence;}
                else if (name === 'complete') result = {lessonCompleted: false, nextSentence: {sentenceId: 11}};
                else if (name === 'history') result = {items: []};
                else {sentence.hasListened = true; if (name === 'replay-end') sentence.canComplete = true; result = sentence;}
                result = JSON.parse(JSON.stringify(result));
                if (key) receipts.set(key, result);
            }
            if (!predicate(result)) throw Error('Invalid simulated API response: ' + name);
            return result;
        }, ...overrides.context};
    vm.runInNewContext(source + '\nthis.api={options,setup,teardown,practiceJourney,workload};', context);
    return {context, api: context.api, calls, sleeps, metrics, counts: () => ({setupCount, cleanupCount})};
}

test('50-VU profile has a ten-minute plateau with a gradual ramp and recovery', () => {
    const h = harness(), stages = h.api.options.scenarios.practice.stages;
    assert.equal(Math.max(...stages.map(stage => stage.target)), 50);
    assert.ok(stages.some(stage => stage.target === 50 && stage.duration === '600s'));
    assert.equal(stages.reduce((sum, stage) => sum + Number(stage.duration.slice(0, -1)), 0), 930);
    assert.equal(stages.at(-1).target, 10);
    h.api.teardown(h.api.setup()); assert.deepEqual(h.counts(), {setupCount: 50, cleanupCount: 50});
});

test('smoke uses one VU with bounded work and graceful stop', () => {
    const h = harness({env: {PROFILE: 'smoke'}, context: {users: 1}});
    assert.equal(h.api.options.scenarios.practice.vus, 1);
    assert.equal(h.api.options.scenarios.practice.duration, '30s');
    assert.equal(h.api.options.scenarios.practice.gracefulStop, '90s');
});

test('load rejects external targets, shared runtimes, missing fields and insufficient budgets', () => {
    for (const overrides of [
        {context: {base: 'https://dictalab.example'}}, {runtime: {Database: 'dictalab'}},
        {runtime: {Accounts: 49}}, {runtime: {Accounts: undefined}}, {runtime: {RedisDedicated: false}},
        {runtime: {JwtIsolated: false}}, {runtime: {RateLimitEnabled: false}}, {runtime: {ProviderMode: 'real'}},
        {runtime: {MaxRuntimeSeconds: 100}}, {env: {RUNTIME_MANIFEST: ''}}, {env: {HOLD_SECONDS: 'Infinity'}},
        {env: {INSECURE_TLS: 'true'}}, {context: {users: 51}}
    ]) assert.throws(() => harness(overrides));
});

test('expired time-left budget blocks setup but cannot break final-summary init', () => {
    const h = harness({runtime: {At: new Date(Date.now() - 3000 * 1000).toISOString()}});
    assert.throws(() => h.api.setup(), /Runtime budget/);
    assert.equal(h.counts().setupCount, undefined);
    assert.equal(h.api.options.scenarios.practice.stages.at(-1).target, 10);
});

test('journey uses real WordSlot fields, quick checks, audio time and stable duplicate keys', () => {
    const h = harness(); h.api.workload([{id: 1}]);
    assert.equal(h.calls.filter(call => call.name === 'typing-check').length, 3);
    assert.equal(h.sleeps.filter(seconds => seconds === 0.04).length, 3);
    assert.equal(h.sleeps.filter(seconds => seconds === 3).length, 2);
    for (const [original, duplicate] of [['typing-check', 'typing-duplicate'], ['complete', 'complete-duplicate']]) {
        const first = h.calls.find(call => call.name === original), repeated = h.calls.find(call => call.name === duplicate);
        assert.ok(first.key); assert.equal(first.key, repeated.key); assert.deepEqual(first.body, repeated.body);
    }
    assert.equal(h.calls.some(call => /conversation|coach|generate|listening-learning/.test(call.url)), false);
    assert.deepEqual(h.metrics.practice_started, [1]); assert.deepEqual(h.metrics.practice_finished, [1]);
    assert.deepEqual(h.metrics.practice_success, [true]); assert.deepEqual(h.metrics.practice_vus_completed, [1]);
    h.context.__ITER = 1; h.api.workload([{id: 1}]);
    assert.deepEqual(h.metrics.practice_vus_completed, [1], 'participation is counted once per VU');
});

test('failed journey is recorded and cannot count as completion or participation', () => {
    const h = harness({context: {client: () => () => {throw Error('Simulated HTTP 503');}}});
    assert.throws(() => h.api.workload([{id: 1}]), /503/);
    assert.deepEqual(h.metrics.practice_started, [1]); assert.deepEqual(h.metrics.practice_finished, [1]);
    assert.deepEqual(h.metrics.practice_success, [false]); assert.deepEqual(h.metrics.practice_sentences_completed, []);
    assert.deepEqual(h.metrics.practice_vus_completed, []);
});
