const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('scripts/load/dictation-audio.js','utf8').replace(/^import .*;\r?\n/gm,'').replace(/\bexport /g,'');
function context(){
  const values={},calls=[],sleeps=[];class Metric{constructor(name){this.name=name;values[name]=[];}add(n){values[this.name].push(n);}}
  const body=Uint8Array.from([73,68,51,0,0,0,0,0,0,0,0,0]).buffer;
  const ctx={Counter:Metric,Rate:Metric,Trend:Metric,base:'https://184.194.89.90',Uint8Array,Date,
    http:{get(url,opts){calls.push({url,opts});return{status:200,headers:{'Content-Type':'audio/mpeg'},body};}},
    check(_,checks){return Object.values(checks).every(f=>f());},fail(message){throw Error(message);},sleep(n){sleeps.push(n);},
    __VU:1,__ITER:0,__ENV:{}};
  vm.runInNewContext(source+'\nthis.api={audioSignature,audioPath,segmentSeconds,loadAudio,dictationAudioJourney};',ctx);
  return{ctx,api:ctx.api,values,calls,sleeps,body};
}
test('audio paths and segment budgets reject unrelated targets and malformed state',()=>{
  const {api,body}=context();assert.equal(api.audioSignature(body),true);
  assert.equal(api.audioSignature(new Uint8Array(12).buffer),false);
  for(const url of ['https://external/audio.mp3','/api/auth/login','/audio/../secret','/audio/%2e%2e/file','//external/audio.mp3'])
    assert.throws(()=>api.audioPath(url),/authorized/);
  assert.equal(api.audioPath('/media/s3/file.mp3'),'/media/s3/file.mp3');
  assert.equal(api.segmentSeconds({audioStartSeconds:3,audioEndSeconds:8}),5);
  for(const s of [{},{audioStartSeconds:3,audioEndSeconds:3},{audioStartSeconds:0,audioEndSeconds:31}])
    assert.throws(()=>api.segmentSeconds(s),/duration budget/);
});
test('audio really downloads full binary and replays use bounded per-VU LRU',()=>{
  const {api,calls,values}=context();api.loadAudio('/audio/1.mp3');api.loadAudio('/audio/1.mp3');
  assert.equal(calls.length,1);assert.equal(calls[0].opts.responseType,'binary');assert.equal(calls[0].opts.headers,undefined);
  assert.equal(calls[0].opts.tags.phase,'audio');assert.deepEqual(values.audio_bytes,[12]);
  for(let n=2;n<=9;n++)api.loadAudio(`/audio/${n}.mp3`);
  api.loadAudio('/audio/1.mp3');assert.equal(calls.length,10);assert.deepEqual(values.audio_cache_hits,[1]);
});
test('invalid audio fails without caching or retrying the request',()=>{
  const {ctx,api,values}=context();let calls=0;
  ctx.http.get=()=>{calls++;return{status:404,headers:{},body:new Uint8Array(12).buffer};};
  assert.throws(()=>api.loadAudio('/audio/missing.mp3'),/validation failed/);
  assert.equal(calls,1);assert.deepEqual(values.audio_failed,[true]);assert.equal(values.audio_downloads.length,0);
});
test('dictation journey records both playback completions and advances after real duration, never calls AI',()=>{
  const {ctx,api,calls,sleeps,values}=context(),requests=[];
  const sentence={sentenceId:12,audioUrl:'/audio/lesson.mp3',audioStartSeconds:4,audioEndSeconds:9,words:[{index:0,prefilled:false}]};
  ctx.client=()=> (method,path,body,name,predicate)=>{
    requests.push({method,path,body,name});let result;
    if(name==='catalog')result=[{id:1,previewAudioUrl:sentence.audioUrl}];
    else if(name==='start'||name==='resume')result={sessionId:'s',sentence};
    else if(name==='history')result={items:[]};
    else if(name==='complete')result={lessonCompleted:false,nextSentence:{sentenceId:13}};
    else result={hasListened:true,words:sentence.words,requiresReplayAfterReveal:true,canComplete:true};
    assert.equal(predicate(result),true);return result;
  };
  api.dictationAudioJourney({id:1});assert.deepEqual(sleeps,[5,5]);assert.equal(calls.length,1);
  assert.equal(requests.filter(r=>r.body?.event==='COMPLETED').length,2);
  assert.equal(requests.find(r=>r.name==='complete').body.timeSpentSeconds,12);
  assert.deepEqual(values.audio_playbacks,[1,1]);assert.deepEqual(values.dictation_sentences_completed,[1]);
  assert.equal(requests.some(r=>/ai|coach|conversation|generate/.test(r.path)),false);
});
test('learning workload verifies duplicate receipts and review recovery without real AI',()=>{
  const {ctx,api,values}=context(),requests=[];
  ctx.__ENV.TEST_LEARNING='true';ctx.sameJson=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
  const sentence={sentenceId:12,audioUrl:'/audio/lesson.mp3',audioStartSeconds:4,audioEndSeconds:9,words:[{index:0,prefilled:false}]};
  ctx.client=()=> (method,path,body,name,predicate,key)=>{
    requests.push({method,path,body,name,key});let result;
    if(name==='catalog')result=[{id:1,previewAudioUrl:sentence.audioUrl}];
    else if(name==='start'||name==='resume')result={sessionId:'s',sentence};
    else if(name==='history')result={items:[]};
    else if(name.startsWith('complete'))result={lessonCompleted:false,nextSentence:{sentenceId:13}};
    else if(name==='learning-bank')result={items:[],pending:0};
    else if(name==='review-start')result=[{id:'review',audioUrl:'/audio/other.mp3',audioStartSeconds:0,audioEndSeconds:3}];
    else if(name.startsWith('review-answer'))result={correct:false,stage:0};
    else if(name==='review-recovery')result={result:{correct:false,stage:0}};
    else result={hasListened:true,words:sentence.words,requiresReplayAfterReveal:true,canComplete:true};
    assert.equal(predicate(result),true);return result;
  };
  api.dictationAudioJourney({id:1});
  assert.equal(requests.find(r=>r.name==='check').key,requests.find(r=>r.name==='check-duplicate').key);
  assert.equal(requests.find(r=>r.name==='complete').key,requests.find(r=>r.name==='complete-duplicate').key);
  assert.deepEqual(values.submission_replays_verified,[1,1,1]);assert.deepEqual(values.listening_reviews_completed,[1]);
  assert.equal(requests.some(r=>/ai|coach|conversation|generate/.test(r.path)),false);
});
