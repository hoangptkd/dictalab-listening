const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../../scripts/load/shared.js'), 'utf8');
const context = {__ENV: {CAPACITY_PASSWORD: 'fixture-only'}, Rate: class {}, Counter: class {}};
vm.runInNewContext(source.replace(/^import .*;\r?\n/gm, '').replace(/\bexport /g, '') + '\nthis.sameJson = sameJson; this.slowResponseSummary = slowResponseSummary;', context);

test('slow-request diagnostics retain timing and correlation but no payload or credentials', () => {
    const response = {status: 200, timings: {duration: 4123, blocked: 1, connecting: 2, waiting: 4100, receiving: 20},
        headers: {'X-Request-ID': 'capacity-1-2', 'Set-Cookie': 'private-cookie'}, body: 'private-answer', url: 'private-url'};
    const summary = context.slowResponseSummary(response, 'history');
    assert.equal(summary.durationMs, 4123);
    assert.equal(summary.requestId, 'capacity-1-2');
    assert.equal(summary.name, 'history');
    assert.doesNotMatch(JSON.stringify(summary), /private-/);
    assert.equal(context.slowResponseSummary({...response, timings: {duration: 999}}, 'history'), null);
});

test('JSON replay ignores object member order, including nested members', () => {
    assert.equal(context.sameJson({id: 1, turns: [{done: true, attempts: [{id: 'a', grade: 2}]}]},
        {turns: [{attempts: [{grade: 2, id: 'a'}], done: true}], id: 1}), true);
});

test('JSON replay rejects changed values, types, missing members and reordered arrays', () => {
    for (const [a, b] of [[{score: 1}, {score: 2}], [{score: 1}, {score: '1'}],
        [{score: null}, {}], [[], {}], [[1, 2], [2, 1]], [null, {}], [{a: 1}, {b: 1}]]) {
        assert.equal(context.sameJson(a, b), false);
    }
});

test('mixed workload keeps four readers and one stateful writer per five VUs', () => {
    const core = fs.readFileSync(path.resolve(__dirname, '../../../scripts/load/core.js'), 'utf8')
        .replace(/^import .*;\r?\n/gm, '').replace('export default function', 'function workload').replace(/\bexport /g, '');
    for (let vu = 1; vu <= 5; vu++) {
        const calls = [];
        let completed = 0;
        const request = (method, url, body, name, predicate) => {
            calls.push(name);
            const response = {catalog: [{id: 1}], history: {items: []}, dashboard: {},
                start: {sessionId: 's', sentence: {sentenceId: 1, words: [{index: 0, prefilled: false}]}},
                play: {hasListened: true}, check: {words: []}, hint: {requiresReplayAfterReveal: true},
                'replay-start': {hasListened: true}, 'replay-complete': {canComplete: true}, complete: {lessonCompleted: true}}[name];
            assert.equal(predicate(response), true);
            return response;
        };
        const sandbox = {__ENV: {MODE: 'mixed'}, __VU: vu, users: 5, seconds: 10, base: 'http://fixture',
            complete: {add(n) {completed += n;}}, setupAuth() {}, client: () => request, sleep() {}, fail: assert.fail};
        vm.runInNewContext(core + '\nworkload(Array(5).fill({}));', sandbox);
        assert.equal(completed, 1);
        assert.deepEqual(calls, vu === 5 ? ['catalog', 'start', 'play', 'check', 'hint',
            'replay-start', 'replay-complete', 'complete', 'history'] : ['catalog', 'history', 'dashboard']);
    }
});
