# web

- 테스트는 Vitest + Testing Library(`@testing-library/react`)다. jest-dom은 없으니 `toBeTruthy()`나 `textContent` 비교를 쓴다.
- Vitest `globals`가 꺼져 있어 Testing Library의 자동 cleanup이 돌지 않는다. 한 파일에서 여러 번 `render`하면 `afterEach(cleanup)`를 둔다.
- `fetch`는 `vi.stubGlobal('fetch', ...)`로 가짜를 넣고 `vi.unstubAllGlobals()`로 되돌린다.
- api 응답 타입은 api의 Kotlin 응답 클래스와 같은 형식으로 기능 폴더의 `types.ts`에 둔다(예: `src/connection/types.ts`).
- `App`이 `ConnectionStatus`를 그려 마운트 때 `fetch`를 부른다. `App`을 그리는 테스트는 `fetch`를 가짜로 넣지 않으면 실제 요청이 실패해 "불러오지 못했" 안내가 뜬다(테스트는 통과하지만 화면 글을 볼 때 염두에 둔다).
