// Jarvis bridge: a tiny MCP server that lets the Gemini app (or any MCP client) speak through your Jarvis.
//
// Gemini calls the tool `tell_jarvis`; the bridge encrypts the text exactly as one Jarvis messages another
// (ECDH P-256 -> HKDF-SHA256 "jarvis-message-v1" -> AES-256-GCM, see shared/.../core/Identity.kt) and posts it
// to your Jarvis's inbox on ntfy.sh. Jarvis sees a message from its contact "Gemini" and says it out loud.
//
// Stateless: everything it needs is in the URL Jarvis gave you, https://<bridge>/mcp/<token>, where the token
// holds your Jarvis address and the bridge's own key. Nothing is stored here and nothing is logged but
// "sent" / "failed". Uses only web standards (fetch, Request/Response, WebCrypto), so it runs on Cloudflare
// Workers, Node 20+, Deno, Bun and edge hosts alike: `handle(request)` is the whole server.

export const VERSION = "1.0.0";
export const RELAY = "https://ntfy.sh";
const PROTOCOLS = ["2025-06-18", "2025-03-26", "2024-11-05"];
const PREFIX = "jarvis:";
const TOPIC = /^jv_[A-Za-z0-9_-]{16,40}$/;
// ntfy turns bodies over 4096 bytes into attachments Jarvis can't read: keep the text well under that.
export const MAX_TEXT_BYTES = 1500;

const TOOLS = [
  {
    name: "tell_jarvis",
    title: "Tell Jarvis",
    description:
      "Send a short message to the user's Jarvis voice assistant on their phone, which says it out loud to them. " +
      "Use it to report the result of a task the user started from Jarvis, or when the user asks you to tell " +
      "their Jarvis something. Write it to be heard: one to three plain sentences, no markdown, no links.",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string", description: "What Jarvis should say, written to be spoken." },
        request_id: { type: "string", description: "The request id Jarvis gave, if there was one." },
      },
      required: ["text"],
    },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true },
  },
];

/** The whole server. [deps.fetch] and [deps.now] are for tests. */
export async function handle(request, deps = {}) {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "");
  if (path === "" || path === "/health") return text(`Jarvis bridge ${VERSION}\n`);
  const m = path.match(/^\/mcp\/([A-Za-z0-9_-]+)$/);
  if (!m) return text("Not found. Jarvis bridge URLs look like /mcp/<token>; get yours in Jarvis › Connect Gemini.\n", 404);
  const pairing = parseToken(m[1]);
  if (!pairing) return text("This Jarvis bridge link isn't valid. Make a new one in Jarvis › Connect Gemini.\n", 404);
  if (request.method === "GET") return text("This MCP server only answers POST.\n", 405, { Allow: "POST" });
  if (request.method === "DELETE") return new Response(null, { status: 204 });
  if (request.method !== "POST") return text("Method not allowed\n", 405, { Allow: "POST" });

  let msg;
  try {
    msg = await request.json();
  } catch {
    return json(rpcError(null, -32700, "Parse error"), 400);
  }
  if (Array.isArray(msg)) return json(rpcError(null, -32600, "Batches are not supported"), 400);
  if (!msg || msg.jsonrpc !== "2.0" || typeof msg.method !== "string") return json(rpcError(msg?.id ?? null, -32600, "Invalid request"), 400);
  // Notifications (no id) get no answer.
  if (msg.id === undefined || msg.id === null) return new Response(null, { status: 202 });
  return json(await rpc(msg, pairing, deps));
}

async function rpc(msg, pairing, deps) {
  const { id, method, params = {} } = msg;
  switch (method) {
    case "initialize": {
      const asked = params.protocolVersion;
      return result(id, {
        protocolVersion: PROTOCOLS.includes(asked) ? asked : PROTOCOLS[0],
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: "jarvis-bridge", title: "Jarvis", version: VERSION },
        instructions:
          "Use tell_jarvis to have the user's Jarvis voice assistant say something out loud on their phone, " +
          "for example the result of a task they started from Jarvis.",
      });
    }
    case "ping":
      return result(id, {});
    case "tools/list":
      return result(id, { tools: TOOLS });
    case "tools/call": {
      if (params.name !== "tell_jarvis") return rpcError(id, -32602, `Unknown tool ${params.name}`);
      const said = String(params.arguments?.text ?? "").trim();
      if (!said) return result(id, toolText("Nothing to say: text is empty.", true));
      const error = await tellJarvis(pairing, said, deps);
      if (error) {
        console.log(`tell_jarvis failed: ${error}`);
        return result(id, toolText(`Couldn't reach Jarvis: ${error}`, true));
      }
      console.log("tell_jarvis sent");
      return result(id, toolText("Delivered. Jarvis will say it to the user."));
    }
    default:
      return rpcError(id, -32601, `Method not found: ${method}`);
  }
}

