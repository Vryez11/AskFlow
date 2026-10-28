# 진행 메모: add-safe-sql-execution

## 알아 둘 패턴
- 모듈 규칙과 Gradle 함정은 `pipeline/CLAUDE.md`에 있다.
- 공개 타입은 `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/`에 있다. 오류는 `ExecutionError(kind, message, cause, checkPoints)`이고, 테스트는 `kind`와 `cause`(거부한 테이블·함수 이름, 상한 값), `checkPoints`(설정 키)를 본다. 오류 종류의 한국어 이름은 `ExecutionErrorKind.label`.
- `SqlGuard(schema, allowedTables, maxRows).check(sql)`은 `GuardResult.Passed(sql = 다시 쓴 SQL)` 또는 `Rejected(error)`를 돌려준다.
- 개인정보 열 추적은 `PiiLineage(piiColumns).trace(sql): List<ItemPii>`(guard 패키지). 최상위 SELECT 항목마다 `PII`/`SAFE`/`STAR`. `STAR`(`*`, `t.*`)는 3.3에서 JDBC 메타데이터로 판정한다. 결과 열과 항목 수가 `STAR` 때문에 다를 수 있으니 3.3에서 맞춰야 한다. 2.4 구현: `STAR`는 기본 테이블만 펼친 `*`에만 나온다. 서브쿼리·CTE를 펼친 `*`, `*`가 섞인 UNION, VALUES 같은 문은 `PII` 한 항목이 되어 항목 수가 결과 열 수와 다를 수 있다. 3.3은 맞출 수 없으면(별표가 둘 이상이거나 별표 없이 수가 다름) 모든 열을 가린다(`PiiLineage` KDoc). `QueryResult.forLlm()`은 구현됐다.
- 1장 동안 Stop 훅은 `:pipeline:test` 실패(모두 `NotImplementedError`)로 매번 막는다. 1장의 확인 조건이 실패라 정상이고, 구현을 당겨 하지 않는다. 2~5장에서도 뒤 작업의 테스트(pipeline 단위, web `ConnectionStatus.test.tsx`)가 남아 있는 동안은 같다. 실패가 모두 뒤 작업 몫인지만 확인한다. 3/3이면 훅이 사람에게 넘긴다. 실패가 `NotImplementedError` 말고도 있는지만 확인한다.
- UNION 끝 LIMIT의 JSqlParser 함정은 `pipeline/CLAUDE.md`에 있다. LIMIT 다시 쓰기는 `SqlGuard.capLimit()`가 이를 처리한다.
- `MySqlConnector(config)`(공개)와 `MySqlConnector(config, dataSource)`(internal, 테스트용)가 있다. 풀 생성은 companion의 `createPool`. `checkConnection()`은 아직 `TODO()`(3.5).
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
- 테이블·함수 검사는 `guard/ReferenceCollector.collect(statement)`(리플렉션으로 트리 전체를 훑음)가 모은 것을 `SqlGuard`가 비교한다. 개인정보 추적(2.4)처럼 트리를 훑는 일에서 JSqlParser visitor만 믿으면 노드를 놓친다(`pipeline/CLAUDE.md`).
- `MySqlConnector`는 `config.allowedTables`로 `SqlGuard`를 만들고 `execute`의 검사 통과 뒤는 `TODO()`다(3.1). 빈 허용 목록 처리는 3.5.
- 읽기 전용 세션 테스트는 `MySqlConnector.createPool(config)`(internal)로 풀을 받아 writer 계정으로 INSERT를 보내고 오류 코드 1792를 기대한다. 그래서 풀의 JDBC `readOnly`는 끄고(켜면 드라이버가 먼저 거부해 오류 코드 0) 세션 변수로 읽기 전용을 건다(3.1, design 4장을 이에 맞춰 고쳤다).
- `MySqlConnector.execute`(3.1): 검사 → `run`(연결 빌림, 끝나면 rollback) → `query`. SQL 예외는 아직 그대로 던진다. 3.2가 `run`에서 잡아 분류한다. 결과 열의 `pii`는 아직 `false`(3.3).
- 결과 열의 `pii`(3.3): `MySqlConnector.markPii`가 실행한 SQL을 `PiiLineage`로 추적하고 `PiiColumnMarker`가 메타데이터와 합친다. 추적이 예외를 던지거나 항목을 열에 맞출 수 없으면 모두 가린다.
- **3.2 해결:** 시간 상한 시나리오 쿼리는 행을 실제로 훑는 `SELECT SUM(a.n + b.n + c.n) FROM numbers a, numbers b, numbers c`로 바꿨다(사람이 승인). MySQL 8.4는 조건 없는 교차 조인의 `COUNT(*)`를 행 수 곱으로 바로 계산하니, 느린 쿼리가 필요하면 `COUNT(*)`를 쓰지 않는다.

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

