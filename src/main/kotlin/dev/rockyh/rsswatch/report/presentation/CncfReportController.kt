package dev.rockyh.rsswatch.report.presentation

import dev.rockyh.rsswatch.report.application.BuildCncfReportUseCase
import dev.rockyh.rsswatch.report.application.CncfReport
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** CNCF レポート API(cncf-web-view spec 要件 1.1)。バリデーションは /api/report と同じ規約。 */
@RestController
class CncfReportController(private val buildCncfReportUseCase: BuildCncfReportUseCase) {

    @GetMapping("/api/cncf")
    fun cncf(@RequestParam(defaultValue = "$DEFAULT_DAYS") days: Int): CncfReport {
        if (days !in MIN_DAYS..MAX_DAYS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "days must be between $MIN_DAYS and $MAX_DAYS",
            )
        }
        return buildCncfReportUseCase.build(days)
    }

    companion object {
        private const val DEFAULT_DAYS = 7
        private const val MIN_DAYS = 1
        private const val MAX_DAYS = 365
    }
}
