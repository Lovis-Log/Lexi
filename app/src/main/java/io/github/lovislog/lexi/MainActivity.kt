package io.github.lovislog.lexi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val model: LexiModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.light(transparent, transparent), SystemBarStyle.light(transparent, transparent))
        val openSettings = intent.getBooleanExtra(OpenSettings, false)
        setContent {
            val appearance by model.appearance.collectAsStateWithLifecycle()
            LexiTheme(appearance) { LexiApp(model, openSettings, onExit = ::finishAffinity) }
        }
    }
}

class ProcessTextActivity : ComponentActivity() {
    private val model: LexiModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selected = if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        if (selected.isBlank()) {
            finish()
            return
        }
        setContent {
            val appearance by model.appearance.collectAsStateWithLifecycle()
            LexiTheme(appearance) {
                SelectionWindow(
                    model,
                    selected = selected.take(801),
                    onClose = ::finish,
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).putExtra(OpenSettings, true))
                        finish()
                    },
                )
            }
        }
    }
}

private const val OpenSettings = "io.github.lovislog.lexi.OPEN_SETTINGS"
