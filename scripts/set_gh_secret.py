#!/usr/bin/env python3
"""Set a GitHub repository secret via the REST API."""
import sys, os, base64, json
import requests
from nacl import encoding, public

REPO = "hellbound2307/shadow"
TOKEN = os.environ.get("GITHUB_TOKEN")

def set_secret(secret_name: str, secret_value: str):
    # Get repo public key
    headers = {
        "Authorization": f"Bearer {TOKEN}",
        "Accept": "application/vnd.github+json",
    }
    r = requests.get(f"https://api.github.com/repos/{REPO}/actions/secrets/public-key", headers=headers)
    r.raise_for_status()
    key_data = r.json()
    
    # Encrypt the secret with the repo's public key
    public_key = public.PublicKey(key_data["key"].encode("utf-8"), encoding.Base64Encoder())
    encrypted = public.SealedBox(public_key).encrypt(secret_value.encode("utf-8"))
    encrypted_b64 = base64.b64encode(encrypted).decode("utf-8")
    
    # Put the secret
    payload = {
        "encrypted_value": encrypted_b64,
        "key_id": key_data["key_id"],
    }
    r = requests.put(
        f"https://api.github.com/repos/{REPO}/actions/secrets/{secret_name}",
        headers=headers,
        json=payload,
    )
    r.raise_for_status()
    print(f"  {secret_name}: {'OK' if r.status_code in (201, 204) else 'FAILED ' + str(r.status_code)}")

if __name__ == "__main__":
    name = sys.argv[1]
    value = sys.argv[2]
    set_secret(name, value)
