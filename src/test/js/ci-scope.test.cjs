const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const {spawnSync, execFileSync} = require('node:child_process');
const {classify, plan} = require('../../../scripts/ci-changes.cjs');
const root = path.resolve(__dirname, '../../..');
const main = 'refs/heads/main';

test('UI, documentation and iOS PRs do not start backend services', () => {
    for (const files of [['src/main/resources/static/css/app.css'], ['src/main/resources/static/js/app.js'],
        ['README.md', 'docs/vocabulary.md'], ['ios/DictaLab/ContentView.swift'], ['src/test/js/ci-scope.test.cjs']]) {
        const scope = plan('pull_request', 'refs/pull/4/merge', files);
        assert.equal(scope.backend, false);
        assert.equal(scope.ai, false);
        assert.equal(scope.progress, false);
        assert.equal(scope.release, false);
    }
});

test('isolated services and their workflows select the relevant contracts', () => {
    for (const file of ['services/progress/src/Main.java', 'docs/progress-contract.json', '.github/workflows/progress.yml']) {
        assert.deepEqual(classify([file]), {backend: false, ai: false, progress: true, frontend: false});
    }
    for (const file of ['services/ai-generation/app/main.py', 'services/model-serving/server.py',
        'training/prepare_dataset.py', 'artifacts/prompts/conversation.json', 'artifacts/reports/ai-platform-golden.json',
        '.github/workflows/ai-platform.yml']) {
        assert.deepEqual(classify([file]), {backend: false, ai: true, progress: false, frontend: false});
    }
});

test('shared, renamed, deleted, unknown and mixed changes select every source scope', () => {
    for (const files of [['src/main/java/com/dictalab/Service.java'], ['src/main/resources/db/migration/V99.sql'],
        ['pom.xml'], ['compose.yaml'], ['.env.example'], ['scripts/linux/service-release.py'],
        ['.github/workflows/deploy.yml'], ['scripts/ci-changes.cjs'], ['new-module/config.json'],
        ['docs/runtime-config.json'], ['src/main/java/Old.java', 'docs/Old.java'],
        ['src/main/resources/static/css/app.css', 'pom.xml']]) {
        const scope = plan('pull_request', 'refs/pull/4/merge', files);
        for (const gate of ['backend', 'ai', 'progress']) assert.equal(scope[gate], true, files.join(','));
        assert.equal(scope.release, false);
    }
});

test('every application release builds every source scope, including UI-only releases', () => {
    for (const files of [['src/main/resources/static/js/app.js'], ['services/progress/src/Main.java'],
        ['services/ai-generation/app/main.py'], ['pom.xml'], ['unknown-initial-push']]) {
        const scope = plan('push', main, files);
        assert.deepEqual(scope, {backend: true, ai: true, progress: true, frontend: true, release: true});
    }
    assert.equal(plan('push', main, ['docs/vocabulary.md']).release, false);
    assert.equal(plan('push', main, ['ios/App.swift']).release, false);
});

test('manual validation builds all source scopes and cannot deploy a branch', () => {
    for (const [event, ref, compatibility, release] of [
        ['workflow_dispatch', main, true, false],
        ['workflow_dispatch', 'refs/heads/codex/topic', false, false], ['workflow_dispatch', main, false, true]]) {
        const scope = plan(event, ref, [], compatibility);
        for (const gate of ['backend', 'ai', 'progress']) assert.equal(scope[gate], true);
        assert.equal(scope.release, release);
    }
});

test('actual scope CLI reads Git diff and rejects invalid revisions', () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'dictalab-ci-scope-'));
    const output = path.join(directory, 'output');
    const summary = path.join(directory, 'summary');
    try {
        const head = execFileSync('git', ['rev-parse', 'HEAD'], {cwd: root, encoding: 'utf8'}).trim();
        const run = base => spawnSync(process.execPath, [path.join(root, 'scripts/ci-changes.cjs')], {
            cwd: root, encoding: 'utf8', env: {...process.env, GITHUB_EVENT_NAME: 'pull_request',
                GITHUB_REF: 'refs/pull/4/merge', CI_BASE_SHA: base, GITHUB_SHA: head,
                GITHUB_OUTPUT: output, GITHUB_STEP_SUMMARY: summary}});
        assert.equal(run(head).status, 0);
        assert.match(fs.readFileSync(output, 'utf8'), /backend=false/);
        assert.match(fs.readFileSync(summary, 'utf8'), /## CI scope/);
        assert.notEqual(run('--output=unexpected').status, 0);
        assert.equal(run('0'.repeat(40)).status, 0);
        assert.match(fs.readFileSync(output, 'utf8'), /backend=true/);
    } finally {
        for (const file of [output, summary]) if (fs.existsSync(file)) fs.unlinkSync(file);
        fs.rmdirSync(directory);
    }
});

