const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
// Execute the actual added submit block shipped in the compatibility patch.
const patch = fs.readFileSync(path.resolve(__dirname, '../../..', 'scripts/chatgpt-web-compat.patch'), 'utf8');

// The remaining bridge checks cover legacy rollback tooling, not the running application.
test('application configuration, startup and deploy use Gemini without the legacy browser bridge', () => {
    const read = file => fs.readFileSync(path.resolve(__dirname, '../../..', file), 'utf8');
    for (const service of ['CoachService', 'ConversationService']) {
        const source = read(`src/main/java/com/dictalab/service/${service}.java`);
        assert.ok(source.includes('GeminiClient'));
        assert.ok(!source.includes('ChatGptWebCoachClient'));
        assert.ok(!source.includes('closeSession('));
    }
    assert.ok(!fs.existsSync(path.resolve(__dirname, '../../..', 'src/main/java/com/dictalab/service/ChatGptWebCoachClient.java')));
    for (const file of ['scripts/run-local.ps1', 'scripts/deploy-to-vps.ps1', 'src/main/resources/application.yml', '.env.example']) {
        const source = read(file);
        assert.ok(!/chatgpt-api-web|chatgpt-web-compat|start-coach-bridge|update-coach-bridge|127\.0\.0\.1:3000/.test(source), file);
    }
    const config = read('src/main/resources/application.yml');
    assert.ok(config.includes('GEMINI_API_KEY'));
    assert.ok(!/DICTALAB_COACH_(URL|MODEL|API_KEY|TIMEOUT)/.test(config));
});
const submitHunk = patch.split(/^@@/m).find(hunk => hunk.includes('async function submitPrompt'));
const added = submitHunk.split(/\r?\n/).filter(line => line.startsWith('+') || line.startsWith(' ')).map(line => line.slice(1)).join('\n');
const start = added.indexOf('// A freshly loaded composer');
const end = added.indexOf("throw new Error('ChatGPT composer did not retain the prompt.');", start);
const block = added.slice(start, end + "throw new Error('ChatGPT composer did not retain the prompt.');".length);
const submit = vm.runInNewContext(`(async (page,input,prompt) => { ${block} })`);
function pageFor(input, send) {
    input.click = async () => {}; input.press = async () => {};
    send.isEnabled = async () => true;
    return {keyboard: {insertText: prompt => input.fill(prompt)}, waitForTimeout: async () => {}, getByRole: () => ({first: () => send})};
}

test('bridge retains a multiline prompt with rich editor whitespace and sends once', async () => {
    let text, clicks = 0;
    const input = {async fill(prompt) { text = prompt.replaceAll('\n', '\n\n'); }, async evaluate() { return text; }};
    const send = {async count() { return 1; }, async click() { clicks++; }};
    await submit(pageFor(input, send), input, 'Return JSON.\nEnglish listening.');
    assert.equal(clicks, 1);
});
test('bridge retries a draft erased during hydration before sending', async () => {
    let fills = 0, text, clicks = 0;
    const input = {async fill(prompt) { fills++; text = fills === 1 ? '' : prompt; }, async evaluate() { return text; }};
    const send = {async count() { return 1; }, async click() { clicks++; }};
    await submit(pageFor(input, send), input, 'Return JSON.');
    assert.equal(fills, 2); assert.equal(clicks, 1);
});
test('bridge does not resend when clicking Send fails', async () => {
    let clicks = 0;
    const input = {async fill() {}, async evaluate() { return 'Return JSON.'; }};
    const send = {async count() { return 1; }, async click() { clicks++; throw new Error('timeout'); }};
    await assert.rejects(submit(pageFor(input, send), input, 'Return JSON.'), /timeout/);
    assert.equal(clicks, 1);
});

// Run the session resolver and tab lifecycle additions shipped to the VPS.
const additions = patch.split(/\r?\n/).filter(line => line.startsWith('+') && !line.startsWith('+++'))
    .map(line => line.slice(1)).join('\n');
