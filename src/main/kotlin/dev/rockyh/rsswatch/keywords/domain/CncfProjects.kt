package dev.rockyh.rsswatch.keywords.domain

import dev.rockyh.rsswatch.shared.contract.CncfMaturity

/**
 * CNCF プロジェクト辞書の 1 エントリ。
 *
 * @property name バッジ表示に使う正式名
 * @property maturity 成熟度
 * @property ignoreCaseAliases 大文字小文字を区別せずに照合する表記(通常はこちら)
 * @property exactCaseAliases 一般語と衝突する名前(Harbor / Helm / Envoy 等)のため、大文字小文字を完全一致で照合する表記
 */
data class CncfProject(
    val name: String,
    val maturity: CncfMaturity,
    val ignoreCaseAliases: List<String>,
    val exactCaseAliases: List<String>,
)

/**
 * CNCF プロジェクト辞書。graduated はほぼ全件、incubating / sandbox は注目プロジェクトの厳選。
 * 成熟度は 2026-07 時点の https://www.cncf.io/projects/ を基にした手書き管理で、
 * 昇格・アーカイブがあれば行を移す・消す(追加は 1 行足すだけでよい)。
 */
object CncfProjects {

    private fun project(maturity: CncfMaturity, name: String, vararg aliases: String) =
        CncfProject(
            name = name,
            maturity = maturity,
            ignoreCaseAliases = listOf(name) + aliases,
            exactCaseAliases = emptyList(),
        )

    /** `Harbor` のような一般語と衝突する名前用。正式名は完全一致、エイリアスは大文字小文字を区別しない。 */
    private fun exactNameProject(maturity: CncfMaturity, name: String, vararg ignoreCaseAliases: String) =
        CncfProject(
            name = name,
            maturity = maturity,
            ignoreCaseAliases = ignoreCaseAliases.toList(),
            exactCaseAliases = listOf(name),
        )

