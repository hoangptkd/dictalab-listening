const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(process.env.DICTALAB_APP_JS || path.resolve(__dirname, '../../main/resources/static/js/app.js'), 'utf8');

// Deterministic event/timer/API harness. Native IME, layout and audio still need browser tests.
function harness() {
    let now = 1000, nextTimer = 0, active = 0, completed = 0;
    const timers = new Map(), requests = [], statuses = [], inputs = new Map();
    const state = {currentUser: {id: 1}, session: {sessionId: 'session-a'},
        sentence: {sentenceId: 10, hasListened: true, canComplete: false, replayCount: 1, hintCount: 0, revealCount: 0,
            words: [0, 1, 2].map(index => ({index, length: 3, userInput: '', correct: false}))},
        wordDrafts: new Map(), wordAttemptStartedAt: new Map(), checkingWords: new Set(),
        inputInteractionRevision: 0, practiceMutationQueue: Promise.resolve()};
    const input = index => {
        if (!inputs.has(index)) inputs.set(index, {dataset: {wordIndex: String(index)}, value: '',
            selectionStart: 0, selectionEnd: 0, readOnly: false, disabled: false,
            closest(selector) {return selector === '.word-slot' ? {classList: {remove() {}}} : this;},
            setAttribute() {}, dispatchEvent(event) {context.onWordInput({...event, target: this});}});
        return inputs.get(index);
    };
    const context = vm.createContext({state, Map, Set, DOMException, document: {body: {dataset: {route: 'practice'}}}, Date: {now: () => now},
        window: {setTimeout(fn, delay) {const id = ++nextTimer; timers.set(id, {fn, at: now + delay}); return id;},
            clearTimeout: id => timers.delete(id)},
        elements: {wordSlots: {querySelectorAll: () => [...inputs.values()]}},
        Event: class {constructor(type, options) {this.type = type; Object.assign(this, options);}},
        refreshDictationCopy() {}, roomQueueDraft() {}, updatePracticeControls() {}, stopRecordedAudio() {},
        practicePath: suffix => `/api/v1/practice-sessions/${state.session.sessionId}/sentences/${state.sentence.sentenceId}${suffix}`,
        api: (url, options) => new Promise((resolve, reject) => requests.push({url, options, resolve, reject})),
        sessionStorage: {getItem: () => null, setItem() {}}, crypto: {randomUUID: () => `key-${requests.length}-${now}`},
        captureWordFocus: () => active === null ? null : {index: active, selectionStart: input(active).selectionStart, selectionEnd: input(active).selectionEnd},
        restoreWordFocus: focus => {
            active = focus.index;
            input(active).selectionStart = focus.selectionStart;
            input(active).selectionEnd = focus.selectionEnd;
        },
        focusNextWord: index => {active = Math.min(2, index + 1);}, focusWord: index => {active = index;},
        setPracticeStatus: (message, error) => statuses.push({message, error}),
        completeSentence: async () => {completed++;},
        renderPractice() {
            inputs.clear();
            for (const word of state.sentence.words) {
                input(word.index).value = state.wordDrafts.get(word.index) ?? word.userInput;
                input(word.index).readOnly = word.correct;
            }
        }
    });
    const handlers = source.slice(source.indexOf('    const wordCheckTimers = new Map();'), source.indexOf('    async function useHint('));
    const journal = source.slice(source.indexOf('    const pendingPracticeSubmissions = new Map();'), source.indexOf('    function escapeHtml('));
    vm.runInContext(journal + '\n' + handlers, context);
    const flush = async () => {for (let n = 0; n < 16; n++) await Promise.resolve();};
    return {state, context, requests, timers, input, statuses, flush,
        active: () => active, completed: () => completed,
        type(index, value, extra = {}) {
            active = index; input(index).value = value;
            input(index).selectionStart = input(index).selectionEnd = value.length;
            context.onWordInput({target: input(index), ...extra});
        },
        key(index, key, extra = {}) {
            const event = {target: input(index), key, prevented: false, preventDefault() {this.prevented = true;}, ...extra};
            const pending = context.onWordKeydown(event); return {event, pending};
        },
        async advance(ms) {
            const until = now + ms;
            while ([...timers.values()].some(timer => timer.at <= until)) {
                const [id, timer] = [...timers].filter(([, item]) => item.at <= until).sort((a, b) => a[1].at - b[1].at)[0];
                now = timer.at; timers.delete(id); timer.fn(); await flush();
            }
            now = until; await flush();
        },
        async reply(requestIndex, correct = false, compact = true) {
            const request = requests[requestIndex], wordIndex = Number(request.url.match(/words\/(\d+)/)[1]);
            const sentence = JSON.parse(JSON.stringify(state.sentence));
            Object.assign(sentence.words[wordIndex], {userInput: request.options.body.answer, correct});
            sentence.canComplete = sentence.words.every(word => word.correct);
            request.resolve(compact ? {sentenceId: sentence.sentenceId, word: sentence.words[wordIndex],
                resolvedWords: sentence.words.filter(word => word.correct || word.revealed).length,
                replayCount: sentence.replayCount, hintCount: sentence.hintCount, revealCount: sentence.revealCount,
                hasListened: true, requiresReplayAfterReveal: false, canComplete: sentence.canComplete, completed: false}
                : sentence); await flush();
        }
    };
}

