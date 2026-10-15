const fs=require('node:fs');
const zlib=require('node:zlib');
const readline=require('node:readline');
function percentile(values,p){
  if(!values.length)return null;
  const a=[...values].sort((x,y)=>x-y),i=(a.length-1)*p;
  return a[Math.floor(i)]+(a[Math.ceil(i)]-a[Math.floor(i)])*(i-Math.floor(i));
}
function prom(row,name,label=()=>true){
  let total=0,found=false;
  for(const line of row.metrics||[]){
    const m=line.match(/^([^\s{]+)(\{.*\})?\s+([-+\d.eE]+)$/);
    if(m&&m[1]===name&&label(m[2]||'')){total+=Number(m[3]);found=true;}
  }
  return found?total:null;
}
function workSloFailures(p95,p99,routes,names=['catalog','history','dashboard','start','resume','play','check','hint','replay-start','replay-complete','complete']){
  const failures=[];
  if(p95===null||p95>=1000)failures.push('work p95 >=1000ms or missing');
  if(p99===null||p99>=2000)failures.push('work p99 >=2000ms or missing');
  for(const name of names)
    if(!routes[name]||routes[name].p95Ms===null||routes[name].p95Ms>=1000)failures.push(name+' work p95 >=1000ms or missing');
  return failures;
}
function cpuSamples(rows,initialRow,key){
  return rows.filter(row=>row!==initialRow).map(row=>row[key]).filter(Number.isFinite);
}
function postgresCounterDelta(rows,key){
  const values=rows.map(row=>row.postgres?.[key]).filter(Number.isFinite);
  if(values.length<2)return null;
  const change=values.at(-1)-values[0];
  return change>=0?change:null; // Counter reset is not evidence of zero failures.
}
async function summarize(prefix){
  const manifest=JSON.parse(fs.readFileSync(prefix+'.manifest.json','utf8'));
  const summary=JSON.parse(fs.readFileSync(prefix+'.summary.json','utf8'));
  const allRows=fs.readFileSync(prefix+'.server.jsonl','utf8').trim().split(/\r?\n/).filter(Boolean).map(JSON.parse);
  const points=[];
  const input=readline.createInterface({input:fs.createReadStream(prefix+'.raw.json.gz').pipe(zlib.createGunzip()),crlfDelay:Infinity});
  let started=0,finished=0,first=Infinity;
  for await(const line of input){
    const point=JSON.parse(line);if(point.type!=='Point')continue;
    const time=Date.parse(point.data.time);
    if(point.metric==='production_started'){started+=point.data.value;first=Math.min(first,time)}
    if(point.metric==='production_finished')finished+=point.data.value;
    if(['http_req_duration','http_reqs','http_req_failed','vus','production_started','audio_ready_duration','audio_bytes','audio_downloads','audio_cache_hits','audio_playbacks','audio_failed','dictation_sentences_completed'].includes(point.metric))
      points.push({...point,time});
  }
  const rows=allRows.filter(r=>Date.parse(r.at)>=first&&Date.parse(r.at)<first+manifest.seconds*1000);
  const work=points.filter(p=>p.data.tags.phase==='work'&&p.time>=first&&p.time<first+manifest.seconds*1000);
  const vuSamples=points.filter(p=>p.metric==='vus'&&p.time>=first&&p.time<first+manifest.seconds*1000);
  const audioPoints=points.filter(p=>p.time>=first&&p.time<first+manifest.seconds*1000);
  const audioValues=name=>audioPoints.filter(p=>p.metric===name).map(p=>p.data.value);
  const audioSum=name=>audioValues(name).reduce((a,b)=>a+b,0);
  const audioRequests=audioPoints.filter(p=>p.metric==='http_reqs'&&p.data.tags.phase==='audio');
  const audioFailures=audioPoints.filter(p=>p.metric==='http_req_failed'&&p.data.tags.phase==='audio');
  const durations=work.filter(p=>p.metric==='http_req_duration');
  const reqs=work.filter(p=>p.metric==='http_reqs');
  const fails=work.filter(p=>p.metric==='http_req_failed');
  const firstFailure=Math.min(...fails.filter(p=>p.data.value>0).map(p=>p.time));
  const healthyRequests=reqs.filter(p=>p.time<firstFailure);
  const healthyDurations=durations.filter(p=>p.time<firstFailure).map(p=>p.data.value);
  const routes={};const statuses={};
  for(const p of reqs){const status=p.data.tags.status||'unknown';statuses[status]=(statuses[status]||0)+p.data.value;}
  for(const p of durations)(routes[p.data.tags.name]??=[]).push(p.data.value);
  const max=values=>values.length?Math.max(...values):null;
  const nums=key=>['cpuPercent','iowaitPercent','stealPercent'].includes(key)
    ?cpuSamples(rows,allRows[0],key):rows.map(r=>r[key]).filter(v=>Number.isFinite(v));
  const metricValues=(name,label)=>rows.map(r=>prom(r,name,label)).filter(v=>v!==null);
  const delta=(name,label)=>{const a=metricValues(name,label);return a.length>=2?a.at(-1)-a[0]:null;};
  const readAverageMs=(name,label)=>{
    const count=delta(name+'_seconds_count',label),sum=delta(name+'_seconds_sum',label);
    return count>0&&sum!==null?1000*sum/count:null;
  };
  const sampleDelta=(key,field)=>rows.length>=2&&Number.isFinite(rows[0][key]?.[field])&&Number.isFinite(rows.at(-1)[key]?.[field])
    ?rows.at(-1)[key][field]-rows[0][key][field]:null;
  const pg=rows.filter(r=>r.postgres&&!r.postgres.unavailable);
  const redis=rows.filter(r=>r.redis);
  const generator=JSON.parse(fs.readFileSync(prefix+'.generator.json','utf8'));
  const failedThresholds=Object.entries(summary.metrics).flatMap(([name,m])=>Object.entries(m.thresholds||{}).filter(([,failed])=>failed).map(([test])=>name+': '+test));
  const routeStats=Object.fromEntries(Object.entries(routes).map(([name,a])=>[name,{n:a.length,p95Ms:percentile(a,.95),p99Ms:percentile(a,.99)}]));
  const workP95=percentile(durations.map(p=>p.data.value),.95),workP99=percentile(durations.map(p=>p.data.value),.99);
  const output={manifest,fullRun:{httpErrorRate:summary.metrics.http_req_failed?.value,
    businessErrorRate:summary.metrics.business_failed?.value,journeySuccessRate:summary.metrics.production_success?.value},
    workWindow:{start:new Date(first).toISOString(),seconds:manifest.seconds,
    requests:reqs.reduce((n,p)=>n+p.data.value,0),rps:reqs.reduce((n,p)=>n+p.data.value,0)/manifest.seconds,
    p95Ms:percentile(durations.map(p=>p.data.value),.95),p99Ms:percentile(durations.map(p=>p.data.value),.99),
    errorRate:fails.length?fails.reduce((n,p)=>n+p.data.value,0)/fails.length:null,statuses,
    firstFailureOffsetSeconds:Number.isFinite(firstFailure)?(firstFailure-first)/1000:null,
    preFailure:Number.isFinite(firstFailure)?{seconds:(firstFailure-first)/1000,
      requests:healthyRequests.length,rps:healthyRequests.length/((firstFailure-first)/1000),
      p95Ms:percentile(healthyDurations,.95),p99Ms:percentile(healthyDurations,.99)}:null},
    routes:routeStats,workSloFailures:workSloFailures(workP95,workP99,routeStats,manifest.businessRoutes),
    learning:manifest.learningVerification?{
      reviewsCompleted:summary.metrics.listening_reviews_completed?.count,
      replayChecks:summary.metrics.submission_replays_verified?.count,
      pendingMax:max(metricValues('dictalab_learning_backlog',l=>l.includes('state="PENDING"'))),
      runningMax:max(metricValues('dictalab_learning_backlog',l=>l.includes('state="RUNNING"'))),
      failedMax:max(metricValues('dictalab_learning_backlog',l=>l.includes('state="FAILED"'))),
      completedDelta:delta('dictalab_learning_completed_total',l=>l.includes('outcome="done"')),
      rabbitMemoryMaxMiB:max(nums('rabbitMemoryBytes'))===null?null:max(nums('rabbitMemoryBytes'))/2**20,
      rabbitQueueSamples:rows.filter(r=>Array.isArray(r.rabbitQueues)).map(r=>({at:r.at,queues:r.rabbitQueues})),
      rabbitAlarmSamples:rows.filter(r=>r.rabbitAlarms!==undefined).map(r=>({at:r.at,alarm:r.rabbitAlarms}))}:null,
    audio:manifest.workloadKind==='dictation-audio'?{scope:'Declared work window, not grace/cleanup; HTTP requests can include redirect hops',
      downloads:audioSum('audio_downloads'),bytes:audioSum('audio_bytes'),cacheHits:audioSum('audio_cache_hits'),
      playbacks:audioSum('audio_playbacks'),sentencesCompleted:audioSum('dictation_sentences_completed'),
      readyP95Ms:percentile(audioValues('audio_ready_duration'),.95),readyP99Ms:percentile(audioValues('audio_ready_duration'),.99),
      validationFailures:audioSum('audio_failed'),httpRequests:audioRequests.reduce((n,p)=>n+p.data.value,0),
      httpErrorRate:audioFailures.length?audioFailures.reduce((n,p)=>n+p.data.value,0)/audioFailures.length:null,
      readySloPass:audioValues('audio_ready_duration').length>0&&percentile(audioValues('audio_ready_duration'),.95)<manifest.audioReadyP95BudgetMs
        &&percentile(audioValues('audio_ready_duration'),.99)<manifest.audioReadyP99BudgetMs}:null,
    accounting:{started,finished,interrupted:started-finished,completed:summary.metrics.journeys_completed?.count,
      successRate:summary.metrics.production_success?.value,peakVUs:max(points.filter(p=>p.metric==='vus').map(p=>p.data.value)),
      workVUSamples:vuSamples.length,workVUMin:vuSamples.length?Math.min(...vuSamples.map(p=>p.data.value)):null,
      workVUMax:max(vuSamples.map(p=>p.data.value))},
    failedThresholds,resources:{samples:rows.length,totalSamplerSamples:allRows.length,
      scope:'Only samples inside the declared work window; cumulative deltas cover sampled boundaries',
      cpuSamples:nums('cpuPercent').length,cpuScope:'Initial sampler tick is excluded: no full measurement interval',
      cpuAvg:nums('cpuPercent').reduce((a,b)=>a+b,0)/nums('cpuPercent').length,
      cpuMax:max(nums('cpuPercent')),appCPUMax:max(nums('appCPUPercent')),iowaitMax:max(nums('iowaitPercent')),stealMax:max(nums('stealPercent')),
      availableMinMiB:Math.min(...nums('ramAvailableBytes'))/2**20,rssMaxMiB:max(nums('appRSSBytes'))/2**20,
      swapMaxMiB:max(nums('swapUsedBytes'))/2**20,
      heapUsedMaxMiB:max(metricValues('jvm_memory_used_bytes',l=>l.includes('area="heap"')))/2**20,
      heapMaxMiB:max(metricValues('jvm_memory_max_bytes',l=>l.includes('area="heap"')))/2**20,
      threadsMax:max(metricValues('jvm_threads_live_threads')),gcPauseDeltaSeconds:delta('jvm_gc_pause_seconds_sum'),
      gcCountDelta:delta('jvm_gc_pause_seconds_count'),poolActiveMax:max(metricValues('hikaricp_connections_active')),
      poolPendingMax:max(metricValues('hikaricp_connections_pending')),poolTimeoutDelta:delta('hikaricp_connections_timeout_total'),
      poolAcquireAverageMs:1000*delta('hikaricp_connections_acquire_seconds_sum')/delta('hikaricp_connections_acquire_seconds_count'),
      poolUsageAverageMs:1000*delta('hikaricp_connections_usage_seconds_sum')/delta('hikaricp_connections_usage_seconds_count'),
      poolMax:max(metricValues('hikaricp_connections_max')),
      poolAcquireCount:delta('hikaricp_connections_acquire_seconds_count'),
      poolUsageSeconds:delta('hikaricp_connections_usage_seconds_sum'),
      telemetrySeconds:rows.length>=2?(Date.parse(rows.at(-1).at)-Date.parse(rows[0].at))/1000:null,
      readAverageMs:{authPostgres:readAverageMs('dictalab_auth_lookup',l=>l.includes('store="postgres"')),
        authRedis:readAverageMs('dictalab_auth_lookup',l=>l.includes('store="redis"')),
        catalogSummary:readAverageMs('dictalab_catalog_read',l=>l.includes('part="summary"')),
        catalogPreview:readAverageMs('dictalab_catalog_read',l=>l.includes('part="preview"'))},
      pagingDeltas:Object.fromEntries(['pswpin','pswpout','pgmajfault'].map(k=>[k,sampleDelta('paging',k)])),
      memoryEventDeltas:Object.fromEntries(['oom','oom_kill','max','sock_throttled'].map(k=>[k,sampleDelta('memoryEvents',k)])),
      restartsFirst:rows[0]?.service?.NRestarts,restartsLast:rows.at(-1)?.service?.NRestarts,
      pgSamples:pg.length,pgSessionsMax:max(pg.map(r=>r.postgres.sessions)),pgActiveMax:max(pg.map(r=>r.postgres.active)),
      pgLockWaitersMax:max(pg.map(r=>r.postgres.lockWaiters)),pgIdleInTxMax:max(pg.map(r=>r.postgres.idleInTransaction)),
      pgDeadlocksDelta:postgresCounterDelta(pg,'deadlocks'),
      pgRollbackDelta:postgresCounterDelta(pg,'rollbacks'),
      fullSamplerPgDeadlocksDelta:postgresCounterDelta(allRows,'deadlocks'),
      fullSamplerPgRollbackDelta:postgresCounterDelta(allRows,'rollbacks'),
      fullSamplerScope:'Includes available grace samples, not necessarily the complete grace/cleanup interval',
      pgProbeMaxMs:max(pg.map(r=>r.postgresProbeMs)),queues:pg[0]?.queues,
      redisSamples:redis.length,redisFailures:redis.filter(r=>!r.redis.ok).length,
      redisPingP95Ms:percentile(redis.map(r=>r.redis.pingMs).filter(Number.isFinite),.95),
      redisPingMaxMs:max(redis.map(r=>r.redis.pingMs).filter(Number.isFinite)),
      redisUsedMemoryMaxBytes:max(redis.map(r=>Number(r.redis.info?.used_memory)).filter(Number.isFinite)),
      generatorCPUMax:max(generator.map(r=>r.cpuPercent)),generatorRSSMaxMiB:max(generator.map(r=>r.k6RSSMiB)),
      generatorAvailableMinMiB:Math.min(...generator.map(r=>r.availableMiB))}};
  fs.writeFileSync(prefix+'.analysis.json',JSON.stringify(output,null,2)+'\n');
  return output;
}
if(require.main===module)summarize(process.argv[2]).then(v=>console.log(JSON.stringify(v,null,2))).catch(e=>{console.error(e.message);process.exitCode=1});
module.exports={percentile,prom,workSloFailures,cpuSamples,postgresCounterDelta};
