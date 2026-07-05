package dev.rockyh.rsswatch.shared.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** @Scheduled(fetch の定期巡回)を有効にする。 */
@Configuration
@EnableScheduling
class SchedulingConfig
