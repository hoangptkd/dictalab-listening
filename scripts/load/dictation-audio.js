import http from 'k6/http';
import {check, fail, sleep} from 'k6';
import {Counter, Rate, Trend} from 'k6/metrics';
import {base, client, sameJson} from './shared.js';

const cached=new Map(); // Per-VU cache, same 8-file LRU limit as loadAudioBuffer in the UI.
const downloaded=new Counter('audio_downloads');
const bytes=new Counter('audio_bytes');
const cacheHits=new Counter('audio_cache_hits');
const errors=new Rate('audio_failed');
const ready=new Trend('audio_ready_duration',true);
const played=new Counter('audio_playbacks');
const sentences=new Counter('dictation_sentences_completed');
const reviews=new Counter('listening_reviews_completed');
const replays=new Counter('submission_replays_verified');

export function audioSignature(body){
  const b=new Uint8Array(body),ascii=(offset,text)=>[...text].every((c,i)=>b[offset+i]===c.charCodeAt(0));
  return b.length>=12&&(ascii(0,'ID3')||(b[0]===255&&(b[1]&224)===224)
    ||(ascii(0,'RIFF')&&ascii(8,'WAVE'))||ascii(4,'ftyp')||ascii(0,'OggS')||ascii(0,'fLaC'));
}
export function audioPath(url){
  if(typeof url!=='string'||!/^\/(audio|media)\/[A-Za-z0-9_./%-]+$/.test(url)
    ||url.includes('..')||/%(?:2e|2f|5c)/i.test(url))throw new Error('Audio path outside authorized production media');
  return url;
}
export function segmentSeconds(sentence){
  const start=sentence.audioStartSeconds,end=sentence.audioEndSeconds;
  if(!Number.isFinite(start)||!Number.isFinite(end)||start<0||end<=start||end-start>30)
    throw new Error('Audio segment missing or outside measured duration budget');
  return end-start;
}
export function loadAudio(url){
  const path=audioPath(url);
  if(cached.has(path)){const value=cached.get(path);cached.delete(path);cached.set(path,value);cacheHits.add(1);return;}
  const begin=Date.now();
  // Fetch the whole file like the Web Audio frontend, not an artificial byte range.
  const response=http.get(base+path,{responseType:'binary',redirects:3,timeout:'30s',
    tags:{phase:'audio',name:'audio-download'}});
  const type=String(response.headers['Content-Type']||'').toLowerCase();
  const ok=response.status===200&&(type.startsWith('audio/')||type.includes('octet-stream'))&&audioSignature(response.body);
  ready.add(Date.now()-begin);errors.add(!ok);check(response,{'audio status, content type and file signature valid':()=>ok});
  if(!ok)fail('Recorded audio validation failed');
  bytes.add(response.body.byteLength);downloaded.add(1);
  if(cached.size>=8)cached.delete(cached.keys().next().value);
  cached.set(path,true); // No decoding in k6; cache successful download, not the multi-MB response body.
}
export function dictationAudioJourney(token){
  const request=client(token),level=['A1','A2','B1','B2'][(__VU+__ITER)%4];
  const learning=__ENV.TEST_LEARNING==='true',key=`learning-${__VU}-${__ITER}-${Date.now()}`;
  const lessons=request('GET',`/api/v1/catalog/lessons?level=${level}`,null,'catalog',b=>Array.isArray(b)&&b.length>0);
  const recorded=lessons.filter(l=>l.previewAudioUrl);
  if(!recorded.length)fail('No recorded lesson at selected level');
  const practice=request('POST','/api/v1/practice-sessions',{lessonId:recorded[(__VU+__ITER)%recorded.length].id},
    'start',b=>!!b.sessionId&&!!b.sentence);
  const resumed=request('GET',`/api/v1/practice-sessions/${practice.sessionId}`,null,'resume',b=>b.sessionId===practice.sessionId);
  const state=resumed.sentence,path=`/api/v1/practice-sessions/${practice.sessionId}/sentences/${state.sentenceId}`;
  const duration=segmentSeconds(state);
  function playback(startName,endName){
    request('POST',path+'/replay-events',{event:'STARTED',playbackSpeed:1},startName,b=>b.hasListened===true||b.canComplete===true);
    loadAudio(state.audioUrl);sleep(duration);played.add(1);
    return request('POST',path+'/replay-events',{event:'COMPLETED',playbackSpeed:1},endName,b=>Array.isArray(b.words));
  }
  playback('play','play-complete');
  const word=state.words.find(w=>!w.prefilled);
  if(word){
    const body={answer:'fixture',responseTimeMs:1000},valid=b=>Array.isArray(b.words);
    const checked=request('POST',path+`/words/${word.index}/checks`,body,'check',valid,learning?key+'-check':undefined);
    if(learning){
      const replay=request('POST',path+`/words/${word.index}/checks`,body,'check-duplicate',valid,key+'-check');
      if(!sameJson(checked,replay))fail('Check replay changed its committed result');
      replays.add(1);
    }
    request('POST',path+'/hints',{type:'REVEAL_SENTENCE'},'hint',b=>b.requiresReplayAfterReveal===true);
    const replayed=playback('replay-start','replay-complete');
    if(!replayed.canComplete)fail('Replay did not unlock sentence completion');
  }
  const completion={timeSpentSeconds:Math.ceil(duration*(word?2:1)+2)},validCompletion=
    b=>typeof b.lessonCompleted==='boolean'&&(b.lessonCompleted?!!b.result:!!b.nextSentence);
  const result=request('POST',path+'/complete',completion,'complete',validCompletion,learning?key+'-complete':undefined);
  if(learning){
    const repeated=request('POST',path+'/complete',completion,'complete-duplicate',validCompletion,key+'-complete');
    if(!sameJson(result,repeated))fail('Completion replay changed its committed result');
    replays.add(1);
  }
  if(!result.lessonCompleted&&result.nextSentence.sentenceId===state.sentenceId)fail('Sentence did not advance');
  sentences.add(1);
  request('GET','/api/v1/history?page=0&size=10',null,'history',b=>Array.isArray(b.items));
  if(learning){
    request('GET','/api/v1/listening-learning',null,'learning-bank',b=>Array.isArray(b.items)&&Number.isInteger(b.pending));
    const challenges=request('POST','/api/v1/listening-learning/reviews',null,'review-start',Array.isArray);
    if(challenges.length){
      const challenge=challenges[0],reviewPath='/api/v1/listening-learning/reviews/'+challenge.id;
      loadAudio(challenge.audioUrl);sleep(segmentSeconds(challenge));played.add(1);
      request('POST',reviewPath+'/played',null,'review-played',()=>true);
      const body={answer:'fixture',reveal:false},valid=b=>typeof b.correct==='boolean';
      const result=request('POST',reviewPath+'/answer',body,'review-answer',valid);
      const repeated=request('POST',reviewPath+'/answer',body,'review-answer-duplicate',valid);
      if(!sameJson(result,repeated))fail('Review replay changed its committed schedule');
      request('GET',reviewPath,null,'review-recovery',b=>sameJson(b.result,result));
      replays.add(1);reviews.add(1);
    }
  }
}
