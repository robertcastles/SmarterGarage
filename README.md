# Smarter Garage

https://play.google.com/store/apps/details?id=com.lusion.sgauto

An Android application for SmartThings-connected garage doors, with Android Auto integration and location-based automation. I made this app for myself, as I wanted a much better experience for controlling my garage through Android Auto and through my phone compared to the OOB SmartThings options.

I wanted several key features for usability as I perceived met my needs.

1. Only show in Android Auto when close to home, so geofence setup was key.
2. As easy to access as possible from AA, which led to a mountain of notification hacking to get through AA limitations. I think I have the only 'animated' AA notification in existence. Although, new tightened restrictions have forced me away from leveraging this feature, but I have to say, it's actually worked out to be for the good.
3. Persistently present garage controls in notification on phone when at home, but only show notification when away if the garage is open.
4. A thumb smash friendly phone UI for simplicity as an alternate interface, with cool animations because it stinks without them. Really.
5. I only have one automated garage door opener, so no cool "multi garage" features here. In my case, it's a GoControl/Linear GD00Z-4 Z-Wave and I'm stunned it has lasted for so many years and that the door sensor battery lasts so long. Good device.
6. Automatically start when phone boots. Keep running if updated. Never make me go find this thing or the controls then when the point is to quickly get to it. 
7. Try, as best as possible to keep the current garage status without heavy polling and lacking any push features. I don't want this app to track anything sensitive, and I don't want users to have to configure SmartThings for push notifications when they are hard to configure, and GCM and whatever other hurdles to make that work. I think I kind of nailed it.
8. Be as small as possible without writing it in C.
9. Never, ever, ever, show up on the battery usage list.
10. Oh, and because I was going to put this on github, never have to send anything or store anything to any cloud service, or anything that remotely looks like the app would be collecting personal information, especially as it has your geofence and your smartthings key stored.

I purposely did not make the following.

1. A widget. Too easy to accidentally trigger.
2. Voice controls. Maybe I can figure out Gemini if that has settled down.
3. Any "automatic open door when arriving, close door when leaving". Though it was originally tempting, you'll soon realize why it's a terrible idea.

This app started as Hand written cobbled Java and XML layouts from the brutally bad examples I could find in the dark days of AA development. I converted it to Kotlin/Compose and now I maintain it with AI, of course. 

Oh, it's been a terrible journey getting through Google Play Store policy to get my app published. They were(are?) ridiculously strict about AA apps, or at least they were in the beginning. Feel free to try!

I should probably publish some material on how to get the smarthings API key. I'll try to add that later.

## Things I want to do

Try to port this to iOS since I'm the only android user in the family.

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

