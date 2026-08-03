package dev.rockyh.rsswatch.notify.application

import dev.rockyh.rsswatch.notify.domain.DigestMessageStore
import dev.rockyh.rsswatch.notify.domain.DiscordFeedbackSource
import dev.rockyh.rsswatch.notify.domain.DiscordMessageRef
import dev.rockyh.rsswatch.notify.domain.DiscordReply
import dev.rockyh.rsswatch.notify.domain.FeedbackStore
import dev.rockyh.rsswatch.notify.domain.ReactionCount
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CollectFeedbackUseCaseTest {

    private val now: Instant = Instant.parse("2026-08-04T12:00:00Z")

    private class FakeMessageStore(private val messages: List<DiscordMessageRef>) : DigestMessageStore {
        var requestedSince: Instant? = null

        override fun record(messages: List<DiscordMessageRef>) = throw UnsupportedOperationException("not used")

        override fun messagesSince(since: Instant): List<DiscordMessageRef> {
            requestedSince = since
            return messages
        }
    }

    private class FakeFeedbackSource : DiscordFeedbackSource {
        val reactionsByMessage = mutableMapOf<String, List<ReactionCount>?>()
        val repliesByChannel = mutableMapOf<String, List<DiscordReply>>()
        val failReactionsFor = mutableSetOf<String>()
        val failRepliesFor = mutableSetOf<String>()
        val reactionLookups = mutableListOf<Pair<String, String>>()
        val replyLookups = mutableListOf<Pair<String, Set<String>>>()

        override fun reactions(channelId: String, messageId: String): List<ReactionCount>? {
            reactionLookups += channelId to messageId
            check(messageId !in failReactionsFor) { "reactions lookup failed for $messageId" }
            return reactionsByMessage.getOrDefault(messageId, emptyList())
        }

        override fun repliesTo(channelId: String, messageIds: Set<String>): List<DiscordReply> {
            replyLookups += channelId to messageIds
            check(channelId !in failRepliesFor) { "replies lookup failed for $channelId" }
            return repliesByChannel[channelId].orEmpty()
        }
    }

    private class FakeFeedbackStore : FeedbackStore {
        val replacedReactions = mutableMapOf<String, List<ReactionCount>>()
        val recordedReplies = mutableListOf<DiscordReply>()

        override fun replaceReactions(messageId: String, reactions: List<ReactionCount>) {
            replacedReactions[messageId] = reactions
        }

        override fun reactions(messageId: String): List<ReactionCount> = replacedReactions[messageId].orEmpty()

        override fun recordReplies(replies: List<DiscordReply>) {
            recordedReplies += replies
        }

        override fun repliesTo(messageId: String): List<DiscordReply> =
            recordedReplies.filter { it.referencedMessageId == messageId }
    }

    private fun message(guid: String, messageId: String, channelId: String = "ch-1"): DiscordMessageRef =
        DiscordMessageRef(guid = guid, channelId = channelId, messageId = messageId)

    private fun reply(replyMessageId: String, referencedMessageId: String): DiscordReply =
        DiscordReply(
            replyMessageId = replyMessageId,
            referencedMessageId = referencedMessageId,
            authorId = "user-1",
            authorName = "rocky",
            content = "あとで見る",
            repliedAt = Instant.parse("2026-08-04T09:00:00Z"),
        )

    private fun useCase(
        messageStore: FakeMessageStore,
        source: FakeFeedbackSource,
        store: FakeFeedbackStore,
        lookbackDays: Int = 7,
    ): CollectFeedbackUseCase =
        CollectFeedbackUseCase(
            messageStore = messageStore,
            feedbackSource = source,
            feedbackStore = store,
            lookbackDays = lookbackDays,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    @Test
    fun replaces_reaction_snapshots_for_every_tracked_message() {
        val messageStore = FakeMessageStore(listOf(message("g1", "m1"), message("g2", "m2")))
        val source =
            FakeFeedbackSource().apply {
                reactionsByMessage["m1"] = listOf(ReactionCount("👍", 2))
                reactionsByMessage["m2"] = emptyList()
            }
        val store = FakeFeedbackStore()

        useCase(messageStore, source, store).run()

        assertEquals(listOf(ReactionCount("👍", 2)), store.replacedReactions["m1"])
        // 0 件もスナップショットとして置き換える(全部取り消された状態が反映される)
        assertEquals(emptyList(), store.replacedReactions["m2"])
    }

    @Test
    fun records_replies_with_one_lookup_per_channel() {
        val messageStore =
            FakeMessageStore(
                listOf(message("g1", "m1"), message("g2", "m2"), message("g3", "m3", channelId = "ch-2")),
            )
        val source =
            FakeFeedbackSource().apply {
                repliesByChannel["ch-1"] = listOf(reply("r1", "m1"))
                repliesByChannel["ch-2"] = listOf(reply("r2", "m3"))
            }
        val store = FakeFeedbackStore()

        useCase(messageStore, source, store).run()

        // チャンネルごとに 1 回のまとめ照会(メッセージごとに叩かない)
        assertEquals(
            listOf("ch-1" to setOf("m1", "m2"), "ch-2" to setOf("m3")),
            source.replyLookups.sortedBy { it.first },
        )
        assertEquals(listOf(reply("r1", "m1"), reply("r2", "m3")), store.recordedReplies.sortedBy { it.replyMessageId })
    }

    @Test
    fun looks_back_only_the_configured_window() {
        val messageStore = FakeMessageStore(emptyList())

        useCase(messageStore, FakeFeedbackSource(), FakeFeedbackStore(), lookbackDays = 3).run()

        assertEquals(now.minus(Duration.ofDays(3)), messageStore.requestedSince)
    }

    @Test
    fun skips_the_reaction_snapshot_when_the_message_was_deleted() {
        // 削除済み(null)は「取れなかった」であって「0 件」ではないので、前回のスナップショットを消さない
        val messageStore = FakeMessageStore(listOf(message("g1", "m1")))
        val source = FakeFeedbackSource().apply { reactionsByMessage["m1"] = null }
        val store = FakeFeedbackStore()

        useCase(messageStore, source, store).run()

        assertFalse("m1" in store.replacedReactions)
    }

    @Test
    fun keeps_collecting_remaining_messages_when_one_reaction_lookup_fails() {
        val messageStore = FakeMessageStore(listOf(message("g1", "m1"), message("g2", "m2")))
        val source =
            FakeFeedbackSource().apply {
                failReactionsFor += "m1"
                reactionsByMessage["m2"] = listOf(ReactionCount("👍", 1))
            }
        val store = FakeFeedbackStore()

        useCase(messageStore, source, store).run()

        assertFalse("m1" in store.replacedReactions)
        assertEquals(listOf(ReactionCount("👍", 1)), store.replacedReactions["m2"])
    }

    @Test
    fun keeps_collecting_reactions_when_the_reply_lookup_fails() {
        val messageStore = FakeMessageStore(listOf(message("g1", "m1")))
        val source =
            FakeFeedbackSource().apply {
                failRepliesFor += "ch-1"
                reactionsByMessage["m1"] = listOf(ReactionCount("👍", 1))
            }
        val store = FakeFeedbackStore()

        useCase(messageStore, source, store).run()

        assertTrue(store.recordedReplies.isEmpty())
        assertEquals(listOf(ReactionCount("👍", 1)), store.replacedReactions["m1"])
    }

    @Test
    fun does_nothing_when_no_messages_are_tracked() {
        val source = FakeFeedbackSource()

        useCase(FakeMessageStore(emptyList()), source, FakeFeedbackStore()).run()

        assertTrue(source.reactionLookups.isEmpty())
        assertTrue(source.replyLookups.isEmpty())
    }
}