test('compact checks merge only the graded word and retain static sentence metadata', async () => {
    const h = harness(); Object.assign(h.state.sentence, {audioUrl: '/test.mp3', position: 2, totalSentences: 5});
    const untouched = h.state.sentence.words[2];
    h.type(0, 'cat'); await h.advance(240); h.type(1, 'do'); await h.reply(0, true);
    assert.ok(h.requests[0].url.endsWith('/checks?compact=true'));
    assert.equal(h.state.sentence.words[2], untouched);
    assert.equal(h.state.sentence.audioUrl, '/test.mp3');
    assert.equal(h.state.sentence.position, 2); assert.equal(h.state.sentence.totalSentences, 5);
    assert.equal(h.state.sentence.words[0].correct, true); assert.equal(h.input(1).value, 'do');
    assert.equal(h.requests.length, 1);
});

test('a legacy full sentence response remains compatible during rollout', async () => {
    const h = harness(); h.type(0, 'cat'); await h.advance(240); await h.reply(0, true, false);
    assert.equal(h.state.sentence.words[0].correct, true); assert.equal(h.requests.length, 1);
});

test('a compact result from another word or sentence cannot corrupt cached state', async () => {
    for (const result of [{sentenceId: 99, word: {index: 0}}, {sentenceId: 10, word: {index: 2}}]) {
        const h = harness(); h.type(0, 'cat'); await h.advance(240);
        h.requests[0].resolve(result); await h.flush();
        assert.equal(h.state.sentence.words[0].correct, false);
        assert.equal(h.state.wordDrafts.get(0), 'cat'); assert.equal(h.statuses.at(-1).error, true);
    }
});

test('a compact progress mismatch reloads server state without repeating the mutation', async () => {
    const h = harness(); h.type(0, 'cat'); await h.advance(240);
    const current = {...h.state.sentence, canComplete: true,
        words: h.state.sentence.words.map(word => ({...word, correct: true, userInput: 'cat'}))};
    h.requests[0].resolve({sentenceId: 10, word: current.words[0], resolvedWords: 3, canComplete: true,
        hintCount: 0, revealCount: 0}); await h.flush();
    assert.equal(h.requests.length, 2); assert.equal(h.requests[1].options, undefined);
    assert.equal(h.requests[1].url, '/api/v1/practice-sessions/session-a');
    h.requests[1].resolve({sentence: current}); await h.flush();
    assert.equal(h.state.sentence.words.every(word => word.correct), true);
    assert.equal(h.requests.length, 2);
});

test('a resync response after navigation cannot restore the old cached sentence', async () => {
    const h = harness(); h.type(0, 'cat'); await h.advance(240);
    h.requests[0].resolve({sentenceId: 10, word: {index: 0, correct: true}, resolvedWords: 3}); await h.flush();
    assert.equal(h.requests.length, 2);
    h.context.resetPracticeInput(); h.state.sentence = {...h.state.sentence, sentenceId: 11};
    h.requests[1].resolve({sentence: {sentenceId: 10}}); await h.flush();
    assert.equal(h.state.sentence.sentenceId, 11); assert.equal(h.state.sentence.words[0].correct, false);
});

