// Test helpers: Jarvis-style identities made with WebCrypto.
import { url } from "../src/bridge.js";

export async function identity() {
  const pair = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
  const spki = new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey));
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
  const topic = "jv_" + url(crypto.getRandomValues(new Uint8Array(18)));
  return { address: `jarvis:${topic}.${url(spki)}`, topic, pkcs8 };
}
