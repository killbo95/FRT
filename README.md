# FRT — Facial Recognition Technology

FRT is an Android research prototype. Version 1.0 provides visible, foreground camera preview and on-device face detection/counting using CameraX and Google ML Kit.

## Current capabilities
- Android camera permission prompt
- Live camera preview while the app is open
- On-device face detection and count
- No face images or biometric templates are stored
- No identity lookup or internet face search

## Build an APK online with GitHub Actions
1. Create a new GitHub repository named `FRT`.
2. Upload the contents of this folder to the repository root (not the containing FRT folder).
3. Open the repository's **Actions** tab.
4. Select **Build FRT APK** and choose **Run workflow**, or push to `main`.
5. Open the completed workflow run.
6. Under **Artifacts**, download `FRT-debug-apk`.
7. Extract the ZIP and install `app-debug.apk` on your Android phone.

The APK is a debug build for testing. For wider distribution, create a properly signed release build and protect its signing key.

## Requirements
- Android 6.0 (API 23) or newer
- Internet connection for the first ML Kit model download
- Camera permission

## Privacy and safety
Use the camera openly and only in settings where recording or analysis is permitted. This prototype does not identify people, automatically save unknown faces, or search the web for identities. For experiments involving participants, obtain informed consent and provide a clear deletion process before collecting data.
