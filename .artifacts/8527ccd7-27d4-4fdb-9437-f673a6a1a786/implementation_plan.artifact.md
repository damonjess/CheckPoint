# Fix Gemma Initialization Error (Red Writing)

The app fails to initialize the Gemma LLM engine because it lacks permission to access `gemma.task` in the `/sdcard/Download/` folder on modern Android (API 30+).

## Proposed Changes

### [Component Name] vision

#### [MODIFY] [GemmaAnalyzer.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/vision/GemmaAnalyzer.kt)
- Update `findModelFile` to use `Environment.getExternalStoragePublicDirectory` for the Downloads folder.
- Improve error handling to specifically catch permission issues when opening the model file.
- Provide a clear, actionable error message telling the user to move the model to the app's private directory if a permission error occurs.

### [Component Name] manifest

#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/AndroidManifest.xml)
- Add `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` permissions to support older devices and provide a better baseline for storage access.

## Verification Plan

### Manual Verification
- Deploy the app to the device.
- Observe the "red writing" (error message). It should now be more descriptive or gone if the file is moved to the suggested path.
- I will advise the user to move the `gemma.task` file to:
  `/sdcard/Android/data/com.yourcompany.facesearch/files/gemma.task`
  where the app has full access without extra permissions.