for (const interval of [0, 10, 40, 120]) test(`INPUT-01: burst typing at ${interval}ms sends only the final answer`, async () => {
    const h = harness();
    for (const text of ['c', 'ca', 'cat']) {h.type(0, text); await h.advance(interval);}
    await h.advance(240);
    assert.equal(h.requests.length, 1);
    assert.equal(h.requests[0].options.body.answer, 'cat');
});

test('INPUT-02: empty input cancels automatic checking and preserves intentional deletion', async () => {
    const h = harness(); h.type(0, 'cat'); h.type(0, ''); await h.advance(500);
    assert.equal(h.requests.length, 0); assert.equal(h.state.wordDrafts.get(0), '');
});

test('INPUT-03: Space advances immediately while the request is pending', async () => {
    const h = harness(); h.type(0, 'cat'); h.key(0, ' '); await h.flush();
    assert.equal(h.active(), 1); assert.equal(h.requests.length, 1);
    h.type(1, 'do'); await h.reply(0, true);
    assert.equal(h.active(), 1); assert.equal(h.input(1).value, 'do');
});

test('INPUT-04: a late incorrect response preserves the new draft and focus', async () => {
    const h = harness(); h.type(0, 'cot'); await h.advance(240);
    h.type(0, 'ca'); await h.reply(0, false);
    assert.equal(h.input(0).value, 'ca'); assert.equal(h.active(), 0);
});

test('INPUT-05: duplicate Enter while pending submits one check and completes once', async () => {
    const h = harness(); h.state.sentence.words.slice(1).forEach(word => {word.correct = true;});
    h.type(0, 'cat'); h.key(0, 'Enter'); h.key(0, 'Enter'); await h.flush();
    assert.equal(h.requests.length, 1); await h.reply(0, true);
    assert.equal(h.completed(), 1);
});

test('INPUT-06: response from the previous sentence cannot replace the current sentence', async () => {
    const h = harness(); h.type(0, 'cat'); await h.advance(240);
    const old = JSON.parse(JSON.stringify(h.state.sentence)); h.state.sentence = {...old, sentenceId: 11};
    h.requests[0].resolve(old); await h.flush(); assert.equal(h.state.sentence.sentenceId, 11);
});

test('INPUT-07: network failure preserves the draft and unlocks retry', async () => {
    const h = harness(); h.type(0, 'cat'); await h.advance(240);
    h.requests[0].reject(new Error('Simulated connection loss')); await h.flush();
    assert.equal(h.state.wordDrafts.get(0), 'cat'); assert.equal(h.state.checkingWords.size, 0);
    h.key(0, 'Enter'); await h.flush(); assert.equal(h.requests.length, 2);
    assert.equal(h.requests[1].options.headers['Idempotency-Key'], h.requests[0].options.headers['Idempotency-Key']);
});

test('INPUT-08: paste distributes words and removes trailing punctuation', () => {
    const h = harness(); [0, 1, 2].forEach(h.input);
    let prevented = false;
    h.context.onSentencePaste({target: h.input(0), clipboardData: {getData: () => 'cat, dog! pig.'}, preventDefault() {prevented = true;}});
    assert.equal(prevented, true);
    assert.deepEqual([0, 1, 2].map(n => h.input(n).value), ['cat', 'dog', 'pig']);
});

test('INPUT-09: Enter during IME composition is left to the input method', async () => {
    const h = harness(); h.input(0).value = 'cat';
    const {event, pending} = h.key(0, 'Enter', {isComposing: true}); await pending;
    assert.equal(event.prevented, false); assert.equal(h.requests.length, 0);
});

// These are acceptance assertions, not TODOs: a red result is a reproducible defect.
test('INPUT-10: response from the previous session cannot update the new session', async () => {
    const h = harness(); h.type(0, 'cat'); await h.advance(240);
    const old = JSON.parse(JSON.stringify(h.state.sentence));
    h.state.session = {sessionId: 'session-b'};
    h.requests[0].resolve({...old, words: old.words.map(word => ({...word, userInput: 'old'}))});
    await h.flush(); assert.equal(h.state.sentence.words[0].userInput, '');
});

