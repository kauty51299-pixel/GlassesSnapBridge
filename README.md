# Glasses Snap Bridge — Android development app

Records a 30- or 60-second MP4 from Meta glasses and hands the completed clip to Snapchat. This is an independent developer app, not a Meta or Snap product.

## Version 0.6

- Original yellow glasses logo in the launcher and app header.
- Camera registration, readiness checks, error reporting and recovery controls.
- Manual recording plus optional three/four-event triggering from the side touchpad or top capture button.
- Creative Kit Lite Share to Preview handoff for sending the video through Snapchat's Snap editor. Generic chat-attachment sharing is no longer the default or a silent fallback.

## Set up Snapchat Snap-editor sharing

Snapchat requires a registered and appropriately authorized Creative Kit integration. This repository deliberately contains no invented Client ID or borrowed credentials.

1. Register the Android app at https://kit.snapchat.com/ and enable Creative Kit as applicable to the current portal.
2. In this app, choose **Set up Snap sharing → Copy Android details**. Use the package and signing certificate details when configuring the integration.
3. Enter the integration's **public Client ID** in the setup field and save it. Do not enter a client secret, password or token.
4. Ensure the Snapchat account/device is allowed to test the integration, and complete any Snap approval required for its release mode.
5. Record/import a clip, tap **Open Snap editor**, then review and choose recipients in Snapchat yourself.

A syntactically valid Client ID is not proof of Snap approval. Snapchat validates the integration and controls import presentation, attribution and delivery. This app cannot guarantee that imported content is indistinguishable from a video recorded with Snapchat's own camera. If setup is missing or opening fails, the clip stays saved locally; the app does not send it as a chat attachment.

The handoff uses Snapchat's documented `snapchat://creativekit/preview` route, a video content URI with temporary read permission, Client ID and a private result callback. Receiving the callback does not prove a Snap was sent. Official reference: https://github.com/Snapchat/creative-kit

## Three/four-tap controls — experimental

1. Connect the glasses and wait for camera preview.
2. Select **3 taps** or **4 taps**, **Side touchpad** or **Top button**, and 30/60 seconds.
3. Enable **Arm glasses taps** and wait for the controls-ready status.
4. Use a quick sequence, with no more than 650 ms between received events.
5. The clip is recorded in this app, then handed to the Snap editor when configured. You still select recipients and send in Snapchat.

The app counts events actually delivered by Meta Inputs. CAPTOUCH Select is a semantic event: firmware can combine physical gestures, consume them for system controls or pause the camera instead. Therefore three/four physical temple taps are not guaranteed to become three/four events on all firmware. The top button is an alternative using short capture events. This is not a workaround for Meta capability denial. Inputs approval/enablement may be required in Wearables Developer Center; it is not an Android runtime permission.

Keep the phone unlocked with this app visible. Armed mode keeps the screen awake. Leaving the screen ends the session and resets the pending gesture. There is no background/locked-screen camera service and no live replacement for Snapchat's own camera. No posts or messages are sent automatically.

## Installation and updates

Debug builds may have a different signing certificate on each clean hosted build. Export any recordings to Gallery before uninstalling an earlier build when Android cannot update it. A new signing certificate may also need updating in the Snap integration's Android configuration. A stable production signing identity is not configured in this repository.

Meta AI Developer Mode and supported glasses/firmware are required for the zero-ID Meta developer configuration. Meta and Snap SDK/account behavior requires physical-device testing.

## Build

Run `sh build-on-mac.sh` with Android Studio/SDK 36 installed, or use the repository's **Build Android APK** GitHub Actions workflow. With Gradle 8.13, JDK 17 and the Android SDK configured: `gradle assembleDebug lintDebug`. The APK output is `app/build/outputs/apk/debug/app-debug.apk`.

Run pure Java regressions with `sh tests/run.sh`. They cover YUV plane layout, preservation of secondary connection errors, and tap sequence thresholds, expiry, source separation and cancellation. They do not validate Meta firmware events or Snapchat acceptance. The GitHub build compiles the Android integration and runs lint.
