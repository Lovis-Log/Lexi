package io.github.lovislog.lexi

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "lookup_cache")
internal data class CachedLookup(
    @PrimaryKey val id: String,
    val version: Int,
    val resultJson: String,
)

@Entity(tableName = "lookup_history")
internal data class HistoryRow(
    @PrimaryKey val id: String,
    val text: String,
    val context: String,
    val viewedAt: Long,
) {
    val input: LookupInput get() = LookupInput(text, context)
}

@Entity(tableName = "flashcards")
internal data class CardRow(
    @PrimaryKey val id: String,
    val resultJson: String,
    val savedAt: Long,
    val dueAt: Long,
    val lastReviewAt: Long?,
    val stability: Double,
    val difficulty: Double,
    val reviews: Int,
    val lapses: Int,
    val phase: String,
    val archived: Boolean,
    val firstReviewAt: Long?,
    val lastCompletedAt: Long?,
)

@Dao
internal interface LexiDao {
    @Query("SELECT * FROM lookup_cache WHERE id = :id AND version = :version")
    suspend fun cached(id: String, version: Int): CachedLookup?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun cache(lookup: CachedLookup)

    @Query("SELECT * FROM lookup_history ORDER BY viewedAt DESC, id")
    fun observeHistory(): Flow<List<HistoryRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordHistory(row: HistoryRow)

    @Query("DELETE FROM lookup_history WHERE id = :id")
    suspend fun deleteHistory(id: String)

    @Query("DELETE FROM lookup_history")
    suspend fun clearHistory()

    @Query("SELECT resultJson FROM flashcards WHERE lastReviewAt >= :start AND lastReviewAt < :end")
    suspend fun reviewedResults(start: Long, end: Long): List<String>

    @Query("SELECT * FROM flashcards ORDER BY savedAt DESC, id")
    fun observeCards(): Flow<List<CardRow>>

    @Query("SELECT * FROM flashcards ORDER BY savedAt DESC, id")
    suspend fun allCards(): List<CardRow>

    @Query("SELECT * FROM flashcards WHERE id = :id")
    suspend fun card(id: String): CardRow?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(card: CardRow): Long

    @Update
    suspend fun update(card: CardRow)

    @Query("UPDATE flashcards SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)
}

internal val LexiMigration1To2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flashcards ADD COLUMN firstReviewAt INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS lookup_history " +
                "(id TEXT NOT NULL, text TEXT NOT NULL, context TEXT NOT NULL, " +
                "viewedAt INTEGER NOT NULL, PRIMARY KEY(id))",
        )
    }
}

internal val LexiMigration2To3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flashcards ADD COLUMN lastCompletedAt INTEGER")
        db.execSQL("UPDATE flashcards SET lastCompletedAt = lastReviewAt WHERE phase = 'REVIEW'")
    }
}

@Database(entities = [CachedLookup::class, CardRow::class, HistoryRow::class], version = 3)
internal abstract class LexiDatabase : RoomDatabase() {
    abstract fun lexiDao(): LexiDao
}
