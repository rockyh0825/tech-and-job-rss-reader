package dev.rockyh.rsswatch.notify.infrastructure

import dev.rockyh.rsswatch.notify.ConditionalOnNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.DigestArticle
import dev.rockyh.rsswatch.notify.domain.DigestPublisher
import dev.rockyh.rsswatch.notify.domain.PostOutcome
import dev.rockyh.rsswatch.notify.domain.TechDigest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * 「求人で言及された技術 × その技術の記事」を Discord Webhook へ POST する(infrastructure)。
 *
 * embed の組み立て(author に技術名 + 求人言及数、title に記事タイトル+URL、field「要約」に AI 要約)だけを
 * 担い、投稿の transport(リトライ・レート制限・打ち切り分類・導線送信)は [DiscordPoster] に委譲する。
 * 投稿のセマンティクスとエラーの扱いは [DiscordPoster] の kdoc を参照。
 */
@Component
@ConditionalOnNotifyEnabled
class DiscordWebhookClient(
    restClientBuilder: RestClient.Builder,
    @Value("\${rss-watch.notify.discord-webhook-url:}") private val webhookUrl: String,
    @Value("\${rss-watch.notify.site-url:$DEFAULT_SITE_URL}") private val siteUrl: String,
    @Value("\${rss-watch.notify.discord.max-retries:2}") maxRetries: Int,
    sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : DigestPublisher {

    private val poster = DiscordPoster(restClientBuilder, webhookUrl, maxRetries, sleeper)

    override fun post(digests: List<TechDigest>): PostOutcome {
        blankWebhookUrlOutcome()?.let { return it }

        return poster.postArticles(
            posts =
                digests.toArticlePosts().map { (digest, article) ->
                    DiscordPoster.ArticlePost(guid = article.guid, embed = toEmbed(digest, article))
                },
            ctaEmbed = siteCtaEmbed(siteUrl),
        )
    }

    override fun postCtaOnly(): PostOutcome {
        blankWebhookUrlOutcome()?.let { return it }
        return poster.postCtaOnly(listOf(siteCtaEmbed(siteUrl)))
    }

    /** Webhook URL 未設定なら warn して失敗の [PostOutcome] を返す(設定済みなら null)。 */
    private fun blankWebhookUrlOutcome(): PostOutcome? {
        if (webhookUrl.isNotBlank()) return null
        log.warn("Discord Webhook URL が空のため投稿をスキップします。RSS_WATCH_NOTIFY_DISCORD_WEBHOOK_URL を設定してください。")
        return PostOutcome(emptyList(), IllegalStateException("Discord Webhook URL が設定されていません(空文字)"))
    }

    /**
     * 技術グループを平坦化し、投稿 1 通ぶんの単位(所属技術と記事の組)に並べ直す。
     * 順序は技術ランキング順・記事の新しい順で、そのまま投稿順になる。
     */
    private fun List<TechDigest>.toArticlePosts(): List<Pair<TechDigest, DigestArticle>> =
        flatMap { digest -> digest.articles.map { article -> digest to article } }

    /**
     * 記事 1 件を embed に変換する。author に技術グループの見出し(技術名 + 求人言及数)、field は見出しを
     * 「要約」に固定して AI 要約本文を載せる(要約なしは field ごと省く)。記事の OGP 画像は
     * thumbnail(右上の小さい画像)に載せる(解決できていなければ省く)。
     */
    private fun toEmbed(digest: TechDigest, article: DigestArticle): DiscordPoster.Embed =
        DiscordPoster.Embed(
            author = DiscordPoster.EmbedAuthor(name = authorLabel(digest).clampTo(DiscordPoster.MAX_AUTHOR_NAME_LENGTH)),
            title = article.title.clampTo(DiscordPoster.MAX_TITLE_LENGTH),
            url = article.url,
            description = null,
            thumbnail = article.thumbnailUrl?.let { DiscordPoster.EmbedThumbnail(url = it) },
            // 要約が空白のみの場合も field ごと省く。Discord は field value 空(0 文字)を 400 で弾くため。
            fields =
                article.summary
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        listOf(
                            DiscordPoster.EmbedField(
                                name = SUMMARY_FIELD_NAME,
                                value = it.clampTo(DiscordPoster.MAX_FIELD_VALUE_LENGTH),
                            ),
                        )
                    },
        )

    /**
     * 技術グループの見出し文言(例: 「🧩 Kotlin ・ 求人 5 件で言及」)。
     * 興味技術は ⭐、求人に出ていない技術(mentionCount=0)は「求人 0 件で言及」の代わりに「新着記事」。
     */
    private fun authorLabel(digest: TechDigest): String {
        val icon = if (digest.interested) "⭐" else "🧩"
        val suffix = if (digest.mentionCount > 0) "求人 ${digest.mentionCount} 件で言及" else "新着記事"
        return "$icon ${digest.keyword} ・ $suffix"
    }

    companion object {
        private val log = LoggerFactory.getLogger(DiscordWebhookClient::class.java)

        /** 要約 field の見出し(常にこの固定文言。モデル生成の見出しは使わない)。 */
        private const val SUMMARY_FIELD_NAME = "要約"
    }
}
