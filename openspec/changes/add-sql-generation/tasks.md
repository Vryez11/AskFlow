# Tasks

브랜치: `change/add-sql-generation`. F-003(`add-safe-sql-execution`)이 `develop`에 들어간 뒤 `develop`에서 만든다. 테스트 이름에는 `F-002`와 시나리오 이름을 넣는다. 설계는 design.md, 동작은 `specs/`를 따른다. 자동 테스트는 네트워크의 LLM을 부르지 않는다(가짜 LLM과 로컬 스텁).

## 1. 시나리오를 실패하는 테스트로 옮기기

- [ ] 1.1 테스트가 컴파일될 골격을 만든다: pipeline에 Google GenAI Java SDK, Anthropic Java SDK, Resilience4j CircuitBreaker, SnakeYAML 의존성을 더하고, 새 `eval` 모듈(`settings.gradle.kts`, pipeline처럼 `test`와 `check`에 묶인 `integrationTest` 소스 세트)을 만든다. design 1장의 공개 타입(`SqlGenerator`, `GenerationResult`, `LlmClient`, `LlmRequest`, `LlmResponse`, `LlmFailure`, `LlmRouter`, `LlmSettings`, `Glossary`, `GlossaryLoader`, `RunLogger`)과 `DataSourceConnector.describeSchema()`, eval의 결과 비교·평가 실행·시드 타입을 본문 `TODO()`로 둔다. 확인: `./gradlew ktlintCheck compileKotlin compileTestKotlin compileIntegrationTestKotlin`, `./gradlew :pipeline:test`가 빠르게 끝난다
- [ ] 1.2 `sql-generation`의 생성·정보·고치기 시나리오(집계 질문, 가정, 허용 테이블만 알림, 컬럼 설명, 상대 기간, 없는 컬럼을 고침, 끝까지 실패, 연결 실패, 고치기 요청에 DB 원문 없음, 행 수 상한 초과를 고침)를 `pipeline/src/test`의 `SqlGeneratorTest`, `PromptBuilderTest`로 옮긴다. 받은 요청을 기록하는 가짜 `LlmClient`와 결과를 정해 둔 가짜 커넥터를 쓴다. 확인: `./gradlew :pipeline:test --tests '*SqlGeneratorTest*' --tests '*PromptBuilderTest*'`가 컴파일되고 실패한다
- [ ] 1.3 실행 기록 시나리오(기록을 남김, 값은 남기지 않음)를 `RunLoggerTest`로 옮긴다. API 키 환경 변수 값이 기록 파일에 없는지도 본다. 확인: `./gradlew :pipeline:test --tests '*RunLoggerTest*'`가 실패한다
- [ ] 1.4 `llm-provider` 시나리오(1순위 공급자, 설정만 바꿔 공급자 바꾸기, 키가 없는 공급자, 요청 한도 초과, 인증 오류, 형식을 어긴 응답, 계속 실패하는 공급자를 건너뜀, 살아나면 되돌림, 기다릴 시간을 지킴, 두 공급자 모두 실패)를 `LlmRouterTest`로 옮긴다. 상태를 정한 가짜 공급자와 돌릴 수 있는 시계를 쓴다. 데이터 정책 세 시나리오는 `LlmSettingsPolicyTest`로 옮긴다. 확인: `./gradlew :pipeline:test --tests '*LlmRouterTest*' --tests '*LlmSettingsPolicyTest*'`가 실패한다
- [ ] 1.5 `glossary`의 pipeline 쪽 시나리오(용어 정의 반영, 용어 사전 없음, 형식이 잘못된 파일, 허용하지 않은 테이블의 컬럼 설명, 끄고 만들기)를 `GlossaryLoaderTest`와 `PromptBuilderTest`에 옮긴다. 확인: `./gradlew :pipeline:test --tests '*GlossaryLoaderTest*'`가 실패한다
- [ ] 1.6 api 시나리오를 테스트로 옮긴다: `LlmPropertiesTest`(데이터 정책 세 가지가 앱 시작에서 걸리는지, 키가 없어도 앱이 시작하는지), `GlossaryConfigTest`(형식이 잘못된 파일이면 시작 실패, 파일을 바꿔 다시 시작하면 반영, 용어 12개면 연결 상태의 용어 수 12, 경로를 비우면 0). 확인: `./gradlew :api:test`가 이 테스트들만 실패한다
- [ ] 1.7 `evaluation` 시나리오를 `eval/src/test`로 옮긴다: `ResultComparatorTest`(SQL은 다르고 결과는 같음, 열 이름과 순서가 다름, 정렬을 따지지 않는 문항, 정렬을 따지는 문항, 값이 다름, 생성 실패), `EvalHistoryTest`(이력에 한 줄 더함), `GlossaryComparisonTest`(켬과 끔 비교), `FailureReportTest`(틀린 문항), 그리고 `sql-generation`의 "요청 하나 재현"을 `ReproduceTest`로. 가짜 LLM과 가짜 커넥터를 쓴다. 확인: `./gradlew :eval:test`가 컴파일되고 실패한다
- [ ] 1.8 MySQL이 필요한 시나리오를 통합 테스트로 옮긴다: `pipeline/src/integrationTest`의 `SqlGeneratorIT`(실제 DB에서 없는 컬럼 오류를 받아 고침, `describeSchema`가 허용 테이블의 컬럼 이름과 형만 돌려줌, F-003의 `TestMySql`을 씀), `eval/src/integrationTest`의 `OlistSeederIT`(처음 시드, 다시 시드, CSV가 없음, 7일 단위로 옮김, 요일 유지, 다시 시드해도 쌓이지 않음)와 `EvalSetIT`(정답 SQL은 실행된다). Olist의 공개된 열 머리글로 작은 CSV 묶음을 `eval/src/integrationTest/resources/olist-mini/`에 만든다. `data/raw/`는 읽지 않는다. 확인: `./gradlew :pipeline:integrationTest --tests '*SqlGeneratorIT*'`, `./gradlew :eval:integrationTest`가 실패한다

