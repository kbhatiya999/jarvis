// Node 20+ entry: local development (mise run tilt:bridge-dev) and self-hosting on any server or container.
//   PORT=8787 node src/node.js
import { createServer } from "node:http";
import { handle } from "./bridge.js";

const port = Number(process.env.PORT || process.env.BRIDGE_PORT || 8787);

createServer(async (req, res) => {
  try {
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const body = chunks.length ? Buffer.concat(chunks) : undefined;
    const host = req.headers.host || `localhost:${port}`;
    const request = new Request(`http://${host}${req.url}`, {
      method: req.method,
      headers: req.headers,
      body: req.method === "GET" || req.method === "HEAD" ? undefined : body,
    });
    const response = await handle(request);
    res.writeHead(response.status, Object.fromEntries(response.headers));
    res.end(Buffer.from(await response.arrayBuffer()));
  } catch (e) {
    console.log(`error: ${e?.message ?? e}`);
    res.writeHead(500, { "Content-Type": "text/plain" });
    res.end("Internal error\n");
  }
}).listen(port, () => console.log(`Jarvis bridge on http://localhost:${port}`));
