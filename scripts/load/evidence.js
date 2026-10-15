import {sleep} from 'k6';
import exec from 'k6/execution';
import {Rate,Counter,Trend} from 'k6/metrics';
import {users,seconds,setupAuth,cleanupAuth,completed} from './shared.js';
import {readJourney,dictationJourney} from './core.js';
import {listeningJourney} from './listening.js';
import {roomJourney} from './rooms.js';

if (__ENV.EVIDENCE_SUITE !== 'true' || users > 1000 || users % 20 !== 0)
    throw new Error('Evidence suite requires an explicit acknowledgement and VUs divisible by 20 (max 1000)');
if (__ENV.PROVIDER_MODE !== 'fake' || !__ENV.RECALL_TEXT)
    throw new Error('Bulk evidence requires an isolated fake provider and nonempty recall; real AI runs separately');
const success=new Rate('journey_success');
const started=new Counter('journeys_started');
const finished=new Counter('journeys_finished');
const window=new Trend('work_window_ms',true);
const mix=new Counter('workload_journeys');
const profile=__ENV.PROFILE||'load';
if (!['load','spike'].includes(profile) || (profile === 'spike' && (users !== 300 || seconds < 190)))
    throw new Error('Spike requires 300 maximum VUs and at least 190 seconds');
const scenario=profile==='spike' ? {executor:'ramping-vus',startVUs:100,
    stages:[{duration:'60s',target:100},{duration:'10s',target:300},{duration:`${Math.max(60,seconds-130)}s`,target:300},{duration:'60s',target:100}],gracefulRampDown:'120s',gracefulStop:'120s'}
    : {executor:'constant-vus',vus:users,duration:`${seconds}s`,gracefulStop:'120s'};
export const options={setupTimeout:'30m',teardownTimeout:'30m',noCookiesReset:true,
    scenarios:{work:scenario},systemTags:['status','method','name','scenario','expected_response'],
    thresholds:{journey_success:['rate>=0.999'],journeys_started:['count>0'],journeys_completed:['count>0'],
        work_window_ms:[`max>=${seconds*1000}`],
        auth_cleanup_failed:['rate==0'],auth_families_cleaned:[`count>=${users*2}`],
        'http_req_failed{phase:work}':['rate<=0.001'],
        'http_req_duration{phase:work}':['p(95)<=500','p(99)<=1500'],
        ...Object.fromEntries(['catalog','history','dashboard','start','check','hint','complete','listening-start','room-create']
            .map(name=>[`http_req_duration{phase:work,name:${name}}`,['p(95)<=500','p(99)<=1500']])),
        'workload_journeys{kind:reads}':['count>0'],'workload_journeys{kind:dictation}':['count>0'],
        'workload_journeys{kind:listening}':['count>0'],'workload_journeys{kind:room}':['count>0']} };
// Twenty-iteration deterministic blocks: 12 reads, 6 Dictation, 1 Listening, 1 Room.
// This is a journey mix, not a claim that HTTP request proportions are 60/30/10.
export function kind(vu,iteration){const slot=(vu-1+iteration)%20;
    return slot<12?'reads':slot<18?'dictation':slot===18?'listening':'room';}
export function setup(){return setupAuth(users*2);}
export function teardown(tokens){cleanupAuth(tokens);}
export default function(tokens){
    const owner=tokens[(__VU-1)*2],guest=tokens[(__VU-1)*2+1];
    const type=kind(__VU,__ITER);started.add(1);mix.add(1,{kind:type});
    let ok=false;
    try{
        if(type==='reads')readJourney(owner);
        else if(type==='dictation')dictationJourney(owner);
        else if(type==='listening')listeningJourney(owner);
        else roomJourney(owner,guest);
        ok=true;completed.add(1,{kind:type});
    }catch(error){console.error(JSON.stringify({event:'journey_failed',kind:type,vu:__VU,error:'business_or_transport_failure'}));}
    finally{success.add(ok,{kind:type});finished.add(1);}
    sleep(2+(__VU+__ITER)%4);
    window.add(Date.now()-exec.scenario.startTime);
}
