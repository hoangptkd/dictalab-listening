import http from 'k6/http';
import ws from 'k6/ws';
import { check, fail } from 'k6';
import { base, users, client, setupAuth, journeyOptions, completed } from './shared.js';

if (users > 100) throw new Error('Room smoke budget is 100 rooms/200 synthetic accounts, not a capacity claim');
export const options = journeyOptions(Number(__ENV.P95_MS || 1000), 100);
export function setup() { return setupAuth(users * 2); }
export default function(tokens) {
    const hostToken = tokens[(__VU - 1) * 2], guestToken = tokens[(__VU - 1) * 2 + 1];
    const host = client(hostToken), guest = client(guestToken);
    const room = host('POST', '/api/v1/rooms', {}, 'room-create', b => !!b.roomId && !!b.inviteCode);
    const path = `/api/v1/rooms/${room.roomId}`;
    let leaveOk = false;
    try {
        guest('POST', path + '/join', {}, 'room-join', b => b.status === 'PENDING_APPROVAL');
        host('POST', path + '/approve', {}, 'room-approve', b => b.status === 'ACTIVE' && b.guest.userId === guestToken.id);
        const lessons = host('GET', '/api/v1/catalog/lessons?level=A1', null, 'catalog', b => Array.isArray(b) && b.length > 0);
        host('POST', path + '/lesson', { lessonId: lessons[0].id }, 'room-lesson', b => b.lessonId === lessons[0].id);
        guest('POST', path + '/practice', {}, 'room-practice', b => !!b.sessionId && !!b.sentence);
        // One host socket exercises connect/heartbeat/reconnect; REST checks guest ownership and approval.
        for (let reconnect = 0; reconnect < 2; reconnect++) {
            for (const [key, values] of Object.entries(hostToken.cookies)) http.cookieJar().set(base, key, values[0]);
            let pong = false, serverError = false;
            const response = ws.connect(base.replace(/^http/, 'ws') + `/ws/room/${room.roomId}`, {
                headers: { Origin: __ENV.WS_ORIGIN || 'http://localhost:18081' }, tags: { name: 'room-websocket' },
            }, socket => {
                socket.on('open', () => socket.send(JSON.stringify({ type: 'ping' })));
                socket.on('message', raw => {
                    const body = JSON.parse(raw);
                    if (body.type === 'pong') {
                        pong = true;
                        socket.send(JSON.stringify({ type: 'draft', position: 1, sequence: reconnect + 1, words: ['fixture'] }));
                    }
                    if (body.type === 'error') serverError = true;
                });
                socket.setTimeout(() => socket.close(), 2500);
            });
            if (!check(response, { 'socket handshake and heartbeat valid': r => r.status === 101 && pong && !serverError })) fail('Room signaling failed');
            guest('GET', path, null, 'room-poll', b => b.peerDraftSequence === reconnect + 1 && b.peerDrafts[0] === 'fixture');
        }
        completed.add(1);
    } finally {
        for (const [key, values] of Object.entries(hostToken.cookies)) http.cookieJar().set(base, key, values[0]);
        const response = http.del(base + path, null, { headers: { [hostToken.csrf.headerName]: hostToken.csrf.token }, tags: { phase: 'cleanup', name: 'room-leave' } });
        leaveOk = check(response, { 'room cleaned up': r => r.status === 200 || r.status === 204 });
        if (!leaveOk) fail('Room cleanup failed');
    }
}
