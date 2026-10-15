const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('src/main/resources/static/js/free-practice.js', 'utf8');
function setup(fail = false) {
    const requests = [];
    let play, note;
    const context = {
        URL, Set, JSON, Error,
        location: {href: 'https://dictalab.test/audio/lesson.html', origin: 'https://dictalab.test'},
        document: {
            createElement: () => (note = {setAttribute() {}}),
            querySelector: () => ({prepend() {}}),
            addEventListener: (event, callback) => { if (event === 'play') play = callback; }
        },
        fetch: async (url, options) => {
            requests.push({url, options});
            if (url === '/api/auth/csrf') return {ok: true, json: async () => ({headerName: 'X-CSRF-TOKEN', token: 'fixture'})};
            return {ok: !fail};
        }
    };
    vm.runInNewContext(source, context);
    return {requests, note, play: target => play({target})};
}

test('free practice explains persistence and records exposure once with CSRF', async () => {
    const app = setup();
    assert.match(app.note.textContent, /chưa được lưu/);
    const audio = {tagName: 'AUDIO', dataset: {src: '/media/example.wav'}};
    await app.play(audio);
    await app.play(audio);
    assert.equal(app.requests.length, 2);
    const write = app.requests[1];
    assert.equal(write.options.headers['X-CSRF-TOKEN'], 'fixture');
    assert.deepEqual(JSON.parse(write.options.body), {audioUrl: '/media/example.wav'});
    assert.equal(write.options.credentials, 'same-origin');
});

test('failure is visible and can retry; external audio is not submitted', async () => {
    const app = setup(true);
    await app.play({tagName: 'AUDIO', dataset: {src: 'https://external.test/example.wav'}});
    assert.equal(app.requests.length, 0);
    const audio = {tagName: 'AUDIO', dataset: {src: '/media/example.wav'}};
    await app.play(audio);
    assert.match(app.note.textContent, /Chưa ghi nhận được/);
    await app.play(audio);
    assert.equal(app.requests.length, 4);
});

test('every published standalone quiz loads the shared disclosure and exposure code', () => {
    for (const dir of ['b1-expansion-20261015', 'listening-samples-20261015']) {
        const folder = 'src/main/resources/static/audio/' + dir;
        for (const file of fs.readdirSync(folder).filter(name => name.endsWith('.html'))) {
            const html = fs.readFileSync(folder + '/' + file, 'utf8');
            if (html.includes('<audio')) assert.match(html, /src="\/js\/free-practice\.js"/, file);
        }
    }
});
