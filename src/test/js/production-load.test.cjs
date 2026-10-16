const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const {percentile,prom,workSloFailures,cpuSamples,postgresCounterDelta}=require('../../../scripts/load/summarize-production.cjs');

test('PostgreSQL failures in grace are reported separately from the work window',()=>{
    const work=[{postgres:{deadlocks:0}},{postgres:{deadlocks:0}}];
    assert.equal(postgresCounterDelta(work,'deadlocks'),0);
    assert.equal(postgresCounterDelta([...work,{postgres:{deadlocks:1}}],'deadlocks'),1);
    assert.equal(postgresCounterDelta([{postgres:{deadlocks:2}},{postgres:{deadlocks:0}}],'deadlocks'),null);
    assert.equal(postgresCounterDelta([{postgres:{unavailable:true}}],'deadlocks'),null);
});
test('CPU reporting excludes the initial tick, not a later genuine full-load sample',()=>{
  const first={cpuPercent:100},real={cpuPercent:100},later={cpuPercent:20};
  assert.deepEqual(cpuSamples([first,real,later],first,'cpuPercent'),[100,20]);
  assert.deepEqual(cpuSamples([real,later],first,'cpuPercent'),[100,20]);
  assert.deepEqual(cpuSamples([first,{cpuPercent:null}],first,'cpuPercent'),[]);
});
test('work-window acceptance rejects missing routes and exact SLO boundaries',()=>{
  const names=['catalog','history','dashboard','start','resume','play','check','hint','replay-start','replay-complete','complete'];
  const routes=Object.fromEntries(names.map(name=>[name,{p95Ms:999}]));
  assert.deepEqual(workSloFailures(999,1999,routes),[]);
  assert.equal(workSloFailures(1000,2000,routes).length,2);
  assert.equal(workSloFailures(null,null,{}).length,13);
  routes.resume.p95Ms=1000;
  assert.deepEqual(workSloFailures(999,1999,routes),['resume work p95 >=1000ms or missing']);
});
test('report percentiles and heap aggregation use real numeric samples, not absent-as-zero',()=>{
  assert.equal(percentile([], .95),null);assert.equal(percentile([100,200,300],.95),290);
  assert.equal(prom({metrics:[]},'missing'),null);
  assert.equal(prom({metrics:['jvm_memory_used_bytes{area="heap",id="a"} 1e3',
    'jvm_memory_used_bytes{area="heap",id="b"} 500','jvm_memory_used_bytes{area="nonheap"} 9999']},
    'jvm_memory_used_bytes',l=>l.includes('area="heap"')),1500);
});
const source=fs.readFileSync('scripts/load/production.js','utf8')
  .replace(/^import .*;\r?\n/gm,'').replace('export default function','function workload').replace(/\bexport /g,'');
test('production probe reuses business flows and accounts failures without retries or payload logging',()=>{
  const metrics={};class Metric{constructor(name){metrics[name]=[];this.name=name}add(n,t){metrics[this.name].push({n,t})}}
  const ctx={__ENV:{BASE_URL:'https://184.194.89.90',AUTH_FILE:'fixture'},users:10,seconds:120,
    Counter:Metric,Rate:Metric,SharedArray:class{constructor(_,factory){return factory()}},open:()=>JSON.stringify(Array(10).fill({})),
    sleep(){},completed:{add(){}},cleanupAuth(){},readJourney(){},dictationJourney(){throw Error('secret')},__VU:1};
  vm.runInNewContext(source+'\nthis.workload=workload;this.options=options',ctx);
  for(let vu=1;vu<=10;vu++){ctx.__VU=vu;ctx.workload()}
  assert.equal(metrics.production_started.length,10);assert.equal(metrics.production_finished.length,10);
  assert.equal(metrics.production_success.filter(x=>x.n).length,8);
  assert.equal(metrics.production_started.filter(x=>x.t.kind==='dictation').length,2);
  assert.equal(ctx.options.scenarios.load.vus,10);
  assert.equal(ctx.options.teardownTimeout,'60s');
  assert.deepEqual(Array.from(ctx.options.thresholds.auth_families_cleaned),['count==10']);
  assert.deepEqual(Array.from(ctx.options.thresholds.auth_cleanup_failed),['rate==0']);
  assert.doesNotMatch(source,/abortOnFail|console\.|insecureSkipTLSVerify/);
});

