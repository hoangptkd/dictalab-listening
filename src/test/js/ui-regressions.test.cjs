const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const read = name => fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js', name), 'utf8');
const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/index.html'), 'utf8');

test('idempotency journal survives reload and keeps replay start/end as distinct actions', async () => {
    const source=read('app.js').match(/    const pendingPracticeSubmissions = new Map\(\);[\s\S]*?(?=    function escapeHtml)/)[0];
    const storage=new Map(), requests=[];
    let key=0;
    function load(api) {
        const state={currentUser:{id: 42},practiceMutationQueue:Promise.resolve()};
        return vm.runInNewContext(source+'\nqueuePracticeMutation', {state,api,
            crypto:{randomUUID:()=>`key-${++key}`},sessionStorage:{getItem:k=>storage.get(k),setItem:(k,v)=>storage.set(k,v)}});
    }
    const first=load(async (path,options)=>{requests.push({path,options});throw new Error('Response lost');});
    await assert.rejects(first('/session/word/checks',{method:'POST',body:{answer:'milk',responseTimeMs:10}}));
    const resumed=load(async (path,options)=>{requests.push({path,options});return {};});
    await resumed('/session/word/checks',{method:'POST',body:{answer:'milk',responseTimeMs:999}});
    assert.equal(requests[0].options.headers['Idempotency-Key'],requests[1].options.headers['Idempotency-Key']);
    assert.equal(requests[1].options.body.responseTimeMs,10);
    const replay=load(async (path,options)=>{requests.push({path,options});return {};});
    await Promise.all(['STARTED','COMPLETED'].map(event=>replay('/replay-events',{method:'POST',body:{event}})));
    assert.notEqual(requests[2].options.headers['Idempotency-Key'],requests[3].options.headers['Idempotency-Key']);
    assert.equal(requests[3].options.body.event,'COMPLETED');
    assert.equal(JSON.parse(storage.get('dictalab.practice.42')).length,0);
});

test('missed word classifications stay outside inputs and retain word association and saved choices', () => {
    const source = read('app.js').match(/    function renderWordSlots\([\s\S]*?\n    }\n/)[0];
    const classifications = read('app.js').match(/    function renderWordClassifications\([\s\S]*?\n    }\n/)[0];
    const state = {sentence: {words: []}, wordDrafts: new Map()};
    const elements = {wordSlots: {}, wordCompletion: {}, wordClassifications: {},
        wordClassificationList: {}, classificationCount: {}};
    const render = vm.runInNewContext(`${source}\n${classifications}\n(() => {renderWordSlots(); renderWordClassifications();})`, {
        state, elements, composingWords: new Set(), document: {}, escapeHtml: text => String(text).replaceAll('<', '&lt;').replaceAll('>', '&gt;'), escapeAttribute: String,
        refreshDictationCopy() {}, roomQueueDraft() {}
    });
    for (const [errorType, correct, revealed] of [
        [null, false, false], [null, true, false],
        ['KNOWN_BUT_MISSED', false, false], ['KNOWN_BUT_MISSED', true, false], ['UNKNOWN', false, true]
    ]) {
        state.sentence.words = [{index: 2, length: 4, mask: '____', userInput: 'werd',
            revealedText: 'word', firstLetter: 'w', errorType, correct, revealed}];
        render();
        assert.doesNotMatch(elements.wordSlots.innerHTML, /word-classification|<button /);
        const markup = elements.wordClassificationList.innerHTML;
        assert.equal(elements.wordClassifications.hidden, !errorType);
        if (!errorType) {
            assert.equal(markup, '');
            continue;
        }
        assert.match(markup, /aria-label="Phân loại lỗi của từ 3"/);
        assert.equal((markup.match(/<button /g) || []).length, 2);
        assert.match(markup, revealed ? /<strong>word<\/strong>/ : /<strong>werd<\/strong>/);
        if (!revealed) assert.doesNotMatch(markup, /<strong>word<\/strong>/);
        assert.equal(elements.classificationCount.textContent, '1 từ');
        for (const type of ['KNOWN_BUT_MISSED', 'UNKNOWN']) {
            assert.match(markup, new RegExp(`data-word-index="2" data-error-type="${type}"\\s+aria-pressed="${errorType === type}"`));
        }
    }
    state.sentence.words[0].revealedText = '<img src=x>';
    render();
    assert.match(elements.wordClassificationList.innerHTML, /&lt;img src=x&gt;/);
    assert.doesNotMatch(elements.wordClassificationList.innerHTML, /<img/);
    assert.ok(html.indexOf('id="word-classifications"') > html.indexOf('id="next-sentence"'));
    assert.match(read('app.js'), /wordClassificationList.addEventListener\("click", onClassifyWord\)/);
});

test('a first-letter hint remains visible without becoming an unsubmitted answer', () => {
    const renderSource = read('app.js').match(/    function renderWordSlots\([\s\S]*?\n    }\n/)[0];
    const state = {sentence: {words: [{index: 0, length: 6, mask: '______', firstLetter: 'm'}]}, wordDrafts: new Map()};
    const elements = {wordSlots: {}, wordCompletion: {}};
    vm.runInNewContext(renderSource + '\nrenderWordSlots();', {
        state, elements, composingWords: new Set(), document: {}, escapeHtml: String, escapeAttribute: String,
        refreshDictationCopy() {}, roomQueueDraft() {}
    });
    assert.match(elements.wordSlots.innerHTML, /Bắt đầu: m/);
    assert.match(elements.wordSlots.innerHTML, /value=""/);
});

