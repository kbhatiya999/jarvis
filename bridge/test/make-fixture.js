// Writes shared/src/androidUnitTest/resources/bridge-fixture.json: a message the bridge encrypted, plus the
// keys, for WireFormatTest to check that Kotlin's Identity decrypts it and reads the bridge's key format.
// Run again only if the format changes:  node test/make-fixture.js
import { writeFileSync, mkdirSync } from "node:fs";
import { encrypt, parseToken, makeToken, url } from "../src/bridge.js";
import { identity } from "./keys.js";

const user = await identity();
const bridge = await identity();
const token = makeToken({ to: user.address, from: bridge.address, key: bridge.pkcs8 });
const payload = JSON.stringify({ name: "Gemini", text: "Bridge test, Sir.", t: 1700000000000 });
const envelope = await encrypt(parseToken(token), payload);
const dir = new URL("../../shared/src/androidUnitTest/resources/", import.meta.url);
mkdirSync(dir, { recursive: true });
writeFileSync(new URL("bridge-fixture.json", dir), JSON.stringify({
  userAddress: user.address, userTopic: user.topic, userPrivatePkcs8: url(user.pkcs8),
  bridgeAddress: bridge.address, bridgePrivatePkcs8: url(bridge.pkcs8), token, envelope, payload,
}, null, 2) + "\n");
console.log("wrote bridge-fixture.json");
