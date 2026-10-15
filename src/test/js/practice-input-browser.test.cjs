const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const {spawn} = require('node:child_process');

const source = fs.readFileSync(process.env.DICTALAB_APP_JS || path.resolve(__dirname, '../../main/resources/static/js/app.js'), 'utf8');
const chrome = [process.env.CHROME_BIN,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    '/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser']
    .find(candidate => candidate && fs.existsSync(candidate));

// Real DOM removal emits focusout in Chrome; the VM input harness cannot model that behavior.
test('native practice rendering preserves active inputs and does not grade partial drafts', {
    timeout: 30000, skip: chrome ? false : 'Set CHROME_BIN to run the native browser regression'
}, async () => {
    const functions = ['renderWordSlots', 'captureWordFocus', 'restoreWordFocus', 'focusNextWord', 'focusWord', 'escapeHtml', 'escapeAttribute']
        .map(name => {
            const fn = source.match(new RegExp(`^    function ${name}\\([^]*?^    }`, 'm'));
            assert.ok(fn, name); return fn[0];
        }).join('\n');
    const handlers = source.slice(source.indexOf('    const wordCheckTimers = new Map();'), source.indexOf('    async function useHint('));
    const journal = source.slice(source.indexOf('    const pendingPracticeSubmissions = new Map();'), source.indexOf('    function escapeHtml('));
    const script = `
    const state = {currentUser: {id: 1}, session: {sessionId: 'browser-test'},
        wordDrafts: new Map(), wordAttemptStartedAt: new Map(), checkingWords: new Set(),
        inputInteractionRevision: 0, practiceMutationQueue: Promise.resolve(), activeWordIndex: 0};
    const elements = {wordSlots: document.querySelector('#slots'), wordCompletion: document.querySelector('#completion')};
    const requests = [];
    const api = (url, options) => new Promise(resolve => requests.push({url, options, resolve}));
    const practicePath = suffix => '/practice/' + state.sentence.sentenceId + suffix;
    const refreshDictationCopy = () => {}, roomQueueDraft = () => {}, updatePracticeControls = () => {};
    const stopRecordedAudio = () => {}, setPracticeStatus = () => {}, completeSentence = async () => {};
    const renderPractice = fresh => renderWordSlots(fresh);
    ${functions}
    ${journal}
    ${handlers}
    elements.wordSlots.addEventListener('focusin', onWordFocus);
    elements.wordSlots.addEventListener('input', onWordInput);
    elements.wordSlots.addEventListener('focusout', onWordBlur);
    elements.wordSlots.addEventListener('keydown', onWordKeydown);
    const check = (condition, message) => {if (!condition) throw new Error(message);};
    const flush = async () => {for (let i = 0; i < 30; i++) await Promise.resolve();};
    const input = i => elements.wordSlots.querySelector('input[data-word-index="' + i + '"]');
    function type(i, text) {
        const el = input(i); el.focus(); el.value = text;
        el.dispatchEvent(new InputEvent('input', {bubbles: true, inputType: 'insertText'}));
        return el;
    }
    async function reply(i) {
        const request = requests[i];
        const index = Number(request.url.match(/words\\/(\\d+)/)[1]);
        const sentence = structuredClone(state.sentence);
        Object.assign(sentence.words[index], {correct: true, userInput: request.options.body.answer});
        sentence.canComplete = sentence.words.every(word => word.correct);
        request.resolve(sentence); await flush();
    }
    (async () => {
        await new Promise(resolve => window.addEventListener('load', () => setTimeout(resolve, 0), {once: true}));
        const answers = ['I', 'like', 'hot', 'pizza', 'and', 'salad', 'for', 'dinner'];
        state.sentence = {sentenceId: 10, hasListened: true, canComplete: false,
            words: answers.map((word, index) => ({index, length: word.length, mask: '_'.repeat(word.length), userInput: '', correct: false}))};
        renderWordSlots(true);
        let nativeBlurCount = 0;
        elements.wordSlots.addEventListener('focusout', () => nativeBlurCount++);
        const space = el => el.dispatchEvent(new KeyboardEvent('keydown', {key: ' ', bubbles: true, cancelable: true}));
        space(type(0, answers[0])); await flush();
        for (let i = 1; i < answers.length; i++) {
            const el = type(i, answers[i].slice(0, -1));
            el.setSelectionRange(0, 1, 'backward');
            const blurs = nativeBlurCount;
            await reply(i - 1);
            check(input(i) === el && document.activeElement === el, 'render replaced the active word ' + i);
            check(el.selectionStart === 0 && el.selectionEnd === 1 && el.selectionDirection === 'backward', 'render changed selection');
            check(nativeBlurCount === blurs, 'render emitted native focusout');
            check(requests.length === i, 'render submitted a partial answer');
            space(type(i, answers[i])); await flush();
        }
        await reply(answers.length - 1);
        check(nativeBlurCount > 0, 'browser did not dispatch native focus events');
        check(requests.length === answers.length, 'eight words did not produce exactly eight checks');
        check(requests.every((r, i) => r.options.body.answer === answers[i]), 'a partial word was graded');
        check(state.sentence.canComplete, 'correct answers did not complete the sentence');

        // Hint and reveal updates retain other controls and never grade their programmatic blur.
        resetPracticeInput(); state.wordDrafts.clear();
        state.sentence = {sentenceId: 11, hasListened: true, words: [0, 1].map(index => ({index, length: 3, mask: '___', userInput: '', correct: false}))};
        renderWordSlots(true);
        const first = input(0), second = type(1, 'do');
        first.focus(); await flush(); // A real blur must still grade the shorter draft.
        check(requests.length === 9 && requests[8].options.body.answer === 'do', 'explicit blur was not graded');
        second.focus(); second.setSelectionRange(1, 2);
        state.sentence.words[0].firstLetter = 'c'; renderWordSlots();
        check(input(0) === first && first.value === 'c', 'hint replaced its input or lost its value');
        check(first.closest('.word-slot').querySelector('.slot-hint').textContent.includes('c'), 'hint text missing');
        check(first.labels[0].textContent.includes('c'), 'accessible hint label missing');
        check(input(1) === second && second.selectionStart === 1 && second.selectionEnd === 2, 'hint interrupted another input');

        onWordCompositionStart({target: second}); renderWordSlots();
        check(input(1) === second && document.activeElement === second, 'composition input replaced');
        onWordCompositionEnd({target: second}); await flush();
        check(requests.length === 9, 'composition render graded partial text');
        Object.assign(state.sentence.words[1], {revealed: true, revealedText: 'dog'});
        renderWordSlots(); await flush();
        check(!input(1) && elements.wordSlots.querySelector('.revealed-word').textContent.trim() === 'dog', 'reveal failed');
        check(requests.length === 9, 'reveal submitted an unwanted answer');

        // A same-sized next sentence also replaces the previous control without submitting its hint.
        first.focus(); resetPracticeInput(); state.wordDrafts.clear();
        state.sentence = {sentenceId: 12, words: [0, 1].map(index => ({index, length: 3, mask: '___', userInput: '', correct: false}))};
        renderWordSlots(true); await flush();
        check(input(0) !== first && input(0).value === '', 'new sentence retained an old control');
        check(requests.length === 9, 'sentence change graded an old hint');
        resetPracticeInput();
        document.querySelector('#result').dataset.status = 'pass';
        document.querySelector('#result').textContent = '8 words: 8 checks; native focus, selection, blur, hints, reveal and composition passed';
    })().catch(error => {
        document.querySelector('#result').dataset.status = 'fail';
        document.querySelector('#result').textContent = error.stack;
    });`;
    const server = http.createServer((req, res) => {
        res.setHeader('Content-Type', req.url === '/probe.js' ? 'text/javascript; charset=utf-8' : 'text/html; charset=utf-8');
        res.end(req.url === '/probe.js' ? script
            : '<!doctype html><body data-route="practice"><div id="slots"></div><span id="completion"></span><pre id="result" data-status="pending"></pre><script src="/probe.js"></script>');
    });
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'dictalab-input-browser-'));
    try {
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        const output = await new Promise((resolve, reject) => {
            const child = spawn(chrome, ['--headless=new', '--disable-gpu', '--no-first-run',
                '--no-default-browser-check', '--disable-background-networking', '--user-data-dir=' + profile,
                '--dump-dom', '--virtual-time-budget=5000', 'http://127.0.0.1:' + server.address().port], {windowsHide: true});
            let stdout = '', stderr = '';
            const timeout = setTimeout(() => {child.kill(); reject(new Error('Chrome regression timed out'));}, 20000);
            child.stdout.on('data', data => stdout += data);
            child.stderr.on('data', data => stderr += data);
            child.on('error', error => {clearTimeout(timeout); reject(error);});
            child.on('close', code => {clearTimeout(timeout); code === 0 ? resolve(stdout) : reject(new Error('Chrome exited ' + code + ': ' + stderr.slice(-1000)));});
        });
        assert.match(output, /data-status="pass"/, output.match(/<pre[^]*?<\/pre>/)?.[0] || output);
    } finally {
        await new Promise(resolve => server.close(resolve));
        // This is the unique temporary Chrome profile created above, never a user profile.
        assert.equal(path.dirname(profile), os.tmpdir());
        fs.rmSync(profile, {recursive: true, force: true, maxRetries: 5, retryDelay: 100});
    }
});
