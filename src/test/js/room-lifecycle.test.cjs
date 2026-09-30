const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/js/app.js"), "utf8");

function loadFunction(name, context) {
    const match = source.match(new RegExp(`    (?:async )?function ${name}\\([^]*?\\n    \\}`));
    assert.ok(match, `${name} is present`);
    vm.runInContext(`${match[0]}\nthis.${name} = ${name}`, context);
}

test("a failed leave request retains the room for retry", async () => {
    const state = {room: {roomId: "room-one"}};
    const context = vm.createContext({
        state, elements: {}, api: async () => { throw Object.assign(new Error("Network offline"), {status: 503}); },
        roomCleanupMedia: () => assert.fail("media must remain active"),
        roomStopPolling: () => assert.fail("polling must remain active"),
        showToast: () => {}, encodeURIComponent, window: {clearTimeout: () => {}}
    });
    loadFunction("roomLeave", context);
    assert.equal(await context.roomLeave(), false);
    assert.equal(state.room.roomId, "room-one");
});

test("guest readiness received before host microphone triggers an offer later", async () => {
    const sent = [];
    const state = {roomIsHost: true, roomPc: null, roomMakingOffer: false, roomPeerReady: false};
    const context = vm.createContext({state, roomSendWs: message => sent.push(message), roomShowError: () => {}});
    loadFunction("roomOnPeerReady", context);
    await context.roomOnPeerReady();
    assert.equal(state.roomPeerReady, true);
    state.roomPc = {
        signalingState: "stable", localDescription: {type: "offer"},
        createOffer: async () => ({type: "offer"}), setLocalDescription: async () => {}
    };
    await context.roomOnPeerReady();
    assert.equal(sent.length, 1);
    assert.equal(sent[0].type, "offer");
    assert.equal(state.roomPeerReady, false);
});

function audioFixture(getUserMedia) {
    const track = {enabled: true, stop() { this.stopped = true; }, addEventListener() {}};
    class Stream {
        constructor(tracks = []) { this.tracks = tracks; }
        getTracks() { return this.tracks; }
        getAudioTracks() { return this.tracks; }
    }
    let connections = 0;
    class PeerConnection {
        constructor() { connections++; this.connectionState = "new"; }
        addTrack() {}
        close() { this.connectionState = "closed"; }
        async getStats() { return new Map([["audio", {type: "inbound-rtp", kind: "audio", packetsReceived: 12}]]); }
    }
    const localStream = new Stream([track]);
    const audio = {
        srcObject: null, paused: true, readyState: 0, muted: false, volume: 1,
        async play() { this.paused = false; }, pause() { this.paused = true; }
    };
    const classList = {toggle() {}, add() {}, remove() {}};
    const elements = {
        roomRemoteAudio: audio,
        roomHostCard: {classList}, roomGuestCard: {classList},
        sharedHostAvatar: {classList}, sharedGuestAvatar: {classList},
        roomHostActivity: {textContent: ""}, roomGuestActivity: {textContent: ""},
        roomHostMicIndicator: {classList}, roomGuestMicIndicator: {classList}
    };
    for (const name of ["roomJoinCallBtn", "roomMicBtn", "sharedMicButton", "roomAutoplayNotice", "sharedEnableAudio", "roomReconnectBtn", "sharedReconnect"]) {
        elements[name] = {hidden: true, disabled: false, setAttribute() {}};
    }
    const state = {
        room: {roomId: "room-one"}, roomWs: {readyState: 1}, roomPc: null,
        roomIsHost: true, roomSpeechMonitors: {}, roomSpeechContext: null, roomSpeechTimer: null
    };
    const statuses = [];
    const context = vm.createContext({
        state, elements, navigator: {mediaDevices: {getUserMedia: getUserMedia || (async () => localStream)}},
        WebSocket: {OPEN: 1}, RTCPeerConnection: PeerConnection, MediaStream: Stream,
        api: async () => ({iceServers: []}), roomClearError() {}, roomUpdateMicButton() {}, roomSendWs() {},
        roomShowError: message => assert.fail(message), roomUpdateStatus: status => statuses.push(status),
        window: {clearTimeout() {}, setTimeout: () => 1, clearInterval() {}}
    });
    for (const name of ["roomSetSpeaking", "roomStopSpeechMonitor", "roomStopAllSpeechMonitors",
        "roomSendMicState", "roomConfirmCallReady", "roomJoinCall", "roomEnableAudio", "roomCheckAudio", "roomCleanupMedia"]) loadFunction(name, context);
    return {context, state, elements, track, Stream, localStream, audio, statuses, connections: () => connections};
}

