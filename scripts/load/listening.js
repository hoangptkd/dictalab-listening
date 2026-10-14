import { sleep, fail } from 'k6';
import { Trend } from 'k6/metrics';
import { client, setupAuth, journeyOptions, completed } from './shared.js';

const gradingTime = new Trend('grading_elapsed_ms', true);
export const options = journeyOptions(Number(__ENV.P95_MS || 1000));
export function setup() { return setupAuth(); }
export default function(tokens) {
    listeningJourney(tokens[__VU - 1]);
    completed.add(1);
}
export function listeningJourney(token) {
    const request = client(token);
    const units = request('GET', '/api/v1/listening/units', null, 'listening-units', b => Array.isArray(b) && b.length > 0);
    let state = request('POST', '/api/v1/listening/sessions', {
        unitId: Number(__ENV.UNIT_ID || units[(__VU + __ITER) % units.length].id), mode: 'ONE_PASS', heardBefore: true,
    }, 'listening-start', b => !!b.sessionId && b.stage === 'READY');
    const path = `/api/v1/listening/sessions/${state.sessionId}`;
    state = request('POST', path + '/play', {}, 'listening-play', b => b.stage === 'PLAYING' && !!b.playbackToken);
    // Never bypass the real wall-clock playback gate. Audio duration contributes to journey time.
    sleep(state.durationSeconds + 0.2);
    state = request('POST', path + '/play/complete', { token: state.playbackToken }, 'listening-play-complete', b => b.stage === 'RECALL');
    const recall = __ENV.RECALL_TEXT || '';
    request('PUT', path + '/recall', { text: recall }, 'recall-draft', b => b.stage === 'RECALL' && b.recallDraft === recall);
    state = request('POST', path + '/recall', { text: recall }, 'recall-submit', b => b.stage === 'QUESTIONS' && Array.isArray(b.questions));
    const answers = {};
    for (const question of state.questions) answers[question.id] = 0;
    request('PUT', path + '/answers', { answers }, 'answer-draft', b => b.stage === 'QUESTIONS');
    const start = Date.now();
    state = request('POST', path + '/answers', { answers }, 'answer-submit', b => b.stage === 'PASS1_RESULT');
    for (let attempt = 0; attempt < 45 && ['PENDING', 'RUNNING'].includes(state.gradingStatus); attempt++) {
        sleep(2);
        state = request('GET', path, null, 'grading-poll', b => !!b.gradingStatus);
    }
    if (state.gradingStatus !== 'COMPLETED') fail(`Grading did not complete: ${state.gradingStatus}`);
    gradingTime.add(Date.now() - start);
    request('POST', path + '/finish-after-pass-1', {}, 'listening-finish', b => b.stage === 'COMPLETE');
    request('GET', path + '/report', null, 'listening-report', b => !!b.sessionId);
    request('GET', '/api/v1/listening/history/page?page=0&size=10', null, 'listening-history', b => Array.isArray(b.items));
}
