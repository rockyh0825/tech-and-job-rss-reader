package dev.rockyh.rsswatch.shared.config

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/** RssItem(JSON 文字列)を送受信するための String key/value の Kafka 設定。 */
@Configuration
class KafkaConfig {

    @Bean
    fun stringKafkaTemplate(kafkaProperties: KafkaProperties): KafkaTemplate<String, String> {
        val props = kafkaProperties.buildProducerProperties(null)
        props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        return KafkaTemplate(DefaultKafkaProducerFactory(props))
    }

    /**
     * sink 用のバッチリスナーファクトリ。
     *
     * リスナーが例外を投げた場合はオフセットをコミットせず無限リトライする
     * (DB 一時障害はオフセット非コミット → 再配信で回復させる方針。design.md Error Scenario 3)。
     * デフォルトの DefaultErrorHandler はリトライ上限到達後にスキップしてコミットするため、
     * 取りこぼしが起きないよう上限なしに変更している。パースエラーは consumer 側で捨てるため
     * poison message でリトライが詰まることはない。
     */
    @Bean
    fun batchKafkaListenerContainerFactory(
        kafkaProperties: KafkaProperties,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val props = kafkaProperties.buildConsumerProperties(null)
        props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.consumerFactory = DefaultKafkaConsumerFactory(props)
        factory.isBatchListener = true
        factory.setCommonErrorHandler(
            DefaultErrorHandler(FixedBackOff(RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS)),
        )
        return factory
    }

    companion object {
        private const val RETRY_INTERVAL_MS = 1000L
    }
}
