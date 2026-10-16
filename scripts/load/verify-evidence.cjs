const fs=require('node:fs');
const zlib=require('node:zlib');
const readline=require('node:readline');
const assert=require('node:assert/strict');

async function verify(file,users,seconds) {
    assert.ok(Number.isSafeInteger(users) && users>=1,'Invalid VU target');
    assert.ok(Number.isSafeInteger(seconds) && seconds>=10,'Invalid duration target');
    const input=fs.createReadStream(file);
    const decoded=file.endsWith('.gz')?input.pipe(zlib.createGunzip()):input;
    if(decoded!==input)input.on('error',error=>decoded.destroy(error));
    const lines=readline.createInterface({input:decoded,crlfDelay:Infinity});
    const counts={journeys_started:0,journeys_finished:0,journeys_completed:0};
    const mix={reads:0,dictation:0,listening:0,room:0};
    let windowMs=0,peakVUs=0,success=0,finished=0;
    try{
        for await(const line of lines){
            const point=JSON.parse(line);
            if(point.type!=='Point')continue;
            const {metric,data}=point;
            const counter=Object.hasOwn(counts,metric) || metric==='workload_journeys';
            if(!counter && !['work_window_ms','vus','journey_success'].includes(metric))continue;
            assert.ok(data && Number.isFinite(data.value) && data.value>=0,'Invalid metric value');
            const value=data.value;
            if(counter || metric==='vus')assert.ok(Number.isSafeInteger(value),'Invalid integer metric');
            if(metric==='journey_success')assert.ok(value===0 || value===1,'Invalid success sample');
            if(Object.hasOwn(counts,metric)){
                counts[metric]+=value;
                assert.ok(Number.isSafeInteger(counts[metric]),'Counter overflow');
            }
            if(metric==='workload_journeys'){
                assert.ok(data.tags && typeof data.tags.kind==='string' && Object.hasOwn(mix,data.tags.kind),'Invalid workload kind');
                mix[data.tags.kind]+=value;
                assert.ok(Number.isSafeInteger(mix[data.tags.kind]),'Counter overflow');
            }
            if(metric==='work_window_ms')windowMs=Math.max(windowMs,value);
            if(metric==='vus')peakVUs=Math.max(peakVUs,value);
            if(metric==='journey_success'){finished++;success+=value;}
        }
    }finally{
        lines.close();
        input.destroy();
        decoded.destroy();
    }
    const reasons=[];
    if(counts.journeys_started===0 || counts.journeys_started!==counts.journeys_finished || finished!==counts.journeys_finished)
        reasons.push('missing_or_interrupted_journeys');
    if(windowMs<seconds*1000)reasons.push('incomplete_work_window');
    if(peakVUs<users)reasons.push('target_VUs_not_observed');
    if(!finished || success/finished<0.999)reasons.push('journey_success_below_99_9');
    if(Object.values(mix).some(count=>count===0))reasons.push('missing_workload_kind');
    const total=Object.values(mix).reduce((a,b)=>a+b,0);
    if(counts.journeys_completed!==success)reasons.push('completed_journey_mismatch');
    if(!Number.isSafeInteger(total) || total!==counts.journeys_started)reasons.push('workload_count_mismatch');
    return {passed:reasons.length===0,reasons,counts,workWindowMs:windowMs,peakVUs:peakVUs,
        journeySuccess:finished?success/finished:0,observedStartedMix:mix,
        observedStartedMixPercent:Object.fromEntries(Object.entries(mix).map(([kind,count])=>[kind,total?100*count/total:0])),
        note:'Raw accounting is necessary, not sufficient: k6 thresholds, runtime guards and DB invariants must also pass.'};
}

module.exports={verify};
if(require.main===module){
    const [file,users,seconds]=process.argv.slice(2);
    const parsedUsers=Number(users),parsedSeconds=Number(seconds);
    if(!file || !Number.isSafeInteger(parsedUsers) || parsedUsers<1 ||
        !Number.isSafeInteger(parsedSeconds) || parsedSeconds<10){
        process.stderr.write('Evidence invalid: Usage\n');
        process.exitCode=1;
    }else verify(file,parsedUsers,parsedSeconds).then(result=>{
        process.stdout.write(JSON.stringify(result,null,2)+'\n');
        if(!result.passed)process.exitCode=1;
    }).catch(error=>{process.stderr.write(`Evidence invalid: ${error.name}\n`);process.exitCode=1;});
}