const runtimeSettings = additions.slice(additions.indexOf('// Chrome runtime settings.'), additions.indexOf('// End Chrome runtime settings.'));
function runtime(env = {}, extra = {}) {
    const context = vm.createContext({process: {env}, ...extra});
    vm.runInContext(runtimeSettings, context);
    return context;
}
const lifecycle = additions.slice(additions.indexOf('const pageLastUsed'), additions.indexOf('// Run cleanup'));
const resolver = additions.slice(additions.indexOf('  if (sessionId !== undefined)'), additions.indexOf('app.post("/v1/sessions'));
function sessions() {
    const records = new Map(), pages = new Map();
    const context = runtime({}, {conversationPages: pages, Date,
        getConversation: id => records.get(id), createConversationRecord: () => ({id: 'new', url: null}),
        updateConversation: item => records.set(item.id, item)});
    vm.runInContext(lifecycle, context);
    const resolve = vm.runInContext(`(conversationId,chatgptId,sessionId) => {${resolver}}`, context);
    return {records, pages, resolve, close: vm.runInContext('closeConversationPage', context),
        trim: vm.runInContext('trimConversationPages', context), touch: vm.runInContext('(id,time) => pageLastUsed.set(id,time)', context)};
}
test('Chrome launch switches headless mode while preserving profile, loopback and background responsiveness', () => {
    const launch = additions.match(/const chromeArgs = \[[\s\S]*?\n  \];/)[0];
    for (const headless of ['true', 'false']) {
        const context = runtime({CHROME_HEADLESS: headless}, {
            CDP_PORT: 9223, PROFILE_DIR: 'C:/bridge/data/chrome-profile', CHATGPT_URL: 'https://chatgpt.com/'
        });
        const args = Array.from(vm.runInContext(`${launch}\nchromeArgs`, context));
        assert.equal(args.includes('--headless'), headless === 'true');
        assert.ok(args.includes('--user-data-dir=C:/bridge/data/chrome-profile'));
        assert.ok(args.includes('--remote-debugging-address=127.0.0.1'));
        assert.ok(args.includes('--remote-debugging-port=9223'));
        assert.ok(args.includes('--disable-backgrounding-occluded-windows'));
        assert.equal(args.at(-1), 'https://chatgpt.com/');
    }
});

test('runtime defaults preserve local login and reject invalid VPS settings', () => {
    const context = runtime();
    assert.equal(vm.runInContext('CHROME_HEADLESS', context), false);
    for (const [name, values] of Object.entries({
        CHROME_HEADLESS: ['1', ''], BRIDGE_DEBUG: ['yes', '']
    })) {
        for (const value of values) assert.throws(() => runtime({[name]: value}), new RegExp(name));
    }
});

test('response polling logs are quiet by default and enabled explicitly', () => {
    const patchedContext = patch.split(/\r?\n/).filter(line => line.startsWith(' ') || (line.startsWith('+') && !line.startsWith('+++')))
        .map(line => line.slice(1)).join('\n');
    const logs = [...patchedContext.matchAll(/if \(BRIDGE_DEBUG\) console\.log\(\s*`\[(?:WAIT|STREAM[^\]]*)\][\s\S]*?`\s*\);/g)].map(match => match[0]);
    assert.equal(logs.length, 7);
    for (const debug of [undefined, 'true']) {
        const output = [];
        const context = runtime(debug ? {BRIDGE_DEBUG: debug} : {}, {
            console: {log: text => output.push(text)}, snapshot: {count: 1, last: {text: 'Hello'}},
            snapshotBefore: {count: 0}, text: 'Hello', delta: 'Hello', accumulated: '', current: 'Hello', generating: true
        });
        vm.runInContext(logs.join('\n'), context);
        assert.equal(output.length, debug ? 7 : 0);
    }
});