## 2026-10-28 - 2.1 실행 주석·힌트, 해석, 문 개수·종류 검사
- `SqlGuard.check()`를 구현했다: 원문 검사(실행 주석·힌트 → `NOT_SELECT`, 잠금·`INTO` → `NOT_SELECT`) → `parseStatements`(실패 → `PARSE_FAILED`, 0개도 `PARSE_FAILED`) → 문이 둘 이상이면 `MULTIPLE_STATEMENTS` → `Select`가 아니면 `NOT_SELECT` → 지금은 `statement.toString()`을 `Passed`로 돌려준다.
- 원문 검사는 새 `guard/RawSqlScanner`(internal). 실행 주석·힌트는 리터럴만 건너뛰고 보통 주석 안에서도 찾는다(넉넉히 거부). 잠금·`INTO`는 리터럴·주석을 공백으로 바꾼 뒤 정규식으로 찾아 서브쿼리 안까지 잡는다. 그래서 `FOR UPDATE`/`FOR SHARE`도 AST가 아니라 여기서 막힌다.
- 검사 오류의 `checkPoints`는 `["실행하려던 SQL"]`로 뒀다.
- `SqlGuardTest` 22개 중 15개 통과. 남은 7개는 테이블·함수 검사와 `MySqlConnector.execute`(2.2)다. `LimitRewriteTest`는 이제 `NotImplementedError`가 아니라 단언 실패(2.3).
- 바꾼 파일: `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/guard/SqlGuard.kt`, `.../guard/RawSqlScanner.kt`(새 파일), `pipeline/CLAUDE.md`
- 다음 반복(2.2): `SqlGuardTest`의 "DB에 닿지 않음"은 `MySqlConnector.execute`가 검사에서 걸리면 DB를 건드리지 않고 `Failure`를 돌려주게 해야 통과한다. 커넥터 쪽 `SqlGuard`의 허용 테이블은 `config.allowedTables`(빈 목록 처리는 3.5). 실행 부분은 3.1이니 검사 통과 뒤는 아직 `TODO()`로 둬도 된다.
---

## 2026-10-28 - 2.2 테이블 visitor와 거부 함수 검사
- 새 `guard/ReferenceCollector`(internal): 해석 트리의 JSqlParser 노드 필드를 리플렉션으로 모두 따라가 `Table`과 `Function`을 모은다. `TablesNamesFinder`는 `>` 비교식 안 서브쿼리 등을 건너뛰어 쓰지 않았다. `Column`·`AllTableColumns`의 `table`(별칭일 수 있음)은 테이블로 보지 않는다. SELECT 안의 SELECT가 아닌 문(CTE 본문의 DML)은 `NOT_SELECT`.
- CTE 범위: MySQL처럼 CTE 본문은 앞에 정의한 CTE만, RECURSIVE면 자기 자신도 본다. 바깥 쿼리는 그 WITH의 이름을 모두 본다. 스키마를 적은 이름은 CTE가 아니다. CTE 이름 비교는 대소문자를 가린다(어긋나면 테이블로 검사돼 거부 쪽).
- `SqlGuard`: 테이블(세 부분 이름 거부, 스키마는 설정 스키마와 대소문자 무시 비교, 이름은 허용 목록과 대소문자 무시) → 거부 함수(마지막 이름 부분을 대문자로) 순서. `cause`는 쓴 그대로의 테이블 이름, 함수 이름.
- `MySqlConnector.execute`: 검사에 걸리면 `Failure`, 통과 뒤는 `TODO()`(3.1).
- 새 `SqlGuardBypassTest`(6개)로 비교식·정렬·HAVING·함수 인자·CASE 안 서브쿼리, CTE 그림자(비재귀 본문, 뒤 CTE, 서브쿼리 범위, 스키마 접두어), 재귀 CTE, 별칭 컬럼, 세 부분 이름, 정렬·조인 조건 안 함수를 확인했다.
- `SqlGuardTest` 22개 모두 통과. `:pipeline:test`의 남은 실패는 `LimitRewriteTest` 6(2.3), `PiiLineageTest` 14(2.4), `GrantParserTest` 11·`ConnectionFailureClassifierTest` 7(3.4)뿐이다. `ktlintCheck`, 모든 컴파일 통과.
- 바꾼 파일: `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/guard/ReferenceCollector.kt`(새 파일), `.../guard/SqlGuard.kt`, `.../mysql/MySqlConnector.kt`, `pipeline/src/test/kotlin/io/askflow/pipeline/datasource/guard/SqlGuardBypassTest.kt`(새 파일), `pipeline/CLAUDE.md`
- 다음 반복(2.3): `SqlGuard.check()` 끝의 `GuardResult.Passed(statement.toString())` 앞에 LIMIT 다시 쓰기를 넣는다. UNION 끝 LIMIT 함정(`pipeline/CLAUDE.md`)을 본다.
---

