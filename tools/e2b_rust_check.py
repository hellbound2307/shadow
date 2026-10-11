#!/usr/bin/env python3
"""
Remote Rust compile gate via E2B.

Why this exists:
  The on-device sandbox ships cargo 1.83, which cannot parse the edition2024
  dependency tree, and the phone has ~700MB free disk. Both make a local
  `cargo check` impossible, so every Rust error costs a 10-minute CI cycle.

This spins a clean remote box, installs stable Rust, uploads the workspace,
and runs `cargo check` — real compiler output in ~2-4 min.

Usage:
    python3 e2b_rust_check.py            # check, auto-kill sandbox
    python3 e2b_rust_check.py --keep     # leave sandbox up, print its id

Exit 0 = the Rust core compiles.
"""
import io
import json
import os
import sys
import tarfile
import time

from e2b_code_interpreter import Sandbox

REPO = "/var/minis/workspace/shadow"
SKIP_DIRS = {"target", ".git", "node_modules", ".gradle", "build"}


def _filter(ti):
    parts = ti.name.split("/")
    if any(s in parts for s in SKIP_DIRS):
        return None
    if ti.name.endswith((".so", ".a", ".o", ".rlib")):
        return None
    return ti


def make_tarball():
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        for rel in ["Cargo.toml", "core", "modules"]:
            p = os.path.join(REPO, rel)
            if os.path.exists(p):
                tf.add(p, arcname=rel, filter=_filter)
    return buf.getvalue()


def main():
    if not os.environ.get("E2B_API_KEY"):
        sys.exit("E2B_API_KEY not set")

    tar = make_tarball()
    print(f"[gate] packing {len(tar)/1024:.0f} KB of source")

    sbx = Sandbox(timeout=1800)
    sid = sbx.sandbox_id
    print(f"[gate] sandbox {sid}")

    try:
        # Upload as base64 in chunks (files API is fine, this is just robust
        # across SDK versions).
        import base64

        b64 = base64.b64encode(tar).decode()
        sbx.commands.run("mkdir -p /tmp/w && rm -rf /tmp/w/* && : > /tmp/w/src.b64", timeout=60)

        CHUNK = 100_000
        for i in range(0, len(b64), CHUNK):
            part = b64[i : i + CHUNK]
            sbx.files.write("/tmp/w/chunk.tmp", part)
            sbx.commands.run("cat /tmp/w/chunk.tmp >> /tmp/w/src.b64", timeout=60)
        print("[gate] source pushed")

        r = sbx.commands.run(
            "cd /tmp/w && base64 -d src.b64 | tar xzf - && ls -la /tmp/w | head -10", timeout=180
        )
        print(r.stdout.strip()[:400])

        print("[gate] installing stable rust...")
        r = sbx.commands.run(
            "curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal "
            "--default-toolchain stable --no-modify-path 2>&1 | tail -2",
            timeout=900,
        )
        print("  " + (r.stdout or "").strip()[-160:])

        print("[gate] cargo check --lib")
        # Never let a non-zero exit raise — we want the compiler output.
        r = sbx.commands.run(
            "export PATH=$HOME/.cargo/bin:$PATH && cd /tmp/w && "
            "cargo check --lib > /tmp/w/cargo.log 2>&1; echo EXIT=$?",
            timeout=1800,
        )
        log = sbx.commands.run("tail -80 /tmp/w/cargo.log", timeout=120)
        print("\n===== CARGO =====")
        print(log.stdout)
        code = 0
        for line in (r.stdout or "").splitlines():
            if line.startswith("EXIT="):
                code = int(line.split("=", 1)[1].strip())
        print(f"===== exit={code} =====")
        return code
    finally:
        if "--keep" in sys.argv:
            print(f"[gate] sandbox kept: {sid}")
        else:
            try:
                sbx.kill()
                print("[gate] sandbox killed")
            except Exception as e:
                print(f"[gate] kill failed: {e}")


if __name__ == "__main__":
    sys.exit(main())