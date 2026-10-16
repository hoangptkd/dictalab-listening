const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/app.js'), 'utf8');

function harness(words = [], inputs = {}) {
    let value = '', writes = 0;
    const field = {
        get value() { return value; },
        set value(text) { value = text; writes++; this.selectionStart = this.selectionEnd = 0; },
        selectionStart: 0, selectionEnd: 0,
        focus() { this.focused = true; },
        setSelectionRange(start, end) { this.selectionStart = start; this.selectionEnd = end; }
    };
    const copied = [];
    const context = vm.createContext({
        state: {sentence: {words}, wordDrafts: new Map()},
        elements: {
            wordSlots: {querySelector(selector) {
                const index = Number(selector.match(/\d+/)[0]);
                const word = words.find(word => word.index === index);
                if (selector.includes('.revealed-word')) {
                    return word?.revealed ? {textContent: `\n ${word.revealedText} \n`} : undefined;
                }
                if (word?.revealed) return undefined;
                return Object.hasOwn(inputs, index) ? inputs[index]
                    : word ? {value: word.userInput || word.firstLetter || ''} : undefined;
            }},
            dictationCopyText: field,
            dictationCopyStatus: {textContent: ''},
            copyDictationSelection: {disabled: false},
            copyDictationAll: {disabled: false}
        },
        navigator: {clipboard: {async writeText(text) { copied.push(text); }}},
        document: {body: {dataset: {route: 'practice'}}},
        window: {isSecureContext: true},
        copyTextWithSelection(text) { copied.push(text); return true; }
    });
    for (const name of ['buildVisibleDictationSentence', 'refreshDictationCopy', 'copyDictationText']) {
        const match = source.match(new RegExp(`    (?:async )?function ${name}\\([^]*?\\n    \\}`));
        assert.ok(match, name);
        vm.runInContext(`${match[0]}\nthis.${name} = ${name}`, context);
    }
    return {context, field, copied, writes: () => writes};
}

test('copies only visible words and opened answers, skipping blank slots and hidden answers', () => {
    const h = harness([
        {index: 0, userInput: 'old', punctuationBefore: '“'},
        {index: 1, userInput: 'stale'},
        {index: 2, userInput: 'werld', revealed: true, revealedText: 'world', punctuationAfter: '!”'},
        {index: 3, revealed: false, revealedText: 'secret', punctuationAfter: '.'}
    ], {0: {value: 'Hello'}, 1: {value: ''}});
    h.context.state.wordDrafts.set(0, 'outdated');
    assert.equal(h.context.buildVisibleDictationSentence(), '“Hello world!”');
    h.context.state.wordDrafts.set(2, 'WORLD');
    assert.equal(h.context.buildVisibleDictationSentence(), '“Hello world!”');
});

test('revealing the whole sentence copies the displayed answer, not old attempts', () => {
    const h = harness([
        {index: 0, userInput: 'olde', revealed: true, revealedText: 'Hello', punctuationBefore: '“'},
        {index: 1, userInput: '', revealed: true, revealedText: 'world', punctuationAfter: '!”'}
    ]);
    h.context.state.wordDrafts.set(0, 'stale');
    assert.equal(h.context.buildVisibleDictationSentence(), '“Hello world!”');
});

test('copies visible hints and prefilled names while omitting unfilled words', () => {
    const h = harness([
        {index: 0, userInput: 'I'},
        {index: 1, userInput: '', revealedText: 'never'},
        {index: 2, userInput: 'liked'},
        {index: 3, prefilled: true, userInput: 'John'},
        {index: 4, firstLetter: 'b', punctuationAfter: '.'}
    ]);
    h.context.state.wordDrafts.set(3, 'stale');
    assert.equal(h.context.buildVisibleDictationSentence(), 'I liked John b.');
});

test('copy reads the rendered controls, not stored attempts or answers absent from the screen', () => {
    const h = harness([
        {index: 0, userInput: 'stored'},
        {index: 1, revealed: true, revealedText: 'stored-answer'}
    ]);
    h.context.elements.wordSlots.querySelector = selector => selector.includes('.revealed-word')
        ? {textContent: '\n displayed \n'} : undefined;
    h.context.state.sentence.words = [h.context.state.sentence.words[1]];
    assert.equal(h.context.buildVisibleDictationSentence(), 'displayed');
    h.context.elements.wordSlots.querySelector = () => undefined;
    h.context.refreshDictationCopy();
    assert.equal(h.field.value, '');
    assert.equal(h.context.elements.copyDictationAll.disabled, true);
});

test('cleared input stays empty in the draft after background rendering', () => {
    const input = {value: '', dataset: {wordIndex: '0'}, closest() { return this; },
        classList: {remove() {}}, setAttribute() {}};
    const h = harness([{index: 0, userInput: 'old', length: 3}], {0: input});
    Object.assign(h.context.state, {inputInteractionRevision: 0, wordAttemptStartedAt: new Map()});
    h.context.window.clearTimeout = () => {};
    h.context.roomQueueDraft = () => {};
    h.context.composingWords = new Set();
    const handler = source.match(/    function onWordInput\([^]*?\n    \}/)[0];
    vm.runInContext(`${handler}\nonWordInput({target: input})`, Object.assign(h.context, {input}));
    assert.equal(h.context.state.wordDrafts.has(0), true);
    assert.equal(h.context.state.wordDrafts.get(0), '');
    const render = source.match(/    function renderWordSlots\([^]*?\n    \}/)[0];
    h.context.elements.wordCompletion = {};
    h.context.escapeHtml = h.context.escapeAttribute = text => text ?? '';
    vm.runInContext(`${render}\nrenderWordSlots()`, h.context);
    assert.match(h.context.elements.wordSlots.innerHTML, /value=""/);
    assert.doesNotMatch(h.context.elements.wordSlots.innerHTML, /value="old"/);
    h.context.elements.wordSlots.querySelector = () => undefined;
    assert.equal(h.context.buildVisibleDictationSentence(), '');
});

