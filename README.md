# Smarter Garage

An Android application for SmartThings-connected garage doors, with Android Auto integration and location-based automation.

## Local development

1. Open this folder in Android Studio.
2. Install JDK 21 and Android SDK platform 37. Configure your local SDK through Android Studio or an ignored `local.properties` file.
3. Create your own Firebase project and register an Android application with application ID `com.lusion.sgauto`.
4. Download that application's `google-services.json` to `mobile/google-services.json`. The Google Services plugin requires this module configuration for debug builds. Firebase Analytics and Crashlytics are included; use a developer-owned Firebase project, not someone else's credentials or project configuration.
5. Build a debug variant on Windows:

   ```powershell
   .\gradlew.bat :mobile:assembleDebug
   ```

The real Firebase configuration is intentionally excluded from Git. A placeholder JSON file is not a working Firebase setup. Do not supply or commit a service-account private key for this Android client configuration.

Configure your own SmartThings personal access token and device in the application's settings. Never put a token into source code, example requests, screenshots, or issue reports. Do not send a real token to a mock or alternate debug server.

## Signing

Debug builds do not require the owner's Play signing key or release credentials.

For owner-managed release signing, copy `keystore.properties.template` to the ignored root-level `keystore.properties` and provide your own settings. `storeFile` may be an absolute path or a path relative to the project root; use forward slashes or properly escaped backslashes in Java properties files. Release signing is configured only when all four properties are present and nonblank.

Keep the keystore outside the repository. Never commit keystores, passwords, private keys, or developer configuration. The repository owner generates the final signed Play AAB manually when ready; ordinary local validation uses debug variants only. Do not bump `versionCode` without an explicit request for a new Play-deliverable version.

## Sharing and diagnostics

IDE state, local configuration, diagnostic logs, settings exports, and generated applications are excluded from Git. These files can contain credentials, device identifiers, locations, or private-network details.

Before sharing a log, screenshot, or exported settings file, review and redact it. Settings exports can include the SmartThings token and home location. Ignore rules are not a substitute for reviewing the files staged for a commit.

