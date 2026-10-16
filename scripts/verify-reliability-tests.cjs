const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const required = {
    ReliabilityInfrastructureTest: 1,
    ListeningLearningIntegrationTest: 12,
    LearningJobsIntegrationTest: 6,
    AiUsageBudgetIntegrationTest: 4,
    BackendReadIntegrationTest: 7,
    PracticeWordCheckIntegrationTest: 6,
    ConversationIntegrationTest: 30,
    ListeningComprehensionIntegrationTest: 17,
    ConcurrencyMatrixTest: 28,
    EmailOutboxRecoveryTest: 8,
    RedisRateLimitIntegrationTest: 2,
    JwtRedisIntegrationTest: 11,
    ListeningGradingQueueIntegrationTest: 8,
    AiRabbitQueueIntegrationTest: 9,
    AiBackgroundJobsIntegrationTest: 16,
    S3MediaLifecycleIntegrationTest: 10,
    S3MediaIntegrationTest: 1,
    S3MediaConfigTest: 4,
    WorkerShutdownTest: 3,
    DatabaseDeadlineIntegrationTest: 4,
    ApiExceptionHandlerTest: 10,
    MessagingLifecycleConfigTest: 1,
    RestoreIsolationIntegrationTest: 7,
    PaymentOperationsIntegrationTest: 18,
    PaymentRestoreIntegrationTest: 1,
    BillingIntegrationTest: 7,
};

function parseReport(xml) {
    const root = xml.match(/<testsuite\s+([^>]+)>/);
    assert.ok(root, 'Missing Surefire testsuite');
    assert.match(xml, /<\/testsuite>\s*$/, 'Truncated Surefire report');
    const attributes = Object.fromEntries([...root[1].matchAll(/([\w.-]+)="([^"]*)"/g)].map(match => [match[1], match[2]]));
    const result = {name: attributes.name?.split('.').pop()};
    for (const field of ['tests', 'failures', 'errors', 'skipped']) {
        assert.match(attributes[field] ?? '', /^\d+$/, `Invalid ${field}`);
        result[field] = Number(attributes[field]);
    }
    assert.equal([...xml.matchAll(/<testcase\s/g)].length, result.tests, 'Incomplete testcase report');
    return result;
}

function verifySuites(suites) {
    const total = {tests: 0, failures: 0, errors: 0, skipped: 0};
    for (const [name, minimum] of Object.entries(required)) {
        const matching = suites.filter(suite => suite.name === name);
        assert.equal(matching.length, 1, `Missing or duplicate suite: ${name}`);
        const suite = matching[0];
        assert.ok(suite.tests >= minimum, `Missing cases in ${name}: ${suite.tests} < ${minimum}`);
        for (const field of ['failures', 'errors', 'skipped']) assert.equal(suite[field], 0, `${name}: ${field}`);
        for (const field of Object.keys(total)) total[field] += suite[field];
    }
    return total;
}

module.exports = {required, parseReport, verifySuites};
if (require.main === module) {
    const folder = process.argv[2];
    assert.ok(folder, 'Usage: node scripts/verify-reliability-tests.cjs <surefire-reports>');
    const suites = fs.readdirSync(folder).filter(file => /^TEST-.*\.xml$/.test(file))
        .map(file => parseReport(fs.readFileSync(path.join(folder, file), 'utf8')));
    console.log('Reliability gate passed:', verifySuites(suites));
}
