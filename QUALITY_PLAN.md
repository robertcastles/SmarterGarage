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

- Plan recorded; implementation phases pending.
