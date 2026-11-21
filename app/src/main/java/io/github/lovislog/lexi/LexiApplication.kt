package io.github.lovislog.lexi

import android.app.Application
import androidx.room.Room

class LexiApplication : Application() {
    internal val settings by lazy { ProviderSettings(this) }
    internal val repository by lazy {
        val database = Room.databaseBuilder(this, LexiDatabase::class.java, "lexi.db").build()
        LexiRepository(database, settings, contentResolver)
    }
}
