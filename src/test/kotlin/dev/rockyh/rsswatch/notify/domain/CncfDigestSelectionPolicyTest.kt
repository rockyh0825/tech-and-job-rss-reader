package dev.rockyh.rsswatch.notify.domain

import dev.rockyh.rsswatch.shared.contract.RssItem
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class CncfDigestSelectionPolicyTest {

    private val policy = CncfDigestSelectionPolicy()

    private fun item(guid: String, publishedAt: String): RssItem =
        RssItem(
            guid = guid,
            feedName = "CNCF Blog",
            category = "cncf",
            title = "title of $guid",
            url = "https://example.com/$guid",
            summary = "summary",
            publishedAt = Instant.parse(publishedAt),
            fetchedAt = Instant.parse(publishedAt),
            keywords = emptyList(),
        )

    private fun mention(maturity: CncfMaturity, name: String = maturity.name.lowercase()) =
        CncfMention(name, maturity)

    @Test
    fun orders_by_lowest_maturity_tier_sandbox_first_then_no_mention_last() {
        val noMention = CncfCandidate(item("no-mention", "2026-07-19T00:00:00Z"), emptyList())
        val graduated = CncfCandidate(item("graduated", "2026-07-19T00:00:00Z"), listOf(mention(CncfMaturity.GRADUATED)))
        val incubating = CncfCandidate(item("incubating", "2026-07-19T00:00:00Z"), listOf(mention(CncfMaturity.INCUBATING)))
        val sandbox = CncfCandidate(item("sandbox", "2026-07-19T00:00:00Z"), listOf(mention(CncfMaturity.SANDBOX)))

        val selected = policy.select(listOf(noMention, graduated, incubating, sandbox), limit = 10)

        assertEquals(listOf(sandbox, incubating, graduated, noMention), selected)
    }

    @Test
    fun tier_is_decided_by_the_lowest_maturity_among_mentions() {
        val sandboxAndGraduated =
            CncfCandidate(
                item("mixed", "2026-07-18T00:00:00Z"),
                listOf(mention(CncfMaturity.SANDBOX), mention(CncfMaturity.GRADUATED)),
            )
        val incubatingOnly =
            CncfCandidate(item("incubating", "2026-07-19T00:00:00Z"), listOf(mention(CncfMaturity.INCUBATING)))

        val selected = policy.select(listOf(incubatingOnly, sandboxAndGraduated), limit = 10)

        assertEquals(listOf(sandboxAndGraduated, incubatingOnly), selected)
    }

    @Test
    fun orders_newest_first_within_the_same_tier() {
        val older = CncfCandidate(item("older", "2026-07-17T00:00:00Z"), listOf(mention(CncfMaturity.GRADUATED)))
        val newer = CncfCandidate(item("newer", "2026-07-19T00:00:00Z"), listOf(mention(CncfMaturity.GRADUATED)))

        val selected = policy.select(listOf(older, newer), limit = 10)

        assertEquals(listOf(newer, older), selected)
    }

    @Test
    fun breaks_ties_deterministically_by_guid() {
        val b = CncfCandidate(item("b", "2026-07-19T00:00:00Z"), emptyList())
        val a = CncfCandidate(item("a", "2026-07-19T00:00:00Z"), emptyList())

        val selected = policy.select(listOf(b, a), limit = 10)

        assertEquals(listOf(a, b), selected)
    }

    @Test
    fun caps_the_result_at_limit_after_ordering() {
        val graduated = CncfCandidate(item("graduated", "2026-07-19T00:00:00Z"), listOf(mention(CncfMaturity.GRADUATED)))
        val sandbox = CncfCandidate(item("sandbox", "2026-07-17T00:00:00Z"), listOf(mention(CncfMaturity.SANDBOX)))
        val incubating = CncfCandidate(item("incubating", "2026-07-18T00:00:00Z"), listOf(mention(CncfMaturity.INCUBATING)))

        val selected = policy.select(listOf(graduated, sandbox, incubating), limit = 2)

        assertEquals(listOf(sandbox, incubating), selected)
    }

    @Test
    fun returns_empty_for_no_candidates() {
        assertEquals(emptyList(), policy.select(emptyList(), limit = 5))
    }
}
