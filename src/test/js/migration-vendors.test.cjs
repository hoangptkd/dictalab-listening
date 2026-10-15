const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

test('H2 variants retain the PostgreSQL migration data statements', () => {
  const root = path.resolve(__dirname, '../../main/resources/db/vendor');
  for (const name of fs.readdirSync(path.join(root, 'postgresql'))) {
    const postgres = fs.readFileSync(path.join(root, 'postgresql', name), 'utf8');
    const h2 = fs.readFileSync(path.join(root, 'h2', name), 'utf8');
    assert.equal(h2, postgres.replace(/^SET LOCAL [^\r\n]*\r?\n/gm, ''));
    assert.match(postgres, /SET LOCAL lock_timeout/);
    assert.match(postgres, /SET LOCAL statement_timeout/);
  }
});
