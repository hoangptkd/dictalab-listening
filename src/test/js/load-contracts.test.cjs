const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('local smoke isolates external workers and uses bounded redirected PostgreSQL launch/stop', () => {
    const script = fs.readFileSync(path.resolve(__dirname, '../../../scripts/run-local-evidence-smoke.ps1'), 'utf8');
    for (const flag of ['DICTALAB_MEDIA_S3_ENABLED', 'DICTALAB_MEDIA_S3_CLEANUP_ENABLED', 'DICTALAB_AI_QUEUE_ENABLED']) {
        assert.ok(script.includes(`'${flag}'`), `${flag} must be saved and restored`);
        assert.ok(script.includes(`$env:${flag}='false'`), `${flag} must be disabled for local smoke`);
    }
    assert.match(script, /\$postgresStart=Start-Process[\s\S]*?-WindowStyle Hidden[\s\S]*?-RedirectStandardOutput[\s\S]*?-RedirectStandardError/);
    assert.match(script, /\$null=\$postgresStart.Handle/);
    assert.match(script, /\$postgresStart.WaitForExit\(60000\)/);
    assert.match(script, /\$postgresStop=Start-Process[\s\S]*?-RedirectStandardOutput[\s\S]*?-RedirectStandardError/);
    assert.match(script, /\$postgresStop.WaitForExit\(30000\)/);
    assert.doesNotMatch(script, /& \(Join-Path \$PostgresBin 'pg_ctl.exe'\)/);
    assert.match(script, /-LocalSmoke -RuntimeManifest/);
});
const source = fs.readFileSync(path.resolve(__dirname, '../../../scripts/load/shared.js'), 'utf8');
const context = {__ENV: {CAPACITY_PASSWORD: 'fixture-only'}, Rate: class {}, Counter: class {}, Trend: class {}};
vm.runInNewContext(source.replace(/^import .*;\r?\n/gm, '').replace(/\bexport /g, '') + '\nthis.sameJson = sameJson; this.slowResponseSummary = slowResponseSummary;', context);

test('slow-request diagnostics retain timing and correlation but no payload or credentials', () => {
    const response = {status: 200, timings: {duration: 4123, blocked: 1, connecting: 2, waiting: 4100, receiving: 20},
        headers: {'X-Request-ID': 'capacity-1-2', 'Set-Cookie': 'private-cookie'}, body: 'private-answer', url: 'private-url'};
    const summary = context.slowResponseSummary(response, 'history');
    assert.equal(summary.durationMs, 4123);
    assert.equal(summary.requestId, 'capacity-1-2');
    assert.equal(summary.name, 'history');
    assert.doesNotMatch(JSON.stringify(summary), /private-/);
    assert.equal(context.slowResponseSummary({...response, timings: {duration: 999}}, 'history'), null);
});

test('JSON replay ignores object member order, including nested members', () => {
    assert.equal(context.sameJson({id: 1, turns: [{done: true, attempts: [{id: 'a', grade: 2}]}]},
        {turns: [{attempts: [{grade: 2, id: 'a'}], done: true}], id: 1}), true);
});

test('JSON replay rejects changed values, types, missing members and reordered arrays', () => {
    for (const [a, b] of [[{score: 1}, {score: 2}], [{score: 1}, {score: '1'}],
        [{score: null}, {}], [[], {}], [[1, 2], [2, 1]], [null, {}], [{a: 1}, {b: 1}]]) {
        assert.equal(context.sameJson(a, b), false);
    }
});

test('mixed workload keeps four readers and one stateful writer per five VUs', () => {
    const core = fs.readFileSync(path.resolve(__dirname, '../../../scripts/load/core.js'), 'utf8')
        .replace(/^import .*;\r?\n/gm, '').replace('export default function', 'function workload').replace(/\bexport /g, '');
    for (let vu = 1; vu <= 5; vu++) {
        const calls = [];
        let completed = 0;
        const request = (method, url, body, name, predicate) => {
            calls.push(name);
            const response = {catalog: [{id: 1}], history: {items: []}, dashboard: {},
                start: {sessionId: 's', sentence: {sentenceId: 1, words: [{index: 0, prefilled: false}]}},
                play: {hasListened: true}, check: {words: []}, hint: {requiresReplayAfterReveal: true},
                'replay-start': {hasListened: true}, 'replay-complete': {canComplete: true}, complete: {lessonCompleted: true}}[name];
            assert.equal(predicate(response), true);
            return response;
        };
        const sandbox = {__ENV: {MODE: 'mixed'}, __VU: vu, users: 5, seconds: 10, base: 'http://fixture',
            complete: {add(n) {completed += n;}}, setupAuth() {}, client: () => request, sleep() {}, fail: assert.fail};
        vm.runInNewContext(core + '\nworkload(Array(5).fill({}));', sandbox);
        assert.equal(completed, 1);
        assert.deepEqual(calls, vu === 5 ? ['catalog', 'start', 'play', 'check', 'hint',
            'replay-start', 'replay-complete', 'complete', 'history'] : ['catalog', 'history', 'dashboard']);
    }
});

