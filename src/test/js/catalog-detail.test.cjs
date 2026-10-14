const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/app.js'), 'utf8');
const descriptionSource = source.slice(source.indexOf('    function catalogDescription('), source.indexOf('    function renderLessons('));

function selectionContext(mobile = false) {
    const rows = [1, 2].map(id => ({
        dataset: {selectLesson: String(id)}, pressed: 'false',
        setAttribute(name, value) { if (name === 'aria-pressed') this.pressed = value; },
        closest() { return {classList: {toggle: (_, value) => { this.selected = value; }}}; }
    }));
    let opened = false, focused = 0, scrolled = 0;
    const state = {lessons: [{id: 1}, {id: 2}], selectedCatalogLessonId: 1};
    const rendered = [];
    const fn = source.slice(source.indexOf('    function selectCatalogLesson('), source.indexOf('    function renderCatalogDetail('));
    const select = vm.runInNewContext(`${fn}\nselectCatalogLesson`, {
        state, elements: {
            lessonGrid: {querySelectorAll: () => rows, closest: () => ({setAttribute: () => {opened = true;}})},
            lessonDetail: {querySelector: () => ({focus: () => focused++}), scrollIntoView: () => scrolled++}
        }, renderCatalogDetail: lesson => rendered.push(lesson.id), window: {matchMedia: () => ({matches: mobile})}
    });
    return {select, rows, state, rendered, status: () => ({opened, focused, scrolled})};
}

test('selecting a lesson updates only that selection and renders its complete detail', () => {
    const ctx = selectionContext();
    ctx.select(2, true);
    assert.equal(ctx.state.selectedCatalogLessonId, 2);
    assert.deepEqual(ctx.rows.map(row => row.pressed), ['false', 'true']);
    assert.deepEqual(ctx.rows.map(row => row.selected), [false, true]);
    assert.deepEqual(ctx.rendered, [2]);
    assert.deepEqual(ctx.status(), {opened: true, focused: 0, scrolled: 0});
});

test('mobile selection opens the detail and moves focus; unknown ids preserve selection', () => {
    const ctx = selectionContext(true);
    ctx.select(2, true);
    ctx.select(999, true);
    assert.equal(ctx.state.selectedCatalogLessonId, 2);
    assert.deepEqual(ctx.rendered, [2]);
    assert.deepEqual(ctx.status(), {opened: true, focused: 1, scrolled: 1});
});

test('selection stays fixed while the original start request is pending', () => {
    const ctx = selectionContext(true);
    ctx.state.catalogStarting = true;
    ctx.select(2, true);
    assert.equal(ctx.state.selectedCatalogLessonId, 1);
    assert.deepEqual(ctx.rendered, []);
    assert.deepEqual(ctx.status(), {opened: false, focused: 0, scrolled: 0});
});

test('a repeated start cannot create a second session and failure unlocks retry', async () => {
    const state = {catalogStarting: false, room: null, currentUser: {id: 1}};
    let calls = 0, reject;
    const failure = new Promise((_, no) => { reject = no; });
    const fn = source.slice(source.indexOf('    async function startLesson('), source.indexOf('    function setCatalogBusy('));
    const start = vm.runInNewContext(`${fn}\nstartLesson`, {
        state, setCatalogBusy: value => { state.catalogStarting = value; },
        api: () => { calls++; return failure; }, showToast() {}
    });
    const first = start(1);
    await start(1);
    assert.equal(calls, 1);
    assert.equal(state.catalogStarting, true);
    reject(new Error('Network unavailable'));
    await first;
    assert.equal(state.catalogStarting, false);
    await start(1);
    assert.equal(calls, 2);
});

