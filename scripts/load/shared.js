import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';

export const base = __ENV.BASE_URL || 'http://127.0.0.1:18282';
export const users = Number(__ENV.USERS || 1);
export const seconds = Number(__ENV.SECONDS || 60);
export const failures = new Rate('business_failed');
export const completed = new Counter('journeys_completed');
export const cleaned = new Counter('auth_families_cleaned');
export const cleanupFailed = new Rate('auth_cleanup_failed');
export const actionDuration = new Trend('business_action_duration', true);
export const csrfRecoveries = new Counter('csrf_recoveries');
let requestSequence = 0;
let slowLogged = 0;
// Each k6 VU has its own module instance. Preserve rotated cookies, including when
// Room switches between its two accounts, instead of restoring expired setup JWTs.
const sessions = new Map();
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
    || !Number.isInteger(seconds) || seconds < 10 || seconds > 10800) {
    throw new Error('Explicit fixture password and valid user/duration budgets are required');
}
export function valid(response, predicate) {
    let body;
    try { body = response.status === 204 ? {} : response.json(); } catch { body = null; }
    const ok = response.status >= 200 && response.status < 300 && body !== null && predicate(body);
    check(response, { 'response and business state valid': () => ok });
    failures.add(!ok);
    if (!ok) fail(`Business response rejected: HTTP ${response.status}`);
    return body;
}
function clearAuthJar() {
    // k6 clear()/delete() reconstruct cookies without their original Path.
    // Explicit expiry avoids retaining a refresh cookie or deleting the wrong path.
    for(const key of ['DICTALAB_ACCESS','DICTALAB_REFRESH','XSRF-TOKEN','JSESSIONID'])
        http.cookieJar().set(base,key,'',{path:key==='DICTALAB_REFRESH'?'/api/auth':'/',max_age:-1});
}
export function setupAuth(count = users) {
    const tokens = [];
    for (let n = 1; n <= count; n++) {
        clearAuthJar();
        let csrf = valid(http.get(base + '/api/auth/csrf', { tags: { phase: 'setup', name: 'csrf' } }), b => !!b.token);
        const auth = valid(http.post(base + '/api/auth/login', JSON.stringify({
            email: `capacity-${n}@loadtest.local`, password: __ENV.CAPACITY_PASSWORD,
        }), { headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
            tags: { phase: 'setup', name: 'login' } }), b => !!b.id);
        csrf = valid(http.get(base + '/api/auth/csrf', { tags: { phase: 'setup', name: 'csrf' } }), b => !!b.token);
        const cookies=http.cookieJar().cookiesForURL(base + '/api/auth');
        if(!cookies.DICTALAB_ACCESS?.[0] || !cookies.DICTALAB_REFRESH?.[0])
            fail('Authentication cookies missing from the setup jar');
        tokens.push({ id: auth.id, csrf, issuedAt: Date.now(), cookies });
        sleep(Number(__ENV.LOGIN_PAUSE || 0.05));
    }
    return tokens;
}
export function cleanupAuth(tokens) {
    for(const token of tokens||[]){
        // Logout accepts a recognized old refresh secret even after rotation/access expiry.
        // Do not replay that old secret at the refresh endpoint.
        clearAuthJar();
        http.cookieJar().set(base,'DICTALAB_REFRESH',token.cookies.DICTALAB_REFRESH[0],{path:'/api/auth'});
        const csrf=http.get(base+'/api/auth/csrf',{tags:{phase:'cleanup',name:'csrf-cleanup'}});
        let ok=false;
        if(csrf.status===200){
            const body=csrf.json();
            const response=http.post(base+'/api/auth/logout',null,{headers:{[body.headerName]:body.token},
                timeout:'10s',tags:{phase:'cleanup',name:'logout'}});
            ok=response.status===204;
        }
        cleanupFailed.add(!ok);if(ok)cleaned.add(1);
        sleep(0.6); // Retain the IP-scoped authentication rate limit during cleanup.
    }
}
export function client(token) {
    if (!token) fail('Missing distinct VU session');
    if (!sessions.has(token.id)) sessions.set(token.id, {cookies: token.cookies, csrf: token.csrf,
        refreshAt: (token.issuedAt || Date.now()) + (240 + (__VU % 60)) * 1000});
    const session = sessions.get(token.id);
    function restore() {
        clearAuthJar();
        for (const [key, values] of Object.entries(session.cookies))
            http.cookieJar().set(base, key, values[0], {path: key === 'DICTALAB_REFRESH' ? '/api/auth' : '/'});
    }
    function refresh() {
        const csrf = valid(http.get(base + '/api/auth/csrf', {tags: {phase: 'work', name: 'csrf-refresh'}}), b => !!b.token);
        const response = http.post(base + '/api/auth/refresh', null, {
            headers: {[csrf.headerName]: csrf.token}, timeout: '10s', tags: {phase: 'work', name: 'auth-refresh'}});
        session.cookies = http.cookieJar().cookiesForURL(base + '/api/auth');
        const ok = response.status === 204;
        check(response, {'refresh successful': () => ok}); failures.add(!ok);
        if (!ok) fail(`Refresh rejected: HTTP ${response.status}`); // Never retry lost/503 refresh responses.
        session.csrf = csrf;
        session.refreshAt = Date.now() + (240 + (__VU % 60)) * 1000;
    }
    return (method, path, body, name, predicate, submissionKey) => {
        restore();
        if(__ENV.AUTH_COOKIE_DIAGNOSTICS==='true' && requestSequence===0)
            console.log(JSON.stringify({event:'cookie_metadata',savedNames:Object.keys(session.cookies),
                restoredNames:Object.keys(http.cookieJar().cookiesForURL(base+'/api/auth')),
                accessCharacters:session.cookies.DICTALAB_ACCESS?.[0]?.length||0}));
        if (Date.now() >= session.refreshAt) refresh();
        const begin = Date.now();
        const send = () => {
            try {
                return http.request(method, base + path, body === null ? null : JSON.stringify(body), {
                    headers: {'Content-Type': 'application/json', [session.csrf.headerName]: session.csrf.token,
                        'X-Request-ID': `capacity-${__VU}-${++requestSequence}`,
                        ...(submissionKey ? {'Idempotency-Key':submissionKey} : {})},
                    timeout: '95s', tags: { phase: 'work', name },
                });
            } finally {
                // Preserve every Set-Cookie update/deletion, not only refresh rotation.
                session.cookies = http.cookieJar().cookiesForURL(base + '/api/auth');
            }
        };
        let response;
        try {
            response = send();
            if (!['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase()) && response.status === 403
                && String(response.headers['X-Csrf-Error'] ?? response.headers['X-CSRF-Error']) === '1') {
                csrfRecoveries.add(1, {name});
                const fresh = http.get(base + '/api/auth/csrf', {timeout: '10s', tags: {phase: 'work', name: 'csrf-recovery'}});
                session.cookies = http.cookieJar().cookiesForURL(base + '/api/auth');
                session.csrf = valid(fresh, b => !!b.token && !!b.headerName);
                response = send(); // Only explicit pre-controller CSRF rejection; never replay network/503/permission failures.
            }
        } finally {
            actionDuration.add(Date.now() - begin, {name});
        }
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
