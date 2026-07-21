package dev.rockyh.rsswatch.report.presentation

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.rockyh.rsswatch.capabilities.ArchiveQueryPort
import dev.rockyh.rsswatch.capabilities.CncfMatchPort
import dev.rockyh.rsswatch.capabilities.PostedGuidQueryPort
import dev.rockyh.rsswatch.capabilities.TechMention
import dev.rockyh.rsswatch.report.application.BuildCncfReportUseCase
import dev.rockyh.rsswatch.shared.contract.CncfMaturity
import dev.rockyh.rsswatch.shared.contract.CncfMention
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

class CncfReportControllerTest {

    private class RecordingArchive : ArchiveQueryPort {
        val receivedDays = mutableListOf<Int>()

        override fun techRanking(days: Int): List<TechMention> = error("not used by the CNCF report")

        override fun itemsByKeyword(keyword: String, category: ItemCategory, days: Int): List<RssItem> =
            error("not used by the CNCF report")

        override fun itemsByKeywords(
            keywords: List<String>,
            category: ItemCategory,
            days: Int,
        ): Map<String, List<RssItem>> = error("not used by the CNCF report")

        override fun itemsByCategory(category: ItemCategory, days: Int): List<RssItem> {
            receivedDays += days
            return when (category) {
                ItemCategory.CNCF -> listOf(rssItem("cncf-1", title = "Kepler saves watts"))
                ItemCategory.TECH, ItemCategory.JOBS -> emptyList()
            }
        }
    }

    private class FakeCncfMatch : CncfMatchPort {
        override fun match(text: String): List<CncfMention> =
            if ("Kepler" in text) listOf(CncfMention("Kepler", CncfMaturity.SANDBOX)) else emptyList()
    }

    private class FakePostedGuids(private val posted: Set<String>) : PostedGuidQueryPort {
        override fun postedIn(guids: Set<String>): Set<String> = posted intersect guids
    }

    private val archive = RecordingArchive()

    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                CncfReportController(
                    BuildCncfReportUseCase(archive, FakeCncfMatch(), FakePostedGuids(setOf("cncf-1"))),
                ),
            )
            .setMessageConverters(
                MappingJackson2HttpMessageConverter(
                    jacksonObjectMapper()
                        .registerModule(JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
                ),
            ).build()

    companion object {
        private fun rssItem(guid: String, title: String): RssItem =
            RssItem(
                guid = guid,
                feedName = "CNCF Blog",
                category = "cncf",
                title = title,
                url = "https://example.com/$guid",
                summary = "summary",
                publishedAt = Instant.parse("2026-07-01T00:00:00Z"),
                fetchedAt = Instant.parse("2026-07-01T00:00:00Z"),
                keywords = emptyList(),
            )
    }

    @Test
    fun returns_cncf_articles_with_mentions_and_maturity() {
        mockMvc
            .perform(get("/api/cncf").param("days", "7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.articles[0].item.guid").value("cncf-1"))
            .andExpect(jsonPath("$.articles[0].item.feedName").value("CNCF Blog"))
            .andExpect(jsonPath("$.articles[0].mentions[0].projectName").value("Kepler"))
            .andExpect(jsonPath("$.articles[0].mentions[0].maturity").value("SANDBOX"))
            .andExpect(jsonPath("$.articles[0].tier").value("SANDBOX"))
    }

    @Test
    fun returns_posted_guids_for_articles_already_posted_to_discord() {
        mockMvc
            .perform(get("/api/cncf").param("days", "7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.postedGuids.length()").value(1))
            .andExpect(jsonPath("$.postedGuids[0]").value("cncf-1"))
    }

    @Test
    fun defaults_to_7_days_when_days_is_omitted() {
        mockMvc
            .perform(get("/api/cncf"))
            .andExpect(status().isOk)

        assertEquals(listOf(7), archive.receivedDays)
    }

    @Test
    fun accepts_boundary_values_1_and_365() {
        mockMvc.perform(get("/api/cncf").param("days", "1")).andExpect(status().isOk)
        mockMvc.perform(get("/api/cncf").param("days", "365")).andExpect(status().isOk)
    }

    @Test
    fun rejects_days_of_zero_with_400() {
        mockMvc.perform(get("/api/cncf").param("days", "0")).andExpect(status().isBadRequest)
    }

    @Test
    fun rejects_days_over_365_with_400() {
        mockMvc.perform(get("/api/cncf").param("days", "366")).andExpect(status().isBadRequest)
    }

    @Test
    fun rejects_non_numeric_days_with_400() {
        mockMvc.perform(get("/api/cncf").param("days", "abc")).andExpect(status().isBadRequest)
    }
}
