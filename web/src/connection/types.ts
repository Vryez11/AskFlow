// GET /api/connection/status 응답 (design 8장, api의 ConnectionStatusResponse와 같은 형식)

export type ConnectionState = 'CONNECTED' | 'FAILED'

// api의 ConnectionFailureReason enum 이름
export type ConnectionFailureReason =
  | 'AUTHENTICATION'
  | 'UNREACHABLE'
  | 'SCHEMA_UNAVAILABLE'
  | 'TABLE_NOT_FOUND'
  | 'UNKNOWN'

export interface ConnectionFailure {
  reason: ConnectionFailureReason
  // 한국어 원인 메시지
  message: string
  // 확인할 설정 키
  checkKeys: string[]
}

export interface ConnectionStatusResponse {
  state: ConnectionState
  tableCount: number
  termCount: number
  writePrivileges: string[]
  warnings: string[]
  // 연결됨이면 null이거나 없다
  failure?: ConnectionFailure | null
  // ISO-8601 시각
  checkedAt: string
}
