pub mod websocket;

use anyhow::Result;
use async_trait::async_trait;

#[async_trait]
pub trait Transport: Send + Sync {
    async fn start(&self) -> Result<()>;
    async fn send(&self, payload: &[u8]) -> Result<()>;
}
