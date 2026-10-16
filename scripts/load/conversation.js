import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { base, users, client, setupAuth, journeyOptions, completed, sameJson } from './shared.js';

if (__ENV.ACK_PROVIDER_CALLS !== 'true' || users > 8 || Number(__ENV.ITERATIONS || 1) !== 1) {
    throw new Error('AI smoke requires ACK_PROVIDER_CALLS=true, at most 8 VUs and exactly one journey each');
}
export const options = journeyOptions(Number(__ENV.AI_P95_MS || 90000), 8);
export function setup() { return setupAuth(); }
function uuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const n = Math.floor(Math.random() * 16); return (c === 'x' ? n : (n & 3) | 8).toString(16);
    });
}
export default function(tokens) {
    const request = client(tokens[__VU - 1]);
    let state = request('POST', '/api/v1/conversation/sessions', { scenario: 'cafe', difficulty: 'A1' },
        'conversation-create', b => !!b.id && Array.isArray(b.turns));
    const path = `/api/v1/conversation/sessions/${state.id}`;
    for (let turn = 1; turn <= 4; turn++) {
        state = request('POST', path + '/next', { turnNumber: turn - 1 }, 'conversation-next', b => b.turnNumber === turn);
        state = request('POST', path + '/play', { turnNumber: turn, variant: 'original', slow: false },
            'conversation-play', b => !!b.audioUrl);
        const audio = http.get(base + state.audioUrl, { responseType: 'binary', tags: { phase: 'work', name: 'conversation-audio' } });
        const audioOk = check(audio, { 'audio bytes received': r => r.status === 200 && r.body.byteLength > 44 });
        if (!audioOk) fail('Audio download failed');
        sleep(3);
        // Assisted journey is explicit: translation supplies a reproducible answer, not learner proficiency evidence.
        state = request('POST', path + '/help', { turnNumber: turn, kind: 'translation' },
            'conversation-translation', b => !!b.turns[turn - 1].prompt.translationVi);
        const body = { turnNumber: turn, requestId: uuid(), answerVi: state.turns[turn - 1].prompt.translationVi };
        const graded = b => b.turns[turn - 1].done === true && b.turns[turn - 1].attempts.some(a => a.requestId === body.requestId && a.evaluation);
        state = request('POST', path + '/answer', body, 'conversation-answer', graded);
        const replay = request('POST', path + '/answer', body, 'conversation-answer-replay', graded);
        if (!sameJson(replay, state)) fail('Idempotent replay changed the response values');
    }
    if (!state.completed || !state.summary) fail('Conversation did not complete');
    completed.add(1);
}
