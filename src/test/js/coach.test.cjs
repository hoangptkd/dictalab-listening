const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/coach.js'), 'utf8');

function harness(api, timers = {setTimeout}, storage = new Map()) {
    const events = [];
    const elements = new Map();
    const $ = selector => {
        if (!elements.has(selector)) elements.set(selector, {innerHTML: '', textContent: '', hidden: false,
            handlers: {}, setAttribute() {}, removeAttribute() {}, addEventListener(name, handler) {this.handlers[name]=handler;}, scrollIntoView() {}, focus() {},
            insertAdjacentHTML(_position, html) {this.innerHTML += html;}});
        return elements.get(selector);
    };
    const listeners = {};
    const root = {querySelector: $, querySelectorAll: () => [], addEventListener: (name, handler) => { listeners[name] = handler; }};
    const window = {crypto: require('node:crypto').webcrypto, DictaLabListeningBridge: {
        api: (url, options) => {
            if (url.endsWith('/coach/events')) { events.push({url, ...options.body}); return Promise.resolve(); }
            return api(url, options);
        }, stopRecordedAudio() {}, showToast() {}, currentUser: () => ({id: 42}), playRecordedAudio: async () => {}}};
    const sessionStorage = {getItem: key => storage.get(key),setItem: (key,value) => storage.set(key,value),removeItem: key => storage.delete(key)};
    vm.runInNewContext(source, {window, sessionStorage, document: {querySelector: () => root}, ...timers});
    return {module: window.DictaLabCoach, $, listeners, events};
}

const report = (label = 'Real evidence', aiEnabled = false) => ({source: 'RULES', aiEnabled,
    weaknesses: [{pattern: 'KNOWN_MISSED', title: label, insight: label + ': short insight', evidenceIds: ['1:0']}],
    progressInsight: {status: 'INSUFFICIENT', text: 'Not enough history'},
    nextActions: [{pattern: 'KNOWN_MISSED', instruction: 'Listen again'}],
    reviewItems: [{id: '1:0', pattern: 'KNOWN_MISSED', heard: '<img onerror=alert(1)>', expected: 'market',
        transcript: 'The market is open.', wordIndex: 1, position: 1, label, explanation: 'Listen in context',
        occurrences: 2, audioUrl: '/test.mp3'}]});

const click = (h, attribute) => h.listeners.click({target: {closest: () => ({disabled: false,
    dataset: {}, hasAttribute: name => name === attribute})}});

test('result review shows three items first and preserves every later item and action', async () => {
    for (const count of [0, 1, 3, 4, 12]) {
        const data = report();
        data.reviewItems = Array.from({length: count}, (_, i) => ({...data.reviewItems[0], id: `item-${i}`}));
        const h = harness(async () => data);
        await h.module.show({sessionId: 'current'});
        const html = h.$('#smart-review-items').innerHTML;
        const first = html.split('<details')[0];
        assert.equal((first.match(/data-coach-item=/g) || []).length, Math.min(3, count));
        assert.equal((html.match(/data-coach-item=/g) || []).length, count);
        for (const action of ['listen', 'drill', 'save']) {
            assert.equal((html.match(new RegExp(`data-coach-${action}[ >]`, 'g')) || []).length, count);
        }
        assert.equal(html.includes('<details'), count > 3);
        if (count > 3) assert.ok(html.includes(`Xem thêm ${count - 3} điểm cần ôn`));
    }
});

test('automatic Error Bank escapes evidence and never presents a suspected cause as confirmed', async () => {
    const h=harness(async url => url==='/api/v1/error-bank' ? [] : url==='/api/v1/listening-learning'
        ? {due: 1,pending: 2,targets: [{target: '<script>',errors: 3,comparable_opportunities: 4}],
            items: [{id: 1,observation: 'SPELLING',possible_pattern: 'WEAK_FORM_POSSIBLE',heard: '<img>',expected: 'milk',transcript: '<script>',audio_url: '/test.mp3'}]}
        : report());
    await h.module.show({sessionId: 'current'});
    h.$('#coach-error-bank').open=true;h.$('#coach-error-bank').handlers.toggle();
    await new Promise(done=>setImmediate(done));
    const html=h.$('#coach-bank-items').innerHTML;
    assert.doesNotMatch(html, /<script>|<img>/);assert.match(html,/&lt;script&gt;/);
    assert.match(html,/chưa xác nhận nguyên nhân/);assert.match(html,/data-spaced-review/);
});

