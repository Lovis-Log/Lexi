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
    val firstReviewAt: Long? = null,
    val lastCompletedAt: Long? = null,
) {
    val id: String get() = result.id
}

internal class LexiRepository(
    private val database: LexiDatabase,
    private val settings: ProviderSettings,
    private val resolver: ContentResolver,
    private val service: LookupService,
) {
    private val dao = database.lexiDao()
    private val lookupLock = Mutex()
    val cards = dao.observeCards().map { rows -> rows.map(CardRow::toCard) }

    val history = dao.observeHistory()

    suspend fun deleteHistory(id: String) = dao.deleteHistory(id)
    suspend fun clearHistory() = dao.clearHistory()

    suspend fun lookup(input: LookupInput): LookupResult {
        val normalized = input.normalized()
        if (!normalized.isValid()) throw LookupFailure("请输入要查询的内容。查询最多 800 字，上下文最多 1600 字。", "Enter a query of up to 800 characters and context of up to 1600 characters.")

        return lookupLock.withLock {
            suspend fun remember(result: LookupResult): LookupResult {
                dao.recordHistory(HistoryRow(result.id, normalized.text, normalized.context, System.currentTimeMillis()))
                return result
            }
            suspend fun cached(version: Int): LookupResult? {
                val row = dao.cached(normalized.identity, version) ?: return null
                return try {
                    lexiJson.decodeFromString<LookupResult>(row.resultJson)
                        .takeIf {
                            val sameInput = if (version == LookupSchemaVersion) it.input == normalized else it.id == normalized.identity
                            sameInput && it.explanation.isValid()
                        }
                } catch (_: SerializationException) {
                    null
                }
            }
            cached(LookupSchemaVersion)?.let { return@withLock remember(it) }
            val legacy = cached(4) ?: cached(3) ?: cached(2) ?: cached(1)
            val config = try {
                settings.config()
            } catch (failure: LookupFailure) {
                if (legacy != null) return@withLock remember(legacy)
                throw failure
            }
            if (config == null) {
                if (legacy != null) return@withLock remember(legacy)
                throw LookupFailure("请先到设置填写模型名称和 API Key。", "Configure your model and API key in Settings.", opensSettings = true)
            }
            val explanation = try {
                service.explain(normalized, config)
            } catch (failure: LookupFailure) {
                if (legacy != null) return@withLock remember(legacy)
                throw failure
            }
            val result = LookupResult(normalized, explanation)
            dao.cache(CachedLookup(result.id, LookupSchemaVersion, lexiJson.encodeToString(result)))
            remember(result)
        }
    }

    suspend fun lookupImage(base64Jpeg: String, context: String): LookupResult = lookupLock.withLock {
        val config = settings.config()
            ?: throw LookupFailure("请先在设置中保存 AI 接口配置。", "Save an AI endpoint configuration in Settings.", opensSettings = true)
        val result = service.explainImage(base64Jpeg, LookupInput("", context).normalized().context, config)
        database.withTransaction {
            dao.cache(CachedLookup(result.id, LookupSchemaVersion, lexiJson.encodeToString(result)))
            dao.recordHistory(HistoryRow(result.id, result.input.text, result.input.context, System.currentTimeMillis()))
        }
        result
    }

    suspend fun save(result: LookupResult): Boolean = database.withTransaction {
        val existing = dao.card(result.id)
        if (existing != null) {
            if (existing.toCard().result.explanation.analysisVersion < result.explanation.analysisVersion) {
                dao.update(existing.copy(resultJson = lexiJson.encodeToString(result)))
                return@withTransaction true
            }
            return@withTransaction false
        }
        val now = System.currentTimeMillis()
        dao.add(SavedCard(result, now, ReviewState(dueAt = now)).toRow())
        false
    }

    suspend fun rate(card: SavedCard, rating: Rating, dailyLimit: Int, sameSession: Boolean): SavedCard? =
        database.withTransaction {
            val latest = dao.card(card.id)?.toCard() ?: return@withTransaction null
            val now = System.currentTimeMillis()
            val day = reviewDay(now)
            val canRepeat = sameSession && day.includes(latest.schedule.lastReviewAt)
            if (latest.archived || latest.schedule != card.schedule || (latest.schedule.dueAt > now && !canRepeat)) {
                return@withTransaction null
            }
            if (!day.includes(latest.schedule.lastReviewAt)) {
                val reviewed = dao.reviewedResults(day.start, day.end)
                    .mapTo(mutableSetOf()) { lexiJson.decodeFromString<LookupResult>(it).reviewWordKey }
                if (reviewed.size >= dailyLimit && latest.result.reviewWordKey !in reviewed) return@withTransaction null
            }
            val updated = latest.copy(
                schedule = ReviewScheduler.rate(latest.schedule, rating, now),
                firstReviewAt = if (latest.schedule.phase == ReviewPhase.NEW) now else latest.firstReviewAt,
                lastCompletedAt = if (rating == Rating.GOOD) now else latest.lastCompletedAt,
            )
            dao.update(updated.toRow())
            updated
        }

    suspend fun setArchived(card: SavedCard, archived: Boolean) {
        dao.setArchived(card.id, archived)
    }

    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val all = dao.allCards().map(CardRow::toCard)
        val bytes = lexiJson.encodeToString(Backup(BackupVersion, all)).toByteArray(Charsets.UTF_8)
        if (bytes.size > BackupLimit) throw LookupFailure("词库超过当前版本可导出的大小。", "The library is too large to export.")
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IOException("Could not open backup file")
        all.size
    }

    suspend fun exportPdf(uri: Uri): Int = withContext(Dispatchers.IO) {
        val cards = dao.allCards().map(CardRow::toCard)
        resolver.openOutputStream(uri, "wt")?.use { writeVocabularyPdf(it, cards) }
            ?: throw IOException("Could not open PDF")
        cards.size
    }

    suspend fun restore(uri: Uri): Int = withContext(Dispatchers.IO) {
        val raw = resolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                if (output.size() + size > BackupLimit) throw LookupFailure("备份文件过大。", "The backup file is too large.")
                output.write(buffer, 0, size)
            }
            output.toString(Charsets.UTF_8.name())
        } ?: throw IOException("Could not read backup file")
        val backup = try {
            lexiJson.decodeFromString<Backup>(raw)
        } catch (_: SerializationException) {
            throw LookupFailure("无法读取备份，请选择 Lexi 导出的 JSON。", "Choose a valid JSON backup exported from Lexi.")
        }
        if (backup.version != BackupVersion) throw LookupFailure("此备份版本暂不支持。", "This backup version is unsupported.")
        if (backup.cards.any { !it.isValid() }) throw LookupFailure("备份含有无效卡片，未导入。", "The backup contains invalid cards. No cards were imported.")
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
        result.input.isValid() && result.langGroup == result.subType.language &&
        result.explanation.isValid() && schedule.isValid() &&
        listOfNotNull(firstReviewAt, lastCompletedAt).all { schedule.lastReviewAt != null && it in 1..schedule.lastReviewAt }
}

private fun SavedCard.toRow() = CardRow(
    id, lexiJson.encodeToString(result), savedAt,
    schedule.dueAt, schedule.lastReviewAt, schedule.stability, schedule.difficulty,
    schedule.reviews, schedule.lapses, schedule.phase.name, archived, firstReviewAt, lastCompletedAt,
)

private fun CardRow.toCard() = SavedCard(
    lexiJson.decodeFromString<LookupResult>(resultJson), savedAt,
    ReviewState(dueAt, lastReviewAt, stability, difficulty, reviews, lapses, ReviewPhase.valueOf(phase)),
    archived, firstReviewAt, lastCompletedAt,
)
