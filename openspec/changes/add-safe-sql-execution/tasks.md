# Tasks

브랜치: `change/add-safe-sql-execution`. 테스트 이름에는 `F-003`과 시나리오 이름을 넣는다. 설계는 design.md, 동작은 `specs/`를 따른다.

## 1. 시나리오를 실패하는 테스트로 옮기기

- [x] 1.1 테스트가 컴파일될 골격을 만든다: pipeline에 JSqlParser, MySQL Connector/J, HikariCP, Testcontainers(MySQL) 의존성과 `integrationTest` 소스 세트(`check`에 묶음)를 추가하고, design 1·2장의 공개 타입(`DataSourceConnector`, `TargetDbConfig`, `ExecutionOutcome`, `QueryResult`, `LlmSafeResult`, `ExecutionError`와 오류 종류, `ConnectionStatus`, `SqlGuard`)을 본문 `TODO()`로 둔다. 확인: `./gradlew ktlintCheck compileKotlin compileTestKotlin compileIntegrationTestKotlin`, `./gradlew :pipeline:test`가 빠르게 끝난다
- [x] 1.2 `safe-sql-execution`의 검사 시나리오(SELECT 하나만, 실행 주석·힌트, 허용 테이블, 부작용 함수, 검사에 걸린 SQL은 DB에 닿지 않음)를 `pipeline/src/test`의 `SqlGuardTest`로 옮긴다. DB에 닿지 않음은 기록용 가짜 JDBC로 확인한다. 확인: `./gradlew :pipeline:test --tests '*SqlGuardTest*'`가 컴파일되고 실패한다
- [x] 1.3 행 수 상한의 LIMIT 다시 쓰기(없음, 작은 LIMIT, 큰 LIMIT, 설정 50)와 개인정보 열 해석 트리 시나리오(그대로, 별칭·함수, COUNT 안, 값을 돌려주는 집계, 서브쿼리를 거친 열)를 `LimitRewriteTest`, `PiiLineageTest`로 옮긴다. 확인: `./gradlew :pipeline:test --tests '*LimitRewriteTest*' --tests '*PiiLineageTest*'`가 실패한다
- [x] 1.4 `SHOW GRANTS` 줄 해석(읽기 전용, INSERT, ALL PRIVILEGES `*.*`, 역할 줄)과 연결 실패 원인 분류(1045, 통신 오류, 1049/1044)를 `GrantParserTest`, `ConnectionFailureClassifierTest`로 옮긴다. 확인: `./gradlew :pipeline:test --tests '*GrantParserTest*' --tests '*ConnectionFailureClassifierTest*'`가 실패한다
- [x] 1.5 MySQL이 필요한 시나리오를 `pipeline/src/integrationTest`의 `MySqlConnectorIT`로 옮긴다: 테스트용 스키마(orders, customers, numbers)와 reader·writer 계정을 만들고, 읽기 전용 세션, 실행 시간 상한(교차 조인), 행 수 상한 초과와 상한 안, 성공 결과 형식, 없는 컬럼 DB 오류, DB 꺼짐 연결 실패, `SELECT *` 개인정보 열, 화면용 결과는 그대로, 다시 쓴 SQL과 원문의 결과 일치(CTE, UNION, 윈도 함수, 날짜 함수). 확인: `./gradlew :pipeline:integrationTest`가 실패한다
- [x] 1.6 연결 확인 시나리오를 `ConnectionCheckIT`로 옮긴다: 설정한 DB에 연결, 비밀번호 틀림, DB에 닿지 않음, 스키마 없음, 허용 테이블이 스키마에 없음, 허용 테이블 비움, 읽기 전용 계정·쓰기 권한·전체 권한 경고, 나중에 살아난 DB. 확인: `./gradlew :pipeline:integrationTest --tests '*ConnectionCheckIT*'`가 실패한다
- [x] 1.7 api 시나리오를 테스트로 옮긴다: `TargetDbPropertiesTest`(상한 기본값, 0 이하 값이면 시작 실패와 설정 키), `ConnectionStatusControllerTest`(정상 상태, 용어 수 0, 실패 원인과 확인할 설정, 응답에 비밀번호 없음), 연결 실패에도 컨텍스트가 뜨는지(`ApiApplicationTests`). 확인: `./gradlew :api:test`가 실패한다
- [x] 1.8 홈 연결 상태 시나리오(연결됨, 쓰기 권한 경고, 연결 실패, 상태 API에 닿지 않음)를 `web/src/connection/ConnectionStatus.test.tsx`로 옮긴다. 응답 타입은 design 8장 형식으로 `web/src/connection/types.ts`에 둔다. 확인: `npm --prefix web run typecheck`는 통과하고 `npm --prefix web run test`는 이 테스트만 실패한다

