package dev.rockyh.rsswatch.notify.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import dev.rockyh.rsswatch.notify.ConditionalOnAnyNotifyEnabled
import dev.rockyh.rsswatch.notify.domain.Summarizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/**
 * タイトル + 概要から日本語の簡潔な要約を生成する(infrastructure)。
 *
 * Claude Messages API(`POST {baseUrl}/v1/messages`)を Spring RestClient で直接呼ぶ(SDK 依存を増やさない)。
 * 既定モデルは再現性のため dated スナップショット `claude-haiku-4-5-20251001`。モデル・プロンプト・
 * max_tokens は application.yml から注入し、安全なデフォルトを持たせる(feature 有効時に値が欠けても起動を壊さない)。
 *
 * タイムアウト・レート制限(429)・API キー未設定・不正レスポンスは [Result.failure] にまとめ、
 * 呼び出し側(BuildDigestUseCase)が要約なしフォールバックできるようにする(要件 2.2)。
 */
@Component
@ConditionalOnAnyNotifyEnabled
class ClaudeSummarizer(
    restClientBuilder: RestClient.Builder,
    @Value("\${rss-watch.notify.claude.base-url:https://api.anthropic.com}") baseUrl: String,
    @Value("\${rss-watch.notify.claude.api-key:\${ANTHROPIC_API_KEY:}}") private val apiKey: String,
    @Value("\${rss-watch.notify.claude.model:claude-haiku-4-5-20251001}") private val model: String,
    @Value("\${rss-watch.notify.claude.max-tokens:256}") private val maxTokens: Int,
    @Value("\${rss-watch.notify.claude.system-prompt:$DEFAULT_SYSTEM_PROMPT}") private val systemPrompt: String,
) : Summarizer {

    private val restClient: RestClient = restClientBuilder.baseUrl(baseUrl).build()

    override fun summarize(title: String, summary: String): Result<String> =
        runCatching {
            val response =
                restClient
                    .post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .body(
                        MessagesRequest(
                            model = model,
                            maxTokens = maxTokens,
                            system = systemPrompt,
                            messages = listOf(Message(role = "user", content = "$title\n$summary")),
                        ),
                    ).retrieve()
                    .body<MessagesResponse>()
            response?.content?.firstOrNull()?.text
                ?: error("Claude response had no text content")
        }

    private data class MessagesRequest(
        val model: String,
        @get:JsonProperty("max_tokens") val maxTokens: Int,
        val system: String,
        val messages: List<Message>,
    )

    private data class Message(val role: String, val content: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MessagesResponse(val content: List<ContentBlock>)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ContentBlock(val text: String?)

    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
        // 見出しは通知側で「要約」に固定するため、本文には見出し・前置きを含めさせない。
        const val DEFAULT_SYSTEM_PROMPT =
            "あなたは技術記事を日本語で簡潔に要約するアシスタントです。" +
                "与えられたタイトルと概要の要点を1〜2文でまとめてください。" +
                "「要約」などの見出しや前置きは付けず、要約本文だけを出力すること。"
    }
}