test('classification panel preserves expansion during saves and clears stale words on sentence changes', () => {
    const source = read('app.js').match(/    function renderWordClassifications\([\s\S]*?\n    }\n/)[0];
    const state = {sentence: {words: [{index: 0, mask: '___', errorType: 'UNKNOWN'}]}};
    const elements = {wordClassifications: {open: true}, wordClassificationList: {}, classificationCount: {}};
    const render = vm.runInNewContext(`${source}\nrenderWordClassifications`, {state, elements, escapeHtml: String});
    render();
    assert.equal(elements.wordClassifications.open, true);
    render(true);
    assert.equal(elements.wordClassifications.open, false);
    assert.match(elements.wordClassificationList.innerHTML, /<strong>___<\/strong>/);
    state.sentence = {words: []};
    elements.wordClassifications.open = true;
    render();
    assert.equal(elements.wordClassifications.open, false);
    assert.equal(elements.wordClassifications.hidden, true);
    assert.equal(elements.wordClassificationList.innerHTML, '');
});

test('relocated classification saves the selected word and restores button focus after rendering', async () => {
    const source = read('app.js').match(/    async function onClassifyWord\([\s\S]*?\n    }\n/)[0];
    for (const focused of [true, false]) {
        const button = {dataset: {wordIndex: '2', errorType: 'UNKNOWN'}};
        const requests = [], selectors = [], statuses = [];
        let restored = 0, rendered = 0;
        const sentence = {words: [{index: 2, errorType: 'UNKNOWN'}]}, state = {};
        const classify = vm.runInNewContext(`${source}\nonClassifyWord`, {
            state, document: {activeElement: focused ? button : {}},
            practiceInputContext: () => () => true, captureWordFocus: () => null,
            practicePath: suffix => `/practice${suffix}`,
            queuePracticeMutation: async (url, options) => { requests.push({url, ...options}); return sentence; },
            renderPractice() { rendered++; }, setPracticeStatus: message => statuses.push(message),
            elements: {wordClassificationList: {querySelector(selector) {
                selectors.push(selector); return {focus() { restored++; }};
            }}}
        });
        await classify({target: {closest: () => button}});
        assert.equal(requests[0].url, '/practice/classifications');
        assert.equal(requests[0].method, 'POST');
        assert.equal(requests[0].body.wordIndex, 2);
        assert.equal(requests[0].body.errorType, 'UNKNOWN');
        assert.equal(state.sentence, sentence);
        assert.equal(rendered, 1);
        assert.equal(restored, focused ? 1 : 0);
        if (focused) assert.equal(selectors[0], 'button[data-word-index="2"][data-error-type="UNKNOWN"]');
        assert.match(statuses[0], /Đã cập nhật/);
    }
});

test('a late incorrect check cannot mark a newer draft as invalid or interrupt active composition', () => {
    const source = read('app.js').match(/    function renderWordSlots\([\s\S]*?\n    }\n/)[0];
    const state = {sentence: {words: [{index: 0, length: 3, userInput: 'cot', correct: false, mask: '___'}]},
        wordDrafts: new Map([[0, 'ca']])};
    const elements = {wordSlots: {}, wordCompletion: {}}, composingWords = new Set();
    const render = vm.runInNewContext(`${source}\nrenderWordSlots`, {
        state, elements, composingWords, document: {}, escapeHtml: String, escapeAttribute: String,
        refreshDictationCopy() {}, roomQueueDraft() {}
    });
    render(); assert.doesNotMatch(elements.wordSlots.innerHTML, /is-wrong/);
    assert.match(elements.wordSlots.innerHTML, /aria-invalid="false"/);
    state.wordDrafts.set(0, 'cot'); render();
    assert.match(elements.wordSlots.innerHTML, /is-wrong/);
    const html = elements.wordSlots.innerHTML; composingWords.add(0); state.wordDrafts.set(0, 'cat'); render();
    assert.equal(elements.wordSlots.innerHTML, html);
});

test('password toggles keep values and form behavior intact and affect only their own field', () => {
    const buttons = [], inputs = ['currentPassword', 'newPassword'].map(name => ({
        name, type: 'password', value: 'unchanged-secret', form: {id: 'account-password-form'},
        closest(selector) { return selector === 'label' ? this.label : this.wrapper; },
        label: {textContent: 'Mật khẩu', before(wrapper) { this.wrapper = wrapper; }}
    }));
    const document = {createElement(tag) {
        if (tag === 'div') return {append(node) {
            const input = inputs.find(input => input.label === node);
            if (input) input.wrapper = this;
        }};
        const button = {attrs: {}, setAttribute(name, value) { this.attrs[name] = value; },
            addEventListener(_event, handler) { this.click = handler; }};
        buttons.push(button); return button;
    }};
    const window = {};
    vm.runInNewContext(read('account.js'), {window, document});
    const root = {querySelectorAll: () => inputs};
    window.DictaLabAccount.enhancePasswords(root);
    window.DictaLabAccount.enhancePasswords(root);
    assert.equal(buttons.length, 2, 'repeated rendering cannot duplicate controls');
    assert.equal(buttons[0].type, 'button');
    assert.equal(buttons[0].attrs['aria-controls'], inputs[0].id);
    assert.notEqual(inputs[0].id, inputs[1].id);
    buttons[0].click();
    assert.equal(inputs[0].type, 'text');
    assert.equal(inputs[1].type, 'password');
    assert.equal(buttons[0].attrs['aria-pressed'], 'true');
    buttons[0].click();
    assert.equal(inputs[0].type, 'password');
    assert.equal(buttons[0].attrs['aria-pressed'], 'false');
    assert.equal(inputs[0].value, 'unchanged-secret');
});

