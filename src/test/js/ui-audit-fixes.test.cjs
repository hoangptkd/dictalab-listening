const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = name => fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js', name), 'utf8');
const escape = value => String(value ?? '').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
const extract = (text, start, end) => text.slice(text.indexOf(start), text.indexOf(end));

test('support URLs preserve the explicit all-status filter through paging and detail selection', () => {
    for (const pathname of ['/admin/support', '/admin/users']) {
        const location = {href: 'https://dictalab.test' + pathname};
        const window = {}, history = {replaceState(_state, _title, url) { location.href = String(url); }};
        vm.runInNewContext(source('admin-ui.js'), {location, window, history, URL, URLSearchParams,
            document: {body: {dataset: {area: 'learner'}}}});
        window.DictaLabAdmin.url({status: '', q: '', page: 2, ticket: 7}, true);
        const params = new URL(location.href).searchParams;
        assert.equal(params.has('status'), pathname === '/admin/support');
        assert.equal(params.get('q'), null); assert.equal(params.get('page'), '2'); assert.equal(params.get('ticket'), '7');
    }
});

test('admin navigation selects the library by query/hash and restores ordinary route navigation', () => {
    const links = [{adminSection: 'lessons', route: 'admin'}, {adminSection: 'library'},
        {route: 'admin-support'}, {route: 'catalog'}].map(dataset => ({dataset, attrs: {},
        classList: {toggle(_name, active) { this.active = active; }},
        setAttribute(name, value) { this.attrs[name] = value; }, removeAttribute(name) { delete this.attrs[name]; }}));
    const location = {search: '', hash: ''};
    const update = vm.runInNewContext(extract(source('app.js'), '    function updateNavigation(', '    function showAuthScreen(') + '\nupdateNavigation',
        {elements: {navLinks: links}, location, URLSearchParams});
    for (const [search, hash, expected] of [['', '', 0], ['?library=1', '', 1], ['', '#library-management', 1],
        ['?library=0', '#other', 0], ['?library=1&page=3', '#library-management', 1]]) {
        Object.assign(location, {search, hash}); update('admin');
        assert.equal(links.filter(link => link.classList.active).length, 1);
        assert.equal(links[expected].attrs['aria-current'], expected === 1 ? 'location' : 'page');
    }
    update('admin-support'); assert.equal(links[2].attrs['aria-current'], 'page');
    update('practice'); assert.equal(links[3].attrs['aria-current'], 'page');
    assert.equal(links[1].attrs['aria-current'], undefined);
});

function listeningHarness(name, data) {
    const text = source('listening.js'), clicks = {}, requests = [];
    const element = key => ({addEventListener(_event, action) { clicks[key] = action; }, dataset: {mark: '316', kind: 'NOT_HEARD'}});
    const root = {querySelector: element, querySelectorAll(selector) {
        return selector === '[data-mark]' ? [element('mark')] : [];
    }};
    const types = extract(text, '    const questionTypeLabels', '    let sessionId');
    const start = `    async function ${name}(`;
    const end = {report: '    async function review(', review: '    async function playReview(', history: '    window.DictaLabListening'}[name];
    const render = vm.runInNewContext(types + extract(text, start, end) + `\n${name}`, {
        root: () => root, window: {clearTimeout() {}}, reportTimer: null, sessionId: 'session', escape,
        loadView: async () => data, followLink() {}, review: async () => {},
        bridge: () => ({api: async (url, options) => requests.push({url, options}), navigate: (...args) => requests.push(args)})
    });
    return {render, root, clicks, requests};
}

test('listening reports use the editor labels including recommendations and safe unknown types', async () => {
    const h = listeningHarness('report', {title: '<Title>', passes: [{pass_number: 1, grading_status: 'DONE'}],
        questionTypes: {MAIN_IDEA: [40], DETAIL: [90], INFERENCE: [80], SPEAKER_INTENT: [60], toString: [70], '<script>': [70]}});
    await h.render();
    for (const label of ['Ý chính', 'Chi tiết', 'Suy luận', 'Ý định người nói', 'Loại câu hỏi khác']) assert.ok(h.root.innerHTML.includes(label));
    assert.match(h.root.innerHTML, /loại câu hỏi Ý chính có độ chính xác thấp nhất \(40\.0%\)/);
    assert.doesNotMatch(h.root.innerHTML, /MAIN_IDEA|DETAIL|INFERENCE|SPEAKER_INTENT|<script>/);
    assert.match(h.root.innerHTML, /&lt;Title&gt;/);
});

test('review numbers follow transcript order with noncontiguous IDs; API keeps the real ID', async () => {
    const h = listeningHarness('review', {title: 'Review', sentences: [{id: 316, transcript: 'First'}, {id: 999, transcript: 'Second'}],
        content: {questions: []}, marks: [{sentence_id: '316', difficulty_type: 'NOT_HEARD'},
            {sentence_id: 999, difficulty_type: 'HEARD_NOT_UNDERSTOOD'}, {sentence_id: 123, difficulty_type: 'NOT_HEARD'}]});
    await h.render();
    assert.match(h.root.innerHTML, /Câu 1: Không nghe ra/);
    assert.match(h.root.innerHTML, /Câu 2: Chưa hiểu nghĩa/);
    assert.match(h.root.innerHTML, /Câu không còn trong transcript: Không nghe ra/);
    assert.doesNotMatch(h.root.innerHTML, /Câu (316|999|123):/);
    await h.clicks.mark();
    assert.equal(h.requests[0].options.body.sentenceId, 316);
    assert.equal(h.requests[0].options.body.type, 'NOT_HEARD');
});

