# 진행 메모: add-safe-sql-execution

## 알아 둘 패턴
- 모듈 규칙과 Gradle 함정은 `pipeline/CLAUDE.md`에 있다.
- 공개 타입은 `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/`에 있다. 오류는 `ExecutionError(kind, message, cause, checkPoints)`이고, 테스트는 `kind`와 `cause`(거부한 테이블·함수 이름, 상한 값), `checkPoints`(설정 키)를 본다. 오류 종류의 한국어 이름은 `ExecutionErrorKind.label`.
- `SqlGuard(schema, allowedTables, maxRows).check(sql)`은 `GuardResult.Passed(sql = 다시 쓴 SQL)` 또는 `Rejected(error)`를 돌려준다. 개인정보 열 추적 API는 아직 없다(1.3에서 `PiiLineageTest`를 쓰며 정한다).
- `LlmSafeResult`는 생성자가 `internal`이라 `QueryResult.forLlm()`으로만 만든다.
- 버전: JSqlParser 5.3, HikariCP 7.0.2, Connector/J 9.7.0, Testcontainers 2.0.5(아티팩트 이름 `testcontainers-mysql`, `testcontainers-junit-jupiter`).

---

## 2026-10-28 - 1.1 테스트가 컴파일될 골격
- pipeline에 JSqlParser, HikariCP, Connector/J 의존성과 `integrationTest` 테스트 스위트(`check`에 묶음)를 추가했다.
- design 1·2장의 공개 타입을 만들고, 동작이 필요한 `QueryResult.forLlm()`, `SqlGuard.check()`는 `TODO()`로 뒀다. `TargetDbConfig.toString()`은 비밀번호를 가린다.
- 바꾼 파일: `pipeline/build.gradle.kts`, `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/**`, `pipeline/CLAUDE.md`
- 다음 반복: `GrantParser`, `ConnectionFailureClassifier`, `MySqlConnector`는 아직 없다. 그 테스트를 옮기는 작업(1.4, 1.5)에서 시그니처와 `TODO()` 본문을 함께 만든다.
---
