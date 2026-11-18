import { test } from "node:test";
import assert from "node:assert/strict";
import { handle, makeToken, parseToken, decrypt, cutBytes, MAX_TEXT_BYTES } from "../src/bridge.js";
import { identity } from "./keys.js";

async function setup() {
  const user = await identity();
  const bridge = await identity();
  const token = makeToken({ to: user.address, from: bridge.address, key: bridge.pkcs8 });
  const posted = [];
  const deps = {
    now: () => 1_700_000_000_000,
    fetch: async (u, init) => { posted.push({ url: u, body: init.body }); return new Response("{}", { status: 200 }); },
  };
  const call = (body, method = "POST") =>
    handle(new Request(`https://bridge.test/mcp/${token}`, { method, body: body && JSON.stringify(body), headers: { "Content-Type": "application/json" } }), deps);
  return { user, bridge, token, posted, deps, call };
}

test("initialize answers with the asked protocol and tools capability", async () => {
  const { call } = await setup();
  const r = await call({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "t", version: "1" } } });
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.equal(body.result.protocolVersion, "2025-03-26");
  assert.ok(body.result.capabilities.tools);
  assert.equal(body.result.serverInfo.name, "jarvis-bridge");
});

test("unknown protocol version gets the newest one", async () => {
  const { call } = await setup();
  const body = await (await call({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "1999-01-01" } })).json();
  assert.equal(body.result.protocolVersion, "2025-06-18");
});

test("notifications get 202 and no body", async () => {
  const { call } = await setup();
  const r = await call({ jsonrpc: "2.0", method: "notifications/initialized" });
  assert.equal(r.status, 202);
});

test("tools/list has tell_jarvis", async () => {
  const { call } = await setup();
  const body = await (await call({ jsonrpc: "2.0", id: 2, method: "tools/list" })).json();
  assert.deepEqual(body.result.tools.map((t) => t.name), ["tell_jarvis"]);
  assert.deepEqual(body.result.tools[0].inputSchema.required, ["text"]);
});

test("tell_jarvis posts a message only the user's Jarvis can read, from Gemini", async () => {
  const { call, posted, user, bridge } = await setup();
  const body = await (await call({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: "tell_jarvis", arguments: { text: "The report is ready, Sir." } } })).json();
  assert.equal(body.result.isError, false);
  assert.equal(posted.length, 1);
  assert.equal(posted[0].url, `https://ntfy.sh/${user.topic}`);
  const envelope = JSON.parse(posted[0].body);
  assert.equal(envelope.v, 1);
  assert.equal(envelope.from, bridge.address);
  assert.ok(!posted[0].body.includes("report"), "plaintext must not appear on the relay");
  const payload = JSON.parse(await decrypt(user.pkcs8, user.address, envelope));
  assert.deepEqual(payload, { name: "Gemini", text: "The report is ready, Sir.", t: 1_700_000_000_000 });
});

test("a relay failure is reported to Gemini as a tool error", async () => {
  const { token, deps } = await setup();
  deps.fetch = async () => new Response("no", { status: 503 });
  const r = await handle(new Request(`https://b/mcp/${token}`, { method: "POST", body: JSON.stringify({ jsonrpc: "2.0", id: 4, method: "tools/call", params: { name: "tell_jarvis", arguments: { text: "hi" } } }) }), deps);
  const body = await r.json();
  assert.equal(body.result.isError, true);
  assert.match(body.result.content[0].text, /503/);
});

test("empty text and unknown tools are refused", async () => {
  const { call, posted } = await setup();
  const empty = await (await call({ jsonrpc: "2.0", id: 5, method: "tools/call", params: { name: "tell_jarvis", arguments: { text: "  " } } })).json();
  assert.equal(empty.result.isError, true);
  const unknown = await (await call({ jsonrpc: "2.0", id: 6, method: "tools/call", params: { name: "rm_rf", arguments: {} } })).json();
  assert.equal(unknown.error.code, -32602);
  assert.equal(posted.length, 0);
});

test("bad tokens and paths are 404, GET on the MCP path is 405", async () => {
  const { call } = await setup();
  assert.equal((await handle(new Request("https://b/mcp/nonsense"))).status, 404);
  assert.equal((await handle(new Request("https://b/elsewhere"))).status, 404);
  assert.equal((await handle(new Request("https://b/"))).status, 200);
  assert.equal((await call(undefined, "GET")).status, 405);
});

test("tokens round-trip", async () => {
  const { token, user, bridge } = await setup();
  const p = parseToken(token);
  assert.equal(p.to.raw, user.address);
  assert.equal(p.from.raw, bridge.address);
  assert.deepEqual([...p.key], [...bridge.pkcs8]);
});

test("long text is cut to fit the relay, at a character boundary", () => {
  const hindi = "नमस्ते ".repeat(500);
  const cut = cutBytes(hindi, MAX_TEXT_BYTES);
  assert.ok(new TextEncoder().encode(cut).length <= MAX_TEXT_BYTES);
  assert.ok(cut.endsWith("…"));
  assert.equal(cutBytes("short", MAX_TEXT_BYTES), "short");
});
