package dev.rockyh.rsswatch.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * structure.md の依存ルールを強制する(.spec-workflow/steering/structure.md 参照)。
 *
 * - レイヤー配置: UseCase → application、Controller/Consumer/Scheduler → presentation、
 *   Port → capabilities、PortImpl → application
 * - domain は純 Kotlin(Spring・Kafka・Rome・SQLite に非依存)
 * - feature 間の直接 import は禁止(capabilities の Port 経由のみ)
 */
class ArchitectureTest {

    companion object {
        private const val BASE_PACKAGE = "dev.rockyh.rsswatch"

        private val featurePackages = setOf("fetch", "keywords", "archive", "live", "report")

        private val springStereotypeAnnotations =
            setOf(
                "Service",
                "Component",
                "Repository",
                "Controller",
                "RestController",
                "Configuration",
                "Autowired",
                "Scheduled",
                "KafkaListener",
            )

        private val domainForbiddenImportPrefixes =
            listOf(
                "org.springframework.",
                "org.apache.kafka.",
                "com.rometools.",
                "org.sqlite.",
                "java.sql.",
                "javax.sql.",
            )
    }

    private val productionScope = Konsist.scopeFromProduction()

    @Test
    fun `UseCase classes reside in application layer`() {
        productionScope
            .classes()
            .filter { it.name.endsWith("UseCase") }
            .forEach { koClass ->
                assertTrue(koClass.resideInPackage("..application..")) {
                    "${koClass.name} must reside in an application package, " +
                        "but is in ${koClass.packagee?.name}"
                }
            }
    }

    @Test
    fun `Controller Consumer and Scheduler classes reside in presentation layer`() {
        productionScope
            .classes()
            .filter {
                it.name.endsWith("Controller") ||
                    it.name.endsWith("Consumer") ||
                    it.name.endsWith("Scheduler")
            }
            .forEach { koClass ->
                assertTrue(koClass.resideInPackage("..presentation..")) {
                    "${koClass.name} must reside in a presentation package, " +
                        "but is in ${koClass.packagee?.name}"
                }
            }
    }

    @Test
    fun `Port interfaces reside in capabilities package`() {
        productionScope
            .interfaces()
            .filter { it.name.endsWith("Port") }
            .forEach { koInterface ->
                assertTrue(koInterface.resideInPackage("$BASE_PACKAGE.capabilities")) {
                    "${koInterface.name} must reside in $BASE_PACKAGE.capabilities, " +
                        "but is in ${koInterface.packagee?.name}"
                }
            }
    }

    @Test
    fun `PortImpl classes reside in application layer`() {
        productionScope
            .classes()
            .filter { it.name.endsWith("PortImpl") }
            .forEach { koClass ->
                assertTrue(koClass.resideInPackage("..application..")) {
                    "${koClass.name} must reside in an application package, " +
                        "but is in ${koClass.packagee?.name}"
                }
            }
    }

    @Test
    fun `domain classes do not use Spring or Kafka annotations`() {
        productionScope
            .classes()
            .filter { it.resideInPackage("..domain..") }
            .forEach { koClass ->
                val violations =
                    koClass.annotations.filter { it.name in springStereotypeAnnotations }
                assertTrue(violations.isEmpty()) {
                    "${koClass.name} is in a domain package and must not use " +
                        "framework annotations, but found: ${violations.map { it.name }}"
                }
            }
    }

    @Test
    fun `domain files do not import Spring Kafka Rome or SQLite`() {
        productionScope
            .files
            .filter { it.packagee?.name.orEmpty().contains(".domain") }
            .forEach { file ->
                val violations =
                    file.imports.filter { import ->
                        domainForbiddenImportPrefixes.any { import.name.startsWith(it) }
                    }
                assertTrue(violations.isEmpty()) {
                    "${file.name} is in a domain package and must not depend on " +
                        "Spring/Kafka/Rome/SQLite, but imports: ${violations.map { it.name }}"
                }
            }
    }

    @Test
    fun `features do not import other features directly`() {
        productionScope
            .files
            .forEach { file ->
                val ownFeature = featureOf(file.packagee?.name.orEmpty()) ?: return@forEach
                val violations =
                    file.imports.filter { import ->
                        val importedFeature = featureOf(import.name)
                        importedFeature != null && importedFeature != ownFeature
                    }
                assertTrue(violations.isEmpty()) {
                    "${file.name} (feature: $ownFeature) must not import other features " +
                        "directly (use capabilities Ports), but imports: ${violations.map { it.name }}"
                }
            }
    }

    private fun featureOf(fullyQualifiedName: String): String? {
        if (!fullyQualifiedName.startsWith("$BASE_PACKAGE.")) return null
        val firstSegment = fullyQualifiedName.removePrefix("$BASE_PACKAGE.").substringBefore(".")
        return firstSegment.takeIf { it in featurePackages }
    }
}
