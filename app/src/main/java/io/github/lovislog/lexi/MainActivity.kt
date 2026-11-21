package io.github.lovislog.lexi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val model: LexiModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val openSettings = intent.getBooleanExtra(OpenSettings, false)
        setContent { LexiTheme { LexiApp(model, openSettings) } }
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
            LexiTheme {
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
