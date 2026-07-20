plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.spring") version "2.2.20"
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
    // kuery-client のコンパイラプラグインは Kotlin バージョンと結合する(0.11.0 = Kotlin 2.2.20)。
    // Kotlin 更新時は https://kuery-client.hsbrysk.dev/compatibility の対応表を確認すること
    id("dev.hsbrysk.kuery-client") version "0.11.0"
}

group = "dev.rockyh"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    // メトリクス公開(/actuator/prometheus)。エンドポイント計装は Actuator/Micrometer の自動計装に任せる
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-toml")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.rometools:rome:2.1.0")
    // 記事ページの og:image を取り出す HTML パース(notify のサムネイル解決)
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("dev.hsbrysk.kuery-client:kuery-client-spring-data-jdbc:0.11.0")
    implementation("org.flywaydb:flyway-core")
    // Flyway 10 以降は DB ごとのモジュールが分離されている
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    // Cloudflare Access の Cf-Access-Jwt-Assertion(JWT)を JWKS で検証する(任意ハードニング)。
    implementation("com.nimbusds:nimbus-jose-jwt:9.48")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.lemonappdev:konsist:0.17.3")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// デプロイ先(/opt/rss-watch/rss-watch.jar)と CI がバージョン番号に依存しないよう、boot jar は固定名で出力する
tasks.bootJar {
    archiveFileName = "rss-watch.jar"
}
