# FRT — Facial Recognition Technology

FRT is an Android research app for visible, on-device face detection and aggregate session tracking.

## Features
- Live camera preview with front/back camera switching
- On-device face detection and live face count using CameraX and Google ML Kit
- Start, pause, resume, and save-and-stop session controls
- Local session history saved on the phone
- Session duration, total face observations, analyzed frames, and peak faces in view
- Export saved session summaries to CSV using Android's file picker
- Clear saved session history from the app
- Camera sessions automatically stop and save when the app leaves the foreground

## Build an APK with GitHub Actions
1. Open the repository's **Actions** tab.
2. Select **Build FRT APK**.
3. Open the newest successful run.
4. Under **Artifacts**, download **FRT-debug-apk**.
5. Extract the ZIP and install `app-debug.apk` on your Android phone.

The workflow also runs automatically whenever changes are pushed to `main`. The APK is a debug build for testing.

## Requirements
- Android 6.0 (API 23) or newer
- Camera permission
- Internet may be needed the first time ML Kit downloads its face-detection model; session history and detection processing are local.

## Privacy
FRT stores aggregate session statistics only. It does not save face photographs, biometric templates, names, or identity matches, and it does not search the internet for identities. Camera access is used only while the app is in the foreground. Use the camera openly and only where analysis is permitted; obtain informed consent for research involving participants.
