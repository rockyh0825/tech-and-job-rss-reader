package dev.rockyh.rsswatch.notify.infrastructure

import dev.rockyh.rsswatch.notify.domain.DiscordReply
import dev.rockyh.rsswatch.notify.domain.ReactionCount
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class DiscordBotClientTest {

    private val apiBaseUrl = "https://discord.example/api/v10"
    private val botToken = "token-x"

    private lateinit var builder: RestClient.Builder
    private lateinit var server: MockRestServiceServer

    private fun client(): DiscordBotClient =
        DiscordBotClient(restClientBuilder = builder, botToken = botToken, apiBaseUrl = apiBaseUrl)

    @BeforeEach
    fun setUp() {
        builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
    }

    @Test
    fun reactions_returns_emoji_counts_from_the_message() {
        server
            .expect(requestTo("$apiBaseUrl/channels/ch1/messages/m1"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bot $botToken"))
            .andRespond(
                withSuccess(
                    """
                    {
                      "id": "m1",
                      "channel_id": "ch1",
                      "reactions": [
                        {"emoji": {"id": null, "name": "👍"}, "count": 2},
                        {"emoji": {"id": "123456", "name": "custom_yay"}, "count": 1}
                      ]
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val reactions = client().reactions("ch1", "m1")

        // カスタム絵文字は名前だけだと別サーバーの同名絵文字と衝突するので name:id で識別する
        assertEquals(listOf(ReactionCount("👍", 2), ReactionCount("custom_yay:123456", 1)), reactions)
        server.verify()
    }

    @Test
    fun reactions_returns_empty_list_when_the_message_has_no_reactions() {
        // Discord はリアクション 0 件のとき reactions フィールド自体を返さない
        server
            .expect(requestTo("$apiBaseUrl/channels/ch1/messages/m1"))
            .andRespond(withSuccess("""{"id": "m1", "channel_id": "ch1"}""", MediaType.APPLICATION_JSON))

        assertEquals(emptyList(), client().reactions("ch1", "m1"))
        server.verify()
    }

    @Test
    fun reactions_returns_null_when_the_message_is_gone() {
        // 404 = メッセージが削除済み。呼び出し側が追跡をやめられるように null で区別する
        server
            .expect(requestTo("$apiBaseUrl/channels/ch1/messages/m1"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertNull(client().reactions("ch1", "m1"))
        server.verify()
    }

    @Test
    fun replies_to_returns_replies_referencing_the_given_messages() {
        server
            .expect(requestTo("$apiBaseUrl/channels/ch1/messages?limit=100"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bot $botToken"))
            .andRespond(
                withSuccess(
                    """
                    [
                      {
                        "id": "r1",
                        "content": "あとで見る",
                        "timestamp": "2026-08-04T09:00:00.000000+00:00",
                        "author": {"id": "user-1", "username": "rocky"},
                        "message_reference": {"message_id": "m1", "channel_id": "ch1"}
                      },
                      {
                        "id": "plain",
                        "content": "ただの雑談",
                        "timestamp": "2026-08-04T09:01:00.000000+00:00",
                        "author": {"id": "user-1", "username": "rocky"}
                      },
                      {
                        "id": "r2",
                        "content": "他のメッセージへの返信",
                        "timestamp": "2026-08-04T09:02:00.000000+00:00",
                        "author": {"id": "user-1", "username": "rocky"},
                        "message_reference": {"message_id": "unknown", "channel_id": "ch1"}
                      },
                      {
                        "id": "r3",
                        "content": "bot の返信",
                        "timestamp": "2026-08-04T09:03:00.000000+00:00",
                        "author": {"id": "bot-1", "username": "some-bot", "bot": true},
                        "message_reference": {"message_id": "m1", "channel_id": "ch1"}
                      }
                    ]
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val replies = client().repliesTo("ch1", setOf("m1", "m9"))

        assertEquals(
            listOf(
                DiscordReply(
                    replyMessageId = "r1",
                    referencedMessageId = "m1",
                    authorId = "user-1",
                    authorName = "rocky",
                    content = "あとで見る",
                    repliedAt = Instant.parse("2026-08-04T09:00:00Z"),
                ),
            ),
            replies,
        )
        server.verify()
    }

    @Test
    fun replies_to_uses_empty_content_when_the_intent_is_disabled() {
        // Message Content Intent が無効だと content が空で届く。返信が付いた事実だけでも記録する
        server
            .expect(requestTo("$apiBaseUrl/channels/ch1/messages?limit=100"))
            .andRespond(
                withSuccess(
                    """
                    [
                      {
                        "id": "r1",
                        "timestamp": "2026-08-04T09:00:00.000000+00:00",
                        "author": {"id": "user-1", "username": "rocky"},
                        "message_reference": {"message_id": "m1", "channel_id": "ch1"}
                      }
                    ]
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val replies = client().repliesTo("ch1", setOf("m1"))

        assertEquals("", replies.single().content)
        server.verify()
    }
}
