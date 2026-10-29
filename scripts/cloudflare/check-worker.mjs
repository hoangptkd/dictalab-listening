import assert from "node:assert/strict";
import worker from "./worker.mjs";

const base = "https://dictalab.hoangptkdhy.workers.dev";
let sent;
globalThis.fetch = async (request, options) => {
  sent = { request, options };
  return new Response("private", { headers: { "Cache-Control": "no-store", "Set-Cookie": "session=value; Secure; HttpOnly" } });
};
const response = await worker.fetch(new Request(base + "/api/test?q=a%20b", {
  method: "POST", body: "payload", headers: {
    Cookie: "session=value", "X-XSRF-TOKEN": "csrf", Origin: "https://untrusted.example",
    "X-Forwarded-For": "spoofed", "CF-Connecting-IP": "203.0.113.2",
    "Forwarded": "host=spoofed", "Range": "bytes=0-255",
  },
}));
assert.equal(sent.request.url, "https://dictalab-origin-54-254-221-170.sslip.io/api/test?q=a%20b");
assert.equal(await sent.request.text(), "payload");
assert.equal(sent.request.headers.get("Cookie"), "session=value");
assert.equal(sent.request.headers.get("X-XSRF-TOKEN"), "csrf");
assert.equal(sent.request.headers.get("Origin"), "https://untrusted.example");
assert.equal(sent.request.headers.get("X-Forwarded-For"), "203.0.113.2");
assert.equal(sent.request.headers.get("Forwarded"), null);
assert.equal(sent.request.headers.get("Range"), "bytes=0-255");
assert.equal(sent.request.redirect, "manual");
assert.equal(sent.options.cache, "no-store");
assert.equal(response.headers.get("Cache-Control"), "no-store");
assert.equal(response.headers.get("Set-Cookie"), "session=value; Secure; HttpOnly");

const upgrade = { status: 101, webSocket: {} };
globalThis.fetch = async () => upgrade;
assert.equal(await worker.fetch(new Request(base + "/ws/room/test")), upgrade);
globalThis.fetch = async () => new Response(null, { status: 302, headers: { Location: "/login?next=%2Fhistory" } });
assert.equal((await worker.fetch(new Request(base + "/history"))).headers.get("Location"), base + "/login?next=%2Fhistory");
globalThis.fetch = async () => new Response(null, { status: 302, headers: { Location: "https://accounts.google.com/" } });
assert.equal((await worker.fetch(new Request(base + "/login"))).headers.get("Location"), "https://accounts.google.com/");
assert.equal((await worker.fetch(new Request("https://other.example/"))).status, 404);
console.log("WORKER_HEADERS_BODY_RANGE_REDIRECT_WEBSOCKET=PASS");