test('detail preserves preview segments, names, state actions and escapes learner content', () => {
    const lesson = {id: 7, title: '<img src=x onerror=bad()>', description: 'A & B', topic: '<topic>',
        sentenceCount: 58, estimatedMinutes: 11, progressPercent: 25, inProgress: true, completed: false,
        previewAudioUrl: '/audio?x="bad', previewWords: [
            {word: '<vocab>', properName: false, audioStartSeconds: 1.5, audioEndSeconds: 2.5},
            {word: 'Tony', properName: true, audioStartSeconds: 3, audioEndSeconds: 4}
        ]};
    const detail = {innerHTML: '', setAttribute() {}};
    const state = {lessons: [lesson], selectedLevel: 'A1', room: null, currentUser: {id: 1}};
    const fn = source.slice(source.indexOf('    function renderCatalogDetail('), source.indexOf('    async function playPreviewWord('));
    const render = vm.runInNewContext(`${descriptionSource}\n${fn}\nrenderCatalogDetail`, {
        window: {}, state, elements: {lessonDetail: detail}, setCatalogBusy() {},
        escapeHtml: value => String(value).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))
    });
    render(lesson);
    assert.doesNotMatch(detail.innerHTML, /<img/);
    assert.match(detail.innerHTML, /&lt;img/);
    assert.match(detail.innerHTML, /data-preview-word="Tony"/);
    assert.match(detail.innerHTML, /data-preview-start="1.5"/);
    assert.match(detail.innerHTML, /data-preview-end="2.5"/);
    assert.match(detail.innerHTML, /data-preview-lesson-id="7"/);
    assert.match(detail.innerHTML, /<strong>Tiếp tục bài luyện<\/strong>/);
    lesson.inProgress = false; lesson.completed = true; render(lesson);
    assert.match(detail.innerHTML, /<strong>Luyện lại bài luyện<\/strong>/);
    assert.match(detail.innerHTML, /aria-valuenow="100"/);
    lesson.inProgress = true; render(lesson);
    assert.match(detail.innerHTML, /✓ Đã hoàn thành/);
    assert.match(detail.innerHTML, /Lượt luyện lại đang ở 25%/);
    assert.match(detail.innerHTML, /aria-valuenow="100"/);
    assert.match(detail.innerHTML, /<strong>Tiếp tục bài luyện<\/strong>/);
    lesson.inProgress = false;
    lesson.completed = false; render(lesson);
    assert.match(detail.innerHTML, /<strong>Bắt đầu bài luyện<\/strong>/);
    state.room = {roomId: 'shared'}; render(lesson);
    assert.match(detail.innerHTML, /<strong>Chọn cho cả hai<\/strong>/);
});

function listContext(lessons) {
    const node = () => ({innerHTML: '', textContent: '', setAttribute() {}});
    const elements = {lessonGrid: node(), lessonDetail: node(), lessonCount: node(), catalogSummary: node()};
    const state = {lessons, selectedCatalogLessonId: null, currentUser: {id: 1}, catalogSort: 'curriculum'};
    const selected = [];
    const fn = source.slice(source.indexOf('    function catalogList('), source.indexOf('    function selectCatalogLesson('));
    const render = vm.runInNewContext(`${descriptionSource}\n${fn}\nrenderLessons`, {
        state, elements, selectCatalogLesson: id => selected.push(id),
        escapeHtml: text => String(text).replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    });
    return {render, state, elements, selected};
}

test('completion stays visible during replay, preserves ordering, and continues an unfinished lesson first', () => {
    const lessons = [
        {id: 9, title: 'Completed', completed: true, inProgress: true, progressPercent: 20},
        {id: 4, title: '<Pending>', completed: false, inProgress: true, progressPercent: 40},
        {id: 7, title: 'New', completed: false, inProgress: false, progressPercent: 0}
    ];
    const ctx = listContext(lessons);
    ctx.render();
    assert.equal(ctx.elements.lessonCount.textContent, '1/3 bài đã hoàn thành');
    assert.match(ctx.elements.catalogSummary.innerHTML, /data-lesson-id="4"/);
    assert.match(ctx.elements.catalogSummary.innerHTML, /&lt;Pending&gt;/);
    assert.match(ctx.elements.lessonGrid.innerHTML, /✓ Đã hoàn thành<\/strong><span>100%<\/span><span>· Luyện lại 20%/);
    assert.deepEqual([...ctx.elements.lessonGrid.innerHTML.matchAll(/data-catalog-lesson="(\d+)"/g)].map(m => Number(m[1])), [9, 4, 7]);
    assert.deepEqual(lessons.map(l => l.id), [9, 4, 7]);
});

