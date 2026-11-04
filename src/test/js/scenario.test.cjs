const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const crypto = require('node:crypto');
const source = fs.readFileSync(require('node:path').resolve(__dirname, '../../main/resources/static/js/scenario.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));

function harness(api, storage = new Map()) {
    const nodes = new Map(), calls = [], timers = [];
    function node(key) {
        if (!nodes.has(key)) nodes.set(key, {listeners: {}, value: '', textContent: '', addEventListener(name, fn) {this.listeners[name] = fn;}, setAttribute() {}, focus() {}, async play() {}, pause() {}, load() {}, removeAttribute(name) {delete this[name];}});
        return nodes.get(key);
    }
    const root = {innerHTML: '', setAttribute() {}, removeAttribute() {},
        querySelector(selector) {
            if (selector === 'audio') return this.innerHTML.includes('id="scenario-audio"') ? node('#scenario-audio') : null;
            if (selector.startsWith('#') && (this.innerHTML.includes(`id="${selector.slice(1)}"`) || [...nodes.values()].some(n => n.innerHTML?.includes(`id="${selector.slice(1)}"`)))) return node(selector);
            return null;
        },
        querySelectorAll(selector) {
            const attr = selector.slice(1, -1);
            return [...this.innerHTML.matchAll(new RegExp(`${attr}="([^"]*)"`, 'g'))].map((match, index) => {
                const item = node(`${attr}:${index}`);
                item.dataset = {[attr.replace('data-', '').replace(/-([a-z])/g, (_, c) => c.toUpperCase())]: match[1]}; return item;
            });
        }};
    let user = {id: 10};
    const window = {DictaLabListeningBridge: {currentUser: () => user, navigate() {},
        async api(url, options) {calls.push({url, options}); return api(url, options);}}};
    vm.runInNewContext(source, {window, document: {querySelector: () => root}, AbortController, crypto,
        setTimeout: fn => {timers.push(fn); return timers.length;}, clearTimeout() {},
        sessionStorage: {getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key)},
        FormData: class extends Map {constructor(form) {super(form.values || []);}}});
    return {module: window.DictaLabScenario, root, nodes, calls, storage, timers, setUser: id => {user = {id};}, node};
}
function session() {
    return {sessionId: 'session-1', version: 1, status: 'ACTIVE', titleVi: '<script>unsafe</script>', specification: {settingVi: 'Cửa hàng', goalVi: 'Đặt bánh', characters: []},
        activities: [{activityId: 'activity-1', ordinal: 1, status: 'READY', plays: 0, transcriptOpened: false, hintOpened: false,
            segments: [{segmentId: 's1', speakerId: 'a'}], questions: [{questionId: 'q1', promptVi: 'Mở cửa lúc nào?'}, {questionId: 'q2', promptVi: 'Khách cần gì?'}], attempts: []}]};
}

function graded(status = 'CAPTURED', support = {}) {
    return {attemptId: 'attempt-1', ordinal: 1, status: 'GRADED', support,
        answers: [{questionId: 'q1', textVi: 'At nine'}, {questionId: 'q2', textVi: 'Bánh'}],
        result: {facts: [{questionId: 'q1', status, evidenceVi: 'At nine'}, {questionId: 'q2', status, evidenceVi: 'Bánh'}]}};
}

test('completion opens a persisted answer review before the next activity and supports replay', async () => {
    const s = session(); s.activities[0].attempts = [graded()];
    s.activities.push({...structuredClone(s.activities[0]), activityId: 'activity-2', ordinal: 2, attempts: []});
    const storage = new Map();
    const api = async (url, options = {}) => {
        if (url.endsWith('/complete')) {
            s.version++; s.activities[0].status = 'COMPLETED';
            s.activities[0].questions[0].answerVi = 'Chín giờ';
            s.activities[0].questions[0].evidence = [{segmentId: 's1', quoteEn: 'At nine.'}];
            return s;
        }
        if (url.endsWith('/play')) return {status: 'READY', mediaId: 'cached-media'};
        return s;
    };
    const h = harness(api, storage); await h.module.show(s.sessionId);
    h.node('#scenario-complete').listeners.click(); await tick(); await tick();
    assert.match(h.root.innerHTML, /Chữa hoạt động 1/);
    assert.match(h.root.innerHTML, /Đáp án: Chín giờ/);
    assert.match(h.root.innerHTML, /Bằng chứng ở đoạn s1/);
    assert.doesNotMatch(h.root.innerHTML, /id="scenario-answer"/);
    const reloaded = harness(api, storage); await reloaded.module.show(s.sessionId);
    assert.match(reloaded.root.innerHTML, /Chữa hoạt động 1/);
    reloaded.node('data-play:0').listeners.click(); await tick(); await tick();
    assert.match(reloaded.node('#scenario-audio').src, /cached-media$/);
    reloaded.node('#scenario-next').listeners.click(); await tick();
    assert.match(reloaded.root.innerHTML, /Hoạt động 2 \/ 2/);
    assert.doesNotMatch(reloaded.root.innerHTML, /Đáp án: Chín giờ/);
});

test('failed grading returns to saved answers and retry uses the same attempt endpoint', async () => {
    const s = session(); s.activities[0].attempts = [{...graded(), status: 'FAILED', result: null,
        gradeRetry: {allowed: true, remaining: 2, operationId: 'failed-op', errorCode: 'AI_JOB_FAILED'}}];
    const storage = new Map([['dictalab:scenario:10:operation', JSON.stringify({operationId: 'failed-op', action: 'GRADE', sessionId: s.sessionId})]]);
    const h = harness(async (url, options = {}) => {
        if (url.endsWith('/retry-grade')) return {operationId: 'retry-op', stage: 'GRADE', status: 'PENDING'};
        if (url.endsWith('/operations/retry-op')) return {status: 'RUNNING'};
        if (url.endsWith('/operations/failed-op')) return {status: 'FAILED', errorCode: 'AI_JOB_FAILED'};
        return s;
    }, storage);
    await h.module.show(s.sessionId);
    assert.match(h.root.innerHTML, /At nine/);
    assert.match(h.root.innerHTML, /Thử chấm lại câu trả lời đã lưu/);
    h.node('data-retry-grade:0').listeners.click(); await tick(); await tick();
    const post = h.calls.find(c => c.options.method === 'POST');
    assert.match(post.url, /activities\/activity-1\/attempts\/attempt-1\/retry-grade$/);
    assert.deepEqual(JSON.parse(JSON.stringify(post.options.body)), {expectedVersion: 1});
    assert.equal(h.calls.filter(c => c.options.method === 'POST').length, 1);
});

test('unknown grading preserves the lesson without offering a fresh paid retry', async () => {
    const s = session(); s.activities[0].attempts = [{...graded(), status: 'UNKNOWN', result: null, gradeRetry: {allowed: false, operationId: 'uncertain'}}];
    const storage = new Map([['dictalab:scenario:10:operation', JSON.stringify({operationId: 'uncertain', action: 'GRADE', sessionId: s.sessionId})]]);
    const h = harness(async url => url.includes('/operations/') ? {status: 'UNKNOWN'} : s, storage);
    await h.module.show(s.sessionId);
    assert.match(h.root.innerHTML, /At nine/);
    assert.doesNotMatch(h.root.innerHTML, /data-retry-grade=/);
    assert.equal(h.calls.filter(c => c.options.method === 'POST').length, 0);
});

test('playback separates autoplay and decode errors and ignores late errors after navigation', async () => {
    const s = session(); s.specification.characters = [{id: 'a', name: 'Alice', roleVi: 'Khách'}];
    const h = harness(async url => url.endsWith('/play') ? {status: 'READY', mediaId: 'cached'} : s);
    await h.module.show(s.sessionId);
    assert.match(h.root.innerHTML, /Nghe đoạn s1 · Alice/);
    h.node('#scenario-audio').play = async () => {const error = new Error('blocked'); error.name = 'NotAllowedError'; throw error;};
    h.node('data-play:0').listeners.click(); await tick(); await tick();
    assert.match(h.node('#scenario-status').textContent, /chặn tự phát/);
    h.node('#scenario-audio').listeners.error();
    assert.match(h.node('#scenario-status').textContent, /Không tải hoặc giải mã/);
    const lateError = h.node('#scenario-audio').listeners.error;
    h.module.leave(); h.node('#scenario-status').textContent = 'new page'; lateError();
    assert.equal(h.node('#scenario-status').textContent, 'new page');
});

test('summary retains missing first grades in the denominator and identifies assistance', async () => {
    const s = session(); s.status = 'COMPLETED';
    s.activities = [0, 1, 2].map(i => ({...structuredClone(s.activities[0]), status: 'COMPLETED', activityId: `a${i}`, ordinal: i+1,
        attempts: [graded(i === 1 ? 'UNCERTAIN' : 'CAPTURED', i === 1 ? {transcriptOpened: true} : {})]}));
    s.activities[0].attempts.unshift({...graded(), status: 'FAILED', result: null});
    const h = harness(async () => s); await h.module.show(s.sessionId);
    assert.match(h.root.innerHTML, /Lần trả lời đầu:.*có kết quả 4\/6 câu; 2 câu chưa chấm được/);
    assert.match(h.root.innerHTML, /Lần chấm thành công mới nhất:.*có kết quả 6\/6 câu/);
    assert.match(h.root.innerHTML, /1 hoạt động có dùng hỗ trợ/);
    assert.match(h.root.innerHTML, /2 chưa đủ căn cứ/);
    h.node('data-review:0').listeners.click(); await tick();
    assert.match(h.root.innerHTML, /id="scenario-audio"/);
    assert.match(h.root.innerHTML, /Xem tổng kết/);
});
test('session renders real question counts, escapes provider text and shows no fabricated score', async () => {
    const h = harness(async () => session()); await h.module.show('session-1');
    assert.match(h.root.innerHTML, /Hoạt động 1 \/ 1/);
    assert.match(h.root.innerHTML, /&lt;script&gt;unsafe/);
    assert.doesNotMatch(h.root.innerHTML, /<script>|72%|65%|80%|5 câu hỏi/);
    assert.match(h.root.innerHTML, /Mở cửa lúc nào/);
    assert.match(h.root.innerHTML, /tiếng Anh hoặc tiếng Việt/);
    assert.match(h.root.innerHTML, /aria-describedby="scenario-answer-help"/);
});
test('leaving or changing account prevents late provider responses from painting', async () => {
    let resolve; const h = harness(() => new Promise(done => {resolve = done;}));
    const pending = h.module.show('session-1'); h.module.leave(); const html = h.root.innerHTML;
    resolve(session()); await pending; assert.equal(h.root.innerHTML, html);
    const pending2 = h.module.show('session-1'); h.setUser(20); const html2 = h.root.innerHTML;
    resolve(session()); await pending2; assert.equal(h.root.innerHTML, html2);
});
test('answer transport retry after reload keeps idempotency key and Vietnamese draft', async () => {
    const storage = new Map(); let fail = true;
    const api = async (url, options) => {
        if (options.method === 'POST') {if (fail) throw new Error('Connection lost'); return {operationId: 'op', status: 'PENDING'};}
        if (url.includes('/operations/')) return {status: 'RUNNING', pollAfterMs: 2000};
        return session();
    };
    const first = harness(api, storage); await first.module.show('session-1');
    const form = first.node('#scenario-answer'); form.values = [['q1', 'At nine'], ['q2', 'Bánh']];
    form.listeners.submit({preventDefault() {}}); await tick(); await tick();
    const original = first.calls.find(c => c.options.method === 'POST'); assert.ok(original);
    fail = false; const next = harness(api, storage); await next.module.show('session-1');
    assert.match(next.root.innerHTML, /At nine/);
    const form2 = next.node('#scenario-answer'); form2.values = form.values; form2.listeners.submit({preventDefault() {}});
    await tick(); await tick();
    const retried = next.calls.find(c => c.options.method === 'POST');
    assert.equal(retried.options.headers['Idempotency-Key'], original.options.headers['Idempotency-Key']);
    assert.deepEqual(JSON.parse(JSON.stringify(retried.options.body.answers)), [{questionId: 'q1', textVi: 'At nine'}, {questionId: 'q2', textVi: 'Bánh'}]);
    assert.equal(JSON.parse(storage.get('dictalab:scenario:10:operation')).operationId, 'op');
});
test('unknown paid outcome does not submit another job or invent a completed session', async () => {
    const storage = new Map([['dictalab:scenario:10:operation', JSON.stringify({operationId: 'op', action: 'GENERATE'})]]);
    const h = harness(async () => ({status: 'UNKNOWN', errorCode: 'DEADLINE'}), storage); await h.module.show();
    assert.match(h.root.innerHTML, /không tự gọi lại/); assert.match(h.root.innerHTML, /UNKNOWN/);
    assert.equal(h.calls.length, 1); assert.equal(h.calls[0].options.method, undefined);
});

test('draft confirmation separates provided facts from labeled inferences and assumptions', async () => {
    const storage = new Map([['dictalab:scenario:10:operation', JSON.stringify({operationId: 'op', action: 'EXTRACT'})]]);
    const h = harness(async url => url.includes('/operations/')
        ? {status: 'SUCCEEDED', draftId: 'draft-1'}
        : {status: 'READY', draftId: 'draft-1', version: 1, specification: {
            titleVi: 'Hàng xóm', settingVi: 'Hành lang', learnerRoleVi: 'Người muốn làm quen', goalVi: 'Chào hỏi',
            constraintsVi: ['Hàng xóm mới <script>unsafe</script>'],
            assumptionsVi: ['Suy luận: Chưa quen nhau', 'Giả định: Gặp ở hành lang'],
            characters: [{name: 'An', roleVi: 'Người muốn làm quen'}, {name: 'Bình', roleVi: 'Hàng xóm mới'}]
        }}, storage);
    await h.module.show();
    assert.match(h.root.innerHTML, /Dữ kiện bạn cung cấp/);
    assert.match(h.root.innerHTML, /Suy luận và giả định cần xác nhận/);
    assert.match(h.root.innerHTML, /Suy luận: Chưa quen nhau/);
    assert.match(h.root.innerHTML, /&lt;script&gt;unsafe&lt;\/script&gt;/);
    assert.doesNotMatch(h.root.innerHTML, /<script>/);
    assert.equal(h.calls.filter(call => call.options.method === 'POST').length, 0);
});

test('unified entry sends edited Vietnamese brief to v2 and keeps legacy history accessible', async () => {
    const h = harness(async (url, options = {}) => {
        if (options.method === 'POST') return {operationId: 'new-op', status: 'PENDING', stage: 'EXTRACT'};
        if (url.includes('/operations/')) return {status: 'RUNNING'};
        if (url.endsWith('/catalog')) return {enabled: true, generationAvailable: true, generationMode: 'LOCAL_PREVIEW', generationPrograms: [{code: 'general-communication', version: 1}], difficultyProfiles: ['supported','standard','challenge']};
        if (url.endsWith('/suggestions')) return {suggestions: [{id: 'cafe', groupId: 'food', groupTitleVi: 'Ăn uống', titleVi: 'Gọi món', inputVi: 'Tôi gọi món'}, {id:'train',groupId:'transport',groupTitleVi:'Đi lại',titleVi:'Đi tàu',inputVi:'Tôi đi tàu'}]};
        if (url.endsWith('/recommendations')) return {messageVi: 'Gợi ý', suggestions: []};
        if (url.endsWith('/learning-goals')) return [];
        if (url.startsWith('/api/v1/')) return {sessions: [{id:'legacy-id',scenario:'cafe',difficulty:'A1'}],hasMore:false};
        return {sessions: []};
    });
    await h.module.show();
    assert.match(h.root.innerHTML,/LOCAL|Thử nghiệm local/);
    assert.match(h.root.innerHTML,/\/conversation\/legacy-id/);
    assert.doesNotMatch(h.root.innerHTML,/href="\/scenario|value="A1"/);
    const brief='Tôi muốn báo mất hành lý ở ga tàu, trao đổi thời gian nhận lại.';
    h.node('#scenario-input').value=brief;
    h.node('data-group:1').listeners.click();
    assert.equal(h.node('#scenario-input').value,brief,'filtering never erases a custom brief');
    const form=h.node('#scenario-create'); form.values=[['inputVi',brief],['profileId','standard']];
    form.listeners.submit({preventDefault() {},currentTarget:form}); await tick(); await tick();
    const submitted=h.calls.find(c=>c.options?.method==='POST');
    assert.equal(submitted.url,'/api/v2/scenarios/drafts');
    assert.equal(submitted.options.body.inputVi,brief);
    assert.equal(submitted.options.body.profileId,'standard');
    assert.equal(submitted.options.body.scenario,undefined);
});

test('conversation delegates new creation and only falls back to v2 sessions after a genuine v1 404', async () => {
    const called=[], root={innerHTML:'',querySelector:()=>({addEventListener(){}})};
    const window={DictaLabScenario:{leave(){},async show(id){called.push(id || 'catalog');}},DictaLabListeningBridge:{stopRecordedAudio(){},async api(){throw Object.assign(new Error('missing'),{status:404});}}};
    const legacySource=fs.readFileSync(require('node:path').resolve(__dirname,'../../main/resources/static/js/conversation.js'),'utf8');
    vm.runInNewContext(legacySource,{window,document:{querySelector:()=>root},AbortController});
    await window.DictaLabConversation.show(); await window.DictaLabConversation.show('v2-session');
    assert.deepEqual(called,['catalog','v2-session']);
    window.DictaLabListeningBridge.api=async()=>{throw Object.assign(new Error('unavailable'),{status:503});};
    await window.DictaLabConversation.show('error-session');
    assert.deepEqual(called,['catalog','v2-session']);
});

test('navigation exposes one conversation feature and keeps scenario only as a compatibility alias', () => {
    const index=fs.readFileSync(require('node:path').resolve(__dirname,'../../main/resources/static/index.html'),'utf8');
    assert.doesNotMatch(index,/data-route="scenario"|id="scenario-view"/);
    assert.match(index,/data-route="conversation"/);
    assert.match(source,/#conversation-content/);
});

function personalizedApi(preferences = [], code = 'ielts-listening', version = 2) {
    const program = JSON.parse(fs.readFileSync(require('node:path').resolve(__dirname,'../../main/resources/scenario/programs-personalized-v1.json'),'utf8')).find(p => p.code === code && p.version === version);
    return async (url, options = {}) => {
        if (options.method === 'POST') {
            if (url.endsWith('/learning-preferences')) {
                preferences = [{...options.body, version: options.body.expectedVersion + 1}]; return preferences[0];
            }
            return {operationId:'personal-op',status:'PENDING'};
        }
        if (url.endsWith('/catalog')) return {enabled:true,generationAvailable:true,generationMode:'LOCAL_PREVIEW',practicePrograms:[{...program,generationAvailable:true}]};
        if (url.endsWith('/learning-preferences')) return {preferences};
        if (url.endsWith('/learning-goals')) return [];
        if (url.endsWith('/suggestions')) return {suggestions:[]};
        if (url.endsWith('/recommendations')) return {suggestions:[],messageVi:''};
        if (url.includes('/operations/')) return {status:'RUNNING'};
        return {sessions:[]};
    };
}

test('personalization only asks for target and uses main idea with no current level', async () => {
    const h=harness(personalizedApi()); await h.module.show();
    assert.match(h.root.innerHTML,/Mục tiêu luyện nghe/);
    const create=h.node('#scenario-create'); create.values=[['inputVi','']];
    create.listeners.submit({preventDefault(){},currentTarget:create}); await tick();
    assert.equal(h.calls.filter(c=>c.options?.method==='POST').length,0);
    assert.doesNotMatch(h.node('#scenario-personalization-fields').innerHTML,/scenario-current|scenario-focus|Mức hiện tại|Bạn muốn cải thiện kỹ năng nào/);
    const form=h.node('#scenario-personalization'); form.values=[['targetValue','7.5']];
    form.listeners.submit({preventDefault(){},currentTarget:form}); await tick(); await tick();
    const saved=h.calls.find(c=>c.url.endsWith('/learning-preferences') && c.options?.method==='POST');
    assert.deepEqual(JSON.parse(JSON.stringify(saved.options.body)),{programCode:'ielts-listening',programVersion:2,target:{scaleId:'ielts-listening-band',component:'LISTENING',value:'7.5'},currentValue:'',focusSkill:'main-idea',expectedVersion:0});
    create.listeners.submit({preventDefault(){},currentTarget:create}); await tick(); await tick();
    const submitted=h.calls.find(c=>c.url.endsWith('/drafts'));
    assert.equal(submitted.options.body.inputVi,''); assert.equal(submitted.options.body.programVersion,2);
    assert.equal(submitted.options.body.profileId,'');
});

test('communication offers and persists all six CEFR levels while upgrading an older preference', async () => {
    const previous={programCode:'general-communication',programVersion:2,target:{scaleId:'practice-support',value:'standard'},currentValue:'supported',focusSkill:'detail',version:5};
    const h=harness(personalizedApi([previous],'general-communication',3)); await h.module.show();
    const html=h.node('#scenario-personalization-fields').innerHTML;
    assert.match(html,/Mức giao tiếp mong muốn \(CEFR\)/);
    assert.doesNotMatch(html,/scenario-current|scenario-focus|value="standard"/);
    for (const level of ['a1','a2','b1','b2','c1','c2']) {
        assert.ok(html.includes(`value="${level}"`)); assert.ok(html.includes(`>${level.toUpperCase()}</option>`));
        const form=h.node('#scenario-personalization'); form.values=[['targetValue',level]];
        form.listeners.submit({preventDefault(){},currentTarget:form}); await tick(); await tick();
    }
    const saved=h.calls.filter(c=>c.url.endsWith('/learning-preferences') && c.options?.method==='POST');
    assert.equal(saved[0].options.body.expectedVersion,5);
    assert.deepEqual(saved.map(c=>c.options.body.target.value),['a1','a2','b1','b2','c1','c2']);
    assert.ok(saved.every(c=>c.options.body.target.scaleId==='cefr-listening' && c.options.body.programVersion===3 && c.options.body.currentValue==='' && c.options.body.focusSkill==='main-idea'));
});

test('unsaved personalization changes block generation with the previous saved target', async () => {
    const h=harness(personalizedApi([{programCode:'ielts-listening',programVersion:2,target:{value:'6.5'},currentValue:'',focusSkill:'detail',version:1}]));
    await h.module.show(); h.node('#scenario-personalization').listeners.input();
    assert.equal(h.node('#scenario-create-button').disabled,true);
    const form=h.node('#scenario-create'); form.values=[['inputVi','']];
    form.listeners.submit({preventDefault(){},currentTarget:form}); await tick();
    assert.equal(h.calls.filter(c=>c.options?.method==='POST').length,0);
    assert.match(h.node('#scenario-status').textContent,/Hãy lưu mục tiêu/);
});
