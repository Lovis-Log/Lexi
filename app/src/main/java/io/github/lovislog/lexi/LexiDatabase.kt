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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "lookup_cache")
internal data class CachedLookup(
    @PrimaryKey val id: String,
    val version: Int,
    val resultJson: String,
)

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
)

@Dao
internal interface LexiDao {
    @Query("SELECT * FROM lookup_cache WHERE id = :id AND version = :version")
    suspend fun cached(id: String, version: Int): CachedLookup?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun cache(lookup: CachedLookup)

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

@Database(entities = [CachedLookup::class, CardRow::class], version = 1)
internal abstract class LexiDatabase : RoomDatabase() {
    abstract fun lexiDao(): LexiDao
}