test('progress order prioritizes active and unlearned lessons with stable curriculum codes', () => {
    const lessons = [
        {id: 11, title: 'Done', completed: true},
        {id: 22, title: 'New', completed: false},
        {id: 33, title: 'Active', completed: false, inProgress: true},
        {id: 44, title: 'Newer', completed: false},
        {id: 55, title: 'Replaying', completed: true, inProgress: true}
    ];
    const ctx = listContext(lessons);
    ctx.state.catalogSort = 'progress'; ctx.render();
    const html = ctx.elements.lessonGrid.innerHTML;
    assert.deepEqual([...html.matchAll(/data-catalog-lesson="(\d+)"/g)].map(m => Number(m[1])), [33, 55, 22, 44, 11]);
    assert.match(html, /data-catalog-lesson="33"[\s\S]*?aria-hidden="true">03<\/span>/);
    assert.deepEqual(lessons.map(l => l.id), [11, 22, 33, 44, 55]);
    ctx.state.catalogSort = 'curriculum'; ctx.render();
    assert.deepEqual([...ctx.elements.lessonGrid.innerHTML.matchAll(/data-catalog-lesson="(\d+)"/g)].map(m => Number(m[1])), [11, 22, 33, 44, 55]);
    ctx.state.currentUser = null; ctx.state.catalogSort = 'progress'; ctx.render();
    assert.equal(ctx.elements.lessonCount.textContent, '5 bài luyện');
    assert.doesNotMatch(ctx.elements.lessonGrid.innerHTML, /✓ Đã hoàn thành/);
    assert.doesNotMatch(ctx.elements.catalogSummary.innerHTML, /data-lesson-id/);
});

test('guest start asks for login with the selected lesson and never creates a practice session', async () => {
    const fn = source.slice(source.indexOf('    async function startLesson('), source.indexOf('    function setCatalogBusy('));
    let destination, params;
    const start = vm.runInNewContext(`${fn}\nstartLesson`, {window: {}, state: {currentUser: null, selectedLevel: 'B1', catalogSort: 'curriculum'},
        router: {toPath: (route, p) => {params = p; return '/catalog?level=B1&lesson=42&start=1';}},
        showAuthScreen: (message, next) => {destination = next;}, api: () => assert.fail('guest must not create a session')});
    await start(42);
    assert.equal(destination, '/catalog?level=B1&lesson=42&start=1');
    assert.equal(params.lesson, 42);
    assert.equal(params.start, '1');
});

test('anonymous catalog startup opens the application, while personal pages request login', async () => {
    const fn = source.slice(source.indexOf('    async function init()'), source.indexOf('    function bindEvents()'));
    for (const route of ['catalog', 'dashboard']) {
        let opened = 0, destination, removed = false;
        const location = {pathname: '/' + route, search: '?level=B1', origin: 'https://dictalab.test'};
        const state = {currentUser: null};
        const init = vm.runInNewContext(`${fn}\ninit`, {
            state, isAdminArea: false, location, window: {location}, URLSearchParams,
            bindEvents() {}, refreshCsrf: async () => {},
            api: async () => {throw Object.assign(new Error('Guest'), {status: 401});},
            router: {parse: () => ({route, query: new URLSearchParams(location.search)})},
            enterApplication: async () => {opened++;},
            showAuthScreen: (_, next) => {destination = next;},
            document: {querySelector: () => ({remove: () => {removed = true;}})}
        });
        await init();
        assert.equal(opened, route === 'catalog' ? 1 : 0);
        assert.equal(destination, route === 'catalog' ? undefined : '/dashboard?level=B1');
        assert.equal(removed, true);
    }
});

test('authentication restores the selected lesson and rejects an external return URL', async () => {
    const fn = source.slice(source.indexOf('    async function completeAuthentication('), source.indexOf('    async function logout()'));
    const selected = '/catalog?level=B1&lesson=41&sort=curriculum&start=1';
    for (const next of [selected, '//external.test/catalog', 'https://external.test/catalog']) {
        let restored, opened = 0;
        const state = {}, user = {id: 42};
        const authenticate = vm.runInNewContext(`${fn}\ncompleteAuthentication`, {
            state, location: {origin: 'https://dictalab.test'}, URL, URLSearchParams,
            window: {location: {search: '?next=' + encodeURIComponent(next)},
                history: {replaceState: (_, __, path) => {restored = path;}}},
            elements: {authStatus: {}}, refreshCsrf: async () => {},
            enterApplication: async () => {opened++;}
        });
        await authenticate(user);
        assert.equal(state.currentUser, user);
        assert.equal(restored, next === selected ? selected : undefined);
        assert.equal(opened, 1);
    }
});

