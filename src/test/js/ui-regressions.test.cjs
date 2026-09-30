const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const read = name => fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js', name), 'utf8');
const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/index.html'), 'utf8');

test('page navigation uses real links so browser link actions remain available', () => {
    for (const route of ['catalog', 'listening', 'history', 'dashboard', 'room']) {
        assert.match(html, new RegExp(`<a[^>]+data-route="${route}"`));
    }
    assert.doesNotMatch(html, /<button[^>]+data-route=/);
    assert.match(html, /<a[^>]+id="admin-nav-link"[^>]+href="\/admin"/);
    assert.match(read('app.js'), /<a class="history-card" href=/);
    assert.match(read('listening.js'), /<a class="text-button" href="\/listening\?view=history"/);
});

function paginationHarness(page, total, filtered = []) {
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
    paginate(container, 6);
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

function listeningHarness(api, customRoot) {
    const buttons = [{disabled: true, isConnected: true}, {disabled: true, isConnected: true}];
    const notices = [];
    const root = customRoot || {innerHTML: 'original', querySelectorAll: () => buttons,
        querySelector: selector => selector === 'audio' ? null : ({addEventListener() {}})};
    const window = {clearTimeout, setTimeout, DictaLabListeningBridge: {
        api, stopRecordedAudio() {}, showToast: message => notices.push(message)
    }};
    const source = read('listening.js').replace('{show, leave};', '{show, leave, loadView, mutate};');
    vm.runInNewContext(source, {window, URLSearchParams, location: {search: ''},
        document: {querySelector: () => root, body: {dataset: {route: 'listening'}}}});
    return {module: window.DictaLabListening, root, buttons, notices};
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
            isAdminArea, window: {DictaLabAdmin: {canLeave: () => true}}, router: {toPath: () => target}, location: {assign: path => requests.push(path)}
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
    const location = {pathname: '/login', search: '?next=%2Fadmin%2Fusers'};
    const showAuth = vm.runInNewContext(`${source}\nshowAuthScreen`, {
        isAdminArea: false, state: {}, stopRecordedAudio() {}, stopTimer() {}, switchAuthMode() {},
        document: {body: {dataset: {}}}, elements: {appShell: {}, authScreen: {}, authStatus: {}},
        router: {parse: () => ({route: 'login', query: new URLSearchParams(location.search)})},
        window: {location, history: {replaceState(_state, _title, path) { redirected = path; }}, requestAnimationFrame() {}}
    });
    showAuth();
    assert.equal(redirected, '/login?next=%2Fadmin%2Fusers');
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
