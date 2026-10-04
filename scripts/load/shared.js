import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Rate, Counter } from 'k6/metrics';

export const base = __ENV.BASE_URL || 'http://127.0.0.1:18282';
export const users = Number(__ENV.USERS || 1);
export const seconds = Number(__ENV.SECONDS || 60);
export const failures = new Rate('business_failed');
export const completed = new Counter('journeys_completed');
let requestSequence = 0;
let slowLogged = 0;
// No URL, body, cookie, answer or credential in diagnostic output.
export function slowResponseSummary(response, name) {
    if (!(response.timings.duration >= 1000)) return null;
    return {event: 'slow_request', name, status: response.status,
        requestId: response.headers['X-Request-Id'] || response.headers['X-Request-ID'] || '',
        durationMs: response.timings.duration, blockedMs: response.timings.blocked,
        connectingMs: response.timings.connecting, waitingMs: response.timings.waiting,
        receivingMs: response.timings.receiving};
}
// JSON object member order is not part of the replay contract; array order and values are.
export function sameJson(a, b) {
    if (a === b) return true;
    if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object'
        || Array.isArray(a) !== Array.isArray(b)) return false;
    const keys = Object.keys(a);
    return keys.length === Object.keys(b).length && keys.every(key =>
        Object.prototype.hasOwnProperty.call(b, key) && sameJson(a[key], b[key]));
}
if (!__ENV.CAPACITY_PASSWORD || !Number.isInteger(users) || users < 1 || users > 20000
    || !Number.isInteger(seconds) || seconds < 10 || seconds > 3600) {
    throw new Error('Explicit fixture password and valid user/duration budgets are required');
}
export function valid(response, predicate) {
    let body;
    try { body = response.json(); } catch { body = null; }
    const ok = response.status >= 200 && response.status < 300 && body !== null && predicate(body);
    check(response, { 'response and business state valid': () => ok });
    failures.add(!ok);
    if (!ok) fail(`Business response rejected: HTTP ${response.status}`);
    return body;
}
export function setupAuth(count = users) {
    const tokens = [];
    for (let n = 1; n <= count; n++) {
        http.cookieJar().clear(base);
        let csrf = valid(http.get(base + '/api/auth/csrf', { tags: { phase: 'setup', name: 'csrf' } }), b => !!b.token);
        const auth = valid(http.post(base + '/api/auth/login', JSON.stringify({
            email: `capacity-${n}@loadtest.local`, password: __ENV.CAPACITY_PASSWORD,
        }), { headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
            tags: { phase: 'setup', name: 'login' } }), b => !!b.id);
        csrf = valid(http.get(base + '/api/auth/csrf', { tags: { phase: 'setup', name: 'csrf' } }), b => !!b.token);
        tokens.push({ id: auth.id, csrf, cookies: http.cookieJar().cookiesForURL(base) });
        sleep(Number(__ENV.LOGIN_PAUSE || 0.05));
    }
    return tokens;
}
export function client(token) {
    if (!token) fail('Missing distinct VU session');
    const headers = { 'Content-Type': 'application/json', [token.csrf.headerName]: token.csrf.token };
    return (method, path, body, name, predicate) => {
        for (const [key, values] of Object.entries(token.cookies)) http.cookieJar().set(base, key, values[0]);
        const response = http.request(method, base + path, body === null ? null : JSON.stringify(body), {
            headers: {...headers, 'X-Request-ID': `capacity-${__VU}-${++requestSequence}`},
            timeout: '95s', tags: { phase: 'work', name },
        });
        const slow = slowResponseSummary(response, name);
        if (slow && slowLogged++ < 5) console.log(JSON.stringify(slow));
        return valid(response, predicate);
    };
}
export function journeyOptions(p95 = 1000, maxUsers = 20000) {
    if (users > maxUsers) throw new Error('This workload exceeds its declared safety budget');
    return {
        insecureSkipTLSVerify: __ENV.INSECURE_TLS === 'true', setupTimeout: '10m',
        scenarios: { load: { executor: 'per-vu-iterations', vus: users,
            iterations: Number(__ENV.ITERATIONS || 1), maxDuration: `${seconds}s`, gracefulStop: '10s' } },
        thresholds: { business_failed: ['rate<0.01'], checks: ['rate>0.99'],
            journeys_completed: [`count>=${users * Number(__ENV.ITERATIONS || 1)}`],
            http_req_failed: ['rate<0.01'], 'http_req_duration{phase:work}': [`p(95)<${p95}`] },
        systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
    };
}