## 2. LLM 연동 (pipeline)

- [ ] 2.1 `LlmSettings`(단계별 공급자·모델·등급 목록, 브레이커 값, 요청 시간 상한)와 데이터 정책 검사를 구현한다. 확인: `LlmSettingsPolicyTest` 통과
- [ ] 2.2 `LlmRouter`를 구현한다: 공급자·모델마다 Resilience4j `CircuitBreaker`, `Transient`만 세기, `Fatal`은 바로 돌려주기, 형식 위반은 한 번 다시 묻기, 429의 기다릴 시간, 키 없음 건너뛰기, 모두 실패하면 "LLM 사용 불가"(공급자별 이유, 키 값 없음), 응답에 답한 공급자·모델과 전환 이유. 확인: `LlmRouterTest` 통과
- [ ] 2.3 `GeminiLlmClient`를 구현한다. 먼저 SDK 문서로 구조화 출력(응답 JSON 스키마)과 기본 주소 바꾸기를 확인하고, JDK `HttpServer` 스텁에 붙는 `GeminiLlmClientTest`(요청에 모델과 출력 스키마가 있음, 200 응답 파싱, 429와 기다릴 시간, 5xx, 400, 401, 403, 형식 위반 분류)를 먼저 쓴다. 확인: `./gradlew :pipeline:test --tests '*GeminiLlmClientTest*'` 통과
- [ ] 2.4 `AnthropicLlmClient`를 같은 방식으로 구현한다: 구조화 출력(`output_config.format`), SQL 생성 effort `high`, 거절 대비 서버 쪽 대체 설정(design 5장, 아키텍처 7-4). 스텁에 붙는 `AnthropicLlmClientTest`를 먼저 쓴다. 확인: `./gradlew :pipeline:test --tests '*AnthropicLlmClientTest*'` 통과

## 3. 용어 사전과 SQL 생성 (pipeline)

- [ ] 3.1 `Glossary`와 `GlossaryLoader`(SnakeYAML, 형식 오류에 파일 경로와 항목 번호)를 구현한다. 확인: `GlossaryLoaderTest` 통과
- [ ] 3.2 `MySqlConnector.describeSchema()`를 구현한다(허용 테이블과 뷰의 컬럼 이름과 형, 값은 읽지 않음). 확인: `SqlGeneratorIT`의 스키마 설명 시나리오 통과
- [ ] 3.3 프롬프트 구성(고정 앞부분: `prompts/`의 지시문, 스키마, 켜져 있으면 컬럼 설명과 용어 / 바뀌는 뒷부분: 질문, 이전 SQL과 `LlmSafeError`)을 구현한다. 확인: `PromptBuilderTest` 통과
- [ ] 3.4 `SqlGenerator`(구조화 출력 파싱, 최대 3번 시도, 연결 실패는 고치지 않음, 결과 행은 고치기 요청에 넣지 않음)를 구현한다. 확인: `SqlGeneratorTest`, `SqlGeneratorIT` 통과
- [ ] 3.5 파일 `RunLogger`(`logs/runs/<runId>.json`, 결과 값·비밀 없음)와 생성기 연결을 구현한다. 확인: `RunLoggerTest` 통과, `./gradlew :pipeline:test :pipeline:integrationTest` 전체 통과

## 4. 평가와 시드 (eval)

