package io.github.lovislog.lexi

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class Atmosphere { CLOUDS, GRID, STATIC }
internal enum class Accent { BLUE, VIOLET, MINT }
internal enum class ReadingSize(val scale: Float) { SMALL(1f), MEDIUM(1.16f), LARGE(1.32f) }

internal data class Appearance(
    val english: Boolean = false,
    val atmosphere: Atmosphere = Atmosphere.CLOUDS,
    val accent: Accent = Accent.BLUE,
    val dailyLimit: Int = 20,
    val readingSize: ReadingSize = ReadingSize.SMALL,
)

internal class AppearanceSettings(context: Context) {
    private val preferences = context.getSharedPreferences("lexi-ui", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(
        Appearance(
            english = preferences.getBoolean("english", false),
            atmosphere = Atmosphere.entries.firstOrNull { it.name == preferences.getString("atmosphere", null) }
                ?: Atmosphere.CLOUDS,
            accent = Accent.entries.firstOrNull { it.name == preferences.getString("accent", null) } ?: Accent.BLUE,
            readingSize = ReadingSize.entries.firstOrNull { it.name == preferences.getString("reading_size", null) }
                ?: ReadingSize.SMALL,
            dailyLimit = preferences.getInt("daily_limit", 20).coerceIn(5, 50),
        ),
    )
    val state = mutable.asStateFlow()

    fun update(transform: (Appearance) -> Appearance) {
        val next = transform(mutable.value)
        mutable.value = next
        preferences.edit()
            .putBoolean("english", next.english)
            .putString("atmosphere", next.atmosphere.name)
            .putString("accent", next.accent.name)
            .putInt("daily_limit", next.dailyLimit)
            .putString("reading_size", next.readingSize.name)
            .apply()
    }
}

internal val LocalAppearance = staticCompositionLocalOf { Appearance() }

@Composable
internal fun tr(zh: String, en: String): String = if (LocalAppearance.current.english) en else zh

internal data class LocalizedText(val zh: String, val en: String) {
    fun inLanguage(english: Boolean): String = if (english) en else zh
    @Composable fun display(): String = inLanguage(LocalAppearance.current.english)
}

@Composable
internal fun ContentKind.display(plural: Boolean = false): String = tr(label, when (this) {
    ContentKind.WORD -> if (plural) "Words" else "Word"
    ContentKind.PHRASE -> if (plural) "Phrases" else "Phrase"
    ContentKind.SENTENCE -> if (plural) "Sentences" else "Sentence"
})
