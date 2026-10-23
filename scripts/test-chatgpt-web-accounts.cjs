// No real browser or quota consumption: node scripts/test-chatgpt-web-accounts.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const patch = fs.readFileSync(path.join(__dirname, 'chatgpt-web-accounts.patch'), 'utf8');
const added = patch.split('\n').filter(line => line.startsWith('+') && !line.startsWith('+++'))
  .map(line => line.slice(1)).join('\n');
const start = added.indexOf('function isQuotaNotice(');
const end = added.indexOf('async function visibleModelLabel(');
assert(start >= 0 && end > start);
const { isQuotaNotice, assertWebQuotaAvailable } = vm.runInNewContext(
  added.slice(start, end) + '\n({isQuotaNotice, assertWebQuotaAvailable})');
assert(added.includes('process.env.BRIDGE_DATA_DIR'));
assert(added.includes('process.env.CHROME_PROFILE_DIR'));
assert(added.includes('web_judge: result.webJudge'));
assert(added.includes('webQuotaProtocol: 1'));
assert(added.includes('"chrome://newtab/"'));
assert(added.includes('await claimConversationPage(contexts[0], { id: "bridge-startup" })'));
assert(added.includes('await assertWebQuotaAvailable(page)'));
for (const text of ["You've hit the free plan limit for GPT-6", "You’ve reached your usage limit",
  'You have reached the message limit. Try again later.', 'Bạn đã đạt giới hạn sử dụng.']) {
  assert(isQuotaNotice(text), text);
}
for (const text of ['Network disconnected', 'Something went wrong', 'Timeout', 'Speed limit: 30']) {
  assert(!isQuotaNotice(text), text);
}
(async () => {
  const page = texts => ({ evaluate: async fn => {
    for (const selector of ['[data-message-author-role]', '[data-markdown-text-style]',
      '[data-testid="user-message"]', 'article', '#prompt-textarea', 'nav', 'aside']) {
      assert(fn.toString().includes(selector));
    }
    return texts;
  }});
  await assertWebQuotaAvailable(page(['Everything is ready']));
  await assert.rejects(() => assertWebQuotaAvailable(page(["You've hit the free plan limit"])),
    error => error.status === 429 && error.code === 'chatgpt_web_quota');
  console.log('Web account quota and profile checks passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
