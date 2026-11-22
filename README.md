# Lexi

Lexi is a local-first Android vocabulary app for understanding English in context and turning useful expressions into review cards. The interface supports Simplified Chinese and English.

## Build and run

Use JDK 17 and Android SDK 36 (including Build Tools 36.0.0). Open this project in Android Studio or run the Gradle wrapper from the repository root:

```shell
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

On Windows use `gradlew.bat`. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. The app supports Android 8.0 (API 26) and above.

## First lookup

1. Open **设置 → AI 接口** and enter an HTTPS Chat Completions Base URL and your API key. Tap **粘贴 API Key** to paste the key, then **获取模型** and choose a model. You can also enter a model ID manually. **保存并测试** verifies that the configured model can return a JSON object, then saves the settings. The default URL points to DeepSeek.
2. Open **查词**, enter a word, phrase, or sentence, and add context for the intended meaning or audience. Tap an underlined word or phrase to look it up with its original sentence as context. The camera button opens a full-screen crop tool for photos or images from your library. Select the text area and tap **用 AI 识图** to submit the crop.
3. Tap **+ 加入词库** and open **复习**. **忘了** and **模糊** reschedule the card with FSRS and bring it back later in the same session. **掌握** completes the card for that session. Switch to **表达** to practice producing the English answer.
4. In a compatible Android app, select text and choose **用 Lexi 查词** in the selection menu. Confirm the lookup in the dialog. Close it to return to the source app.

For an uncached query, Lexi sends the entered text and any supplied context to the configured provider. The chat model must support JSON mode. Exact repeat queries use the local cache. Saved cards and their FSRS-6 schedules can be used offline.

Photo lookup sends the cropped image to your configured provider. The official DeepSeek endpoint uses `deepseek-flash` by default. For other endpoints, Lexi uses your main model unless you enter a vision model ID in **设置 → AI 接口**. Entries show American and British IPA when available. **外观设置 → 阅读字号** controls the size of definitions, examples, and other reading content.

**设置 → 词库导出与备份** exports cards and review progress as JSON or vocabulary as a printable PDF. Import adds missing cards and preserves existing local copies. The backup does not contain API keys or transient query caches.
