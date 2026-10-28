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

test('compact word grading and retry races remain mandatory in PostgreSQL CI', () => {
    const fs = require('node:fs');
    const path = require('node:path');
    const pom = fs.readFileSync(path.join(__dirname, '../../../pom.xml'), 'utf8');
    const profile = pom.match(/<id>reliability<\/id>([\s\S]*?)<\/profile>/)?.[1];
    assert.ok(profile?.includes('<include>**/PracticeWordCheckIntegrationTest.java</include>'));
    assert.equal(required.PracticeWordCheckIntegrationTest, 6);
    assert.throws(() => verifySuites(passing().filter(suite => suite.name !== 'PracticeWordCheckIntegrationTest')), /Missing or duplicate suite/);
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
    assert.match(workflow.slice(workflow.indexOf('  deploy:')), /needs: \[test-build, postgres-tests, reliability, ai-platform, progress\]/);
});

test('CI runs shared checks once and PostgreSQL suites independently before deployment', () => {
    const fs = require('node:fs');
    const path = require('node:path');
    const workflow = fs.readFileSync(path.join(__dirname, '../../../.github/workflows/deploy.yml'), 'utf8');
    const job = name => workflow.split(`  ${name}:`)[1]?.split(/\r?\n  [\w-]+:\r?\n/)[0];
    const build = job('test-build');
    const postgres = job('postgres-tests');
    const reliability = job('reliability');
    const deploy = job('deploy');
    for (const section of [build, postgres, reliability, deploy]) assert.ok(section, 'Missing CI job');
    assert.doesNotMatch(build, /matrix:|services:|postgres-test/);
    assert.ok(build.indexOf('Resolve and scan runtime dependencies') > build.indexOf('Run tests and build executable JAR'),
        'Maven clean must finish before dependency evidence is generated');
    assert.ok(build.indexOf('Save dependency scan evidence') > build.indexOf('Verify S3 production packaging'),
        'An artifact quota failure must not prevent tests and package verification from running');
    for (const command of ['node --test src/test/js/*.cjs', 'node scripts/test-b1-expansion.cjs',
        'python3 -m unittest discover', 'python3 scripts/scan-dependencies.py',
        '-Pproduction-s3,ci-unit clean verify', 'python3 scripts/verify-production-jar.py']) {
        assert.ok(build.includes(command), `Missing shared check: ${command}`);
        assert.equal(workflow.split(command).length - 1, command.includes('verify-production-jar') ? 2 : 1);
    }
    assert.match(build, /name: application\r?\n\s+retention-days: 14\r?\n\s+path: target\/english-dictation-0\.1\.0-SNAPSHOT\.jar/);
    for (const section of [postgres, reliability]) {
        assert.match(section, /fail-fast: false/, 'One failed PostgreSQL variant must not cancel the other variant');
        assert.ok(section.includes(`postgres: \${{ fromJSON((github.event_name == 'schedule' || inputs.full_compatibility) && '["17.11-alpine","18.4-alpine"]' || '["18.4-alpine"]') }}`));
        assert.doesNotMatch(section, /^    needs:/m, 'Database tests must start independently of H2/build');
    }
    assert.match(postgres, /-Dspring\.profiles\.active=postgres-test/);
    assert.match(postgres, /-Pci-integration/);
    assert.match(postgres, /-Dspring\.datasource\.hikari\.minimum-idle=0 -Dspring\.test\.context\.cache\.maxSize=4 test/);
    assert.match(postgres, /name: postgres-test-reports-\$\{\{ matrix\.postgres \}\}/);
    assert.match(reliability, /-Ddictalab\.race-evidence=target\/reliability\/concurrency-matrix\.csv test/);
    assert.match(deploy, /needs: \[test-build, postgres-tests, reliability, ai-platform, progress\]/);
    for (const section of [build, deploy]) {
        assert.ok(section.includes("if: (github.event_name == 'push' || (github.event_name == 'workflow_dispatch' && !inputs.full_compatibility)) && github.ref == 'refs/heads/main'"));
    }
    assert.ok(deploy.includes("&& vars.PRODUCTION_DEPLOY_ENABLED == 'true'"), 'Production activation requires explicit opt-in while AWS migration is pending');
    assert.doesNotMatch(build + postgres + reliability, /vars\.PRODUCTION_DEPLOY_ENABLED/, 'Pausing deployment must not skip any test gate');
    assert.match(workflow, /cron: '23 20 \* \* 6'/);
    assert.match(workflow, /full_compatibility:[\s\S]*?type: boolean/);
    assert.ok(workflow.includes("&& 'compatibility' || 'production'"), 'Compatibility checks must not block the production concurrency group');
});

test('CI Java test partition covers every Spring context suite without duplicating unit tests', () => {
    const fs = require('node:fs');
    const path = require('node:path');
    const root = path.join(__dirname, '../../..');
    const pom = fs.readFileSync(path.join(root, 'pom.xml'), 'utf8');
    const unit = pom.match(/<id>ci-unit<\/id>([\s\S]*?)<\/profile>/)?.[1];
    const integration = pom.match(/<id>ci-integration<\/id>([\s\S]*?)<\/profile>/)?.[1];
    assert.ok(unit && integration, 'Missing CI test partition');
    const excludes = [...unit.matchAll(/<exclude>(.*?)<\/exclude>/g)].map(match => match[1]).sort();
    const includes = [...integration.matchAll(/<include>(.*?)<\/include>/g)].map(match => match[1]).sort();
    assert.deepEqual(excludes, includes, 'Integration includes must cover every unit exclusion');
    const matches = name => includes.some(pattern => new RegExp('^' + pattern.replace('**/', '').replaceAll('.', '\\.').replaceAll('*', '.*') + '$').test(name));
    const files = fs.readdirSync(path.join(root, 'src/test/java'), {recursive: true})
        .filter(name => /(?:Test|Tests|TestCase)\.java$/.test(name));
    let contexts = 0;
    for (const file of files) {
        const source = fs.readFileSync(path.join(root, 'src/test/java', file), 'utf8');
        const context = /@(?:SpringBootTest|DataJpaTest|WebMvcTest|JdbcTest|SpringJUnitConfig|ContextConfiguration)\b/.test(source);
        if (context) {
            contexts++;
            assert.ok(matches(path.basename(file)), `Spring context test excluded from PostgreSQL: ${file}`);
        }
    }
    assert.ok(contexts >= 39, 'Spring context inventory unexpectedly empty');
    assert.ok(!matches('JwtTokenServiceTest.java'), 'Pure unit tests must not be repeated on PostgreSQL');
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
