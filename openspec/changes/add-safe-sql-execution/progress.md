# 진행 메모: add-safe-sql-execution

## 알아 둘 패턴
- 모듈 규칙과 Gradle 함정은 `pipeline/CLAUDE.md`에 있다.
- 공개 타입은 `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/`에 있다. 오류는 `ExecutionError(kind, message, cause, checkPoints)`이고, 테스트는 `kind`와 `cause`(거부한 테이블·함수 이름, 상한 값), `checkPoints`(설정 키)를 본다. 오류 종류의 한국어 이름은 `ExecutionErrorKind.label`.
- `SqlGuard(schema, allowedTables, maxRows).check(sql)`은 `GuardResult.Passed(sql = 다시 쓴 SQL)` 또는 `Rejected(error)`를 돌려준다.
- 개인정보 열 추적은 `PiiLineage(piiColumns).trace(sql): List<ItemPii>`(guard 패키지). 최상위 SELECT 항목마다 `PII`/`SAFE`/`STAR`. `STAR`(`*`, `t.*`)는 3.3에서 JDBC 메타데이터로 판정한다. 결과 열과 항목 수가 `STAR` 때문에 다를 수 있으니 3.3에서 맞춰야 한다.
- 1장 동안 Stop 훅은 `:pipeline:test` 실패(모두 `NotImplementedError`)로 매번 막는다. 1장의 확인 조건이 실패라 정상이고, 구현을 당겨 하지 않는다. 3/3이면 훅이 사람에게 넘긴다. 실패가 `NotImplementedError` 말고도 있는지만 확인한다.
- UNION 끝 LIMIT의 JSqlParser 함정은 `pipeline/CLAUDE.md`에 있다. 2.3 LIMIT 다시 쓰기에서 꼭 본다.
- `MySqlConnector(config)`(공개)와 `MySqlConnector(config, dataSource)`(internal, 테스트용)가 있다. 본문은 `TODO()`다. 풀 생성은 companion의 `createPool`.
- `SqlGuardTest`는 잠금·파일 쓰기 SELECT(`LOCK IN SHARE MODE`, `INTO OUTFILE`/`DUMPFILE`/`@변수`)를 `NOT_SELECT`로 기대하지만 JSqlParser 5.3은 이것들을 해석하지 못한다. 2.1에서 해석 전 원문 검사(문자열 리터럴 밖 토큰)로 `NOT_SELECT`를 돌려줘야 한다. `FOR UPDATE`/`FOR SHARE`는 해석된다.
- `LlmSafeResult`는 생성자가 `internal`이라 `QueryResult.forLlm()`으로만 만든다.
- 권한 해석은 `GrantParser(schema).parse(lines): GrantCheck(writePrivileges, warnings)`, 연결 실패 분류는 `ConnectionFailureClassifier(password).classify(error): ConnectionFailure`. 둘 다 `mysql` 패키지의 internal이다. 분류기는 HikariCP가 감싼 예외(`SQLTransientConnectionException`)의 원인 사슬을 따라가야 하고, 원인 없는 풀 대기 시간 초과는 접속 불가로 본다.
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

## 2026-10-28 - 1.3 LIMIT 다시 쓰기와 개인정보 열 해석 트리 시나리오를 테스트로 옮김
- `LimitRewriteTest`(7개): LIMIT 없음 → 1001, LIMIT 10 그대로, LIMIT 100000 → 1001, 상한 50 → 51, OFFSET 유지(`LIMIT n OFFSET m`, `LIMIT m, n`), CTE·UNION 최상위, 서브쿼리 LIMIT은 최상위를 대신하지 않음. 다시 쓴 SQL을 JSqlParser로 다시 해석해 최상위 LIMIT 값을 본다.
- `PiiLineageTest`(14개): 그대로(테이블 별칭·스키마 접두어·대소문자·조인에서 테이블 없이 쓴 컬럼), 별칭·함수, `*`는 `STAR`, COUNT 안은 `SAFE`, MIN·MAX·GROUP_CONCAT·SUM은 `PII`, 서브쿼리·CTE·스칼라 서브쿼리·UNION을 거친 열, `*` 서브쿼리를 거친 열은 `PII`. `forLlm()`이 개인정보 열을 빼고 이름만 남기는지, 원래 결과는 그대로인지도 본다.
- 21개 모두 `NotImplementedError`로 실패하는 것을 확인했다.
- 바꾼 파일: `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/guard/PiiLineage.kt`(새 골격), `pipeline/src/test/kotlin/io/askflow/pipeline/datasource/guard/LimitRewriteTest.kt`, `.../guard/PiiLineageTest.kt`, `pipeline/CLAUDE.md`
- 다음 반복: JSqlParser가 괄호 없는 UNION 끝 LIMIT을 마지막 SELECT에 붙인다(`pipeline/CLAUDE.md`). 2.3에서 최상위 LIMIT을 고칠 때 이 위치를 써야 `LIMIT`이 두 번 찍히지 않는다.
---

## 2026-10-28 - 1.4 SHOW GRANTS 해석과 연결 실패 분류를 테스트로 옮김
- `GrantParserTest`(11개): 읽기 전용, 스키마 INSERT, 테이블 권한, 컬럼 권한·대소문자·따옴표 없는 스키마, 중복 제거, 다른 스키마 무시, `ALL PRIVILEGES ON *.*`, MySQL 8이 펼친 정적·동적 권한 줄, 역할 줄 경고(역할 이름 포함), 해석 못 한 줄과 빈 결과 경고.
- `ConnectionFailureClassifierTest`(7개): 1045 → 인증 실패(`askflow.target-db.username`, `ASKFLOW_READER_PASSWORD`), 통신 오류(`08*`)와 원인 없는 HikariCP 대기 시간 초과 → 접속 불가(`askflow.target-db.url`), 1049·1044 → 스키마(`askflow.target-db.schema`), 모르는 오류 → `UNKNOWN`과 DB 메시지, 메시지에서 비밀번호 빼기. HikariCP로 감싼 경우도 함께 본다.
- 18개 모두 `NotImplementedError`로 실패하는 것을 확인했다. 다른 실패는 없다.
- 바꾼 파일: `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/mysql/GrantParser.kt`, `.../mysql/ConnectionFailureClassifier.kt`(새 골격), `pipeline/src/test/kotlin/io/askflow/pipeline/datasource/mysql/GrantParserTest.kt`, `.../mysql/ConnectionFailureClassifierTest.kt`
- 다음 반복: 3.4에서 `08004`(Too many connections)처럼 `08`로 시작하는 SQLState는 접속 불가가 된다. 모르는 오류 테스트는 그래서 `HY001`을 쓴다. 없는 허용 테이블(`TABLE_NOT_FOUND`)은 예외가 아니라 `checkConnection()`에서 만들므로 분류기에 없다.
---