test('VPS launcher defaults to headless and permits visible login and debug logging', {skip: process.platform !== 'win32'}, () => {
    const {execFileSync} = require('node:child_process');
    const script = fs.readFileSync(path.resolve(__dirname, '../../..', 'scripts/start-coach-bridge-vps.ps1'), 'utf8');
    const settings = script.slice(0, script.indexOf('Set-Location'));
    const report = '[ordered]@{Headless=$env:CHROME_HEADLESS; Debug=$env:BRIDGE_DEBUG} | ConvertTo-Json -Compress';
    const run = (visible, env) => JSON.parse(execFileSync('pwsh', ['-NoProfile', '-Command', `& {${settings}\n${report}} ${visible ? '-VisibleChrome' : ''}`], {
        encoding: 'utf8', env: {...process.env, BRIDGE_DEBUG: '', ...env}
    }).trim());
    assert.deepEqual(run(false), {Headless: 'true', Debug: 'false'});
    assert.deepEqual(run(true, {BRIDGE_DEBUG: 'true'}),
        {Headless: 'false', Debug: 'true'});
});

test('same learning session reuses its record and different sessions remain isolated', () => {
    const h = sessions();
    const id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', other = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
    const first = h.resolve(undefined, undefined, id);
    assert.equal(h.resolve(undefined, undefined, id.toUpperCase()), first);
    assert.notEqual(h.resolve(undefined, undefined, other), first);
    assert.equal(h.records.size, 2);
    assert.throws(() => h.resolve('other-conversation', undefined, id), /conflicting/);
    assert.throws(() => h.resolve(undefined, undefined, '../../other'), /Invalid/);
});
test('tab limit closes the oldest tab and idle cleanup preserves the active session', async () => {
    const h = sessions(), closed = [];
    for (let n = 0; n < 5; n++) {
        h.pages.set(String(n), {isClosed: () => false, close: async () => closed.push(String(n))});
        h.touch(String(n), Date.now() - 5000 + n);
    }
    await h.trim(4, '0');
    assert.deepEqual(closed, ['1']); assert.equal(h.pages.size, 4);
    h.touch('0', Date.now() - 11 * 60 * 1000); h.touch('2', Date.now() - 11 * 60 * 1000);
    await h.trim(4, '0');
    assert.deepEqual(closed, ['1', '2']); assert.ok(h.pages.has('0'));
    await h.close('0'); assert.ok(!h.pages.has('0'));
});