test('review requires played audio and freezes uncertain answer until confirmed without a second schedule', async () => {
    const bodies=[];
    const h=harness(async (url,options) => {
        if(url.endsWith('/reviews')) return [{id:'review-1',prompt:'He _____ bread.',audioUrl:'/test.mp3',newContext:true}];
        if(url.endsWith('/played')) return null;
        if(url.endsWith('/answer')) {
            bodies.push(JSON.parse(JSON.stringify(options.body)));
            if(bodies.length===1) throw new Error('Response lost');
            return {correct:true,expected:'buys',stage:1,dueAt:'2026-10-13T00:00:00Z'};
        }
        return report();
    });
    await h.module.show({sessionId:'current'});await click(h,'data-spaced-review');
    const submit=()=>h.listeners.submit({target:{id:'coach-drill-form'},preventDefault(){}});
    h.$('#coach-drill-answer').value='buys';await submit();assert.equal(bodies.length,0);
    await click(h,'data-drill-listen');await submit();
    h.$('#coach-drill-answer').value='different';await submit();
    assert.deepEqual(bodies,[{answer:'buys',reveal:false},{answer:'buys',reveal:false}]);
    await submit();assert.equal(bodies.length,2);assert.equal(h.$('#coach-drill-answer').disabled,true);
});

test('reload recovers a committed review by GET without another grading request', async () => {
    const storage=new Map([['dictalab.review.42',JSON.stringify({id:'lost',body:{answer:'milk',reveal:false}})]]);
    const requests=[];
    const h=harness(async (url,options) => {
        requests.push({url,options});
        if(url.endsWith('/lost')) return {challenge:{id:'lost',prompt:'Buy _____',audioUrl:'/test.mp3'},
            result:{correct:true,expected:'milk',dueAt:'2026-10-13T00:00:00Z'}};
        return report();
    },{setTimeout},storage);
    await h.module.show({sessionId:'current'});await click(h,'data-spaced-review');
    assert.equal(requests.at(-1).options,undefined);assert.match(h.$('#coach-drill-feedback').textContent,/Đúng rồi/);
    assert.equal(storage.has('dictalab.review.42'),false);
});

test('coach ignores stale responses after navigation or logout', async () => {
    let resolve;
    const h = harness(() => new Promise(done => { resolve = done; }));
    const pending = h.module.show({sessionId: 'old'});
    h.module.leave();
    resolve(report('Old account'));
    await pending;
    assert.equal(h.$('#coach-insights').innerHTML, '');
});

test('automatic bank remains available when manual bank fails and is cleared on leave', async () => {
    const h=harness(async url => {
        if(url === '/api/v1/error-bank') throw new Error('Temporary manual bank outage');
        if(url === '/api/v1/listening-learning') return {items:[],targets:[],due:1,pending:0,failed:0};
        return report();
    });
    await h.module.show({sessionId:'current'});
    h.$('#coach-error-bank').open=true;
    h.$('#coach-error-bank').handlers.toggle();
    await new Promise(done=>setImmediate(done));
    assert.match(h.$('#coach-bank-items').innerHTML,/Kho lỗi tự động/);
    h.module.leave();
    assert.equal(h.$('#coach-bank-items').innerHTML,'');
    assert.equal(h.$('#coach-practice').innerHTML,'');
});

test('coach escapes all evidence and keeps primary action and technical-free review', async () => {
    const h = harness(async () => report('<script>bad</script>'));
    await h.module.show({sessionId: 'current'});
    assert.doesNotMatch(h.$('#coach-insights').innerHTML, /<script>/);
    assert.equal(h.$('#coach-quick-review').textContent, 'Ôn nhanh 1 câu');
    assert.doesNotMatch(h.$('#smart-review-items').innerHTML, /<img/);
    assert.match(h.$('#smart-review-items').innerHTML, /&lt;img/);
    assert.match(h.$('#smart-review-items').innerHTML, /data-coach-save/);
});

test('late AI responses cannot overwrite a newer session', async () => {
    let resolve;
    const h = harness(async (url, options) => {
        if (options?.method === 'POST') return new Promise(done => { resolve = done; });
        return report(url.includes('old') ? 'Old' : 'New', url.includes('old'));
    });
    const pending = h.module.show({sessionId: 'old'});
    await new Promise(done => setImmediate(done));
    await h.module.show({sessionId: 'new'});
    resolve({...report('Old AI'), source: 'AI'});
    await pending;
    assert.match(h.$('#coach-insights').innerHTML, /New/);
    assert.doesNotMatch(h.$('#coach-insights').innerHTML, /Old AI/);
});

test('AI failure preserves the deterministic review', async () => {
    const h = harness(async (_url, options) => {
        if (options?.method === 'POST') throw new Error('Offline');
        return report('Useful fallback', true);
    });
    await h.module.show({sessionId: 'current'});
    assert.match(h.$('#coach-insights').innerHTML, /Useful fallback/);
    assert.match(h.$('#smart-review-items').innerHTML, /market/);
    const retry = {disabled: false, hasAttribute: name => name === 'data-coach-retry'};
    await h.listeners.click({target: {closest: () => retry}});
    assert.equal(retry.disabled, false);
});

