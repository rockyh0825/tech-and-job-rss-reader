package dev.rockyh.rsswatch.notify.presentation

import dev.rockyh.rsswatch.notify.application.BuildCncfDigestUseCase
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class CncfDigestSchedulerTest {

    @Test
    fun delegates_to_build_cncf_digest_use_case() {
        val useCase = Mockito.mock(BuildCncfDigestUseCase::class.java)
        val scheduler = CncfDigestScheduler(useCase)

        scheduler.run()

        Mockito.verify(useCase).run()
    }
}
