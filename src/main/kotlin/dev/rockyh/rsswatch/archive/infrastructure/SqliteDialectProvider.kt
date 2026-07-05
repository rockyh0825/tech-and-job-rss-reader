package dev.rockyh.rsswatch.archive.infrastructure

import java.sql.Connection
import java.util.Optional
import org.springframework.data.jdbc.core.dialect.DialectResolver
import org.springframework.data.relational.core.dialect.AnsiDialect
import org.springframework.data.relational.core.dialect.Dialect
import org.springframework.jdbc.core.JdbcOperations

/**
 * spring-data-jdbc は SQLite の Dialect を同梱しないため、SPI で補う
 * (META-INF/spring.factories で登録)。SQLite は ANSI SQL に近いので AnsiDialect で代用する。
 */
class SqliteDialectProvider : DialectResolver.JdbcDialectProvider {

    override fun getDialect(operations: JdbcOperations): Optional<Dialect> {
        val productName =
            operations.execute { connection: Connection -> connection.metaData.databaseProductName }
        return if (productName.orEmpty().contains("SQLite", ignoreCase = true)) {
            Optional.of(AnsiDialect.INSTANCE)
        } else {
            Optional.empty()
        }
    }
}
