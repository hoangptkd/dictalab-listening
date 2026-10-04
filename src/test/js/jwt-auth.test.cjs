const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/js/app.js', 'utf8');
const helpers = source.slice(source.indexOf('    let tokenRefreshPromise = null;'), source.indexOf('    async function api(path'));

function client(fetch, locks) {
    const context = vm.createContext({fetch, navigator: {locks}, AbortSignal, state: {csrfHeader: 'X-XSRF-TOKEN', csrfToken: 'csrf'}});
    vm.runInContext(helpers + '\nthis.request = authenticatedFetch;', context);
    return context.request;
}
function locks() {
    let pending = Promise.resolve();
    return {request(name, fn) { const next = pending.then(fn); pending = next.catch(() => {}); return next; }};
}
const response = status => ({status, ok: status >= 200 && status < 300, json: async () => ({message: 'test error'})});

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