test('patch upgrade preserves local edits and refuses conflicting edits', {skip: process.platform !== 'win32'}, () => {
    const os = require('node:os'), {execFileSync} = require('node:child_process');
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dictalab-patch-test-'));
    const bridge = path.join(root, 'bridge'), scripts = path.join(root, 'scripts');
    fs.mkdirSync(bridge); fs.mkdirSync(scripts);
    const git = args => execFileSync('git', args, {cwd: bridge, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']});
    const source = path.join(bridge, 'index.js');
    const base = Array.from({length: 30}, (_, n) => `// line ${n}: café`).join('\n') + '\n';
    const old = base.replace('// line 5:', '// patched 5:');
    const desired = old.replace('// line 20:', '// session 20:');
    const script = path.join(scripts, 'apply-chatgpt-web-patch.ps1');
    fs.copyFileSync(path.resolve(__dirname, '../../..', 'scripts/apply-chatgpt-web-patch.ps1'), script);
    try {
        git(['init', '-q']); fs.writeFileSync(source, base);
        git(['add', 'index.js']); git(['-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'fixture']);
        fs.writeFileSync(source, desired);
        fs.writeFileSync(path.join(scripts, 'chatgpt-web-compat.patch'), git(['diff', '--', 'index.js']));
        const custom = old.replace('// line 28:', '// custom 28:');
        fs.writeFileSync(source, custom);
        const run = () => execFileSync('pwsh', ['-NoProfile', '-File', script, '-BridgeRoot', bridge], {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']});
        run();
        assert.equal(fs.readFileSync(source, 'utf8'), desired.replace('// line 28:', '// custom 28:'));
        run(); // Already applied is a no-op.
        const conflict = old.replace('// line 20:', '// incompatible 20:');
        fs.writeFileSync(source, conflict);
        assert.throws(run, /conflicts/);
        assert.equal(fs.readFileSync(source, 'utf8'), conflict);
    } finally {
        // Delete only the test folder allocated directly under the system temp directory.
        assert.equal(path.dirname(fs.realpathSync(root)), fs.realpathSync(os.tmpdir()));
        fs.rmSync(root, {recursive: true, force: true});
    }
});

test('VPS bridge deployment refuses an invalid artifact before touching running services', {skip: process.platform !== 'win32'}, () => {
    const os = require('node:os'), {execFileSync} = require('node:child_process');
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dictalab-bridge-release-test-'));
    const files = ['index.js', 'start-coach-bridge-vps.ps1', 'apply-chatgpt-web-patch.ps1', 'chatgpt-web-compat.patch'];
    try {
        for (const file of files) fs.writeFileSync(path.join(root, file), '// fixture\n');
        const script = path.resolve(__dirname, '../../..', 'scripts/update-coach-bridge-vps.ps1');
        assert.throws(() => execFileSync('pwsh', ['-NoProfile', '-File', script, '-StagingDirectory', root, '-ExpectedSha256', '0'.repeat(64)],
            {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']}), /Bridge SHA-256 mismatch/);
    } finally {
        for (const file of files) fs.unlinkSync(path.join(root, file));
        fs.rmdirSync(root);
    }
});

test('VPS shutdown flushes Chrome before stopping its parent task', {skip: process.platform !== 'win32'}, () => {
    const {execFileSync} = require('node:child_process');
    const source = fs.readFileSync(path.resolve(__dirname, '../../..', 'scripts/update-coach-bridge-vps.ps1'), 'utf8');
    const stop = source.slice(source.indexOf('function Stop-Bridge'), source.indexOf('function Start-Bridge'));
    const simulation = `
$bridgeRoot='C:\\DictaLab\\coach-bridge'; $taskName='DictaLabCoachBridge'
$global:chromeAlive=$true; $global:nodeAlive=$true; $global:events=@()
$node={ param($flag,$code)
    if(-not $global:chromeAlive){throw 'Chrome was killed before flushing the profile'}
    $global:events+='chrome-close'; $global:chromeAlive=$false; $global:LASTEXITCODE=0
}
function Get-NetTCPConnection { param($State,[int[]]$LocalPort,$ErrorAction)
    if($global:nodeAlive -and 3000 -in $LocalPort){[pscustomobject]@{OwningProcess=100}}
    if($global:chromeAlive -and 9223 -in $LocalPort){[pscustomobject]@{OwningProcess=200}}
}
function Get-CimInstance { param($class,$Filter)
    if($Filter -eq 'ProcessId=100'){[pscustomobject]@{Name='node.exe';CommandLine='node.exe index.js'}}
    else{[pscustomobject]@{Name='chrome.exe';CommandLine="chrome.exe --user-data-dir=$bridgeRoot\\data\\chrome-profile"}}
}
function Push-Location {param($LiteralPath)}
function Pop-Location {}
function Stop-ScheduledTask {param($TaskName) $global:events+='task-stop';$global:nodeAlive=$false;$global:chromeAlive=$false}
function Get-Process {param($Id,$ErrorAction)}
${stop}
Stop-Bridge
ConvertTo-Json -Compress -InputObject $global:events
`;
    const output = execFileSync('pwsh', ['-NoProfile', '-Command', simulation], {encoding: 'utf8'});
    assert.deepEqual(JSON.parse(output.trim()), ['chrome-close', 'task-stop']);
});
