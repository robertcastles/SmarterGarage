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
- `tap_text`: regular expression matching exactly one OCR text line in a fresh screenshot.
- `before_text`: required host-context regexes before any input.
- `navigate_text`: unique destination regex; retries at most three times, only for
  taps in the calibrated left toolbar. Never use this for a garage action.
- `wait`: seconds before capturing/asserting (default 1; maximum 300).
- `expect_text`: list of OCR regex assertions after the step.
- `expect_log`: list of app-log regex assertions after the step, with optional `timeout`.
- `garage_action`: `open` or `close`, requiring explicit counted-cycle opt-in.
- `disconnect`: final counted-cycle assertion step, using real DHU shutdown.

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

### Counted native Auto notification cycle

The runner now supports `--counted-auto-cycle`. This is deliberately separate from
the smoke test and from instrumentation that sends a PendingIntent. It owns the
loopback fixture reset and observes arrivals continuously; it never directly POSTs
a door command. Do not run another fixture owner or DHU concurrently.

Requirements: ready ranchu/goldfish emulator, local HTTPS fixture on port 3000,
empty debug token, already selected fake device, notifications permitted, and a
test geofence state that keeps CLOSED notifications visible. The supplied scenario
and card geometry require the **1280x720** config and English host labels. It does
not fabricate a cached status, location, connection, or geofence transition.

```powershell
python tools\dhu_test.py --start-head-unit-server --counted-auto-cycle --config 'C:\Users\Admin\OneDrive - PMGNET\Desktop\DHU.ini' --scenario tools\scenarios\auto-notification-cycle-1280x720.json --output build\auto-counted-cycle
```

Each garage action is single-shot: routing is rechecked immediately before input;
an exact aligned OPEN/CLOSE pair is scoped within the calibrated card region below
the unique `Smarter Garage` title, excluding other garage-looking cards. A separate
status caption above the buttons must match the expected state. Word-level OCR
handles merged/decorated button lines without tapping the line midpoint or icon.
PowerShell output and Python decoding use UTF-8 explicitly: OEM console encoding
can otherwise turn a Unicode bullet into an invalid JSON control character.
OCR's detected text angle is rotated back around the image center before taps,
so coordinates refer to the physical screenshot rather than its deskewed text.
The runner requires AUTO receiver routing, foreground acceptance, and service logs
from the fresh transition-completion branch, plus a final visible status caption.
Fixture generation, ordered accepted history, exactly two command arrivals,
intermediate OPEN, and final CLOSED are independent server assertions.

After CLOSED, native `quit` must produce the real disconnect event. The runner
requires carrier id 5 and monitor id 2 removal and no remaining Auto foreground
service record. The phone status card is not required to disappear. Failure stops
the cycle without replay. Cleanup stops the app before resetting the fake door to
CLOSED; reports contain only allowlisted counts/state/acceptance evidence.

Host guard tests also run in debug CI; no device or fixture is used by those tests.
Counted Auto acceptance must be judged from a successful run report, not merely
from the presence of this scenario or passing command-free host tests.

Verified on October 11, 2026: one native notification OPEN/CLOSE cycle passed on
the provisioned 1280x720 AVD. Both actions routed through AUTO and received fresh
service confirmation plus distinct host captions; the fixture recorded exactly
OPEN then CLOSE and observed intermediate OPEN. Real DHU disconnect removed the
Auto carrier and stopped the foreground monitor. Results:
`build/auto-counted-cycle-verified/result.json`. Cleanup left the fixture CLOSED.

- Verified native console handshake, video focus, screenshots, local Windows OCR,
  scripted key/tap dispatch, and clean subprocess shutdown.
  The restored AVD's head-unit server was stopped; restarting it through the real
  settings menu restored the command-free DHU connection/screenshot probe.
- The counted notification scenario is calibrated for this English 1280x720 host,
  not other layouts. Navigation may retry bounded toolbar taps; commands never do.
  Disconnect while CLOSE is still moving, reconnect recovery, and repeated Auto
  cycles remain separate acceptance cases; only CLOSED disconnect is claimed here.
- Instrumentation that sends an existing PendingIntent can test AUTO routing but
  cannot prove the real host's foreground-service-start exemption. Keep at least
  one DHU host-click scenario for end-to-end acceptance.
