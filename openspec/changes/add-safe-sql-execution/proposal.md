# Proposal

**기능 ID:** F-003 SQL 안전 실행과 데이터 연결 ([PRD 4장](../../../docs/mvp/04-prd.md#f-003-sql-안전-실행과-데이터-연결))

## Why

AskFlow는 LLM이 만든 SQL을 회사 DB에 실행한다. 개발자(P3)가 안심하고 DB를 연결하려면, 생성 SQL이 무엇이든 DB를 바꾸거나 무겁게 만들지 못하고 개인정보 값이 LLM으로 나가지 않아야 한다. F-002(SQL 생성), F-004, F-005가 모두 이 실행 경로 위에서 돌기 때문에 가장 먼저 만든다([PRD 7장](../../../docs/mvp/04-prd.md#7-openspec-change-계획) 순서 1).

## What Changes

- 분석 대상 DB 연결 정보, 허용 테이블, 개인정보 컬럼, 행 수·실행 시간 상한을 설정 파일로 넣는다. 비밀번호는 환경 변수로 받는다.
- `pipeline`에 `DataSourceConnector` 인터페이스와 MySQL 구현체를 둔다. SQL은 이 커넥터로만 실행한다.
- [아키텍처 6장](../../../docs/mvp/05-architecture.md#6-분석-대상-db와-sql-안전)의 세 겹 안전을 만든다.
  - 계정: 연결할 때 계정 권한을 확인하고, 쓰기 권한이 있으면 경고 상태로 둔다.
  - 세션: 읽기 전용 트랜잭션, MySQL `max_execution_time`, 가져오는 행 수 상한.
  - SQL 검사: JSqlParser로 SELECT 문 하나인지, 허용 테이블만 쓰는지 확인하고 LIMIT이 없으면 붙인다.
- 실행 실패는 원인과 확인할 곳이 담긴 정해진 오류 종류로 돌려준다. 행 수·시간 상한을 넘으면 결과 없이 중단하고 그 사실을 오류로 남긴다.
- 실행 결과에서 개인정보 컬럼의 값을 뺀 "LLM에 보낼 결과"를 만드는 경로를 둔다. LLM 호출 자체는 F-002부터다.
- api에 연결 상태 조회 API를 두고, 홈에 데이터 연결 상태(연결 여부, 테이블 수, 용어 수, 쓰기 권한 경고, 실패 원인과 확인할 설정)를 보여 준다.
- 임의 SQL을 실행하는 REST API는 만들지 않는다. SQL 실행은 pipeline 안에서만 한다.

## Capabilities

### New Capabilities

- `data-connection`: 분석 대상 DB 연결 설정, 연결 확인과 권한 점검, 홈에 보이는 연결 상태
- `safe-sql-execution`: SQL 검사, 읽기 전용 실행과 행 수·시간 상한, 실행 오류, 개인정보 컬럼을 뺀 LLM용 결과

### Modified Capabilities

없음. `openspec/specs/`에 아직 스펙이 없다.

## Impact

- **pipeline:** `DataSourceConnector`, SQL 검사기, MySQL 커넥터, 개인정보 필터. 의존성 추가: JSqlParser, MySQL Connector/J, HikariCP. MySQL 통합 테스트용 Testcontainers(별도 소스 세트라 `:pipeline:test`는 빠르게 둔다).
- **api:** 설정 바인딩(`askflow.target-db.*`), 커넥터 빈 연결, `GET /api/connection/status`.
- **web:** 홈의 연결 상태 영역. 홈의 나머지(요청 입력, 목록)는 F-001, F-004, F-005에서 만든다.
- **설정:** `application.yml`에 분석 대상 DB 설정, `.env.example`에 필요한 환경 변수 설명.
- **화면의 실행 오류 표시:** 오류 종류와 메시지는 이 change에서 정하고, 보고서·대시보드 화면에 보여 주는 일은 그 화면을 만드는 F-004, F-005에서 한다.
- **평가 세트:** 아직 없다(F-002에서 만든다). 이 change는 생성 SQL을 바꾸지 않는다.
