const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/js/app.js', 'utf8');
const helpers = source.slice(source.indexOf('    let tokenRefreshPromise = null;'), source.indexOf('    function queuePracticeMutation'));
const csrfHelper = source.slice(source.indexOf('    async function refreshCsrf()'), source.indexOf('    async function enterApplication()'));

function client(fetch, locks) {
    const state = {csrfHeader: 'X-XSRF-TOKEN', csrfToken: 'csrf', currentUser: {id: 1}, session: {sessionId: 7}};
    const redirects = [];
    const context = vm.createContext({fetch, navigator: {locks}, AbortSignal, state, isAdminArea: false,
        showAuthScreen: message => redirects.push(message)});
    vm.runInContext(csrfHelper + helpers + '\nthis.request = authenticatedFetch; this.callApi = api;', context);
    context.request.api = context.callApi;
    context.request.state = state;
    context.request.redirects = redirects;
    return context.request;
}
function locks() {
    let pending = Promise.resolve();
    return {request(name, fn) { const next = pending.then(fn); pending = next.catch(() => {}); return next; }};
}
const response = (status, payload = {message: 'test error'}, headers = {}) => new Response(
    status === 204 ? null : JSON.stringify(payload),
    {status, headers: {...(status === 204 ? {} : {'Content-Type': 'application/json'}), ...headers}});
const csrfRejected = () => response(403, {message: 'CSRF rejected'}, {'X-CSRF-Error': '1'});

test('two tabs refresh once, then replay only requests rejected by authentication', async () => {
    const mutex = locks(); let valid = false, refreshes = 0, writes = 0;
    const fetch = async (path, request) => {
        if (path === '/api/auth/refresh') { assert.equal(request.headers['X-XSRF-TOKEN'], 'csrf'); refreshes++; valid = true; return response(204); }
        if (path === '/api/auth/me') return response(valid ? 200 : 401);
        if (!valid) return response(401);
        writes++; return response(200);
    };
    const first = client(fetch, mutex), second = client(fetch, mutex);
    const results = await Promise.all([first('/api/write', {method: 'POST'}), second('/api/write', {method: 'POST'})]);
    assert.equal(refreshes, 1); assert.equal(writes, 2); assert.ok(results.every(result => result.ok));
});

test('network failures and 503 responses never replay a business request', async () => {
    let calls = 0;
    const busy = client(async () => { calls++; return response(503); }, locks());
    assert.equal((await busy('/api/write', {method: 'POST'})).status, 503); assert.equal(calls, 1);
    const failed = client(async () => { calls++; throw new Error('network'); }, locks());
    await assert.rejects(failed('/api/write', {method: 'POST'}), /network/); assert.equal(calls, 2);
});

test('refresh failure stops without replay or refresh loops', async () => {
    const paths = [];
    const request = client(async path => { paths.push(path); return response(path === '/api/auth/refresh' ? 503 : 401); }, locks());
    await assert.rejects(request('/api/write', {method: 'POST'}), error => error.status === 503);
    assert.deepEqual(paths, ['/api/write', '/api/auth/me', '/api/auth/refresh']);
});

test('public auth endpoints can bypass refresh, and older browsers fail without concurrent token reuse', async () => {
    let calls = 0;
    const request = client(async () => { calls++; return response(401); }, null);
    assert.equal((await request('/api/auth/login', {}, false)).status, 401);
    await assert.rejects(request('/api/write', {}), error => error.status === 401);
    assert.equal(calls, 2);
});

test('idle session recovers stale CSRF and expired access without losing the pending action or screen', async () => {
    const paths = []; let valid = false, writes = 0;
    const body = {answer: 'unfinished answer'};
    const request = client(async (path, options) => {
        paths.push(path);
        if (path === '/api/auth/csrf') {
            assert.equal(options.cache, 'no-store');
            return response(200, {headerName: 'X-XSRF-TOKEN', token: 'fresh'});
        }
        if (path === '/api/auth/me') return response(valid ? 200 : 401);
        if (options.headers['X-XSRF-TOKEN'] !== 'fresh') return csrfRejected();
        if (path === '/api/auth/refresh') { valid = true; return response(204); }
        assert.equal(options.body, JSON.stringify(body));
        if (!valid) return response(401);
        writes++; return response(200, {saved: true});
    }, locks());
    const user = request.state.currentUser, session = request.state.session;
    assert.deepEqual(await request.api('/api/answer', {method: 'POST', body}), {saved: true});
    assert.equal(writes, 1);
    assert.deepEqual(paths, ['/api/answer', '/api/auth/csrf', '/api/answer', '/api/auth/me', '/api/auth/refresh', '/api/answer']);
    assert.equal(request.state.currentUser, user);
    assert.equal(request.state.session, session);
    assert.deepEqual(request.redirects, []);
});