test('support selects valid linked categories and ignores unknown or inherited properties', async () => {
    for (const category of ['ACCOUNT_DELETE', 'OTHER', 'invalid', 'toString']) {
        const select = {value: 'AUDIO', addEventListener() {}, closest: () => ({after() {}})};
        const root = {addEventListener() {}, querySelector(selector) {
            return selector === '[name="category"]' ? select : selector === '#support-form' ? root : {addEventListener() {}};
        }};
        const window = {DictaLabListeningBridge: {api: async () => ({tickets: [], hasMore: false})}};
        vm.runInNewContext(read('account.js'), {window, document: {querySelector: () => root, createElement: () => ({})},
            location: {search: `?category=${category}`}, URLSearchParams});
        await window.DictaLabAccount.show('support');
        assert.equal(select.value, ['ACCOUNT_DELETE', 'OTHER'].includes(category) ? category : 'AUDIO');
        assert.match(root.innerHTML, /Phản hồi và trạng thái xử lý/);
    }
});

test('dashboard compares the previous session of the same level and links newest sessions first', () => {
    const welcome = {}, recent = {};
    const source = read('app.js').match(/    function renderDashboardActivity\([\s\S]*?\n    }\n/)[0];
    const render = vm.runInNewContext(`${source}\nrenderDashboardActivity`, {
        document: {querySelector: selector => selector === '#dashboard-welcome' ? welcome : recent},
        formatPercent: String, escapeHtml: String, escapeAttribute: String,
        router: {toPath: (_route, params) => `/history/${params.sessionId}`}
    });
    const points = [
        {sessionId: 'a', level: 'A1', firstAttemptAccuracy: 50},
        {sessionId: 'b', level: 'B1', firstAttemptAccuracy: 90},
        {sessionId: 'c', level: 'A1', firstAttemptAccuracy: 65}
    ].map(point => ({...point, lessonTitle: 'Lesson', date: '2026-10-03'}));
    render(points);
    assert.match(welcome.textContent, /Tăng 15 điểm phần trăm/);
    assert.ok(recent.innerHTML.indexOf('/history/c') < recent.innerHTML.indexOf('/history/b'));
    render([points[0], {...points[2], firstAttemptAccuracy: 50}]);
    assert.match(welcome.textContent, /Không đổi/);
    render([points[0], points[1]]);
    assert.match(welcome.textContent, /Chưa có buổi trước cùng trình độ/);
    render([]);
    assert.match(welcome.textContent, /Chưa có buổi/);
    assert.match(recent.innerHTML, /Chọn bài luyện đầu tiên/);
});

test('reset request consumes the login email once and handles unavailable storage', () => {
    for (const unavailable of [false, true]) {
        const input = {value: '', focus() {}}, removed = [];
        const panel = {querySelectorAll: () => [], querySelector: selector => selector.includes('email') || selector === 'input'
            ? input : {addEventListener() {}}};
        const window = {};
        vm.runInNewContext(read('account.js'), {window, URLSearchParams, location: {search: ''},
            document: {querySelector: selector => selector === '#account-public-actions' ? panel : {}},
            sessionStorage: {getItem() { if (unavailable) throw new Error('blocked'); return 'learner@test.local'; },
                removeItem: key => removed.push(key)}
        });
        window.DictaLabAccount.showPublic('reset-password');
        assert.equal(input.value, unavailable ? '' : 'learner@test.local');
        assert.deepEqual(removed, unavailable ? [] : ['dictalab-reset-email']);
    }
});

test('dashboard load failure has persistent feedback and can be retried', async () => {
    const panel = {}, view = {setAttribute(name, value) { this[name] = value; }};
    let fail = true, rendered = 0;
    const source = read('app.js').match(/    async function refreshDashboard\([\s\S]*?\n    }\n/)[0];
    const refresh = vm.runInNewContext(`${source}\nrefreshDashboard`, {
        document: {querySelector: selector => selector === '#dashboard-load-error' ? panel : view},
        api: async () => { if (fail) throw new Error('Offline'); return {}; },
        renderDashboard: () => rendered++, showToast() {}
    });
    await refresh(false);
    assert.equal(panel.hidden, false);
    assert.equal(view['aria-busy'], 'false');
    fail = false;
    await refresh(true);
    assert.equal(panel.hidden, true);
    assert.equal(rendered, 1);
});