test('INPUT-11: response arriving after logout cannot restore private lesson state', async () => {
    const h = harness(); h.type(0, 'cat'); await h.advance(240);
    const old = JSON.parse(JSON.stringify(h.state.sentence));
    h.state.currentUser = null; h.state.session = null; h.state.sentence = null;
    h.requests[0].resolve(old); await h.flush();
    assert.equal(h.state.sentence, null); assert.equal(h.state.checkingWords.size, 0);
});

test('INPUT-12: blur of a correct, unchanged or blank answer does not create another attempt', async () => {
    for (const fixture of [{correct: true, value: 'cat'}, {correct: false, value: 'cat'}, {correct: false, value: '   '}]) {
        const h = harness(); Object.assign(h.state.sentence.words[0], {correct: fixture.correct, userInput: fixture.value});
        h.input(0).value = fixture.value; h.context.onWordBlur({target: h.input(0)}); await h.flush();
        assert.equal(h.requests.length, 0);
    }
});

test('INPUT-13: Enter on an empty answer cannot submit or complete the sentence', async () => {
    const h = harness(); const {pending} = h.key(0, 'Enter'); await pending;
    assert.equal(h.requests.length, 0); assert.equal(h.completed(), 0);
});

test('INPUT-14: paste keeps readonly words aligned and leaves their saved answer intact', () => {
    const h = harness(); [0, 1, 2].forEach(h.input); h.input(0).readOnly = true; h.input(0).value = 'cat';
    h.context.onSentencePaste({target: h.input(0), clipboardData: {getData: () => 'cat dog pig'}, preventDefault() {}});
    assert.deepEqual([0, 1, 2].map(n => h.input(n).value), ['cat', 'dog', 'pig']);
});

test('INPUT-15: automatic length checking counts Unicode code points rather than UTF-16 units', async () => {
    const h = harness(); h.type(0, 'a😀b'); await h.advance(240);
    assert.equal(h.requests.length, 1); assert.equal(h.requests[0].options.body.answer, 'a😀b');
});

test('RACE-06: a timer from a previous session must not submit into another session', async () => {
    const h = harness(); h.type(0, 'cat'); h.state.session = {sessionId: 'session-b'};
    h.state.wordDrafts.clear(); await h.advance(300);
    assert.equal(h.requests.length, 0, 'old timer submitted into the new session');
});

test('RACE-07: Enter while a check is pending must clear completion intent after a wrong result', async () => {
    const h = harness(); h.state.sentence.words.slice(1).forEach(word => {word.correct = true;});
    h.type(0, 'cot'); await h.advance(240); h.key(0, 'Enter'); await h.flush();
    await h.reply(0, false); assert.equal(h.completed(), 0);
    h.type(0, 'cat'); await h.advance(240); await h.reply(1, true);
    assert.equal(h.completed(), 0, 'later typing completed the sentence without a new Enter');
});

test('RACE-01: corrected full word during an in-flight check must eventually be checked', async () => {
    const h = harness(); h.type(0, 'cot'); await h.advance(240);
    h.type(0, 'cat'); await h.advance(600); await h.reply(0, false); await h.advance(500);
    assert.equal(h.requests.at(-1).options.body.answer, 'cat', 'latest complete answer was never submitted');
});

test('RACE-02: blur plus pending debounce must not count the same answer twice', async () => {
    const h = harness(); h.type(0, 'cot'); h.context.onWordBlur({target: h.input(0)}); await h.flush();
    await h.advance(100); await h.reply(0, false); await h.advance(200);
    assert.equal(h.requests.length, 1, 'blur did not cancel the pending auto-check');
});

test('RACE-03: a timer belonging to an old sentence must not submit against the new sentence', async () => {
    const h = harness(); h.type(0, 'cat');
    h.state.sentence = {...h.state.sentence, sentenceId: 11}; h.state.wordDrafts.clear();
    await h.advance(300);
    assert.equal(h.requests.length, 0, 'old input timer submitted into the new sentence');
});

test('RACE-04: composing text must not be graded before composition finishes', async () => {
    const h = harness(); h.type(0, 'cat', {isComposing: true}); await h.advance(300);
    assert.equal(h.requests.length, 0, 'intermediate composition was graded');
});

