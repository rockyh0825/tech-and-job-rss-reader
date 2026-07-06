package dev.rockyh.rsswatch.notify.presentation

import dev.rockyh.rsswatch.notify.application.BuildDigestUseCase
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class DigestSchedulerTest {

    @Test
    fun delegates_to_build_digest_use_case() {
        val useCase = Mockito.mock(BuildDigestUseCase::class.java)
        val scheduler = DigestScheduler(useCase)

        scheduler.run()

        Mockito.verify(useCase).run()
    }
}
