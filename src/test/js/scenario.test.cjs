const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const crypto = require('node:crypto');
const source = fs.readFileSync(require('node:path').resolve(__dirname, '../../main/resources/static/js/scenario.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));

function harness(api, storage = new Map(), environment = {}) {
    const nodes = new Map(), calls = [], timers = []; let focused = null;
    function node(key) {
        if (!nodes.has(key)) nodes.set(key, {id: key.startsWith('#') ? key.slice(1) : '', listeners: {}, events: {}, value: '', textContent: '',
            addEventListener(name, fn, options = {}) {
                (this.events[name] ||= []).push({fn, once: options.once});
                this.listeners[name] = event => { for (const entry of [...this.events[name]]) { if (entry.once) this.removeEventListener(name, entry.fn); entry.fn(event || {type: name}); } };
            },
            removeEventListener(name, fn) {this.events[name] = (this.events[name] || []).filter(e => e.fn !== fn);},
            dataset: {}, hasAttribute() {return false;}, setAttribute() {}, focus() {focused = key;}, async play() {this.paused = false; this.listeners.playing?.({type:'playing'});},
            pause() {this.paused = true; this.listeners.pause?.({type:'pause'});}, load() {}, removeAttribute(name) {delete this[name];}});
        return nodes.get(key);
    }
    const root = {innerHTML: '', setAttribute() {}, removeAttribute() {},
        querySelector(selector) {
            if (selector === 'audio') return this.innerHTML.includes('id="scenario-audio"') ? node('#scenario-audio') : null;
            if (selector === '[data-edit-preview]') return this.innerHTML.includes('data-edit-preview') ? node('data-session:0') : null;
            if (selector.startsWith('#') && (this.innerHTML.includes(`id="${selector.slice(1)}"`) || [...nodes.values()].some(n => n.innerHTML?.includes(`id="${selector.slice(1)}"`)))) return node(selector);
            return null;
        },
        querySelectorAll(selector) {
            if (selector === 'button') return [...this.innerHTML.matchAll(/<button\b([^>]*)>/g)].map(m => {
                const id = m[1].match(/id="([^"]+)"/); const el = node(id ? '#'+id[1] : 'button:'+m.index);
                if (!el.dataset.busyDisabled) el.disabled = /\bdisabled\b/.test(m[1]); return el;
            });
            const attr = selector.slice(1, -1);
            return [...this.innerHTML.matchAll(new RegExp(`${attr}="([^"]*)"`, 'g'))].map((match, index) => {
                const item = node(`${attr}:${index}`);
                item.hasAttribute = name => this.innerHTML.slice(this.innerHTML.lastIndexOf('<', match.index), this.innerHTML.indexOf('>', match.index)).includes(name);
                item.dataset = {[attr.replace('data-', '').replace(/-([a-z])/g, (_, c) => c.toUpperCase())]: match[1]}; return item;
            });
        }};
    let user = {id: 10};
    const window = {DictaLabListeningBridge: {currentUser: () => user, navigate() {},
        async api(url, options) {calls.push({url, options}); return api(url, options);}}};
    vm.runInNewContext(source, {window, document: {querySelector: () => root}, AbortController, crypto,
        fetch: environment.fetch, URL: environment.URL, Audio: environment.Audio,
        setTimeout: fn => {timers.push(fn); return timers.length;}, clearTimeout() {},
        sessionStorage: {getItem: key => storage.get(key), setItem: (key, value) => {if (environment.rejectWrites) throw new Error('storage blocked'); storage.set(key, value);}, removeItem: key => {if (environment.rejectWrites) throw new Error('storage blocked'); storage.delete(key);}},
        FormData: class extends Map {constructor(form) {super(form.values || []);}}});
    return {module: window.DictaLabScenario, root, nodes, calls, storage, timers, focused: () => focused, setUser: id => {user = {id};}, node};
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
        if (url.includes('/prepare')) return {status: 'READY', mediaId: 'cached-media'};
        return s;
    };
    const h = harness(api, storage); await h.module.show(s.sessionId);
    h.node('#scenario-complete').listeners.click(); await tick(); await tick();
    assert.match(h.root.innerHTML, /Chữa hoạt động 1/);
    assert.match(h.root.innerHTML, /Đáp án: Chín giờ/);
    assert.match(h.root.innerHTML, /Bằng chứng ở đoạn 1/);
    assert.doesNotMatch(h.root.innerHTML, /id="scenario-answer"/);
    const reloaded = harness(api, storage); await reloaded.module.show(s.sessionId);
    assert.match(reloaded.root.innerHTML, /Chữa hoạt động 1/);
    reloaded.node('data-play:0').listeners.click(); await tick(); await tick();
    assert.match(reloaded.node('#scenario-audio').src, /cached-media$/);
    reloaded.node('#scenario-audio').ended = true;
    reloaded.node('#scenario-audio').listeners.ended(); await tick();
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
    assert.match(h.node('#scenario-status').textContent, /Chưa chấm được câu trả lời đã lưu/);
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
    const h = harness(async url => url.includes('/prepare') ? {status: 'READY', mediaId: 'cached'} : s);
    await h.module.show(s.sessionId);
    assert.match(h.root.innerHTML, /Đoạn 1 · Alice/);
    h.node('#scenario-audio').play = async () => {const error = new Error('blocked'); error.name = 'NotAllowedError'; throw error;};
    h.node('data-play:0').listeners.click(); await tick(); await tick();
    assert.match(h.node('#scenario-status').textContent, /chặn tự phát/);
    assert.equal(h.calls.filter(c=>c.url.endsWith('/played')).length,0);
    const lateError = h.node('#scenario-audio').events.error[0].fn;
    h.node('#scenario-audio').listeners.error();
    assert.match(h.node('#scenario-status').textContent, /Không tải hoặc giải mã/);
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
    assert.match(h.root.innerHTML, /0 hoạt động dùng gợi ý chiến lược; 1 hoạt động mở bản chép/);
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
    assert.match(h.root.innerHTML, /không tự gọi lại/); assert.match(h.root.innerHTML, /Chưa xác định được kết quả/);
    assert.equal(h.calls.length, 1); assert.equal(h.calls[0].options.method, undefined);
});
test('provider overload is explained without replaying an unknown job', async () => {
    const storage = new Map([['dictalab:scenario:10:operation', JSON.stringify({operationId: 'op', action: 'EXTRACT'})]]);
    const h = harness(async () => ({status: 'UNKNOWN', errorCode: 'AI_PROVIDER_UNAVAILABLE'}), storage);
    await h.module.show();
    assert.match(h.root.innerHTML, /Nhà cung cấp AI tạm quá tải/);
    assert.match(h.root.innerHTML, /lỗi máy chủ/);
    assert.match(h.root.innerHTML, /không tự gửi lại/);
    assert.equal(h.calls.length, 1); assert.equal(h.calls[0].options.method, undefined);
});