test('admin usage preserves its period control, keyboard focus and retry after errors', async () => {
    let fail = true, focus = 0, created = 0;
    const result = {}, select = {value: '30', focus() { focus++; }, addEventListener() {}};
    const retry = {addEventListener(_event, handler) { this.click = handler; }};
    const root = {isConnected: true, contains: () => true, setAttribute() {},
        querySelector: selector => selector === 'select' ? (created ? select : null)
            : selector === 'button' ? retry : result,
        set innerHTML(_value) { created++; }};
    const requests = [];
    const source = read('account.js').match(/    async function usage\([\s\S]*?\n    }\n/)[0];
    const usage = vm.runInNewContext(`${source}\nusage`, {
        document: {activeElement: select}, escape: String, encodeURIComponent,
        bridge: () => ({api: async url => {
            requests.push(url);
            if (fail) throw new Error('Offline');
            return {from: '2026-10-01', to: '2026-10-03'};
        }})
    });
    await usage(root, 7);
    assert.match(result.innerHTML, /Offline/);
    assert.equal(select.disabled, false);
    fail = false;
    await retry.click();
    assert.match(result.innerHTML, /usage\/export\?days=7/);
    await usage(root, 90);
    assert.equal(select.value, '90');
    assert.equal(created, 1);
    assert.equal(focus, 3);
    assert.deepEqual(requests, ['/api/admin/usage?days=7', '/api/admin/usage?days=7', '/api/admin/usage?days=90']);
});

test('opening a support link preserves deletion category and existing lesson references', async () => {
    const source = read('app.js').match(/    async function resolveUrlAndNavigate\([\s\S]*?\n    }\n/)[0];
    for (const search of ['?category=ACCOUNT_DELETE', '?type=LISTENING&id=reference', '']) {
        let replaced = false, rendered = false;
        const resolve = vm.runInNewContext(`${source}\nresolveUrlAndNavigate`, {
            isAdminArea: false, state: {currentUser: {id: 1}}, router: {replace() { replaced = true; }}, _showView() {},
            window: {DictaLabAccount: {show: async () => { rendered = true; }}}
        });
        await resolve({route: 'support', params: {}, query: new URLSearchParams(search)}, false);
        assert.equal(replaced, search === '');
        assert.equal(rendered, true);
    }
});

test('dashboard labels dates from each session and places the next lesson before lifetime totals', () => {
    const app = read('app.js');
    const chart = {setAttribute() {}};
    const source = app.match(/    function renderTrend\([\s\S]*?\n    }\n/)[0];
    const render = vm.runInNewContext(`${source}\nrenderTrend`, {
        elements: {trendChart: chart}, escapeAttribute: String, escapeHtml: String
    });
    render([{date: '2026-10-03', level: 'B1', lessonTitle: 'Bài 1', firstAttemptAccuracy: 50, finalAccuracy: 80}]);
    assert.match(chart.innerHTML, /<time datetime="2026-10-03">03\/10<\/time>/);
    const dashboard = html.slice(html.indexOf('<section id="dashboard-view"'), html.indexOf('<section id="history-view"'));
    assert.ok(dashboard.indexOf('id="recommendation-card"') < dashboard.indexOf('id="dashboard-summary"'));
    assert.match(dashboard, /Tổng hợp toàn bộ thời gian/);
});

test('mobile overflow navigation preserves links and synchronizes disclosure state', () => {
    assert.match(html, /id="mobile-nav-menu"[^>]+popover="auto"/);
    assert.match(html, /popovertarget="mobile-nav-menu"/);
    const menuHtml = html.match(/<div id="mobile-nav-menu"[\s\S]*?<\/div>/)[0];
    for (const route of ['history', 'room', 'support']) {
        assert.match(menuHtml, new RegExp(`<a[^>]+href="[^"]+"[^>]+data-route="${route}"`));
    }
    const attributes = {};
    let onToggle, onResize, closed = 0;
    const more = {setAttribute: (name, value) => { attributes[name] = value; }};
    const menu = {hidePopover() { closed++; }, addEventListener(_type, handler) { onToggle = handler; }};
    const elements = {};
    const source = read('app.js').match(/    function bindMobileNavigation\([\s\S]*?\n    }\n/)[0];
    const bind = vm.runInNewContext(`${source}\nbindMobileNavigation`, {
        elements, document: {querySelector: selector => selector === '#mobile-nav-more' ? more : menu},
        window: {matchMedia: query => {
            assert.equal(query, '(min-width: 761px)');
            return {addEventListener(_type, handler) { onResize = handler; }};
        }}
    });
    bind();
    onToggle({newState: 'open'}); assert.equal(attributes['aria-expanded'], 'true');
    onToggle({newState: 'closed'}); assert.equal(attributes['aria-expanded'], 'false');
    onResize({matches: false}); assert.equal(closed, 0);
    onResize({matches: true}); assert.equal(closed, 1);
});

test('public account instructions match reset request, reset completion and email verification', () => {
    for (const [route, search, expected] of [
        ['reset-password', '', 'Nhập email tài khoản để nhận liên kết đặt lại mật khẩu.'],
        ['reset-password', '?token=test', 'Nhập mật khẩu mới để bảo vệ tài khoản của bạn.'],
        ['verify-email', '', 'Kiểm tra liên kết xác nhận email của bạn.']
    ]) {
        const nodes = new Map();
        const panel = {hidden: true, querySelectorAll: () => [], querySelector: () => ({addEventListener() {}, focus() {}})};
        const document = {querySelector: selector => {
            if (selector === '#account-public-actions') return panel;
            if (!nodes.has(selector)) nodes.set(selector, {});
            return nodes.get(selector);
        }};
        const window = {};
        vm.runInNewContext(read('account.js'), {window, document, location: {search}, URLSearchParams});
        window.DictaLabAccount.showPublic(route);
        assert.equal(nodes.get('#auth-subtitle').textContent, expected);
        assert.equal(panel.hidden, false);
        assert.equal(nodes.get('#forgot-password-link').hidden, true);
    }
});

