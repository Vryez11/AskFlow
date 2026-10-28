// 생성 파이프라인. Spring에 기대지 않는 순수 Kotlin 라이브러리로 둔다 (아키텍처 문서 2장)
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

// MySQL이 필요한 통합 테스트. Docker를 써서 느리므로 Stop 훅의 :pipeline:test와 나눈다 (design 9장)
testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
            }
        }
    }
}

// 통합 테스트도 test처럼 main의 internal(MySqlConnector.createPool 등)을 볼 수 있게 한다
kotlin.target.compilations.named("integrationTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

dependencies {
    // 버전만 api와 같은 Spring Boot BOM에 맞춘다. Spring 라이브러리를 쓰지는 않는다
    val springBootBom = platform("org.springframework.boot:spring-boot-dependencies:4.1.2")

    implementation(springBootBom)
    implementation("com.github.jsqlparser:jsqlparser:5.3")
    implementation("com.zaxxer:HikariCP")
    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "integrationTestImplementation"(springBootBom)
    // kotlin("test")만 두면 이 소스 세트에서는 JUnit 5 변형이 골라지지 않아 @Test가 없다
    "integrationTestImplementation"(kotlin("test-junit5"))
    "integrationTestImplementation"("org.testcontainers:testcontainers-mysql")
    "integrationTestImplementation"("org.testcontainers:testcontainers-junit-jupiter")
    "integrationTestImplementation"("com.mysql:mysql-connector-j")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<Test>("integrationTest") {
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(tasks.named("integrationTest"))
}
