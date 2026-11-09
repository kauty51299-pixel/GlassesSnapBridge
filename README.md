# Glasses Snap Bridge — Android prototype

An original native Android app project for Sunny's Galaxy S22 and Ray-Ban Meta Gen 2.

**Delivery status: source project, not a compiled APK.** The Android SDK and Gradle are unavailable in the authoring environment, and their downloads are blocked. The app has not been compiled, linted, installed, or tested with glasses or Snapchat. Do not treat it as a verified working integration yet. An automated APK build workflow and a Mac build script are included.

## Intended flow

Connect Meta AI → preview the glasses camera → choose 30 or 60 seconds → stop automatically → tap Open in Snapchat → review and send yourself.

The video is recorded in this app. It cannot replace Snapchat's live camera. Sharing uses Android ACTION_SEND directed to the installed Snapchat app; it does not use Creative Kit or claim to create a native Snapchat-camera recording. The destination screen and video handling depend on your Snapchat version. If Snapchat rejects the share, use Save to Gallery and import from Snapchat Memories → Camera Roll. Nothing is posted automatically.

## Included

- Meta DAT 1.0.1 initialization, registration, camera permission, session and stream handling.
- Live preview, 30/60-second timers, manual stop, and connection timeout handling.
- H.264 MP4 encoding; optional sound using the **phone microphone**, not the glasses microphone.
- App-private clip storage, explicit Gallery export, Snapchat share, and delete-latest control.
- An existing-MP4 importer to test the sharing path without glasses. Imports are limited to 60 seconds and 250 MB.
- Nearby devices permission; microphone permission requested only for sound recording.
- Recording ends when the app leaves the screen. Keep the phone unlocked and the app open.
- No custom server, login collection, or automatic posting. Meta's SDK and Snapchat are third-party software with their own terms and data handling.

## Option A: build an APK using GitHub

1. Create a **private** GitHub repository for this project.
2. Upload the contents of this folder to the repository root. Include the `.github/workflows/build-apk.yml` file. On a Mac, Command+Shift+Period shows hidden files in Finder. This archive contains no credentials.
3. Open the repository's **Actions** tab. Select **Build Android APK → Run workflow**. A push to main/master also triggers the build.
4. Wait for the build to pass. If it fails, download its build log; an APK has not been produced by a failed build.
5. Download the **Glasses-Snap-Bridge-APK** artifact from that run and unzip it. The installable file is **app-debug.apk**. This is a debug-signed development build.
6. Transfer that APK to your S22, open it, and allow installation from the particular app opening it when Android asks. Install, then turn that source permission off again if desired.

Nothing in this delivery has created a repository or run this workflow on your account.

## Option B: build on your Intel Mac

1. Install Android Studio from https://developer.android.com/studio and complete its initial setup.
2. In Android Studio's SDK Manager, install **Android SDK Platform 36** and **Android SDK Build-Tools 36.0.0**. Accept the Android SDK licenses during setup. The script uses Android Studio's bundled Java when available.
3. Extract this project. Open Terminal, type `cd `, drag this project folder into Terminal, and press Return.
4. Run `sh build-on-mac.sh`. The script downloads Gradle 8.13 from its official distribution site, verifies its SHA-256 checksum, runs the frame-copy tests, and attempts the Android build and lint checks. Internet access is required.
5. If successful, transfer `app/build/outputs/apk/debug/app-debug.apk` to the S22 and install it.

Alternatively, with JDK 17, Gradle 8.13, and Android SDK 36 already configured, run `gradle assembleDebug lintDebug`. There is no bundled Gradle wrapper binary; the Mac script and GitHub workflow supply Gradle.

## First use on the S22

1. Update Meta AI, Snapchat, and the glasses firmware. Pair the glasses in Meta AI first.
2. Enable **Developer Mode** for the glasses in Meta AI. Availability and menus depend on account, region, and current app/firmware. If the account cannot enable it, this prototype's glasses route cannot be tested with the zero-ID configuration. Do not enter passwords or bypass account restrictions.
3. Open **Glasses Snap Bridge → Connect Meta AI**. Grant Nearby devices. Complete the Meta AI registration/approval flow; tap Connect again if the first tap only handled permissions.
4. Tap **Preview glasses**, then allow camera access in the Meta AI permission prompt.
5. Make sure the preview has correct colours and orientation. Choose sound from the phone microphone or untick the sound option.
6. Tap **Record 30 s** or **Record 60 s**. Keep this app visible. The timer stops the recording, or tap **Stop & save** early.
7. Tap **Open in Snapchat**. Review the imported video, choose recipients or Story, and send in Snapchat. If needed, use the Gallery fallback described above.

## Important prototype limits

- **Physical-device testing is still required.** SDK/account/region compatibility, registration callbacks, Samsung encoder behavior, image layout, and Snapchat handoff have not been verified on Sunny's hardware.
- Camera frames are interpreted as tightly packed decoded I420. Byte length and dimensions are checked, but length cannot prove chroma order. Incorrect colours require a frame-layout adjustment based on actual firmware. The phone camera is never substituted for the glasses camera.
- Recording requests HIGH quality at 24 fps. Delivered quality and frame rate depend on the glasses and connection. Encoding uses arrival timestamps, drops frames under pressure, and stops on resolution changes. The timer is approximate; encoding or scheduling can make final container duration differ slightly from 30/60 seconds.
- Phone sound is captured separately and then merged; exact lip-sync is not guaranteed. If microphone access or merging fails, the app preserves a silent video and shows a message. Glasses-microphone support is not implemented.
- No background/locked-screen recording, live Snapchat filters on the glasses feed, automatic sending, root changes, or Snapchat modifications.
- Clips accumulate in app-private storage until deleted or the app is uninstalled. The app exposes the latest clip. Gallery copies are separate and remain when the private clip is deleted. There is no bulk clip browser in this version.
- A debug build is for local development. Production distribution requires proper app credentials, release signing, device testing, and any platform requirements.

## Validation performed here

- Nine Java checks passed for padded Y planes, overlapping/interleaved chroma planes, buffer offsets, I420-to-NV21 conversion, and invalid/truncated input rejection.
- All three XML files parsed successfully.
- Android compilation, Android lint, recording duration/media playback, audio sync, registration and permission flows, and Snapchat behavior remain **unverified**.

## Device acceptance checks after a successful build

1. Import an existing MP4 under 60 seconds. Verify Open in Snapchat and Gallery export.
2. Decline Nearby devices and camera access separately; verify a clear error and successful retry.
3. Connect the real glasses and check preview colours and orientation before recording.
4. Record 30 and 60 seconds, play each clip, and inspect duration and optional phone sound.
5. Stop early, remove/fold the glasses, disconnect Bluetooth, and switch apps during recording; verify that available footage saves or an actionable error appears.
6. Share both durations to Snapchat and verify the actual preview before sending.
7. Test deletion and restoration of the latest clip after restarting the app.

## Official references

- Meta SDK setup, camera and session APIs: https://github.com/meta/meta-wearables-dat-android
- Meta SDK API notes: https://github.com/meta/meta-wearables-dat-android/blob/main/AGENTS.md
- Meta SDK changes: https://github.com/meta/meta-wearables-dat-android/blob/main/CHANGELOG.md
- Meta eligibility and supported devices: https://developers.meta.com/wearables/faq/
- Snapchat Camera Roll fallback: https://help.snapchat.com/hc/en-us/articles/7047503341204-How-do-I-send-photos-or-videos-from-my-Camera-Roll

This project is independent of Meta and Snap. Third-party SDKs are resolved during the build and are governed by their respective licenses and terms.
