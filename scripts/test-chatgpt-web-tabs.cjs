// Run without Chrome: node scripts/test-chatgpt-web-tabs.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Exercise the shipped patch, not an ignored local bridge checkout.
const patch = fs.readFileSync(path.join(__dirname, 'chatgpt-web-compat.patch'), 'utf8');
const added = patch.split('\n').filter(line => line.startsWith('+') && !line.startsWith('+++'))
  .map(line => line.slice(1)).join('\n');
const start = added.indexOf('async function claimConversationPage(');
assert(start >= 0, 'single-tab implementation must be in the shipped patch');
const end = added.indexOf('\n}', start) + 2;
const implementation = added.slice(start, end);
assert(added.includes('const MAX_CONVERSATION_TABS = 1;'));
assert(added.includes('await claimConversationPage(context, conversation)'));

class Page {
  constructor(url) { this.location = url; this.closed = false; this.visits = []; }
  url() { return this.location; }
  isClosed() { return this.closed; }
  async close() { this.closed = true; }
  setDefaultTimeout(timeout) { assert.equal(timeout, 15000); }
  async goto(url) { this.location = url; this.visits.push(url); }
}

function setup(urls) {
  const pages = urls.map(url => new Page(url));
  let created = 0;
  const context = {
    pages: () => pages.filter(page => !page.closed),
    newPage: async () => { created++; const page = new Page('about:blank'); pages.push(page); return page; },
  };
  const conversationPages = new Map(), pageLastUsed = new Map();
  const claim = vm.runInNewContext(implementation + '\nclaimConversationPage', {
    conversationPages, pageLastUsed, CHATGPT_URL: 'https://chatgpt.com/', URL,
  });
  return { claim, context, pages, conversationPages, pageLastUsed, created: () => created };
}

(async () => {
  // Startup/restart: orphaned ChatGPT tabs and blank startup tabs are reclaimed.
  const run = setup(['https://chatgpt.com/c/old', 'about:blank', 'https://chatgpt.com/c/wanted', 'https://example.org/']);
  const wanted = run.pages[2];
  assert.equal(await run.claim(run.context, { id: 'first', url: wanted.url() }), wanted);
  assert(run.pages[0].closed && run.pages[1].closed);
  assert(!run.pages[3].closed, 'unrelated browser content must be preserved');
  assert.equal(run.created(), 0);
  // Each new conversation navigates to a fresh composer on the SAME tab.
  for (let i = 0; i < 20; i++) {
    wanted.location = `https://chatgpt.com/c/response-${i}`;
    assert.equal(await run.claim(run.context, { id: `new-${i}` }), wanted);
    assert.equal(wanted.url(), 'https://chatgpt.com/');
    assert.equal(run.conversationPages.size, 1);
    assert.equal(run.pageLastUsed.size, 1);
  }
  assert.equal(run.created(), 0);
  assert.equal(wanted.visits.length, 20);
  // The previous conversation may still have the home URL while its ID resolves.
  await run.claim(run.context, { id: 'another-new' });
  assert.equal(wanted.visits.length, 21, 'new ID needs a fresh composer even at the home URL');
  await run.claim(run.context, { id: 'another-new' });
  assert.equal(wanted.visits.length, 21, 'same cached conversation must retain its history');
  // Empty profile and a closed cached page both allow exactly one replacement.
  const empty = setup([]);
  const first = await empty.claim(empty.context, { id: 'new' });
  assert.equal(empty.created(), 1);
  await first.close();
  const replacement = await empty.claim(empty.context, { id: 'new' });
  assert.notEqual(replacement, first);
  assert.equal(empty.created(), 2);
  assert.equal(empty.context.pages().length, 1);
  // Navigation failures propagate; no hidden retry/new page is attempted.
  replacement.goto = async () => { throw new Error('navigation failed'); };
  replacement.location = 'https://chatgpt.com/c/previous';
  await assert.rejects(empty.claim(empty.context, { id: 'next' }), /navigation failed/);
  assert.equal(empty.created(), 2);
  console.log('PASS: orphan cleanup, one-tab reuse, fresh conversation navigation, replacement, failure propagation');
})().catch(error => { console.error(error); process.exitCode = 1; });
