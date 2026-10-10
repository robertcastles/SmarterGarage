# Smarter Garage AI Instructions

## Local build and Play distribution

- For local validation, build **debug** variants only (for example, `:mobile:compileDebugKotlin` or `:mobile:assembleDebug`).
- Do **not** generate, inspect, upload, or request a release AAB as part of normal local validation.
- The repository owner generates the final signed AAB manually, only after they decide the change is ready for Play distribution.
- Bump `mobile/build.gradle.kts` `versionCode` only when the repository owner requests a new Play-deliverable version.

