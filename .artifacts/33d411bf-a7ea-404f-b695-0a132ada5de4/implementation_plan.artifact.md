# Fix Kotlin Compilation Error: Type Inference in Function References

The goal is to resolve the "Cannot infer type for this parameter" error caused by using function references (`::`) with `let` in contexts where the compiler cannot resolve the functional signature or where suspend/non-suspend mismatches occur.

## Proposed Changes

### [UI]

#### [MODIFY] [CheckInViewModel.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/ui/CheckInViewModel.kt)
- Replace the function reference `::loadThumbnailBitmap` with a lambda `{ loadThumbnailBitmap(it) }` to allow the suspend function call to be correctly scoped within the coroutine.

### [Vision]

#### [MODIFY] [NativeFaceCropper.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/vision/NativeFaceCropper.kt)
- Replace the function reference `::scaleToMaxDimension` with a lambda `{ scaleToMaxDimension(it) }` for consistency and to avoid potential inference issues.

### [Data]

#### [MODIFY] [IdentityProfileStore.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/data/IdentityProfileStore.kt)
- Replace function references `::add` within `buildList` blocks with lambdas `{ add(it) }` to resolve ambiguity between overloaded `add` methods.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:compileDebugKotlin` to ensure the project builds without the reported error.
