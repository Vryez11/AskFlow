import type { ConnectionStatusResponse } from './types.ts'

// GET /api/connection/status. 네트워크 오류와 2xx 밖 응답은 모두 예외로 던진다
export async function fetchConnectionStatus(signal?: AbortSignal): Promise<ConnectionStatusResponse> {
  const response = await fetch('/api/connection/status', {
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) {
    throw new Error(`연결 상태 API 응답 ${response.status}`)
  }
  return (await response.json()) as ConnectionStatusResponse
}