test('page navigation uses real links so browser link actions remain available', () => {
    for (const route of ['catalog', 'listening', 'conversation', 'history', 'dashboard', 'room']) {
        assert.match(html, new RegExp(`<a[^>]+data-route="${route}"`));
    }
    assert.doesNotMatch(html, /<button[^>]+data-route=/);
    assert.match(html, /<a[^>]+id="admin-nav-link"[^>]+href="\/admin"/);
    assert.match(read('app.js'), /<a class="history-card" href=/);
    assert.match(read('listening.js'), /<a class="text-button" href="\/listening\?view=history"/);
});

test('conversation navigation opens and loads the conversation view and session', async () => {
    const app = read('app.js');
    const start = app.indexOf('    const router = {');
    const end = app.indexOf('\n    function paginateList', start);
    const router = vm.runInNewContext(`${app.slice(start, end)}\nrouter`, {URLSearchParams});
    assert.equal(router.parse('/conversation/session', '').params.sessionId, 'session');
    assert.equal(router.toPath('conversation', {sessionId: 'session'}), '/conversation/session');
    const target = {id: 'conversation-view'}, catalog = {id: 'catalog-view'};
    const document = {body: {dataset: {route: 'catalog'}}, querySelector: selector => selector === '#conversation-view' ? target : {focus() {}}};
    const showSource = app.match(/    function _showView\([\s\S]*?\n    }\n/)[0];
    const navigationSource = app.match(/    function updateNavigation\([\s\S]*?\n    }\n/)[0];
    const show = vm.runInNewContext(`${showSource}\n${navigationSource}\n_showView`, {isAdminArea: false, state: {}, document,
        resetPracticeInput() {},
        elements: {views: [target, catalog], navLinks: []}, window: {scrollTo() {}}, roomPrepareStudyAudio() {}});
    let opened;
    const navigateSource = app.match(/    async function navigate\([\s\S]*?\n    }\n/)[0];
    const navigate = vm.runInNewContext(`${navigateSource}\nnavigate`, {state: {}, document, _showView: show,
        resolveUrlAndNavigate: async mapped => { opened = mapped; show(mapped.route); }});
    await navigate('conversation', {sessionId: 'session'});
    assert.equal(opened.route, 'conversation'); assert.equal(opened.params.sessionId, 'session');
    assert.equal(document.body.dataset.route, 'conversation'); assert.equal(target.hidden, false); assert.equal(catalog.hidden, true);
});

function paginationHarness(page, total, filtered = [], onPage) {
    const location = new URL(`https://dictalab.test/listening?level=B1&page=${page}`);
    let buttons = [];
    const nav = {
        setAttribute() {},
        set innerHTML(value) {
            buttons = [...value.matchAll(/data-page="([^"]+)"/g)].map(match => ({
                dataset: {page: match[1]}, addEventListener(_event, handler) { this.click = handler; }
            }));
        },
        querySelectorAll: () => buttons,
        querySelector: () => ({focus() {}})
    };
    const container = {
        children: Array.from({length: total}, (_, index) => ({hidden: filtered.includes(index)})),
        after() {}, scrollIntoView() {}
    };
    const context = {URL, URLSearchParams, document: {createElement: () => nav},
        window: {location, history: {replaceState(_state, _title, url) { location.href = url.href; }}}};
    const source = read('app.js').match(/    function paginateList\([\s\S]*?\n    }\n/)[0];
    const paginate = vm.runInNewContext(`${source}\npaginateList`, context);
    paginate(container, 6, onPage);
    return {container, nav, location, next: () => buttons[1].click()};
}

test('pagination bounds invalid URLs and preserves filter parameters', () => {
    for (const page of ['-1', '0', 'NaN', '1.5', 'Infinity']) {
        const h = paginationHarness(page, 15);
        assert.equal(h.location.searchParams.get('page'), '1');
        assert.equal(h.location.searchParams.get('level'), 'B1');
        assert.equal(h.container.children.filter(item => !item.hidden).length, 6);
    }
    const last = paginationHarness(999, 15);
    assert.equal(last.location.searchParams.get('page'), '3');
    assert.equal(last.container.children.filter(item => !item.hidden).length, 3);
});

test('pagination keeps filtered-out records hidden and updates the URL on next', () => {
    const h = paginationHarness(1, 12, [0, 2, 4]);
    h.next();
    assert.equal(h.location.searchParams.get('page'), '2');
    assert.deepEqual(h.container.children.map((item, i) => !item.hidden ? i : -1).filter(i => i >= 0), [9, 10, 11]);
});

test('empty and single-page lists need no pagination controls', () => {
    assert.equal(paginationHarness(999, 0).nav.hidden, true);
    assert.equal(paginationHarness(1, 6).nav.hidden, true);
});

