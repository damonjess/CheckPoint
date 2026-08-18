# Public Profile Discovery Update

## New capability

The app now includes a separate **Find My Public Profiles** screen. It helps the device owner prepare and manually review likely public profile routes without changing the existing camera, gallery, offline scan, face verification, Termux fallback, or visual-search flows.

| Capability | Behavior |
|---|---|
| **Local identity card** | Stores an optional name, aliases, known handle fragments, city, and website in the app's private storage. The new store does not upload these fields. |
| **Username-variant generator** | Builds normalized handle variants from supplied names and aliases, including compact, dotted, underscored, and hyphenated forms. |
| **Public profile routes** | Generates manually reviewable profile URLs for Instagram, Facebook, TikTok, Snapchat, X, LinkedIn, GitHub, YouTube, and Reddit. It does not log into or scrape any platform. |
| **Manual public-web search** | Opens a browser search only after the user taps it, allowing the user to complete any provider prompt or login themselves. |
| **Evidence labels** | Marks routes made from a provided handle separately from name-derived variants. A route is never presented as proof that an account exists or belongs to the user. |

> The existing offline scan is unchanged. The new discovery screen does not call the camera pipeline, alter an image, or require Termux.

## Files to copy into Android Studio

Replace or add the following files from the latest archive:

```text
app/src/main/java/com/yourcompany/facesearch/MainActivity.kt
app/src/main/java/com/yourcompany/facesearch/ui/CheckInScreen.kt
app/src/main/java/com/yourcompany/facesearch/data/IdentityProfileStore.kt
app/src/main/java/com/yourcompany/facesearch/ui/ProfileDiscoveryViewModel.kt
app/src/main/java/com/yourcompany/facesearch/ui/ProfileDiscoveryScreen.kt
```

No Termux `server.js` update is required for this feature.

## How to use it

1. Rebuild and install the Android app.
2. On the main search screen, tap **Find My Public Profiles**.
3. Add only your own name, aliases, or handle fragments; all fields are optional.
4. Tap **Generate Public Profile Leads**.
5. Review the routes marked **Built from a handle you provided** first. Tap a card to open it in the browser and confirm it yourself.
6. Treat **Name or alias variant** routes as leads only. They may be unrelated accounts.

## Validation note

Static wiring checks passed, including the new screen navigation, local-only storage, and the unchanged photo-capture call. Full Gradle compilation could not run in this environment because the Android SDK location is not configured here. Android Studio should compile the project using its installed SDK.
