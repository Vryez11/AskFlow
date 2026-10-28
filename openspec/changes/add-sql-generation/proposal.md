# Proposal

**기능 ID:** F-002 SQL 생성 ([PRD 4장](../../../docs/mvp/04-prd.md#f-002-sql-생성))

## Why

AskFlow의 핵심은 위젯·섹션마다 정확한 숫자를 뽑는 SQL이다. F-003이 SQL을 안전하게 실행하는 길을 만들었으니, 이제 그 위에서 질문 하나를 MySQL에서 실행할 수 있는 SELECT 하나로 바꾸는 단계가 필요하다. F-001(계획), F-004, F-005가 모두 이 단계를 부르고, H1(정확도)과 H5(용어 사전 효과)는 이 단계의 정답률로 잰다([PRD 2장](../../../docs/mvp/04-prd.md#2-성공-지표)). 그래서 평가 세트와 그 실행 도구도 여기서 만든다([PRD 7장](../../../docs/mvp/04-prd.md#7-openspec-change-계획) 순서 2).

## What Changes

- `pipeline`에 SQL 생성기를 둔다. 질문 하나와 분석 대상 DB의 스키마(허용 테이블의 컬럼 이름과 형), 컬럼 설명, 용어 사전으로 LLM에 SELECT 하나와 가정을 구조화 출력으로 받고, F-003의 `DataSourceConnector`로 실행한다. 실행이 실패하면 `LlmSafeError`를 넘겨 정해진 횟수까지 고쳐 다시 받는다.
- LLM에는 스키마, 컬럼 설명, 용어 사전, 질문, `LlmSafeError`만 보낸다. 실행 결과 값은 SQL 생성 단계에서 보내지 않는다.
- [아키텍처 7장](../../../docs/mvp/05-architecture.md#7-llm-연동)의 LLM 연동을 만든다.
  - 공급자와 무관한 `LlmClient`, 구현체 `GeminiLlmClient`와 `AnthropicLlmClient`.
  - 설정(`askflow.llm`)으로 단계별 공급자·모델 목록을 정하고, `LlmRouter`가 공급자·모델마다 서킷 브레이커를 두어 계속 실패하면 다음 순위로 넘기고 살아나면 되돌린다.
  - 데이터 정책: 회사 DB를 붙이는 설정에 무료 등급 모델이 있으면 앱이 시작하지 않는다.
- 용어 사전과 컬럼 설명을 설정 파일(YAML)로 넣는다. 앱을 시작할 때 읽고, 홈의 용어 수는 용어 사전의 항목 수가 된다(F-003의 `TermCountProvider`를 바꾼다).
- 요청마다 실행 기록(질문, 생성 SQL, 시도, 행 수와 열 이름, 오류, 답한 공급자·모델)을 `logs/runs/`에 남기고, 기록 ID로 요청 하나를 다시 돌리는 명령을 둔다(하네스 2-6). 결과 값과 개인정보는 남기지 않는다.
- 새 `eval` 모듈에 Olist 질문 30개와 정답 SQL을 두고, 생성 SQL과 정답 SQL의 결과를 비교해 정답률과 실패 리포트를 남긴다. 용어 사전을 켠 상태와 끈 상태로 각각 돌린다([아키텍처 9장](../../../docs/mvp/05-architecture.md#9-평가-세트)).
- Olist CSV를 개발용 MySQL의 `olist` DB에 넣고 날짜를 7일 단위로 최근으로 옮기는 시드 명령을 둔다([아키텍처 8장](../../../docs/mvp/05-architecture.md#8-olist-데이터와-날짜)).
- 화면은 바꾸지 않는다(홈의 용어 수 숫자만 바뀐다). 근거 패널은 F-004, F-005에서 만든다.

## Capabilities

### New Capabilities

- `sql-generation`: 질문 하나에서 SELECT 하나와 가정을 만들고, 실행 실패 시 고쳐 다시 받으며, LLM에 보내는 내용을 제한하고 실행 기록을 남긴다
- `llm-provider`: LLM 공급자·모델 설정, 서킷 브레이커로 자동 전환과 복구, 데이터 정책에 따른 전환 제한
- `glossary`: 용어 사전과 컬럼 설명 설정 파일, 다시 시작하면 반영, 홈의 용어 수
- `evaluation`: 평가 세트, 정답 SQL과의 결과 비교, 용어 사전 켬·끔 비교, 정답률 기록과 실패 리포트
- `olist-seed`: Olist 적재와 날짜를 7일 단위로 최근으로 옮기기

### Modified Capabilities

없음. 홈의 용어 수는 `data-connection`의 "용어 사전이 아직 없음" 시나리오를 그대로 두고, 용어 사전이 있을 때의 동작을 `glossary`에 더한다.

## Impact

- **pipeline:** SQL 생성기, 프롬프트 구성, `LlmClient`와 Gemini·Anthropic 구현체, `LlmRouter`(서킷 브레이커), 용어 사전 읽기, 실행 기록. 의존성 추가: Google GenAI Java SDK(`com.google.genai:google-genai`), Anthropic Java SDK(`com.anthropic:anthropic-java`), Resilience4j CircuitBreaker, YAML 파서.
- **eval (새 모듈):** 평가 세트 파일, 결과 비교, 평가 실행·재현·시드 명령(Gradle 태스크). `settings.gradle.kts`에 모듈을 더한다.
- **api:** `askflow.llm`·`askflow.glossary` 설정 바인딩과 시작 때 검증(데이터 정책), 용어 사전을 읽는 `TermCountProvider`, SQL 생성기 빈 등록. REST API는 더하지 않는다.
- **설정:** `application.yml`의 `askflow.llm`, `askflow.glossary`, Olist용 용어 사전 파일, `.env.example`의 키 설명(이미 `GEMINI_API_KEY`가 있다).
- **외부 서비스:** 평가와 실행에서 Gemini API를 부른다. 개발·평가는 무료 등급이라 공개 데이터인 Olist에만 쓴다(아키텍처 7-3). 단위·통합 테스트는 가짜 LLM과 로컬 스텁 서버로 돌려 비용과 네트워크에 기대지 않는다.
- **평가 세트:** 이 change에서 처음 만든다. 첫 정답률이 이후 파이프라인 change(F-001, F-004, F-005)의 기준이 된다.
- **사람이 할 일:** Olist CSV를 `data/raw/olist/`에 받아 두기(Kaggle 로그인), `.env`의 `GEMINI_API_KEY`, 정답 SQL 검토.
