package dev.rockyh.rsswatch.shared.contract

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Instant

/**
 * Kafka topic `rss.items` のメッセージ契約。全 feature・将来のサービス間の契約であり、
 * フィールドは後方互換な追加のみ許可(削除・改名は不可。consumer が複数いるため)。
 * JSON のシリアライズ形式はこのファイルの 1 箇所で定義する。
 */
data class RssItem(
    val guid: String,
    val feedName: String,
    val category: String,
    val title: String,
    val url: String,
    val summary: String,
    val publishedAt: Instant?,
    val fetchedAt: Instant,
    val keywords: List<String>,
) {
    fun toJson(): String = objectMapper.writeValueAsString(this)

    companion object {
        private val objectMapper: ObjectMapper =
            jacksonObjectMapper()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

        fun fromJson(json: String): RssItem = objectMapper.readValue(json)
    }
}
