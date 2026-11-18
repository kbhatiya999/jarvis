// Cloudflare Workers entry: the default deployment (mise run tilt:bridge-deploy).
import { handle } from "./bridge.js";

export default {
  fetch: (request) => handle(request),
};
