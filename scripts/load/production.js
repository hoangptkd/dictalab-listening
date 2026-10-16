import {sleep} from 'k6';
import {Counter, Rate} from 'k6/metrics';
import {SharedArray} from 'k6/data';
import {readJourney, dictationJourney} from './core.js';
import {dictationAudioJourney} from './dictation-audio.js';
import {users, seconds, completed, cleanupAuth} from './shared.js';

const tokens = new SharedArray('production-probe-sessions', () => JSON.parse(open(__ENV.AUTH_FILE)));
const started = new Counter('production_started');
const finished = new Counter('production_finished');
const success = new Rate('production_success');
const audioWorkload=__ENV.PRODUCTION_WORKLOAD==='dictation-audio';
if (__ENV.BASE_URL !== 'https://184.194.89.90' || tokens.length !== users || users > 300)
    throw new Error('Production probe requires exact target, distinct sessions and <=300 VUs');
export const options = {
    // Serial logout keeps the production rate limit; 60s is too short for 100+ sessions.
    teardownTimeout: `${users * 3 + 30}s`,
    scenarios: {load: {executor: 'constant-vus', vus: users, duration: `${seconds}s`, gracefulStop: audioWorkload?'90s':'20s'}},
    thresholds: {
        'http_req_duration{phase:work}': ['p(95)<1000', 'p(99)<2000'],
        http_req_failed: ['rate<0.01'], business_failed: ['rate<0.01'],
        production_success: ['rate>0.99'], checks: ['rate>0.99'],
        production_finished: ['count>0'],
        auth_families_cleaned: [`count==${users}`], auth_cleanup_failed: ['rate==0'],
    },
    systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
};
const routes=audioWorkload?['catalog','history','start','resume','play','play-complete','check','hint','replay-start','replay-complete','complete']
    :['catalog','history','dashboard','start','resume','play','check','hint','replay-start','replay-complete','complete'];
for (const name of routes)
    options.thresholds[`http_req_duration{name:${name}}`] = ['p(95)<1000'];
if(audioWorkload){
    options.thresholds.audio_failed=['rate==0'];
    options.thresholds.audio_downloads=['count>0'];
    options.thresholds.audio_playbacks=['count>0'];
    options.thresholds.dictation_sentences_completed=['count>0'];
    options.thresholds.audio_ready_duration=['p(95)<3000','p(99)<5000']; // Declared audio transfer budget, separate from API SLO.
}
if(__ENV.TEST_LEARNING==='true'){
    if(!audioWorkload)throw new Error('Learning verification requires the recorded dictation workload');
    options.thresholds.submission_replays_verified=['count>0'];
    options.thresholds.listening_reviews_completed=['count>0'];
    for(const name of ['learning-bank','review-start','review-answer','review-recovery'])
        options.thresholds[`http_req_duration{name:${name}}`]=['p(95)<1000'];
}
export default function () {
    const kind = audioWorkload?'dictation-audio':__VU % 5 === 0 ? 'dictation' : 'reads';
    started.add(1, {kind});
    let ok = false;
    try {
        if(audioWorkload)dictationAudioJourney(tokens[__VU-1]);
        else if (kind === 'dictation') dictationJourney(tokens[__VU - 1]);
        else readJourney(tokens[__VU - 1]);
        completed.add(1, {kind});
        ok = true;
    } catch (_) {
        // No retry of writes with uncertain outcomes; raw status/timing points retain failures.
    } finally {
        success.add(ok, {kind});
        finished.add(1, {kind});
        sleep(Number(__ENV.THINK_SECONDS || 2));
    }
}
export function teardown() { cleanupAuth(tokens); }
