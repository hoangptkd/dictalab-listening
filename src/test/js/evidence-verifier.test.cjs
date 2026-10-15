const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const {spawnSync} = require('node:child_process');
const {verify} = require('../../../scripts/load/verify-evidence.cjs');

const target = path.resolve(__dirname, '../../../target');
fs.mkdirSync(target, {recursive: true});
const folder = fs.mkdtempSync(path.join(target, 'evidence-verifier-'));
const cli = path.resolve(__dirname, '../../../scripts/load/verify-evidence.cjs');
const point = (metric, value, tags = {}) => ({type: 'Point', metric, data: {value, tags}});
function raw(count = 20, successes = count) {
    return [point('journeys_started', count), point('journeys_finished', count), point('journeys_completed', successes),
        point('work_window_ms', 30001), point('vus', 20),
        ...Object.entries({reads: count - 3, dictation: 1, listening: 1, room: 1})
            .map(([kind, value]) => point('workload_journeys', value, {kind})),
        ...Array.from({length: count}, (_, i) => point('journey_success', Number(i < successes)))];
}
let sequence = 0;
function save(points, gzip = false) {
    const file = path.join(folder, `${sequence++}.json${gzip ? '.gz' : ''}`);
    const content = typeof points === 'string' ? points : points.map(value => JSON.stringify(value)).join('\n');
    fs.writeFileSync(file, gzip ? zlib.gzipSync(content) : content);
    return file;
}

test('raw accounting accepts plain/gzip evidence and an accounted failure at the existing 99.9% SLO', async () => {
    for (const gzip of [false, true]) {
        const result = await verify(save(raw(1000, 999), gzip), 20, 30);
        assert.equal(result.passed, true);
        assert.equal(result.journeySuccess, 0.999);
        assert.equal(result.counts.journeys_completed, 999);
    }
    assert.deepEqual((await verify(save(raw(1000, 998)), 20, 30)).reasons, ['journey_success_below_99_9']);
});

test('raw accounting rejects missing/contradictory completion counters', async () => {
    for (const value of [0, 19, 21, undefined]) {
        const points = raw();
        if (value === undefined) points.splice(2, 1);
        else points[2].data.value = value;
        const result = await verify(save(points), 20, 30);
        assert.equal(result.passed, false, `completed=${value}`);
        assert.ok(result.reasons.includes('completed_journey_mismatch'));
    }
});

test('raw accounting reconciles all workload starts, not just nonempty kinds', async () => {
    for (const value of [16, 18]) {
        const points = raw();
        points[5].data.value = value;
        const result = await verify(save(points), 20, 30);
        assert.equal(result.passed, false);
        assert.ok(result.reasons.includes('workload_count_mismatch'));
    }
});

test('consumed metric values must be finite numbers in their metric domain', async () => {
    const invalid = [[0, -1], [0, 0.5], [0, '20'], [0, Number.MAX_SAFE_INTEGER + 1],
        [3, 'not-a-number'], [3, null], [3, -1], [4, 'not-a-number'], [4, 20.5], [4, null],
        [5, -1], [9, 2], [9, -1], [9, true]];
    for (const [index, value] of invalid) {
        const points = raw();
        points[index].data.value = value;
        await assert.rejects(verify(save(points), 20, 30), {name: 'AssertionError'}, `point ${index}: ${value}`);
    }
    await assert.rejects(verify(save(raw().map(value => JSON.stringify(value)).join('\n')
        .replace('30001', '1e309')), 20, 30), {name: 'AssertionError'});
    for (const metric of ['journeys_started', 'workload_journeys']) {
        await assert.rejects(verify(save([...raw(), point(metric, Number.MAX_SAFE_INTEGER, {kind: 'reads'})]),
            20, 30), {name: 'AssertionError'});
    }
});

test('malformed consumed data and unknown workload kinds fail closed', async () => {
    for (const mutate of [points => {points[0].data = null;}, points => {delete points[5].data.tags;},
        points => {points[5].data.tags.kind = 'unknown';}, points => {points[5].data.tags.kind = 'constructor';},
        points => {points[5].data.tags.kind = ['reads'];}]) {
        const points = raw();
        mutate(points);
        await assert.rejects(verify(save(points), 20, 30), {name: 'AssertionError'});
    }
});

test('exported verifier validates targets instead of allowing NaN or zero to bypass gates', async () => {
    const file = save(raw());
    for (const [users, seconds] of [[NaN, 30], [20, NaN], [0, 30], [20, 0], [20.5, 30], [20, '30']]) {
        await assert.rejects(verify(file, users, seconds), {name: 'AssertionError'});
    }
});

test('CLI reports missing/corrupt inputs without an unhandled stream error or raw payload', () => {
    const gzip = save(raw(), true);
    const bytes = fs.readFileSync(gzip);
    fs.writeFileSync(gzip, bytes.subarray(0, bytes.length - 8));
    const badGzip = save(raw(), true);
    fs.writeFileSync(badGzip, 'private-payload-not-gzip');
    for (const file of [path.join(folder, 'missing.json.gz'), path.join(folder, 'missing.json'),
        gzip, save('private-payload-invalid-json'), badGzip]) {
        const result = spawnSync(process.execPath, [cli, file, '20', '30'], {encoding: 'utf8', timeout: 5000});
        assert.equal(result.error, undefined);
        assert.equal(result.status, 1);
        assert.equal(result.stdout, '');
        assert.match(result.stderr, /^Evidence invalid: \w+\r?\n$/);
        assert.doesNotMatch(result.stderr, /private-payload|Unhandled|node:events/);
    }
});

test('CLI exit status enforces accounting failures while accepting 99.9% success', () => {
    for (const [points, status] of [[raw(1000, 999), 0], [raw(20, 0).map(value =>
        value.metric === 'journey_success' ? point('journey_success', 1) : value), 1]]) {
        const result = spawnSync(process.execPath, [cli, save(points, true), '20', '30'],
            {encoding: 'utf8', timeout: 5000});
        assert.equal(result.error, undefined);
        assert.equal(result.status, status);
        assert.equal(result.stderr, '');
        assert.equal(JSON.parse(result.stdout).passed, status === 0);
    }
    for (const args of [[], [save(raw()), 'NaN', '30'], [save(raw()), '20', '0']]) {
        const result = spawnSync(process.execPath, [cli, ...args], {encoding: 'utf8', timeout: 5000});
        assert.equal(result.error, undefined);
        assert.equal(result.status, 1);
        assert.equal(result.stdout, '');
        assert.equal(result.stderr, 'Evidence invalid: Usage\n');
    }
});
