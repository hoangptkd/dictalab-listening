const {execFileSync} = require('node:child_process');
const fs = require('node:fs');

function classify(paths) {
    const flags = {backend: false, ai: false, progress: false, frontend: false};
    for (const path of paths) {
        if (path.startsWith('src/main/resources/static/')) flags.frontend = true;
        else if (path.startsWith('services/progress/') || /^docs\/progress-.*\.(json|yaml)$/.test(path)) flags.progress = true;
        else if (/^(services\/(ai-generation|model-serving)\/|tools\/ai-eval\/|training\/|artifacts\/prompts\/)/.test(path)
            || /^artifacts\/reports\/ai-platform-.*\.json$/.test(path)
            || path === '.github/workflows/ai-platform.yml') flags.ai = true;
        else if (path === '.github/workflows/progress.yml') flags.progress = true;
        else if (/^(ios\/|src\/test\/js\/)/.test(path) || /^docs\/.*\.(md|png|jpg|svg|pdf)$/.test(path)
            || /^[^/]+\.md$/.test(path)
            || /^\.github\/workflows\/(ios|training-offline)\.yml$/.test(path)) continue;
        // Unknown/shared changes require all source scopes to be built.
        else Object.assign(flags, {backend: true, ai: true, progress: true});
    }
    return flags;
}

function plan(event, ref, paths, fullCompatibility = false) {
    const flags = classify(paths);
    const changed = Object.values(flags).some(Boolean);
    const release = ref === 'refs/heads/main' && !fullCompatibility
        && (event === 'workflow_dispatch' || event === 'push' && changed);
    // Every release and explicit validation builds the complete source revision.
    if (release || !['pull_request', 'push'].includes(event)) {
        Object.assign(flags, {backend: true, ai: true, progress: true, frontend: true});
    }
    return {...flags, release};
}

if (require.main === module) {
    const event = process.env.GITHUB_EVENT_NAME;
    let paths = [];
    if (['pull_request', 'push'].includes(event)) {
        const base = process.env.CI_BASE_SHA;
        const head = process.env.GITHUB_SHA;
        if (!/^[a-f0-9]{40}$/.test(base || '') || !/^[a-f0-9]{40}$/.test(head || '')) {
            throw new Error('Invalid CI comparison revision');
        }
        paths = /^0+$/.test(base) ? ['unknown-initial-push']
            : execFileSync('git', ['diff', '--name-only', '--no-renames', '-z', base, head, '--'],
                {encoding: 'utf8', maxBuffer: 8 * 1024 * 1024}).split('\0').filter(Boolean);
    }
    const result = plan(event, process.env.GITHUB_REF, paths, process.env.FULL_COMPATIBILITY === 'true');
    fs.appendFileSync(process.env.GITHUB_OUTPUT, Object.entries(result).map(([key, value]) => `${key}=${value}\n`).join(''));
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY,
        '## CI scope\n\n' + Object.entries(result).map(([key, value]) => `- ${key}: ${value}\n`).join(''));
}

module.exports = {classify, plan};
