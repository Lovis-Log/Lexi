package io.github.lovislog.lexi

import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

internal fun buildReviewQueue(
    cards: List<SavedCard>, remaining: Int, now: Long, alreadyReviewed: Set<String>,
): List<SavedCard> {
    var slots = remaining
    val includedWords = alreadyReviewed.toMutableSet()
    return cards.filter { it.schedule.dueAt <= now }
        .sortedWith(compareBy<SavedCard> { it.schedule.phase == ReviewPhase.NEW }
            .thenBy { it.schedule.dueAt }.thenBy { it.schedule.stability }.thenBy { it.savedAt }.thenBy { it.id })
        .filter { card ->
            val word = card.result.reviewWordKey
            if (word in includedWords) true
            else if (slots <= 0) false
            else {
                slots--
                includedWords += word
                true
            }
        }
}

internal enum class Rating(val grade: Int) { AGAIN(1), HARD(2), GOOD(3) }

internal data class ReviewDay(val start: Long, val end: Long) {
    fun includes(timestamp: Long?): Boolean = timestamp != null && timestamp >= start && timestamp < end
}

internal fun reviewDay(now: Long, zone: ZoneId = ZoneId.systemDefault()): ReviewDay {
    val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return ReviewDay(
        date.atStartOfDay(zone).toInstant().toEpochMilli(),
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
}

@Serializable
internal enum class ReviewPhase { NEW, LEARNING, REVIEW, RELEARNING }

@Serializable
internal data class ReviewState(
    val dueAt: Long,
    val lastReviewAt: Long? = null,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val reviews: Int = 0,
    val lapses: Int = 0,
    val phase: ReviewPhase = ReviewPhase.NEW,
) {
    fun isValid(): Boolean {
        val dateRange = 1..253402300799999L
        return dueAt in dateRange && reviews in 0..1_000_000 && lapses in 0..reviews &&
            if (phase == ReviewPhase.NEW) {
                lastReviewAt == null && reviews == 0 && stability == 0.0 && difficulty == 0.0
            } else {
                lastReviewAt != null && lastReviewAt in dateRange && dueAt >= lastReviewAt &&
                    reviews > 0 && stability in 0.001..36500.0 && difficulty in 1.0..10.0
            }
    }
}

/**
 * FSRS-6 with its published defaults and 90% target retention.
 * Formula reference: https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm#fsrs-6
 * Lexi applies its own learning intervals and stores absolute timestamps.
 */
internal object ReviewScheduler {
    private const val Minute = 60_000L
    private const val Day = 24 * 60 * Minute
    private const val MaxDays = 36500
    private val weights = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
        1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729,
        0.5425, 0.0912, 0.0658, 0.1542,
    )
    private val decay = -weights[20]
    private val factor = 0.9.pow(1.0 / decay) - 1.0

    fun rate(state: ReviewState, rating: Rating, now: Long): ReviewState {
        val days = state.lastReviewAt?.let { (now - it).coerceAtLeast(0) / Day } ?: 0L
        val new = state.phase == ReviewPhase.NEW
        val stability = if (new) weights[rating.grade - 1] else nextStability(state, rating, days)
        val difficulty = if (new) initialDifficulty(rating.grade) else nextDifficulty(state.difficulty, rating)

        val (phase, interval) = when {
            rating == Rating.AGAIN -> {
                val next = if (state.phase in setOf(ReviewPhase.REVIEW, ReviewPhase.RELEARNING)) {
                    ReviewPhase.RELEARNING
                } else {
                    ReviewPhase.LEARNING
                }
                next to Minute
            }
            rating == Rating.HARD && state.phase != ReviewPhase.REVIEW -> {
                val next = if (state.phase == ReviewPhase.RELEARNING) state.phase else ReviewPhase.LEARNING
                next to Day / 2
            }
            new -> ReviewPhase.REVIEW to 4 * Day
            else -> {
                // At 90% retention the interval in days equals stability by definition.
                val intervalDays = stability.roundToInt().coerceIn(1, MaxDays)
                ReviewPhase.REVIEW to intervalDays * Day
            }
        }

        return ReviewState(
            dueAt = now + interval,
            lastReviewAt = now,
            stability = stability,
            difficulty = difficulty,
            reviews = state.reviews + 1,
            lapses = state.lapses + if (state.phase == ReviewPhase.REVIEW && rating == Rating.AGAIN) 1 else 0,
            phase = phase,
        )
    }

    private fun initialDifficulty(grade: Int): Double =
        (weights[4] - exp(weights[5] * (grade - 1)) + 1).coerceIn(1.0, 10.0)

    private fun nextDifficulty(value: Double, rating: Rating): Double {
        val change = -weights[6] * (rating.grade - 3) * (10 - value) / 9
        val reverted = weights[7] * initialDifficulty(4) + (1 - weights[7]) * (value + change)
        return reverted.coerceIn(1.0, 10.0)
    }

    private fun nextStability(state: ReviewState, rating: Rating, days: Long): Double {
        val s = state.stability
        val d = state.difficulty
        if (days == 0L) {
            val change = exp(weights[17] * (rating.grade - 3 + weights[18])) * s.pow(-weights[19])
            return (s * if (rating == Rating.GOOD) change.coerceAtLeast(1.0) else change)
                .coerceIn(0.001, MaxDays.toDouble())
        }

        val recall = (1 + factor * days / s).pow(decay)
        val next = if (rating == Rating.AGAIN) {
            val forgotten = weights[11] * d.pow(-weights[12]) *
                ((s + 1).pow(weights[13]) - 1) * exp((1 - recall) * weights[14])
            minOf(forgotten, s / exp(weights[17] * weights[18]))
        } else {
            val penalty = if (rating == Rating.HARD) weights[15] else 1.0
            s * (1 + exp(weights[8]) * (11 - d) * s.pow(-weights[9]) *
                (exp((1 - recall) * weights[10]) - 1) * penalty)
        }
        return next.coerceIn(0.001, MaxDays.toDouble())
    }
}
