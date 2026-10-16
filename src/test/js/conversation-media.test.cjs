const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/js/app.js', 'utf8');

function player() {
    const sources = [];
    class AudioContext {
        currentTime = 0;
        state = 'running';
        createBufferSource() {
            const item = {playbackRate: {}, connect() {}, disconnect() {},
                start(...args) { this.args = args; }, stop() { this.stopped = true; }};
            sources.push(item);
            return item;
        }
    }
    const state = {audioPlaybackGeneration: 0};
    const context = vm.createContext({state, window: {AudioContext}, DOMException, AbortSignal,
        authenticatedFetch: async () => { throw Error('unexpected network'); }});
    const functions = source.slice(source.indexOf('    async function playRecordedAudio('), source.indexOf('    function speak('));
    vm.runInContext(functions + '\nloadAudioBuffer = async () => ({duration: 20});\nthis.api = {playRecordedAudio,stopRecordedAudio,getRecordedAudioState,pauseRecordedAudio,resumeRecordedAudio,seekRecordedAudio};', context);
    return {api: context.api, state, sources};
}

test('conversation media pauses and seeks within segment without completing or re-requesting playback', async () => {
    const {api, state, sources} = player();
    let finished = false;
    const playing = api.playRecordedAudio('/audio/test', 4, 14, 0.8).then(() => { finished = true; });
    await Promise.resolve();
    state.audioContext.currentTime = 5;
    assert.equal(api.getRecordedAudioState().currentTime, 4);
    assert.equal(api.getRecordedAudioState().duration, 10);
    api.pauseRecordedAudio();
    state.audioContext.currentTime = 10;
    assert.equal(api.getRecordedAudioState().currentTime, 4);
    assert.equal(api.getRecordedAudioState().paused, true);
    api.seekRecordedAudio(6);
    api.resumeRecordedAudio();
    assert.equal(sources.length, 2);
    assert.deepEqual(sources[1].args, [10, 10, 4]);
    assert.equal(finished, false);
    api.seekRecordedAudio(100);
    await playing;
    assert.equal(finished, true);
    assert.equal(api.getRecordedAudioState().playing, false);
});

test('stop and replacement cancel paused playback and ignore invalid seek', async () => {
    const {api, sources} = player();
    const first = api.playRecordedAudio('/audio/test', 0, 10);
    const cancelled = assert.rejects(first, {name: 'AbortError'});
    await Promise.resolve();
    api.seekRecordedAudio(Number.NaN);
    assert.equal(sources.length, 1);
    api.pauseRecordedAudio();
    api.stopRecordedAudio();
    await cancelled;
    assert.equal(api.getRecordedAudioState().playing, false);
});

test('audio position respects scheduled delay and natural completion', async () => {
    const {api, state, sources} = player();
    const playing = api.playRecordedAudio('/audio/test', 3, 8, 1, 2);
    await Promise.resolve();
    state.audioContext.currentTime = 1;
    assert.equal(api.getRecordedAudioState().currentTime, 0);
    state.audioContext.currentTime = 4;
    assert.equal(api.getRecordedAudioState().currentTime, 2);
    sources[0].onended();
    await playing;
    assert.equal(api.getRecordedAudioState().duration, 0);
});

test('conversation API timeout is bounded, preserves recovery message, and never replays writes', async () => {
    let calls = 0;
    const context = vm.createContext({AbortSignal, navigator: {}, isAdminArea: false,
        state: {}, showAuthScreen() {}, authenticatedFetch(path, request) {
            calls++;
            return new Promise((resolve, reject) => {
                const timer = setTimeout(() => reject(Error('timeout failed')), 1000);
                request.signal.addEventListener('abort', () => { clearTimeout(timer); reject(request.signal.reason); }, {once: true});
            });
        }});
    vm.runInContext(source.slice(source.indexOf('    async function api('), source.indexOf('    const pendingPracticeSubmissions')) + '\nthis.call = api;', context);
    await assert.rejects(context.call('/api/v1/conversation/sessions/x/answer', {method: 'POST', body: {requestId: 'same'}, timeoutMs: 10}), /trạng thái đã lưu/);
    assert.equal(calls, 1);
});

test('conversation API forwards caller cancellation without converting it to a server error', async () => {
    const controller = new AbortController();
    controller.abort();
    const context = vm.createContext({AbortSignal, navigator: {}, isAdminArea: false, state: {},
        showAuthScreen() {}, authenticatedFetch: async (path, request) => { request.signal.throwIfAborted(); }});
    vm.runInContext(source.slice(source.indexOf('    async function api('), source.indexOf('    const pendingPracticeSubmissions')) + '\nthis.call = api;', context);
    await assert.rejects(context.call('/api/v1/conversation', {signal: controller.signal}), {name: 'AbortError'});
});
