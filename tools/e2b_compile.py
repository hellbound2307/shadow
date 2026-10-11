#!/usr/bin/env python3
"""
Remote compile gate via E2B.

Pushes the shadow workspace into a disposable cloud sandbox and runs either
the Rust check or the Android (Kotlin) compile, returning real compiler output.
Use this BEFORE pushing to main so CI cycles are not wasted.

Usage:
  python3 tools/e2b_compile.py rust      [--keep]
  python3 tools/e2b_compile.py android   [--keep]

Notes:
  - cargo resolves the WHOLE workspace, so modules/* must compile too.
  - Android needs the SDK; we use the prebuilt E2B android template if
    available, otherwise we fall back to reporting that the check is skipped.
"""
import os
import sys
import time

from e2b_code_interpreter import Sandbox

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORK = "/tmp/w"
CHUNK = 48 * 1024


def sh(sbx, cmd, timeout=600, quiet=False):
    r = sbx.commands.run(cmd, timeout=timeout)
    if not quiet:
        out = (r.stdout or "").strip()
        if out:
            print(out)
    return r


def push_workspace(sbx, include):
    """base64-tar the given paths and stream them in chunk by chunk."""
    import subprocess
    import tarfile
    import io
    import base64

    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        for rel in include:
            p = os.path.join(REPO, rel)
            if os.path.exists(p):
                tf.add(p, arcname=rel)
    data = base64.b64encode(buf.getvalue()).decode()
    print(f"  source payload: {len(buf.getvalue()) // 1024} KB")

    sh(sbx, f"mkdir -p {WORK} && rm -rf {WORK}/* && : > {WORK}/src.b64", quiet=True)
    for i in range(0, len(data), CHUNK):
        part = data[i:i + CHUNK]
        sbx.files.write(f"{WORK}/chunk.tmp", part)
        sbx.commands.run(f"cat {WORK}/chunk.tmp >> {WORK}/src.b64", timeout=120)
        print(f"  pushed {min(i + CHUNK, len(data)) * 100 // len(data)}%", end="\r")
    print()
    sh(sbx, f"cd {WORK} && base64 -d src.b64 | tar xzf - && rm -f src.b64", timeout=300)


def extract_exit(r):
    for line in (r.stdout or "").splitlines():
        if line.startswith("EXIT="):
            return int(line.split("=", 1)[1].strip())
    return -1


def check_rust(sbx):
    print("[gate] cargo check --lib (whole workspace)")
    sh(sbx, "curl -sSf https://sh.rustup.rs -o /tmp/r.sh && "
            "sh /tmp/r.sh -y --profile minimal --default-toolchain stable "
            ">/tmp/rustup.log 2>&1 || true", timeout=900, quiet=True)
    r = sbx.commands.run(
        "export PATH=$HOME/.cargo/bin:$PATH && cd /tmp/w && "
        "cargo check --lib > /tmp/w/cargo.log 2>&1; echo EXIT=$?",
        timeout=1800,
    )
    sh(sbx, "tail -80 /tmp/w/cargo.log")
    return extract_exit(r)


def check_android(sbx):
    print("[gate] gradle compileReleaseKotlin")
    # Kotlin needs the Android SDK; E2B's base image does not ship it.
    probe = sbx.commands.run(
        "ls -d /opt/android-sdk /usr/lib/android-sdk $ANDROID_HOME 2>/dev/null | head -1",
        timeout=60,
    )
    sdk = (probe.stdout or "").strip()
    if not sdk:
        print("  SKIP: no Android SDK in this sandbox image.")
        print("  (Kotlin compile still requires the CI round trip.)")
        return -2
    r = sbx.commands.run(
        f"export ANDROID_HOME={sdk} PATH=$PATH:{sdk}/cmdline-tools/latest/bin && "
        "cd /tmp/w/operator/android && chmod +x gradlew && "
        "./gradlew compileReleaseKotlin --no-daemon > /tmp/w/gradle.log 2>&1; echo EXIT=$?",
        timeout=2400,
    )
    sh(sbx, "grep -E '^e: |error:|FAILURE' /tmp/w/gradle.log | head -40")
    return extract_exit(r)


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ("rust", "android", "both"):
        print(__doc__)
        return 2
    mode = sys.argv[1]
    keep = "--keep" in sys.argv

    print("creating E2B sandbox...")
    sbx = Sandbox(timeout=3600)
    print(f"  sandbox {sbx.sandbox_id}")
    code = 0
    try:
        if mode in ("rust", "both"):
            print("uploading workspace (rust)...")
            push_workspace(sbx, ["Cargo.toml", "core", "modules"])
            code = check_rust(sbx)
        if mode in ("android", "both"):
            print("uploading workspace (android)...")
            push_workspace(sbx, ["operator", "core"])
            rc = check_android(sbx)
            code = rc if mode == "android" else max(code, rc)
    finally:
        if keep:
            print(f"sandbox kept: {sbx.sandbox_id}")
        else:
            sbx.kill()
            print("sandbox killed")
    print(f"\n=== RESULT: exit={code} ===")
    return 0 if code == 0 else 1


if __name__ == "__main__":
    sys.exit(main())