test('actual final gate rejects failed, cancelled and unexpectedly skipped dependencies', () => {
    const workflow = fs.readFileSync(path.join(root, '.github/workflows/deploy.yml'), 'utf8');
    const source = workflow.match(/          node - <<'NODE'\r?\n([\s\S]*?)          NODE/)?.[1];
    assert.ok(source, 'Missing final gate');
    const needs = backend => ({changes: {result: 'success', outputs: {
        backend: String(backend), ai: String(backend), progress: String(backend), release: 'false'}},
        'test-build': {result: 'success'}});
    const run = checks => spawnSync(process.execPath, ['-e', source], {
        encoding: 'utf8', env: {...process.env, NEEDS: JSON.stringify(checks)}});
    assert.equal(run(needs(false)).status, 0);
    assert.equal(run(needs(true)).status, 0);
    for (const name of ['changes','test-build']) {
        for (const result of ['failure', 'cancelled', 'skipped']) {
            const checks = needs(true); checks[name].result = result;
            assert.notEqual(run(checks).status, 0);
        }
        const checks = needs(true); delete checks[name];
        assert.notEqual(run(checks).status, 0);
    }
    const checks = needs(false); checks.changes.result = 'failure';
    assert.notEqual(run(checks).status, 0);
    const invalid = needs(false); delete invalid.changes.outputs.backend;
    assert.notEqual(run(invalid).status, 0);
    const release = needs(false); release.changes.outputs.release = 'true';
    assert.notEqual(run(release).status, 0);
    const complete = needs(true); complete.changes.outputs.release = 'true';
    assert.equal(run(complete).status, 0);
});

test('deployment revision validation rejects a different source revision, even with Python optimization', () => {
    const workflow = fs.readFileSync(path.join(root, '.github/workflows/deploy.yml'), 'utf8');
    const source = workflow.match(/\| python3 -c '(.*Release revision mismatch.*)'/)?.[1];
    assert.ok(source, 'Missing deployment revision validation');
    const run = revision => spawnSync(process.platform === 'win32' ? 'python' : 'python3', ['-c', source], {
        encoding: 'utf8', input: JSON.stringify({sourceRevision: revision}),
        env: {...process.env, GITHUB_SHA: 'a'.repeat(40), PYTHONOPTIMIZE: '1'}});
    assert.equal(run('a'.repeat(40)).status, 0);
    assert.notEqual(run('b'.repeat(40)).status, 0);
    assert.notEqual(run(undefined).status, 0);
});

test('demo releases package in test-build while service checks remain manual', () => {
    const read = name => fs.readFileSync(path.join(root, '.github/workflows', name), 'utf8');
    const ai = read('ai-platform.yml'), progress = read('progress.yml'), deploy = read('deploy.yml');
    for (const workflow of [ai, progress]) {
        assert.match(workflow, /  workflow_call:/);
        assert.doesNotMatch(workflow, /^  (push|pull_request):/m);
    }
    assert.doesNotMatch(deploy, /uses: \.\/\.github\/workflows\/(ai-platform|progress)\.yml/);
    assert.doesNotMatch(deploy, /^  (postgres-tests|reliability|ai-platform|progress|schedule):/m);
    assert.equal(deploy.split('service-bundle.py build --revision').length - 1, 1);
    assert.match(deploy, /--output target\/services\.tar\.gz/);
    assert.match(deploy, /service-bundle\.py verify target\/services\.tar\.gz/);
    assert.match(deploy, /name: application\r?\n\s+retention-days: 3/);
    assert.match(deploy, /Release revision mismatch/);
    assert.match(ai, /if: \$\{\{ !inputs.core-unit-covered \}\}/);
    assert.match(ai, /if: inputs.publish-bundle && github.ref == 'refs\/heads\/main'/);
    assert.match(progress, /-Ddictalab.testcontainers.postgres-image=postgres:17\.11-alpine/);
    const progressTest = fs.readFileSync(path.join(root,
        'services/progress/src/test/java/com/dictalab/progress/ProgressIntegrationTest.java'), 'utf8');
    assert.match(progressTest, /System.getProperty\("dictalab.testcontainers.postgres-image"/);
    for (const workflow of [ai, progress, deploy]) {
        assert.match(workflow, /github.event_name != 'pull_request' \|\| failure\(\)/);
    }
});
