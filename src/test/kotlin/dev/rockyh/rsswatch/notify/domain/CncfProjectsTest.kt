package dev.rockyh.rsswatch.notify.domain

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class CncfProjectsTest {

    @Test
    fun project_names_are_unique() {
        val names = CncfProjects.entries.map { it.name }

        assertTrue(names.size == names.toSet().size, "project names must not duplicate")
    }

    @Test
    fun every_maturity_level_has_at_least_one_project() {
        val usedMaturities = CncfProjects.entries.map { it.maturity }.toSet()

        assertEquals(CncfMaturity.entries.toSet(), usedMaturities)
    }

    @Test
    fun every_project_has_at_least_one_alias() {
        val withoutAliases =
            CncfProjects.entries.filter { it.ignoreCaseAliases.isEmpty() && it.exactCaseAliases.isEmpty() }

        assertEquals(emptyList(), withoutAliases, "every project must be matchable")
    }

    @ParameterizedTest(name = "{0} は {1}")
    @CsvSource(
        "Kubernetes, GRADUATED",
        "Prometheus, GRADUATED",
        "Argo, GRADUATED",
        "OpenTelemetry, INCUBATING",
        "Backstage, INCUBATING",
        "WasmEdge, SANDBOX",
        "Headlamp, SANDBOX",
    )
    fun assigns_expected_maturity_to_representative_projects(name: String, expected: CncfMaturity) {
        val project = CncfProjects.entries.single { it.name == name }

        assertEquals(expected, project.maturity)
    }
}
