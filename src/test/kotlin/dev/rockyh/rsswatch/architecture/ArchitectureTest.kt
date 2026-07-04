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
 * - feature 内のレイヤー依存は外側から内側への一方向のみ:
 *   - domain はプロジェクト内では同 feature の domain と shared.contract のみ import 可
 *   - presentation は同 feature の infrastructure を import しない
 *   - application は同 feature の presentation を import しない
 *   - infrastructure は同 feature の presentation・application を import しない
 */
class ArchitectureTest {

    companion object {
        private const val BASE_PACKAGE = "dev.rockyh.rsswatch"

        private val featurePackages = setOf("fetch", "keywords", "archive", "live", "report")

        private val layerPackages = setOf("presentation", "application", "domain", "infrastructure")

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

    @Test
    fun `domain files import project code only from same feature domain or shared contract`() {
        productionScope
            .files
            .forEach { file ->
                val (feature, layer) =
                    featureLayerOf(file.packagee?.name.orEmpty()) ?: return@forEach
                if (layer != "domain") return@forEach
                val violations =
                    file.imports.filter { import ->
                        import.name.startsWith("$BASE_PACKAGE.") &&
                            !import.name.startsWith("$BASE_PACKAGE.shared.contract.") &&
                            featureLayerOf(import.name) != (feature to "domain")
                    }
                assertTrue(violations.isEmpty()) {
                    "${file.name} (feature: $feature, layer: domain) may only import " +
                        "same-feature domain or shared.contract within the project, " +
                        "but imports: ${violations.map { it.name }}"
                }
            }
    }

    @Test
    fun `presentation files do not import same feature infrastructure`() {
        assertLayerDoesNotImport(sourceLayer = "presentation", forbiddenLayers = setOf("infrastructure"))
    }

    @Test
    fun `application files do not import same feature presentation`() {
        assertLayerDoesNotImport(sourceLayer = "application", forbiddenLayers = setOf("presentation"))
    }

    @Test
    fun `infrastructure files do not import same feature presentation or application`() {
        assertLayerDoesNotImport(
            sourceLayer = "infrastructure",
            forbiddenLayers = setOf("presentation", "application"),
        )
    }

    private fun assertLayerDoesNotImport(sourceLayer: String, forbiddenLayers: Set<String>) {
        productionScope
            .files
            .forEach { file ->
                val (feature, layer) =
                    featureLayerOf(file.packagee?.name.orEmpty()) ?: return@forEach
                if (layer != sourceLayer) return@forEach
                val violations =
                    file.imports.filter { import ->
                        val imported = featureLayerOf(import.name) ?: return@filter false
                        imported.first == feature && imported.second in forbiddenLayers
                    }
                assertTrue(violations.isEmpty()) {
                    "${file.name} (feature: $feature, layer: $sourceLayer) must not import " +
                        "$forbiddenLayers of the same feature (dependencies point inward only), " +
                        "but imports: ${violations.map { it.name }}"
                }
            }
    }

    private fun featureOf(fullyQualifiedName: String): String? {
        if (!fullyQualifiedName.startsWith("$BASE_PACKAGE.")) return null
        val firstSegment = fullyQualifiedName.removePrefix("$BASE_PACKAGE.").substringBefore(".")
        return firstSegment.takeIf { it in featurePackages }
    }

    /** `dev.rockyh.rsswatch.<feature>.<layer>` 形式の FQN から (feature, layer) を返す。 */
    private fun featureLayerOf(fullyQualifiedName: String): Pair<String, String>? {
        if (!fullyQualifiedName.startsWith("$BASE_PACKAGE.")) return null
        val segments = fullyQualifiedName.removePrefix("$BASE_PACKAGE.").split(".")
        if (segments.size < 2) return null
        val feature = segments[0].takeIf { it in featurePackages } ?: return null
        val layer = segments[1].takeIf { it in layerPackages } ?: return null
        return feature to layer
    }
}
