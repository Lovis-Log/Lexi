package io.github.lovislog.lexi

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
internal fun rememberSpeech(onUnavailable: (String) -> Unit): (String) -> Unit {
    val context = LocalContext.current.applicationContext
    val notice by rememberUpdatedState(onUnavailable)
    var speaker by remember { mutableStateOf<TextToSpeech?>(null) }

    // Initialize on the first tap. The engine is released when this activity closes.
    var requested by remember { mutableStateOf(false) }
    var queued by remember { mutableStateOf<String?>(null) }
    if (requested) {
        DisposableEffect(context) {
            val main = Handler(Looper.getMainLooper())
            var active = true
            lateinit var engine: TextToSpeech
            engine = TextToSpeech(context) { status ->
                main.post {
                    if (active) {
                        val voice = if (status == TextToSpeech.SUCCESS) {
                            engine.voices.orEmpty()
                                .filter { it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired }
                                .maxByOrNull { (if (it.locale == Locale.US) 1_000 else 0) + it.quality }
                        } else {
                            null
                        }
                        if (voice != null && engine.setVoice(voice) == TextToSpeech.SUCCESS) {
                            speaker = engine
                            queued?.let { if (!engine.say(it)) notice("暂时无法播放发音。") }
                        } else {
                            requested = false
                            notice("系统暂无可用的离线英语语音。")
                        }
                        queued = null
                    }
                }
            }
            onDispose {
                active = false
                speaker = null
                engine.shutdown()
            }
        }
    }
    return { text ->
        val ready = speaker
        if (ready == null) {
            queued = text
            requested = true
        } else if (!ready.say(text)) {
            notice("暂时无法播放发音。")
        }
    }
}

private fun TextToSpeech.say(text: String): Boolean =
    speak(text, TextToSpeech.QUEUE_FLUSH, null, "lexi-speech") == TextToSpeech.SUCCESS
