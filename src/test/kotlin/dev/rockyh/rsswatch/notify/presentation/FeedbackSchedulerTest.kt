package dev.rockyh.rsswatch.notify.presentation

import dev.rockyh.rsswatch.notify.application.CollectFeedbackUseCase
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class FeedbackSchedulerTest {

    @Test
    fun delegates_to_collect_feedback_use_case() {
        val useCase = Mockito.mock(CollectFeedbackUseCase::class.java)
        val scheduler = FeedbackScheduler(useCase)

        scheduler.run()

        Mockito.verify(useCase).run()
    }
}
