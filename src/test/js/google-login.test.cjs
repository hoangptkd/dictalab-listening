const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/google-login.js'), 'utf8');
const app = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/app.js'), 'utf8');

function harness(apiOverride) {
    const elements = Object.fromEntries(['google-auth', 'google-signin', 'google-auth-status', 'google-auth-retry'].map(id =>
        [id, {hidden: true, clientWidth: 224, textContent: '', replaceChildren() {this.cleared = true;}}]));
    const requests = [], completed = [], renders = [];
    let options, busy = false, resize;
    const window = {google: {accounts: {id: {initialize(value) {options = value;}, renderButton(el, value) {renders.push(value);}}}}};
    window.ResizeObserver = class {
        constructor(callback) { resize = callback; }
        observe() {}
        disconnect() { resize = undefined; }
    };
    vm.runInNewContext(source, {window, document: {querySelector: id => elements[id.slice(1)]}, setTimeout, clearTimeout});
    const api = async (url, request) => {
        requests.push({url, request});
        if (apiOverride) return apiOverride(url, request);
        if (url.endsWith('/config')) return {enabled: true, clientId: 'test-client'};
        if (url.endsWith('/challenge')) return {nonce: 'single-use-test-nonce'};
        return {id: 42};
    };
    const show = () => window.DictaLabGoogleLogin.show(api, user => completed.push(user), value => {
        if (value && busy) return false;
        busy = value; return true;
    });
    return {window, elements, requests, completed, renders, show, callback: response => options.callback(response),
        options: () => options, setBusy: value => busy = value, resize: () => resize?.()};
}

test('Google button uses server configuration and nonce, then posts only the credential', async () => {
    const h = harness(); await h.show();
    assert.equal(h.options().client_id, 'test-client');
    assert.equal(h.options().nonce, 'single-use-test-nonce');
    assert.equal(h.options().auto_select, false);
    assert.equal(h.renders[0].width, 224);
    await h.callback({credential: 'test-id-token'});
    assert.equal(h.requests[2].url, '/api/auth/google');
    assert.equal(h.requests[2].request.method, 'POST');
    assert.equal(h.requests[2].request.body.credential, 'test-id-token');
    assert.equal(h.completed[0].id, 42);
    assert.equal(h.elements['google-auth'].inert, false);
});

test('unconfigured Google stays hidden without loading the library or requesting a challenge', async () => {
    const h = harness(async () => ({enabled: false, clientId: ''})); await h.show();
    assert.equal(h.elements['google-auth'].hidden, true);
    assert.equal(h.requests.length, 1);
    assert.equal(h.options(), undefined);
});

test('Google button resizes with the form and stops updating after leaving authentication', async () => {
    const h = harness(); await h.show();
    h.elements['google-auth'].clientWidth = 400;
    h.resize();
    assert.equal(h.renders.at(-1).width, 400);
    h.elements['google-auth'].clientWidth = 238;
    h.resize();
    assert.equal(h.renders.at(-1).width, 238);
    assert.equal(h.renders.at(-1).text, 'signin_with');
    const count = h.renders.length;
    h.window.DictaLabGoogleLogin.hide();
    h.elements['google-auth'].clientWidth = 400;
    h.resize();
    assert.equal(h.renders.length, count);
});

test('failed Google login presents retry and obtains a fresh nonce before another attempt', async () => {
    let nonce = 0;
    const h = harness(async url => {
        if (url.endsWith('/config')) return {enabled: true, clientId: 'test'};
        if (url.endsWith('/challenge')) return {nonce: `nonce-${++nonce}`};
        throw new Error('Google token expired');
    });
    await h.show(); await h.callback({credential: 'expired-token'});
    assert.equal(h.elements['google-auth-status'].textContent, 'Google token expired');
    assert.equal(h.elements['google-auth-retry'].hidden, false);
    assert.equal(h.completed.length, 0);
    await h.elements['google-auth-retry'].onclick();
    assert.equal(h.options().nonce, 'nonce-2');
});

test('callbacks from an earlier screen and callbacks during password login are ignored', async () => {
    const h = harness(); await h.show();
    h.setBusy(true); await h.callback({credential: 'ignored'});
    assert.equal(h.requests.length, 2);
    h.setBusy(false); h.window.DictaLabGoogleLogin.hide();
    await h.callback({credential: 'old-screen'});
    assert.equal(h.completed.length, 0);
    assert.equal(h.requests.length, 2);
});

test('both login methods restore a safe internal destination and renew browser CSRF', async () => {
    const source = app.match(/    async function completeAuthentication\([\s\S]*?\n    }\n/)[0];
    for (const [next, expected] of [['/practice/session-123', '/practice/session-123'], ['//attacker.test', null], ['https://attacker.test', null]]) {
        let destination = null, csrf = 0, entered = 0;
        const state = {};
        const complete = vm.runInNewContext(`${source}\ncompleteAuthentication`, {
            state, elements: {authStatus: {}}, URL, URLSearchParams,
            location: {origin: 'https://dictalab.test'},
            window: {location: {search: `?next=${encodeURIComponent(next)}`}, history: {replaceState(_s, _t, path) {destination = path;}}},
            refreshCsrf: async () => csrf++, enterApplication: async () => entered++
        });
        await complete({id: 42});
        assert.equal(state.currentUser.id, 42);
        assert.equal(destination, expected);
        assert.equal(csrf, 1);
        assert.equal(entered, 1);
    }
});
