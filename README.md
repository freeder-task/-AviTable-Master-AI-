# AI Voice Assistant — Android starter project

Package: `com.example.aibotgame`

This is a starter Android Studio project with:
- draggable floating overlay
- speech recognition request using `bn-BD` and Bengali Text-to-Speech
- user-approved continuous MediaProjection screen monitoring (captures a fresh frame about every 1.5 seconds while enabled)
- AccessibilityService for visible UI text (must be manually enabled in Android Settings)
- on-device ML Kit OCR attempt (Latin-script recognizer; Bengali OCR is not guaranteed)
- Gemini REST API request that can include the captured screenshot and screen text
- explicit overlay and microphone permission checks

## Open/build
1. Download and unzip the project.
2. Open the `AIBotGame` folder in Android Studio.
3. Let Gradle sync, then run on a real Android device (Android 8+).
4. In `GeminiClient.kt`, replace `PASTE_YOUR_GEMINI_API_KEY_HERE` with a test key to try it.
5. For production, **do not ship the Gemini API key inside the APK**. Add a backend endpoint that holds the key and call that endpoint from the app.
6. Grant microphone and overlay permissions. Tap the Accessibility Settings button and manually enable this app's accessibility service if you want visible UI text collection.
7. Tap “লাইভ Screen Monitoring চালু করুন” and approve Android’s system screen-sharing prompt. While active, a foreground notification remains visible and a fresh frame is saved about every 1.5 seconds. Stop monitoring from the app or the notification Stop action. Android may stop capture, and the service is not guaranteed to run literally 24/7; user consent is required for each new MediaProjection session.

## Important limitations and privacy
- MediaProjection always requires system consent. Android versions and OEMs may impose additional restrictions.
- The app sends the user-approved screenshot plus collected screen text to the configured Gemini endpoint. Do not capture passwords, payment information, private messages, or other sensitive screens.
- Accessibility text is incomplete for canvas/video/game surfaces and apps that block accessibility. OCR here uses ML Kit's Latin recognizer; Bengali text recognition is not assured. Gemini vision may describe visible pixels, but it can still make mistakes.
- Voice questions are analyzed using the most recently captured frame plus accessible UI text. The code does not continuously record the microphone; press the voice button to speak. It does not predict crash-game results or give a guaranteed cash-out time. Such outcomes cannot be reliably inferred from a screenshot.
- This is a starter project, not a security-reviewed production release. Review current Android SDK, Gemini API, and Google Play policies before distribution.

## GitHub Actions: Build APK automatically

This project includes `.github/workflows/build.yml`. After you upload/extract the **project files** into a GitHub repository and commit them to `main` or `master`, GitHub Actions attempts to build a debug APK. You can also run it manually from the repository's **Actions** tab using **Build AIBotGame APK** → **Run workflow**.

When the workflow succeeds, open that run and download the `AIBotGame-Debug-APK` artifact. It is a ZIP containing the debug APK; extract it on your phone to get the `.apk` file.

Important:
- Upload the extracted project contents, not only this project ZIP, to GitHub. The workflow file must be at `.github/workflows/build.yml` in the repository.
- A successful CI build is not the same as testing the app on a physical device. This source has not been verified by a successful Android build in this environment.
- The APK is a debug build, not a Play Store release. Live screen capture still requires Android's explicit screen-capture consent.
