# AskFlow

자연어 요청을 TextToSQL로 처리해, 정형 보고서는 대시보드로, 비정형 보고서는 문서형 보고서로 만드는 B2B 서비스다.

## 먼저 읽을 것

- **개발 흐름:** [conventions/harness.md](conventions/harness.md). 스펙은 OpenSpec으로, 검증은 하네스로, 구현은 루프로 한다
- **규칙:** [spec.md](conventions/spec.md)(PRD와 OpenSpec), [commit.md](conventions/commit.md), [branch.md](conventions/branch.md)
- **제품:** [PRD](docs/mvp/04-prd.md)(Must 기능과 수용 기준), [아키텍처](docs/mvp/05-architecture.md)
- **동작 스펙:** `openspec/specs/`, 진행 중인 change는 `openspec/changes/`

## 구조

| 폴더 | 내용 |
|---|---|
| `api/` | Spring Boot(Kotlin) 앱 |
| `pipeline/` | 생성 파이프라인. Spring에 기대지 않는 순수 Kotlin |
| `web/` | React + TypeScript + Vite |
| `docker/`, `docker-compose.yml` | 로컬 MySQL |
| `scripts/harness/` | Claude Code 훅 (`.claude/settings.json`에서 연결) |
| `scripts/git-hooks/` | 커밋 메시지·브랜치 검사 |
| `scripts/ralph/` | Ralph 루프 |

## 명령

| 목적 | 명령 |
|---|---|
| 처음 한 번 | `git config core.hooksPath scripts/git-hooks`, `cp .env.example .env`, `npm --prefix web ci` |
| 로컬 DB | `docker compose up -d` |
| 백엔드 린트·빌드·테스트 | `./gradlew ktlintCheck build` |
| 백엔드 포맷 | `./gradlew ktlintFormat` |
| 빠른 단위 테스트 | `./gradlew :pipeline:test` |
| web 린트·타입·테스트 | `npm --prefix web run lint`, `typecheck`, `test` |
| 스펙 검사 | `openspec validate --all --strict --no-interactive` |
| API 실행 | `./gradlew :api:bootRun` |

훅은 `jq`를 쓴다.

## 지킬 것

- 작업은 `develop`에서 만든 브랜치에서 한다. `main`에는 직접 커밋하지 않는다.
- 커밋 메시지는 [커밋 컨벤션](conventions/commit.md) 형식을 따른다. git `commit-msg` 훅이 검사한다.
- `.env`와 `data/raw/`는 읽거나 고치지 않는다.
- 기능은 OpenSpec change를 기준으로, 시나리오를 테스트로 먼저 만들고 구현한다.
