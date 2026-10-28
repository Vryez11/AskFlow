package io.askflow.pipeline.datasource.guard

/**
 * 해석 전에 원문을 보는 검사 (design 3장).
 * JSqlParser가 주석으로 버리거나 해석하지 못하는 MySQL 구문을 찾는다.
 */
internal object RawSqlScanner {
    private val LOCK_OR_INTO =
        Regex(
            """\bFOR\s+(UPDATE|SHARE)\b|\bLOCK\s+IN\s+SHARE\s+MODE\b|\bINTO\b""",
            RegexOption.IGNORE_CASE,
        )

    // 문자열 리터럴 밖의 실행 주석("/" + "*!")과 옵티마이저 힌트("/" + "*+").
    // Kotlin 주석은 중첩되므로 여기에 그 모양을 그대로 쓰지 않는다. 보통 주석 안에 있어도 찾는다.
    fun findExecutableComment(sql: String): String? {
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c.isQuote() -> i = skipQuoted(sql, i)
                sql.startsWith("/*", i) && i + 2 < sql.length && sql[i + 2] in "!+" -> return sql.substring(i, i + 3)
                else -> i++
            }
        }
        return null
    }

    /** 리터럴과 주석 밖의 잠금(`FOR UPDATE`, `FOR SHARE`, `LOCK IN SHARE MODE`)과 `INTO`. 서브쿼리 안도 본다 */
    fun findLockOrInto(sql: String): String? = LOCK_OR_INTO.find(codeOnly(sql))?.value?.replace(Regex("""\s+"""), " ")

    /** 리터럴과 주석을 공백으로 바꾼 원문 */
    private fun codeOnly(sql: String): String {
        val out = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val end =
                when {
                    sql[i].isQuote() -> skipQuoted(sql, i)
                    sql.startsWith("/*", i) -> sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                    sql[i] == '#' || isDashComment(sql, i) -> sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
                    else -> -1
                }
            if (end < 0) {
                out.append(sql[i])
                i++
            } else {
                repeat(end - i) { out.append(' ') }
                i = end
            }
        }
        return out.toString()
    }

    // MySQL은 `--` 뒤에 공백이나 제어 문자가 있어야 주석으로 본다
    private fun isDashComment(
        sql: String,
        i: Int,
    ): Boolean = sql.startsWith("--", i) && (i + 2 == sql.length || sql[i + 2].isWhitespace() || sql[i + 2].isISOControl())

    private fun Char.isQuote() = this == '\'' || this == '"' || this == '`'

    /** [start]의 따옴표로 시작한 리터럴·식별자 다음 위치. 닫히지 않으면 끝 */
    private fun skipQuoted(
        sql: String,
        start: Int,
    ): Int {
        val quote = sql[start]
        var i = start + 1
        while (i < sql.length) {
            when {
                // 백틱 식별자에는 백슬래시 이스케이프가 없다. 겹친 따옴표는 닫고 다시 여는 것으로 처리된다
                sql[i] == '\\' && quote != '`' -> i += 2
                sql[i] == quote -> return i + 1
                else -> i++
            }
        }
        return sql.length
    }
}
