import ConnectionStatus from './connection/ConnectionStatus.tsx'

// 화면 구성은 IA(docs/mvp/03-ia.md)와 STEP 6 디자인 토큰을 정한 뒤 채운다
function App() {
  return (
    <main>
      <h1>AskFlow</h1>
      <p>물어보면, 보고서가 된다</p>
      <ConnectionStatus />
    </main>
  )
}

export default App