test('shared practice also sends the cleared draft rather than the previous answer', async () => {
    for (const name of ['roomQueueDraft', 'roomMarkReady']) {
        for (const liveInput of [undefined, {value: ''}]) {
            const h = harness([{index: 0, userInput: 'old'}], {0: liveInput});
            Object.assign(h.context.state, {session: {sessionId: 1}, room: {roomId: 'test'},
                roomSharedSessionId: 1, roomStudy: {lessonPosition: 1}, roomDraftSequence: 0});
            h.context.state.sentence.position = 1;
            h.context.state.wordDrafts.set(0, '');
            const messages = [];
            h.context.window.clearTimeout = () => {};
            h.context.window.setTimeout = callback => { callback(); return 1; };
            h.context.roomSendWs = message => messages.push(message);
            h.context.api = async () => ({});
            h.context.roomRenderStudy = () => {};
            const handler = source.match(new RegExp(`    (?:async )?function ${name}\\([^]*?\\n    \\}`))[0];
            await vm.runInContext(`${handler}\n${name}()`, h.context);
            assert.equal(messages.length, 1);
            assert.deepEqual(Array.from(messages[0].words), ['']);
        }
    }
});

test('refresh preserves selection on background checks and clears stale sentence content', () => {
    const h = harness([{index: 0, userInput: 'hello'}]);
    h.context.refreshDictationCopy();
    h.field.setSelectionRange(1, 4);
    h.context.refreshDictationCopy();
    assert.equal(h.writes(), 1);
    assert.equal(h.field.selectionStart, 1);
    assert.equal(h.field.selectionEnd, 4);
    h.context.elements.dictationCopyStatus.textContent = 'copied';
    h.context.state.sentence = {words: []};
    h.context.refreshDictationCopy();
    assert.equal(h.field.value, '');
    assert.equal(h.context.elements.copyDictationAll.disabled, true);
    assert.equal(h.context.elements.dictationCopyStatus.textContent, '');
});

test('supports a single word, a phrase, and the full typed sentence', async () => {
    const h = harness();
    h.field.value = 'I like green tea.';
    h.field.setSelectionRange(2, 6);
    await h.context.copyDictationText(true);
    h.field.setSelectionRange(7, 16);
    await h.context.copyDictationText(true);
    await h.context.copyDictationText(false);
    assert.deepEqual(h.copied, ['like', 'green tea', 'I like green tea.']);
    assert.equal(h.context.elements.copyDictationAll.disabled, false);
});

test('empty selection does not copy the entire sentence accidentally', async () => {
    const h = harness();
    h.field.value = 'hello';
    await h.context.copyDictationText(true);
    assert.deepEqual(h.copied, []);
    assert.match(h.context.elements.dictationCopyStatus.textContent, /bôi chọn/);
});

test('HTTP and denied clipboard permission use existing fallback and restore selection', async () => {
    for (const secure of [false, true]) {
        const h = harness();
        h.field.value = 'hello world';
        h.field.setSelectionRange(6, 11);
        h.context.window.isSecureContext = secure;
        h.context.navigator.clipboard.writeText = async () => { throw new Error('denied'); };
        await h.context.copyDictationText(true);
        assert.deepEqual(h.copied, ['world']);
        assert.equal(h.field.focused, true);
        assert.equal(h.field.selectionStart, 6);
        assert.equal(h.field.selectionEnd, 11);
        assert.match(h.context.elements.dictationCopyStatus.textContent, /Đã sao chép/);
    }
});

test('failed fallback selects text for manual copy without losing typed content', async () => {
    const h = harness();
    h.field.value = 'hello world';
    h.context.window.isSecureContext = false;
    h.context.copyTextWithSelection = () => { throw new Error('blocked'); };
    await h.context.copyDictationText(false);
    assert.equal(h.field.value, 'hello world');
    assert.equal(h.field.selectionStart, 0);
    assert.equal(h.field.selectionEnd, 11);
    assert.equal(h.context.elements.copyDictationAll.disabled, false);
    assert.match(h.context.elements.dictationCopyStatus.textContent, /Ctrl\+C/);
});

test('late clipboard completion never focuses, reports success, or falls back on another sentence', async () => {
    for (const change of ['sentence', 'session', 'text', 'route']) {
        for (const reject of [false, true]) {
            const h = harness();
            h.context.state.session = {sessionId: 1};
            h.context.state.sentence.sentenceId = 1;
            h.field.value = 'hello world';
            let finish;
            h.context.navigator.clipboard.writeText = () => new Promise((resolve, fail) => {
                finish = reject ? () => fail(new Error('denied')) : resolve;
            });
            const pending = h.context.copyDictationText(false);
            if (change === 'sentence') h.context.state.sentence.sentenceId = 2;
            if (change === 'session') h.context.state.session.sessionId = 2;
            if (change === 'text') h.field.value = 'new text';
            if (change === 'route') h.context.document.body.dataset.route = 'catalog';
            h.context.elements.dictationCopyStatus.textContent = 'current status';
            finish();
            await pending;
            assert.deepEqual(h.copied, []);
            assert.equal(h.field.focused, undefined);
            assert.equal(h.context.elements.dictationCopyStatus.textContent, 'current status');
            assert.equal(h.context.elements.copyDictationAll.disabled, false);
        }
    }
});