test('dictation probes accept ready proper-name-only sentences without masking a missing listen', () => {
    const core = fs.readFileSync(path.resolve(__dirname, '../../../scripts/load/core.js'), 'utf8')
        .replace(/^import .*;\r?\n/gm, '').replace('export default function', 'function workload').replace(/\bexport /g, '');
    for (const entry of ['workload(Array(5).fill({}));', 'dictationJourney({});']) {
        for (const [words, canComplete, accepted] of [
            [[{index: 0, prefilled: true, correct: true}], true, true],
            [[{index: 0, prefilled: false}], false, false],
            [[{index: 0, prefilled: false}], true, false],
            [[{index: 0, prefilled: true}], false, false],
            [[], true, false],
        ]) {
            const calls = [];
            const sentence = {sentenceId: 1, words, canComplete, hasListened: false};
            const request = (method, url, body, name, predicate) => {
                calls.push(name);
                const response = {catalog: [{id: 1}], start: {sessionId: 's', sentence},
                    resume: {sessionId: 's', sentence}, play: sentence,
                    complete: {lessonCompleted: true}, history: {items: []}}[name];
                if (!predicate(response)) throw new Error('Business predicate rejected ' + name);
                return response;
            };
            const sandbox = {__ENV: {MODE: 'mixed'}, __VU: 5, __ITER: 0, users: 5, seconds: 10,
                base: 'http://fixture', complete: {add() {}}, setupAuth() {}, client: () => request,
                sleep() {}, fail: assert.fail};
            const run = () => vm.runInNewContext(core + '\n' + entry, sandbox);
            if (accepted) {
                run();
                assert.ok(calls.includes('complete'));
                assert.ok(!calls.includes('hint'));
            } else {
                assert.throws(run, /Business predicate rejected play/);
                assert.ok(!calls.includes('complete'));
            }
        }
    }
});

test('evidence workload preserves 60/30/5/5 per complete block and counts thrown journeys as failures', () => {
    const script=fs.readFileSync(path.resolve(__dirname,'../../../scripts/load/evidence.js'),'utf8')
        .replace(/^import .*;\r?\n/gm,'').replace('export default function','function workload').replace(/\bexport /g,'');
    const metrics={};
    class Metric {constructor(name){this.name=name;metrics[name]=[];} add(value,tags){metrics[this.name].push({value,tags});}}
    const sandbox={__ENV:{EVIDENCE_SUITE:'true',PROVIDER_MODE:'fake',RECALL_TEXT:'fixture'},users:20,seconds:30,
        __VU:1,__ITER:0,Rate:Metric,Counter:Metric,Trend:Metric,exec:{scenario:{startTime:Date.now()}},completed:{add(){}},setupAuth(){},sleep(){},
        readJourney(){throw new Error('private payload');},dictationJourney(){},listeningJourney(){},roomJourney(){},
        console:{error(message){assert.doesNotMatch(message,/private payload/);}}};
    vm.runInNewContext(script+'\nthis.kind=kind;this.workload=workload;this.options=options;',sandbox);
    for(let vu=1;vu<=20;vu++){
        const counts={reads:0,dictation:0,listening:0,room:0};
        for(let iteration=0;iteration<20;iteration++)counts[sandbox.kind(vu,iteration)]++;
        assert.deepEqual(counts,{reads:12,dictation:6,listening:1,room:1});
    }
    sandbox.workload(Array(40).fill({id:1}));
    assert.equal(metrics.journeys_started.length,1);
    assert.equal(metrics.journey_success[0].value,false);
    assert.deepEqual(Array.from(sandbox.options.thresholds['http_req_duration{phase:work}']),['p(95)<=500','p(99)<=1500']);
    for(const env of [{EVIDENCE_SUITE:'true',PROVIDER_MODE:'real',RECALL_TEXT:'fixture'},
        {EVIDENCE_SUITE:'true',PROVIDER_MODE:'fake',RECALL_TEXT:''}]){
        assert.throws(()=>vm.runInNewContext(script,{...sandbox,__ENV:env}),/fake provider/);
    }
});

