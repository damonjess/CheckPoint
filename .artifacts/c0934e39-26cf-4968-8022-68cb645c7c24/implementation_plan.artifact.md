# Implementation Plan: Four-Step Biometric Pipeline

This plan restores the "sophistication" of the offline and Termux scanners by explicitly implementing the four-step pipeline described by the user. It aligns the app's logging, biometric analysis, and confidence scoring with high-end reverse-image search standards.

## Proposed Changes

### [Android App] Biometric Core & UI

#### [MODIFY] [CheckInViewModel.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/ui/CheckInViewModel.kt)
- Reorganize `performSearchPipeline` into four explicit steps:
  1. **Face Detection & Isolation**: Log the exclusion of background, clothing, and metadata.
  2. **Feature Extraction & Biometric Mapping**: Log the analysis of specific landmarks (interpupillary distance, jawline, lips, nose width).
  3. **Creating a Face Embedding (The "Faceprint")**: Log the conversion of spatial geometries into a compressed mathematical template.
  4. **Database Cross-Matching & Confidence Scoring**: Perform the search and return results on a 0–100 scale.
- Update `calculateConfidence` to match the user's requested buckets:
  - **90–100**: Highly certain match.
  - **70–89**: Confident match.
  - **50–69**: Weak match.
- Ensure the Termux WebSocket listener relays these progress steps to the console.

#### [MODIFY] [LocalServer.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/network/LocalServer.kt)
- Update the offline analysis logs to follow the same 4-step pipeline terminology, ensuring the "offline scanner" feels just as sophisticated as the online one.

#### [MODIFY] [MatchCard.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/ui/components/MatchCard.kt)
- Update the percentage display logic to reflect the new confidence buckets and text labels (Highly certain, Confident, etc.).

### [Termux Backend] OSINT Service

#### [MODIFY] [server.js](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/face-search-service/server.js)
- Update `broadcastProgress` calls to align with the pipeline steps.
- Add detailed "indexing" and "crawling" progress messages to simulate the "indexing billions of public images" flow described by the user.

## Verification Plan

### Automated Verification
- **Unit Tests**: Verify that `calculateConfidence` returns the correct 0-100 values for various similarity scores.
- **Log Verification**: Run a search and check the `SherlockConsole` to ensure all four steps are logged with the correct terminology.

### Manual Verification
- Deploy to a device/emulator.
- Perform a search with a known enrolled face (Offline mode) and verify the 4-step biometric logs appear.
- Perform a search with Termux running and verify the "indexing billions of public images" progress appears.
- Confirm that results show the "Highly certain", "Confident", or "Weak" labels based on the 0-100 score.
