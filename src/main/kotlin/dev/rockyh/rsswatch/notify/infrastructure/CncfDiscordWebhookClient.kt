package dev.rockyh.rsswatch.notify.infrastructure

import dev.rockyh.rsswatch.notify.ConditionalOnCncfNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.CncfDigestEntry
import dev.rockyh.rsswatch.notify.domain.CncfDigestPublisher
import dev.rockyh.rsswatch.shared.contract.CncfMention
import dev.rockyh.rsswatch.notify.domain.PostOutcome
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * CNCF ダイジェストを専用チャンネルの Discord Webhook へ POST する(infrastructure)。
 *
 * embed の組み立てだけを担い、投稿の transport(リトライ・レート制限・打ち切り分類・導線送信)は
 * [DiscordPoster] に委譲する。投稿のセマンティクスとエラーの扱いは [DiscordPoster] の kdoc を参照。
 *
 * author 行は言及プロジェクトの成熟度バッジ(例: 「🌱 Sandbox: Kepler ・ OpenTelemetry, Kubernetes」。
 * バッジは最も成熟度が低いプロジェクト、続けて残りを最大 [MAX_EXTRA_PROJECT_NAMES] 件併記)。
 * 言及なしの記事は「☸️ CNCF」。導線は CNCF プロジェクト一覧([ctaUrl])へ飛ばす。
 */
@Component
@ConditionalOnCncfNotifyEnabled
class CncfDiscordWebhookClient(
    restClientBuilder: RestClient.Builder,
    @Value("\${rss-watch.notify.cncf.discord-webhook-url:}") private val webhookUrl: String,
    @Value("\${rss-watch.notify.cncf.cta-url:https://www.cncf.io/projects/}") private val ctaUrl: String,
    @Value("\${rss-watch.notify.discord.max-retries:2}") maxRetries: Int,
    sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : CncfDigestPublisher {

    private val poster = DiscordPoster(restClientBuilder, webhookUrl, maxRetries, sleeper)

    override fun post(entries: List<CncfDigestEntry>): PostOutcome {
        if (webhookUrl.isBlank()) {
            log.warn("CNCF 用 Discord Webhook URL が空のため投稿をスキップします。RSS_WATCH_NOTIFY_CNCF_DISCORD_WEBHOOK_URL を設定してください。")
            return PostOutcome(emptyList(), IllegalStateException("CNCF 用 Discord Webhook URL が設定されていません(空文字)"))
        }

        return poster.postArticles(
            posts =
                entries.map { entry ->
                    DiscordPoster.ArticlePost(guid = entry.article.guid, embed = toEmbed(entry))
                },
            ctaEmbed = ctaEmbed(),
        )
    }

    /**
     * 記事 1 件を embed に変換する。author に成熟度バッジ、field は見出しを「要約」に固定して
     * AI 要約本文を載せる(要約なしは field ごと省く)。記事の OGP 画像は thumbnail に載せる。
     */
    private fun toEmbed(entry: CncfDigestEntry): DiscordPoster.Embed =
        DiscordPoster.Embed(
            author = DiscordPoster.EmbedAuthor(name = authorLabel(entry.mentions).clampTo(DiscordPoster.MAX_AUTHOR_NAME_LENGTH)),
            title = entry.article.title.clampTo(DiscordPoster.MAX_TITLE_LENGTH),
            url = entry.article.url,
            description = null,
            thumbnail = entry.article.thumbnailUrl?.let { DiscordPoster.EmbedThumbnail(url = it) },
            // 要約が空白のみの場合も field ごと省く。Discord は field value 空(0 文字)を 400 で弾くため。
            fields =
                entry.article.summary
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
     * 成熟度バッジの見出し文言。[mentions] は成熟度の低い順に整列済み([CncfProjectMatcher] 参照)なので、
     * 先頭 = 記事の tier を決めたプロジェクトをバッジにし、残りを最大 [MAX_EXTRA_PROJECT_NAMES] 件併記する。
     */
    private fun authorLabel(mentions: List<CncfMention>): String {
        val primary = mentions.firstOrNull() ?: return NO_MENTION_LABEL
        val badge = "${primary.maturity.emoji} ${primary.maturity.label}: ${primary.projectName}"
        val extras = mentions.drop(1).take(MAX_EXTRA_PROJECT_NAMES).joinToString(", ") { it.projectName }
        return if (extras.isEmpty()) badge else "$badge ・ $extras"
    }

    /** 最後に単独で送る、CNCF プロジェクト一覧への導線 embed。 */
    private fun ctaEmbed(): DiscordPoster.Embed =
        DiscordPoster.Embed(
            author = null,
            title = CTA_TITLE,
            url = ctaUrl,
            description = null,
            thumbnail = null,
            fields = null,
        )

    companion object {
        private val log = LoggerFactory.getLogger(CncfDiscordWebhookClient::class.java)

        /** 要約 field の見出し(既存ダイジェストと同じ固定文言)。 */
        private const val SUMMARY_FIELD_NAME = "要約"

        /** プロジェクト言及が無い記事の見出し。 */
        private const val NO_MENTION_LABEL = "☸️ CNCF"

        /** バッジに続けて併記する残りプロジェクト名の上限。 */
        private const val MAX_EXTRA_PROJECT_NAMES = 2

        /** 最後に単独で送る導線のタイトル(CNCF プロジェクト一覧へのリンク)。 */
        private const val CTA_TITLE = "🔗 CNCF プロジェクトの成熟度一覧を見る"
    }
}
