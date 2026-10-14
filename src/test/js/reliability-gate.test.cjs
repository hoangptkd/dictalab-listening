const {test} = require('node:test');
const assert = require('node:assert/strict');
const {required, parseReport, verifySuites} = require('../../../scripts/verify-reliability-tests.cjs');
const passing = () => Object.entries(required).map(([name, tests]) => ({name, tests, failures: 0, errors: 0, skipped: 0}));

test('learning concurrency and broker recovery evidence cannot be skipped in CI', () => {
    for(const name of ['ListeningLearningIntegrationTest','LearningJobsIntegrationTest'])
        assert.throws(()=>verifySuites(passing().filter(suite=>suite.name!==name)),new RegExp('Missing or duplicate suite: '+name));
});

test('S3 transport retry regressions are mandatory in the Maven profile and evidence verifier', () => {
    const fs = require('node:fs');
    const path = require('node:path');
    const pom = fs.readFileSync(path.join(__dirname, '../../../pom.xml'), 'utf8');
    const profile = pom.match(/<id>reliability<\/id>([\s\S]*?)<\/profile>/)?.[1];
    assert.ok(profile?.includes('<include>**/S3MediaConfigTest.java</include>'));
    assert.equal(required.S3MediaConfigTest, 4);
    assert.throws(() => verifySuites(passing().filter(suite => suite.name !== 'S3MediaConfigTest')), /Missing or duplicate suite: S3MediaConfigTest/);
});

test('reliability gate requires every suite and no skipped, failed or missing cases', () => {
    assert.equal(verifySuites(passing()).skipped, 0);
    assert.throws(() => verifySuites(passing().slice(1)), /Missing or duplicate/);
    assert.throws(() => verifySuites([...passing(), passing()[0]]), /Missing or duplicate/);
    for (const field of ['failures', 'errors', 'skipped']) {
        const suites = passing(); suites[0][field] = 1;
        assert.throws(() => verifySuites(suites), new RegExp(field));
    }
    const suites = passing(); suites[1].tests--;
    assert.throws(() => verifySuites(suites), /Missing cases/);
});

test('Surefire parser rejects malformed, truncated and missing-count reports', () => {
    const xml = '<testsuite name="com.dictalab.Example" tests="1" failures="0" errors="0" skipped="0"><testcase name="works"/></testsuite>';
    assert.equal(parseReport(xml).tests, 1);
    assert.throws(() => parseReport(''), /Missing Surefire/);
    assert.throws(() => parseReport(xml.replace('skipped="0"', '')), /Invalid skipped/);
    assert.throws(() => parseReport(xml.replace('tests="1"', 'tests="2"')), /Incomplete/);
    assert.throws(() => parseReport(xml.replace('</testsuite>', '')), /Truncated/);
});

test('CI retains concurrency CSV and rejects missing evidence before allowing deployment', () => {
    const fs = require('node:fs');
    const path = require('node:path');
    const workflow = fs.readFileSync(path.join(__dirname, '../../../.github/workflows/deploy.yml'), 'utf8');
    const reliability = workflow.match(/^  reliability:\r?\n([\s\S]*?)(?=^  deploy:)/m)?.[1];
    assert.ok(reliability, 'Missing reliability job');
    const evidence = 'target/reliability/concurrency-matrix.csv';
    const run = reliability.match(/      - name: Run mandatory concurrency and messaging tests[\s\S]*?(?=      - name:)/)?.[0];
    assert.ok(run?.includes(`-Ddictalab.race-evidence=${evidence}`), 'Concurrency CSV must use the uploaded build directory');
    const gate = reliability.match(/      - name: Reject missing or skipped reliability tests[\s\S]*?(?=      - name:)/)?.[0];
    assert.ok(gate?.includes(`test -s ${evidence}`), 'Missing or empty concurrency evidence must fail CI');
    const upload = reliability.slice(reliability.indexOf('      - name: Save reliability evidence'));
    assert.match(upload, /if: always\(\)/);
    assert.ok(upload.split(/\r?\n/).some(line => line.trim() === evidence), 'Concurrency CSV must be uploaded even after a failed run');
    assert.match(workflow.slice(workflow.indexOf('  deploy:')), /needs: \[test-build, reliability\]/);
});

test('VPS preflight collectors run long enough to produce ten samples without weakening guards', () => {
    const fs = require('node:fs');
    const path = require('node:path');
    const root = path.join(__dirname, '../../../scripts');
    const scripts = ['preflight-evidence-vps.ps1', 'run-final-capacity-vps.ps1', 'start-capacity-vps.ps1']
        .map(name => fs.readFileSync(path.join(root, name), 'utf8'));
    for (const script of scripts) assert.match(script, /-Seconds 70\b/);
    assert.match(scripts[0], /MinSamples=10/);
    assert.match(scripts[0], /DiskQueue=2/);
    assert.match(scripts[0], /PagesInputSec=100/);
    assert.doesNotMatch(scripts[0], /-Seconds 60\b/);
});
