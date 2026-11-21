package io.github.lovislog.lexi

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

@Serializable
internal data class SavedCard(
    val result: LookupResult,
    val savedAt: Long,
    val schedule: ReviewState,
    val archived: Boolean = false,
) {
    val id: String get() = result.id
}

internal class LexiRepository(
    private val database: LexiDatabase,
    private val settings: ProviderSettings,
    private val resolver: ContentResolver,
) {
    private val dao = database.lexiDao()
    private val service = LookupService()
    private val lookupLock = Mutex()
    val cards = dao.observeCards().map { rows -> rows.map(CardRow::toCard) }

    suspend fun lookup(input: LookupInput): LookupResult {
        val normalized = input.normalized()
        if (!normalized.isValid()) throw LookupFailure("请输入要查询的内容，每个输入框最多 800 个字符。")

        return lookupLock.withLock {
            val local = dao.cached(normalized.identity, LookupSchemaVersion)
            if (local != null) {
                try {
                    val result = lexiJson.decodeFromString<LookupResult>(local.resultJson)
                    if (result.input == normalized && result.explanation.isValid()) return@withLock result
                } catch (_: SerializationException) {
                    // A stale or damaged cache entry can be replaced by the next successful lookup.
                }
            }
            val config = settings.config() ?: throw LookupFailure("请先到设置填写模型名称和 API Key。")
            val explanation = service.explain(normalized, config)
            val result = LookupResult(
                normalized,
                if (explanation.intent == LookupIntent.UNDERSTAND) {
                    explanation.copy(answerEn = normalized.text)
                } else {
                    explanation
                },
            )
            dao.cache(CachedLookup(result.id, LookupSchemaVersion, lexiJson.encodeToString(result)))
            result
        }
    }

    suspend fun save(result: LookupResult) {
        val now = System.currentTimeMillis()
        dao.add(SavedCard(result, now, ReviewState(dueAt = now)).toRow())
    }

    suspend fun rate(card: SavedCard, rating: Rating) = database.withTransaction {
        val latest = dao.card(card.id)?.toCard() ?: return@withTransaction
        val now = System.currentTimeMillis()
        if (latest.archived || latest.schedule != card.schedule || latest.schedule.dueAt > now) {
            return@withTransaction
        }
        val schedule = ReviewScheduler.rate(latest.schedule, rating, now)
        dao.update(latest.copy(schedule = schedule).toRow())
    }

    suspend fun setArchived(card: SavedCard, archived: Boolean) {
        dao.setArchived(card.id, archived)
    }

    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val all = dao.allCards().map(CardRow::toCard)
        val bytes = lexiJson.encodeToString(Backup(BackupVersion, all)).toByteArray(Charsets.UTF_8)
        if (bytes.size > BackupLimit) throw LookupFailure("词库超过当前版本可导出的大小。")
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IOException("Could not open backup file")
        all.size
    }

    suspend fun restore(uri: Uri): Int = withContext(Dispatchers.IO) {
        val raw = resolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                if (output.size() + size > BackupLimit) throw LookupFailure("备份文件过大。")
                output.write(buffer, 0, size)
            }
            output.toString(Charsets.UTF_8.name())
        } ?: throw IOException("Could not read backup file")
        val backup = try {
            lexiJson.decodeFromString<Backup>(raw)
        } catch (_: SerializationException) {
            throw LookupFailure("无法读取备份，请选择 Lexi 导出的 JSON。")
        }
        if (backup.version != BackupVersion) throw LookupFailure("此备份版本暂不支持。")
        if (backup.cards.any { !it.isValid() }) throw LookupFailure("备份含有无效卡片，未导入。")
        database.withTransaction {
            backup.cards.count { dao.add(it.toRow()) != -1L }
        }
    }
}

private const val BackupVersion = 1
private const val BackupLimit = 20 * 1024 * 1024

@Serializable
private data class Backup(val version: Int, val cards: List<SavedCard>)

private fun SavedCard.isValid(): Boolean {
    return savedAt in 1..253402300799999L &&
        result.input == result.input.normalized() &&
        result.input.isValid() && result.explanation.isValid() && schedule.isValid()
}

private fun SavedCard.toRow() = CardRow(
    id, lexiJson.encodeToString(result), savedAt,
    schedule.dueAt, schedule.lastReviewAt, schedule.stability, schedule.difficulty,
    schedule.reviews, schedule.lapses, schedule.phase.name, archived,
)

private fun CardRow.toCard() = SavedCard(
    lexiJson.decodeFromString<LookupResult>(resultJson), savedAt,
    ReviewState(dueAt, lastReviewAt, stability, difficulty, reviews, lapses, ReviewPhase.valueOf(phase)),
    archived,
)
