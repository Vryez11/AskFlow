# Design

## Context

- 왜 하는지는 proposal.md, 동작은 `specs/` 아래 다섯 capability를 따른다. 큰 틀은 [아키텍처 7장](../../../docs/mvp/05-architecture.md#7-llm-연동)(LLM 연동), [8장](../../../docs/mvp/05-architecture.md#8-olist-데이터와-날짜)(Olist 시드), [9장](../../../docs/mvp/05-architecture.md#9-평가-세트)(평가 세트)이다.
- 지금 코드: F-003(`add-safe-sql-execution`)이 `pipeline`에 `DataSourceConnector`(검사 → 다시 쓴 SQL 실행), `QueryResult.forLlm()`(`LlmSafeResult`), `ExecutionError.forLlm()`(`LlmSafeError`)을 만들었다. 커넥터는 실행마다 `information_schema`에서 뷰·생성 컬럼·저장 함수·컬럼 목록을 읽는다. api에는 `TargetDbProperties`, `GET /api/connection/status`, 늘 0을 돌려주는 `ZeroTermCountProvider`가 있다.
- LLM은 개발·평가를 Gemini API 무료 등급으로 시작한다(로드맵 결정 표, 2026-10-28). 무료 등급은 입력이 학습과 사람 검토에 쓰일 수 있어 공개 데이터(Olist)에만 쓴다. `GEMINI_API_KEY`는 `.env.example`에 있고, 사람이 `.env`에 넣었다.
- 에이전트는 `.env`와 `data/raw/`를 읽지 않는다(CLAUDE.md). 그래서 에이전트가 키나 Olist CSV 내용을 직접 보지 않고도 시드와 평가를 돌릴 수 있어야 한다.
- Stop 훅은 `:pipeline:test`를 돌리니 빨라야 하고, CI(`./gradlew ktlintCheck build`)는 네트워크의 LLM에 기대면 안 된다.

## Goals / Non-Goals

**Goals:**

- 질문 → SQL 생성 → 안전 실행 → 실패하면 고치기를 한 번 부르면 끝나는 단위로 만들어, F-001·F-004·F-005가 그대로 부른다.
- LLM 공급자는 설정만으로 바꾸고, 공급자가 멈춰도 데이터 정책 안에서 자동으로 넘긴다.
- 평가 세트로 정답률(H1)과 용어 사전 효과(H5)를 한 명령으로 잰다. 이 첫 정답률이 이후 파이프라인 change의 기준이 된다.
- 실패한 요청을 기록 ID 하나로 다시 돌려 볼 수 있다(하네스 2-6).

**Non-Goals:**

- 요청 해석과 계획(위젯·섹션 나누기)은 F-001이다. 이 change의 입력은 질문 하나다. 평가 세트의 대시보드·보고서 요청 10개(아키텍처 9장)도 F-001에서 더한다.
- 보고서 서술처럼 결과 값을 LLM에 보내는 단계는 F-004다. 이 change는 결과 값을 LLM에 보내지 않는다.
- REST API와 화면. 근거 패널은 F-004, F-005다. 홈의 용어 수만 바뀐다.
- 용어 사전 편집 화면(PRD 6장 제외 범위). 파일을 고치고 다시 시작한다.
- 평가를 CI에서 자동으로 돌리기. LLM 비용과 무료 등급 한도 때문에 사람이 PR 전에 돌리고 결과를 기록한다(하네스 2-3).

## Decisions

### 1. 모듈과 패키지

- `pipeline`에 생성 단계를 둔다. Spring에 기대지 않는 F-003 규칙을 그대로 따른다.
  - `io.askflow.pipeline.generation`: SQL 생성기, 프롬프트 구성, 생성 결과 타입
  - `io.askflow.pipeline.llm`: `LlmClient`, 요청·응답·실패 타입, `LlmRouter`, 설정 타입, `gemini`·`anthropic` 하위 패키지의 구현체
  - `io.askflow.pipeline.glossary`: 용어 사전 타입과 YAML 읽기
  - `io.askflow.pipeline.runlog`: `RunLogger`와 파일 구현체
- 새 `eval` 모듈(Kotlin JVM, `pipeline`에 기댐)에 평가 세트 파일, 결과 비교, 평가 실행·재현, Olist 시드를 둔다. 아키텍처 3장의 레포 구조를 따른다. 시드를 `scripts/seed/`의 셸 스크립트 대신 `eval` 모듈의 Kotlin으로 두는 이유는, CSV 파싱(리뷰 본문의 줄바꿈과 따옴표)과 날짜 옮기기를 Testcontainers로 테스트하기 쉬워서다. `scripts/seed/`에는 Gradle 태스크를 부르는 짧은 진입점만 둔다.
- api는 설정을 바인딩·검증하고 빈을 연결한다. 새 REST 엔드포인트는 없다.

공개 API는 이 정도다.

```kotlin
class SqlGenerator(connector, llm: LlmClient, glossary: Glossary, runLogger: RunLogger, maxAttempts = 3) {
    fun generate(question: String, useGlossary: Boolean = true): GenerationResult
}
data class GenerationResult(runId, sql: String?, assumptions: List<String>, outcome: GenerationOutcome, attempts: List<Attempt>)
// GenerationOutcome: Executed(ExecutionOutcome) 또는 LlmUnavailable / LlmConfigError(원인, 확인할 설정)

interface LlmClient { fun complete(request: LlmRequest): LlmResponse }   // 실패는 LlmFailure 예외
data class LlmRequest(stage: LlmStage, fixedPrefix: String, variable: String, outputSchema: JsonSchema)
data class LlmResponse(json: String, provider: String, model: String, usage: TokenUsage, switchedFrom: List<SkipReason>)
```

### 2. 스키마 정보는 커넥터가 준다

SQL을 만들려면 허용 테이블의 컬럼 이름과 형이 필요하다. F-003 커넥터가 이미 실행마다 `information_schema`를 읽고 허용 목록을 안다. 그래서 `DataSourceConnector`에 `describeSchema()`를 더해, 허용 테이블(뷰 포함)마다 컬럼 이름과 형을 돌려준다. 값은 읽지 않는다.

- 대안: 생성기가 따로 JDBC로 읽는다. 허용 목록 해석(빈 목록이면 스키마 전체)과 연결 설정을 두 곳에서 다루게 되어 버렸다.
- 개인정보 컬럼의 이름도 보낸다. 이름은 값이 아니고, LLM이 그 컬럼을 COUNT에 쓰려면 이름이 필요하다.
- F-003 스펙의 동작은 바뀌지 않는다(실행 경로는 그대로 `execute` 하나).

### 3. 프롬프트: 고정 앞부분과 바뀌는 뒷부분

- 고정 앞부분: 지시문(MySQL 8.4, SELECT 문 하나, 상대 기간은 `CURDATE()` 기준, 사용자 변수 금지, 가정은 한국어 문장), 스키마, 켜져 있으면 컬럼 설명과 용어 사전. 앱이 떠 있는 동안 같으므로 공급자의 프롬프트 캐싱을 탄다(아키텍처 7-4).
- 바뀌는 뒷부분: 질문, 고치기라면 이전 SQL과 `LlmSafeError.toString()`.
- 지시문은 `pipeline/src/main/resources/prompts/`의 텍스트 파일로 둔다. 평가 세트로 고칠 때 코드가 아니라 이 파일을 고친다.
- 컬럼 설명은 허용 테이블의 컬럼 것만 넣는다. 용어 사전을 끄면 용어와 컬럼 설명을 모두 뺀다(H5는 설정 파일 전체의 효과를 잰다).

### 4. 구조화 출력과 고치기

- 출력 스키마는 `{"sql": string, "assumptions": string[]}` 하나다. Gemini는 응답 MIME 형식과 JSON 스키마를, Claude는 구조화 출력(`output_config.format`)을 쓴다(아키텍처 7-4). 정확한 SDK 사용법은 구현할 때 각 SDK 문서로 확인한다.
- 시도는 최대 3번이다. `ExecutionErrorKind`가 `CONNECTION_FAILED`면 고치지 않는다. 그 밖(검사 거부, 상한 초과, DB 오류)은 고친다. 결과 행은 어떤 경우에도 고치기 요청에 넣지 않는다.
- 3번으로 둔 이유: 첫 시도에서 틀린 컬럼 이름 같은 단순 오류는 한 번이면 대부분 고쳐지고, 그 이상은 시간(PRD 5장, 생성 1회 2분)과 무료 등급 한도를 쓴다. 평가 세트의 시도 횟수 분포를 보고 조정한다.

### 5. LlmClient 구현체와 실패 분류

- 구현체는 SDK 예외와 HTTP 상태를 `LlmFailure`로 바꾼다.
  - 넘길 실패(`Transient`): 429(기다릴 시간 포함), 5xx, 연결 실패, 시간 초과, 거절
  - 넘기지 않는 실패(`Fatal`): 400, 401, 403
  - 형식 위반(`FormatViolation`): JSON이 아니거나 스키마를 어김
  - 키 없음(`MissingKey`): 환경 변수 이름을 담는다
- Anthropic 구현체도 이 change에서 만든다(아키텍처 7장). Claude 키가 없어도 앱과 테스트가 돈다. 실제 호출은 키를 넣고 공급자 목록에 넣을 때 확인한다.
- Claude는 SQL 생성에 effort `high`, 거절 대비 서버 쪽 대체 설정을 켠다(아키텍처 7-4). Gemini의 thinking 설정은 기본값으로 두고 평가 세트로 정한다.

### 6. LlmRouter와 서킷 브레이커

- `LlmRouter`는 `LlmClient`를 구현하고, 단계별 목록의 공급자·모델마다 Resilience4j `CircuitBreaker`를 하나씩 둔다. Spring 없이 쓰는 라이브러리라 pipeline 규칙에 맞는다(BOM에 없으니 버전을 직접 적는다).
- 브레이커는 `Transient` 실패만 센다. `Fatal`은 넘기지 않고 바로 돌려주므로 브레이커를 열지 않는다. `FormatViolation`은 같은 공급자에 한 번 다시 묻고, 두 번째도 어기면 `Transient`처럼 다음 순위로 넘긴다.
- 429의 기다릴 시간은 브레이커와 따로, 공급자·모델별 "이 시각 전에는 부르지 않음"으로 둔다. 브레이커 상태를 억지로 바꾸지 않아도 되어 단순하다.
- 초기값(설정으로 바꿈): 최근 10번 중 최소 4번을 본 뒤 실패율 50% 이상이면 열림, 열림 60초, 반열림에서 시험 2번. 무료 등급 한도 단위(분)에 맞춰 열림을 분 단위로 둔다.
- 시간은 `java.time.Clock`을 받아 테스트에서 돌린다. 라이브러리 안의 시계를 바꿀 수 없는 부분은 짧은 시간 값과 상태 전환으로 테스트한다.

### 7. 설정과 데이터 정책

```yaml
askflow:
  llm:
    data-policy: PUBLIC_DATA        # COMPANY_DATA면 무료 등급 모델이 있을 때 시작하지 않는다
    request-timeout: 60s
    stages:
      sql-generation:
        - { provider: gemini, model: gemini-3.8-flash, tier: FREE }
        - { provider: gemini, model: gemini-3.5-flash-lite, tier: FREE }
    circuit-breaker: { failure-rate-threshold: 50, sliding-window-size: 10, minimum-calls: 4, wait-in-open: 60s, half-open-calls: 2 }
  glossary:
    path: config/glossary/olist.yml   # 비우면 용어 사전 없음
```

- 키는 `GEMINI_API_KEY`, `ANTHROPIC_API_KEY` 환경 변수다. 설정 파일에 쓰지 않는다.
- pipeline은 설정 형식을 모르고 `LlmSettings`(공급자·모델·등급 목록, 브레이커 값) 값만 받는다(F-003 `TargetDbConfig`와 같은 방식).
- 데이터 정책 검사는 api 시작 때 한다. 회사 데이터인데 무료(또는 등급 없음) 모델이 있으면 시작 실패와 설정 키를 알린다. 파일럿 설정 파일(`SPRING_CONFIG_ADDITIONAL_LOCATION`)에 `data-policy: COMPANY_DATA`를 쓴다(README).

### 8. 용어 사전 파일

```yaml
terms:
  - name: 순매출
    definition: 결제 금액 합계에서 환불 금액을 뺀 값
columns:
  orders.order_status: "주문 상태. delivered(배송 완료), shipped(배송 중), canceled(취소) 등"
```

- YAML은 SnakeYAML(Spring Boot BOM에 있는 순수 Java 라이브러리)로 읽는다. 이름이 비거나, 같은 이름이 두 번 있거나, 컬럼 키가 `table.column`이 아니면 파일 경로와 항목 번호를 담은 오류로 앱 시작을 막는다.
- Olist용 파일(`config/glossary/olist.yml`)을 이 change에서 쓴다. H5를 재려면 평가 세트에 용어 정의가 필요한 문항이 있어야 하므로, 평가 세트와 함께 만든다.
- 홈의 용어 수: `ZeroTermCountProvider`를 용어 사전의 용어 수를 돌려주는 구현으로 바꾼다. 경로를 비우면 0이다.

### 9. 실행 기록과 재현

- `RunLogger`(아키텍처 2장)의 파일 구현이 `logs/runs/<runId>.json`에 쓴다. 담는 것은 스펙의 "실행 기록" 그대로이고, 결과 값은 행 수와 열 이름만 남긴다. 프롬프트 전문은 남기지 않는다(스키마가 커서). 재현에는 질문과 용어 사전 켬·끔이면 충분하다.
- 재현: `./gradlew :eval:reproduce -PrunId=<id>`가 그 기록의 질문과 설정으로 생성기를 다시 부른다.

### 10. 평가 세트와 결과 비교

- 문항 파일은 `eval/src/main/resources/eval-set/questions.yml`(id, 질문, 정답 SQL, 순서를 따지는지)이다.
- 30문항의 구성: 단순 집계, 상대 기간(지난 7일, 지난 30일, 이번 달), 그룹·정렬·상위 N, 2~3개 테이블 조인, 비율, 그리고 용어 정의가 있어야 맞힐 수 있는 문항 8개 이상(H5).
- 결과 비교: 정답 결과의 열마다 생성 결과에서 같은 값을 가진 열을 찾아 맞춘 뒤(열 이름·순서 무시, 남는 열 허용), 순서를 따지지 않는 문항은 행을 다중집합으로, 따지는 문항은 목록으로 비교한다. 숫자는 상대 오차 0.0001, 날짜·시각은 같은 값, NULL은 NULL과만 같다.
- 출력: 정답률 이력은 `eval/history.csv`(커밋함), 실패 리포트와 문항별 결과는 `logs/eval/<시각>/`(커밋하지 않음, 결과 값이 있음).
- 무료 등급 한도를 넘지 않게 문항 사이에 설정한 간격을 둔다. 넘으면 라우터가 다음 모델로 넘기고, 문항별로 답한 모델을 남긴다.

### 11. 시드와 평가를 에이전트가 키를 보지 않고 돌리기

`eval`의 Gradle 태스크(`seed`, `evaluate`, `reproduce`)는 프로그램이 직접 `.env`를 읽어 자기 프로세스 환경에만 넣는다. 값을 출력하거나 기록하지 않는다. `docker compose`가 `.env`를 읽는 것과 같다. 그래서 에이전트는 `.env` 내용을 보지 않고 명령만 부른다.

- 시드는 쓰기가 필요하니 root 계정(`MYSQL_ROOT_PASSWORD`)으로 붙고, 평가와 재현은 앱과 같은 `askflow_reader`로 붙는다.
- 대안: 사람이 셸에 `source .env`를 하고 돌린다. F-003의 4.3·5.2처럼 루프가 매번 사람을 기다려 버렸다.

### 12. Olist 시드

- CSV 9개를 짧은 이름의 테이블(`orders`, `order_items`, `order_payments`, `order_reviews`, `customers`, `sellers`, `products`, `geolocation`, `product_category_name_translation`)로 넣는다. 테이블은 시드가 만든다(`DROP` 후 `CREATE`).
- 날짜 간격은 CSV의 마지막 `order_purchase_timestamp`에서 계산하고, 넣을 때 더한다(넣은 뒤 `UPDATE`하지 않는다). 그래서 다시 시드해도 쌓이지 않는다.
- CSV는 Apache Commons CSV로 읽는다(리뷰 본문의 줄바꿈과 따옴표). `geolocation`은 약 100만 행이라 JDBC 배치(`rewriteBatchedStatements`)로 넣는다.
- 시작 전에 필요한 파일이 모두 있는지 먼저 본다. 하나라도 없으면 DB를 건드리지 않는다.

### 13. 테스트를 어디에 어떻게 두나

| 대상 | 위치 | 방식 | 명령 |
|---|---|---|---|
| 프롬프트 구성, 생성기 고치기 흐름, 결과 값이 안 나감 | `pipeline/src/test` | 가짜 `LlmClient`(받은 요청 기록)와 가짜 커넥터 | `:pipeline:test` |
| 라우터·브레이커·기다릴 시간, 데이터 정책 검사 | `pipeline/src/test` | 상태를 정한 가짜 공급자, 돌릴 수 있는 시계 | `:pipeline:test` |
| Gemini·Anthropic 구현체의 요청 모양과 상태 코드 분류 | `pipeline/src/test` | JDK `HttpServer`로 띄운 로컬 스텁에 SDK의 기본 주소를 바꿔 붙임 | `:pipeline:test` |
| 용어 사전 읽기, 실행 기록 | `pipeline/src/test` | 임시 폴더 | `:pipeline:test` |
| 생성기 + 실제 MySQL(DB 오류로 고치기, 스키마 설명) | `pipeline/src/integrationTest` | Testcontainers(F-003의 `TestMySql`), 가짜 LLM | `:pipeline:integrationTest` |
| 결과 비교, 이력 한 줄, 실패 리포트 | `eval/src/test` | 순수 단위 | `:eval:test` |
| 시드(적재, 7일 단위, 다시 시드, 파일 없음), 정답 SQL 실행 | `eval/src/integrationTest` | Testcontainers와 작은 CSV 묶음 | `:eval:integrationTest` |
| 데이터 정책 시작 실패, 용어 사전 형식 오류, 용어 수 | `api/src/test` | `@SpringBootTest` | `:api:test` |
| 실제 Gemini로 평가 | 사람이 PR 전에 | `./gradlew :eval:evaluate` | CI 밖 |

- 테스트 이름에 `F-002`와 시나리오 이름을 넣는다(하네스 2-3).
- CI는 네트워크의 LLM을 부르지 않는다. 모든 자동 테스트는 가짜 LLM이나 로컬 스텁을 쓴다.
- `eval` 모듈의 통합 테스트도 pipeline처럼 `check`에 묶는다. Stop 훅은 `:pipeline:test`만 돌리니 `:eval:test`를 빠르게 둘 필요는 없지만, 단위 테스트는 네트워크 없이 돌게 한다.
- **평가 세트 정답률:** 이 change가 첫 기준을 만든다. 켬·끔 두 정답률을 `eval/history.csv`와 PR 설명에 적는다. 직전 기록이 없으니 3%p 규칙은 다음 파이프라인 change부터 적용한다.

## Risks / Trade-offs

- [무료 등급 한도에 걸려 평가 중간에 모델이 바뀐다] → 문항 사이 간격을 두고, 문항별로 답한 모델을 남긴다. 리포트에 모델별 정답 수를 따로 보여, 섞인 결과를 정답률 하나로만 읽지 않게 한다.
- [LLM 결과가 실행마다 달라 정답률이 흔들린다] → temperature는 쓰지 않는다(아키텍처 7-4). 첫 기준을 잡을 때 켬·끔을 각각 두 번 돌려 차이를 기록하고, 3%p 규칙이 이 흔들림보다 큰지 확인한다.
- [정답 SQL이 틀리면 평가가 틀린다] → 정답 SQL은 시드한 DB에서 모두 실행되는지 테스트로 확인하고, 사람이 검토 단계에서 문항과 정답을 본다.
- [F-003 검사가 맞는 SQL을 거부한다(JSqlParser가 모르는 문법, 사용자 변수 등)] → 지시문에서 그런 문법을 쓰지 말라고 하고, 리포트에서 "검사 거부"를 실패 이유로 따로 센다(F-003 design의 예고).
- [SDK 사용법(구조화 출력, 기본 주소 바꾸기)이 문서와 다르다] → 구현체 작업에서 SDK 문서를 먼저 확인하고, 로컬 스텁 테스트로 요청 모양을 고정한다.
- [`geolocation` 적재가 느리다] → 배치로 넣고, 시드는 Olist가 과거가 됐을 때만 다시 돌린다.
- [Claude 구현체는 실제 호출 없이 들어간다] → 스텁 테스트로 요청 모양과 실패 분류를 고정하고, 실제 호출은 공급자 목록에 넣는 PR에서 평가 세트로 확인한다.

## Migration Plan

서비스 DB와 데이터 이전은 없다.

1. `application.yml`에 `askflow.llm`, `askflow.glossary`를 넣고, `config/glossary/olist.yml`을 더한다.
2. 사람이 Olist CSV를 `data/raw/olist/`에 받고 `./gradlew :eval:seed`를 돌린다. `.env`에 `GEMINI_API_KEY`가 있어야 평가를 돌릴 수 있다.
3. 되돌릴 때는 change 커밋을 되돌리면 된다. 홈의 용어 수는 다시 0이 된다.

## Open Questions

- 서킷 브레이커 초기값, 문항 사이 간격, 시도 횟수 3은 첫 평가에서 무료 등급 한도와 시도 분포를 보고 조정한다. 값만 바뀌고 동작은 같다.
- Gemini thinking 설정과 Claude effort의 단계별 값은 평가 세트로 정한다.
