package dev.rockyh.rsswatch.keywords.domain

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class KeywordExtractorTest {

    private val extractor = KeywordExtractor()

    // --- 要件 2.2: 日本語文中の英語キーワードを独自境界で検出する ---

    @ParameterizedTest(name = "「{0}」から {1} を検出する")
    @CsvSource(
        "Pythonで機械学習を始める, Python",
        "Kotlinを使ったサーバーサイド開発, Kotlin",
        "TypeScriptに入門した, TypeScript",
        "Kubernetesの運用ノウハウ, Kubernetes",
        "Dockerによる開発環境構築, Docker",
    )
    fun detects_keyword_embedded_in_japanese_text(text: String, expected: String) {
        val keywords = extractor.extract(text)

        assertTrue(expected in keywords, "expected $expected in $keywords")
    }

    @Test
    fun detects_multiple_keywords_in_one_text() {
        val keywords = extractor.extract("PythonとKotlinでKafkaを使う")

        assertEquals(setOf("Python", "Kotlin", "Kafka"), keywords)
    }

    // --- 要件 2.2: 独自境界 — 英数字が連続する場合はキーワードとみなさない ---

    @ParameterizedTest(name = "「{0}」から {1} を誤検出しない")
    @CsvSource(
        "JavaScriptの話, Java",
        "Pythonista向けの記事, Python",
        "TypeScript入門, Script",
        "goroutineの仕組み, Go",
        "Scalableな設計, Scala",
        "PHPUnitの使い方, PHP",
    )
    fun does_not_match_inside_longer_alphanumeric_words(text: String, notExpected: String) {
        val keywords = extractor.extract(text)

        assertTrue(notExpected !in keywords, "$notExpected should not be in $keywords")
    }

    // --- 要件 2.1: エイリアスの正規化(表記ゆれを正規化名に寄せる) ---

    @ParameterizedTest(name = "「{0}」を {1} に正規化する")
    @CsvSource(
        "k8sクラスタの構築, Kubernetes",
        "K8s運用の話, Kubernetes",
        "JSの非同期処理, JavaScript",
        "TSの型パズル, TypeScript",
        "Railsアプリの高速化, Ruby on Rails",
        "Postgresのインデックス, PostgreSQL",
        "Node.jsでAPIサーバー, Node.js",
        "nodejsの話, Node.js",
        "ReactNativeでアプリ開発, React Native",
        "GCPで構築する, Google Cloud",
        "Amazon Web Servicesの資格, AWS",
        "golangでCLIツール, Go",
        "Golangの並行処理, Go",
    )
    fun normalizes_aliases_to_canonical_name(text: String, expected: String) {
        val keywords = extractor.extract(text)

        assertTrue(expected in keywords, "expected $expected in $keywords")
    }

    @ParameterizedTest(name = "大文字小文字を区別せず「{0}」から {1} を検出する")
    @CsvSource(
        "PYTHONのエラー処理, Python",
        "python 3.13の新機能, Python",
        "KAFKAのパーティション, Kafka",
        "spring bootで作るAPI, Spring Boot",
    )
    fun matches_case_insensitively_for_normal_keywords(text: String, expected: String) {
        val keywords = extractor.extract(text)

        assertTrue(expected in keywords, "expected $expected in $keywords")
    }

    // --- 要件 2.3: 一般語と衝突する短い名前は大文字小文字を区別する別枠で照合する ---

    @ParameterizedTest(name = "「{0}」から Go を検出する")
    @CsvSource(
        "Goで書いたマイクロサービス, true",
        "Go言語の勉強会, true",
        "golangのジェネリクス, true",
        "Golang入門, true",
        "go to the store and buy milk, false",
        "let it go, false",
        "GO TO統計の話, false",
        "Went and gone, false",
    )
    fun matches_Go_only_with_exact_case(text: String, shouldMatch: Boolean) {
        val keywords = extractor.extract(text)

        assertEquals(shouldMatch, "Go" in keywords, "text=$text keywords=$keywords")
    }

    // --- 記号を含むキーワード ---

    @ParameterizedTest(name = "「{0}」から {1} を検出する")
    @CsvSource(
        "モダンC++の書き方, C++",
        "C#とUnityでゲーム開発, C#",
        "Next.jsのApp Router, Next.js",
        ".NETエンジニア募集, .NET",
    )
    fun detects_keywords_containing_symbols(text: String, expected: String) {
        val keywords = extractor.extract(text)

        assertTrue(expected in keywords, "expected $expected in $keywords")
    }

    // --- 境界値・異常系 ---

    @Test
    fun returns_empty_set_for_empty_text() {
        assertEquals(emptySet(), extractor.extract(""))
    }

    @Test
    fun returns_empty_set_when_no_keywords_exist() {
        assertEquals(emptySet(), extractor.extract("今日は良い天気なので散歩した"))
    }

    @Test
    fun deduplicates_repeated_keywords() {
        val keywords = extractor.extract("Python、Python、またPython")

        assertEquals(setOf("Python"), keywords)
    }

    // --- 要件 2.1: 辞書は約 60 分類を持つ ---

    @Test
    fun dictionary_has_around_60_normalized_keywords() {
        val count = Keywords.entries.size

        assertTrue(count >= 55, "dictionary should have around 60 classifications but has $count")
    }

    @Test
    fun dictionary_normalized_names_are_unique() {
        val names = Keywords.entries.map { it.normalizedName }

        assertEquals(names.size, names.toSet().size)
    }
}
