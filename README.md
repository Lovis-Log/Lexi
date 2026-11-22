# Lexi

Lexi 是一款 Android 英语词汇学习应用。它根据词句所在的语境提供双语解析，并把值得记住的词句加入复习。界面支持简体中文和英文。

## 下载与安装

当前版本为 **v0.1.0-preview**，支持 Android 8.0 及以上版本。已有同签名测试版的用户可以直接覆盖安装。

[下载 APK](https://github.com/Lovis-Log/Lexi/releases/download/v0.1.0-preview/Lexi-v0.1.0-preview.apk) · [查看发布说明和 SHA-256 校验文件](https://github.com/Lovis-Log/Lexi/releases/tag/v0.1.0-preview)

## 开始使用

1. 打开 **设置 → AI 接口**，填写兼容 Chat Completions 的 HTTPS 接口地址和 API Key。默认地址为 `https://api.deepseek.com`。可点 **获取模型** 后选择，也可手动输入模型 ID。点 **保存并测试** 验证模型能返回 JSON 后保存配置。
2. 在 **查词** 页输入单词、短语、英文句子或想用英文表达的中文。可以补充原句、场景或沟通对象。点例句中带虚下划线的单词或短语，可带着原句继续查词。
3. 点输入框旁的相机图标，拍照或从相册选图。在全屏中框选文字后点 **用 AI 识图**。官方 DeepSeek 接口默认使用 `deepseek-flash`，其他接口默认使用主模型，也可以在 **AI 接口** 中单独指定支持图片输入的模型。
4. 点 **加入词库** 保存词句。在 **复习** 页翻卡对照答案。**认读**练习从英文回想意思，**表达**练习根据中文或提示回想英文。选 **忘了** 或 **模糊** 会在本轮再次看到卡片，选 **掌握** 才计入今日已复习。FSRS-6 根据评分安排下次复习。
5. 在支持 Android 文本处理菜单的应用里，可选中文字并点 **用 Lexi 查词**。关闭查询窗口后即可回到原应用。

词条有音标时会分别标注美式和英式。通过 **设置 → 外观设置 → 阅读字号** 可为查词结果和词库详情选择小、中、大三档，复习翻转卡片使用固定字号。

## 数据与备份

词库、查询历史和复习进度保存在本机，API Key 由 Android Keystore 保护。新查询的文字、补充语境和主动提交的框选图片会发送给所配置的 AI 接口。重复查询可使用本地缓存，已保存的词句支持离线复习。

在 **设置 → 词库导出与备份** 中可以导出包含词库和复习进度的 JSON 备份，或导出便于打印的词库 PDF。导入 JSON 会添加缺失的卡片并保留已有卡片。备份仅包含词库和复习数据。

## 本地构建

需要 JDK 17、Android SDK Platform 36 和 Android SDK Build Tools 35.0.0。可用 Android Studio 打开项目，也可在仓库根目录运行 Gradle Wrapper。

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows PowerShell 使用以下命令。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