/** Encrypts [said] for the user's Jarvis and posts it to their inbox. Returns an error message, or null. */
export async function tellJarvis(pairing, said, deps = {}) {
  const doFetch = deps.fetch ?? fetch;
  const now = deps.now ?? Date.now;
  const payload = JSON.stringify({ name: "Gemini", text: cutBytes(said, MAX_TEXT_BYTES), t: now() });
  const envelope = await encrypt(pairing, payload);
  try {
    const resp = await doFetch(`${RELAY}/${pairing.to.topic}`, {
      method: "POST",
      headers: { "Content-Type": "text/plain" },
      body: JSON.stringify(envelope),
    });
    return resp.ok ? null : `relay returned HTTP ${resp.status}`;
  } catch (e) {
    return `relay unreachable (${e?.message ?? e})`;
  }
}

// ---------------------------------------------------------------- token and addresses

/**
 * Token = base64url(JSON {v: 1, to: "<user's Jarvis address>", from: "<bridge's address>", k: "<bridge private
 * key, PKCS#8 DER, base64url>"}). Made by Jarvis (shared/.../core/GeminiBridge.kt). Null if malformed.
 */
export function parseToken(token) {
  try {
    const t = JSON.parse(new TextDecoder().decode(unurl(token)));
    if (t.v !== 1) return null;
    const to = parseAddress(t.to);
    const from = parseAddress(t.from);
    if (!to || !from || typeof t.k !== "string") return null;
    return { to, from, key: unurl(t.k) };
  } catch {
    return null;
  }
}

export function makeToken({ to, from, key }) {
  return url(new TextEncoder().encode(JSON.stringify({ v: 1, to, from, k: url(key) })));
}

/** "jarvis:<topic>.<public key, X.509 DER, base64url>" */
export function parseAddress(text) {
  if (typeof text !== "string" || !text.startsWith(PREFIX)) return null;
  const rest = text.slice(PREFIX.length);
  const dot = rest.indexOf(".");
  if (dot < 0) return null;
  const topic = rest.slice(0, dot);
  if (!TOPIC.test(topic)) return null;
  try {
    return { raw: text, topic, publicDer: unurl(rest.slice(dot + 1)) };
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------- crypto (matches Identity.kt)

export async function encrypt(pairing, plaintext) {
  const key = await messageKey(pairing.key, pairing.to.publicDer, ["encrypt"]);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const aad = new TextEncoder().encode(`${pairing.from.raw}>${pairing.to.topic}`);
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: aad }, key, new TextEncoder().encode(plaintext));
  return { v: 1, from: pairing.from.raw, iv: url(iv), ct: url(new Uint8Array(ct)) };
}

/** Decrypts an envelope sent to [myAddress] (for tests). */
export async function decrypt(myPrivatePkcs8, myAddress, envelope) {
  const me = parseAddress(myAddress);
  const from = parseAddress(envelope.from);
  const key = await messageKey(myPrivatePkcs8, from.publicDer, ["decrypt"]);
  const aad = new TextEncoder().encode(`${from.raw}>${me.topic}`);
  const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: unurl(envelope.iv), additionalData: aad }, key, unurl(envelope.ct));
  return new TextDecoder().decode(plain);
}

async function messageKey(privatePkcs8, peerSpki, usages) {
  const priv = await crypto.subtle.importKey("pkcs8", privatePkcs8, { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
  const peer = await crypto.subtle.importKey("spki", peerSpki, { name: "ECDH", namedCurve: "P-256" }, false, []);
  const shared = await crypto.subtle.deriveBits({ name: "ECDH", public: peer }, priv, 256);
  const hkdf = await crypto.subtle.importKey("raw", shared, "HKDF", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt: new Uint8Array(32), info: new TextEncoder().encode("jarvis-message-v1") },
    hkdf,
    { name: "AES-GCM", length: 256 },
    false,
    usages,
  );
}

// ---------------------------------------------------------------- helpers

export function url(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function unurl(text) {
  const b = atob(text.replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(b, (c) => c.charCodeAt(0));
}

/** At most [max] UTF-8 bytes, cut at a character boundary. */
export function cutBytes(text, max) {
  const enc = new TextEncoder();
  if (enc.encode(text).length <= max) return text;
  let out = "";
  let size = 0;
  for (const ch of text) {
    const n = enc.encode(ch).length;
    if (size + n > max - 3) break;
    out += ch;
    size += n;
  }
  return out + "…";
}

const result = (id, value) => ({ jsonrpc: "2.0", id, result: value });
const rpcError = (id, code, message) => ({ jsonrpc: "2.0", id, error: { code, message } });
const toolText = (t, isError = false) => ({ content: [{ type: "text", text: t }], isError });
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const text = (body, status = 200, headers = {}) => new Response(body, { status, headers: { "Content-Type": "text/plain; charset=utf-8", ...headers } });