test('RACE-05: Space during IME composition must not change the active word', async () => {
    const h = harness(); h.input(0).value = 'cat';
    const {event} = h.key(0, ' ', {isComposing: true}); await h.flush();
    assert.equal(event.prevented, false, 'composition Space was intercepted');
    assert.equal(h.active(), 0); assert.equal(h.requests.length, 0);
});

test('composition lifecycle suppresses blur and keys, then grades the finished draft once', async () => {
    const h = harness();
    h.context.onWordCompositionStart({target: h.input(0)});
    h.type(0, 'cat'); h.context.onWordBlur({target: h.input(0)});
    const {event} = h.key(0, 'Enter');
    await h.advance(500);
    assert.equal(event.prevented, false); assert.equal(h.requests.length, 0);
    h.context.onWordCompositionEnd({target: h.input(0)});
    h.type(0, 'cat'); await h.advance(300);
    assert.equal(h.requests.length, 1);
});

test('incorrect response preserves the selected substring', async () => {
    const h = harness(); h.type(0, 'cot'); await h.advance(240);
    h.input(0).selectionStart = 1; h.input(0).selectionEnd = 2;
    await h.reply(0, false);
    assert.equal(h.input(0).selectionStart, 1); assert.equal(h.input(0).selectionEnd, 2);
});

test('leaving practice cancels debounce and ignores a response even after reopening the same sentence', async () => {
    const h = harness(); h.type(0, 'cot'); await h.advance(240);
    h.type(1, 'dog'); h.context.resetPracticeInput(); await h.advance(300);
    assert.equal(h.requests.length, 1);
    await h.reply(0, true);
    assert.equal(h.state.sentence.words[0].correct, false);
    assert.equal(h.statuses.length, 0);
});

test('checks still queued when leaving practice are never sent', async () => {
    const h = harness(); h.type(0, 'cot'); await h.advance(240);
    h.type(1, 'dog'); await h.advance(240);
    h.context.resetPracticeInput(); await h.reply(0, false);
    assert.equal(h.requests.length, 1);
});

test('an edited unsent answer is skipped and only its latest submitted draft is checked', async () => {
    const h = harness(); h.type(0, 'cat'); await h.advance(240);
    h.type(1, 'cot'); await h.advance(240);
    h.type(1, 'dog'); await h.advance(240);
    await h.reply(0, true);
    assert.equal(h.requests.length, 2);
    assert.equal(h.requests[1].options.body.answer, 'dog');
    assert.equal(h.statuses.some(status => status.error), false);
    const pending = vm.runInContext('[...pendingPracticeSubmissions.values()]', h.context);
    assert.equal(pending.length, 1); assert.equal(pending[0].body.answer, 'dog');
});

for (const draft of ['', 'do']) test(`an unsent answer changed to ${JSON.stringify(draft)} waits for another submission`, async () => {
    const h = harness(); h.type(0, 'cat'); await h.advance(240);
    h.type(1, 'dog'); await h.advance(240); h.type(1, draft);
    await h.reply(0, true); await h.advance(500);
    assert.equal(h.requests.length, 1);
    assert.equal(h.input(1).value, draft);
    assert.equal(h.state.checkingWords.size, 0);
    assert.equal(h.statuses.some(status => status.error), false);
    assert.equal(vm.runInContext('pendingPracticeSubmissions.size', h.context), 0);
});

test('composition invalidates an unsent check until composition ends', async () => {
    const h = harness(); h.type(0, 'cat'); await h.advance(240);
    h.type(1, 'dog'); await h.advance(240);
    h.context.onWordCompositionStart({target: h.input(1)});
    await h.reply(0, true);
    assert.equal(h.requests.length, 1);
    h.context.onWordCompositionEnd({target: h.input(1)}); await h.advance(240);
    assert.equal(h.requests.length, 2); assert.equal(h.requests[1].options.body.answer, 'dog');
});

