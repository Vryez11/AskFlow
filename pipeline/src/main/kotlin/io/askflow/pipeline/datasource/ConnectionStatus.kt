package io.askflow.pipeline.datasource

import java.time.Instant

/**
 * 연결 확인 결과 (data-connection "연결 상태 조회"). 비밀번호는 담지 않는다.
 */
data class ConnectionStatus(
    val state: ConnectionState,
    /** 허용 테이블 수 */
    val tableCount: Int,
    /** 조회가 아닌 권한 이름 (INSERT 등) */
    val writePrivileges: List<String>,
    /** 권한을 끝까지 확인하지 못한 경우 등 */
    val warnings: List<String>,
    val failure: ConnectionFailure?,
    val checkedAt: Instant,
)

enum class ConnectionState {
    CONNECTED,
    FAILED,
}

data class ConnectionFailure(
    val reason: ConnectionFailureReason,
    /** 한국어 메시지 */
    val message: String,
    /** 확인할 설정 키 */
    val checkKeys: List<String>,
)

enum class ConnectionFailureReason(
    val label: String,
) {
    AUTHENTICATION("인증 실패"),
    UNREACHABLE("접속 불가"),
    SCHEMA_UNAVAILABLE("스키마 없음 또는 권한 없음"),
    TABLE_NOT_FOUND("없는 허용 테이블"),
    UNKNOWN("알 수 없는 오류"),
}
