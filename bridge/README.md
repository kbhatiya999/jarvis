# Jarvis bridge

A tiny MCP server that lets the Gemini app (or any MCP client) speak through your Jarvis. Gemini calls
`tell_jarvis`; the bridge encrypts the text the way one Jarvis messages another and posts it to your
Jarvis's inbox on ntfy.sh; Jarvis says it out loud as a message from its contact "Gemini".

It stores nothing: the link Jarvis gives you (`https://<bridge>/mcp/<token>`) carries your Jarvis address
and the bridge's key. Anyone with the link can make your Jarvis speak (always as "Gemini"); make a new
link in Jarvis to turn the old one off.

## Use it
In Jarvis: Settings › Connect Gemini › Copy link. In Gemini: Connectors › + Custom › paste › Next.

## Run and deploy (from the repo root)
| Command | What |
|---|---|
| `mise run tilt:bridge-setup` | Once: Cloudflare token and account into fnox.local.toml (or the "Set up Cloudflare" button on bridge-check in Tilt) |
| `mise run tilt:bridge-dev` | Run locally on http://localhost:8787 |
| `mise run tilt:bridge-test` | Tests |
| `mise run tilt:bridge-tunnel` | Temporary public https address for the local bridge |
| `mise run tilt:bridge-deploy` | Deploy to Cloudflare Workers; saves the address as BRIDGE_URL |
| `mise run tilt:bridge-logs` | Live logs of the deployed bridge |

## Host it elsewhere
`src/bridge.js` uses only web standards; `handle(request)` is the whole server.
- Node 20+: `PORT=8787 node src/node.js` (any VPS, Render, Railway...)
- Container: `docker build -t jarvis-bridge bridge && docker run -p 8787:8787 jarvis-bridge` (Fly.io, Cloud Run...)
- Deno / Bun / edge hosts: `export default { fetch: handle }` from `src/bridge.js`.
Then put your address in Jarvis › Connect Gemini › Bridge address.
