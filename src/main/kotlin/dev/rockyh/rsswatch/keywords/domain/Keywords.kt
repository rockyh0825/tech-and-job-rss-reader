package dev.rockyh.rsswatch.keywords.domain

import dev.rockyh.rsswatch.shared.contract.TechCategory

/**
 * 技術キーワード辞書の 1 分類。
 *
 * @property normalizedName 抽出結果として返す正規化名
 * @property category キーワードが属するカテゴリ(興味設定でカテゴリ単位の指定に使う)
 * @property ignoreCaseAliases 大文字小文字を区別せずに照合する表記(通常はこちら)
 * @property exactCaseAliases 一般語と衝突する短い名前のため、大文字小文字を完全一致で照合する表記
 */
data class KeywordEntry(
    val normalizedName: String,
    val category: TechCategory,
    val ignoreCaseAliases: List<String>,
    val exactCaseAliases: List<String>,
)

/**
 * 「正規化名 + エイリアス」の技術キーワード辞書(約 60 分類)。
 * キーワードの追加は entry を 1 行足すだけでよい。
 *
 * 正規化名を rename するときは注意:rss-watch.notify.interests の keywords に旧名を設定している
 * 環境(自宅サーバー)では、未知の名前として起動エラーになる(fail-fast)。rename 時は設定側も揃えること。
 */
object Keywords {

    private fun entry(category: TechCategory, normalizedName: String, vararg aliases: String) =
        KeywordEntry(
            normalizedName = normalizedName,
            category = category,
            ignoreCaseAliases = listOf(normalizedName) + aliases,
            exactCaseAliases = emptyList(),
        )

    /** `Go` のような一般語と衝突する名前用。正規化名は完全一致、エイリアスは大文字小文字を区別しない。 */
    private fun shortNameEntry(category: TechCategory, normalizedName: String, vararg ignoreCaseAliases: String) =
        KeywordEntry(
            normalizedName = normalizedName,
            category = category,
            ignoreCaseAliases = ignoreCaseAliases.toList(),
            exactCaseAliases = listOf(normalizedName),
        )

    val entries: List<KeywordEntry> =
        listOf(
            // 言語
            entry(TechCategory.LANGUAGE, "Python"),
            entry(TechCategory.LANGUAGE, "JavaScript", "JS"),
            entry(TechCategory.LANGUAGE, "TypeScript", "TS"),
            entry(TechCategory.LANGUAGE, "Java"),
            entry(TechCategory.LANGUAGE, "Kotlin"),
            shortNameEntry(TechCategory.LANGUAGE, "Go", "golang"),
            shortNameEntry(TechCategory.LANGUAGE, "Rust"),
            entry(TechCategory.LANGUAGE, "Ruby"),
            entry(TechCategory.LANGUAGE, "PHP"),
            shortNameEntry(TechCategory.LANGUAGE, "Swift"),
            entry(TechCategory.LANGUAGE, "Scala"),
            entry(TechCategory.LANGUAGE, "C++", "cpp"),
            entry(TechCategory.LANGUAGE, "C#", "csharp"),
            entry(TechCategory.LANGUAGE, "Elixir"),
            entry(TechCategory.LANGUAGE, "Haskell"),
            shortNameEntry(TechCategory.LANGUAGE, "Dart"),
            entry(TechCategory.LANGUAGE, "Perl"),
            // フロントエンド
            entry(TechCategory.FRONTEND, "React", "React.js", "ReactJS"),
            entry(TechCategory.FRONTEND, "Vue.js", "Vue", "VueJS"),
            entry(TechCategory.FRONTEND, "Angular", "AngularJS"),
            entry(TechCategory.FRONTEND, "Next.js", "NextJS"),
            entry(TechCategory.FRONTEND, "Nuxt", "Nuxt.js", "NuxtJS"),
            entry(TechCategory.FRONTEND, "Svelte", "SvelteKit"),
            entry(TechCategory.FRONTEND, "Tailwind CSS", "Tailwind", "TailwindCSS"),
            entry(TechCategory.FRONTEND, "HTML", "HTML5"),
            entry(TechCategory.FRONTEND, "CSS", "CSS3"),
            // バックエンド
            entry(TechCategory.BACKEND, "Spring Boot", "Spring", "spring-kafka"),
            entry(TechCategory.BACKEND, "Django"),
            shortNameEntry(TechCategory.BACKEND, "Flask"),
            entry(TechCategory.BACKEND, "FastAPI"),
            entry(TechCategory.BACKEND, "Ruby on Rails", "Rails", "RoR"),
            entry(TechCategory.BACKEND, "Laravel"),
            entry(TechCategory.BACKEND, "Node.js", "NodeJS"),
            entry(TechCategory.BACKEND, "NestJS", "Nest.js"),
            entry(TechCategory.BACKEND, ".NET", "dotnet", "ASP.NET", "VB.NET"),
            entry(TechCategory.BACKEND, "GraphQL"),
            entry(TechCategory.BACKEND, "gRPC"),
            // モバイル
            entry(TechCategory.MOBILE, "iOS"),
            entry(TechCategory.MOBILE, "Android"),
            entry(TechCategory.MOBILE, "Flutter"),
            entry(TechCategory.MOBILE, "React Native", "ReactNative"),
            // データベース・ミドルウェア
            entry(TechCategory.DATABASE_MIDDLEWARE, "MySQL"),
            entry(TechCategory.DATABASE_MIDDLEWARE, "PostgreSQL", "Postgres"),
            entry(TechCategory.DATABASE_MIDDLEWARE, "SQLite"),
            entry(TechCategory.DATABASE_MIDDLEWARE, "MongoDB"),
            entry(TechCategory.DATABASE_MIDDLEWARE, "Redis"),
            entry(TechCategory.DATABASE_MIDDLEWARE, "Elasticsearch"),
            entry(TechCategory.DATABASE_MIDDLEWARE, "Kafka", "Apache Kafka"),
            entry(TechCategory.DATABASE_MIDDLEWARE, "RabbitMQ"),
            shortNameEntry(TechCategory.DATABASE_MIDDLEWARE, "Spark", "Apache Spark"),
            entry(TechCategory.DATABASE_MIDDLEWARE, "BigQuery"),
            entry(TechCategory.DATABASE_MIDDLEWARE, "Snowflake"),
            // クラウド・インフラ
            entry(TechCategory.CLOUD_INFRA, "AWS", "Amazon Web Services"),
            entry(TechCategory.CLOUD_INFRA, "Google Cloud", "GCP", "Google Cloud Platform"),
            entry(TechCategory.CLOUD_INFRA, "Azure", "Microsoft Azure"),
            entry(TechCategory.CLOUD_INFRA, "Docker", "Docker Compose"),
            entry(TechCategory.CLOUD_INFRA, "Kubernetes", "k8s"),
            entry(TechCategory.CLOUD_INFRA, "Terraform"),
            entry(TechCategory.CLOUD_INFRA, "Ansible"),
            entry(TechCategory.CLOUD_INFRA, "GitHub Actions"),
            entry(TechCategory.CLOUD_INFRA, "Jenkins"),
            entry(TechCategory.CLOUD_INFRA, "Linux"),
            entry(TechCategory.CLOUD_INFRA, "Nginx"),
            // 機械学習・AI
            entry(TechCategory.ML_AI, "機械学習", "Machine Learning"),
            entry(TechCategory.ML_AI, "TensorFlow"),
            entry(TechCategory.ML_AI, "PyTorch"),
            entry(TechCategory.ML_AI, "LLM", "大規模言語モデル"),
        )
}