test('listening pagination keeps disabled boundaries and navigation after component styling', async () => {
    for (const page of [1, 2, 3]) {
        const h = listeningHarness('history', {items: [], size: 10, total: 21}); await h.render(page);
        assert.match(h.root.innerHTML, /<nav class="pagination"/);
        for (const [id, disabled] of [['prev', page === 1], ['next', page === 3]]) {
            const markup = h.root.innerHTML.match(new RegExp(`<button class="text-button"[^>]+id="listening-history-${id}"[^>]*>`))[0];
            assert.equal(markup.includes('disabled'), disabled);
        }
        await h.clicks['#listening-history-next']();
        assert.equal(h.requests[0][1].page, page + 1);
    }
});

function supportHarness(filterStatus = '', failRefresh = false) {
    const text = source('account.js'), requests = [], params = [], handlers = {};
    const item = {id: 7, subject: 'Audio', email: 'test@example.test', status: 'OPEN', category: 'AUDIO',
        admin_note: '', edit_version: 1, events: []};
    const statusNode = {dataset: {}}, events = {}, master = {querySelector: () => null, querySelectorAll: () => []};
    const detail = {}, heading = {setAttribute() {}, focus() {}};
    const form = {closest: () => root, hasAttribute: () => true, addEventListener(_type, handler) { handlers.save = handler; }};
    const root = {setAttribute() {}, querySelector(selector) {
        return ({'.admin-detail': detail, '.admin-detail h2': heading, '[data-ticket]': form,
            '[data-events]': events, '.admin-master': master, '[data-status]': statusNode})[selector] ?? null;
    }, querySelectorAll: () => []};
    const filter = {q: 'Audio', status: filterStatus, category: 'AUDIO', referenceType: '', from: '', to: '', sort: 'oldest', page: 2, ticket: '7'};
    let listRequests = 0;
    const document = {body: {dataset: {route: 'admin-support'}}, querySelector: () => root};
    const ui = {query: () => filter, url: value => params.push(value), track() {}, clean() {},
        events: value => JSON.stringify(value), badge: value => value, stamp: () => '', pager: value => `Total:${value.total}`,
        failure: (_root, message) => { throw Error(message); }, busy: (_form, action) => action()};
    const api = async (url, options) => {
        requests.push({url, options});
        if (options?.method === 'PUT') { assert.equal(options.body.version, item.edit_version);
            Object.assign(item, {status: options.body.status, admin_note: options.body.adminNote, edit_version: item.edit_version + 1,
                events: ['saved']}); return {}; }
        if (url.includes('/page?')) { listRequests++;
            if (failRefresh && listRequests > 1) throw Error('offline');
            const items = !filterStatus || item.status === filterStatus ? [{...item}] : [];
            return {items, total: items.length, page: 0, size: 20};
        }
        return {...item};
    };
    const support = vm.runInNewContext(extract(text, '    const bridge =', '    function enhancePasswords(')
        + extract(text, '    let adminRequest', '    async function usage(')
        + extract(text, '    function ticketList(', '    function bindGrading(') + '\ntickets', {
        document, window: {DictaLabAdmin: ui, DictaLabListeningBridge: {api}}, URLSearchParams,
        FormData: class {constructor() {} entries() { return Object.entries(this.constructor.data)[Symbol.iterator](); }
            static data = {status: 'IN_PROGRESS', adminNote: 'Reply'}; },
        detailUrl: () => '/detail', listUrl: () => '/list', bindGrading() {}, location: {search: ''}
    });
    const save = () => handlers.save({preventDefault() {}, submitter: {disabled: false}});
    return {support, save, root, master, detail, requests, params, item, statusNode, document, events};
}

test('support save refreshes list/count with current filters without replacing detail or losing edit version', async () => {
    for (const filter of ['', 'OPEN']) {
        const h = supportHarness(filter); await h.support(); const detail = h.detail.innerHTML;
        await h.save();
        assert.equal(h.detail.innerHTML, detail, 'selected detail and any additional grading edits stay mounted');
        assert.equal(h.item.edit_version, 2);
        assert.match(h.events.innerHTML, /saved/);
        assert.match(h.master.innerHTML, filter ? /Không có yêu cầu phù hợp/ : /Đang xử lý/);
        assert.match(h.master.innerHTML, filter ? /0 yêu cầu phù hợp/ : /1 yêu cầu phù hợp/);
        const query = new URL(h.requests.at(-1).url, 'https://test.local').searchParams;
        assert.equal(query.get('status'), filter); assert.equal(query.get('q'), 'Audio'); assert.equal(query.get('category'), 'AUDIO');
        assert.equal(h.params.at(-1).ticket, '7'); assert.equal(h.params.at(-1).page, 1);
        await h.save(); assert.equal(h.item.edit_version, 3);
        assert.match(h.statusNode.textContent, /Đã lưu phản hồi\./);
    }
});

test('a failed list refresh reports that the reply was saved and a second save uses its new version', async () => {
    const h = supportHarness('', true); await h.support(); await h.save();
    assert.equal(h.item.edit_version, 2);
    assert.match(h.statusNode.textContent, /Đã lưu phản hồi, nhưng chưa tải lại danh sách: offline/);
    assert.equal(h.statusNode.dataset.error, 'true');
    await h.save(); assert.equal(h.item.edit_version, 3);
});
