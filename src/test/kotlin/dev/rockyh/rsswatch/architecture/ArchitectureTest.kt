package dev.rockyh.rsswatch.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * structure.md の依存ルールを自動強制するテスト。
 * Package by Feature + Layer within Feature、domain の純 Kotlin 維持、
 * feature 間の Port(capabilities)経由の依存を検証する。
 */
class ArchitectureTest {
    companion object {
        private const val BASE_PACKAGE = "dev.rockyh.rsswatch"

        private val features = setOf("fetch", "keywords", "archive", "live", "report")

        private val springStereotypeAnnotations =
            setOf("Service", "Component", "Repository", "Controller", "RestController", "Configuration")

        // domain が依存してはいけない外部ライブラリ(structure.md: 純 Kotlin)
        private val forbiddenDomainImportPrefixes =
            listOf(
                "org.springframework.",
                "org.apache.kafka.",
                "com.rometools.",
                "org.sqlite.",
                "java.sql.",
            )
    }

    @Test
    fun `UseCase suffix classes reside in application package`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .withNameEndingWith("UseCase")
            .assertTrue { it.resideInPackage("..application..") }
    }

    @Test
    fun `Controller suffix classes reside in presentation package`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .withNameEndingWith("Controller")
            .assertTrue { it.resideInPackage("..presentation..") }
    }

    @Test
    fun `Consumer suffix classes reside in presentation package`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .withNameEndingWith("Consumer")
            .assertTrue { it.resideInPackage("..presentation..") }
    }

    @Test
    fun `Port suffix interfaces reside in capabilities package`() {
        Konsist
            .scopeFromProduction()
            .interfaces()
            .withNameEndingWith("Port")
            .assertTrue { it.resideInPackage("$BASE_PACKAGE.capabilities") }
    }

    @Test
    fun `PortImpl suffix classes reside in application package`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .withNameEndingWith("PortImpl")
            .assertTrue { it.resideInPackage("..application..") }
    }

    @Test
    fun `domain classes do not use Spring stereotype annotations`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .withPackage("..domain..")
            .assertFalse { koClass ->
                koClass.annotations.any { it.name in springStereotypeAnnotations }
            }
    }

    @Test
    fun `domain files do not import Spring, Kafka, Rome or SQLite`() {
        Konsist
            .scopeFromProduction()
            .files
            .withPackage("..domain..")
            .assertFalse { file ->
                file.imports.any { import ->
                    forbiddenDomainImportPrefixes.any { prefix -> import.name.startsWith(prefix) }
                }
            }
    }

    @Test
    fun `features do not import other features directly`() {
        features.forEach { feature ->
            val otherFeatures = features - feature
            Konsist
                .scopeFromProduction()
                .files
                .withPackage("$BASE_PACKAGE.$feature..")
                .assertFalse(testName = "feature $feature does not import other features") { file ->
                    file.imports.any { import ->
                        otherFeatures.any { other -> import.name.startsWith("$BASE_PACKAGE.$other.") }
                    }
                }
        }
    }
}