test('draft confirmation separates provided facts from labeled inferences and assumptions', async () => {
    const storage = new Map([['dictalab:scenario:10:operation', JSON.stringify({operationId: 'op', action: 'EXTRACT'})]]);
    const h = harness(async url => url.includes('/operations/')
        ? {status: 'SUCCEEDED', draftId: 'draft-1'}
        : {status: 'READY', draftId: 'draft-1', version: 1, inputVi: 'Làm quen hàng xóm', programCode: 'general-communication', specification: {
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
    assert.match(h.root.innerHTML, /Sửa mô tả/);
    h.node('data-session:0').listeners.click({preventDefault(){}});
    assert.equal(JSON.parse(storage.get('dictalab:scenario:10:brief')),'Làm quen hàng xóm');
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

test('play-all prepares only the selected activity and waits for a user gesture when autoplay is blocked', async () => {
    const s = session(); s.activities[0].segments.push({segmentId: 's2', speakerId: 'a'}, {segmentId: 's3', speakerId: 'a'});
    let n = 0;
    const h = harness(async url => url.includes('/prepare') ? {status: 'READY', mediaId: 'cached-' + (++n)} : s);
    await h.module.show(s.sessionId);
    const audio = h.node('#scenario-audio');
    audio.paused = true;
    audio.play = async () => { const e = new Error('blocked'); e.name = 'NotAllowedError'; throw e; };
    h.node('#scenario-play-all').listeners.click(); await tick(); await tick(); await tick();
    assert.equal(n, 3); // One explicit play-all request covers all three selected segments.
    assert.match(h.node('#scenario-status').textContent, /chặn tự phát/);
    assert.equal(h.node('#scenario-audio').events.ended.length, 2); // Native Play can resume this sequence.
});

test('draft restores preview on catalog route after remix extract', async () => {
    const draft = {draftId: 'remix-draft', version: 1, status: 'READY', specification: {titleVi: 'Remix', settingVi: 'Office', learnerRoleVi: 'Listener', goalVi: 'Listen', constraintsVi: [], assumptionsVi: [], characters: []}};
    const storage = new Map([['dictalab:scenario:10:draft', JSON.stringify({draftId: draft.draftId})]]);
    const h = harness(async () => draft, storage);
    // Catalog route (no id) with saved draft → show preview without a new EXTRACT
    await h.module.show();
    assert.match(h.root.innerHTML, /scenario-confirm/);
    assert.match(h.root.innerHTML, /Remix/);
    // No POST requests (no new EXTRACT)
    assert.equal(h.calls.filter(c => c.options?.method === 'POST').length, 0);
    const s = session();
    const storage2 = new Map([['dictalab:scenario:10:draft', JSON.stringify({draftId: draft.draftId, sourceSessionId: s.sessionId})]]);
    const h2 = harness(async url => url.includes('/drafts/') ? draft : s, storage2);
    await h2.module.show(s.sessionId);
    assert.match(h2.root.innerHTML, /scenario-confirm/);
    const h3 = harness(async () => s, storage2); await h3.module.show('unrelated');
    assert.doesNotMatch(h3.root.innerHTML, /scenario-confirm/);
});

for (const basis of ['COLD_START','RECENT_UNAIDED_FIRST_ATTEMPTS']) test(`generic duplicate recommendations are hidden for ${basis} when there is no personal reason`, async () => {
    const suggestion = {id: 'cafe', groupId: 'food', groupTitleVi: 'Food', titleVi: 'Order coffee', inputVi: 'Order coffee'};
    const h = harness(async url => {
        if (url.endsWith('/catalog')) return {enabled: true, generationAvailable: true, difficultyProfiles: ['b1'], practicePrograms: []};
        if (url.endsWith('/suggestions')) return {suggestions: [suggestion]};
        if (url.endsWith('/recommendations')) return {basis, messageVi: 'Not enough history', suggestions: [suggestion]};
        if (url.endsWith('/learning-goals')) return [];
        if (url.endsWith('/learning-preferences')) return {preferences: []};
        return {sessions: []};
    });
    await h.module.show();
    // Recommendation aside should be hidden (all items overlap with explorer)
    assert.doesNotMatch(h.root.innerHTML, /data-recommendation="0"/);
    // Explorer should still show
    assert.match(h.root.innerHTML, /data-suggestion="0"/);
});

test('grading result shows correction cue for PARTIAL and INCORRECT facts', async () => {
    const s = session();
    s.activities[0].attempts = [{
        ordinal: 1, attemptId: 'a1', status: 'GRADED',
        support: {transcriptOpened: false, hintOpened: false},
        answers: [{questionId: 'q1', textVi: 'Some answer'}],
        result: {facts: [{questionId: 'q1', status: 'PARTIAL', evidenceVi: 'Some answer'}]}
    }];
    const h = harness(async () => s);
    await h.module.show(s.sessionId);
    assert.match(h.root.innerHTML, /Hiểu một phần/);
    assert.match(h.root.innerHTML, /Hãy nghe lại/);
    assert.match(h.root.innerHTML, /Câu 1:/);
});

test('play-all continues through queued and cached segments, refreshing versions without resubmission', async () => {
    const s = session(); s.activities[0].segments.push({segmentId:'s2'}, {segmentId:'s3'});
    let checks = 0;
    const h = harness(async (url, options = {}) => {
        if (url.includes('/prepare')) {
            const id = options.body.segmentId;
            return id === 's1' ? {operationId:'queued', stage:'TTS', status:'PENDING'} : {status:'READY', mediaId:id};
        }
        if (url.endsWith('/operations/queued')) return ++checks === 1 ? {operationId:'queued',status:'RUNNING'} : {operationId:'queued',status:'SUCCEEDED', mediaId:'s1'};
        return s;
    });
    await h.module.show(s.sessionId);
    h.node('#scenario-play-all').listeners.click(); await tick(); await tick();
    assert.equal(h.calls.filter(c => c.url.includes('/prepare')).length, 3);
    for (let i=0; i<8 && !h.node('#scenario-audio').src; i++) {
        h.timers.shift()?.(); await tick(); await tick();
    }
    assert.equal(h.calls.filter(c => c.url.includes('/prepare')).length, 3);
    for (let i=0; i<3; i++) {
        assert.match(h.node('#scenario-audio').src, new RegExp(`s${i+1}$`));
        h.node('#scenario-audio').ended = true;
        h.node('#scenario-audio').listeners.pause();
        h.node('#scenario-audio').listeners.ended(); await tick();
        h.node('#scenario-audio').ended = false;
    }
    const plays = h.calls.filter(c => c.url.includes('/prepare'));
    assert.deepEqual(plays.map(c => c.options.body.segmentId), ['s1','s2','s3']);
    assert.deepEqual(plays.map(c => c.options.body.expectedVersion), [1,1,1]);
    assert.match(h.node('#scenario-status').textContent, /Đã nghe xong hoạt động/);
    // Browser event dispatch may resume the sequence before its other ended listener runs.
    h.node('#scenario-audio').listeners.ended();
    assert.match(h.node('#scenario-status').textContent, /Đã nghe xong hoạt động/);
    assert.equal(h.node('#scenario-audio').events.pause.length, 1); // Media status listener remains, sequence listener is gone.
    assert.equal(h.storage.has('dictalab:scenario:10:operation'), false);
});

for (const interruption of ['pause','error','stop','leave']) test(`play-all handles ${interruption} within the user-selected activity`, async () => {
    const s=session(); s.activities[0].segments.push({segmentId:'s2'});
    const h=harness(async url => url.includes('/prepare') ? {status:'READY',mediaId:'s1'} : s);
    await h.module.show(s.sessionId); h.node('#scenario-play-all').listeners.click(); await tick();
    if (interruption === 'leave') h.module.leave();
    else if (interruption === 'stop') h.node('#scenario-stop-audio').listeners.click();
    else h.node('#scenario-audio').listeners[interruption]();
    await tick();
    assert.equal(h.calls.filter(c=>c.url.includes('/prepare')).length,2);
    assert.equal(h.node('#scenario-audio').events.ended.length,['stop','leave'].includes(interruption) ? 0 : interruption === 'pause' ? 2 : 1); // Pause retains the sequence; stop/error/leave end it.
    if(interruption==='error') assert.match(h.node('#scenario-status').textContent,/Không tải hoặc giải mã/);
});

test('stopping queued preparation clears intent and prevents late autoplay or resubmission', async () => {
    const s=session(), h=harness(async url=>url.includes('/prepare') ? {operationId:'queued',stage:'TTS',status:'PENDING'} : url.includes('/operations/') ? {status:'RUNNING'} : s);
    await h.module.show(s.sessionId); h.node('#scenario-play-all').listeners.click(); await tick();
    h.node('#scenario-stop-audio').listeners.click(); await tick();
    const before=h.calls.length; await h.timers.shift()(); await tick();
    assert.equal(h.calls.length,before);
    assert.equal(h.storage.has('dictalab:scenario:10:operation'),false);
    assert.equal(h.storage.has('dictalab:scenario:10:audioIntent'),false);
    assert.equal(h.node('#scenario-audio').src,undefined);
});

test('personalized recommendations with reused catalog IDs stay visible and propagate the actual skill', async () => {
    const pref={programCode:'ielts-listening',programVersion:2,target:{scaleId:'ielts-listening-band',component:'LISTENING',value:'6.5'},focusSkill:'detail',version:3};
    const original=personalizedApi([pref]);
    const suggestion={id:'office',inputVi:'Cuộc họp',titleVi:'Cuộc họp'};
    const h=harness(async (url, options={})=>{
        if(url.endsWith('/suggestions')) return {suggestions:[suggestion]};
        if(url.endsWith('/recommendations')) return {basis:'RECENT_UNAIDED_FIRST_ATTEMPTS',suggestions:[{...suggestion,inputVi:'Cuộc họp, hiểu lý do',focusSkill:'reason'}]};
        return original(url,options);
    });
    await h.module.show(); assert.match(h.root.innerHTML,/data-recommendation="0"/);
    assert.match(h.root.innerHTML, /<details class="scenario-personalization" >/);
    h.node('data-recommendation:0').listeners.click();
    const form=h.node('#scenario-create'); form.values=[['inputVi',h.node('#scenario-input').value]];
    form.listeners.submit({preventDefault(){},currentTarget:form}); await tick(); await tick();
    const posts=h.calls.filter(c=>c.options?.method==='POST');
    assert.equal(posts.length,1); assert.ok(posts[0].url.endsWith('/drafts'));
    assert.equal(posts[0].options.body.focusSkill,'reason'); assert.equal(posts[0].options.body.inputVi,'Cuộc họp, hiểu lý do');
});

test('active listening hides content goals and roles; correction uses safe backend cues with readable labels', async () => {
    const s=session(); s.specification.characters=[{id:'a',name:'Alice',roleVi:'SECRET_ROLE'}]; s.specification.goalVi='SECRET_GOAL';
    s.learningContext={programCode:'ielts-listening',target:{value:'6.5'},profile:{id:'standard'},focusSkill:'reason'};
    s.activities[0].attempts=[graded('PARTIAL')]; s.activities[0].attempts[0].result.facts[0].feedbackVi='Hãy nghe lại đoạn 2. Chú ý lý do.';
    const h=harness(async()=>s); await h.module.show(s.sessionId);
    assert.doesNotMatch(h.root.innerHTML,/SECRET_ROLE|SECRET_GOAL|ielts-listening/);
    assert.match(h.root.innerHTML,/IELTS Listening/); assert.match(h.root.innerHTML,/Hãy nghe lại đoạn 2/);
});

test('draft survives a lost confirmation response and return to saved brief', async () => {
    const draft={draftId:'draft',version:1,status:'READY',specification:{constraintsVi:[],assumptionsVi:[],characters:[]}};
    const storage=new Map([['dictalab:scenario:10:draft',JSON.stringify({draftId:'draft'})]]);
    const h=harness(async (url,options={})=>{ if(options.method==='POST') throw new Error('offline'); return draft; },storage);
    await h.module.show(); h.node('#scenario-confirm').listeners.click(); await tick();
    assert.equal(JSON.parse(storage.get('dictalab:scenario:10:draft')).draftId,'draft');
    h.node('data-session:0').listeners.click({preventDefault(){}});
    assert.equal(storage.has('dictalab:scenario:10:draft'),true);
});

test('stop cancels a late preparation response without counting a play', async () => {
    const s=session(); let respond;
    const h=harness(async url => url.includes('/prepare') ? await new Promise(resolve=>{respond=resolve;}) : s);
    await h.module.show(s.sessionId); h.node('data-play:0').listeners.click(); await tick();
    h.node('#scenario-stop-audio').listeners.click(); respond({status:'READY',mediaId:'late',sessionVersion:1}); await tick();
    assert.equal(h.node('#scenario-audio').src,undefined);
    h.node('data-play:0').listeners.click(); await tick();
    assert.equal(h.calls.filter(c=>c.url.includes('/prepare'))[1].options.body.expectedVersion,1);
    respond({status:'READY',mediaId:'second',sessionVersion:1}); await tick();
    assert.match(h.node('#scenario-audio').src,/second$/);
});

test('recommendations incompatible with the saved target profile are not offered', async () => {
    const pref={programCode:'general-communication',programVersion:3,target:{value:'a1'},version:1};
    const api=personalizedApi([pref],'general-communication',3);
    const h=harness(async (url,options)=>url.endsWith('/recommendations') ? {basis:'RECENT_UNAIDED_FIRST_ATTEMPTS',suggestions:[{id:'inference',focusSkill:'supported-inference',inputVi:'Suy luận'}]} : api(url,options));
    await h.module.show(); assert.doesNotMatch(h.root.innerHTML,/data-recommendation/);
});

test('recommendation focus survives reload along with its brief', async () => {
    const pref={programCode:'ielts-listening',programVersion:2,target:{value:'6.5'},version:1};
    const original=personalizedApi([pref]); const storage=new Map();
    const api=(url,options)=>url.endsWith('/recommendations') ? {suggestions:[{titleVi:'Họp',inputVi:'Hiểu lý do trong cuộc họp',focusSkill:'reason'}]} : original(url,options);
    const first=harness(api,storage); await first.module.show(); first.node('data-recommendation:0').listeners.click();
    const second=harness(api,storage); await second.module.show();
    const form=second.node('#scenario-create'); form.values=[['inputVi',second.node('#scenario-input').value]];
    form.listeners.submit({preventDefault(){},currentTarget:form}); await tick();
    assert.equal(second.calls.find(c=>c.options?.method==='POST').options.body.focusSkill,'reason');
});

test('raw remix survives refresh, review and failed extract', async () => {
    const s=session(); s.status='COMPLETED'; s.activities[0].status='COMPLETED';
    const storage=new Map(); const api=async(url,options={})=>options.method==='POST' ? {operationId:'bad',stage:'EXTRACT'} : url.includes('/operations/') ? {status:'FAILED'} : s;
    const first=harness(api,storage); await first.module.show(s.sessionId);
    first.node('#scenario-remix-input').value='Đổi thành đi xe buýt'; first.node('#scenario-remix-input').listeners.input();
    const next=harness(api,storage); await next.module.show(s.sessionId);
    assert.equal(next.node('#scenario-remix-input').value,'Đổi thành đi xe buýt');
    const form=next.node('#scenario-remix'); form.values=[['inputVi','Đổi thành đi xe buýt']];
    form.listeners.submit({preventDefault(){},currentTarget:form}); await tick(); await tick();
    assert.equal(JSON.parse(storage.get('dictalab:scenario:10:remix:session-1')),'Đổi thành đi xe buýt');
});

test('optional recommendation outage retains catalog and session load failure has GET-only retry', async () => {
    const original=personalizedApi(); const h=harness((url,opts)=>url.endsWith('/recommendations') ? Promise.reject(new Error('offline')) : original(url,opts));
    await h.module.show(); assert.match(h.root.innerHTML,/scenario-create/);
    let failed=true; const s=session(); const lesson=harness(async()=>{if(failed)throw new Error('offline');return s;});
    await lesson.module.show(s.sessionId); assert.match(lesson.root.innerHTML,/Thử tải lại/); assert.doesNotMatch(lesson.root.innerHTML,/Đang tải bài học/);
    failed=false; lesson.node('#scenario-load-retry').listeners.click(); await tick();
    assert.match(lesson.root.innerHTML,/scenario-answer/); assert.equal(lesson.calls.filter(c=>c.options?.method==='POST').length,0);
});

test('native single playback shows real ordinal, pause and ended without replacing answers', async () => {
    const s=session();s.activities[0].segments.push({segmentId:'s2',speakerId:'a'});
    const h=harness(async url=>url.includes('/prepare') ? {status:'READY',mediaId:'second'} : s);
    await h.module.show(s.sessionId); h.node('data-play:1').listeners.click(); await tick();
    assert.match(h.node('#scenario-status').textContent,/đoạn 2\/2/);
    h.node('#scenario-audio').listeners.pause(); assert.match(h.node('#scenario-status').textContent,/tạm dừng/);
    h.node('#scenario-audio').ended=true;h.node('#scenario-audio').listeners.ended(); assert.match(h.node('#scenario-status').textContent,/Đã nghe xong đoạn 2\/2/);
    assert.match(h.root.innerHTML,/scenario-answer/);
});

test('play-all locks playback buttons but keeps other actions and stop available', async () => {
    const s=session(); const h=harness(async url=>url.includes('/prepare') ? {status:'READY',mediaId:'one'} : s);
    await h.module.show(s.sessionId);h.node('#scenario-play-all').listeners.click(); await tick();
    assert.equal(h.node('#scenario-refresh').disabled,false); assert.notEqual(h.node('#scenario-stop-audio').disabled,true);
    h.node('#scenario-audio').listeners.pause(); await tick(); assert.equal(h.node('#scenario-refresh').disabled,false);
    h.node('#scenario-stop-audio').listeners.click(); await tick(); assert.equal(h.node('#scenario-refresh').disabled,false);
});

test('review brings focus to its heading and places answer, correction and source in a question card', async () => {
    const s=session();s.status='COMPLETED'; const a=s.activities[0];a.status='COMPLETED';a.attempts=[graded('INCORRECT')];
    a.questions[0].answerVi='Chín giờ';a.questions[0].evidence=[{segmentId:'s1',quoteEn:'At nine.'}];
    const h=harness(async()=>s); await h.module.show(s.sessionId);h.node('data-review:0').listeners.click(); await tick();
    assert.equal(h.focused(),'#scenario-step-heading');
    assert.match(h.root.innerHTML, /scenario-review-question[\s\S]*Bạn trả lời:[\s\S]*Chưa đúng[\s\S]*Đáp án: Chín giờ[\s\S]*Nghe bằng chứng/);
    assert.match(h.root.innerHTML,/Lịch sử trả lời và hỗ trợ/);
});

test('opened hint stays separate from playback messages on reload', async () => {
    const s=session();s.activities[0].hintOpened=true;s.activities[0].hintVi='Chú ý lý do.';
    const h=harness(async url=>url.includes('/prepare') ? {status:'READY',mediaId:'one'} : s);
    await h.module.show(s.sessionId);h.node('data-play:0').listeners.click();await tick();
    assert.match(h.root.innerHTML,/scenario-listening-hint[\s\S]*Chú ý lý do/);
    assert.match(h.node('#scenario-status').textContent,/Đang nghe/);
});

test('play-all buffers media and changes segments without a preparation or download barrier', async () => {
    const s=session(); s.activities[0].segments.push({segmentId:'s2',speakerId:'a'});
    let mediaGets=0, serial=0;
    const h=harness(async url=>url.includes('/prepare') ? {status:'READY',mediaId:`m${++serial}`} : s, new Map(), {
        fetch: async () => {mediaGets++; return {ok:true,blob:async()=>({type:'audio/wav',size:48})};},
        URL: {createObjectURL:()=>`blob:media-${++serial}`,revokeObjectURL() {}}
    });
    await h.module.show(s.sessionId); h.node('#scenario-play-all').listeners.click();
    for(let i=0;i<8 && !h.node('#scenario-audio').src;i++) await tick();
    assert.equal(mediaGets,2);
    assert.match(h.node('#scenario-audio').src,/^blob:/);
    const before=h.calls.length;
    const audio=h.node('#scenario-audio'); audio.ended=true; audio.listeners.ended();
    for(let i=0;i<5;i++) await tick();
    assert.equal(h.calls.slice(before).filter(c=>!c.url.endsWith('/played')).length,0);
    assert.equal(mediaGets,2);
    assert.match(audio.src,/^blob:/);
});

test('reopening a completed TTS operation does not autoplay audio', async () => {
    const s=session();
    const storage=new Map([['dictalab:scenario:10:operation',JSON.stringify({operationId:'tts-op',action:'TTS',sessionId:s.sessionId})]]);
    const h=harness(async url=>url.includes('/operations/') ? {status:'SUCCEEDED',mediaId:'m1'} : s,storage);
    await h.module.show(s.sessionId); h.node('#scenario-check').listeners.click(); await tick(); await tick();
    assert.equal(h.calls.filter(c=>c.url.includes('/prepare')).length,0);
    assert.equal(h.node('#scenario-audio').src,undefined);
    assert.match(h.node('#scenario-status').textContent,/Bấm nghe đoạn/);
});

test('a failed session restores its owner brief and explains the saved generation error', async () => {
    const s=session(); s.status='PREPARATION_FAILED'; s.activities=[]; s.inputVi='Tình huống bảo tàng';
    s.preparationError={operationId:'generation-op',status:'FAILED',errorCode:'AI_PROVIDER_UNAVAILABLE',stage:'GENERATE'};
    const h=harness(async url=>url.includes('/sessions/') ? s : personalizedApi()(url));
    await h.module.show(s.sessionId);
    assert.match(h.root.innerHTML,/Mã tác vụ: generation-op/);
    assert.match(h.root.innerHTML,/Sửa mô tả đã lưu/);
    h.node('data-session:1').listeners.click({preventDefault(){}});
    assert.equal(JSON.parse(h.storage.get('dictalab:scenario:10:brief')),'Tình huống bảo tàng');
});

test('new answer in memory wins over stale storage when only writes fail', async () => {
    const s=session(); const key='dictalab:scenario:10:answers:session-1:activity-1';
    const storage=new Map([[key,JSON.stringify({q1:'Câu cũ'})]]);
    const h=harness(async()=>s,storage,{rejectWrites:true}); await h.module.show(s.sessionId);
    const form=h.node('#scenario-answer'); form.values=[['q1','Câu mới'],['q2','Bánh']]; form.listeners.input();
    await h.module.show(s.sessionId);
    assert.match(h.root.innerHTML,/Câu mới/);
    assert.doesNotMatch(h.root.innerHTML,/Câu cũ/);
    assert.match(storage.get(key),/Câu cũ/);
});

test('pausing before play resolves keeps the selected sequence waiting for native resume', async () => {
    const s=session(); s.activities[0].segments.push({segmentId:'s2',speakerId:'a'});
    const h=harness(async url=>url.includes('/prepare') ? {status:'READY',mediaId:url.includes('/prepare') ? 'ready' : ''} : s);
    await h.module.show(s.sessionId);
    let rejectPlay; const audio=h.node('#scenario-audio');
    audio.play=()=>new Promise((_,reject)=>{rejectPlay=reject;});
    h.node('#scenario-play-all').listeners.click();
    for(let i=0;i<6 && !rejectPlay;i++) await tick();
    audio.pause(); const error=new Error('paused before play settled'); error.name='AbortError'; rejectPlay(error);
    await tick();
    assert.equal(audio.events.ended.length,2);
    assert.equal(h.node('#scenario-refresh').disabled,false);
    audio.play=async()=>{audio.paused=false; audio.listeners.playing?.();};
    await audio.play(); audio.ended=true; audio.listeners.ended();
    for(let i=0;i<5;i++) await tick();
    assert.match(audio.src,/ready$/);
    assert.equal(h.calls.filter(c=>c.url.includes('/prepare')).length,2);
});

test('rolling preparation downloads ready audio immediately with two workers and starts before the later buffer', async () => {
    const s=session(); s.activities[0].segments=[1,2,3,4,5].map(i=>({segmentId:`s${i}`,speakerId:'a'}));
    const downloads=[], pending=new Map(); let inflight=0, peak=0;
    const h=harness(async (url,options={})=> {
        if(url.includes('/prepare')) return {status:'READY',mediaId:options.body.segmentId,sessionVersion:s.version};
        if(url.endsWith('/played')) return {status:'READY',sessionVersion:s.version+1};
        return s;
    },new Map(),{
        fetch: async url=>{downloads.push(url); inflight++; peak=Math.max(peak,inflight);
            if(url.endsWith('s1') || url.endsWith('s2')) {inflight--;return {ok:true,blob:async()=>({type:'audio/wav',size:48})};}
            return new Promise(resolve=>pending.set(url.split('/').at(-1),()=>{inflight--;resolve({ok:true,blob:async()=>({type:'audio/wav',size:48})});}));},
        URL:{createObjectURL:()=>`blob:${downloads.length}`,revokeObjectURL(){}}
    });
    await h.module.show(s.sessionId); h.node('#scenario-play-all').listeners.click();
    for(let i=0;i<8;i++) await tick();
    assert.match(h.node('#scenario-status').textContent,/Đang nghe đoạn 1/);
    assert.equal(pending.size,2); assert.equal(peak,2);
    assert.equal(h.calls.filter(c=>c.url.includes('/prepare')).length,4);
    h.node('#scenario-stop-audio').listeners.click();
    for(const done of pending.values()) done();
    for(let i=0;i<5;i++) await tick();
    assert.equal(h.calls.filter(c=>c.url.includes('/prepare')).length,4); // Segment five was never admitted.
    assert.equal(h.calls.filter(c=>c.url.endsWith('/played')).length,1);
});

test('one observed media start is counted once and its acknowledgement drains before answering', async () => {
    const s=session(); let acknowledge;
    const h=harness(async (url,options={})=>{
        if(url.includes('/prepare')) return {status:'READY',mediaId:'one',sessionVersion:1};
        if(url.endsWith('/played')) return new Promise(resolve=>{acknowledge=resolve;});
        if(url.endsWith('/answers')) return {operationId:'grade',status:'PENDING'};
        if(url.includes('/operations/')) return {operationId:'grade',status:'RUNNING'};
        return s;
    });
    await h.module.show(s.sessionId); h.node('#scenario-play-all').listeners.click(); await tick();
    const audio=h.node('#scenario-audio'); audio.pause(); await audio.play(); await tick();
    assert.equal(h.calls.filter(c=>c.url.endsWith('/played')).length,1);
    const form=h.node('#scenario-answer');form.values=[['q1','Chín giờ'],['q2','Bánh']];form.listeners.submit({preventDefault(){}});
    await tick(); assert.equal(h.calls.filter(c=>c.url.endsWith('/answers')).length,0);
    acknowledge({status:'READY',sessionVersion:2}); for(let i=0;i<5;i++) await tick();
    assert.equal(h.calls.find(c=>c.url.endsWith('/answers')).options.body.expectedVersion,2);
    assert.match(h.root.innerHTML,/scenario-pending-grade/); assert.match(h.root.innerHTML,/Chín giờ/);
    assert.match(h.root.innerHTML,/id="scenario-answer"/);
});

for(const recovered of [true,false]) test(`lost playback acknowledgement ${recovered ? 'retries its identical receipt' : 'blocks mutations until reload'}`, async()=>{
    const s=session(); let attempts=0;
    const h=harness(async(url,options={})=>{
        if(url.includes('/prepare')) return {status:'READY',mediaId:'one',sessionVersion:1};
        if(url.endsWith('/played')) { attempts++; if(recovered && attempts===2) return {status:'READY',sessionVersion:2}; throw new Error('connection lost'); }
        if(url.endsWith('/help')) return {version:3,hintVi:'Nghe ý chính.'};
        return s;
    });
    await h.module.show(s.sessionId);h.node('#scenario-play-all').listeners.click();for(let i=0;i<5;i++)await tick();
    const posts=h.calls.filter(c=>c.url.endsWith('/played'));
    assert.equal(posts.length,2);assert.equal(posts[0].options.headers['Idempotency-Key'],posts[1].options.headers['Idempotency-Key']);
    assert.equal(JSON.stringify(posts[0].options.body),JSON.stringify(posts[1].options.body));
    h.node('data-help:0').listeners.click();for(let i=0;i<5;i++)await tick();
    assert.equal(h.calls.filter(c=>c.url.endsWith('/help')).length,recovered ? 1 : 0);
    if(!recovered) {
        assert.match(h.node('#scenario-status').textContent,/tải lại bài/i);
        await h.module.show(s.sessionId);h.node('data-help:0').listeners.click();await tick();
        assert.equal(h.calls.filter(c=>c.url.endsWith('/help')).length,1);
    }
});

test('new lesson preparation shows a real screen, prepares two segments without counting and never autoplays',async()=>{
    const s=session();s.activities[0].segments.push({segmentId:'s2',speakerId:'a'},{segmentId:'s3',speakerId:'a'});
    const storage=new Map([['dictalab:scenario:10:startAudio',JSON.stringify(s.sessionId)]]);
    let release;const gate=new Promise(resolve=>{release=resolve;});
    const h=harness(async(url,options={})=>{if(url.endsWith('/prepare')){await gate;return {status:'READY',mediaId:options.body.segmentId,sessionVersion:1};} return s;},storage);
    const opening=h.module.show(s.sessionId);await tick();assert.match(h.root.innerHTML,/Đang chuẩn bị bài nghe/);
    assert.match(h.root.innerHTML,/scenario-skip-preparation/);release();await opening;
    assert.equal(h.calls.filter(c=>c.url.endsWith('/prepare')).length,2);
    assert.equal(h.calls.filter(c=>c.url.endsWith('/played')).length,0);
    assert.equal(h.node('#scenario-audio').src,undefined);assert.match(h.root.innerHTML,/Lượt phát đoạn: 0/);
    assert.match(h.node('#scenario-status').textContent,/sẵn sàng/);
});

for(const outcome of ['FAILED','UNKNOWN']) test(`reload preparation preserves ${outcome} without requesting a paid retry`,async()=>{
    const s=session(),storage=new Map([['dictalab:scenario:10:audioIntent',JSON.stringify({sessionId:s.sessionId,activityId:'activity-1'})]]);
    const h=harness(async url=>url.endsWith('/prepare') ? {status:outcome,operationId:'saved-job'} : s,storage);
    await h.module.show(s.sessionId);
    assert.equal(h.calls.filter(c=>c.url.endsWith('/prepare')).length,1);
    assert.equal(h.calls.filter(c=>c.url.includes('retryFailed=true')).length,0);
    assert.match(h.node('#scenario-status').textContent,/saved-job/);
    assert.equal(h.calls.filter(c=>c.url.endsWith('/played')).length,0);
});

test('hint and transcript reuse their response, preserve drafts and do not refetch the lesson',async()=>{
    const s=session(),h=harness(async(url,options={})=>url.endsWith('/help') ? options.body.kind==='HINT'
        ? {version:2,hintVi:'Nghe lý do.'} : {version:3,segments:[{id:'s1',textEn:'At nine.'}],translations:[{textVi:'Lúc chín giờ.'}]} : s);
    await h.module.show(s.sessionId);const form=h.node('#scenario-answer');form.values=[['q1','Bản nháp']];form.listeners.input();
    h.node('data-help:0').listeners.click();await tick();assert.match(h.root.innerHTML,/Nghe lý do/);
    h.node('data-help:1').listeners.click();await tick();assert.match(h.root.innerHTML,/At nine/);assert.match(h.root.innerHTML,/Lúc chín giờ/);
    h.node('data-help:0').listeners.click();await tick();
    assert.equal(h.calls.filter(c=>c.url.endsWith('/help')).length,2);
    assert.equal(h.calls.filter(c=>c.options.method!=='POST').length,1);
    assert.match(h.root.innerHTML,/Bản nháp/);
});

test('skip preparation cancels a decoder wait, keeps the lesson and admits no further segment',async()=>{
    const s=session(),storage=new Map([['dictalab:scenario:10:startAudio',JSON.stringify(s.sessionId)]]);
    let decoding=0;
    const h=harness(async(url,options={})=>url.endsWith('/prepare') ? {status:'READY',mediaId:options.body.segmentId} : s,storage,{
        fetch:async()=>({ok:true,blob:async()=>({type:'audio/wav',size:48})}),
        URL:{createObjectURL:()=>'blob:waiting',revokeObjectURL(){}},
        Audio:class {addEventListener(){decoding++;} removeEventListener(){} removeAttribute(){} load(){}}
    });
    const opening=h.module.show(s.sessionId);for(let i=0;i<5;i++)await tick();assert.ok(decoding);
    h.node('#scenario-skip-preparation').listeners.click();await opening;
    assert.match(h.root.innerHTML,/id="scenario-answer"/);
    assert.equal(h.calls.filter(c=>c.url.endsWith('/played')).length,0);
    assert.equal(storage.has('dictalab:scenario:10:audioIntent'),false);
});

test('an observed start queued behind preparation is never acknowledged under another account',async()=>{
    const s=session();s.activities[0].segments=[1,2,3].map(i=>({segmentId:`s${i}`,speakerId:'a'}));
    let release;
    const h=harness(async(url,options={})=>{
        if(url.includes('/prepare')) {if(options.body.segmentId==='s3')return new Promise(resolve=>{release=resolve;}); return {status:'READY',mediaId:options.body.segmentId};}
        return s;
    });
    await h.module.show(s.sessionId);h.node('#scenario-play-all').listeners.click();for(let i=0;i<5;i++)await tick();
    assert.match(h.node('#scenario-status').textContent,/Đang nghe/);
    h.setUser(20);h.module.leave();release({status:'READY',mediaId:'s3'});for(let i=0;i<5;i++)await tick();
    assert.equal(h.calls.filter(c=>c.url.endsWith('/played')).length,0);
});

test('pending grade status remains usable after help and background polling waits for the help version',async()=>{
    const s=session(),storage=new Map([['dictalab:scenario:10:operation',JSON.stringify({operationId:'grade',action:'GRADE',sessionId:s.sessionId})]]);
    s.activities[0].attempts=[{...graded(),status:'PENDING',result:null}];
    let support,done=false;
    const h=harness(async url=>{
        if(url.endsWith('/help'))return new Promise(resolve=>{support=resolve;});
        if(url.includes('/operations/'))return {status:done?'SUCCEEDED':'RUNNING',operationId:'grade'};
        return s;
    },storage);
    await h.module.show(s.sessionId);h.node('data-help:0').listeners.click();await tick();
    const before=h.calls.length;h.timers.shift()?.();await tick();assert.equal(h.calls.length,before);
    support({version:2,hintVi:'Nghe mục đích.'});for(let i=0;i<4;i++)await tick();
    done=true;h.node('#scenario-check').listeners.click();for(let i=0;i<5;i++)await tick();
    assert.equal(storage.has('dictalab:scenario:10:operation'),false);
    assert.doesNotMatch(h.root.innerHTML,/scenario-pending-grade/);
    assert.match(h.root.innerHTML,/Nghe mục đích/);
});
