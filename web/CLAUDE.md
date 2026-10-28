# web

- 테스트는 Vitest + Testing Library(`@testing-library/react`)다. jest-dom은 없으니 `toBeTruthy()`나 `textContent` 비교를 쓴다.
- Vitest `globals`가 꺼져 있어 Testing Library의 자동 cleanup이 돌지 않는다. 한 파일에서 여러 번 `render`하면 `afterEach(cleanup)`를 둔다.
- `fetch`는 `vi.stubGlobal('fetch', ...)`로 가짜를 넣고 `vi.unstubAllGlobals()`로 되돌린다.
- api 응답 타입은 api의 Kotlin 응답 클래스와 같은 형식으로 기능 폴더의 `types.ts`에 둔다(예: `src/connection/types.ts`).
