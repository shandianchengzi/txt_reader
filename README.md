# TXT Reader 📖

Lightweight native Android TXT novel reader with offline system TTS. No third-party runtime, ad SDK or network permission.

## Features

- Imports local TXT files (UTF-8/UTF-16/GB18030), automatically detects Chinese chapters and restores scroll positions.
- Tap any word to start speech at that character; tap “朗读” to start from the paragraph at the top of the current screen.
- Highlights only the current **sentence**, at most 120 characters, using a subtle beige background.
- A foreground media playback service continues speaking while the screen is locked or another app is foregrounded, and automatically advances chapters. MediaSession exposes lock-screen/media controls; the persistent notification provides **暂停 / 继续 / 停止**.
- Android audio focus pauses playback for calls/other sound apps and resumes after transient interruptions. A conservative watchdog retries a sentence if a system TTS engine stops replying (up to twice, without silently skipping content).
- Phone manufacturers may still aggressively restrict background activity. If speech consistently stops on screen lock, allow unrestricted battery use for TXT Reader in the phone's system settings; force-stop will always stop playback. The app cannot guarantee survival against system force-stop.
- Tracks book progress locally; a partial wake lock prevents ordinary screen-off CPU sleep during speech.
- Offline speech requires an installed system Chinese offline voice; the APK contains no model.

## Book library and reading progress: update vs. reinstall

- **In-place updates:** Android preserves SharedPreferences, library, imported-book file grants, reading chapter/scroll position, voice speed and font size as long as the package ID and signing key stay unchanged.
- **Uninstall/reinstall, new phone, or incompatible signing key:** Android normally removes app data. BEFORE uninstall, choose **数据 → 导出完整备份（含小说）**, save the ZIP outside the app folder, then select **数据 → 从 ZIP 备份恢复** after reinstalling.
- Backups include actual TXT files, book list, chapter, scroll location, last speech location, font size and speed. Imported books are only duplicated to app-private storage when restoring a backup. ZIP files are unencrypted and should be stored securely.
- Limits: 32 MiB per TXT and 512 MiB per backup. Restoring a ZIP replaces the existing shelf.

**Migration warning:** v0.1.0/v0.1.1 were signed with different ephemeral debug keys and did not include backup export. The first upgrade to a fixed-signature build STILL requires uninstalling the old APK, which erases its metadata. Please record old progress manually before replacing that version. From v0.1.2 onward, the same permanent key should always be used.

## Permanent Android signing and GitHub Actions

Keep the permanent PKCS12 key offline and configure these GitHub repository **Actions secrets** in Settings → Secrets and variables → Actions:

- `ANDROID_KEYSTORE_BASE64`: single-line base64 representation of the PKCS12 keystore.
- `ANDROID_SIGNING_PASSWORD`: keystore/key password; the alias is `txtreader`.

Workflow: `.github/workflows/android.yml`. GitHub Actions always compiles Debug APK to validate changes, but only publishes the R8-compressed, permanently signed Release APK when both secrets exist. It NEVER publishes unsigned or ephemeral-debug-signed Release builds. Future APKs must retain the same keystore, `applicationId`, and increment `versionCode`.

Back up the key and password yourself. Losing the key makes future seamless upgrades impossible.

## Development

JDK 17, Android Platform 35, Gradle 8.9:

```bash
gradle :app:assembleDebug
```

All runtime functionality uses the Android framework; no third-party Android dependencies.
