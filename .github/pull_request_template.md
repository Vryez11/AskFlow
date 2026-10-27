<!--
제목은 커밋 컨벤션과 같은 형식으로 쓴다. 예) feat(api): SELECT만 실행한다 (F-003)
해당하지 않는 항목은 지우지 말고 "해당 없음"으로 적는다.
develop → main 릴리스 PR은 release 템플릿을 쓴다: gh pr create --template release.md
-->

## 요약

<!-- 무엇이 달라지는지 1~3줄 -->

## 연결

- 종류: <!-- change / fix / chore / docs / hotfix -->
- 기능 ID: <!-- F-001 등. 없으면 해당 없음 -->
- OpenSpec change: <!-- openspec/changes/<change-id>/ . 없으면 해당 없음 -->

## 확인한 것

<!-- change라면 conventions/harness.md 4장의 완료 조건을 모두 채운다. 그 밖의 PR은 해당하는 것만 체크한다 -->

- [ ] tasks.md의 작업이 모두 체크됐다
- [ ] 시나리오마다 테스트가 있고 모두 통과한다
- [ ] 자동 검사가 로컬에서 모두 통과한다
- [ ] 검토에서 막는 지적이 없다
- [ ] change 브랜치에서 archive했다
- [ ] 요구사항이 바뀌었다면 PRD도 고쳤다

## 평가 세트 결과

<!-- 파이프라인(F-001, F-002, F-004, F-005)을 바꾼 change만 적는다. 정답률과 직전 기록, 3%p 넘게 떨어졌다면 그 이유 -->

## 참고

<!-- 리뷰어가 알아야 할 것, 남은 일 -->
