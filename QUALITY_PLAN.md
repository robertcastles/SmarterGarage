# Quality improvement plan

Each phase is a separate, reviewable commit. Preserve explicit user-triggered
OPEN/CLOSE behavior, home-test TLS bypass, and owner-managed Play distribution.
Never retry a door command automatically after uncertain delivery.

## Phases

1. **Networking and broadcast boundaries**: isolate diagnostic transport; never
   send the production PAT to pings or test servers; add an explicit test token;
   retain TLS bypass only for explicitly configured test endpoints. Reject malformed
   status messages and make internal receivers non-exported.
2. **Tests and debug CI**: JVM policy tests and debug compilation, tests, and lint
   in CI. No signing credentials or release artifacts. Use temporary developer
   Firebase client configuration for CI, not production credentials.
3. **Location ownership**: cancellable recurring subscriptions paired with activity
   lifecycle; bounded one-shot home capture; no late home writes after cancellation.
4. **Command ownership**: one serialized execution boundary across all callers;
   await worker completion; eliminate detached command execution. Distinguish API
   acceptance, rejection, and uncertain delivery. Test opposing commands and cancellation.
5. **Typed status outcomes**: distinguish fresh success, failure, and skipped polls;
   reserve quota at dispatch; update polling callers to use freshness, not cached state.
6. **Responsibility boundaries**: incrementally extract transport policy, command
   coordinator, state repository, and location policy into testable units. Keep the
   single Gradle module unless a concrete independent-module benefit emerges.
7. **Privacy and build hygiene**: explicit platform backup exclusions; collection
   disclosure/defaults; validated settings imports; dependency/configuration cleanup.
   Review unused resources before deleting them and document intentional exceptions.

## Validation

- Per implementation commit: `:mobile:compileDebugKotlin`, applicable JVM tests.
- At milestones: `:mobile:testDebugUnitTest :mobile:lintDebug`.
- Device checks still required: home test server (including self-signed TLS), normal
  SmartThings access, rapid opposite commands, permission revocation, home capture,
  activity recreation, boot/update, process death, car disconnect, and battery restrictions.
- Do not change `versionCode`, generate release bundles, or upload artifacts.

## Progress

- `8998871`: plan recorded.
- `0a8e19f`: endpoint credential policy, separate credential-free diagnostic client,
  home-test TLS bypass retained, non-exported/validated UI status messages.
- `814d03b`: eight network-policy JVM tests, debug-only CI, debug-only Compose tooling.
- `0a7190a`: cancellable activity location subscription, bounded fresh home acquisition,
  stop/replacement cancellation, normalized update-rate preference.
- `00569f5`: shared command serialization, cancellable queue admission, awaited
  command workers, no replay on later worker attempts, three concurrency tests.
- `b34f42a`: typed status fetch outcomes, dispatch-time quota reservation, fresh-only
  confirmation in service/pulse/status workers and car-connect probe.
- Privacy milestone: platform backup exclusions, default telemetry collection disabled,
  home-test/privacy documentation; redirects disabled to preserve endpoint boundaries.
- Auto milestone: watch-until-closed cannot finish on fresh OPEN, cleanup retains the
  completing observation, screen lifecycle observer registered, delayed refresh cancellable,
  and screen broadcasts package-scoped. Three additional JVM policy tests pass (14 total).
- DHU automation: native console runner and Windows OCR; handshake/screenshot smoke
  test passes on the running AVD. See tools/README.md. Host open/close notification
  scenarios still need stable calibrated targets and server-side command-count assertions.
- UI Automator: AndroidJUnitRunner and androidTest-only dependencies added. Two
  command-free emulator tests pass (phone resume and all-display inspection).
  Strict host capability probe finds projection but zero accessible Auto host controls;
  use native DHU automation for host commands on this setup, not default-display selectors.
- Deterministic simulator fixture added in the separate repository at
  `C:\projects\SmarterGarageAPI` (opt-in `SMARTER_GARAGE_TEST_MODE=1`).
  HTTPS API on 3000, loopback-only controls on 3001, generation-safe reset/cancellation,
  FIFO response scripts, request counts, accepted command history, and header-presence
  diagnostics. Six black-box fixture tests pass. AVD status/ping traffic reaches this
  instance with no Authorization headers and no commands during the polling check.
  Simulator changes remain uncommitted because that repository has owner edits already
  in progress; startup/API/test instructions are in its README. Next: wire these controls
  into asserted device/DHU scenarios (no end-to-end Auto cycle claimed yet).

## Remaining work / limitations

- CI is configured but has not yet run on GitHub; local debug builds/tests/lint pass.
- Command serialization is process-local, not exactly-once durable delivery. WorkManager
  scheduling latency and foreground-service handoff still need phone/Auto device testing.
  Boolean command results still need accepted/rejected/uncertain UI outcomes.
- Foreground-service command admission is still coupled to replacing the monitor job;
  complete separation requires a service-owned command queue with acknowledged handoff.
- Freshness migration is incremental: background-runnable timeout/cleanup and notification
  publication still use cache; add single-flight/generation-aware status publication and
   audit remaining publication paths. Foreground-service final-card confirmation now
   retains its completing observation.
- Extract a state repository/ViewModel and immutable location acceptance policy; current
  extractions are NetworkPolicy, CommandExecutor, and StatusFetchResult, not a full rewrite.
- Add transport integration tests, malformed status/import tests, and location lifecycle tests.
- Manual JSON import still needs strict schema/key/type/range validation before replacement.
  Export remains plaintext by design. SDK removal/telemetry opt-in needs a separate decision.
- Audit legacy location-service cleanup, crash-guard recovery, host-validator fallback,
  Kotlin version/configuration flags, and unused resources before making broad deletions.





