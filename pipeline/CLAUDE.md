# pipeline

- 순수 Kotlin 모듈이다. Spring 라이브러리는 쓰지 않는다. 의존성 버전만 Spring Boot BOM(`platform(...)`)에 맞춘다. BOM에 없는 것(JSqlParser)은 버전을 직접 적는다.
- 테스트는 두 곳이다. `src/test`는 순수 단위 테스트로 Stop 훅의 `:pipeline:test`가 돌리니 빠르게 둔다. MySQL이 필요한 테스트는 `src/integrationTest`(Testcontainers, `:pipeline:integrationTest`, `check`에 묶임)에 둔다.
- `build.gradle.kts`의 `testing.suites` 블록 안에서는 `platform()`, `kotlin("test")`를 못 쓴다. 통합 테스트 의존성은 바깥 `dependencies`에 `"integrationTestImplementation"(...)`로 적는다. 거기서도 `kotlin("test")`는 JUnit 5 변형이 골라지지 않으니 `kotlin("test-junit5")`를 쓴다.
- `integrationTest` 컴파일은 `associateWith(main)`으로 묶여 있어 main의 `internal`을 볼 수 있다.
- 통합 테스트의 MySQL·테스트용 스키마·계정은 `src/integrationTest`의 `TestMySql`(처음 쓸 때 한 번 띄움)에 모아 둔다. 새 통합 테스트도 이것을 쓴다. 공용 컨테이너는 멈추지 않는다. DB가 꺼졌다 살아나는 상황은 `TcpForwarder`(정해 둔 포트를 나중에 열어 DB로 잇는 중계)로 만든다.
- JSqlParser 5.3은 `LOCK IN SHARE MODE`, `INTO OUTFILE`/`DUMPFILE`/`@변수`를 해석하지 못하고(해석 실패), `/*! */` 실행 주석은 조용히 버린다. 이런 구문을 다른 오류 종류로 거부하려면 해석 전에 원문을 따로 검사해야 한다.
- JSqlParser 5.3은 괄호 없는 UNION 끝의 `LIMIT`(MySQL에서는 UNION 전체에 걸림)을 `SetOperationList`가 아니라 마지막 `PlainSelect`에 붙인다. `(…) UNION (…) LIMIT n`일 때만 `SetOperationList.limit`에 붙는다. 최상위 LIMIT을 볼 때 두 곳을 다 봐야 하고, 둘 다 채우면 `LIMIT`이 두 번 찍힌다.
- 해석 전 원문 검사는 `guard/RawSqlScanner`에 있다(실행 주석·힌트, 잠금·`INTO`). 리터럴·주석을 공백으로 바꾼 뒤 찾으니 서브쿼리 안까지 잡힌다.
- JSqlParser의 `TablesNamesFinder`와 visitor 어댑터는 일부 노드를 건너뛴다(예: `>` 비교식 안 서브쿼리). 검사용으로 트리를 훑을 때는 `guard/ReferenceCollector`(필드를 리플렉션으로 모두 따라감)를 쓰고, 새로 훑는 코드는 `guard/JsqlTree`(필드 목록, 잎 노드, 따옴표 벗기기)를 같이 쓴다. JSqlParser 소스가 필요하면 Gradle 캐시는 샌드박스 밖이니 init 스크립트로 `jsqlparser:5.3:sources`를 `build/` 아래에 풀어 본다.
- Kotlin 주석은 중첩된다. KDoc이나 `/* */` 주석에 `/*!` 같은 SQL 주석 모양을 쓰면 주석이 닫히지 않아 컴파일이 깨진다.
- DB에 닿는지 단위 테스트로 보려면 `MySqlConnector`의 internal 생성자에 `src/test`의 `RecordingDataSource`(보낸 SQL을 기록하는 가짜 JDBC)를 넣는다.
- 읽기 전용은 DB 세션(`connectionInitSql`의 `transaction_read_only = ON`)에 건다. 풀의 JDBC `readOnly`를 켜거나 Connector/J `readOnlyPropagatesToServer`를 켜면 반납 때 `SET SESSION TRANSACTION READ WRITE`가 나가 세션이 쓰기 가능해진다(`ReadOnlySessionIT`가 막는다).
- MySQL 8.4는 조건 없는 교차 조인의 `COUNT(*)`를 행 수 곱으로 바로 계산한다. 느린 쿼리가 필요하면 행을 실제로 훑게 만들어야 한다.
- Connector/J 메타데이터의 `getColumnLabel`은 별칭, `getTableName`·`getColumnName`은 원래 테이블·컬럼이다. 계산한 열은 둘 다 빈 문자열이다. 결과 열의 개인정보 표시는 `mysql/PiiColumnMarker`가 `PiiLineage` 판정과 이 메타데이터를 합쳐 정한다.
- 분석 대상 DB 코드는 `io.askflow.pipeline.datasource`(공개 타입), `.guard`(SQL 검사), `.mysql`(구현체)에 둔다.