## 2026-10-28 - 2.3 LIMIT 다시 쓰기와 다시 쓴 SQL
- `SqlGuard.capLimit()`: 최상위 LIMIT이 없거나 숫자 리터럴이 아니거나(`?`, `ALL`) `maxRows + 1`보다 크면 `maxRows + 1`로 바꾼다. OFFSET은 그대로 둔다. 큰 수는 `bigIntegerValue`로 비교한다.
- LIMIT을 둘 곳: 괄호 없는 UNION에서 `SetOperationList.limit`이 비고 마지막 `PlainSelect`에 LIMIT이 있으면 그것을 고친다(LIMIT 두 번 방지). 그 밖에는 최상위 `Select`(PlainSelect, SetOperationList, ParenthesedSelect)에 둔다.
- 다시 쓴 SQL은 그대로 `statement.toString()`이라 보통 주석은 빠진다(`SqlGuardTest` 보통 주석 통과).
- 새 `LimitRewriteEdgeTest`(4개): UNION 끝 LIMIT 한 번만, 괄호 친 UNION, 아주 큰 LIMIT, 괄호로 감싼 최상위 SELECT.
- `LimitRewriteTest` 7개, `SqlGuardTest` 전체 통과. `:pipeline:test`의 남은 실패는 `PiiLineageTest` 14(2.4), `GrantParserTest` 11·`ConnectionFailureClassifierTest` 7(3.4)뿐이다.
- 바꾼 파일: `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/guard/SqlGuard.kt`, `pipeline/src/test/kotlin/io/askflow/pipeline/datasource/guard/LimitRewriteEdgeTest.kt`(새 파일)
- 다음 반복(2.4): `PiiLineage(piiColumns).trace(sql)`를 구현한다. 트리를 훑을 때 visitor만 믿지 말고 `ReferenceCollector`처럼 빠짐없이 따라간다. LIMIT 다시 쓰기는 최상위 SELECT 항목을 바꾸지 않으니 개인정보 추적은 원문·다시 쓴 SQL 어느 쪽으로 해도 항목이 같다.
---

## 2026-10-28 - 2.4 개인정보 열 해석 트리 추적과 forLlm
- `PiiLineage.trace()`: 문을 해석해 SELECT마다 FROM 출처(기본 테이블, 파생 테이블·CTE, 모르는 것)로 범위를 만들고, 항목 식을 리플렉션으로 모두 훑어 COUNT(창 함수가 아닌 이름 하나짜리) 밖의 컬럼 참조를 출처까지 푼다. 테이블을 적은 컬럼은 그 별칭의 출처, 안 적은 컬럼은 보이는 모든 범위(바깥 포함)에서 찾고, 못 찾으면 `PII`. 식 안 서브쿼리는 항목 하나라도 `SAFE`가 아니면 `PII`.
- CTE 이름은 쓴 그대로 비교(`ReferenceCollector`와 같음), 열 이름 목록(`c(a, b)`, `t(a)`)은 자리로 붙이고 맞출 수 없으면 모두 `PII`. UNION은 자리마다 OR, 가지에 `*`가 있거나 수가 다르면 모두 `PII`. 재귀 CTE의 자기 참조는 풀지 않고 가린다(넉넉히 가림).
- `QueryResult.forLlm()`: `pii` 열을 빼고 이름만 `removedColumns`에 남긴다.
- `ReferenceCollector`의 리플렉션 도구를 새 `guard/JsqlTree`로 옮겨 같이 쓴다.
- 새 `PiiLineageEdgeTest`(6개): 열 이름 목록, 괄호 조인·상관 서브쿼리, CASE·창 함수, 서브쿼리를 펼친 별표·별표 섞인 UNION, 모르는 별칭·재귀 CTE, 같은 이름의 다른 테이블·CTE 그림자.
- `PiiLineageTest` 14개 통과. `:pipeline:test`의 남은 실패는 `GrantParserTest` 11·`ConnectionFailureClassifierTest` 7(3.4)뿐이다. `ktlintCheck`, 모든 컴파일 통과.
- 바꾼 파일: `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/guard/PiiLineage.kt`, `.../guard/JsqlTree.kt`(새 파일), `.../guard/ReferenceCollector.kt`, `.../datasource/QueryResult.kt`, `pipeline/src/test/kotlin/io/askflow/pipeline/datasource/guard/PiiLineageEdgeTest.kt`(새 파일), `pipeline/CLAUDE.md`
- 다음 반복(3.1): HikariCP 풀과 `execute`. 결과 열의 `pii`는 3.3에서 `PiiLineage`와 메타데이터를 합쳐 정한다. 3.1에서는 아직 `false`로 둬도 3.1 확인 시나리오에는 영향이 없다.
---

