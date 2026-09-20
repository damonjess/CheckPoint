# Fix Termux Detection and Console Error Reporting

The user reports that the app is not detecting the Termux server. Additionally, there are unspecified messages in the console that need addressing. Analysis suggests the Termux server is bound only to `127.0.0.1`, preventing emulator access, and the detection timeout in the app is too aggressive.

## User Review Required

> [!IMPORTANT]
> This plan will change the Termux server to listen on all network interfaces (`0.0.0.0`). If you are running this on a public network, ensure your firewall is configured correctly, although it defaults to port 3000.

## Proposed Changes

### Termux Server (OSINT Helper)

#### [MODIFY] [server.js](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/face-search-service/server.js)
- Change `server.listen` to bind to `0.0.0.0` instead of `127.0.0.1` to allow emulator-to-host communication.

### Android Application

#### [MODIFY] [CheckInViewModel.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/ui/CheckInViewModel.kt)
- Increase the Termux detection timeout from 2 seconds to 5 seconds to prevent false negatives on slower devices/emulators.
- Improve console logging for Termux detection to be more descriptive.

#### [MODIFY] [FaceSearchRepository.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/network/FaceSearchRepository.kt)
- Add `10.0.3.2` to `potentialBackends` to support Genymotion emulators.
- Increase the `fastClient` connect timeout to 3 seconds.

## Verification Plan

### Automated Tests
- Run `adb shell logcat -s CheckIn` during search to verify "Local Termux backend detected" log.

### Manual Verification
1. Start the Termux server (`node server.js`).
2. Run the app in the emulator.
3. Perform a search and verify that the console in the app shows "Local Termux backend detected" instead of the warning.
4. Verify that results are returned from the Termux backend (logs will show "✓ Termux SUCCESS").