test('large production stages allow bounded serial logout without relaxing work thresholds',()=>{
  for(const users of [100,300]){
    const ctx={__ENV:{BASE_URL:'https://184.194.89.90',AUTH_FILE:'fixture'},users,seconds:120,
      Counter:class{},Rate:class{},SharedArray:class{constructor(_,f){return f()}},
      open:()=>JSON.stringify(Array(users).fill({})),cleanupAuth(){}};
    vm.runInNewContext(source+'\nthis.options=options',ctx);
    assert.equal(ctx.options.teardownTimeout,`${users*3+30}s`);
    assert.deepEqual(Array.from(ctx.options.thresholds['http_req_duration{phase:work}']),['p(95)<1000','p(99)<2000']);
    assert.deepEqual(Array.from(ctx.options.thresholds.auth_families_cleaned),[`count==${users}`]);
  }
  const runner=fs.readFileSync('scripts/run-production-load.ps1','utf8');
  assert.match(runner,/\$Seconds\+\$Users\*3\+120/);
  assert.match(runner,/\$Seconds -ge 600 -and \$power.AC -ne 1/);
  assert.match(runner,/SetThreadExecutionState\(\[uint32\]2147483649\)/);
  assert.match(runner,/finally \{\r?\n    \$null=\[DictaLabLoadPower\]::SetThreadExecutionState\(\[uint32\]2147483648\)/);
});
test('production probe rejects accidental alternate target or missing distinct sessions',()=>{
  const ctx={__ENV:{BASE_URL:'https://wrong',AUTH_FILE:'fixture'},users:10,seconds:120,
    Counter:class{},Rate:class{},SharedArray:class{constructor(_,f){return f()}},open:()=>JSON.stringify([{}])};
  assert.throws(()=>vm.runInNewContext(source,ctx),/exact target/);
});

test('thirty-minute campaign preserves latency/error thresholds and has continuous telemetry coverage',()=>{
  const ctx={__ENV:{BASE_URL:'https://184.194.89.90',AUTH_FILE:'fixture'},users:100,seconds:1800,
    Counter:class{},Rate:class{},SharedArray:class{constructor(_,f){return f()}},
    open:()=>JSON.stringify(Array(100).fill({})),cleanupAuth(){}};
  vm.runInNewContext(source+'\nthis.options=options',ctx);
  assert.equal(ctx.options.scenarios.load.duration,'1800s');
  assert.deepEqual(Array.from(ctx.options.thresholds['http_req_duration{phase:work}']),['p(95)<1000','p(99)<2000']);
  assert.deepEqual(Array.from(ctx.options.thresholds.http_req_failed),['rate<0.01']);
  assert.match(fs.readFileSync('scripts/run-production-load.ps1','utf8'),/ValidateRange\(10,1800\)/);
  assert.match(fs.readFileSync('scripts/linux/sample-production.py','utf8'),/10 <= args.seconds <= 3600/);
});
test('dictation audio mode uses every VU, separate transfer SLO and correct API routes',()=>{
  let called=0;const ctx={__ENV:{BASE_URL:'https://184.194.89.90',AUTH_FILE:'fixture',PRODUCTION_WORKLOAD:'dictation-audio'},users:50,seconds:120,
    Counter:class{add(){}},Rate:class{add(){}},SharedArray:class{constructor(_,f){return f()}},
    open:()=>JSON.stringify(Array(50).fill({})),cleanupAuth(){},dictationAudioJourney(){called++;},completed:{add(){}},sleep(){},__VU:1};
  vm.runInNewContext(source+'\nthis.options=options;this.workload=workload;',ctx);ctx.workload();
  assert.equal(called,1);assert.equal(ctx.options.scenarios.load.gracefulStop,'90s');
  assert.equal(ctx.options.thresholds['http_req_duration{name:dashboard}'],undefined);
  assert.deepEqual(Array.from(ctx.options.thresholds.audio_failed),['rate==0']);
  assert.deepEqual(Array.from(ctx.options.thresholds.audio_ready_duration),['p(95)<3000','p(99)<5000']);
  assert.deepEqual(workSloFailures(900,1500,{'play-complete':{p95Ms:900}},['play-complete']),[]);
});
