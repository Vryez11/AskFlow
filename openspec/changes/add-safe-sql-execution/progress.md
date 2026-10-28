# 진행 메모: add-safe-sql-execution

## 알아 둘 패턴
- 모듈 규칙과 Gradle 함정은 `pipeline/CLAUDE.md`에 있다.
- 공개 타입은 `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/`에 있다. 오류는 `ExecutionError(kind, message, cause, checkPoints)`이고, 테스트는 `kind`와 `cause`(거부한 테이블·함수 이름, 상한 값), `checkPoints`(설정 키)를 본다. 오류 종류의 한국어 이름은 `ExecutionErrorKind.label`.
- `SqlGuard(schema, allowedTables, maxRows).check(sql)`은 `GuardResult.Passed(sql = 다시 쓴 SQL)` 또는 `Rejected(error)`를 돌려준다. 개인정보 열 추적 API는 아직 없다(1.3에서 `PiiLineageTest`를 쓰며 정한다).
- `MySqlConnector(config)`(공개)와 `MySqlConnector(config, dataSource)`(internal, 테스트용)가 있다. 본문은 `TODO()`다. 풀 생성은 companion의 `createPool`.
- `SqlGuardTest`는 잠금·파일 쓰기 SELECT(`LOCK IN SHARE MODE`, `INTO OUTFILE`/`DUMPFILE`/`@변수`)를 `NOT_SELECT`로 기대하지만 JSqlParser 5.3은 이것들을 해석하지 못한다. 2.1에서 해석 전 원문 검사(문자열 리터럴 밖 토큰)로 `NOT_SELECT`를 돌려줘야 한다. `FOR UPDATE`/`FOR SHARE`는 해석된다.
- `LlmSafeResult`는 생성자가 `internal`이라 `QueryResult.forLlm()`으로만 만든다.
- 버전: JSqlParser 5.3, HikariCP 7.0.2, Connector/J 9.7.0, Testcontainers 2.0.5(아티팩트 이름 `testcontainers-mysql`, `testcontainers-junit-jupiter`).

---

## 2026-10-28 - 1.1 테스트가 컴파일될 골격
- pipeline에 JSqlParser, HikariCP, Connector/J 의존성과 `integrationTest` 테스트 스위트(`check`에 묶음)를 추가했다.
- design 1·2장의 공개 타입을 만들고, 동작이 필요한 `QueryResult.forLlm()`, `SqlGuard.check()`는 `TODO()`로 뒀다. `TargetDbConfig.toString()`은 비밀번호를 가린다.
- 바꾼 파일: `pipeline/build.gradle.kts`, `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/**`, `pipeline/CLAUDE.md`
- 다음 반복: `GrantParser`, `ConnectionFailureClassifier`, `MySqlConnector`는 아직 없다. 그 테스트를 옮기는 작업(1.4, 1.5)에서 시그니처와 `TODO()` 본문을 함께 만든다.
---

## 2026-10-28 - 1.2 SQL 검사 시나리오를 SqlGuardTest로 옮김
- `SqlGuardTest`에 실행 주석·힌트, SELECT 하나만, 허용 테이블, 부작용 함수, 검사에 걸린 SQL은 DB에 닿지 않음 시나리오를 22개 테스트로 옮겼다. 모두 `NotImplementedError`로 실패하는 것을 확인했다.
- "DB에 닿지 않음"은 `MySqlConnector`의 internal 생성자에 `RecordingDataSource`를 넣고 `sentSql`이 비었는지 본다. 이를 위해 `MySqlConnector` 골격을 만들었다.
- 시나리오 밖이지만 design 3장에 있는 것도 테스트했다: 문자열 리터럴 안의 `/*!`는 통과, 대소문자 무시와 `olist.` 스키마 접두어 통과, CALL·SET 거부, 거부 함수 6개 전부.
- 바꾼 파일: `pipeline/src/test/kotlin/io/askflow/pipeline/datasource/guard/SqlGuardTest.kt`, `.../datasource/RecordingDataSource.kt`, `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/mysql/MySqlConnector.kt`, `pipeline/CLAUDE.md`
- 다음 반복: `:pipeline:test`는 이제 실패한다(1장 단계라 정상). 2.1에서 JSqlParser가 못 읽는 잠금·파일 쓰기 구문을 원문 검사로 막아야 한다("알아 둘 패턴" 참고).
---
