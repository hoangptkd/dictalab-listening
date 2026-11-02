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
        if (!nodes.has(key)) nodes.set(key, {listeners: {}, value: '', textContent: '', addEventListener(name, fn) {this.listeners[name] = fn;}, setAttribute() {}, focus() {}, pause() {}, load() {}, removeAttribute() {}});
        return nodes.get(key);
    }
    const root = {innerHTML: '', setAttribute() {}, removeAttribute() {},
        querySelector(selector) {
            if (selector === 'audio') return null;
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

function personalizedApi(preferences = []) {
    const program = JSON.parse(fs.readFileSync(require('node:path').resolve(__dirname,'../../main/resources/scenario/programs-personalized-v1.json'),'utf8')).find(p => p.code === 'ielts-listening');
    return async (url, options = {}) => {
        if (options.method === 'POST') {
            if (url.endsWith('/learning-preferences')) {
                preferences = [{...options.body, version: 1}]; return preferences[0];
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

test('personalization persists Listening band and unknown current level before allowing a blank brief', async () => {
    const h=harness(personalizedApi()); await h.module.show();
    assert.match(h.root.innerHTML,/Mục tiêu luyện nghe/);
    const create=h.node('#scenario-create'); create.values=[['inputVi','']];
    create.listeners.submit({preventDefault(){},currentTarget:create}); await tick();
    assert.equal(h.calls.filter(c=>c.options?.method==='POST').length,0);
    const form=h.node('#scenario-personalization'); form.values=[['targetValue','7.5'],['currentValue',''],['focusSkill','reason']];
    form.listeners.submit({preventDefault(){},currentTarget:form}); await tick(); await tick();
    const saved=h.calls.find(c=>c.url.endsWith('/learning-preferences') && c.options?.method==='POST');
    assert.deepEqual(JSON.parse(JSON.stringify(saved.options.body)),{programCode:'ielts-listening',programVersion:2,target:{scaleId:'ielts-listening-band',component:'LISTENING',value:'7.5'},currentValue:'',focusSkill:'reason',expectedVersion:0});
    create.listeners.submit({preventDefault(){},currentTarget:create}); await tick(); await tick();
    const submitted=h.calls.find(c=>c.url.endsWith('/drafts'));
    assert.equal(submitted.options.body.inputVi,''); assert.equal(submitted.options.body.programVersion,2);
    assert.equal(submitted.options.body.profileId,'');
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
