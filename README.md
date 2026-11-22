# Lexi

Lexi is a local-first Android vocabulary app for understanding English in context and turning useful expressions into review cards. Its interface is currently in Simplified Chinese.

## Build and run

Use JDK 17 and Android SDK 36 (including Build Tools 36.0.0). Open this project in Android Studio or run the Gradle wrapper from the repository root:

```shell
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

On Windows use `gradlew.bat`. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. The app supports Android 8.0 (API 26) and above.

## First lookup

1. Open **设置** and enter an HTTPS Chat Completions Base URL and your API key. Tap **粘贴 API Key** to paste the key, then **获取模型** and choose a model. You can also enter a model ID manually. **保存并测试** runs one sample lookup and saves the settings once it succeeds. The default URL points to DeepSeek.
2. Open **查词**, enter English text or ask in Chinese for an English expression. Add the original sentence when it helps resolve meaning.
3. Tap **收藏** and open **复习**. English lookups become recognition cards. Chinese requests for expressions become production cards.
4. In a compatible Android app, select text and choose **用 Lexi 查词** in the selection menu. Confirm the lookup in the dialog. Close it to return to the source app.

For an uncached query, Lexi sends the entered text and any supplied context to the configured provider. Exact repeat queries use the local cache. Saved cards and their FSRS-6 schedules can be used offline. Android TTS uses an installed offline English voice when available.

**设置 → 导出 JSON** saves your cards and review progress. Import adds missing cards and preserves existing local copies. The backup does not contain API keys or transient query caches.