test('skipping an edited retry preserves its uncertain original receipt for an explicit retry', async () => {
    const h = harness(); h.type(1, 'cot'); await h.advance(240);
    h.requests[0].reject(new TypeError('Response lost')); await h.flush();
    h.type(0, 'cat'); await h.advance(240);
    h.context.onWordBlur({target: h.input(1)}); await h.flush();
    h.type(1, ''); await h.reply(1, true);
    assert.equal(h.requests.length, 2);
    const pending = vm.runInContext('[...pendingPracticeSubmissions.values()]', h.context);
    assert.equal(pending.length, 1);
    assert.equal(pending[0].headers['Idempotency-Key'], h.requests[0].options.headers['Idempotency-Key']);
    h.type(1, 'cot'); h.key(1, 'Enter'); await h.flush();
    assert.equal(h.requests.length, 3);
    assert.equal(h.requests[2].options.headers['Idempotency-Key'], h.requests[0].options.headers['Idempotency-Key']);
});

test('an old response cannot unlock a newer check at the same word index', async () => {
    const h = harness(); h.type(0, 'cot'); await h.advance(240);
    h.context.resetPracticeInput(); h.state.session = {sessionId: 'session-b'};
    h.state.practiceMutationQueue = Promise.resolve();
    h.type(0, 'dog'); await h.advance(240); await h.reply(0, false);
    assert.equal(h.state.checkingWords.has(0), true);
    h.key(0, 'Enter'); await h.flush(); assert.equal(h.requests.length, 2);
    await h.reply(1, true); assert.equal(h.state.checkingWords.has(0), false);
});

test('an explicit blur checks a shorter correction after the pending check finishes', async () => {
    const h = harness(); h.type(0, 'cot'); await h.advance(240);
    h.type(0, 'ca'); h.context.onWordBlur({target: h.input(0)});
    await h.reply(0, false);
    assert.equal(h.requests.length, 2); assert.equal(h.requests[1].options.body.answer, 'ca');
    assert.notEqual(h.requests[0].options.headers['Idempotency-Key'], h.requests[1].options.headers['Idempotency-Key']);
});

test('a later deletion cancels the correction queued behind an in-flight check', async () => {
    const h = harness(); h.type(0, 'cot'); await h.advance(240);
    h.type(0, 'cat'); await h.advance(300); h.type(0, '');
    await h.reply(0, false); await h.advance(500);
    assert.equal(h.requests.length, 1); assert.equal(h.input(0).value, '');
});

test('network failure keeps the latest draft, clears completion intent and requires an explicit retry', async () => {
    const h = harness(); h.type(0, 'cot'); await h.advance(240);
    h.key(0, 'Enter'); h.type(0, 'cat'); await h.advance(300);
    h.requests[0].reject(new TypeError('Failed to fetch')); await h.flush(); await h.advance(1000);
    assert.equal(h.requests.length, 1); assert.equal(h.state.wordDrafts.get(0), 'cat');
    assert.equal(h.state.completeAfterPendingCheck, false);
    assert.match(h.statuses.at(-1).message, /Không thể kết nối/);
    h.context.onWordBlur({target: h.input(0)}); await h.flush();
    assert.equal(h.requests.length, 2);
});

test('editing after Enter invalidates the previous completion request', async () => {
    const h = harness(); h.state.sentence.words.slice(1).forEach(word => {word.correct = true;});
    h.type(0, 'cat'); h.key(0, 'Enter'); await h.flush();
    h.type(0, 'cot'); await h.reply(0, true);
    assert.equal(h.completed(), 0);
});

test('restoring a selection does not depend on animation frames in a background tab', () => {
    let selection, focused = 0;
    const input = {value: 'word', disabled: false, focus() {focused++;},
        setSelectionRange(...args) {selection = args;}};
    const restore = source.match(/    function restoreWordFocus\([^]*?\n    }/)[0];
    const fn = vm.runInNewContext(`${restore}\nrestoreWordFocus`, {
        elements: {wordSlots: {querySelector: () => input}}, updatePracticeControls() {},
        window: {requestAnimationFrame() {throw new Error('background frame requested');}}
    });
    fn({index: 0, selectionStart: 1, selectionEnd: 3, selectionDirection: 'backward'});
    assert.equal(focused, 1); assert.deepEqual(selection, [1, 3, 'backward']);
    input.disabled = true; fn({index: 0, selectionStart: 0, selectionEnd: 1});
    assert.equal(focused, 1);
});