test('refresh itself can recover CSRF before rotating, and replay uses the new CSRF token', async () => {
    let valid = false, refreshAttempts = 0, rotations = 0, writes = 0;
    const request = client(async (path, options) => {
        if (path === '/api/auth/csrf') return response(200, {headerName: 'X-XSRF-TOKEN', token: 'fresh'});
        if (path === '/api/auth/me') return response(valid ? 200 : 401);
        if (path === '/api/auth/refresh') {
            refreshAttempts++;
            if (options.headers['X-XSRF-TOKEN'] !== 'fresh') return csrfRejected();
            rotations++; valid = true; return response(204);
        }
        if (!valid) return response(401);
        assert.equal(options.headers['X-XSRF-TOKEN'], 'fresh');
        writes++; return response(200);
    }, locks());
    assert.equal((await request('/api/write', {method: 'POST'})).status, 200);
    assert.equal(refreshAttempts, 2); assert.equal(rotations, 1); assert.equal(writes, 1);
});

test('real permission denial never refreshes or repeats a write', async () => {
    const paths = [];
    const request = client(async path => { paths.push(path); return response(403); }, locks());
    await assert.rejects(request.api('/api/admin/write', {method: 'POST'}), error => error.status === 403);
    assert.deepEqual(paths, ['/api/admin/write']);
    assert.deepEqual(request.redirects, []);
});

test('CSRF rejection retry is bounded, including public login requests', async () => {
    const paths = [];
    const request = client(async path => {
        paths.push(path);
        return path === '/api/auth/csrf'
            ? response(200, {headerName: 'X-XSRF-TOKEN', token: 'fresh'}) : csrfRejected();
    }, locks());
    await assert.rejects(request.api('/api/auth/login', {method: 'POST'}), error => error.status === 403);
    assert.deepEqual(paths, ['/api/auth/login', '/api/auth/csrf', '/api/auth/login']);
});

test('concurrent CSRF failures share one token reload within the tab', async () => {
    let reloads = 0, writes = 0;
    const request = client(async (path, options) => {
        if (path === '/api/auth/csrf') {
            reloads++; return response(200, {headerName: 'X-XSRF-TOKEN', token: 'fresh'});
        }
        if (options.headers['X-XSRF-TOKEN'] !== 'fresh') return csrfRejected();
        writes++; return response(200);
    }, locks());
    const results = await Promise.all([request('/api/one', {method: 'POST'}), request('/api/two', {method: 'POST'})]);
    assert.ok(results.every(result => result.ok));
    assert.equal(reloads, 1); assert.equal(writes, 2);
});

test('uncertain refresh outcome never retries rotation or the pending write', async () => {
    const paths = [];
    const request = client(async path => {
        paths.push(path);
        if (path === '/api/auth/refresh') throw new Error('network');
        return response(401);
    }, locks());
    await assert.rejects(request.api('/api/write', {method: 'POST'}), /network/);
    assert.deepEqual(paths, ['/api/write', '/api/auth/me', '/api/auth/refresh']);
    assert.deepEqual(request.redirects, []);
});

test('CSRF service outage preserves the screen and permits recovery on a later user attempt', async () => {
    let reloads = 0, writes = 0;
    const request = client(async (path, options) => {
        if (path === '/api/auth/csrf') return ++reloads === 1 ? response(503)
            : response(200, {headerName: 'X-XSRF-TOKEN', token: 'fresh'});
        if (options.headers['X-XSRF-TOKEN'] !== 'fresh') return csrfRejected();
        writes++; return response(204);
    }, locks());
    await assert.rejects(request.api('/api/write', {method: 'POST'}), error => error.status === 503);
    assert.equal(writes, 0); assert.deepEqual(request.redirects, []);
    await request.api('/api/write', {method: 'POST'});
    assert.equal(writes, 1); assert.equal(reloads, 2);
});
