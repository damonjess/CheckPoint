# Face Capture and Gemma Removal Implementation Notes

The updated capture path uses ML Kit in accurate mode with landmarks and classification enabled, rejects ambiguous multi-face frames, and checks face size, pose, illumination, sharpness, and eye openness before a search is started. The crop retains forehead, chin, and side context and aligns the crop from the detected eye landmarks. This replaces the prior inconsistent cropping logic, which clipped the lower face and attempted to alter the image for search engines.

The reverse-image probe is a size-normalized copy of the original photograph, not a synthesized composite or a distorted face crop. This preserves the visual context needed for legitimate reverse-image matching while the aligned portrait crop is reserved for on-device match verification. The app should only be used with images of the user or where the user has explicit authorization.

Termux is treated as an optional, resource-constrained helper: its backend reports availability cleanly, runs engines sequentially, and does not attempt to evade access controls. When an external engine blocks automated access, the Android application should use its existing user-controlled share flow to open a normal reverse-image-search client.

The Gemma `.task` feature is removed end to end: the MediaPipe GenAI dependency, `GemmaAnalyzer`, view-model initialization and calls, state field, result card, and lifecycle shutdown call are all deleted. This removes the large local model requirement and prevents an automated “identity” summary from being produced.

## Reference

Google’s ML Kit guide says that input images should generally be at least 480×360 pixels, faces should generally be at least 100×100 pixels, landmarks support pose-aware processing, and poor focus materially harms detection. See [ML Kit face detection for Android](https://developers.google.com/ml-kit/vision/face-detection/android). ML Kit’s face-detection concepts document defines its pose axes, landmarks, classifications, and minimum-face-size trade-off. See [ML Kit face-detection concepts](https://developers.google.com/ml-kit/vision/face-detection/face-detection-concepts).