test('JWT client retains rotated cookies and isolates host/guest sessions with refresh-only path', () => {
    let now=0,cookies={},rotations=0;const requests=[];
    const jar={set(base,key,value,options){
        if(key==='DICTALAB_REFRESH')assert.equal(options.path,'/api/auth');
        if(options.max_age===-1)delete cookies[key];else cookies[key]=[value];
    },cookiesForURL(url){assert.match(url,/\/api\/auth$/);return {...cookies};}};
    const response=(status,body)=>({status,json:()=>body,headers:{},timings:{duration:1}});
    const sandbox={__ENV:{CAPACITY_PASSWORD:'fixture'},__VU:1,Date:{now:()=>now},
        Rate:class {add(){}},Counter:class {add(){}},Trend:class {add(){}},check:(r,checks)=>Object.values(checks).every(check=>check()),
        fail:message=>{throw new Error(message);},http:{cookieJar:()=>jar,
            get:()=>response(200,{token:'csrf-new',headerName:'X-CSRF'}),
            post:()=>{rotations++;cookies={DICTALAB_ACCESS:['access-new'],DICTALAB_REFRESH:['refresh-new']};return response(204);},
            request:(method,url,body,options)=>{requests.push({cookies:{...cookies},options});return response(200,{ok:true});}}};
    vm.runInNewContext(source.replace(/^import .*;\r?\n/gm,'').replace(/\bexport /g,'')+'\nthis.client=client;',sandbox);
    const token=id=>({id,csrf:{headerName:'X-CSRF',token:'initial'},cookies:{DICTALAB_ACCESS:[`access-${id}`],DICTALAB_REFRESH:[`refresh-${id}`]}});
    const host=sandbox.client(token(1)),guest=sandbox.client(token(2));
    const call=client=>client('GET','/api/auth/me',null,'me',body=>body.ok);
    call(host);now=250000;call(host);call(guest);call(host);
    assert.equal(rotations,2);
    assert.equal(requests[0].cookies.DICTALAB_ACCESS[0],'access-1');
    assert.equal(requests[1].cookies.DICTALAB_ACCESS[0],'access-new');
    assert.equal(requests[3].cookies.DICTALAB_REFRESH[0],'refresh-new');
    assert.equal(requests[3].options.headers['X-CSRF'],'csrf-new');
});

test('raw verifier rejects a short window, missing VUs, interrupted work and failed journeys', async () => {
    const {verify}=require('../../../scripts/load/verify-evidence.cjs');
    const zlib=require('node:zlib');
    const crypto=require('node:crypto');
    const folder=path.resolve(__dirname,'../../../target/evidence-contract-'+crypto.randomUUID());
    fs.mkdirSync(folder,{recursive:true});
    const point=(metric,value,tags={})=>({type:'Point',metric,data:{value,tags}});
    const raw=[point('journeys_started',20),point('journeys_finished',20),point('journeys_completed',20),
        point('work_window_ms',30001),point('vus',20),
        ...Object.entries({reads:12,dictation:6,listening:1,room:1}).map(([kind,count])=>point('workload_journeys',count,{kind})),
        ...Array.from({length:20},()=>point('journey_success',1))];
    const valid=path.join(folder,'valid.json.gz');
    fs.writeFileSync(valid,zlib.gzipSync(raw.map(value=>JSON.stringify(value)).join('\n')));
    const result=await verify(valid,20,30);
    assert.equal(result.passed,true);
    assert.equal(result.observedStartedMixPercent.reads,60);
    const invalid=path.join(folder,'invalid.json');
    const changed=raw.map(value=>({...value,data:{...value.data}}));
    changed[1].data.value=19;changed[3].data.value=5000;changed[4].data.value=10;changed[9].data.value=0;
    fs.writeFileSync(invalid,changed.map(value=>JSON.stringify(value)).join('\n'));
    assert.deepEqual((await verify(invalid,20,30)).reasons,['missing_or_interrupted_journeys',
        'incomplete_work_window','target_VUs_not_observed','journey_success_below_99_9','completed_journey_mismatch']);
});

test('setup expires cookies at their exact paths before another account login can revoke them', () => {
    let cookies={},id=0;const revoked=[];
    const jar={set(base,key,value,options){
        assert.equal(options.max_age,-1);assert.equal(options.path,key==='DICTALAB_REFRESH'?'/api/auth':'/');delete cookies[key];},
        cookiesForURL(){return {...cookies};}};
    const response=body=>({status:200,json:()=>body});
    const sandbox={__ENV:{CAPACITY_PASSWORD:'fixture'},__VU:1,Rate:class {add(){}},Counter:class {add(){}},Trend:class {add(){}},
        check:(r,checks)=>Object.values(checks).every(check=>check()),fail:assert.fail,sleep(){},
        http:{cookieJar:()=>jar,get:()=>response({headerName:'X-CSRF',token:'fixture'}),
            post:()=>{if(cookies.DICTALAB_REFRESH)revoked.push(cookies.DICTALAB_REFRESH[0]);id++;
                cookies={DICTALAB_ACCESS:[`access-${id}`],DICTALAB_REFRESH:[`refresh-${id}`]};return response({id});}}};
    vm.runInNewContext(source.replace(/^import .*;\r?\n/gm,'').replace(/\bexport /g,'')+'\nthis.setupAuth=setupAuth;',sandbox);
    const tokens=sandbox.setupAuth(3);
    assert.equal(tokens.length,3);assert.deepEqual(revoked,[]);
    assert.equal(tokens[0].cookies.DICTALAB_REFRESH[0],'refresh-1');
});

