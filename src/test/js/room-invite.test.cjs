const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const app = fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/js/app.js"), "utf8");
const match = app.match(/    function parseRoomInvite\(input\) \{[\s\S]*?\n    \}\n\n    async function roomOpenByInviteCode/);
assert.ok(match, "parseRoomInvite must exist in app.js");
const parseRoomInvite = vm.runInNewContext(match[0].replace(/\n\n    async function roomOpenByInviteCode$/, "\nparseRoomInvite"), {URL});
const code = "Abcdefghijklmnopqr12_-";

test("accepts a code, a new invitation URL and a legacy invitation URL", () => {
    assert.equal(parseRoomInvite(code).code, code);
    assert.equal(parseRoomInvite(`http://example.test/rooms/join/${code}`).code, code);
    assert.equal(parseRoomInvite(`http://example.test/?invite=${code}`).code, code);
});

test("rejects room addresses and malformed invitations before calling the API", () => {
    assert.match(parseRoomInvite("http://example.test/rooms/17f3d7c0-bb8d-4547-b3d3-b2d705aec2a3").error, /địa chỉ phòng/);
    assert.ok(parseRoomInvite("http://example.test/rooms/join/").error);
    assert.ok(parseRoomInvite("not-an-invite-code").error);
});
