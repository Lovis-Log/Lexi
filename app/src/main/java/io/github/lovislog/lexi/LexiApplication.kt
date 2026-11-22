package io.github.lovislog.lexi

import android.app.Application
import androidx.room.Room

class LexiApplication : Application() {
    internal val settings by lazy { ProviderSettings(this) }
    internal val appearance by lazy { AppearanceSettings(this) }
    internal val lookupService by lazy { LookupService() }
    internal val repository by lazy {
        val database = Room.databaseBuilder(this, LexiDatabase::class.java, "lexi.db")
            .addMigrations(LexiMigration1To2, LexiMigration2To3)
            .build()
        LexiRepository(database, settings, contentResolver, lookupService)
    }
}