    val entries: List<CncfProject> =
        listOf(
            // ---- Graduated ----
            project(CncfMaturity.GRADUATED, "Kubernetes", "k8s"),
            project(CncfMaturity.GRADUATED, "Prometheus"),
            exactNameProject(CncfMaturity.GRADUATED, "Envoy", "Envoy Proxy"),
            project(CncfMaturity.GRADUATED, "CoreDNS"),
            project(CncfMaturity.GRADUATED, "containerd"),
            project(CncfMaturity.GRADUATED, "Fluentd", "Fluent Bit"),
            project(CncfMaturity.GRADUATED, "Jaeger"),
            project(CncfMaturity.GRADUATED, "Vitess"),
            exactNameProject(CncfMaturity.GRADUATED, "TUF", "The Update Framework"),
            exactNameProject(CncfMaturity.GRADUATED, "Helm"),
            exactNameProject(CncfMaturity.GRADUATED, "Harbor"),
            exactNameProject(CncfMaturity.GRADUATED, "Rook"),
            project(CncfMaturity.GRADUATED, "etcd"),
            CncfProject(
                name = "Open Policy Agent",
                maturity = CncfMaturity.GRADUATED,
                ignoreCaseAliases = listOf("Open Policy Agent"),
                exactCaseAliases = listOf("OPA"),
            ),
            project(CncfMaturity.GRADUATED, "CRI-O"),
            project(CncfMaturity.GRADUATED, "TiKV"),
            project(CncfMaturity.GRADUATED, "Linkerd"),
            exactNameProject(CncfMaturity.GRADUATED, "Argo", "ArgoCD", "Argo CD", "Argo Workflows", "Argo Rollouts", "Argo Events"),
            exactNameProject(CncfMaturity.GRADUATED, "Flux", "FluxCD", "Flux CD"),
            project(CncfMaturity.GRADUATED, "SPIFFE"),
            exactNameProject(CncfMaturity.GRADUATED, "SPIRE"),
            project(CncfMaturity.GRADUATED, "CloudEvents"),
            project(CncfMaturity.GRADUATED, "Cilium", "eBPF-based Cilium"),
            project(CncfMaturity.GRADUATED, "Istio"),
            project(CncfMaturity.GRADUATED, "KEDA"),
            project(CncfMaturity.GRADUATED, "CubeFS"),
            exactNameProject(CncfMaturity.GRADUATED, "Falco"),
            project(CncfMaturity.GRADUATED, "cert-manager"),
            exactNameProject(CncfMaturity.GRADUATED, "Dapr"),
            project(CncfMaturity.GRADUATED, "KubeEdge"),
            project(CncfMaturity.GRADUATED, "in-toto"),
            project(CncfMaturity.GRADUATED, "OpenTelemetry", "OTel"),
            // ---- Incubating ----
            exactNameProject(CncfMaturity.INCUBATING, "Backstage"),
            project(CncfMaturity.INCUBATING, "Crossplane"),
            project(CncfMaturity.INCUBATING, "Knative"),
            project(CncfMaturity.INCUBATING, "gRPC"),
            exactNameProject(CncfMaturity.INCUBATING, "NATS", "NATS.io"),
            exactNameProject(CncfMaturity.INCUBATING, "Thanos"),
            exactNameProject(CncfMaturity.INCUBATING, "Cortex"),
            exactNameProject(CncfMaturity.INCUBATING, "Contour"),
            project(CncfMaturity.INCUBATING, "Emissary-Ingress", "Emissary Ingress"),
            project(CncfMaturity.INCUBATING, "Operator Framework", "OperatorHub"),
            exactNameProject(CncfMaturity.INCUBATING, "Buildpacks", "Cloud Native Buildpacks"),
            project(CncfMaturity.INCUBATING, "Chaos Mesh", "ChaosMesh"),
            exactNameProject(CncfMaturity.INCUBATING, "Litmus", "LitmusChaos"),
            project(CncfMaturity.INCUBATING, "Kyverno"),
            exactNameProject(CncfMaturity.INCUBATING, "Longhorn"),
            project(CncfMaturity.INCUBATING, "Kubeflow"),
            project(CncfMaturity.INCUBATING, "KubeVela"),
            exactNameProject(CncfMaturity.INCUBATING, "Kuma"),
            project(CncfMaturity.INCUBATING, "OpenKruise"),
            project(CncfMaturity.INCUBATING, "OpenFeature"),
            project(CncfMaturity.INCUBATING, "Strimzi"),
            exactNameProject(CncfMaturity.INCUBATING, "Dragonfly", "DragonflyOSS"),
            exactNameProject(CncfMaturity.INCUBATING, "Volcano"),
            project(CncfMaturity.INCUBATING, "Karmada"),
            project(CncfMaturity.INCUBATING, "KubeVirt"),
            exactNameProject(CncfMaturity.INCUBATING, "Notary", "Notary Project", "Notation"),
            project(CncfMaturity.INCUBATING, "OpenCost"),
            project(CncfMaturity.INCUBATING, "wasmCloud"),
            project(CncfMaturity.INCUBATING, "OpenYurt"),
            project(CncfMaturity.INCUBATING, "CNI", "Container Network Interface"),
            project(CncfMaturity.INCUBATING, "Keycloak"),
            project(CncfMaturity.INCUBATING, "Kubescape"),
            project(CncfMaturity.INCUBATING, "KServe"),
            project(CncfMaturity.INCUBATING, "OpenFGA"),
            // ---- Sandbox(厳選)----
            project(CncfMaturity.SANDBOX, "WasmEdge"),
            project(CncfMaturity.SANDBOX, "k3s"),
            exactNameProject(CncfMaturity.SANDBOX, "Kepler"),
            project(CncfMaturity.SANDBOX, "KubeArmor"),
            project(CncfMaturity.SANDBOX, "Headlamp"),
            project(CncfMaturity.SANDBOX, "Perses"),
            project(CncfMaturity.SANDBOX, "Meshery"),
            project(CncfMaturity.SANDBOX, "KCL", "KCL Lang"),
            project(CncfMaturity.SANDBOX, "Kairos"),
            project(CncfMaturity.SANDBOX, "Kmesh"),
            project(CncfMaturity.SANDBOX, "youki"),
            project(CncfMaturity.SANDBOX, "KitOps"),
            project(CncfMaturity.SANDBOX, "KWOK"),
            CncfProject(
                name = "Copacetic",
                maturity = CncfMaturity.SANDBOX,
                // "copacetic" は英単語(「順調」)、"Copa" は Copa America 等と衝突するため両方 exact-case
                ignoreCaseAliases = emptyList(),
                exactCaseAliases = listOf("Copacetic", "Copa"),
            ),
            project(CncfMaturity.SANDBOX, "bpfman"),
            project(CncfMaturity.SANDBOX, "Spiderpool"),
            project(CncfMaturity.SANDBOX, "HAMi"),
            project(CncfMaturity.SANDBOX, "Sermant"),
            project(CncfMaturity.SANDBOX, "OpenFunction"),
            project(CncfMaturity.SANDBOX, "Podman Desktop"),
        )
}
