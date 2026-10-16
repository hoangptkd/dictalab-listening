const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/billing.js'), 'utf8');

function harness(api, search = '') {
    const handlers = {};
    const buy = {disabled: false, addEventListener(event, callback) {handlers.buy = callback;}};
    const status = {textContent: ''};
    const section = {innerHTML: '', setAttribute() {}, querySelector(selector) {
        if (selector === '[data-buy]') return buy;
        if (selector === '[data-billing-status]') return status;
        return {addEventListener() {}};
    }, querySelectorAll: () => []};
    const root = {isConnected: true, prepend() {}};
    const redirects = [];
    const window = {DictaLabListeningBridge: {api}};
    vm.runInNewContext(source, {window, document: {createElement: () => section}, URL, URLSearchParams,
        Intl, Date, location: {search, assign: value => redirects.push(value)}});
    return {mount: () => window.DictaLabBilling.mount(root), section, handlers, buy, status, redirects};
}
const plan = {enabled: true, premium: false, priceVnd: 49000, durationDays: 30, freeDailyLessons: 3,
    premiumDailyAi: 10, lessonsUsed: 2, aiUsed: 0};

test('return URL cannot grant Premium; the browser checks its own authenticated order', async () => {
    const requests = [];
    const app = harness(async (url, options) => {
        requests.push([url, options?.method || 'GET']);
        if (url.endsWith('/check')) return {status: 'PENDING'};
        return url.endsWith('/status') ? plan : [];
    }, '?orderCode=100001&status=PAID&cancel=false');
    await app.mount();
    assert.ok(requests.some(([url, method]) => url === '/api/billing/orders/100001/check' && method === 'POST'));
    assert.match(app.section.innerHTML, /Chờ thanh toán/);
    assert.doesNotMatch(app.section.innerHTML, /Premium đang hoạt động/);
});

test('checkout errors restore the purchase control and reject an untrusted redirect', async () => {
    let attempts = 0;
    const app = harness(async (url, options) => {
        if (options?.method === 'POST') {
            attempts++;
            return {checkoutUrl: attempts === 1 ? 'https://pay.payos.vn.attacker.test/web/id' : 'https://pay.payos.vn/web/id'};
        }
        return url.endsWith('/status') ? plan : [];
    });
    await app.mount();
    await app.handlers.buy();
    assert.equal(app.redirects.length, 0);
    assert.match(app.section.innerHTML, /Đơn đang đồng bộ/);
    assert.doesNotMatch(app.section.innerHTML, /data-buy disabled/);
    await app.handlers.buy();
    assert.deepEqual(app.redirects, ['https://pay.payos.vn/web/id']);
});

test('unconfigured checkout explains availability without applying learning limits', async () => {
    const app = harness(async url => url.endsWith('/status') ? {...plan, enabled: false} : []);
    await app.mount();
    assert.match(app.section.innerHTML, /chưa áp dụng hạn mức học/);
    assert.match(app.section.innerHTML, /data-buy disabled/);
});