test('an unsent cancelled action is removed, but an uncertain sent action survives for retry', async () => {
    const h = harness(); h.type(0, 'cot'); await h.advance(240);
    h.type(1, 'dog'); await h.advance(240);
    h.context.resetPracticeInput(); h.requests[0].reject(new TypeError('Response lost')); await h.flush();
    const pending = vm.runInContext('[...pendingPracticeSubmissions.values()]', h.context);
    assert.equal(pending.length, 1); assert.equal(pending[0].body.answer, 'cot');
});

for (const action of ['hint', 'classification']) test(`a late ${action} response cannot replace another session`, async () => {
    const h = harness();
    vm.runInContext(source.slice(source.indexOf('    async function useHint('),
        source.indexOf('    async function playSentence(')), h.context);
    h.context.document.activeElement = null;
    const old = JSON.parse(JSON.stringify(h.state.sentence));
    const pending = action === 'hint' ? h.context.useHint('FIRST_LETTER')
        : h.context.onClassifyWord({target: {closest: () => ({dataset: {wordIndex: '0', errorType: 'UNKNOWN'}})}});
    await h.flush(); h.context.resetPracticeInput(); h.state.session = {sessionId: 'session-b'};
    h.requests[0].resolve(old); await pending;
    assert.notEqual(h.state.sentence, old); assert.equal(h.statuses.length, 0);
});

test('a late complete response cannot advance a new session or unlock its own pending complete', async () => {
    const h = harness();
    h.state.sentence.canComplete = true;
    h.context.elements.nextSentence = {};
    vm.runInContext(source.slice(source.indexOf('    async function completeSentence('),
        source.indexOf('    function renderResult(')), h.context);
    const pending = h.context.completeSentence(); await h.flush();
    h.context.resetPracticeInput(); h.state.session = {sessionId: 'session-b'};
    h.state.completingSentence = true;
    h.requests[0].resolve({lessonCompleted: false, nextSentence: {...h.state.sentence, sentenceId: 11}});
    await pending;
    assert.equal(h.state.sentence.sentenceId, 10); assert.equal(h.state.completingSentence, true);
});

test('focusout caused by hiding the practice view cannot resubmit an in-flight answer', async () => {
    const h = harness(); h.type(0, 'cot'); await h.advance(240);
    h.context.resetPracticeInput(); h.context.document.body.dataset.route = 'catalog';
    h.context.onWordBlur({target: h.input(0)}); await h.flush(); await h.reply(0, false);
    assert.equal(h.requests.length, 1); assert.equal(h.statuses.length, 0);
});

test('deliberately deleting and retyping a wrong answer is a new attempt with a new key', async () => {
    const h = harness(); h.type(0, 'cot'); await h.advance(240); await h.reply(0, false);
    h.type(0, ''); h.type(0, 'cot'); await h.advance(240);
    assert.equal(h.requests.length, 2);
    assert.notEqual(h.requests[0].options.headers['Idempotency-Key'], h.requests[1].options.headers['Idempotency-Key']);
});

test('an old shared-audio failure cannot clear the new sentence audio-ready state', async () => {
    const h = harness();
    Object.assign(h.state, {roomSharedSessionId: 'session-a', roomStudy: {}, audioContext: {state: 'running'}});
    h.state.sentence.audioUrl = '/audio/fixture.mp3';
    h.context.elements.speechSupportNote = {};
    h.context.elements.listeningConsole = {classList: {add() {}, remove() {}}};
    h.context.roomSendWs = () => {};
    vm.runInContext(source.match(/^    async function playSentence\([^]*?^    }/m)[0], h.context);
    const pending = h.context.playSentence({startsAt: 'fixture', localStartsAt: 1000, position: 1, sequence: 1, speed: 1});
    await h.flush(); h.context.resetPracticeInput(); h.state.roomAudioPreparedKey = 'new-sentence-ready';
    h.requests[0].reject(new TypeError('Audio request lost')); await pending;
    assert.equal(h.state.roomAudioPreparedKey, 'new-sentence-ready');
    assert.equal(h.state.speaking, false); assert.equal(h.statuses.length, 0);
});
