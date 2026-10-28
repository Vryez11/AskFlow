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
- 통합 테스트는 `TestMySql`(mysql:8.4, 스키마 `olist`, 테이블 orders 6행·customers 4행·numbers 1..5000, 뷰 `delivered_orders`, 계정 `askflow_reader`(SELECT, SHOW VIEW)·`askflow_writer`(SELECT, INSERT, UPDATE, DELETE)·`askflow_admin`(ALL ON `*.*`), reader가 못 들어가는 스키마 `hr`)를 쓴다.
- `ConnectionCheckIT`가 정한 것(3.5에서 맞춘다): 허용 테이블 비움이면 테이블 수에 뷰도 센다(4). 스키마 없음(1049)과 접근 못 하는 스키마(1044) 모두 `SCHEMA_UNAVAILABLE`. 없는 허용 테이블은 `message`에 없는 이름만 담는다. DB에 닿지 않음은 10초 안에 끝나야 한다. 실패 상태의 `toString()`에 비밀번호가 없어야 한다. "나중에 살아난 DB"는 같은 커넥터로 두 번 `checkConnection()`을 부른다(`initializationFailTimeout = -1` 필요). 이 로컬 환경에서는 Docker가 돌아 `:pipeline:integrationTest`가 실제로 컨테이너를 띄운다.
- `MySqlConnectorIT`가 정한 오류 형식(3장에서 맞춘다): 시간 상한 `cause`에 `"1초"`, `checkPoints`에 `askflow.target-db.query-timeout`. 행 수 상한 `cause`에 상한 숫자(`"1000"`, 쉼표 없음), `checkPoints`에 `askflow.target-db.max-rows`. 연결 실패 `checkPoints` 중 하나에 `"연결 상태"`. DB 오류 `cause`에 MySQL 메시지 원문.
- api 모듈 규칙은 `api/CLAUDE.md`에 있다. api 코드는 `io.askflow.api.connection`: `TargetDbProperties.toConfig()`, `TargetDbConfiguration`(빈 `TargetDbConfig`, `DataSourceConnector`), `TermCountProvider`/`ZeroTermCountProvider`, `ConnectionStatusController`와 `ConnectionStatusResponse`(`failure.reason`은 enum 이름 문자열, 예: `"AUTHENTICATION"`). 본문은 `TODO()`다.
- 4.1이 할 일: `@Validated` + `maxRows`에 `@field:Positive`, `queryTimeout`에 `@field:DurationMin(millis = 1)`, `toConfig()`, `application.yml`에 design 2장 블록(`url: jdbc:mysql://localhost:${MYSQL_PORT:3306}/olist`, `password: ${ASKFLOW_READER_PASSWORD:}`). 1.7에서 이렇게 임시로 구현해 잘못된 상한 값 4개와 컨트롤러 6개가 통과하는 것을 확인하고 되돌렸다. `TargetDbPropertiesTest`의 나머지 3개와 앱 전체 테스트는 `MySqlConnector.createPool`(3.1)이 있어야 통과한다.
- 커밋된 `ApiApplicationTests`는 Ralph 훅이 고치지 못하게 막는다. 연결 실패 시작 시나리오는 새 `ApiApplicationConnectionFailureTests`에 뒀다. `ApiApplicationTests.contextLoads`는 4.1까지 실패한다.
- web 모듈 규칙은 `web/CLAUDE.md`에 있다. `ConnectionStatus.test.tsx`가 정한 것(5.1에서 맞춘다): 컴포넌트는 기본 내보내기 `ConnectionStatus`(props 없음)이고 스스로 `fetch('/api/connection/status', <옵션>)`를 부른다(두 번째 인자가 있어야 한다). 화면 글은 `textContent` 정규식으로 본다: "연결됨", "테이블 9개", "용어 0개", "쓰기 권한"(권한이 없으면 이 글이 없어야 한다), "연결 실패", 원인 `message`, `checkKeys`, 실패 시 "연결 상태를 불러오지 못했"(네트워크 오류와 502 둘 다). 마지막 시나리오는 `<App />`을 그려 보므로 `App`에 컴포넌트를 넣어야 통과한다.
- 읽기 전용 세션 테스트는 `MySqlConnector.createPool(config)`(internal)로 풀을 받아 writer 계정으로 INSERT를 보내고 오류 코드 1792를 기대한다. `createPool`은 `readOnly`·`autoCommit = false`·`connectionInitSql`을 모두 걸어야 한다.

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

## 2026-10-28 - 1.5 MySQL이 필요한 시나리오를 MySqlConnectorIT로 옮김
- `TestMySql`(공용 Testcontainers MySQL과 테스트용 스키마·계정)과 `MySqlConnectorIT`(19개)를 만들었다: 읽기 전용 세션(writer 계정의 INSERT가 1792로 거부, 데이터 그대로), 시간 상한(교차 조인, 1초 상한이 5초 안에 끝남), 시간 상한 안, 행 수 상한 안·초과·작은 LIMIT·큰 LIMIT·상한 50(80행 초과, 50행은 통과), 성공 결과 형식, 없는 컬럼, DB 꺼짐(포트 1, 비밀번호 미노출), `SELECT *`·`c.*` 개인정보 열, 화면용 결과, 다시 쓴 SQL과 원문(root로 실행) 결과 일치(CTE, UNION·UNION ALL ... LIMIT, 윈도 함수·WINDOW 절, 날짜 함수·INTERVAL).
- 19개 모두 컨테이너와 스키마 준비 뒤 `NotImplementedError`로 실패하는 것을 확인했다. `:pipeline:test`도 `NotImplementedError`만 있다.
- `integrationTest` 컴파일을 main과 묶고(`associateWith`), `kotlin("test-junit5")`로 바꿨다. `MySqlConnector`의 companion을 internal로 열었다.
- 바꾼 파일: `pipeline/build.gradle.kts`, `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/mysql/MySqlConnector.kt`, `pipeline/src/integrationTest/kotlin/io/askflow/pipeline/datasource/mysql/TestMySql.kt`, `.../mysql/MySqlConnectorIT.kt`, `pipeline/CLAUDE.md`
- 다음 반복(1.6): `ConnectionCheckIT`는 `TestMySql`을 쓴다. 비밀번호 틀림·스키마 없음·허용 테이블 없음은 `TestMySql.config(...)`나 `.copy(...)`로 만들고, "나중에 살아난 DB"는 공용 컨테이너를 멈추지 말고 따로 컨테이너를 띄우거나 늦게 띄우는 방식이 필요하다. 전체 권한 경고용 계정은 아직 없으니 `TestMySql`에 더한다.
---

