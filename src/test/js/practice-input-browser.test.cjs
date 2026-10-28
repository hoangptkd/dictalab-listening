const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const {spawn} = require('node:child_process');
const {once} = require('node:events');
const {setTimeout: delay} = require('node:timers/promises');

const source = fs.readFileSync(process.env.DICTALAB_APP_JS || path.resolve(__dirname, '../../main/resources/static/js/app.js'), 'utf8');
const chrome = [process.env.CHROME_BIN,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    '/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser']
    .find(candidate => candidate && fs.existsSync(candidate));

// Real DOM removal emits focusout in Chrome; the VM input harness cannot model that behavior.
test('native practice rendering preserves active inputs and does not grade partial drafts', {
    timeout: 60000, skip: chrome ? false : 'Set CHROME_BIN to run the native browser regression'
}, async t => {
    const functions = ['renderWordSlots', 'captureWordFocus', 'restoreWordFocus', 'focusNextWord', 'focusWord', 'escapeHtml', 'escapeAttribute',
        'practiceElapsedSeconds', 'startTimer', 'resetTimer', 'stopTimer']
        .map(name => {
            const fn = source.match(new RegExp(`^    function ${name}\\([^]*?^    }`, 'm'));
            assert.ok(fn, name); return fn[0];
        }).join('\n');
    const handlers = source.slice(source.indexOf('    const wordChecks = new Map();'), source.indexOf('    async function useHint('));
    const journal = source.slice(source.indexOf('    const pendingPracticeSubmissions = new Map();'), source.indexOf('    function escapeHtml('));
    const lifecycle = source.slice(source.indexOf('    window.addEventListener("pagehide"'), source.indexOf('    async function init('));
    const syncSource = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/practice-sync.js'), 'utf8');
    const script = `
    ${syncSource}
    const state = {currentUser: {id: 1}, session: {sessionId: 'browser-test'},
        wordDrafts: new Map(), wordAttemptStartedAt: new Map(), checkingWords: new Set(),
        inputInteractionRevision: 0, practiceMutationQueue: Promise.resolve(), activeWordIndex: 0};
    const elements = {wordSlots: document.querySelector('#slots'), wordCompletion: document.querySelector('#completion'), sentenceTimer: document.createElement('span')};
    const requests = [];
    const api = (url, options) => new Promise(resolve => requests.push({url, options, resolve}));
    const practicePath = suffix => '/practice/' + state.sentence.sentenceId + suffix;
    const refreshDictationCopy = () => {}, roomQueueDraft = () => {}, updatePracticeControls = () => {};
    const stopRecordedAudio = () => {}, setPracticeStatus = () => {}, completeSentence = async () => {};
    const formatTime = seconds => String(seconds);
    const renderPractice = (fresh, index) => renderWordSlots(fresh, index);
    ${functions}
    ${journal}
    ${handlers}
    ${lifecycle}
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
        request.resolve({sentenceId: sentence.sentenceId, word: sentence.words[index],
            resolvedWords: sentence.words.filter(word => word.correct || word.revealed).length,
            replayCount: 1, hintCount: 0, revealCount: 0, hasListened: true,
            requiresReplayAfterReveal: false, canComplete: sentence.canComplete, completed: false}); await flush();
    }
    async function probeLocalSync(restoring) {
        const sentence = {sentenceId: 14, hasListened: true, replayCount: 1, hintCount: 0, revealCount: 0,
            canComplete: false, requiresReplayAfterReveal: false,
            words: Array.from({length: 14}, (_, index) => ({index, length: 3, mask: '___', correct: false}))};
        const hashes = await Promise.all(sentence.words.map(async (_, index) => {
            const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode('native-salt\\n' + index + '\\ncat'));
            return [...new Uint8Array(bytes)].map(value => value.toString(16).padStart(2, '0')).join('');
        }));
        const send = async (url, options) => {
            if (options.body) throw new TypeError('Intentionally offline');
            return {revision: 0, sentence, grading: {normalization: 'ascii-nfkc-v1', contentVersion: 'native-content', salt: 'native-salt', hashes}};
        };
        const sync = new DictaLabPracticeSync({owner: 1, sessionId: 'browser-sync', send});
        await sync.open(restoring ? null : {sessionId: 'browser-sync', sentence});
        state.session = {sessionId: 'browser-sync'}; state.practiceSync = sync;
        state.sentence = sync.view(); state.wordDrafts.clear(); renderWordSlots(true);
        if (restoring) {
            check(sync.pending === 13, 'real reload lost committed local attempts');
            check(sync.view().words.slice(0, 13).every(word => word.correct), 'real reload lost local correctness');
            check(sync.draft.drafts.some(([i, value]) => i === 13 && value === 'ca'), 'IndexedDB lost the ungraded raw draft');
            check(sync.draft.elapsedSeconds === 24, 'IndexedDB lost elapsed practice time');
            await sync.close(true);
        } else {
            const secondTab = new DictaLabPracticeSync({owner: 1, sessionId: 'browser-sync', send});
            let rejected = false;
            try {await secondTab.open();} catch (_) {rejected = true;}
            check(rejected, 'Web Locks allowed two writers');
            const timings = [];
            for (let index = 0; index < 12; index++) {
                const start = performance.now();
                type(index, 'cat');
                const deadline = performance.now() + 1000;
                while ((!state.sentence.words[index].correct || state.checkingWords.has(index)) && performance.now() < deadline)
                    await new Promise(resolve => setTimeout(resolve, 0));
                check(state.sentence.words[index].correct, 'local feedback waited for the offline server');
                await new Promise(requestAnimationFrame);
                timings.push(performance.now() - start);
            }
            const put = sync.store.put;
            let held = false, release;
            const gate = new Promise(resolve => {release = resolve;});
            sync.store.put = async record => {
                if (!held && record.events.length === 13) {held = true; await gate;}
                return put(record);
            };
            type(12, 'cat');
            while (!held) await new Promise(resolve => setTimeout(resolve, 0));
            type(12, 'cot'); release();
            while (state.checkingWords.has(12)) await new Promise(resolve => setTimeout(resolve, 0));
            check(input(12).value === 'cat' && input(12).readOnly, 'a late draft was painted green instead of the accepted correct answer');
            sync.store.put = put;
            type(13, 'ca');
            await sync.saveDraft({drafts: [...state.wordDrafts], elapsedSeconds: 24});
            sessionStorage.setItem('local-feedback-timings', JSON.stringify(timings.sort((a, b) => a - b)));
        }
        state.practiceSync = null; state.session = {sessionId: 'browser-test'}; state.wordDrafts.clear();
    }
    (async () => {
        await new Promise(resolve => window.addEventListener('load', () => setTimeout(resolve, 0), {once: true}));
        if (sessionStorage.getItem('draft-reload-phase') === 'restore') {
            await probeLocalSync(true);
            state.sentence = {sentenceId: 13, hasListened: true, words: [{index: 0, length: 6, mask: '______', firstLetter: 'm'}]};
            restorePracticeDraft(); renderWordSlots(true); startTimer();
            check(input(0).value === 'ma', 'reload lost the partial draft');
            check(state.timerSeconds >= 12 && state.timerId != null, 'reload did not resume the saved clock');
            check(requests.length === 0, 'reload graded a restored draft');
            resetPracticeInput(); clearPracticeDrafts(); sessionStorage.removeItem('draft-reload-phase');
            document.querySelector('#result').dataset.status = 'pass';
            const timings = JSON.parse(sessionStorage.getItem('local-feedback-timings'));
            document.querySelector('#result').textContent = 'Native input, IndexedDB/Web Locks and real offline reload passed; input-to-frame p95 '
                + timings[Math.ceil(timings.length * .95) - 1].toFixed(1) + ' ms (12 simulated input events, offline server)';
            return;
        }
        const answers = ['I', 'like', 'hot', 'pizza', 'and', 'salad', 'for', 'dinner'];
        state.sentence = {sentenceId: 10, hasListened: true, canComplete: false, hintCount: 0, revealCount: 0,
            words: answers.map((word, index) => ({index, length: word.length, mask: '_'.repeat(word.length), userInput: '', correct: false}))};
        renderWordSlots(true);
        let nativeBlurCount = 0;
        elements.wordSlots.addEventListener('focusout', () => nativeBlurCount++);
        const space = el => el.dispatchEvent(new KeyboardEvent('keydown', {key: ' ', bubbles: true, cancelable: true}));
        const firstWord = type(0, answers[0]); await flush();
        check(requests.length === 1, 'finished word did not start checking immediately');
        space(firstWord); await flush();
        check(requests.length === 1, 'Space duplicated the immediate check');
        for (let i = 1; i < answers.length; i++) {
            const el = type(i, answers[i].slice(0, -1));
            el.setSelectionRange(0, 1, 'backward');
            const blurs = nativeBlurCount;
            const updates = [];
            const observer = new MutationObserver(records => updates.push(...records));
            observer.observe(elements.wordSlots, {subtree: true, childList: true, attributes: true, characterData: true});
            await reply(i - 1);
            observer.disconnect();
            check(updates.length > 0 && updates.every(update =>
                Number(update.target.parentElement.closest('.word-slot')?.dataset.slotIndex
                    ?? update.target.closest?.('.word-slot')?.dataset.slotIndex) === i - 1), 'compact result rendered an unrelated word');
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
        check(input(0) === first && first.value === '', 'hint became an unsubmitted answer');
        check(first.closest('.word-slot').querySelector('.slot-hint').textContent.includes('c'), 'hint text missing');
        check(first.labels[0].textContent.includes('c'), 'accessible hint label missing');
        check(input(1) === second && second.selectionStart === 1 && second.selectionEnd === 2, 'hint interrupted another input');
        first.focus(); second.focus(); await flush();
        check(requests.length === 9, 'blurring a hint-only control submitted an answer');

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
        await probeLocalSync(false);
        resetPracticeInput();
        state.wordDrafts.clear(); resetTimer();
        state.sentence = {sentenceId: 13, hasListened: true, words: [{index: 0, length: 6, mask: '______', firstLetter: 'm'}]};
        restorePracticeDraft(); renderWordSlots(true); type(0, 'ma'); await flush();
        check(requests.length === 9, 'saving a partial draft graded it');
        state.timerSeconds = 12; startTimer();
        sessionStorage.setItem('draft-reload-phase', 'restore');
        location.reload();
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
    let child, socket, exited;
    try {
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        child = spawn(chrome, ['--headless=new', '--disable-gpu', '--no-first-run',
            '--no-default-browser-check', '--disable-background-networking', '--user-data-dir=' + profile,
            '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0', 'about:blank'], {windowsHide: true, stdio: 'ignore'});
        let browserError;
        exited = new Promise(resolve => {
            child.once('exit', resolve);
            child.once('error', error => {browserError = error; resolve();});
        });
        const startupDeadline = Date.now() + 30000;
        const portFile = path.join(profile, 'DevToolsActivePort');
        while (!fs.existsSync(portFile) && Date.now() < startupDeadline && !browserError && child.exitCode === null) await delay(25);
        if (browserError) throw browserError;
        assert.ok(fs.existsSync(portFile), 'Chrome did not open its temporary debugger');
        const port = Number(fs.readFileSync(portFile, 'utf8').split('\n')[0]);
        assert.ok(port > 0 && port <= 65535);
        // The port file can appear before the HTTP debugger and its first page are ready.
        let page, readinessError;
        while (!page && Date.now() < startupDeadline && child.exitCode === null) {
            try {
                const response = await fetch('http://127.0.0.1:' + port + '/json/list', {signal: AbortSignal.timeout(1000)});
                if (response.ok) page = (await response.json()).find(target => target.type === 'page');
            } catch (error) { readinessError = error; }
            if (!page) await delay(50);
        }
        assert.ok(page, 'Chrome debugger did not become ready: ' + (readinessError?.message || 'no page target'));
        socket = new WebSocket(page.webSocketDebuggerUrl);
        await once(socket, 'open', {signal: AbortSignal.timeout(5000)});
        let sequence = 0;
        const pending = new Map();
        socket.addEventListener('message', event => {
            const reply = JSON.parse(event.data), request = pending.get(reply.id);
            if (!request) return;
            pending.delete(reply.id);
            reply.error ? request.reject(new Error(reply.error.message)) : request.resolve(reply.result);
        });
        socket.addEventListener('close', () => {
            for (const request of pending.values()) request.reject(new Error('Chrome debugger closed'));
            pending.clear();
        });
        const send = (method, params = {}) => new Promise((resolve, reject) => {
            const id = ++sequence;
            const timer = setTimeout(() => {pending.delete(id); reject(new Error('Chrome command timed out: ' + method));}, 5000);
            pending.set(id, {resolve: value => {clearTimeout(timer); resolve(value);},
                reject: error => {clearTimeout(timer); reject(error);}});
            socket.send(JSON.stringify({id, method, params}));
        });
        // Headless pages may be unfocused when another Chrome window is active on the test machine.
        await send('Emulation.setFocusEmulationEnabled', {enabled: true});
        await send('Page.navigate', {url: 'http://127.0.0.1:' + server.address().port});
        const deadline = Date.now() + 20000;
        let result;
        do {
            try {
                const evaluated = await send('Runtime.evaluate', {returnByValue: true,
                    expression: "({status: document.querySelector('#result')?.dataset.status, message: document.querySelector('#result')?.textContent})"});
                result = evaluated.result.value;
            } catch (error) {
                if (error.message !== 'Inspected target navigated or closed') throw error;
            }
            if (result?.status === 'pass' || result?.status === 'fail') break;
            await delay(25);
        } while (Date.now() < deadline);
        assert.equal(result?.status, 'pass', result?.message || 'Chrome regression timed out');
        t.diagnostic(result.message);
    } finally {
        // Graceful shutdown lets Chrome's children finish writing the temporary profile.
        const killTimer = setTimeout(() => child?.kill(), 5000);
        try {
            if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({id: 0, method: 'Browser.close'}));
            else child?.kill();
            if (exited) await exited;
        } finally {
            clearTimeout(killTimer);
            socket?.close();
        }
        await new Promise(resolve => server.close(resolve));
        // This is the unique temporary Chrome profile created above, never a user profile.
        assert.equal(path.dirname(profile), os.tmpdir());
        await fs.promises.rm(profile, {recursive: true, force: true, maxRetries: 5, retryDelay: 100});
    }
});