## 2026-10-28 - 3.1 HikariCP 풀과 execute
- `createPool`: HikariCP(`autoCommit = false`, 풀 크기 4, `connectionTimeout` 3초, `initializationFailTimeout = -1`), `connectionInitSql`은 `SET SESSION transaction_read_only = ON, SESSION max_execution_time = <ms>`. `SET SESSION TRANSACTION READ ONLY, ...`는 문법 오류라 연결이 하나도 안 만들어졌다.
- 읽기 전용: 풀의 JDBC `readOnly`를 켜면 드라이버가 INSERT를 먼저 거부해(오류 코드 0) 읽기 전용 세션 시나리오가 1792를 못 본다. 풀은 `readOnly = false` + `readOnlyPropagatesToServer=false`로 두고 세션 변수로 건다. design 4장을 이에 맞춰 고쳤다. `execute`는 실행마다 JDBC `readOnly`를 켜(드라이버 쪽 검사만) 실행하고 rollback한다.
- `execute`: `queryTimeout` = 상한 + 1초(올림), `maxRows` = 상한 + 1, 결과가 상한보다 많으면 행 없이 `ROW_LIMIT_EXCEEDED`(`cause` "상한 N행", `checkPoints` `askflow.target-db.max-rows`). 열 이름은 `getColumnLabel`.
- 새 `ReadOnlySessionIT`: JDBC `readOnly`를 켰다 반납한 연결도 DB가 쓰기를 거부하는지. `readOnlyPropagatesToServer=true`로 바꾸면 DELETE가 통과해 실패하는 것을 확인했다.
- `MySqlConnectorIT` 19개 중 13개 통과(3.1 확인 시나리오 모두). 남은 6개: 시간 상한·없는 컬럼·DB 꺼짐(3.2), `SELECT *` 두 개·화면용 결과(3.3). `:pipeline:test` 남은 실패는 `GrantParserTest` 11·`ConnectionFailureClassifierTest` 7(3.4), `ConnectionCheckIT` 11(3.5). `ktlintCheck`, 모든 컴파일 통과.
- 바꾼 파일: `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/mysql/MySqlConnector.kt`, `pipeline/src/integrationTest/kotlin/io/askflow/pipeline/datasource/mysql/ReadOnlySessionIT.kt`(새 파일), `openspec/changes/add-safe-sql-execution/design.md`, `pipeline/CLAUDE.md`
- 다음 반복(3.2): "알아 둘 패턴"의 **3.2 주의**(시간 상한 테스트 쿼리가 MySQL 8.4에서 바로 끝남)를 먼저 본다. 예외 분류는 `run`에서 한다. HikariCP 연결 대기 시간 초과(`SQLTransientConnectionException`)는 원인 사슬을 봐야 한다(DB 꺼짐 시나리오).
---

