package dev.rockyh.rsswatch

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class RssWatchApplication

fun main(args: Array<String>) {
    runApplication<RssWatchApplication>(*args)
}
