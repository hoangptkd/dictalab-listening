const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const app = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/app.js'), 'utf8');

test('empty history distinguishes a filtered level from the first practice session', () => {
    const message = {}, button = {}, link = {};
    const state = {historySessions: [], historyPage: {totalElements: 0}, historyFilter: 'A1'};
    const elements = {historyEmpty: {querySelector: selector => ({p: message, button, a: link})[selector]},
        historyCount: {}, historyList: {}};
    const source = app.match(/    function renderHistoryList\([\s\S]*?\n    }\n/)[0];
    const render = vm.runInNewContext(`${source}\nrenderHistoryList`, {state, elements,
        router: {toPath: (_route, params) => `/catalog?level=${params.level}&page=${params.page}`}});
    render();
    assert.match(message.textContent, /trình độ A1/);
    assert.equal(button.hidden, false);
    assert.equal(link.href, '/catalog?level=A1&page=1');
    state.historyFilter = 'all'; render();
    assert.match(message.textContent, /Hoàn thành bài đầu tiên/);
    assert.equal(button.hidden, true);
});

function harness() {
    const calls = [], renders = [], urls = [];
    const state = {historyFilter: 'A2', historyLoadGeneration: 0};
    const errorPanel = {hidden: true};
    const document = {body: {dataset: {route: 'history'}}, activeElement: null, querySelector: () => errorPanel};
    const list = {setAttribute(name, value) { this[name] = value; }};
    const context = {state, document, elements: {historyList: list, historyEmpty: {}}, URLSearchParams,
        window: {location: {search: '?level=A2&page=2'}},
        router: {replace(route, params) { urls.push({...params}); }},
        api(url) { return new Promise((resolve, reject) => calls.push({url, resolve, reject})); },
        renderHistoryLevelTabs() {}, renderHistoryList() { renders.push(state.historySessions); },
        renderHistoryPagination() {}, showToast(message) { renders.push(message); }};
    const source = app.match(/    async function loadHistory\([\s\S]*?\n    }\n/)[0];
    const load = vm.runInNewContext(`${source}\nloadHistory`, context);
    return {load, state, document, calls, renders, urls, list, errorPanel, elements: context.elements};
}

test('history requests a bounded server page and sends the selected level', async () => {
    const h = harness();
    const pending = h.load();
    const query = new URL(h.calls[0].url, 'https://dictalab.test').searchParams;
    assert.equal(query.get('page'), '1');
    assert.equal(query.get('size'), '10');
    assert.equal(query.get('level'), 'A2');
    h.calls[0].resolve({items: [{sessionId: 'page-two'}], page: 1, totalPages: 4, totalElements: 31});
    await pending;
    assert.equal(h.state.historySessions[0].sessionId, 'page-two');
    assert.equal(h.urls[0].page, 2);
    assert.equal(h.list['aria-busy'], 'false');
});

test('late history responses cannot overwrite a newer level or page', async () => {
    const h = harness();
    const old = h.load(2);
    h.state.historyFilter = 'B1';
    const current = h.load(1);
    h.calls[1].resolve({items: [{sessionId: 'new'}], page: 0, totalPages: 1, totalElements: 1});
    await current;
    h.calls[0].resolve({items: [{sessionId: 'old'}], page: 1, totalPages: 4, totalElements: 31});
    await old;
    assert.equal(h.state.historySessions[0].sessionId, 'new');
    assert.equal(h.renders.length, 1);
    assert.equal(h.urls[0].level, 'B1');
});

test('history bounds obsolete deep links to the last existing page', async () => {
    const h = harness();
    const pending = h.load(500);
    h.calls[0].resolve({items: [], page: 499, totalPages: 2, totalElements: 11});
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(new URL(h.calls[1].url, 'https://dictalab.test').searchParams.get('page'), '1');
    h.calls[1].resolve({items: [{sessionId: 'last'}], page: 1, totalPages: 2, totalElements: 11});
    await pending;
    assert.equal(h.state.historySessions[0].sessionId, 'last');
    assert.equal(h.urls.at(-1).page, 2);
});

test('failed history requests clear loading and remain retryable', async () => {
    const h = harness();
    const failed = h.load(1);
    h.calls[0].reject(new Error('Offline'));
    await failed;
    assert.equal(h.list['aria-busy'], 'false');
    assert.equal(h.errorPanel.hidden, false);
    assert.equal(h.elements.historyEmpty.hidden, true);
    const retry = h.load(1);
    h.calls[1].resolve({items: [], page: 0, totalPages: 0, totalElements: 0});
    await retry;
    assert.equal(h.errorPanel.hidden, true);
    assert.equal(h.state.historyPage.totalElements, 0);
});

test('pagination keeps its controls available for retry after a failed request', async () => {
    const h = harness();
    const buttons = [
        {dataset: {page: '1'}, disabled: false},
        {dataset: {page: '3'}, disabled: false}
    ];
    buttons.forEach(button => {
        button.addEventListener = (name, handler) => { button.click = handler; };
    });
    const nav = {setAttribute() {}, querySelectorAll() { return buttons; }};
    h.document.createElement = () => nav;
    h.list.after = () => {};
    h.state.historyPage = {page: 1, totalPages: 4, totalElements: 31};
    const context = {state: h.state, document: h.document, elements: {historyList: h.list},
        router: {push() {}}, loadHistory: h.load};
    const source = app.match(/    function renderHistoryPagination\([\s\S]*?\n    }\n/)[0];
    vm.runInNewContext(`${source}\nrenderHistoryPagination()`, context);
    buttons[1].click();
    h.calls[0].reject(new Error('Offline'));
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(buttons[1].disabled, false);
    assert.equal(h.list['aria-busy'], 'false');
    buttons[1].click();
    h.calls[1].resolve({items: [], page: 2, totalPages: 4, totalElements: 31});
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.state.historyPage.page, 2);
});

test('changing a history level preserves keyboard focus before the request starts', () => {
    const state = {historyFilter: 'all'};
    const document = {activeElement: {dataset: {historyLevel: 'all'}}, querySelector() { return null; }};
    const selected = {dataset: {historyLevel: 'B1'}, focus() { document.activeElement = this; }};
    let requestedPage;
    const context = {state, document, elements: {historyLevelTabs: {querySelector() { return selected; }}},
        router: {replace() {}},
        renderHistoryLevelTabs() { document.activeElement = null; },
        loadHistory(page) {
            requestedPage = page;
            assert.equal(document.activeElement, selected);
        }};
    const source = app.match(/    function selectHistoryLevel\([\s\S]*?\n    }\n/)[0];
    vm.runInNewContext(`${source}\nselectHistoryLevel('B1')`, context);
    assert.equal(state.historyFilter, 'B1');
    assert.equal(requestedPage, 1);
});
