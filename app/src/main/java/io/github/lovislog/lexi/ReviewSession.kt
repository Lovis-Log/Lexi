package io.github.lovislog.lexi

import kotlin.random.Random

internal data class ReviewQueueEntry(val id: String, val requeueCount: Int = 0, val expectedReviews: Int = 0)

internal data class ReviewSession(
    val dayStart: Long = 0,
    val sessionQueue: List<ReviewQueueEntry> = emptyList(),
    private val completed: Set<String> = emptySet(),
) {
    fun sync(day: ReviewDay, active: Set<String>, due: List<SavedCard>): ReviewSession {
        val today = if (dayStart == day.start) this else ReviewSession(day.start)
        val remaining = today.sessionQueue.filter { it.id in active }
        val queued = remaining.mapTo(mutableSetOf()) { it.id }
        val newcomers = due.filter { it.id !in queued && it.id !in today.completed }
            .map { ReviewQueueEntry(it.id, expectedReviews = it.schedule.reviews) }
        return today.copy(sessionQueue = remaining + newcomers)
    }

    fun focus(id: String): ReviewSession {
        val entry = sessionQueue.firstOrNull { it.id == id } ?: return this
        return copy(sessionQueue = listOf(entry) + sessionQueue.filterNot { it.id == id })
    }

    fun rated(id: String, reviews: Int, rating: Rating, random: Random = Random.Default): ReviewSession {
        val position = sessionQueue.indexOfFirst { it.id == id }
        if (position == -1) return this
        val next = sessionQueue.toMutableList()
        val current = next.removeAt(position)
        if (rating == Rating.GOOD) return copy(sessionQueue = next, completed = completed + id)
        val distance = when (rating) {
            Rating.AGAIN -> random.nextInt(2, 4)
            Rating.HARD -> random.nextInt(5, 7)
            Rating.GOOD -> error("A completed card leaves the queue.")
        }
        next.add((position + distance).coerceAtMost(next.size),
            current.copy(requeueCount = current.requeueCount + 1, expectedReviews = reviews))
        return copy(sessionQueue = next)
    }
}