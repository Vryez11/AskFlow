package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.ConnectionFailure

/**
 * 연결할 때 난 예외를 원인과 확인할 설정 키로 나눈다 (design 6장).
 * HikariCP가 감싼 예외도 원인 사슬을 따라가 본다. 메시지에서 비밀번호는 뺀다.
 * 1045 → 인증 실패, 통신 오류(SQLState `08*`) → 접속 불가, 1049·1044 → 스키마 없음 또는 권한 없음.
 */
internal class ConnectionFailureClassifier(
    private val password: String,
) {
    fun classify(error: Throwable): ConnectionFailure = TODO()
}
