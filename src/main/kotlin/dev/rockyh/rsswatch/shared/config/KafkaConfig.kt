package dev.rockyh.rsswatch.shared.config

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate

/** RssItem(JSON 文字列)を送るための String key/value の KafkaTemplate。 */
@Configuration
class KafkaConfig {

    @Bean
    fun stringKafkaTemplate(kafkaProperties: KafkaProperties): KafkaTemplate<String, String> {
        val props = kafkaProperties.buildProducerProperties(null)
        props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        return KafkaTemplate(DefaultKafkaProducerFactory(props))
    }
}
