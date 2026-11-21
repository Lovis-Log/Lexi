package io.github.lovislog.lexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSchedulerTest {
    private val t0 = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test
    fun newCardSchedulesOneLearningStepOrGraduates() {
        val card = ReviewState(t0)
        val forgotten = ReviewScheduler.rate(card, Rating.AGAIN, t0)
        assertEquals(ReviewPhase.LEARNING, forgotten.phase)
        assertEquals(t0 + 180_000L, forgotten.dueAt)
        assertEquals(0.212, forgotten.stability, 0.0001)
        assertEquals(0, forgotten.lapses)
        val hard = ReviewScheduler.rate(card, Rating.HARD, t0)
        assertEquals(t0 + 360_000L, hard.dueAt)
        val good = ReviewScheduler.rate(card, Rating.GOOD, t0)
        assertEquals(ReviewPhase.REVIEW, good.phase)
        assertEquals(t0 + 2 * day, good.dueAt)
        assertEquals(t0, good.lastReviewAt)
    }

    @Test
    fun lapseCountsOnceAndSuccessfulRelearningReturnsToReview() {
        val studied = ReviewScheduler.rate(ReviewState(t0), Rating.GOOD, t0)
        val lapse = ReviewScheduler.rate(studied, Rating.AGAIN, studied.dueAt)
        assertEquals(ReviewPhase.RELEARNING, lapse.phase)
        assertEquals(1, lapse.lapses)
        assertTrue(lapse.stability < studied.stability)
        val retry = ReviewScheduler.rate(lapse, Rating.AGAIN, lapse.dueAt)
        assertEquals(1, retry.lapses)
        val recovered = ReviewScheduler.rate(retry, Rating.GOOD, retry.dueAt)
        assertEquals(ReviewPhase.REVIEW, recovered.phase)
        assertEquals(4, recovered.reviews)
        assertTrue(recovered.isValid())
    }
}
