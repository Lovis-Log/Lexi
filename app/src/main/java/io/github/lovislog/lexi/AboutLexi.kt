package io.github.lovislog.lexi

import androidx.compose.runtime.Composable

@Composable
internal fun AboutLexi() {
    Note(tr("查词和翻译", "Lookup and translation"),
        tr("输入单词、短语、英文原句或中文表达。补充原句、场景或对象后，Lexi 会按语境提供双语释义、搭配、句型和例句。点击虚下划线可带着原句继续查词，点击同境表达可查相关词。",
            "Look up words, phrases, English sentences, or Chinese expressions. Add the source sentence, situation, or audience to get contextual definitions, patterns, collocations, and examples. Tap an underline to look up the expression in context, or tap a related expression to explore it."))
    Note(tr("照片查词", "Photo lookup"),
        tr("拍照或从相册选图后，在全屏中框选目标文字。点「用 AI 识图」会将框内图像发送给已配置的 AI 接口，由支持图片的模型识别并解析。",
            "Take or choose a photo and select the text area in the full-screen crop tool. Tap Analyze with AI to send the selected area to a model that accepts images."))
    Note(tr("词库和复习", "Library and review"),
        tr("保存词条即可加入复习。认读（Recognition）从英语回想含义，表达（Expression）从中文或提示回想英语。翻面核对后，「忘了」和「模糊」会安排本轮再学，「掌握」计入今日已复习。FSRS 会根据评分安排下次复习。",
            "Save an entry to add it to review. Recognition prompts you to recall the meaning of English. Expression prompts you to recall English from Chinese or a hint. Flip the card to check your answer. Forgot and Unsure bring it back later in the session, and Got it counts as completed. FSRS schedules the next review."))
    Note(tr("CEFR 难度 · A1 到 C2", "CEFR difficulty · A1 to C2"),
        tr("A1 入门，A2 基础，B1 中级，B2 中高级，C1 高级，C2 熟练。以 B1 为例，通常能理解熟悉场景中的清晰表达，并应对多数日常交流。词条上的级别是模型对用法难度的估计。",
            "A1 is beginner, A2 elementary, B1 intermediate, B2 upper intermediate, C1 advanced, and C2 proficient. At B1, learners typically grasp the main points of clear language on familiar topics. An entry's level is the model's estimate of usage difficulty."))
    Note(tr("音标、词性和频率", "Phonetics, part of speech, and frequency"),
        tr("US 是美式音标，UK 是英式音标。n. 表示名词，v. 表示动词，adj. 表示形容词，adv. 表示副词。Very common 表示高频，Common 表示常见，Academic 表示偏学术用语。",
            "US and UK mark American and British IPA. n. means noun, v. verb, adj. adjective, and adv. adverb. Very common and Common describe everyday frequency. Academic marks usage typical of academic contexts."))
    Note(tr("本机数据与备份", "Local data and backups"),
        tr("词库、复习记录和查询历史保存在本机，API Key 由 Android Keystore 保护。新查询的文字、语境和主动提交的框选图会发送给所配置的接口。设置中可导出备份 JSON 或可打印 PDF。",
            "Your library, review progress, and history are stored on this device, and Android Keystore protects the API key. New queries, context, and image crops you submit go to the configured endpoint. Export a JSON backup or a printable PDF in Settings."))
}
