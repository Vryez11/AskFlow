# 스펙 관리 규칙 (PRD와 OpenSpec)

MVP 기간에는 PRD와 OpenSpec을 함께 쓴다. 같은 내용을 두 곳에 쓰지 않고, 맡는 내용을 나눠 기능 ID로 잇는다.

## 역할

| | PRD (`docs/mvp/04-prd.md`) | OpenSpec (`openspec/`) |
|---|---|---|
| 다루는 것 | 왜, 무엇을, 어디까지: 목표, 성공 지표(H1~H6), 기능 ID 목록과 우선순위, 유저 스토리, 사용자 관점의 수용 기준, 제외 범위 | 시스템이 어떻게 동작해야 하나: 요구사항(SHALL)과 시나리오(WHEN/THEN), 설계, 작업 목록 |
| 범위 | MVP | 프로젝트 전체 (MVP 이후에도 계속) |
| 수명 | MVP가 끝나면 MVP 기록으로 고정한다 | 계속 갱신하는 살아 있는 스펙 |

## 흐름

1. PRD에 기능 ID(F-001 등), 유저 스토리, 수용 기준을 쓴다.
2. 기술 스택과 아키텍처가 정해진 뒤(MVP에서는 STEP 5 이후), 기능 ID마다 OpenSpec change를 만든다(`/opsx:propose`). change의 설계에 기술 스택이 들어가기 때문이다. 기능 하나에 change 하나가 기본이고, 함께 구현해야 하는 기능은 한 change로 묶어도 된다.
3. change를 구현한다(`/opsx:apply`).
4. 구현과 검증이 끝나면 archive한다(`/opsx:archive`). 이때 change의 스펙이 `openspec/specs/`에 합쳐진다.

## 동기화 규칙

1. **PRD가 먼저다.** OpenSpec change는 PRD의 기능 ID에서 시작한다. PRD에 없는 기능을 MVP에서 만들려면 PRD부터 고친다.
2. **기능 ID로 잇는다.** change 제안(proposal)과 요구사항마다 관련 기능 ID를 적는다. 예) `### Requirement: 읽기 전용 실행 (F-003)`
3. **내용을 복사하지 않는다.** PRD에는 WHEN/THEN 수준의 시나리오를 쓰지 않고, OpenSpec에는 목표, 성공 지표, 우선순위를 쓰지 않는다. 필요하면 링크로 가리킨다.
4. **MVP 전까지는 양쪽에 반영한다.** 구현하다 요구사항이 바뀌면 OpenSpec change를 고치고(`/opsx:update`), 같은 작업에서 PRD의 해당 기능도 고친다.
5. **MVP가 끝나면 PRD를 고정한다.** 이후 변경은 OpenSpec에서만 관리하고, PRD 맨 위에 고정한 날짜를 적는다.

## 커밋

- OpenSpec 문서만 바꾸면 `docs(spec): ...`로 쓴다.
- change를 구현한 커밋은 [커밋 컨벤션](commit.md)대로 기능 ID를 붙인다. 예) `feat(api): SELECT가 아닌 SQL은 실행하지 않는다 (F-003)`