test('pagination notifies detail selection with only the visible page after hiding other rows', () => {
    const pages = [];
    const h = paginationHarness(1, 7, [], items => pages.push(Array.from(items)));
    assert.deepEqual(pages[0], h.container.children.slice(0, 6));
    h.next();
    assert.deepEqual(pages[1], h.container.children.slice(6));
    assert.equal(pages[1][0].hidden, false);
    assert.ok(h.container.children.slice(0, 6).every(item => item.hidden));
});

test('mobile viewport resizing reveals an occluded word without moving focus or scrolling visible inputs', () => {
    const source = read('app.js');
    const start = source.indexOf('        window.visualViewport?.addEventListener');
    const end = source.indexOf('        elements.copyDictationSelection', start);
    let handler, scrolled = 0, frame;
    const input = {
        matches: () => true,
        getBoundingClientRect: () => ({top: 450, bottom: 496}),
        scrollIntoView() { scrolled++; }
    };
    const document = {body: {dataset: {route: 'practice'}}, activeElement: input};
    const window = {matchMedia: () => ({matches: true}), visualViewport: {
        offsetTop: 0, height: 400,
        addEventListener(_event, callback) { handler = callback; }
    }};
    vm.runInNewContext(source.slice(start, end), {document, window, requestAnimationFrame: callback => {frame = callback;}});
    handler(); frame();
    assert.equal(scrolled, 1);
    assert.equal(document.activeElement, input);
    window.visualViewport.height = 844;
    handler(); frame();
    assert.equal(scrolled, 1);
    window.visualViewport.height = 400;
    handler(); document.activeElement = null; frame();
    assert.equal(scrolled, 1);
});

function listeningHarness(api, customRoot) {
    const buttons = [{disabled: true, isConnected: true}, {disabled: true, isConnected: true}];
    const notices = [];
    const root = customRoot || {innerHTML: 'original', querySelectorAll: () => buttons,
        querySelector: selector => selector === 'audio' ? null : ({addEventListener() {}})};
    const window = {clearTimeout, setTimeout, DictaLabListeningBridge: {
        api, stopRecordedAudio() {}, showToast: message => notices.push(message)
    }};
    const source = read('listening.js').replace('{show, leave};', '{show, leave, loadView, mutate, history};');
    vm.runInNewContext(source, {window, URLSearchParams, location: {search: ''},
        document: {querySelector: selector => selector === '#listening-content' ? root : null, body: {dataset: {route: 'listening'}}}});
    return {module: window.DictaLabListening, bridge: window.DictaLabListeningBridge, root, buttons, notices};
}

test('an old response cannot overwrite the screen after leaving Listening', async () => {
    let resolve;
    const h = listeningHarness(() => new Promise(done => { resolve = done; }));
    const pending = h.module.loadView('/old-session');
    h.module.leave();
    resolve({stage: 'READY'});
    assert.equal(await pending, null);
    assert.equal(h.root.innerHTML, 'original');
});

test('only the most recent Listening view request may render', async () => {
    const resolves = [];
    const h = listeningHarness(() => new Promise(done => resolves.push(done)));
    const old = h.module.loadView('/report');
    const recent = h.module.loadView('/review');
    resolves[1]({title: 'review'});
    assert.equal((await recent).title, 'review');
    resolves[0]({title: 'report'});
    assert.equal(await old, null);
});

test('failed assessment submission restores buttons for retry', async () => {
    const h = listeningHarness(async () => { throw new Error('offline'); });
    await h.module.mutate('/second-pass', {});
    assert.equal(h.root.innerHTML, 'original');
    assert.ok(h.buttons.every(button => !button.disabled));
    assert.deepEqual(h.notices, ['offline']);
});

test('leaving immediately after typing flushes the draft to its original session', async () => {
    const requests = [];
    const textarea = {value: 'Beth is from New York.', addEventListener(_event, handler) { this.input = handler; }};
    const status = {textContent: ''};
    const form = {isConnected: true, addEventListener() {},
        querySelector: selector => selector === 'textarea' ? textarea : status};
    const root = {innerHTML: '', querySelector: selector => selector === '#listening-recall-form' ? form : null};
    const h = listeningHarness(async (url, options) => {
        requests.push({url, options});
        return {stage: 'RECALL', passNumber: 1, title: 'Listening', recallDraft: ''};
    }, root);
    await h.module.show('original-session');
    textarea.input();
    h.module.leave();
    form.isConnected = false;
    await h.module.show('another-session');
    const writes = requests.filter(request => request.options?.method === 'PUT');
    assert.equal(writes.length, 1);
    assert.equal(writes[0].url, '/api/v1/listening/sessions/original-session/recall');
    assert.equal(writes[0].options.body.text, textarea.value);
});

test('reopening an invitation restores existing room membership before checking capacity', async () => {
    const requested = [];
    let restored = false;
    const source = read('app.js').match(/    async function roomOpenByInviteCode\([\s\S]*?\n    }\n/)[0];
    const join = vm.runInNewContext(`${source}\nroomOpenByInviteCode`, {
        parseRoomInvite: code => ({code}), state: {room: null}, router: {push() {}},
        elements: {roomLobbyStatus: {textContent: ''}},
        api: async url => { requested.push(url); return {roomId: 'existing'}; },
        roomRestoreMine: async () => { restored = true; }
    });
    await join('already-approved-invite');
    assert.equal(restored, true);
    assert.deepEqual(requested, ['/api/v1/rooms/mine']);
});

