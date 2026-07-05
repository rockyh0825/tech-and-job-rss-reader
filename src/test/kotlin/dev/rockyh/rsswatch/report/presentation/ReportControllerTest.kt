package dev.rockyh.rsswatch.report.presentation

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.capabilities.TechMention
import dev.rockyh.rsswatch.report.application.BuildReportUseCase
import dev.rockyh.rsswatch.shared.contract.ItemCategory
import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ReportControllerTest {

    private class RecordingArchive : ArchiveQueryPort {
        val receivedDays = mutableListOf<Int>()

        override fun techRanking(days: Int): List<TechMention> {
            receivedDays += days
            return listOf(TechMention("Kotlin", 2))
        }

        override fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem> =
            listOf(rssItem("article-1"))

        override fun itemsByCategory(category: ItemCategory, days: Int): List<RssItem> =
            when (category) {
                ItemCategory.TECH -> listOf(rssItem("article-1"))
                ItemCategory.JOBS -> listOf(rssItem("job-1", category = "jobs"))
            }
    }

    private val archive = RecordingArchive()

    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(ReportController(BuildReportUseCase(archive)))
            .setMessageConverters(
                MappingJackson2HttpMessageConverter(
                    jacksonObjectMapper()
                        .registerModule(JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
                ),
            ).build()

    companion object {
        private fun rssItem(guid: String, category: String = "tech"): RssItem =
            RssItem(
                guid = guid,
                feedName = "feed",
                category = category,
                title = "title of $guid",
                url = "https://example.com/$guid",
                summary = "summary",
                publishedAt = Instant.parse("2026-07-01T00:00:00Z"),
                fetchedAt = Instant.parse("2026-07-01T00:00:00Z"),
                keywords = listOf("Kotlin"),
            )
    }

    @Test
    fun returns_cross_sections_tech_articles_and_job_postings() {
        mockMvc
            .perform(get("/api/report").param("days", "7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.crossSections[0].keyword").value("Kotlin"))
            .andExpect(jsonPath("$.crossSections[0].mentionCount").value(2))
            .andExpect(jsonPath("$.crossSections[0].articles[0].guid").value("article-1"))
            .andExpect(jsonPath("$.techArticles[0].guid").value("article-1"))
            .andExpect(jsonPath("$.jobPostings[0].guid").value("job-1"))
    }

    @Test
    fun defaults_to_7_days_when_days_is_omitted() {
        mockMvc
            .perform(get("/api/report"))
            .andExpect(status().isOk)

        assertEquals(listOf(7), archive.receivedDays)
    }

    @Test
    fun accepts_boundary_values_1_and_365() {
        mockMvc.perform(get("/api/report").param("days", "1")).andExpect(status().isOk)
        mockMvc.perform(get("/api/report").param("days", "365")).andExpect(status().isOk)
    }

    @Test
    fun rejects_days_of_zero_with_400() {
        mockMvc.perform(get("/api/report").param("days", "0")).andExpect(status().isBadRequest)
    }

    @Test
    fun rejects_days_over_365_with_400() {
        mockMvc.perform(get("/api/report").param("days", "366")).andExpect(status().isBadRequest)
    }

    @Test
    fun rejects_non_numeric_days_with_400() {
        mockMvc.perform(get("/api/report").param("days", "abc")).andExpect(status().isBadRequest)
    }
}