## 2026-10-28 - 3.2 실행 오류 분류 (막힘)
- `MySqlConnector.run`에서 `SQLException`을 잡아 분류했다: 원인 사슬에 `SQLTimeoutException`이나 3024 → `TIMEOUT`(`cause` "상한 1초", `askflow.target-db.query-timeout`), `SQLTransientConnectionException`이나 SQLState `08*` → `CONNECTION_FAILED`(`checkPoints` "홈의 연결 상태", `askflow.target-db.url`), 그 밖 → `DB_ERROR`(MySQL 메시지 원문, 비밀번호는 `****`로 바꿈). 커밋 37e6925. tasks.md 3.2는 체크하지 않았다.
- `MySqlConnectorIT`의 없는 컬럼, DB 꺼짐 시나리오는 통과한다.
- **막힌 이유:** 시간 상한 시나리오(`F-003 실행 시간 상한을 넘는 쿼리`)의 쿼리 `SELECT COUNT(*) FROM numbers a, numbers b, numbers c`가 MySQL 8.4에서 11ms 만에 `Success`로 끝나, 구현과 상관없이 통과할 수 없다. 이미 커밋된 시나리오 테스트를 고쳐야 하므로 사람에게 넘긴다.
- 확인한 것: 임시 테스트로 같은 설정(상한 1초)에서 `SELECT SUM(a.n + b.n + c.n) FROM numbers a, numbers b, numbers c`를 실행하면 1080ms 만에 `TIMEOUT`, `cause` "상한 1초", `checkPoints` `askflow.target-db.query-timeout`이 돌아왔다. 임시 테스트는 지웠다.
- 사람에게 제안: `MySqlConnectorIT.kt:89`의 쿼리를 위 `SUM(a.n + b.n + c.n)` 쿼리로 바꾸고, design.md 9장(테스트 전략)의 시간 상한 테스트 쿼리 문장도 같이 고친다. 바꾸면 3.2를 다시 확인해 체크하면 된다.
- 바꾼 파일: `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/mysql/MySqlConnector.kt`
---

## 2026-10-28 - 3.2 실행 오류 분류 (사람이 막힘 해결)
- 사람이 승인해 `MySqlConnectorIT.kt:89`의 시간 상한 쿼리를 `SELECT SUM(a.n + b.n + c.n) FROM numbers a, numbers b, numbers c`로 바꾸고, design.md 9장 문장도 고쳤다.
- 확인: `./gradlew :pipeline:integrationTest --tests '*MySqlConnectorIT*'`에서 시간 상한(1.02초 만에 `TIMEOUT`), 없는 컬럼, DB 꺼짐 시나리오가 통과한다. 남은 실패 3개(`SELECT 별표` 두 개, 화면용 결과)는 3.3 몫이다. tasks.md 3.2를 체크했다.
- 바꾼 파일: `pipeline/src/integrationTest/kotlin/io/askflow/pipeline/datasource/mysql/MySqlConnectorIT.kt`, `openspec/changes/add-safe-sql-execution/design.md`, `openspec/changes/add-safe-sql-execution/tasks.md`
- 다음 반복(3.3): `PiiLineage`의 `STAR` 항목을 JDBC 메타데이터로 판정한다. "알아 둘 패턴"의 별표 항목 수 맞추기 규칙을 먼저 본다.
---

## 2026-10-28 - 3.3 JDBC 메타데이터로 개인정보 열 표시
- `MySqlConnector.query`가 결과 열마다 `pii`를 채운다: 메타데이터의 `getTableName`·`getColumnName`(원래 테이블·컬럼, 계산한 열은 빈 값)과 `PiiLineage.trace(실행한 SQL)`를 새 `mysql/PiiColumnMarker`(internal)가 합친다. 둘 중 하나라도 개인정보면 가린다.
- 맞추기 규칙: 별표 없으면 항목 수 = 열 수일 때만 자리로 맞춘다. 별표 하나면 나머지 항목을 앞뒤에 두고 남는 열을 별표 몫으로 보고 메타데이터로만 판정한다(메타데이터 없으면 가림). 별표 둘 이상, 수가 안 맞음, 추적 예외면 모두 가린다. 설정의 스키마 접두어는 무시하고 대소문자도 무시한다.
- 새 `PiiColumnMarkerTest`(5개) 통과. `MySqlConnectorIT` 19개 모두 통과. `:pipeline:test`의 남은 실패는 `GrantParserTest` 11·`ConnectionFailureClassifierTest` 7(3.4)뿐이다. `ktlintCheck`, 모든 컴파일 통과.
- 바꾼 파일: `pipeline/src/main/kotlin/io/askflow/pipeline/datasource/mysql/MySqlConnector.kt`, `.../mysql/PiiColumnMarker.kt`(새 파일), `pipeline/src/test/kotlin/io/askflow/pipeline/datasource/mysql/PiiColumnMarkerTest.kt`(새 파일), `pipeline/CLAUDE.md`, `tasks.md`
- 다음 반복(3.4): `GrantParser`와 `ConnectionFailureClassifier`. "알아 둘 패턴"의 HikariCP 원인 사슬 주의와 1.4 기록의 `08004` 메모를 본다.
---