test('Listening history navigation retains its subview and page', async () => {
    let received;
    const source = read('app.js').match(/    async function navigate\([\s\S]*?\n    }\n/)[0];
    const navigate = vm.runInNewContext(`${source}\nnavigate`, {
        state: {}, resolveUrlAndNavigate: async parsed => { received = parsed; }
    });
    await navigate('listening', {view: 'history', page: 2});
    assert.equal(received.params.view, 'history');
    assert.equal(received.params.page, 2);
});

test('Listening history uses the first API page and preserves records when paging forward and back', async () => {
    const controls = new Map();
    const root = {innerHTML: '', querySelectorAll: () => [], querySelector(selector) {
        if (selector === 'audio') return null;
        if (!controls.has(selector)) controls.set(selector, {addEventListener(_event, handler) { this.click = handler; }});
        return controls.get(selector);
    }};
    const requests = [], navigations = [];
    const h = listeningHarness(async path => {
        requests.push(path);
        const page = Number(new URL(path, 'https://dictalab.test').searchParams.get('page'));
        return {page, size: 10, total: 12, items: [{id: `session-${page}`, title: `Page ${page} record`, mode: 'TEST', stage: 'COMPLETE'}]};
    }, root);
    // The public URL and the shared router use one-based pages.
    const source = read('app.js').match(/    async function navigate\([\s\S]*?\n    }\n/)[0];
    const navigate = vm.runInNewContext(`${source}\nnavigate`, {
        state: {}, resolveUrlAndNavigate: async parsed => {
            navigations.push(parsed.params.page);
            await h.module.history(parsed.params.page);
        }
    });
    // The real history buttons call the same bridge as the catalog/history links.
    h.bridge.navigate = navigate;
    await navigate('listening', {view: 'history'});
    assert.match(root.innerHTML, /Page 0 record/);
    assert.match(root.innerHTML, /Trang 1 · 12 bài/);
    assert.match(root.innerHTML, /id="listening-history-prev" disabled/);
    await controls.get('#listening-history-next').click();
    assert.match(root.innerHTML, /Page 1 record/);
    assert.match(root.innerHTML, /Trang 2 · 12 bài/);
    assert.match(root.innerHTML, /id="listening-history-next" disabled/);
    await controls.get('#listening-history-prev').click();
    assert.match(root.innerHTML, /Page 0 record/);
    assert.deepEqual(navigations, [1, 2, 1]);
    assert.deepEqual(requests, [
        '/api/v1/listening/history/page?page=0&size=10',
        '/api/v1/listening/history/page?page=1&size=10',
        '/api/v1/listening/history/page?page=0&size=10'
    ]);
    for (const page of [0, -1, NaN, 1.5]) {
        await h.module.history(page);
        assert.equal(requests.at(-1), '/api/v1/listening/history/page?page=0&size=10');
    }
});

test('history mode links navigate internally, retain the requested subview and preserve modified clicks', async () => {
    const links = [...html.matchAll(/<a[^>]+data-route="(listening|conversation)"[^>]*>/g)]
        .filter(match => match[0].includes('class="text-button"')).map(match => ({
            tagName: 'A', dataset: {route: match[1], view: match[0].match(/data-view="([^"]+)"/)?.[1]},
            addEventListener(_event, handler) { this.click = handler; }
        }));
    assert.equal(links.length, 2);
    const source = read('app.js').match(/        elements\.routeButtons\.forEach\(link => \{[\s\S]*?\n        \}\);/)[0];
    const calls = [];
    vm.runInNewContext(source, {elements: {routeButtons: links}, document: {querySelector: () => null},
        navigate: (route, params) => calls.push({route, view: params.view})});
    let prevented = 0;
    for (const link of links) {
        await link.click({button: 0, preventDefault() { prevented++; }});
        await link.click({button: 0, ctrlKey: true, preventDefault() { assert.fail('modified clicks must remain native'); }});
    }
    assert.equal(prevented, 2);
    assert.deepEqual(calls, [{route: 'listening', view: 'history'}, {route: 'conversation', view: undefined}]);
});

test('startup keeps the auth form hidden while checking the session and clears loading on success or rejection', async () => {
    assert.match(html, /<main id="auth-screen"[^>]* hidden/);
    const source = read('app.js').match(/    async function init\([\s\S]*?\n    }\n/)[0];
    for (const authenticated of [true, false]) {
        let finish, removed = false, entered = false, shownAuth = false;
        const pending = new Promise((resolve, reject) => {
            finish = () => authenticated ? resolve({id: 7}) : reject(Object.assign(new Error('Expired'), {status: 401}));
        });
        const init = vm.runInNewContext(`${source}\ninit`, {
            bindEvents() {}, refreshCsrf: async () => {}, api: () => pending, state: {},
            isAdminArea: false, URLSearchParams, location: {pathname: '/listening', search: '?view=history'},
            window: {location: {pathname: '/listening', search: '?view=history'}},
            router: {parse: () => ({route: 'listening'})},
            enterApplication: async () => { entered = true; },
            showAuthScreen() { shownAuth = true; },
            document: {querySelector: () => ({remove() { removed = true; }})}
        });
        const ready = init();
        await Promise.resolve();
        assert.equal(removed, false);
        assert.equal(entered, false);
        assert.equal(shownAuth, false);
        finish();
        await ready;
        assert.equal(removed, true);
        assert.equal(entered, authenticated);
        assert.equal(shownAuth, !authenticated);
    }
});