test('practice masks the right token and checks the answer without changing session telemetry', async () => {
    const requests = [];
    const h = harness(async (...args) => { requests.push(args); return report(); });
    await h.module.show({sessionId: 'current'});
    const button = {disabled: false, dataset: {coachPractice: 'KNOWN_MISSED'}, hasAttribute: name => name === 'data-coach-practice'};
    await h.listeners.click({target: {closest: () => button}});
    assert.match(h.$('#coach-practice').innerHTML, /The _____ is open\./);
    h.$('#coach-drill-answer').value = 'wrong';
    h.listeners.submit({target: {id: 'coach-drill-form'}, preventDefault() {}});
    assert.equal(h.$('[data-drill-next]').hidden, true);
    h.$('#coach-drill-answer').value = ' MARKET ';
    h.listeners.submit({target: {id: 'coach-drill-form'}, preventDefault() {}});
    assert.equal(h.$('[data-drill-next]').hidden, false);
    assert.equal(requests.length, 1);
});

test('queued Coach polls without repeating the generation request', async () => {
    const requests = [];
    const h = harness(async (url, options) => {
        requests.push({url, options});
        if (options) return {...report('Baseline', true), source: 'QUEUED'};
        return requests.length === 1 ? report('Baseline', true) : {...report('AI result', true), source: 'AI'};
    }, {setTimeout: done => setImmediate(done)});
    await h.module.show({sessionId: 'current'});
    assert.equal(requests.filter(r => r.options?.method === 'POST').length, 1);
    assert.match(h.$('#coach-insights').innerHTML, /AI result/);
});

test('reopening a failed Coach report does not retry AI automatically', async () => {
    let posts = 0;
    const h = harness(async (_url, options) => {
        if (options) posts++;
        return {...report('Baseline', true), source: 'RETRY_REQUIRED'};
    });
    await h.module.show({sessionId: 'current'});
    assert.equal(posts, 0);
    assert.match(h.$('#coach-insights').innerHTML, /data-coach-retry/);
});

test('leaving queued Coach stops polling before making another request', async () => {
    let wake, calls = 0;
    const h = harness(async () => {calls++; return {...report('Baseline', true), source: 'QUEUED'};},
        {setTimeout: done => {wake = done;}});
    const pending = h.module.show({sessionId: 'old'});
    await new Promise(done => setImmediate(done));
    h.module.leave(); wake(); await pending;
    assert.equal(calls, 1);
});

test('polling outage preserves usable Coach evidence and offers read-only reload', async () => {
    let calls = 0;
    const h = harness(async () => {
        if (++calls > 1) throw new Error('Temporary outage');
        return {...report('Useful fallback', true), source: 'QUEUED'};
    }, {setTimeout: done => setImmediate(done)});
    await h.module.show({sessionId: 'current'});
    assert.match(h.$('#coach-insights').innerHTML, /Useful fallback/);
    assert.match(h.$('#coach-insights').innerHTML, /data-coach-reload/);
    assert.match(h.$('#smart-review-items').innerHTML, /market/);
});

test('progress uses typed deltas and reasons, with legacy text fallback', async () => {
    for (const [status, delta, expected] of [['UP',6,'↑ Cao hơn 6 điểm %'], ['DOWN',-4,'↓ Thấp hơn 4 điểm %'], ['STEADY',0,'Kết quả khá ổn định']]) {
        const data=report();
        data.progressInsight={status,text:'Legacy',deltaPoints:delta,comparedLessonCount:3,level:'A2',comparisonReason:'NEW'};
        const h=harness(async()=>data);
        await h.module.show({sessionId:'current',level:'A2'});
        assert.ok(h.$('#coach-insights').innerHTML.includes(expected));
        assert.match(h.$('#coach-insights').innerHTML,/3 bài mới A2/);
        assert.equal(h.$('#coach-next-lesson').href,'/catalog?level=A2&page=1');
    }
    for (const [status,reason,expected] of [['INSUFFICIENT','NEW','Chưa đủ dữ liệu'],['NOT_COMPARABLE','SHARED','Buổi học cùng bạn'],['NOT_COMPARABLE','REPEAT','Kết quả luyện lại']]) {
        const data=report();data.progressInsight={status,comparisonReason:reason};
        const h=harness(async()=>data);await h.module.show({sessionId:'current'});
        assert.ok(h.$('#coach-insights').innerHTML.includes(expected));
    }
    const h=harness(async()=>report());await h.module.show({sessionId:'current'});
    assert.match(h.$('#coach-insights').innerHTML,/Not enough history/);
});

