# pipeline

- 순수 Kotlin 모듈이다. Spring 라이브러리는 쓰지 않는다. 의존성 버전만 Spring Boot BOM(`platform(...)`)에 맞춘다. BOM에 없는 것(JSqlParser)은 버전을 직접 적는다.
- 테스트는 두 곳이다. `src/test`는 순수 단위 테스트로 Stop 훅의 `:pipeline:test`가 돌리니 빠르게 둔다. MySQL이 필요한 테스트는 `src/integrationTest`(Testcontainers, `:pipeline:integrationTest`, `check`에 묶임)에 둔다.
- `build.gradle.kts`의 `testing.suites` 블록 안에서는 `platform()`, `kotlin("test")`를 못 쓴다. 통합 테스트 의존성은 바깥 `dependencies`에 `"integrationTestImplementation"(...)`로 적는다.
- JSqlParser 5.3은 `LOCK IN SHARE MODE`, `INTO OUTFILE`/`DUMPFILE`/`@변수`를 해석하지 못하고(해석 실패), `/*! */` 실행 주석은 조용히 버린다. 이런 구문을 다른 오류 종류로 거부하려면 해석 전에 원문을 따로 검사해야 한다.
- JSqlParser 5.3은 괄호 없는 UNION 끝의 `LIMIT`(MySQL에서는 UNION 전체에 걸림)을 `SetOperationList`가 아니라 마지막 `PlainSelect`에 붙인다. `(…) UNION (…) LIMIT n`일 때만 `SetOperationList.limit`에 붙는다. 최상위 LIMIT을 볼 때 두 곳을 다 봐야 하고, 둘 다 채우면 `LIMIT`이 두 번 찍힌다.
- DB에 닿는지 단위 테스트로 보려면 `MySqlConnector`의 internal 생성자에 `src/test`의 `RecordingDataSource`(보낸 SQL을 기록하는 가짜 JDBC)를 넣는다.
- 분석 대상 DB 코드는 `io.askflow.pipeline.datasource`(공개 타입), `.guard`(SQL 검사), `.mysql`(구현체)에 둔다.
