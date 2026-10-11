# DHU automation (Windows)

`dhu_test.py` owns the DHU subprocess and drives its native console: taps, keys,
screenshots, and clean disconnection. It does not use desktop mouse automation.
Windows OCR supports `tap_text` and `expect_text` scenario steps without pip packages.

Prerequisites: Python 3.10+, Windows PowerShell, a Windows OCR language pack,
SDK platform-tools and DHU, a booted debuggable emulator, Android Auto developer
head-unit server running, and completed Android Auto onboarding. Close any existing
DHU before a run. The app must already use a selected fake device and HTTPS debug
server on `10.0.2.2`, `127.0.0.1`, or `localhost`. The runner refuses other routing.

From the repository root:

```powershell
python tools\dhu_test.py --config 'C:\Users\Admin\OneDrive - PMGNET\Desktop\DHU.ini'
```

This is a **connection/screenshot smoke test**, not an open/close acceptance test.
Add `--scenario tools\scenarios\auto-launcher.json` for a HOME-key navigation probe.
The launcher probe is exploratory: it does not assert that the launcher appeared.

Scenarios are JSON arrays. Each step can specify:

- `command`: DHU console command (`tap x y`, `keycode home`, etc.).
- `tap_text`: regular expression matching exactly one OCR text line in the preceding screenshot.
- `wait`: seconds before capturing/asserting (default 1; maximum 300).
- `expect_text`: list of OCR regex assertions after the step.
- `expect_log`: list of app-log regex assertions after the step, with optional `timeout`.

For example, a calibrated OPEN step should require a visible OPEN control and
`expect_log` events showing AUTO receiver routing, foreground command acceptance,
and fresh final OPEN. A screen-row command uses the worker path instead, so it must
have different assertions. Do not treat these as equivalent or infer single delivery
from log events: count POSTs at the fake server as well.

Screenshots and a non-secret `result.json` go under ignored `build/dhu-tests`.
Screenshots can contain personal data; do not upload without review. Raw app settings,
tokens, and log buffers are not written to disk. Failed runs return nonzero and
disconnect DHU; reconnection is part of each run.

## Current coverage

- Verified native console handshake, video focus, screenshots, local Windows OCR,
  scripted key/tap dispatch, and clean subprocess shutdown.
- App command scenarios are **not yet calibrated/validated** against this host's
  dynamic launcher/notification layout. No repeated manual clicking is required;
  complete stable OCR targets or use instrumentation for routing/lifecycle coverage.
- Instrumentation that sends an existing PendingIntent can test AUTO routing but
  cannot prove the real host's foreground-service-start exemption. Keep at least
  one DHU host-click scenario for end-to-end acceptance.
