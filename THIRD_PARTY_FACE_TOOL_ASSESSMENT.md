# Third-Party Face Tool Assessment

## Sources reviewed

| Project or service | URL | Verified finding |
|---|---|---|
| DeepFace | https://github.com/serengil/deepface | MIT-licensed Python framework that performs face detection, alignment, embeddings, verification, and local database search; it wraps multiple models and detector backends. |
| DeepFace PyPI package | https://pypi.org/project/deepface/ | Current package page lists version 0.0.100 and confirms Python installation plus heavyweight model/dependency management. |
| Nix4444/Pimeyes-scraper | https://github.com/Nix4444/Pimeyes-scraper | Small Selenium script with 5 commits. Its README explicitly uploads a face image to PimEyes, recommends proxies or a VPN after an IP search limit, and automates the results flow. |
| PimEyes Terms | https://pimeyes.com/en/terms-of-service | The service supplies a browser-facing, quota-based service, governs use through its terms, and prohibits improper, unauthorized, invasive, or policy-violating use. |

## Assessment

DeepFace is technically relevant only as an **optional local or self-hosted verification service** for face images the user is authorized to process. It is not an Android library and does not solve web discovery: its `find`/`search` features query a user-controlled local or registered database, not public social media. Integrating it directly into a Termux phone service would impose Python, TensorFlow/Keras, native dependencies, model downloads, and meaningful memory/thermal load. The current Android ML Kit plus on-device embedding pipeline is the more appropriate mobile default.

DeepFace could be useful later for an opt-in desktop/server companion that verifies a captured self-photo against candidate thumbnails returned by permitted search providers. Use only verification/embeddings, not age, gender, race, or emotion inference, which are unnecessary for this app and more error-prone and sensitive.

The displayed PimEyes scraper is not suitable. Its own documentation describes Selenium upload automation and proxy/VPN use to work around an IP search limit. It is fragile, does not supply an approved integration, conflicts with the app's current no-evasion design, and should not be copied into the project. The official service should be used only in its normal browser flow by the person with authorization to search their own image.

## References

[1] [DeepFace GitHub repository](https://github.com/serengil/deepface)
[2] [DeepFace package on PyPI](https://pypi.org/project/deepface/)
[3] [Nix4444/Pimeyes-scraper GitHub repository](https://github.com/Nix4444/Pimeyes-scraper)
[4] [PimEyes Terms of Service](https://pimeyes.com/en/terms-of-service)