test('quick review selects different sentences and late AI cannot replace an active drill', async () => {
    let resolve;
    const initial=report('Baseline',true);
    initial.reviewItems=[...initial.reviewItems,{...initial.reviewItems[0],id:'1:1'},
        {...initial.reviewItems[0],id:'2:0',position:2}, {...initial.reviewItems[0],id:'3:0',position:3}];
    const h=harness(async(_url,options)=>options ? new Promise(done=>{resolve=done;}) : initial);
    const pending=h.module.show({sessionId:'current'});
    await new Promise(done=>setImmediate(done));
    assert.equal(h.$('#coach-quick-review').textContent,'Ôn nhanh 3 câu');
    await click(h,'data-coach-quick-review');
    const active=h.$('#coach-practice').innerHTML;
    resolve({...report('New insight'),source:'AI',reviewItems:[]});await pending;
    assert.equal(h.$('#coach-practice').innerHTML,active);
    assert.equal(h.$('#coach-quick-review').textContent,'Ôn nhanh 3 câu');
    assert.match(active,/The _____ is open\./);
    assert.doesNotMatch(active,/market/);
    for(let i=0;i<3;i++) {
        h.$('#coach-drill-answer').value='market';
        await h.listeners.submit({target:{id:'coach-drill-form'},preventDefault(){}});
        await click(h,'data-drill-next');
    }
    await new Promise(done=>setImmediate(done));
    assert.match(h.$('#coach-practice').innerHTML,/Đã ôn 3 câu/);
    assert.equal(h.events.filter(e=>e.name==='coach_result_viewed').length,1);
    const started=h.events.find(e=>e.name==='coach_quick_review_started');
    const completed=h.events.find(e=>e.name==='coach_quick_review_completed');
    assert.equal(completed.reviewRunId,started.reviewRunId);
    await h.module.detailsOpened();
    assert.equal(h.events.at(-1).name,'coach_details_opened');
});

test('empty audio hides review and overlong insight is omitted without truncation', async () => {
    const data=report(); data.reviewItems[0].audioUrl=null;data.weaknesses[0].insight='x'.repeat(121);
    const h=harness(async()=>data);await h.module.show({sessionId:'current'});
    assert.equal(h.$('#coach-quick-review').hidden,true);
    assert.doesNotMatch(h.$('#coach-insights').innerHTML,/coach-focus|xxxx/);
});

test('saved AI feedback is labeled separately from deterministic review suggestions', async () => {
    for (const source of ['AI', 'RULES', 'UNAVAILABLE']) {
        const data = {...report('Saved feedback'), source};
        const h = harness(async () => data);
        await h.module.show({sessionId: 'saved'}, {autoGenerate: false});
        const html = h.$('#coach-insights').innerHTML;
        assert.equal(html.includes('<h2>Nhận xét AI</h2>'), source === 'AI');
        assert.equal(html.includes('Gợi ý ôn tập:'), source !== 'AI');
        assert.match(html, /Saved feedback: short insight/);
    }
});

test('history never starts AI on load or reload but allows an explicit request', async () => {
    const requests = [];
    const h = harness(async (url, options) => {
        requests.push({url, options});
        return {...report('History feedback', true), source: options?.method === 'POST' ? 'AI' : 'RULES'};
    });
    await h.module.show({sessionId: 'saved'}, {autoGenerate: false});
    assert.match(h.$('#coach-insights').innerHTML, /Chưa có nhận xét AI đã lưu/);
    assert.match(h.$('#coach-insights').innerHTML, /data-coach-retry>Nhận xét AI/);
    await click(h, 'data-coach-reload');
    assert.equal(requests.filter(r => r.options?.method === 'POST').length, 0);
    await click(h, 'data-coach-retry');
    assert.equal(requests.filter(r => r.options?.method === 'POST').length, 1);
    assert.match(h.$('#coach-insights').innerHTML, /<h2>Nhận xét AI<\/h2>/);
});

test('empty evidence explains why AI is not called and AI with no focus is still visibly acknowledged', async () => {
    for (const source of ['RULES', 'AI']) {
        const data = {...report('', true), source, weaknesses: [], reviewItems: []};
        let calls = 0;
        const h = harness(async () => { calls++; return data; });
        await h.module.show({sessionId: 'saved'});
        assert.equal(calls, 1);
        assert.match(h.$('#coach-insights').innerHTML, source === 'AI'
            ? /AI chưa chọn thêm điểm cần chú ý/ : /Không có lỗi cần AI phân tích/);
        assert.doesNotMatch(h.$('#coach-insights').innerHTML, /data-coach-retry/);
    }
});
