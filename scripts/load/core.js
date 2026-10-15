import http from 'k6/http';
import {sleep,fail} from 'k6';
import {base,users,seconds,completed as complete,setupAuth,client} from './shared.js';
const mode=__ENV.MODE || 'journey';
const profile=__ENV.PROFILE || 'load';
const reader=vu=>mode==='reads' || mode==='arrival' || (mode==='mixed' && vu%5!==0);
export const options={
  insecureSkipTLSVerify:__ENV.INSECURE_TLS==='true',
  discardResponseBodies:false,
  setupTimeout:'10m',
  scenarios: mode==='arrival' ? {load:{executor:'constant-arrival-rate',rate:Number(__ENV.RATE||10),timeUnit:'1s',duration:`${seconds}s`,preAllocatedVUs:users,maxVUs:users}}
    : profile==='spike' ? {load:{executor:'ramping-vus',startVUs:1,stages:[{duration:`${Math.floor(seconds/3)}s`,target:users},{duration:`${Math.floor(seconds/3)}s`,target:users},{duration:`${Math.ceil(seconds/3)}s`,target:1}]}}
    : {load:{executor:'constant-vus',vus:users,duration:`${seconds}s`,gracefulStop:'15s'}},
  thresholds:{business_failed:[{threshold:'rate<0.01',abortOnFail:true,delayAbortEval:'30s'}],
    checks:['rate>0.99'],'http_req_duration{phase:work}':[{threshold:`p(95)<${Number(__ENV.P95_MS||1000)}`,abortOnFail:true,delayAbortEval:'30s'}],
    journeys_completed:['count>0'],
    http_req_failed:[{threshold:'rate<0.01',abortOnFail:true,delayAbortEval:'30s'}]},
  systemTags:['status','method','name','scenario','expected_response'],
};
// Retain route-specific measurements; aggregate latency alone cannot locate a bottleneck.
if(mode==='reads' || mode==='arrival' || mode==='mixed'){
  for(const name of ['catalog','history','dashboard'])options.thresholds[`http_req_duration{name:${name}}`]=['p(95)<1000'];
}
if(mode==='arrival')options.thresholds.dropped_iterations=[`count<${Math.max(1,Number(__ENV.RATE||10)*seconds*0.01)}`];
export function setup(){return setupAuth();}
export function readJourney(token){
  const request=client(token);
  const level=['A1','A2','B1','B2'][(__VU+__ITER)%4];
  request('GET',`/api/v1/catalog/lessons?level=${level}`,null,'catalog',b=>Array.isArray(b));
  request('GET',`/api/v1/history?page=${__ITER%3}&size=10`,null,'history',b=>Array.isArray(b.items));
  request('GET','/api/v1/dashboard',null,'dashboard',b=>typeof b==='object');
}
export function dictationJourney(token){
  const request=client(token);
  const level=['A1','A2','B1','B2'][(__VU+__ITER)%4];
  const lessons=request('GET',`/api/v1/catalog/lessons?level=${level}`,null,'catalog',b=>Array.isArray(b)&&b.length>0);
  const practice=request('POST','/api/v1/practice-sessions',{lessonId:lessons[__ITER%lessons.length].id},'start',b=>!!b.sessionId && !!b.sentence);
  const resumed=request('GET',`/api/v1/practice-sessions/${practice.sessionId}`,null,'resume',b=>b.sessionId===practice.sessionId);
  const state=resumed.sentence;
  const path=`/api/v1/practice-sessions/${practice.sessionId}/sentences/${state.sentenceId}`;
  request('POST',path+'/replay-events',{event:'STARTED',playbackSpeed:1},'play',b=>b.hasListened===true
    || (b.canComplete===true && b.words?.length>0 && b.words.every(w=>w.prefilled===true)));
  sleep(Number(__ENV.PLAYBACK_SECONDS||3));
  const word=state.words.find(w=>!w.prefilled);
  if(word){
    request('POST',path+`/words/${word.index}/checks`,{answer:'fixture',responseTimeMs:1000},'check',b=>Array.isArray(b.words));
    request('POST',path+'/hints',{type:'REVEAL_SENTENCE'},'hint',b=>b.requiresReplayAfterReveal===true);
    request('POST',path+'/replay-events',{event:'STARTED',playbackSpeed:1},'replay-start',b=>b.hasListened===true);
    sleep(Number(__ENV.PLAYBACK_SECONDS||3));
    request('POST',path+'/replay-events',{event:'COMPLETED',playbackSpeed:1},'replay-complete',b=>b.canComplete===true);
  }
  request('POST',path+'/complete',{timeSpentSeconds:6},'complete',b=>typeof b.lessonCompleted==='boolean');
  request('GET','/api/v1/history?page=0&size=10',null,'history',b=>Array.isArray(b.items));
}
export default function(tokens){
  const token=tokens[__VU-1];
  if(!token)fail('Missing distinct VU session');
  const request=client(token);
  function get(path,name,predicate){return request('GET',path,null,name,predicate);}
  function post(path,body,name,predicate){return request('POST',path,body,name,predicate);}
  if(reader(__VU)){
    get('/api/v1/catalog/lessons?level=A1','catalog',b=>Array.isArray(b));
    get('/api/v1/history?page=0&size=10','history',b=>Array.isArray(b.items));
    get('/api/v1/dashboard','dashboard',b=>typeof b==='object');
  }else{
    const lessons=get('/api/v1/catalog/lessons?level=A1','catalog',b=>Array.isArray(b)&&b.length>0);
    const practice=post('/api/v1/practice-sessions',{lessonId:lessons[0].id},'start',b=>!!b.sessionId && !!b.sentence);
    const path=`/api/v1/practice-sessions/${practice.sessionId}/sentences/${practice.sentence.sentenceId}`;
    post(path+'/replay-events',{event:'STARTED',playbackSpeed:1},'play',b=>b.hasListened===true
      || (b.canComplete===true && b.words?.length>0 && b.words.every(w=>w.prefilled===true)));
    sleep(Number(__ENV.PLAYBACK_SECONDS||3));
    const word=practice.sentence.words.find(w=>!w.prefilled);
    if(word){
      post(path+`/words/${word.index}/checks`,{answer:'fixture',responseTimeMs:1000},'check',b=>Array.isArray(b.words));
      post(path+'/hints',{type:'REVEAL_SENTENCE'},'hint',b=>b.requiresReplayAfterReveal===true);
      post(path+'/replay-events',{event:'STARTED',playbackSpeed:1},'replay-start',b=>b.hasListened===true);
      sleep(Number(__ENV.PLAYBACK_SECONDS||3));
      post(path+'/replay-events',{event:'COMPLETED',playbackSpeed:1},'replay-complete',b=>b.canComplete===true);
    }
    post(path+'/complete',{timeSpentSeconds:5},'complete',b=>typeof b.lessonCompleted==='boolean');
    get('/api/v1/history?page=0&size=10','history',b=>Array.isArray(b.items));
  }
  complete.add(1);
  if(mode!=='arrival')sleep(Number(__ENV.THINK_SECONDS||2));
}
