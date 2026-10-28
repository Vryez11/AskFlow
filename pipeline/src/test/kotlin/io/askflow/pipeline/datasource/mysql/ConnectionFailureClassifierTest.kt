package io.askflow.pipeline.datasource.mysql

import io.askflow.pipeline.datasource.ConnectionFailureReason
import java.net.ConnectException
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTransientConnectionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** data-connection "연결 실패에도 앱은 뜬다"의 실패 원인 분류 (F-003) */
class ConnectionFailureClassifierTest {
    private val password = "s3cret-pw"
    private val classifier = ConnectionFailureClassifier(password)

    // HikariCP는 연결을 못 얻으면 마지막 연결 예외를 원인으로 감싸 던진다
    private fun hikariTimeout(cause: Throwable?): SQLTransientConnectionException =
        SQLTransientConnectionException(
            "askflow-target - Connection is not available, request timed out after 3001ms.",
            (cause as? SQLException)?.sqlState,
            cause,
        )

    private val accessDenied =
        SQLException("Access denied for user 'askflow_reader'@'172.17.0.1' (using password: YES)", "28000", 1045)

    @Test
    fun `F-003 비밀번호가 틀림 - 1045는 인증 실패이고 계정 이름과 비밀번호 환경 변수를 확인하게 한다`() {
        val failure = classifier.classify(accessDenied)

        assertEquals(ConnectionFailureReason.AUTHENTICATION, failure.reason)
        assertEquals(listOf("askflow.target-db.username", "ASKFLOW_READER_PASSWORD"), failure.checkKeys)
    }

    @Test
    fun `F-003 비밀번호가 틀림 - HikariCP가 감싼 예외도 원인을 따라가 인증 실패로 본다`() {
        val failure = classifier.classify(hikariTimeout(accessDenied))

        assertEquals(ConnectionFailureReason.AUTHENTICATION, failure.reason)
    }

    @Test
    fun `F-003 DB에 닿지 않음 - 통신 오류는 접속 불가이고 접속 주소를 확인하게 한다`() {
        val communications =
            SQLNonTransientConnectionException(
                "Communications link failure",
                "08S01",
                0,
                ConnectException("Connection refused"),
            )

        for (error in listOf(communications, hikariTimeout(communications))) {
            val failure = classifier.classify(error)

            assertEquals(ConnectionFailureReason.UNREACHABLE, failure.reason, "예외: $error")
            assertEquals(listOf("askflow.target-db.url"), failure.checkKeys)
        }
    }

    @Test
    fun `F-003 DB에 닿지 않음 - 원인 없이 풀 대기 시간이 끝나도 접속 불가로 본다`() {
        val failure = classifier.classify(hikariTimeout(null))

        assertEquals(ConnectionFailureReason.UNREACHABLE, failure.reason)
        assertEquals(listOf("askflow.target-db.url"), failure.checkKeys)
    }

    @Test
    fun `F-003 스키마가 없음 - 1049와 1044는 스키마 없음 또는 권한 없음이고 스키마 이름을 확인하게 한다`() {
        val unknownDatabase = SQLException("Unknown database 'olistt'", "42000", 1049)
        val noAccess = SQLException("Access denied for user 'askflow_reader'@'%' to database 'olist'", "42000", 1044)

        for (error in listOf(unknownDatabase, noAccess, hikariTimeout(noAccess))) {
            val failure = classifier.classify(error)

            assertEquals(ConnectionFailureReason.SCHEMA_UNAVAILABLE, failure.reason, "예외: $error")
            assertEquals(listOf("askflow.target-db.schema"), failure.checkKeys)
        }
    }

    @Test
    fun `F-003 연결 실패 - 모르는 오류는 알 수 없는 오류로 두고 DB 메시지를 담는다`() {
        val failure = classifier.classify(IllegalStateException(SQLException("Out of memory", "HY001", 1037)))

        assertEquals(ConnectionFailureReason.UNKNOWN, failure.reason)
        assertTrue(failure.message.contains("Out of memory"), "메시지: ${failure.message}")
    }

    @Test
    fun `F-003 비밀 값은 응답에 없음 - 예외 메시지에 비밀번호가 있어도 빼고 남긴다`() {
        val leaking =
            SQLException("Cannot load connection class for url jdbc:mysql://db/olist?password=$password", "08001", 0)

        val failure = classifier.classify(leaking)

        assertFalse(failure.message.contains(password), "메시지: ${failure.message}")
        assertFalse(failure.toString().contains(password), "실패: $failure")
    }
}
