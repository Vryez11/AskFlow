package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.ConnectionFailure
import io.askflow.pipeline.datasource.ConnectionFailureReason
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException

/**
 * 연결할 때 난 예외를 원인과 확인할 설정 키로 나눈다 (design 6장).
 * HikariCP가 감싼 예외도 원인 사슬을 따라가 본다. 메시지에서 비밀번호는 뺀다.
 * 1045 → 인증 실패, 통신 오류(SQLState `08*`) → 접속 불가, 1049·1044 → 스키마 없음 또는 권한 없음.
 */
internal class ConnectionFailureClassifier(
    private val password: String,
) {
    fun classify(error: Throwable): ConnectionFailure {
        val chain = causeChain(error)
        val sqlErrors = chain.filterIsInstance<SQLException>()
        // 감싼 예외(HikariCP)는 안쪽 예외의 SQLState를 그대로 옮기니 오류 코드부터 본다
        val reason =
            when {
                sqlErrors.any { it.errorCode == ACCESS_DENIED } -> ConnectionFailureReason.AUTHENTICATION
                sqlErrors.any { it.errorCode == UNKNOWN_DATABASE || it.errorCode == DB_ACCESS_DENIED } ->
                    ConnectionFailureReason.SCHEMA_UNAVAILABLE
                sqlErrors.any { it.sqlState?.startsWith("08") == true } -> ConnectionFailureReason.UNREACHABLE
                chain.any { it is ConnectException || it is SocketTimeoutException || it is UnknownHostException } ->
                    ConnectionFailureReason.UNREACHABLE
                // 원인 없이 풀 대기 시간이 끝나면 연결을 한 번도 못 만든 것이다
                chain.last() is SQLTransientConnectionException -> ConnectionFailureReason.UNREACHABLE
                else -> ConnectionFailureReason.UNKNOWN
            }
        val detail = (sqlErrors.lastOrNull() ?: chain.last()).let { it.message ?: it.javaClass.simpleName }
        return ConnectionFailure(
            reason = reason,
            message = "${SUMMARY.getValue(reason)} (${withoutSecret(detail)})",
            checkKeys = CHECK_KEYS.getValue(reason),
        )
    }

    private fun causeChain(error: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = error
        // 원인 사슬이 자기 자신을 가리키는 경우를 막는다
        while (current != null && chain.none { it === current } && chain.size < MAX_CHAIN) {
            chain += current
            current = current.cause
        }
        return chain
    }

    private fun withoutSecret(text: String): String = if (password.isEmpty()) text else text.replace(password, "****")

    private companion object {
        const val ACCESS_DENIED = 1045
        const val DB_ACCESS_DENIED = 1044
        const val UNKNOWN_DATABASE = 1049
        const val MAX_CHAIN = 20

        val SUMMARY =
            mapOf(
                ConnectionFailureReason.AUTHENTICATION to "계정 이름이나 비밀번호가 맞지 않아 접속하지 못했습니다.",
                ConnectionFailureReason.UNREACHABLE to "분석 대상 DB 주소에 닿지 않습니다.",
                ConnectionFailureReason.SCHEMA_UNAVAILABLE to "스키마가 없거나 이 계정에 스키마 접근 권한이 없습니다.",
                ConnectionFailureReason.UNKNOWN to "알 수 없는 오류로 연결하지 못했습니다.",
            )

        val CHECK_KEYS =
            mapOf(
                ConnectionFailureReason.AUTHENTICATION to listOf("askflow.target-db.username", "ASKFLOW_READER_PASSWORD"),
                ConnectionFailureReason.UNREACHABLE to listOf("askflow.target-db.url"),
                ConnectionFailureReason.SCHEMA_UNAVAILABLE to listOf("askflow.target-db.schema"),
                ConnectionFailureReason.UNKNOWN to listOf("askflow.target-db.url", "askflow.target-db.username"),
            )
    }
}
