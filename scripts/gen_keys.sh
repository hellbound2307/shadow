#!/bin/bash
# SHADOW Key Generation Script
# Run this ONCE on a secure machine. Output secrets go to GitHub.

set -euo pipefail

OUT_DIR="shadow_keys"
mkdir -p "$OUT_DIR"
cd "$OUT_DIR"

echo "[+] Generating Ed25519 Operator Identity..."
openssl genpkey -algorithm ed25519 -out operator_identity.key
openssl pkey -in operator_identity.key -pubout -out operator_identity.pub
OP_PUB=$(cat operator_identity.pub | base64 -w0)
echo "  OPERATOR_PUBKEY=$OP_PUB"

echo "[+] Generating Module Signing Key (Ed25519)..."
openssl genpkey -algorithm ed25519 -out module_signing.key
openssl pkey -in module_signing.key -pubout -out module_signing.pub
MOD_PUB=$(cat module_signing.pub | base64 -w0)
echo "  MODULE_SIGNING_PUB=$MOD_PUB"

echo "[+] Generating Transport Encryption Key (X25519)..."
openssl genpkey -algorithm x25519 -out transport.key
openssl pkey -in transport.key -pubout -out transport.pub
TRANS_PUB=$(cat transport.pub | base64 -w0)
echo "  TRANSPORT_PUBKEY=$TRANS_PUB"

echo "[+] Generating Android Keystore for IMPLANT..."
keytool -genkeypair \
  -alias shadow_implant \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -keystore implant.jks \
  -storepass "CHANGE_THIS_PASSWORD" \
  -keypass "CHANGE_THIS_PASSWORD" \
  -dname "CN=Shadow Implant, OU=SHADOW, O=SHADOW, L=Unknown, ST=Unknown, C=XX" \
  -deststoretype pkcs12

IMPLANT_B64=$(base64 -w0 implant.jks)
echo "  IMPLANT_KEYSTORE_BASE64=$IMPLANT_B64"
echo "  IMPLANT_KEY_ALIAS=shadow_implant"
echo "  IMPLANT_KEYSTORE_PASS=CHANGE_THIS_PASSWORD"
echo "  IMPLANT_KEY_PASS=CHANGE_THIS_PASSWORD"

echo "[+] Generating Android Keystore for OPERATOR..."
keytool -genkeypair \
  -alias shadow_operator \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -keystore operator.jks \
  -storepass "CHANGE_THIS_PASSWORD" \
  -keypass "CHANGE_THIS_PASSWORD" \
  -dname "CN=Shadow Operator, OU=SHADOW, O=SHADOW, L=Unknown, ST=Unknown, C=XX" \
  -deststoretype pkcs12

OPERATOR_B64=$(base64 -w0 operator.jks)
echo "  OPERATOR_KEYSTORE_BASE64=$OPERATOR_B64"
echo "  OPERATOR_KEY_ALIAS=shadow_operator"
echo "  OPERATOR_KEYSTORE_PASS=CHANGE_THIS_PASSWORD"
echo "  OPERATOR_KEY_PASS=CHANGE_THIS_PASSWORD"

echo ""
echo "=========================================="
echo "  ADD THESE TO GITHUB SECRETS IMMEDIATELY"
echo "=========================================="
cat <<EOF
OPERATOR_PUBKEY=$OP_PUB
MODULE_SIGNING_PUB=$MOD_PUB
TRANSPORT_PUBKEY=$TRANS_PUB
IMPLANT_KEYSTORE_BASE64=$IMPLANT_B64
IMPLANT_KEY_ALIAS=shadow_implant
IMPLANT_KEYSTORE_PASS=CHANGE_THIS_PASSWORD
IMPLANT_KEY_PASS=CHANGE_THIS_PASSWORD
OPERATOR_KEYSTORE_BASE64=$OPERATOR_B64
OPERATOR_KEY_ALIAS=shadow_operator
OPERATOR_KEYSTORE_PASS=CHANGE_THIS_PASSWORD
OPERATOR_KEY_PASS=CHANGE_THIS_PASSWORD
CLOUDFLARE_API_TOKEN=<your_cf_token>
CLOUDFLARE_ACCOUNT_ID=<your_cf_account_id>
EOF

echo ""
echo "[!] Raw keys saved in $OUT_DIR/ - BACK THEM UP OFFLINE NOW"
echo "[!] Change the default passwords above before production use"