- [ ] 4.1 `.env`를 프로그램이 직접 읽어 자기 프로세스 환경에만 넣는 읽기 도구(값을 출력하거나 기록하지 않음)와 Gradle 태스크 `seed`, `evaluate`, `reproduce`의 뼈대를 만든다. `scripts/seed/`에는 `./gradlew :eval:seed`를 부르는 짧은 진입점을 둔다. 확인: `./gradlew :eval:tasks --group askflow`에 세 태스크가 보이고, 읽기 도구의 단위 테스트가 통과한다
- [ ] 4.2 `OlistSeeder`를 구현한다(design 12장: 파일 확인 먼저, 테이블 `DROP` 후 `CREATE`, Commons CSV, 넣을 때 날짜 옮기기, 배치). 확인: `OlistSeederIT` 통과
- [ ] 4.3 결과 비교(열 맞추기, 순서, 숫자 오차, NULL)를 구현한다. 확인: `ResultComparatorTest` 통과
- [ ] 4.4 평가 실행(문항 사이 간격, 문항별 답한 모델), 이력(`eval/history.csv`), 실패 리포트(`logs/eval/<시각>/`), 켬·끔 비교를 구현한다. 확인: `EvalHistoryTest`, `GlossaryComparisonTest`, `FailureReportTest` 통과
- [ ] 4.5 재현(`./gradlew :eval:reproduce -PrunId=...`)을 구현한다. 확인: `ReproduceTest` 통과
- [ ] 4.6 Olist 용어 사전 파일(`config/glossary/olist.yml`)과 평가 세트 30문항(`eval/src/main/resources/eval-set/questions.yml`)을 쓴다. 구성은 design 10장을 따르고, 용어 정의가 있어야 맞힐 수 있는 문항 8개 이상에 표시를 한다. 문항 수와 표시 개수를 확인하는 단위 테스트를 더한다. 확인: 그 테스트와 `EvalSetIT` 통과, `./gradlew :eval:test :eval:integrationTest` 전체 통과
- [ ] 4.7 실제 Olist로 시드하고 정답 SQL을 확인한다: `./gradlew :eval:seed` 뒤 정답 SQL 30개를 실제 DB에서 실행해 모두 오류가 없고, 상대 기간 문항은 행이 있는지 본다. 결과를 `progress.md`에 적는다. `data/raw/olist/`에 CSV가 없으면 사람에게 넘긴다. 확인: 시드 명령의 테이블별 행 수와 정답 SQL 실행 결과

## 5. api 연결

- [ ] 5.1 `askflow.llm`·`askflow.glossary` 설정 바인딩과 시작 때 검증(데이터 정책, 용어 사전 형식), `LlmRouter`·`SqlGenerator`·`Glossary` 빈 등록, `application.yml` 기본값(design 7장)을 넣는다. 확인: `LlmPropertiesTest`, `GlossaryConfigTest`의 시작 관련 시나리오 통과
- [ ] 5.2 `ZeroTermCountProvider`를 용어 사전의 용어 수를 돌려주는 구현으로 바꾼다(경로를 비우면 0). 확인: `GlossaryConfigTest` 전체 통과, `./gradlew ktlintCheck build` 통과
- [ ] 5.3 README에 LLM 설정(공급자 목록, 등급, 파일럿은 `data-policy: COMPANY_DATA`), 용어 사전 파일 형식, 시드·평가·재현 명령, 필요한 환경 변수를 적는다. `.env.example`의 LLM 키 설명이 design 7장과 맞는지 본다. 확인: README의 명령을 그대로 돌려 `./gradlew :eval:tasks --group askflow`가 동작한다

## 6. 검토, 첫 평가, change 완료 조건

- [ ] 6.1 구현에 참여하지 않은 리뷰어(`/code-review` 또는 리뷰 서브에이전트)로 하네스 3-3 검토를 한다. 특히 결과 값이 SQL 생성 단계에서 LLM으로 나가는 경로가 없는지, API 키가 기록·오류·로그에 남지 않는지, 데이터 정책 검사가 실패 시 막는지(fail-closed) 본다. 사람이 평가 세트 문항과 정답 SQL을 검토한다. 막는 지적은 이 파일에 작업으로 추가한다. 확인: 막는 지적 0건
- [ ] 6.2 실제 Gemini로 첫 평가를 돌린다: 용어 사전 켬·끔을 각각 두 번(`./gradlew :eval:evaluate`). `eval/history.csv`에 네 줄이 더해지고, 실패 리포트에서 지시문이나 용어 사전으로 고칠 만한 것을 `progress.md`에 적는다. 확인: 이력 네 줄과 `logs/eval/`의 리포트
- [ ] 6.3 `conventions/harness.md` 4장의 change 완료 조건을 확인하고, 평가 세트 정답률(켬·끔)을 PR 설명에 적는다. 요구사항이 바뀌었으면 PRD F-002를 고친다. 확인: `./gradlew ktlintCheck build`, `npm --prefix web run lint && npm --prefix web run typecheck && npm --prefix web run test`, `openspec validate --all --strict --no-interactive` 모두 통과

## Workflow follow-up

- 완료 조건을 채우면 change 브랜치에서 `/opsx:archive`로 스펙을 `openspec/specs/`에 합친다.
- `develop`으로 PR을 열고 CI 통과 후 merge한다.
