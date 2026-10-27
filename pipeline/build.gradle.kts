// 생성 파이프라인. Spring에 기대지 않는 순수 Kotlin 라이브러리로 둔다 (아키텍처 문서 2장)
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
