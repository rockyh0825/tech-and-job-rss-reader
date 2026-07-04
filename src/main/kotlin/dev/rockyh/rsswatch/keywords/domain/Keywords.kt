package dev.rockyh.rsswatch.keywords.domain

/**
 * 技術キーワード辞書の 1 分類。
 *
 * @property normalizedName 抽出結果として返す正規化名
 * @property ignoreCaseAliases 大文字小文字を区別せずに照合する表記(通常はこちら)
 * @property exactCaseAliases 一般語と衝突する短い名前のため、大文字小文字を完全一致で照合する表記
 */
data class KeywordEntry(
    val normalizedName: String,
    val ignoreCaseAliases: List<String>,
    val exactCaseAliases: List<String>,
)

/**
 * 「正規化名 + エイリアス」の技術キーワード辞書(約 60 分類)。
 * キーワードの追加は entry を 1 行足すだけでよい。
 */
object Keywords {

    private fun entry(normalizedName: String, vararg aliases: String) =
        KeywordEntry(
            normalizedName = normalizedName,
            ignoreCaseAliases = listOf(normalizedName) + aliases,
            exactCaseAliases = emptyList(),
        )

    /** `Go` のような一般語と衝突する名前用。正規化名は完全一致、エイリアスは大文字小文字を区別しない。 */
    private fun shortNameEntry(normalizedName: String, vararg ignoreCaseAliases: String) =
        KeywordEntry(
            normalizedName = normalizedName,
            ignoreCaseAliases = ignoreCaseAliases.toList(),
            exactCaseAliases = listOf(normalizedName),
        )

    val entries: List<KeywordEntry> =
        listOf(
            // 言語
            entry("Python"),
            entry("JavaScript", "JS"),
            entry("TypeScript", "TS"),
            entry("Java"),
            entry("Kotlin"),
            shortNameEntry("Go", "golang"),
            shortNameEntry("Rust"),
            entry("Ruby"),
            entry("PHP"),
            shortNameEntry("Swift"),
            entry("Scala"),
            entry("C++", "cpp"),
            entry("C#", "csharp"),
            entry("Elixir"),
            entry("Haskell"),
            shortNameEntry("Dart"),
            entry("Perl"),
            // フロントエンド
            entry("React", "React.js", "ReactJS"),
            entry("Vue.js", "Vue", "VueJS"),
            entry("Angular", "AngularJS"),
            entry("Next.js", "NextJS"),
            entry("Nuxt", "Nuxt.js", "NuxtJS"),
            entry("Svelte", "SvelteKit"),
            entry("Tailwind CSS", "Tailwind", "TailwindCSS"),
            entry("HTML", "HTML5"),
            entry("CSS", "CSS3"),
            // バックエンド
            entry("Spring Boot", "Spring", "spring-kafka"),
            entry("Django"),
            shortNameEntry("Flask"),
            entry("FastAPI"),
            entry("Ruby on Rails", "Rails", "RoR"),
            entry("Laravel"),
            entry("Node.js", "NodeJS", "Node"),
            entry("NestJS", "Nest.js"),
            entry(".NET", "dotnet", "ASP.NET"),
            entry("GraphQL"),
            entry("gRPC"),
            // モバイル
            entry("iOS"),
            entry("Android"),
            entry("Flutter"),
            entry("React Native", "ReactNative"),
            // データベース・ミドルウェア
            entry("MySQL"),
            entry("PostgreSQL", "Postgres"),
            entry("SQLite"),
            entry("MongoDB"),
            entry("Redis"),
            entry("Elasticsearch"),
            entry("Kafka", "Apache Kafka"),
            entry("RabbitMQ"),
            shortNameEntry("Spark", "Apache Spark"),
            entry("BigQuery"),
            entry("Snowflake"),
            // クラウド・インフラ
            entry("AWS", "Amazon Web Services"),
            entry("Google Cloud", "GCP", "Google Cloud Platform"),
            entry("Azure", "Microsoft Azure"),
            entry("Docker", "Docker Compose"),
            entry("Kubernetes", "k8s"),
            entry("Terraform"),
            entry("Ansible"),
            entry("GitHub Actions"),
            entry("Jenkins"),
            entry("Linux"),
            entry("Nginx"),
            // 機械学習・AI
            entry("機械学習", "Machine Learning"),
            entry("TensorFlow"),
            entry("PyTorch"),
            entry("LLM", "大規模言語モデル"),
        )
}
