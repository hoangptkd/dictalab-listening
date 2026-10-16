import {sleep, fail} from 'k6';
import {Counter, Rate} from 'k6/metrics';
import {base, users, setupAuth, cleanupAuth, client, sameJson} from './shared.js';
import {loadAudio, segmentSeconds} from './dictation-audio.js';

// API load complements practice-input-races.test.cjs; it cannot reproduce DOM/IME/focus bugs.
const profile = __ENV.PROFILE || 'load';
const hold = Number(__ENV.HOLD_SECONDS || 600);
if (!['smoke', 'load'].includes(profile) || users !== (profile === 'smoke' ? 1 : 50)
    || !Number.isInteger(hold) || hold < 60 || hold > 1800)
    throw new Error('Use smoke/1 VU or load/50 VUs; hold budget is 60..1800 seconds');
if (base !== 'http://127.0.0.1:18282' || !__ENV.RUNTIME_MANIFEST || __ENV.INSECURE_TLS === 'true')
    throw new Error('Only the isolated loopback capacity runtime is allowed; a runtime manifest is required');
const runtime = JSON.parse(open(__ENV.RUNTIME_MANIFEST));
if (!/^dictalab_capacity_[a-z0-9]+$/.test(runtime.Database || '') || !Number.isInteger(runtime.Accounts) || runtime.Accounts < users
    || runtime.RateLimitEnabled !== true || runtime.JwtIsolated !== true || runtime.RedisDedicated !== true
    || runtime.ProviderMode !== 'fake' || !/^[a-f0-9]{64}$/i.test(runtime.ArtifactSHA256 || ''))
    throw new Error('Runtime must have isolated database/JWT/Redis, rate limits, fake providers and enough synthetic accounts');
const stages = [
    {duration: '30s', target: 1}, {duration: '30s', target: 10}, {duration: '60s', target: 10},
    {duration: '30s', target: 25}, {duration: '60s', target: 25}, {duration: '30s', target: 50},
    {duration: `${hold}s`, target: 50}, {duration: '30s', target: 10}, {duration: '60s', target: 10}
];
const workSeconds = profile === 'smoke' ? 30 : hold + 330;
if (!Number.isFinite(Date.parse(runtime.At)) || !Number.isFinite(Number(runtime.MaxRuntimeSeconds))
    || Number(runtime.MaxRuntimeSeconds) < workSeconds + 870)
    throw new Error('Runtime budget must cover setup, full workload, graceful stop and cleanup');

const started = new Counter('practice_started');
const finished = new Counter('practice_finished');
const passed = new Rate('practice_success');
const completed = new Counter('practice_sentences_completed');
const participating = new Counter('practice_vus_completed');
const duplicateChecks = new Counter('practice_duplicates_verified');
let hasCompleted = false;
export const options = {
    setupTimeout: '10m', teardownTimeout: '3m', noCookiesReset: true,
    scenarios: {practice: profile === 'smoke'
        ? {executor: 'constant-vus', vus: 1, duration: '30s', gracefulStop: '90s'}
        : {executor: 'ramping-vus', startVUs: 1, stages, gracefulRampDown: '90s', gracefulStop: '90s'}},
    thresholds: {
        practice_success: [{threshold: 'rate==1', abortOnFail: true, delayAbortEval: '30s'}],
        practice_sentences_completed: [`count>=${users}`], practice_vus_completed: [`count==${users}`],
        practice_duplicates_verified: ['count>0'], checks: ['rate==1'],
        business_failed: [{threshold: 'rate==0', abortOnFail: true, delayAbortEval: '30s'}],
        'http_req_failed{phase:work}': ['rate<0.01'],
        'http_req_duration{phase:work}': ['p(95)<1000', 'p(99)<2000'],
        'business_action_duration{name:typing-check}': ['p(95)<1000', 'p(99)<2000'],
        audio_failed: ['rate==0'], audio_downloads: ['count>0'], audio_ready_duration: ['p(95)<3000'],
        auth_families_cleaned: [`count==${users}`], auth_cleanup_failed: ['rate==0']
    },
    systemTags: ['status', 'method', 'name', 'scenario', 'expected_response']
};
export function setup() {
    // k6 evaluates init again for the final summary; time-left checks belong only to setup.
    const remaining = (Date.parse(runtime.At) + Number(runtime.MaxRuntimeSeconds) * 1000 - Date.now()) / 1000;
    if (!Number.isFinite(remaining) || remaining < workSeconds + 870)
        throw new Error('Runtime budget must cover setup, full workload, graceful stop and cleanup');
    return setupAuth(users);
}
export function teardown(tokens) { cleanupAuth(tokens); }

