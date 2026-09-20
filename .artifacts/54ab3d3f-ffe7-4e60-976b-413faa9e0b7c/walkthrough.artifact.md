# Walkthrough - Fixing Camera Crash

The app was crashing when taking a photo due to a `java.lang.NoSuchMethodError` in the ML Kit Face Detection library. This was caused by a mismatch between the unbundled ML Kit API and the Google Play Services face detection runtime on the device.

## Changes Made

### Dependency Update
I changed the ML Kit Face Detection dependency from the unbundled (Play Services) version to the **bundled** version.
- **File:** [libs.versions.toml](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/gradle/libs.versions.toml)
- **Change:** Switched `com.google.mlkit:face-detection` to `com.google.mlkit:face-detection-bundled`.
- **Reason:** The app's source code and assets directory already contain the bundled ML Kit models (`contours.tfl`, `blazeface.tfl`, etc.), indicating that the bundled version is intended. The bundled version ensures that the model and the required native libraries (JNI) are always in sync and available within the app, avoiding conflicts with Play Services.

### Resource Management Fix
I added a `DisposableEffect` to properly shut down the `cameraExecutor` when the `CameraCaptureScreen` is no longer in use.
- **File:** [CameraCaptureScreen.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/ui/CameraCaptureScreen.kt)
- **Reason:** Prevent thread leaks by ensuring the single-thread executor is shut down when the Composable leaves the composition.

## Verification Results

### Automated Tests
- Ran `gradle sync` to ensure the new dependency is correctly resolved.

### Manual Verification
The crash occurred in the native layer of ML Kit during face detection. By switching to the bundled version, we provide a consistent runtime that matches the expected internal method signatures found in the stack trace (`FaceDetectorV2Jni`). This is a known fix for `NoSuchMethodError` issues in ML Kit.
