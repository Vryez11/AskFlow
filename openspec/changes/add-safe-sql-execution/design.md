# Design

## Context

- 왜 하는지는 proposal.md, 동작은 `specs/data-connection`, `specs/safe-sql-execution`을 따른다. 세 겹 안전의 큰 틀은 [아키텍처 6장](../../../docs/mvp/05-architecture.md#6-분석-대상-db와-sql-안전)이다.
- 지금 코드: `pipeline`은 빈 순수 Kotlin 모듈, `api`는 actuator만 있는 Spring Boot 4.1 앱, `web`은 제목만 있는 React 앱이다. 서비스 DB(`askflow`)와 Flyway는 아직 없고, 이 change에서도 쓰지 않는다.
- 로컬 MySQL(`docker/mysql/init/01-databases.sh`)에는 이미 `olist`에 SELECT, SHOW VIEW만 있는 `askflow_reader` 계정이 있다. Olist 데이터 적재(`scripts/seed/`)는 F-002에서 만든다. 그래서 이 change의 테스트는 Olist가 아닌 작은 테스트용 스키마를 쓴다.
- Stop 훅은 `./gradlew :pipeline:test`만 돌린다. 빠르게 유지해야 한다. CI는 `./gradlew ktlintCheck build`를 돌린다.

## Goals / Non-Goals

**Goals:**

- 생성 SQL은 한 경로(`DataSourceConnector.execute`)로만 실행되고, 그 경로를 피해 갈 수 없게 한다. 검사를 못 하면 막는다(fail-closed).
- 세 겹이 서로 기대지 않게 한다. SQL 검사에 틈이 있어도 세션이, 세션 설정이 빠져도 계정이 막는다.
- LLM으로 나가는 결과는 타입으로 구분해, 개인정보 열을 뺀 결과만 LLM 쪽에 넘길 수 있게 한다.

**Non-Goals:**

- LLM 호출, 용어 사전 파일 형식(F-002). 용어 수는 이 change에서 늘 0이다.
- 보고서·대시보드 화면의 실행 오류 표시(F-004, F-005). 오류 형식만 정한다.
- MySQL 외 DB, 연결 정보 편집 화면(PRD 6장 제외 범위).
- 실행 기록(`logs/runs/`)과 재현 명령(F-002).
- OpenAPI에서 TypeScript 타입 만들기(아키텍처 10장). 엔드포인트가 하나뿐이라 이 change에서는 web 타입을 손으로 쓰고, 엔드포인트가 늘어나는 F-001에서 생성 도구를 들인다.

## Decisions

### 1. 커넥터 구현체를 pipeline에 둔다

`DataSourceConnector` 인터페이스, SQL 검사기, MySQL 구현체(JDBC + HikariCP)를 모두 `pipeline`에 둔다. Spring에 기대지 않는다. api는 설정을 바인딩해 구현체를 만들어 빈으로 등록만 한다.

- 대안: 구현체를 api에 두고 Spring `JdbcTemplate`을 쓴다. eval 모듈(F-002)과 나중의 worker도 같은 커넥터가 필요한데, 그러면 api에 기대야 해서 버렸다.
- 패키지: `io.askflow.pipeline.datasource`(인터페이스, 설정, 결과·오류 타입), `...datasource.guard`(SQL 검사), `...datasource.mysql`(구현체).

공개 API는 이 정도다.

```kotlin
interface DataSourceConnector {
    fun execute(sql: String): ExecutionOutcome   // 검사 → 다시 쓴 SQL 실행 → 결과 또는 오류
    fun checkConnection(): ConnectionStatus      // 연결, 권한, 허용 테이블 확인
}
sealed interface ExecutionOutcome { data class Success(val result: QueryResult); data class Failure(val error: ExecutionError) }
class QueryResult(columns, rows, rowCount, elapsed, executedSql) { fun forLlm(): LlmSafeResult }
class ExecutionError(kind, message, cause, checkPoints, dbErrorCode, sqlState) { fun forLlm(): LlmSafeError }
```

`execute` 말고 SQL 문자열을 받아 실행하는 공개 메서드는 두지 않는다. 연결 확인의 고정 조회(`SHOW GRANTS`, `information_schema` 조회)는 구현체 안의 private 코드다.

### 2. 설정은 Spring 설정 파일의 `askflow.target-db`

```yaml
askflow:
  target-db:
    url: jdbc:mysql://localhost:${MYSQL_PORT:3306}/olist
    schema: olist
    username: askflow_reader
    password: ${ASKFLOW_READER_PASSWORD:}
    allowed-tables: []          # 비우면 스키마의 모든 테이블·뷰
    pii-columns: []             # "table.column"
    max-rows: 1000
    query-timeout: 10s
```

- api의 `@ConfigurationProperties` + `@Validated`(`@Positive`)로 받아 pipeline의 `TargetDbConfig`로 옮긴다. 잘못된 값이면 Spring이 설정 키를 담아 시작을 멈춘다.
- 파일럿은 회사별 설정 파일을 `SPRING_CONFIG_ADDITIONAL_LOCATION`으로 덧붙인다. 비밀번호는 늘 환경 변수다.
- 비밀번호 기본값을 빈 문자열로 둬서, CI처럼 값이 없어도 앱은 뜨고 연결 상태만 "연결 실패"가 된다.
- 기본값 1,000행·10초의 근거: 집계 결과를 차트·표로 보여 주는 용도라 1,000행이면 넉넉하고, 생성 1회 2분 목표(PRD 5장)에서 위젯 4개 × 10초는 여유가 있다. 저장 대시보드 5초 목표와는 F-005에서 다시 맞춘다.
- 대안: 별도 `askflow.yml`을 직접 읽는다. Spring의 검증·덮어쓰기·환경 변수 치환을 다시 만들어야 해서 버렸다. pipeline은 설정 파일 형식을 모르고 `TargetDbConfig`만 받는다.

### 3. SQL 검사는 JSqlParser로 해석하고, 다시 쓴 SQL을 실행한다

순서: 실행 주석·힌트 검사 → 해석 → 문 개수와 종류 → 테이블 → 함수 → LIMIT 다시 쓰기 → 개인정보 열 추적.

- **실행 주석·힌트:** 해석 전에 원문에서 문자열 리터럴 밖의 `/*!`, `/*+`를 찾으면 거부한다. JSqlParser는 이것을 주석으로 버리지만 MySQL은 실행하기 때문이다.
- **해석:** `CCJSqlParserUtil.parseStatements`. 문이 하나가 아니면 "여러 문", 해석 실패는 "SQL 해석 실패".
- **종류:** 최상위가 `Select`(PlainSelect, SetOperationList, ParenthesedSelect, WITH 포함)일 때만 통과. `FOR UPDATE`/`FOR SHARE`, `INTO`가 있으면 거부. JSqlParser가 모르는 구문은 해석 실패로 막힌다(fail-closed).
- **테이블:** 직접 만든 visitor로 서브쿼리, 조인, CTE 본문, WHERE 안 서브쿼리까지 `Table` 노드를 모은다. 그 쿼리 범위의 CTE 이름은 뺀다. 스키마를 적었으면 설정 스키마와 같아야 하고, 이름은 허용 테이블 목록(대소문자 무시)에 있어야 한다. `TablesNamesFinder`는 CTE와 스키마 처리가 맞지 않아 쓰지 않는다.
- **함수:** 모든 `Function` 노드 이름을 대문자로 보고 거부 목록(SLEEP, BENCHMARK, LOAD_FILE, GET_LOCK, RELEASE_LOCK, RELEASE_ALL_LOCKS)과 비교한다.
- **LIMIT:** 최상위 쿼리에 LIMIT이 없거나 `maxRows + 1`보다 크면 `maxRows + 1`로 바꾼다. OFFSET은 둔다. 한 행 더 가져와야 상한을 "넘었는지" 알 수 있다.
- **다시 쓴 SQL:** 해석 트리를 `toString()`으로 다시 쓴 문장을 실행한다. 주석이 사라지고, 검사한 트리와 실행하는 문장이 같아진다. 대안(원문 실행)은 주석·파서 차이로 검사와 실행이 어긋날 수 있어 버렸다. 다시 쓴 문장이 MySQL에서 같은 뜻인지는 통합 테스트로 확인한다.

### 4. 세션: 읽기 전용, DB와 앱 양쪽 시간 상한

- HikariCP `autoCommit = false`, 풀 크기 4, `connectionTimeout` 3초. `initializationFailTimeout = -1`로 두어 DB가 없어도 풀 생성이 실패하지 않는다(앱은 뜬다).
- `connectionInitSql`: `SET SESSION transaction_read_only = ON, SESSION max_execution_time = <ms>`. `SET SESSION TRANSACTION READ ONLY`는 다른 변수와 한 문에 묶을 수 없다.
- 읽기 전용은 DB 세션에 건다. 풀의 JDBC `readOnly`는 끄고 Connector/J `readOnlyPropagatesToServer=false`를 둔다. JDBC `readOnly`를 풀에 켜면 드라이버가 쓰기를 먼저 거부해 세션 겹을 따로 확인할 수 없고, 반납 때 풀이 `readOnly`를 되돌리며 서버에 `READ WRITE`를 보낼 수 있다.
- 실행마다 JDBC `readOnly`를 켜(드라이버 쪽 검사, 서버로는 보내지 않음) 세션의 읽기 전용 트랜잭션에서 실행하고 끝나면 rollback한다.
- 앱 쪽 상한: `Statement.queryTimeout`을 상한 + 1초로, `maxRows`를 `maxRows + 1`로 건다. LIMIT 다시 쓰기가 빠져도 가져오는 행 수가 묶인다.
- 오류 분류: MySQL 3024(`max_execution_time` 초과)와 `SQLTimeoutException`은 실행 시간 상한 초과, 행이 `maxRows`를 넘으면 행 수 상한 초과, 통신 오류(SQLState `08*`)는 연결 실패, 그 밖은 DB 오류(MySQL 메시지를 원인에, 오류 코드와 SQLState를 따로 담는다). 원인의 MySQL 메시지는 화면용이고 LLM에는 보내지 않는다(7장).

### 5. 권한 확인은 `SHOW GRANTS FOR CURRENT_USER()`

- 줄마다 `GRANT <권한들> ON <범위> TO ...`를 해석한다. 범위가 `*.*`이거나 설정 스키마(`` `olist`.* ``, `olist.<table>`)면 본다. 권한이 {SELECT, SHOW VIEW, USAGE} 밖이면 경고 목록에 넣는다. 허용 목록 방식이라 동적 권한(예: BACKUP_ADMIN)도 경고가 된다. 넉넉히 경고하는 쪽을 골랐다.
- 역할(`GRANT role TO user`) 줄이 있으면 역할의 권한까지는 보지 않고 "권한을 끝까지 확인하지 못함" 경고를 남긴다.
- 대안: `information_schema.SCHEMA_PRIVILEGES`·`USER_PRIVILEGES`. 계정이 자기 권한을 다 볼 수 있다는 보장이 없고 역할을 반영하지 않아 버렸다.

### 6. 연결 상태는 조회할 때마다 확인한다

- `checkConnection()`은 연결을 하나 빌려 권한 확인, 스키마 존재, 허용 테이블 확인(`information_schema.TABLES`)을 한다. 빈 허용 목록이면 스키마의 테이블·뷰 수가 테이블 수다.
- 실패 원인과 확인할 설정 키: 인증(1045) → `askflow.target-db.username`, `ASKFLOW_READER_PASSWORD`. 접속 불가(통신 오류) → `askflow.target-db.url`. 스키마 없음·권한 없음(1049, 1044) → `askflow.target-db.schema`. 없는 허용 테이블 → `askflow.target-db.allowed-tables`.
- 홈이 열릴 때 한 번 부르는 정도라 캐시하지 않는다. `connectionTimeout` 3초로 화면이 오래 기다리지 않는다.
- 허용 테이블 목록은 검사기에도 필요하다. 빈 목록이면 첫 연결 확인에서 읽은 스키마의 테이블 목록을 쓰고, 아직 연결된 적이 없으면 `execute`는 연결 실패를 돌려준다.

### 7. 개인정보 열: 해석 트리 추적 + JDBC 메타데이터, 하나라도 맞으면 가린다

- **해석 트리:** 최상위 SELECT 항목마다 참조한 컬럼을 별칭으로 테이블까지 풀고, 서브쿼리·CTE는 그 안의 SELECT 항목 별칭으로 이어 간다. 개인정보 컬럼을 COUNT 밖에서 참조하면 개인정보 열이다. 테이블 없이 쓴 컬럼은 FROM의 어느 테이블에든 같은 이름의 개인정보 컬럼이 있으면 개인정보로 본다. `*`, 풀 수 없는 경우는 개인정보로 본다(fail-closed).
- **JDBC 메타데이터:** Connector/J의 `getTableName`/`getColumnName`(원래 테이블·컬럼)이 개인정보 컬럼이면 개인정보 열이다. `SELECT *`는 이 쪽이 정확하다.
- **뷰와 생성 컬럼, 저장 함수:** 정의를 따라가지 않고 모두 개인정보로 본다. 처음(6.4)에는 뷰 정의와 생성식을 해석 트리로 따라갔으나, 바깥 스키마의 뷰, 저장 함수, 이름에 점이 든 뷰 열, 확인 사이에 바뀐 정의처럼 놓치는 경우가 검토마다 나와(7.1 3차 검토) fail-closed로 바꿨다.
  - 실행할 때마다 같은 연결에서 `information_schema`를 한 번 조회해 설정 스키마의 뷰 이름, 생성 컬럼(`GENERATION_EXPRESSION`이 빈 문자열이 아닌 컬럼), 저장 함수 이름을 읽는다. 읽지 못하면 실행하지 않고 연결 실패를 돌려준다.
  - 해석 트리: 뷰를 출처로 둔 열은 모든 컬럼이 개인정보 컬럼인 테이블에서 온 것으로 본다(COUNT 예외는 그대로). 생성 컬럼은 개인정보 컬럼으로 본다. 저장 함수나 스키마를 붙인 함수를 부른 항목은 개인정보로 본다.
  - JDBC 메타데이터: `getTableName`이 뷰 이름이거나 (테이블, 컬럼)이 생성 컬럼이면 개인정보로 본다.
  - 테이블과 컬럼은 `"table.column"` 문자열이 아니라 쌍으로 다룬다. 이름에 점이 들어갈 수 있다.
  - 대가: 뷰의 값은 LLM 서술에 쓰지 못한다(화면에는 보인다). Olist에는 뷰가 없다. 파일럿에서 꼭 필요하면 운영자가 안전한 열을 직접 적는 설정을 MVP 이후에 둔다.
- **판정 방향: 안전이 확인된 열만 보낸다.** 처음에는 개인정보 컬럼을 찾으면 가리고 못 찾으면 안전으로 봤다. 그래서 판정기가 모르는 문법이나 이름을 만나면 늘 새는 쪽으로 틀렸고, 검토마다 새 우회가 나왔다(7.1 4차 검토). 기본값을 "가림"으로 뒤집는다.
  - 해석 트리: 열이 안전하려면 모든 잎이 리터럴이거나 확인된 일반 컬럼이고, 모든 함수가 내장 함수 허용 목록(`guard`에 코드로 둔다)에 있어야 한다. 해석 트리에서 처음 보는 노드는 안전하지 않다. COUNT는 지금처럼 예외다.
  - 허용 목록: 분석에 흔한 날짜, 문자열, 숫자, 조건(CASE, IF, COALESCE 등), 형 변환, SUM·AVG·COUNT 같은 값을 숨기는 집계, 순위 창 함수로 시작한다. 값을 그대로 돌려줄 수 있는 집계(MIN, MAX, GROUP_CONCAT 등)는 넣지 않는다. 이미 커밋된 테스트가 안전으로 보는 함수는 넣는다. 평가 세트에서 많이 가려지면 스펙 검토를 거쳐 늘린다.
  - 컬럼 확인: 실행마다 읽는 `information_schema` 조회에 허용 테이블의 컬럼 목록을 더한다. SQL에 쓴 이름은 대소문자만 무시하고 목록과 맞춘다. 맞는 것이 없으면 확인하지 못한 컬럼이다. MySQL은 악센트까지 무시해 찾기 때문에, 비슷한 이름으로 판정을 비껴가는 일을 이 규칙이 막는다.
  - 검사기: 사용자 변수(읽기, 대입)와 LAST_INSERT_ID를 거부한다. 다른 스키마 비교는 대소문자를 구분해 정확히 같은 이름만 허용한다.
  - 기본값이 `DEFAULT (식)`인 컬럼은 생성 컬럼이 아니라 일반 컬럼으로 보인다. 개인정보 컬럼을 복사해 두는 컬럼이 있으면 운영자가 설정의 개인정보 컬럼에 적어야 한다(README에 적는다).
- 두 방법 중 하나라도 개인정보라고 하면 가린다. `*`는 해석 트리에서 "알 수 없음"이 되므로, `*` 확장 열은 메타데이터로 판정하고 메타데이터도 비어 있으면 가린다.
- `QueryResult.forLlm()`이 `LlmSafeResult`를 만드는 유일한 방법이다. F-002의 `LlmClient`는 `QueryResult`가 아니라 `LlmSafeResult`만 받게 한다.
- **오류도 같다.** MySQL은 일부 함수(예: `GTID_SUBSET`)에서 인자 값을 오류 메시지에 그대로 넣는다. 그래서 DB 오류 원문은 개인정보 값을 담을 수 있다. `ExecutionError.forLlm()`이 `LlmSafeError`(생성자 internal)를 만드는 유일한 방법이다. DB 오류는 종류, 한국어 메시지, 오류 코드, SQLState만 남기고 MySQL 메시지 원문은 버린다. 값을 내보내는 함수를 거부 목록으로 하나씩 막는 방식은 빠지는 함수가 생기므로 쓰지 않는다. 검사·상한 오류의 원인(거부한 테이블·함수 이름, 상한 값)은 SQL과 설정에서 온 것이라 남긴다. F-002의 SQL 수정 루프는 `ExecutionError`가 아니라 `LlmSafeError`만 받는다.

### 8. API와 화면

- `GET /api/connection/status` → `{ state: "CONNECTED" | "FAILED", tableCount, termCount, writePrivileges: string[], warnings: string[], failure?: { reason, message, checkKeys: string[] }, checkedAt }`.
- 용어 수는 api의 `TermCountProvider` 빈에서 가져온다. 이 change의 기본 구현은 0을 돌려주고 F-002가 바꾼다.
- web: `web/src/connection/` 아래에 상태 조회 함수와 `ConnectionStatus` 컴포넌트를 두고 `App`에 넣는다. Vite 개발 서버에 `/api` → `http://localhost:8080` 프록시를 둔다. 디자인 토큰(STEP 6) 전이라 스타일은 최소로 한다.

### 9. 테스트를 어디에 어떻게 두나

| 대상 | 어디에 | 방식 | 언제 도나 |
|---|---|---|---|
| SQL 검사(종류, 주석, 테이블, 함수, LIMIT), 개인정보 해석 트리, GRANT 해석, 오류 분류 | `pipeline/src/test` | 순수 단위 테스트 | `:pipeline:test` (Stop 훅) |
| 읽기 전용 세션, 시간·행 수 상한, 권한 확인, 연결 실패 종류, 다시 쓴 SQL 실행, JDBC 메타데이터 개인정보 | `pipeline/src/integrationTest` | Testcontainers MySQL 8.4, 테스트용 스키마와 계정(reader, writer)을 테스트가 만든다 | `:pipeline:integrationTest`, `check`에 묶어 CI `build`에서 |
| 설정 바인딩·검증, 상태 API 응답, 비밀번호 미노출 | `api/src/test` | `ApplicationContextRunner`, `@WebMvcTest`와 가짜 커넥터 | `build` |
| 홈 연결 상태 네 가지 | `web/src/connection/*.test.tsx` | Vitest + Testing Library, `fetch` 가짜 | `npm --prefix web run test` |

- 테스트 이름에 `F-003`과 시나리오 이름을 넣는다(하네스 2-3).
- 시간 상한 테스트는 SLEEP이 막히므로, 큰 테스트 테이블의 교차 조인으로 느린 쿼리를 만든다. MySQL 8.4는 조건 없는 교차 조인의 `COUNT(*)`를 행 수 곱으로 바로 계산하므로, 행을 실제로 훑는 `SELECT SUM(a.n + b.n + c.n) FROM numbers a, numbers b, numbers c`를 쓴다.
- **평가 세트 정답률:** 평가 세트는 아직 없어(F-002) 이번에 잴 것이 없다. 다만 검사가 보수적이라 F-002에서 맞는 SQL을 거부할 수 있다(예: JSqlParser가 모르는 MySQL 구문). F-002 평가에서 "검사 거부"를 실패 이유로 따로 세어 확인한다.

## Risks / Trade-offs

- [JSqlParser가 일부 MySQL 구문을 모른다] → 해석 실패로 막히므로 안전 쪽이다. F-002 평가에서 거부 비율을 보고, 필요하면 생성 프롬프트에서 그 구문을 피하게 한다.
- [다시 쓴 SQL이 원문과 뜻이 다를 수 있다] → 통합 테스트에서 대표 쿼리(CTE, UNION, 윈도 함수, 날짜 함수)를 원문과 다시 쓴 문장으로 각각 실행해 결과를 비교한다.
- [LLM에 보낼 DB 오류에 원문이 없어 F-002의 SQL 고치기가 어려워진다] → 오류 코드로 원인 종류(없는 컬럼 1054, 없는 테이블 1146, 문법 1064 등)는 알 수 있다. 부족하면 F-002에서 식별자만 담는 오류 코드에 한해 메시지를 남기는 허용 목록을 스펙으로 정한다.
- [개인정보 추적이 넉넉해 필요한 열까지 가린다] → 가리는 쪽이 안전하다. 서술 품질이 떨어지면 F-004에서 보고, 규칙은 스펙을 고쳐 바꾼다.
- [권한 확인이 역할·프록시 권한을 다 보지 못한다] → 경고를 남겨 사람이 확인하게 한다. 첫 겹(계정)이 무너져도 세션과 검사가 막는다.
- [연결 상태를 조회마다 확인해 DB에 부담] → 홈을 열 때만 부르고 쿼리가 가볍다. 문제가 되면 짧은 캐시를 둔다.
- [CI에서 Testcontainers가 Docker를 써 느려진다] → 통합 테스트는 `:pipeline:test`와 나눠 Stop 훅은 빠르게 둔다. ubuntu-latest에는 Docker가 있다.

## Migration Plan

새 기능이라 옮길 데이터는 없다.

1. `application.yml`에 `askflow.target-db`를 넣는다. 로컬은 기존 `askflow_reader`와 `.env`의 `ASKFLOW_READER_PASSWORD`를 그대로 쓴다.
2. `.env.example`의 `ASKFLOW_READER_PASSWORD` 설명에 "분석 대상 DB 연결(F-003)에서 쓴다"를 덧붙이고, 파일럿 설정 덮어쓰기 방법을 `README.md`에 짧게 적는다.
3. 되돌릴 때는 change 커밋을 되돌리면 된다. 서비스 DB를 바꾸지 않는다.

## Open Questions

- 행 수 1,000행·10초 기본값이 맞는지는 F-002 평가와 F-005 다시 열기 시간을 재며 조정한다. 값만 바뀌고 동작은 같다.
