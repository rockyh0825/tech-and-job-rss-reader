package dev.rockyh.rsswatch.report.presentation

import dev.rockyh.rsswatch.report.application.BuildReportUseCase
import dev.rockyh.rsswatch.report.application.Report
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** クロスリンクレポートの集計 API(要件 5.1)。 */
@RestController
class ReportController(private val buildReportUseCase: BuildReportUseCase) {

    @GetMapping("/api/report")
    fun report(@RequestParam(defaultValue = "$DEFAULT_DAYS") days: Int): Report {
        if (days !in MIN_DAYS..MAX_DAYS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "days must be between $MIN_DAYS and $MAX_DAYS",
            )
        }
        return buildReportUseCase.build(days)
    }

    companion object {
        private const val DEFAULT_DAYS = 7
        private const val MIN_DAYS = 1
        private const val MAX_DAYS = 365
    }
}
