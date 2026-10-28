import { useEffect, useState } from 'react'
import { fetchConnectionStatus } from './api.ts'
import type { ConnectionStatusResponse } from './types.ts'

type LoadState =
  | { kind: 'loading' }
  | { kind: 'loaded'; status: ConnectionStatusResponse }
  | { kind: 'error' }

// 홈의 데이터 연결 상태 (data-connection "홈의 연결 상태 표시"). 디자인 토큰 전이라 스타일은 최소로 둔다
function ConnectionStatus() {
  const [load, setLoad] = useState<LoadState>({ kind: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    fetchConnectionStatus(controller.signal)
      .then((status) => setLoad({ kind: 'loaded', status }))
      .catch(() => {
        if (!controller.signal.aborted) setLoad({ kind: 'error' })
      })
    return () => controller.abort()
  }, [])

  return (
    <section aria-label="데이터 연결 상태">
      <h2>데이터 연결</h2>
      {load.kind === 'loading' && <p>연결 상태를 확인하는 중입니다</p>}
      {load.kind === 'error' && <p role="alert">연결 상태를 불러오지 못했습니다</p>}
      {load.kind === 'loaded' && <StatusDetail status={load.status} />}
    </section>
  )
}

function StatusDetail({ status }: { status: ConnectionStatusResponse }) {
  if (status.state === 'FAILED') {
    return (
      <div role="alert">
        <p>
          <strong>연결 실패</strong>
        </p>
        {status.failure && (
          <>
            <p>{status.failure.message}</p>
            {status.failure.checkKeys.length > 0 && (
              <>
                <p>확인할 설정</p>
                <ul>
                  {status.failure.checkKeys.map((key) => (
                    <li key={key}>
                      <code>{key}</code>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </>
        )}
      </div>
    )
  }

  return (
    <div>
      <p>
        <strong>연결됨</strong> · 테이블 {status.tableCount}개 · 용어 {status.termCount}개
      </p>
      {status.writePrivileges.length > 0 && (
        <p role="alert">
          <strong>쓰기 권한 경고:</strong> 분석용 계정에 {status.writePrivileges.join(', ')} 권한이 있습니다. 읽기 전용
          계정으로 바꿔 주세요
        </p>
      )}
      {status.warnings.length > 0 && (
        <ul>
          {status.warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}
    </div>
  )
}

export default ConnectionStatus
