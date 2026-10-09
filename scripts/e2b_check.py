#!/usr/bin/env python3
"""
SHADOW Pre-CI Compile Check via E2B
Boots an E2B VM, clones the repo, runs cargo check + tsc, returns results.
Usage: python3 e2b_check.py [--skip-worker]
"""
import os, sys, json, time
from e2b_code_interpreter import Sandbox

REPO_URL = "https://github.com/hellbound2307/shadow.git"
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")

CARGO_CHECK_CMD = """
cd /home/user/shadow

# Install Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable 2>/dev/null
source $HOME/.cargo/env

# Add required components
rustup component add rustfmt clippy 2>/dev/null

echo "=== CARGO CHECK (workspace) ==="
cargo check --workspace 2>&1
echo "EXIT_CODE=$?"
"""

WORKER_CHECK_CMD = """
cd /home/user/shadow/worker

# Install Node
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo bash - 2>/dev/null
sudo apt-get install -y nodejs 2>/dev/null

npm install --no-audit --no-fund 2>&1 | tail -3
echo "=== TSC TYPECHECK ==="
npx tsc --noEmit 2>&1
echo "EXIT_CODE=$?"
"""

def run_check():
    print("Booting E2B sandbox...")
    sbx = Sandbox()
    
    try:
        # Clone repo
        print("Cloning repo...")
        if GITHUB_TOKEN:
            clone_cmd = f"git clone https://{GITHUB_TOKEN}@github.com/hellbound2307/shadow.git /home/user/shadow 2>&1"
        else:
            clone_cmd = f"git clone {REPO_URL} /home/user/shadow 2>&1"
        
        result = sbx.commands.run(clone_cmd)
        if "fatal" in (result.stdout or "") or "error" in (result.stderr or ""):
            print(f"Clone failed: {result.stderr}")
            return
        
        print(f"Repo cloned. HEAD: {sbx.commands.run('cd /home/user/shadow && git log --oneline -1').stdout}")
        
        # Run cargo check
        print("\n" + "="*60)
        print("RUNNING: cargo check --workspace")
        print("="*60)
        cargo_result = sbx.commands.run(CARGO_CHECK_CMD, timeout=300)
        
        output = cargo_result.stdout or ""
        print(output)
        
        if "EXIT_CODE=0" in output:
            print("\n✅ CARGO CHECK: PASSED")
        else:
            # Find errors
            error_lines = [l for l in output.split('\n') if 'error' in l.lower()]
            print(f"\n❌ CARGO CHECK: FAILED ({len(error_lines)} errors)")
            for e in error_lines[:10]:
                print(f"  {e}")
        
        # Run worker check unless skipped
        if "--skip-worker" not in sys.argv:
            print("\n" + "="*60)
            print("RUNNING: Worker TypeScript check")
            print("="*60)
            worker_result = sbx.commands.run(WORKER_CHECK_CMD, timeout=300)
            worker_output = worker_result.stdout or ""
            print(worker_output)
            
            if "EXIT_CODE=0" in worker_output:
                print("\n✅ WORKER TSC: PASSED")
            else:
                print("\n❌ WORKER TSC: FAILED")
        
        # Summary
        print("\n" + "="*60)
        cargo_ok = "EXIT_CODE=0" in output
        worker_ok = True if "--skip-worker" in sys.argv else "EXIT_CODE=0" in worker_output
        
        if cargo_ok and worker_ok:
            print("✅ ALL CHECKS PASSED — Safe to push to GitHub")
        else:
            print("❌ CHECKS FAILED — Fix errors before pushing to GitHub")
        print("="*60)
        
    finally:
        print("\nKilling sandbox...")
        sbx.kill()

if __name__ == "__main__":
    run_check()
