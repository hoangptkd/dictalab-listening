const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {textRequest} = require('../../../scripts/create-b1-dictation.cjs');

test('offline authoring sends stdin to the shared gateway and refuses replay after process failure', async () => {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'dictalab-text-'));
    try {
        let calls = 0;
        const launch = (executable, args, options) => {
            calls++;
            assert.ok(args[0].endsWith('ai-text.py'));
            assert.equal(args.length, 1);
            assert.equal(JSON.parse(options.input).model, 'fixture-model');
            assert.equal(options.windowsHide, true);
            return {status: 1, stdout: ''};
        };
        const marker = path.join(directory, 'attempt.sent');
        const payload = {model:'fixture-model', messages:[{role:'user', content:'Synthetic text'}]};
        await assert.rejects(textRequest(payload, marker, launch), /outcome unknown/);
        await assert.rejects(textRequest(payload, marker, launch), /EEXIST/);
        assert.equal(calls, 1);
        const saved = JSON.parse(await fs.readFile(marker, 'utf8'));
        assert.equal(saved.version, 1);
        assert.match(saved.hash, /^[a-f0-9]{64}$/);
        assert.ok(!JSON.stringify(saved).includes('Synthetic text'));
    } finally {
        assert.ok(path.resolve(directory).startsWith(path.resolve(os.tmpdir()) + path.sep));
        await fs.rm(directory, {recursive:true, force:true});
    }
});