## 2026-10-28 - 1.6 연결 확인 시나리오를 ConnectionCheckIT로 옮김
- `ConnectionCheckIT`(11개): 설정한 DB에 연결(테이블 수 3), 허용 테이블 비움(테이블+뷰 4), 비밀번호 틀림, DB에 닿지 않음(포트 1, 10초 안), 스키마 없음(1049)·접근 못 하는 스키마(1044), 없는 허용 테이블, 읽기 전용·쓰기 권한·전체 권한 계정, 나중에 살아난 DB.
- `TestMySql`에 `askflow_admin` 계정, 뷰 `delivered_orders`, 스키마 `hr`, `host`·`port`·`jdbcUrlOf(schema)`를 더했다. "나중에 살아난 DB"는 새 `TcpForwarder`로 처음엔 닫힌 포트를 나중에 열어 공용 컨테이너로 잇는다.
- 11개 모두 `NotImplementedError`로 실패하는 것을 확인했다. `:pipeline:test`, `MySqlConnectorIT`도 `NotImplementedError`만 있다.
- 바꾼 파일: `pipeline/src/integrationTest/kotlin/io/askflow/pipeline/datasource/mysql/ConnectionCheckIT.kt`, `.../mysql/TcpForwarder.kt`(새 파일), `.../mysql/TestMySql.kt`, `pipeline/CLAUDE.md`
- 다음 반복(1.7): api 테스트. `api`에는 아직 actuator뿐이다. `TargetDbProperties`, `ConnectionStatusController`, `TermCountProvider`의 골격을 `TODO()`로 같이 만들어야 컴파일된다.
---

## 2026-10-28 - 1.7 api 시나리오를 테스트로 옮김
- `TargetDbPropertiesTest`(7개): 설정 값이 `TargetDbConfig`로 옮겨짐, 상한 기본값 1000행·10초, 허용 테이블 비움, `max-rows`·`query-timeout`이 0·음수면 시작 실패와 kebab 설정 키. `ApplicationContextRunner`로 `TargetDbConfiguration`만 띄운다.
- `ConnectionStatusControllerTest`(6개, `@WebMvcTest` + 가짜 커넥터 + 실제 `ZeroTermCountProvider`): 정상 상태(테이블 9), 용어 수 0, 쓰기 권한 이름, 실패 원인과 확인할 설정 키, 조회마다 다시 확인, 응답 필드는 정한 것만이고 password 없음.
- `ApiApplicationConnectionFailureTests`(4개, `@SpringBootTest` + `MYSQL_PORT=1`): 앱이 뜨고 상태는 `FAILED`/`UNREACHABLE`/`askflow.target-db.url`, 응답에 비밀번호 값 없음, `application.yml` 기본값(olist, askflow_reader, 비밀번호 환경 변수, 1000행·10초, 허용 테이블 비움).
- `./gradlew :api:test`는 18개 모두 실패(`NotImplementedError`, 설정 없음)한다. `ktlintCheck`와 모든 컴파일은 통과한다.
- 바꾼 파일: `api/src/main/kotlin/io/askflow/api/connection/*`(새 골격), `api/src/test/kotlin/io/askflow/api/connection/TargetDbPropertiesTest.kt`, `.../ConnectionStatusControllerTest.kt`, `api/src/test/kotlin/io/askflow/api/ApiApplicationConnectionFailureTests.kt`, `api/CLAUDE.md`(새 파일)
- 다음 반복(1.8): web 테스트. 응답 형식은 `ConnectionStatusResponse`와 같게 `web/src/connection/types.ts`에 쓴다(`failure.reason`은 enum 이름).
---

## 2026-10-28 - 1.8 홈 연결 상태 시나리오를 테스트로 옮김
- `ConnectionStatus.test.tsx`(6개): 연결됨(테이블 9개, 용어 0개, 요청 주소), 쓰기 권한 경고(INSERT, UPDATE)와 권한 없으면 경고 없음, 연결 실패(원인 메시지, 확인할 설정 키), 상태 API에 닿지 않음(네트워크 오류, 502 응답, `<App />`의 제목은 그대로).
- 응답 타입은 design 8장 형식으로 `types.ts`에 뒀다(`failure`는 선택·null 허용). 컴포넌트 골격은 `null`을 돌려준다.
- `typecheck`, `lint`는 통과하고 `test`는 새 6개만 단언 실패(`App.test.tsx` 통과)하는 것을 확인했다.
- 바꾼 파일: `web/src/connection/types.ts`, `.../ConnectionStatus.tsx`(새 골격), `.../ConnectionStatus.test.tsx`, `web/CLAUDE.md`(새 파일)
- 다음 반복(2.1): 1장이 끝났다. 2.1부터 구현이다. 잠금·파일 쓰기 SELECT의 원문 검사("알아 둘 패턴")를 잊지 않는다.
---
