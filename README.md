# CheckPoint 🔍

An Android application and face search service for facial verification, OSINT investigation, and identity analysis.

## Features

- **On-Device Face Analysis & Embedding:** Powered by LiteRT / ML Kit for local facial detection and vector embeddings.
- **Multi-Engine Reverse Image Search:** Integrates with public and private reverse image search endpoints.
- **OSINT & Profile Discovery:** Automated search tools and web scraping pipelines for identity analysis.
- **Encrypted Local Vault:** SQLCipher-backed encrypted storage for face templates and watchlist targets.
- **Embedded Local Server & Microservice:** Includes a companion `face-search-service` for server-side processing.

## Project Structure

- `app/`: Native Android application built with Jetpack Compose & Kotlin.
- `face-search-service/`: Node.js microservice for face processing & automation.
- `docs/`: Technical notes, implementation logs, and architecture documentation.

## Setup & Local Configuration

1. Clone the repository:
   ```bash
   git clone https://github.com/damonjess/CheckPoint.git
   ```
2. Open in **Android Studio** (2024.1+ recommended).
3. Create a `local.properties` file in the root directory (ignored by Git) and add your optional API keys:
   ```properties
   SERP_API_KEY=your_serp_api_key_here
   IMGBB_API_KEY=your_imgbb_api_key_here
   ```
4. Build and run on an Android device or emulator (Android 8.0+ / API 26+).

## Documentation

Detailed architectural and feature update notes can be found in the [`docs/`](./docs) directory.