test("remote audio is attached only after a track arrives, including streamless tracks", async () => {
    for (const streamless of [false, true]) {
        const f = audioFixture();
        await f.context.roomJoinCall();
        assert.equal(f.audio.srcObject, null, "an empty stream must never be attached to the player");
        const remote = new f.Stream([f.track]);
        f.state.roomPc.ontrack({track: f.track, streams: streamless ? [] : [remote]});
        await Promise.resolve();
        assert.equal(f.audio.srcObject.getAudioTracks()[0], f.track);
        if (!streamless) assert.equal(f.audio.srcObject, remote);
        f.state.roomPc.connectionState = "connected";
        await f.context.roomCheckAudio(f.state.roomPc);
        assert.equal(f.statuses.at(-1), "CONNECTING_AUDIO", "received packets alone do not confirm playback");
        f.audio.readyState = 4;
        await f.context.roomCheckAudio(f.state.roomPc);
        assert.equal(f.statuses.at(-1), "CONNECTED");
    }
});

test("blocked playback offers an explicit retry and recovers after user interaction", async () => {
    const f = audioFixture();
    await f.context.roomJoinCall();
    f.state.roomPc.connectionState = "connected";
    f.audio.srcObject = new f.Stream([f.track]);
    f.audio.play = async () => { throw new Error("NotAllowedError"); };
    await f.context.roomEnableAudio();
    assert.equal(f.elements.roomAutoplayNotice.hidden, false);
    assert.equal(f.elements.sharedEnableAudio.hidden, false);
    f.audio.play = async () => { f.audio.paused = false; f.audio.readyState = 4; };
    await f.context.roomEnableAudio();
    assert.equal(f.elements.roomAutoplayNotice.hidden, true);
    assert.equal(f.elements.sharedEnableAudio.hidden, true);
    assert.equal(f.statuses.at(-1), "CONNECTED");
});

test("joining twice while permission is pending creates only one connection", async () => {
    let grant;
    let requested;
    const pending = new Promise(resolve => { requested = resolve; });
    const f = audioFixture(() => new Promise(resolve => { grant = resolve; requested(); }));
    const first = f.context.roomJoinCall();
    await pending;
    await f.context.roomJoinCall();
    assert.equal(f.elements.sharedMicButton.disabled, true);
    grant(f.localStream);
    await first;
    assert.equal(f.connections(), 1);
    assert.equal(f.elements.sharedMicButton.disabled, false);
});

test("leaving while permission is pending stops the late microphone stream", async () => {
    let grant;
    let requested;
    const pending = new Promise(resolve => { requested = resolve; });
    const f = audioFixture(() => new Promise(resolve => { grant = resolve; requested(); }));
    const joining = f.context.roomJoinCall();
    await pending;
    f.context.roomCleanupMedia();
    grant(f.localStream);
    await joining;
    assert.equal(f.connections(), 0);
    assert.equal(f.track.stopped, true);
    assert.equal(f.state.roomPc, null);
});

test("voice activity ignores brief noise and releases after silence", () => {
    let loud = true;
    const classes = new Set();
    const classList = {toggle(name, on) { on ? classes.add(name) : classes.delete(name); }};
    const monitor = {
        analyser: {getByteTimeDomainData(data) { data.fill(loud ? 150 : 128); }},
        data: new Uint8Array(16), loud: 0, quiet: 0, speaking: false
    };
    const state = {roomIsHost: true, roomMicEnabled: true, roomPeerMicEnabled: true,
        roomSpeechMonitors: {local: monitor}};
    const elements = {
        roomHostCard: {classList}, roomGuestCard: {classList},
        sharedHostAvatar: {classList}, sharedGuestAvatar: {classList},
        roomHostActivity: {textContent: ""}, roomGuestActivity: {textContent: ""}
    };
    const context = vm.createContext({state, elements, Object, Math});
    loadFunction("roomSetSpeaking", context);
    loadFunction("roomMeasureSpeech", context);

    context.roomMeasureSpeech();
    assert.equal(monitor.speaking, false);
    context.roomMeasureSpeech();
    assert.equal(monitor.speaking, true);
    assert.equal(elements.roomHostActivity.textContent, "Đang nói");

    loud = false;
    for (let i = 0; i < 5; i++) context.roomMeasureSpeech();
    assert.equal(monitor.speaking, true);
    context.roomMeasureSpeech();
    assert.equal(monitor.speaking, false);
    assert.equal(elements.roomHostActivity.textContent, "Mic đang bật");
});
