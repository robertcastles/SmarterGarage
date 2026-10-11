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

Add `--start-head-unit-server` to start the server through Android Auto's actual
phone settings menu before connecting. Developer mode must already be enabled and
the menu must use English labels. Exact unique controls are required; missing or
ambiguous targets fail rather than guessing. An already running server is not
toggled. The temporary accessibility dump is removed and the phone returns HOME.
This option does not complete onboarding or change developer settings.

Command-free host tests (no emulator required):

```powershell
python -m unittest discover -s tools -p 'test_*.py' -v
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

### Android UI Automator

Device tests use AndroidJUnitRunner and catalog-managed UI Automator dependencies.
The smoke tests require an already configured local fake-server emulator and never
tap a door command or change settings. Resuming the app can still poll and register geofences.

```powershell
.\gradlew.bat :mobile:connectedDebugAndroidTest
```

For an already installed test APK, a strict Auto-host capability probe is:

```powershell
adb -s emulator-5554 shell am instrument -w -e class 'com.lusion.sgauto.UiAutomatorSmokeTest#inspectAndroidAutoAccessibilityOnAllDisplays' -e requireAutoHost true com.lusion.sgauto.test/androidx.test.runner.AndroidJUnitRunner
```

The ordinary two-test suite passed on the connected AVD. The strict capability
probe found a projection display and a Maps root but **zero accessible Android Auto
host roots/clickable nodes**. Its expected failure means this host cannot presently
be driven through UI Automator's accessibility tree; it is not an app regression.
Probe diagnostics use the `SGUiAutomator` log tag and omit text/credentials/location.
Use UI Automator for phone/SystemUI tests and DHU console/OCR for host clicks.

### Counted phone OPEN/CLOSE test

`PhoneCommandTest` is skipped by ordinary instrumentation runs unless explicitly
opted in. Run it through the host orchestrator, which validates local fake routing,
rejects a connected DHU, resets the fixture, monitors the intermediate OPEN state,
and requires exactly two ordered command POSTs plus successful device assertions.
No cached state is manufactured and no direct test API poll substitutes for app polling.

Start the deterministic simulator from `C:\projects\SmarterGarageAPI` per its README,
disconnect DHU, and use an already provisioned fake-only AVD. Build/install both
`:mobile:assembleDebug` and `:mobile:assembleDebugAndroidTest` APKs first, then:

```powershell
python tools\phone_command_test.py --repeat 3
```

The runner force-stops the app before/after each cycle without clearing settings.
It resets the fixture to CLOSED after execution, records only nonsecret summaries
under `build/phone-command-tests`, stops at the first failure, and never retries an
uncertain command automatically. Each successful repetition issues OPEN then CLOSE.
This validates the phone UI/worker path, **not Android Auto notification routing**.

Verified on the restored AVD on October 11, 2026: all three repetitions passed,
each with fresh phone confirmation, observed intermediate OPEN, exactly two ordered
command requests, and final CLOSED. Cleanup left the fixture CLOSED. Assertions
were not weakened to accommodate the earlier framework/network failure.

- Verified native console handshake, video focus, screenshots, local Windows OCR,
  scripted key/tap dispatch, and clean subprocess shutdown.
  The restored AVD's head-unit server was stopped; restarting it through the real
  settings menu restored the command-free DHU connection/screenshot probe.
- App command scenarios are **not yet calibrated/validated** against this host's
  dynamic launcher/notification layout. No repeated manual clicking is required;
  complete stable OCR targets or use instrumentation for routing/lifecycle coverage.
- Instrumentation that sends an existing PendingIntent can test AUTO routing but
  cannot prove the real host's foreground-service-start exemption. Keep at least
  one DHU host-click scenario for end-to-end acceptance.


