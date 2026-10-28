# pipeline

- 순수 Kotlin 모듈이다. Spring 라이브러리는 쓰지 않는다. 의존성 버전만 Spring Boot BOM(`platform(...)`)에 맞춘다. BOM에 없는 것(JSqlParser)은 버전을 직접 적는다.
- 테스트는 두 곳이다. `src/test`는 순수 단위 테스트로 Stop 훅의 `:pipeline:test`가 돌리니 빠르게 둔다. MySQL이 필요한 테스트는 `src/integrationTest`(Testcontainers, `:pipeline:integrationTest`, `check`에 묶임)에 둔다.
- `build.gradle.kts`의 `testing.suites` 블록 안에서는 `platform()`, `kotlin("test")`를 못 쓴다. 통합 테스트 의존성은 바깥 `dependencies`에 `"integrationTestImplementation"(...)`로 적는다.
- 분석 대상 DB 코드는 `io.askflow.pipeline.datasource`(공개 타입), `.guard`(SQL 검사), `.mysql`(구현체)에 둔다.
