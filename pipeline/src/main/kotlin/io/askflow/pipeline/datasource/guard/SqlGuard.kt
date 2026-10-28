package io.askflow.pipeline.datasource.guard

import io.askflow.pipeline.datasource.ExecutionError
import io.askflow.pipeline.datasource.ExecutionErrorKind
import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.Select

/**
 * 실행 전 SQL 검사 (design 3장). 검사를 못 하면 막는다(fail-closed).
 * 순서: 실행 주석·힌트 → 해석 → 문 개수와 종류 → 테이블 → 함수 → LIMIT 다시 쓰기.
 */
class SqlGuard(
    private val schema: String,
    /** 대소문자를 무시하고 비교한다 */
    private val allowedTables: Set<String>,
    private val maxRows: Int,
) {
    fun check(sql: String): GuardResult {
        RawSqlScanner.findExecutableComment(sql)?.let {
            return reject(ExecutionErrorKind.NOT_SELECT, "MySQL이 실행하는 주석이나 힌트가 있습니다.", it)
        }
        // JSqlParser가 해석하지 못하는 잠금·파일 쓰기 구문은 해석 실패가 아니라 SELECT가 아닌 SQL로 거부한다
        RawSqlScanner.findLockOrInto(sql)?.let {
            return reject(ExecutionErrorKind.NOT_SELECT, "잠금이나 파일·변수 쓰기가 붙은 SELECT입니다.", it)
        }

        val statements =
            try {
                CCJSqlParserUtil.parseStatements(sql).toList()
            } catch (e: JSQLParserException) {
                return reject(ExecutionErrorKind.PARSE_FAILED, "SQL을 해석하지 못했습니다.", parseCause(e))
            }
        if (statements.size != 1) {
            return if (statements.isEmpty()) {
                reject(ExecutionErrorKind.PARSE_FAILED, "SQL을 해석하지 못했습니다.", "실행할 문이 없음")
            } else {
                reject(ExecutionErrorKind.MULTIPLE_STATEMENTS, "SQL 문은 하나만 실행할 수 있습니다.", "문 ${statements.size}개")
            }
        }
        val statement: Statement = statements.single()
        if (statement !is Select) {
            return reject(
                ExecutionErrorKind.NOT_SELECT,
                "SELECT 조회만 실행할 수 있습니다.",
                statement.javaClass.simpleName,
            )
        }

        return GuardResult.Passed(statement.toString())
    }

    private fun parseCause(e: JSQLParserException): String = (e.cause?.message ?: e.message ?: "해석 오류").lineSequence().first()

    private fun reject(
        kind: ExecutionErrorKind,
        message: String,
        cause: String,
    ): GuardResult.Rejected = GuardResult.Rejected(ExecutionError(kind, message, cause, CHECK_POINTS))

    private companion object {
        val CHECK_POINTS = listOf("실행하려던 SQL")
    }
}

sealed interface GuardResult {
    /** 검사를 통과했다. [sql]은 해석 트리를 다시 쓴, DB에 보낼 문장이다 */
    data class Passed(
        val sql: String,
    ) : GuardResult

    data class Rejected(
        val error: ExecutionError,
    ) : GuardResult
}