test('admin startup does not access learner elements or request learning data', async () => {
    const source = read('app.js').match(/    async function enterApplication\([\s\S]*?\n    }\n/)[0];
    const requested = [];
    const loading = {hidden: false};
    const elements = {appShell: {hidden: true}, accountName: {}, accountEmail: {}};
    const enter = vm.runInNewContext(`${source}\nenterApplication`, {
        isAdminArea: true, elements, state: {currentUser: {role: 'ADMIN', displayName: 'Admin', email: 'admin@test.local'}},
        document: {querySelector: () => loading}, location: {pathname: '/admin/users', search: ''},
        router: {parse: () => ({route: 'admin-users'})},
        resolveUrlAndNavigate: async parsed => requested.push(parsed.route),
        api: () => assert.fail('admin startup must not load catalog or learner dashboard')
    });
    await enter();
    assert.equal(elements.appShell.hidden, false);
    assert.equal(loading.hidden, true);
    assert.deepEqual(requested, ['admin-users']);
});

test('cross-area navigation loads its own document and expired admin sessions retain destination', async () => {
    const requests = [];
    const source = read('app.js').match(/    async function resolveUrlAndNavigate\([\s\S]*?\n    }\n/)[0];
    for (const [isAdminArea, route, target] of [[false, 'admin-users', '/admin/users'], [true, 'catalog', '/catalog']]) {
        const resolve = vm.runInNewContext(`${source}\nresolveUrlAndNavigate`, {
            isAdminArea, state: {currentUser: {id: 1}}, window: {DictaLabAdmin: {canLeave: () => true}}, router: {toPath: () => target}, location: {assign: path => requests.push(path)}
        });
        await resolve({route, params: {}});
    }
    assert.deepEqual(requests, ['/admin/users', '/catalog']);
    let login;
    const authSource = read('app.js').match(/    function showAuthScreen\([\s\S]*?\n    }\n/)[0];
    const requireLogin = vm.runInNewContext(`${authSource}\nshowAuthScreen`, {
        isAdminArea: true, location: {pathname: '/admin/lessons/42', search: '?page=3', replace: value => { login = value; }}
    });
    requireLogin();
    assert.equal(login, '/login?next=%2Fadmin%2Flessons%2F42%3Fpage%3D3');
});

test('publishing a lesson stays in the admin editor', async () => {
    let refreshed;
    const source = read('app.js').match(/    async function toggleAdminPublication\([\s\S]*?\n    }\n/)[0];
    const toggle = vm.runInNewContext(`${source}\ntoggleAdminPublication`, {
        confirm: () => true,
        selectedAdminLesson: () => ({id: 42, published: false}),
        elements: {adminPublicationButton: {}}, api: async () => ({id: 42, published: true}),
        refreshAdminLessons: async id => { refreshed = id; }, setAdminStatus() {},
        selectLevel: () => assert.fail('publication must not navigate to the learner catalog')
    });
    await toggle();
    assert.equal(refreshed, 42);
});

test('login initialization preserves the admin destination supplied by the server', () => {
    const source = read('app.js').match(/    function showAuthScreen\([\s\S]*?\n    }\n/)[0];
    let redirected;
    const location = {origin: 'https://dictalab.test', pathname: '/login', search: '?next=%2Fadmin%2Fusers'};
    const notice = {};
    const showAuth = vm.runInNewContext(`${source}\nshowAuthScreen`, {
        isAdminArea: false, state: {}, stopRecordedAudio() {}, stopTimer() {}, resetPracticeInput() {}, clearPracticeDrafts() {}, switchAuthMode() {},
        document: {body: {dataset: {}}, querySelector: () => notice}, elements: {appShell: {}, authScreen: {}, authStatus: {}},
        URL, location,
        router: {parse: path => ({route: path.startsWith('/admin') ? 'admin-users' : 'login', query: new URLSearchParams(location.search)})},
        window: {location, history: {replaceState(_state, _title, path) { redirected = path; }}, requestAnimationFrame() {}}
    });
    showAuth();
    assert.equal(redirected, '/login?next=%2Fadmin%2Fusers');
    assert.equal(notice.hidden, false);
    assert.match(notice.textContent, /trang quản trị/);
    location.search = '?next=%2F%2Fexternal.test';
    showAuth();
    assert.equal(notice.hidden, true);
    assert.equal(notice.textContent, '');
});

test('legacy Listening administration links resolve to the dedicated admin page', () => {
    const start = read('app.js').indexOf('    const router = {');
    const end = read('app.js').indexOf('\n    function paginateList', start);
    const router = vm.runInNewContext(`${read('app.js').slice(start, end)}\nrouter`, {URLSearchParams});
    for (const path of ['/listening', '/admin/listening']) {
        const result = router.parse(path, '?view=admin&edit=42&page=2');
        assert.equal(result.route, 'admin-listening');
        assert.equal(router.toPath(result.route, result.params), '/admin/listening?page=2&edit=42');
    }
});