test('catalog return selects the exact lesson and consumes auto-start only for authenticated users', async () => {
    const fn = source.slice(source.indexOf('    async function resolveUrlAndNavigate('), source.indexOf('    function bindMobileNavigation('));
    for (const authenticated of [true, false]) {
        const state = {currentUser: authenticated ? {id: 42} : null, lessons: [{id: 40}, {id: 41}]};
        const replacements = [], selections = [], starts = [];
        const sort = {};
        const resolve = vm.runInNewContext(`${fn}\nresolveUrlAndNavigate`, {
            window: {}, state, isAdminArea: false, elements: {catalogSort: sort},
            router: {validLevels: new Set(['B1']), replace: (_, params) => replacements.push(params)},
            _showView: async () => {}, selectLevel: async () => {},
            selectCatalogLesson: id => selections.push(id), startLesson: async id => starts.push(id),
            showAuthScreen: () => assert.fail('catalog should remain public')
        });
        await resolve({route: 'catalog', params: {level: 'B1', lesson: '41', start: '1'}, query: new URLSearchParams()}, false);
        assert.deepEqual(selections, [41]);
        assert.deepEqual(starts, authenticated ? [41] : []);
        assert.equal(sort.disabled, !authenticated);
        if (authenticated) assert.equal(replacements.at(-1).start, undefined);
    }
});

test('all completed, replaying, empty and room catalogs have appropriate next actions', () => {
    const ctx = listContext([{id: 1, title: 'Done', completed: true, progressPercent: 100}]);
    ctx.render();
    assert.match(ctx.elements.catalogSummary.innerHTML, /Bạn đã hoàn thành tất cả/);
    assert.doesNotMatch(ctx.elements.catalogSummary.innerHTML, /data-lesson-id/);
    ctx.state.lessons[0].inProgress = true;
    ctx.render();
    assert.match(ctx.elements.catalogSummary.innerHTML, /Tiếp tục luyện lại/);
    ctx.state.room = {roomId: 'shared'};
    ctx.render();
    assert.doesNotMatch(ctx.elements.catalogSummary.innerHTML, /data-lesson-id/);
    ctx.state.lessons = [];
    ctx.render();
    assert.equal(ctx.elements.catalogSummary.innerHTML, '');
    assert.equal(ctx.state.selectedCatalogLessonId, null);
});

test('rounded progress cannot claim completion and authored descriptions remain untouched', () => {
    const ctx = listContext([{id: 1, title: 'Pending', completed: false, inProgress: true, progressPercent: 100}]);
    ctx.render();
    assert.match(ctx.elements.lessonGrid.innerHTML, /<span>99%<\/span>/);
    assert.doesNotMatch(ctx.elements.lessonGrid.innerHTML, /✓ Đã hoàn thành/);
    const describe = vm.runInNewContext(`${descriptionSource}\ncatalogDescription`);
    assert.equal(describe({title: 'Likes and Dislikes', description: 'Author description'}), 'Author description');
    assert.match(describe({title: 'Likes and Dislikes', description: 'Short human-recorded ELLLO sentences for Likes and Dislikes.'}), /sở thích/);
});

test('listening numbers restart per level without exposing topic titles or changing start IDs', async () => {
    const listening = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/listening.js'), 'utf8');
    const fn = listening.slice(listening.indexOf('    async function catalog()'), listening.indexOf('    function contentEditor('));
    const units = [
        {id: 101, level: 'A1', title: 'Hidden topic', duration_seconds: 31},
        {id: 500, level: 'A1', title: 'Hidden topic', duration_seconds: 32},
        {id: 99, level: 'B1', title: 'Hidden topic', duration_seconds: 33}
    ];
    const select = {value: '', selectedIndex: 0, addEventListener() {}};
    const root = {innerHTML: '', insertAdjacentHTML() {},
        querySelector: selector => selector === '#listening-level' ? select : {}, querySelectorAll: () => []};
    const render = vm.runInNewContext(`${fn}\ncatalog`, {loadView: async () => units, root: () => root,
        escape: String, URLSearchParams, location: {search: '?level=B1'}, followLink() {},
        bridge: () => ({paginateList() {}})});
    await render();
    assert.match(root.innerHTML, /Bài nghe A1 · Bài 01/);
    assert.match(root.innerHTML, /Bài nghe A1 · Bài 02/);
    assert.match(root.innerHTML, /Bài nghe B1 · Bài 01/);
    assert.match(root.innerHTML, /data-unit="500"/);
    assert.doesNotMatch(root.innerHTML, /Hidden topic|Bài nghe A1 · 500/);
    assert.equal(select.value, 'B1');
});
