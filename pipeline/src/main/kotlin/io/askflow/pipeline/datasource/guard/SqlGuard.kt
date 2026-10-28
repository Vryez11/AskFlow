package io.askflow.pipeline.datasource.guard

import io.askflow.pipeline.datasource.ExecutionError
import io.askflow.pipeline.datasource.ExecutionErrorKind
import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.expression.LongValue
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.Limit
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.SetOperationList

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
    private val allowedLowercase = allowedTables.map { it.lowercase() }.toSet()

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

        val references = ReferenceCollector.collect(statement)
        references.nestedNonSelect?.let {
            return reject(ExecutionErrorKind.NOT_SELECT, "SELECT 조회만 실행할 수 있습니다.", it)
        }
        references.tables.firstOrNull { !isAllowed(it) }?.let {
            return reject(ExecutionErrorKind.TABLE_NOT_ALLOWED, "조회를 허용하지 않은 테이블입니다.", it.written)
        }
        references.functions.firstOrNull { it.substringAfterLast('.').uppercase() in DENIED_FUNCTIONS }?.let {
            return reject(ExecutionErrorKind.FUNCTION_NOT_ALLOWED, "실행을 허용하지 않은 함수입니다.", it)
        }

        capLimit(statement)
        return GuardResult.Passed(statement.toString())
    }

    /**
     * 최상위 LIMIT이 없거나 `maxRows + 1`보다 크면 `maxRows + 1`로 바꾼다. OFFSET은 둔다.
     * 숫자 리터럴이 아닌 LIMIT(`?`, `ALL` 등)도 바꾼다.
     */
    private fun capLimit(statement: Select) {
        val target = topLimitHolder(statement)
        val cap = maxRows.toLong() + 1
        val limit = target.limit ?: Limit().also { target.limit = it }
        val rowCount = limit.rowCount
        if (rowCount !is LongValue || rowCount.bigIntegerValue > cap.toBigInteger()) {
            limit.rowCount = LongValue(cap)
        }
    }

    // 괄호 없는 UNION 끝의 LIMIT은 MySQL에서 전체에 걸리지만 JSqlParser는 마지막 SELECT에 붙인다(pipeline/CLAUDE.md)
    private fun topLimitHolder(statement: Select): Select {
        if (statement is SetOperationList && statement.limit == null) {
            val last = statement.selects.last()
            if (last is PlainSelect && last.limit != null) return last
        }
        return statement
    }

    private fun isAllowed(table: ReferenceCollector.TableRef): Boolean =
        !table.hasCatalog &&
            (table.schema == null || table.schema.equals(schema, ignoreCase = true)) &&
            table.name.lowercase() in allowedLowercase

    private fun parseCause(e: JSQLParserException): String = (e.cause?.message ?: e.message ?: "해석 오류").lineSequence().first()

    private fun reject(
        kind: ExecutionErrorKind,
        message: String,
        cause: String,
    ): GuardResult.Rejected = GuardResult.Rejected(ExecutionError(kind, message, cause, CHECK_POINTS))

    private companion object {
        val CHECK_POINTS = listOf("실행하려던 SQL")

        /** 잠그거나, 오래 붙잡거나, 서버 파일을 읽는 함수 */
        val DENIED_FUNCTIONS = setOf("SLEEP", "BENCHMARK", "LOAD_FILE", "GET_LOCK", "RELEASE_LOCK", "RELEASE_ALL_LOCKS")
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
