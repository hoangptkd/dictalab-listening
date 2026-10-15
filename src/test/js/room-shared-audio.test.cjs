const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const source = fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/js/app.js"), "utf8");
function load(name, context) {
    const match = source.match(new RegExp(`    (?:async )?function ${name}\\([^]*?\\n    \\}`));
    assert.ok(match);
    vm.runInContext(`${match[0]}\nthis.${name} = ${name}`, context);
}
function fixture(isHost = false) {
    const plays = [], sent = [];
    const state = {room: {roomId: "one"}, roomStudy: {}, roomSharedSessionId: "shared",
        session: {sessionId: "shared"}, sentence: {sentenceId: 5, position: 1},
        roomPlayback: null, roomPlaybackSeen: 0, roomIsHost: isHost,
        roomAudioEnabled: true, audioContext: {state: "running"}};
    const elements = {replayCount: {}, playbackSpeed: {}};
    const context = vm.createContext({state, elements, Date, document: {body: {dataset: {route: "practice"}}},
        updatePracticeControls() {}, playSentence: p => plays.push(p),
        roomSendWs: p => sent.push(p), setPracticeStatus() {}});
    load("roomApplyPlayback", context);
    const playback = {version: 4, sequence: 1, position: 1, replayCount: 1, speed: 0.75,
        startsAt: "2026-10-03T10:00:00.750Z", playing: true, hostAudioReady: true, guestAudioReady: true};
    return {state, elements, context, playback, plays, sent};
}
test("one host command plays once on each member with the same count and speed", () => {
    for (const host of [true, false]) {
        const f = fixture(host);
        f.context.roomApplyPlayback(f.playback, "2026-10-03T10:00:00Z");
        f.context.roomApplyPlayback(f.playback, "2026-10-03T10:00:00Z");
        assert.equal(f.plays.length, 1);
        assert.equal(f.plays[0].speed, 0.75);
        assert.equal(f.elements.replayCount.textContent, 1);
        f.context.roomApplyPlayback({...f.playback, version: 3, replayCount: 0}, "2026-10-03T10:00:00Z");
        assert.equal(f.elements.replayCount.textContent, 1);
        f.context.roomApplyPlayback({...f.playback, position: 2, sequence: 2}, "2026-10-03T10:00:00Z");
        assert.equal(f.plays.length, 1);
    }
});
test("reconnect skips old playback and suspended audio never gets marked as heard", () => {
    for (const blocked of [false, true]) {
        const f = fixture();
        if (blocked) f.state.audioContext.state = "suspended";
        f.context.roomApplyPlayback(f.playback, blocked ? "2026-10-03T10:00:00Z" : "2026-10-03T10:00:10Z");
        assert.equal(f.plays.length, 0);
        assert.equal(f.elements.replayCount.textContent, 1);
        assert.deepEqual(f.sent.map(p => p.type), ["audio-ready", "lesson-played"]);
    }
});
test("guest cannot initiate a shared play through the keyboard/click handler", async () => {
    const f = fixture();
    f.state.roomPlayback = f.playback;
    load("playSentence", f.context);
    await f.context.playSentence({type: "click"});
    assert.equal(f.sent.length, 0);
    f.state.roomIsHost = true;
    f.state.roomPlayback.playing = false;
    f.state.playbackSpeed = 1.25;
    await f.context.playSentence({type: "click"});
    assert.equal(f.sent[0].type, "lesson-play");
    assert.equal(f.sent[0].speed, 1.25);
});
