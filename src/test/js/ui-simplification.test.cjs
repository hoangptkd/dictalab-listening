const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/library.js'), 'utf8');
const accountSource = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/account.js'), 'utf8');

test('support only hides pagination for an empty first page with no following page', async () => {
    for (const [page, count, hasMore, expected] of [[0,0,false,false],[0,1,false,true],[0,0,true,true],[2,0,false,true]]) {
        const category = {value: 'AUDIO', addEventListener() {}, closest: () => ({after() {}})};
        const form = {querySelector: () => category};
        const root = {innerHTML: '', querySelector(selector) {
            if (selector === '#support-form') return form;
            if (selector.includes('category')) return category;
            if (selector === '#support-prev' || selector === '#support-next') return this.innerHTML.includes(selector.slice(1)) ? {addEventListener() {}} : null;
        }};
        const render = vm.runInNewContext(accountSource.slice(accountSource.indexOf('    async function support('), accountSource.indexOf('    let adminRequest')) + '\nsupport', {
            bridge: () => ({api: async () => ({tickets: Array.from({length: count}, () => ({id: 1, subject: 'Audio', status: 'OPEN'})), hasMore})}),
            document: {querySelector: () => root, createElement: () => ({})}, URLSearchParams, location: {search: ''},
            categories: {AUDIO: 'Audio'}, ticketStates: {OPEN: 'Open'}, escape: String, submit() {}
        });
        await render(page);
        assert.equal(root.innerHTML.includes('id="support-prev"'), expected);
        assert.equal(root.innerHTML.includes('id="support-next"'), expected);
        assert.ok(root.innerHTML.includes('chưa có thời hạn phản hồi cố định'));
        if (page > 0) assert.match(root.innerHTML, /id="support-prev"[^>]*>Trang trước/);
    }
});

test('account server error reopens its own disclosure and focuses the error without clearing fields', async () => {
    let handler, focused = false;
    const disclosure = {open: false}, button = {disabled: false};
    const local = {textContent: '', dataset: {}, focus() { focused = true; }};
    const global = {textContent: '', dataset: {}};
    const page = {querySelector: () => global};
    const form = {value: 'keep this draft', addEventListener(_event, action) { handler = action; },
        closest: selector => selector === 'details.account-disclosure' ? disclosure : page,
        querySelector: selector => selector === '[data-form-status]' ? local : null, hasAttribute: () => false};
    const submit = vm.runInNewContext(accountSource.slice(accountSource.indexOf('    const status ='), accountSource.indexOf('    function enhancePasswords(')) + '\nsubmit', {
        FormData: class {entries() { return []; }}, window: {}
    });
    submit(form, async () => { throw Error('Try again'); });
    await handler({preventDefault() {}, submitter: button});
    assert.equal(disclosure.open, true);
    assert.equal(local.textContent, 'Try again');
    assert.equal(local.dataset.error, 'true');
    assert.equal(global.textContent, '');
    assert.equal(focused, true);
    assert.equal(form.value, 'keep this draft');
    assert.equal(button.disabled, false);
});

test('advanced catalog filters count URL values, retain selection and reset every criterion', () => {
    const params = new URLSearchParams('topic=Work&duration=short&search=meeting&status=active');
    const summary = {}, reset = {};
    const filters = {querySelector: selector => selector === 'summary' ? summary : reset};
    const nav = {innerHTML: ''}, catalog = {innerHTML: ''};
    const document = {querySelector: selector => ({'#library-navigation': nav, '#catalog-summary': catalog,
        '.library-filters': filters})[selector] || null};
    const course = {id: 'ielts-band-4-0', title: 'Band 4.0', ready: true};
    const scope = {document, context: () => ({params, goal: 'IELTS', courses: [course], course}),
        bridge: () => ({currentUser: () => ({id: 1}), library: {busy: () => false}}),
        goals: {IELTS: 'IELTS'}, snapshot: {lessons: [], courses: [course], activeSessions: [], groups: [],
            metadata: [{reviewed: true, topic: 'Work', focus: 'Detail', accent: 'UK', speakerCount: 2}]},
        progress: () => ({total: 8, done: 2}), title: x => x.title, escape: x => String(x ?? ''),
        members: () => [{difficulty: 2}], courseLessons: () => new Set(),
        selectOptions: (values, selected) => `<option selected>${selected ?? ''}</option>`,
        query: () => params, renderList() {},
        updateUrl: changes => Object.entries(changes).forEach(([key, value]) => value == null || value === '' ? params.delete(key) : params.set(key, value)),
        localStorage: {getItem: () => null, setItem() {}}, clearTimeout() {}, setTimeout: fn => { fn(); return 1; }};
    const fn = source.slice(source.indexOf('    function navigation()'), source.indexOf('    function renderList()'));
    const render = vm.runInNewContext(fn + '\nnavigation', scope);
    render();
    assert.match(catalog.innerHTML, /Bộ lọc thêm · 2 đang áp dụng/);
    assert.ok(catalog.innerHTML.includes('<option selected>Work</option>'));
    assert.ok(nav.innerHTML.includes('2/8 bài hoàn thành'));
    assert.match(nav.innerHTML, /aria-describedby="library-level-ielts-band-4-0"/);
    for (const name of ['search','status','topic','duration','focus','difficulty','accent','speakers','group']) {
        assert.ok(catalog.innerHTML.includes(`name="${name}"`), name);
    }
    filters.onchange({target: {name: 'accent', value: 'UK'}});
    assert.equal(summary.textContent, 'Bộ lọc thêm · 3 đang áp dụng');
    assert.equal(reset.hidden, false);
    assert.equal(params.get('topic'), 'Work');
    reset.onclick();
    for (const name of ['search','status','topic','duration','focus','difficulty','accent','speakers','group']) assert.equal(params.has(name), false);
    assert.match(catalog.innerHTML, /data-library-reset hidden/);
});
