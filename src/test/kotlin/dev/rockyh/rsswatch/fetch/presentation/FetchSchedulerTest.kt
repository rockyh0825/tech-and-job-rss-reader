package dev.rockyh.rsswatch.fetch.presentation

import dev.rockyh.rsswatch.fetch.application.FetchFeedsUseCase
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class FetchSchedulerTest {

    @Test
    fun delegates_to_fetch_feeds_use_case() {
        val useCase = Mockito.mock(FetchFeedsUseCase::class.java)
        val scheduler = FetchScheduler(useCase)

        scheduler.run()

        Mockito.verify(useCase).fetchAll()
    }
}