test('business client consumes cookie deletion, recovers explicit CSRF once and measures the whole action', () => {
    let cookies={},now=0;const requests=[],metrics={};
    class Metric {constructor(name){this.name=name;metrics[name]=[];} add(value){metrics[this.name].push(value);}}
    const response=(status,headers={})=>({status,headers,json:()=>({ok:true}),timings:{duration:1}});
    const jar={set(base,key,value,options){if(options.max_age===-1)delete cookies[key];else cookies[key]=[value];},
        cookiesForURL:()=>({...cookies})};
    const sandbox={__ENV:{CAPACITY_PASSWORD:'fixture'},__VU:1,Date:{now:()=>now},Rate:Metric,Counter:Metric,Trend:Metric,
        check:(r,checks)=>Object.values(checks).every(check=>check()),fail:message=>{throw new Error(message);},
        http:{cookieJar:()=>jar,get:()=>{now+=20;cookies['XSRF-TOKEN']=['new'];
            return {status:200,json:()=>({token:'new',headerName:'X-CSRF'})};},
            request:(method,url,body,options)=>{requests.push({cookies:{...cookies},options});now+=10;
                if(requests.length===1){delete cookies['XSRF-TOKEN'];return response(200);}
                if(requests.length===2)return response(403,{'X-Csrf-Error':['1']});
                return response(200);}}};
    vm.runInNewContext(source.replace(/^import .*;\r?\n/gm,'').replace(/\bexport /g,'')+'\nthis.client=client;',sandbox);
    const call=sandbox.client({id:1,issuedAt:1,csrf:{headerName:'X-CSRF',token:'initial'},
        cookies:{DICTALAB_ACCESS:['access'],DICTALAB_REFRESH:['refresh'],'XSRF-TOKEN':['initial']}});
    call('GET','/read',null,'read',b=>b.ok);
    call('POST','/write',{},'write',b=>b.ok);
    call('POST','/write',{},'write',b=>b.ok);
    assert.equal(requests[1].cookies['XSRF-TOKEN'],undefined);
    assert.equal(requests[2].options.headers['X-CSRF'],'new');
    assert.equal(requests[3].cookies['XSRF-TOKEN'][0],'new');
    assert.deepEqual(metrics.csrf_recoveries,[1]);
    assert.deepEqual(metrics.business_action_duration,[10,40,10]);
    assert.equal(new Set(requests.map(r=>r.options.headers['X-Request-ID'])).size,4);
});

test('business client never retries uncertain or permission failures and caps explicit CSRF recovery', () => {
    for(const status of [0,503,403,'csrf']){
        let cookies={},requests=0,gets=0;const durations=[];
        const jar={set(base,key,value,options){if(options.max_age===-1)delete cookies[key];else cookies[key]=[value];},
            cookiesForURL:()=>({...cookies})};
        const sandbox={__ENV:{CAPACITY_PASSWORD:'fixture'},__VU:1,Rate:class{add(){}},Counter:class{add(){}},
            Trend:class{add(value){durations.push(value);}},check:()=>false,fail:message=>{throw new Error(message);},
            http:{cookieJar:()=>jar,get:()=>{gets++;return {status:200,json:()=>({token:'new',headerName:'X-CSRF'})};},
                request:()=>{requests++;return {status:status==='csrf'?403:status,
                    headers:status==='csrf'?{'X-CSRF-Error':'1'}:{},json:()=>({}),timings:{duration:1}};}}};
        vm.runInNewContext(source.replace(/^import .*;\r?\n/gm,'').replace(/\bexport /g,'')+'\nthis.client=client;',sandbox);
        const call=sandbox.client({id:1,csrf:{headerName:'X-CSRF',token:'initial'},cookies:{}});
        assert.throws(()=>call('POST','/write',{},'write',()=>true),/Business response rejected/);
        assert.equal(requests,status==='csrf'?2:1);
        assert.equal(gets,status==='csrf'?1:0);
        assert.equal(durations.length,1);
    }
});