## 2. SQL 검사 (pipeline, 단위)

- [x] 2.1 실행 주석·힌트 검사, 해석, 문 개수·종류 검사를 구현한다. 확인: `SqlGuardTest`의 SELECT 하나만, 실행 주석과 힌트 시나리오 통과
- [x] 2.2 테이블 visitor(CTE 이름 제외, 스키마 비교)와 거부 함수 검사를 구현한다. 확인: `SqlGuardTest` 전체 통과
- [x] 2.3 LIMIT 다시 쓰기(`maxRows + 1`)와 다시 쓴 SQL 만들기를 구현한다. 확인: `LimitRewriteTest` 통과, 보통 주석 시나리오에서 다시 쓴 SQL에 주석이 없음
- [x] 2.4 개인정보 열 해석 트리 추적(별칭, 서브쿼리·CTE, COUNT만 예외, 모르면 가림)과 `QueryResult.forLlm()`을 구현한다. 확인: `PiiLineageTest` 통과

## 3. MySQL 커넥터 (pipeline, 통합)

- [x] 3.1 HikariCP 풀(읽기 전용, `connectionInitSql`, `initializationFailTimeout = -1`)과 `execute`(읽기 전용 트랜잭션, `queryTimeout`, `maxRows`, rollback)를 구현한다. 확인: `MySqlConnectorIT`의 읽기 전용 세션, 성공 결과 형식, 행 수 상한, 다시 쓴 SQL 결과 일치 통과
- [x] 3.2 실행 오류 분류(3024·`SQLTimeoutException` → 시간 상한, 통신 오류 → 연결 실패, 그 밖 → DB 오류)와 오류 메시지에서 비밀 값 빼기를 구현한다. 확인: `MySqlConnectorIT`의 시간 상한, 없는 컬럼, DB 꺼짐 시나리오 통과
- [x] 3.3 JDBC 메타데이터로 개인정보 열 표시를 더한다. 확인: `MySqlConnectorIT`의 `SELECT *`, 화면용 결과 시나리오 통과, `./gradlew :pipeline:integrationTest --tests '*MySqlConnectorIT*'` 전체 통과
- [x] 3.4 `GrantParser`와 연결 실패 원인 분류를 구현한다. 확인: `GrantParserTest`, `ConnectionFailureClassifierTest` 통과
- [x] 3.5 `checkConnection()`(권한 확인, 스키마·허용 테이블 확인, 빈 허용 목록이면 스키마 테이블 사용)을 구현한다. 확인: `./gradlew :pipeline:test :pipeline:integrationTest` 전체 통과

## 4. api 연결

- [x] 4.1 `askflow.target-db` 설정 바인딩·검증과 커넥터 빈 등록, `application.yml` 기본값을 넣는다. 확인: `TargetDbPropertiesTest`, `ApiApplicationTests` 통과
- [x] 4.2 `TermCountProvider`(기본 0)와 `GET /api/connection/status`를 구현한다. 확인: `ConnectionStatusControllerTest` 통과, `./gradlew ktlintCheck build` 통과
- [x] 4.3 `.env.example`의 `ASKFLOW_READER_PASSWORD` 설명과 `README.md`의 분석 대상 DB 설정(파일럿 설정 덮어쓰기 포함)을 고친다. 확인: 로컬에서 `docker compose up -d` 뒤 `./gradlew :api:bootRun`을 띄우고 `curl localhost:8080/api/connection/status`가 연결됨과 테이블 수를 돌려준다

## 5. 홈 연결 상태 (web)

- [x] 5.1 상태 조회 함수와 `ConnectionStatus` 컴포넌트를 만들어 `App`에 넣고, Vite 개발 서버에 `/api` 프록시를 둔다. 확인: `npm --prefix web run lint`, `typecheck`, `test` 통과
- [x] 5.2 api와 web을 함께 띄워 홈에서 연결됨과 테이블 수가 보이는지, `.env` 없이(비밀번호 틀림) 띄우면 연결 실패와 확인할 설정이 보이는지 확인하고 `progress.md`에 결과를 적는다. 확인: 두 상태 모두 화면에서 확인

## 6. 검토 지적 고치기 (7.1 독립 검토의 막음)

먼저 실패하는 테스트를 더하고 고친다. 이미 커밋된 테스트는 바꾸지 않는다.