export function practiceJourney(token) {
    const request = client(token), key = `practice-ux-${__VU}-${__ITER}-${Date.now()}`;
    const level = ['A1', 'A2', 'B1', 'B2'][(__VU - 1 + __ITER) % 4];
    const lessons = request('GET', `/api/v1/catalog/lessons?level=${level}`, null, 'catalog', Array.isArray);
    const recorded = lessons.filter(lesson => lesson.previewAudioUrl);
    if (!recorded.length) fail('No recorded lesson available in the isolated fixture');
    const session = request('POST', '/api/v1/practice-sessions', {lessonId: recorded[__ITER % recorded.length].id},
        'start', body => !!body.sessionId && !!body.sentence);
    const sessionPath = `/api/v1/practice-sessions/${session.sessionId}`;
    const resumed = request('GET', sessionPath, null, 'resume', body => body.sessionId === session.sessionId && !!body.sentence);
    let sentence = resumed.sentence;
    const sentenceId = sentence.sentenceId, path = `${sessionPath}/sentences/${sentenceId}`;
    const duration = segmentSeconds(sentence);
    function play(label) {
        request('POST', path + '/replay-events', {event: 'STARTED', playbackSpeed: 1}, label + '-start', body => body.hasListened || body.canComplete);
        loadAudio(sentence.audioUrl); sleep(duration);
        sentence = request('POST', path + '/replay-events', {event: 'COMPLETED', playbackSpeed: 1}, label + '-end', body => body.sentenceId === sentenceId);
    }
    play('play');
    const editable = sentence.words.filter(word => !word.prefilled && !word.correct && !word.revealed).slice(0, 8);
    for (const word of editable) {
        const answer = 'x'.repeat(Math.max(1, Math.min(word.length, 18)));
        const body = {answer, responseTimeMs: 40};
        const checkPath = path + `/words/${word.index}/checks`, receiptKey = key + `-word-${word.index}`;
        const valid = result => result.sentenceId === sentenceId
            && result.words[word.index].userInput === answer;
        sentence = request('POST', checkPath, body, 'typing-check', valid, receiptKey);
        if (word === editable[0]) {
            const duplicate = request('POST', checkPath, body, 'typing-duplicate', valid, receiptKey);
            if (!sameJson(sentence, duplicate)) fail('Duplicate changed the committed word result');
            request('GET', sessionPath, null, 'typing-recovery', result => result.sentence.sentenceId === sentenceId
                && sameJson(result.sentence.words[word.index], sentence.words[word.index]));
            duplicateChecks.add(1);
        }
        sleep(0.04); // Fast submissions; browser keystroke/debounce behavior is tested separately.
    }
    let playCount = 1;
    if (!sentence.canComplete) {
        request('POST', path + '/hints', {type: 'REVEAL_SENTENCE'}, 'reveal', body => body.requiresReplayAfterReveal === true);
        play('replay'); playCount++;
    }
    if (!sentence.canComplete) fail('Required replay did not unlock completion');
    const body = {timeSpentSeconds: Math.ceil(duration * playCount + 1)};
    const validCompletion = result => typeof result.lessonCompleted === 'boolean'
        && (result.lessonCompleted ? !!result.result : !!result.nextSentence && result.nextSentence.sentenceId !== sentenceId);
    const result = request('POST', path + '/complete', body, 'complete', validCompletion, key + '-complete');
    const duplicate = request('POST', path + '/complete', body, 'complete-duplicate', validCompletion, key + '-complete');
    if (!sameJson(result, duplicate)) fail('Duplicate completion changed the committed result');
    duplicateChecks.add(1);
    request('GET', '/api/v1/history?page=0&size=10', null, 'history', body => Array.isArray(body.items));
}

export default function(tokens) {
    started.add(1);
    let success = false;
    try {
        practiceJourney(tokens[__VU - 1]);
        completed.add(1); success = true;
        if (!hasCompleted) {participating.add(1); hasCompleted = true;}
    } finally {
        passed.add(success); finished.add(1);
    }
    sleep(2);
}