- [x] 6.1 옵티마이저 힌트·실행 주석 검사가 보통 주석(`--`, `#`, `/* */`) 안의 따옴표에 속는 문제를 고친다. 지금 `RawSqlScanner.findExecutableComment`는 따옴표만 건너뛰고 주석은 건너뛰지 않아, 주석 안의 따옴표 하나가 뒤의 `/*+ ... */` 힌트를 문자열로 착각하게 만든다. JSqlParser는 SELECT 뒤의 힌트를 다시 쓴 SQL에 남기므로 `MAX_EXECUTION_TIME` 힌트가 DB에 닿는다. 스캐너가 주석과 리터럴을 같은 규칙(`codeOnly`)으로 다루게 하고, 다시 쓴 트리에 힌트가 남아 있으면 거부한다(두 겹). 확인: `SqlGuardTest`에 보통 주석 안 따옴표 뒤의 힌트·실행 주석이 거부되는 테스트를 더하고 통과, `./gradlew :pipeline:test` 통과
- [x] 6.2 개인정보 열 추적이 서브쿼리의 같은 별칭에 속는 문제를 고친다. 지금 `PiiLineage`는 테이블을 적은 컬럼을 가장 안쪽의 같은 이름 출처에서만 찾고, 기본 테이블은 어떤 컬럼이든 가진다고 본다. MySQL은 안쪽 테이블에 없는 컬럼을 바깥 범위의 같은 별칭에서 찾으므로(로컬 MySQL 8.4에서 확인), 바깥 개인정보 테이블과 같은 별칭을 안쪽 서브쿼리에 쓰면 개인정보 열이 `SAFE`가 된다. 테이블을 적은 컬럼도 바깥 범위까지 같은 이름 출처를 모두 보고, 하나라도 개인정보면 가린다. 확인: `PiiLineageTest`에 이 경우를 더하고 통과, `MySqlConnectorIT`에 같은 SQL의 `forLlm()` 결과에서 그 열이 빠지는 테스트를 더하고 `./gradlew :pipeline:integrationTest` 통과
- [x] 6.3 LLM에 보낼 실행 오류를 따로 둔다(`safe-sql-execution` "LLM에 보낼 실행 오류", design 7장). `ExecutionError`에 DB 오류 코드와 SQLState를 더하고, `ExecutionError.forLlm(): LlmSafeError`(생성자 internal)를 만든다. DB 오류는 종류·한국어 메시지·오류 코드·SQLState만 남기고 MySQL 메시지 원문을 버린다. 검사·상한 오류는 원인을 남긴다. 확인: 단위 테스트로 세 시나리오(값을 담은 DB 오류, 화면용 오류는 그대로, 검사에 걸린 오류)를 더하고 통과, `MySqlConnectorIT`에 개인정보 컬럼을 `GTID_SUBSET`에 넘긴 SQL의 `forLlm()` 오류에 그 값이 없는 테스트를 더하고 `./gradlew :pipeline:test :pipeline:integrationTest` 통과
- [x] 6.4 뷰와 생성 컬럼이 개인정보 컬럼을 다른 이름으로 내보내면 가리지 못하는 문제를 고친다(7.1 재검토의 막음, design 7장 "뷰와 생성 컬럼"). `information_schema.VIEWS`의 `VIEW_DEFINITION`을 `PiiLineage`로 추적하고(뷰가 뷰를 쓰면 반복), `information_schema.COLUMNS`의 `GENERATION_EXPRESSION`이 개인정보 컬럼을 참조하는지 보아 "실제 개인정보 컬럼"을 만든다. 정의가 비었거나 해석에 실패하면 그 뷰의 모든 열 또는 그 생성 컬럼을 개인정보로 본다. 연결 확인마다 다시 읽고, 아직 읽지 않았으면 `execute`가 먼저 읽는다. 해석 트리와 JDBC 메타데이터 판정 모두 이 컬럼 집합을 쓴다. 이미 커밋된 테스트는 바꾸지 않는다. 확인: 단위 테스트(뷰 정의·생성식에서 개인정보 컬럼 계산, 해석 실패 시 전부 가림)와 통합 테스트로 세 시나리오(뷰를 거친 열, 개인정보 컬럼으로 만든 생성 컬럼, 정의를 읽을 수 없는 뷰)를 더하고 `./gradlew :pipeline:test :pipeline:integrationTest` 통과
- [x] 6.5 6.4의 정의 추적을 걷어 내고, 뷰에서 온 열, 생성 컬럼, 저장 함수를 부른 열을 모두 개인정보로 보는 방식으로 바꾼다(7.1 3차 검토의 막음 4건, design 7장 "뷰와 생성 컬럼, 저장 함수"). `PiiColumnResolver`와 뷰 정의·생성식 해석을 지우고, 실행마다 같은 연결에서 `information_schema`로 뷰, 생성 컬럼, 저장 함수 목록을 읽는다. 읽지 못하면 실행하지 않고 연결 실패를 돌려준다. 해석 트리와 JDBC 메타데이터 판정 모두 이 목록을 쓰고, 테이블과 컬럼은 문자열이 아니라 쌍으로 다룬다. `pipeline/CLAUDE.md`의 개인정보 판정 설명도 고친다. `PiiViewIT`는 스펙에 맞춰 사람이 고쳐 두었다(실패하는 상태). 확인: `PiiViewIT` 전체 통과, `./gradlew ktlintCheck :pipeline:test :pipeline:integrationTest` 통과
- [x] 6.6 개인정보 판정을 "안전이 확인된 열만 보낸다"로 뒤집는다(7.1 4차 검토의 막음 3건, design 7장 "판정 방향"). 해석 트리에서 처음 보는 노드와 허용 목록 밖의 함수를 개인정보로 보고, 내장 함수 허용 목록을 둔다. 실행마다 읽는 `information_schema` 조회에 허용 테이블의 컬럼 목록을 더하고, 목록에서 찾지 못한 이름은 개인정보로 본다. 검사기는 사용자 변수와 LAST_INSERT_ID를 "허용하지 않은 함수"로 거부하고, 스키마는 대소문자까지 정확히 같아야 통과시킨다. 목록을 읽지 못하면 사용자 SQL을 보내지 않고 연결 실패를 돌려주는 단위 테스트를 더한다. `DEFAULT (식)` 컬럼은 설정 책임이라고 README에 적는다. 먼저 스펙의 새 시나리오(대소문자만 다른 스키마, 사용자 변수, LAST_INSERT_ID, 허용 목록 안의 함수, 허용 목록 밖의 함수, 확인하지 못한 이름)를 실패하는 테스트로 더한다. 테스트의 SQL은 시나리오를 확인할 만큼만 짧게 쓴다. 이미 커밋된 테스트는 바꾸지 않으니, 그 테스트들이 안전으로 보는 함수와 단위 테스트의 사용법은 그대로 통과해야 한다. 확인: `./gradlew ktlintCheck :pipeline:test :pipeline:integrationTest` 통과
- [ ] 6.7 5차 검토의 막음 3건을 "확인되지 않으면 가린다" 원칙으로 고친다(design 7장 "출처 이름"). LATERAL 파생 테이블에서 나온 열은 확인하지 못한 것으로 보아 가린다. 테이블과 CTE 이름이 실행 때 읽은 목록이나 CTE 이름과 대소문자만 다르게 맞으면 그 출처를 확인하지 못한 것으로 보아 열을 모두 가린다(검사기의 CTE 판정은 지금처럼 정확히 같을 때만). 실행 경로가 컬럼 목록을 판정기에 반드시 넘기는지 커넥터 수준 테스트(가짜 JDBC 또는 통합 테스트)로 고정해, 목록에 없는 이름이 `forLlm()`에서 빠지는 것을 확인한다. 검사기의 사용자 변수·LAST_INSERT_ID·스키마 거부가 CTE 본문과 서브쿼리 안에서도 걸리는 테스트를 더한다. 먼저 실패하는 테스트를 더하고, 테스트의 SQL은 원리를 확인할 만큼만 짧게 쓴다. 이미 커밋된 테스트는 바꾸지 않는다. 확인: `./gradlew ktlintCheck :pipeline:test :pipeline:integrationTest` 통과

## 7. 검토와 change 완료 조건

- [ ] 7.1 구현에 참여하지 않은 리뷰어(`/code-review` 또는 리뷰 서브에이전트)로 하네스 3-3 검토를 한다. 특히 3번(SELECT만 실행, 행 수·시간 상한, 개인정보 컬럼 가리기를 피해 가는 경로 없음)을 본다. 막는 지적은 이 파일에 작업으로 추가한다. 확인: 막는 지적 0건
- [ ] 7.2 `conventions/harness.md` 4장의 change 완료 조건을 확인한다. 평가 세트는 아직 없어 해당 없음을 PR 설명에 적는다. 요구사항이 바뀌었으면 PRD F-003(특히 8장 "남은 것"의 상한 기본값과 쓰기 권한 확인 방법)을 고친다. 확인: `./gradlew ktlintCheck build`, `npm --prefix web run lint && npm --prefix web run typecheck && npm --prefix web run test`, `openspec validate --all --strict --no-interactive` 모두 통과

## Workflow follow-up

- 완료 조건을 채우면 change 브랜치에서 `/opsx:archive`로 스펙을 `openspec/specs/`에 합친다.
- `develop`으로 PR을 열고 CI 통과 후 merge한다.
