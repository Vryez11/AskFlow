package io.askflow.pipeline.datasource.mysql

/**
 * `SHOW GRANTS FOR CURRENT_USER()` 줄을 해석해 조회가 아닌 권한을 찾는다 (design 5장).
 * 범위가 `*.*`이거나 설정 스키마(`olist`.*, `olist`.`table`)인 줄만 본다.
 * {SELECT, SHOW VIEW, USAGE} 밖의 권한은 모두 경고 대상이다(허용 목록 방식).
 * 스키마 이름의 `%`·`_`는 MySQL처럼 와일드카드로 보아, 설정 스키마에 걸리는 패턴이면 본다.
 */
internal class GrantParser(
    /** 대소문자를 무시하고 비교한다 */
    private val schema: String,
) {
    fun parse(grantLines: List<String>): GrantCheck {
        if (grantLines.isEmpty()) {
            return GrantCheck(emptyList(), listOf("권한 목록(SHOW GRANTS)이 비어 있어 권한을 확인하지 못했습니다."))
        }
        val writePrivileges = linkedSetOf<String>()
        val warnings = mutableListOf<String>()
        for (line in grantLines) {
            // 따옴표 안을 가려야 이름 속의 ON·TO·쉼표·점에 속지 않는다
            val masked = maskQuoted(line)
            val privilegeLine = PRIVILEGE_LINE.matchEntire(masked)
            val roleLine = if (privilegeLine == null) ROLE_LINE.matchEntire(masked) else null
            when {
                privilegeLine != null -> {
                    val scope = parseScope(line, privilegeLine.groups[2]!!.range)
                    when (scope) {
                        Scope.Unparsed -> warnings += "해석하지 못한 권한 줄이 있어 권한을 끝까지 확인하지 못했습니다: $line"
                        Scope.Other -> Unit
                        Scope.Covered ->
                            privileges(masked.substring(privilegeLine.groups[1]!!.range))
                                .filterNot { it in READ_PRIVILEGES }
                                .forEach { writePrivileges += it }
                    }
                }
                roleLine != null -> {
                    val roles = line.substring(roleLine.groups[1]!!.range).trim()
                    warnings += "역할($roles)이 부여되어 있어 역할의 권한까지는 확인하지 못했습니다."
                }
                else -> warnings += "해석하지 못한 권한 줄이 있어 권한을 끝까지 확인하지 못했습니다: $line"
            }
        }
        return GrantCheck(writePrivileges.toList(), warnings)
    }

    /** `SELECT (`a`), INSERT` → [SELECT, INSERT]. 컬럼 목록은 버리고 공백을 하나로 모은다 */
    private fun privileges(text: String): List<String> =
        text
            .replace(COLUMN_LIST, "")
            .split(',')
            .map { it.trim().replace(WHITESPACE, " ").uppercase() }
            .filter { it.isNotEmpty() }

    private fun parseScope(
        line: String,
        range: IntRange,
    ): Scope {
        val original = line.substring(range).trim()
        val masked = maskQuoted(original)
        // PROXY 권한처럼 범위가 계정이면 스키마 권한이 아니다
        if ('@' in masked) return Scope.Other
        val withoutKind = OBJECT_KIND.find(masked)?.let { it.range.last + 1 } ?: 0
        val dot = masked.indexOf('.', withoutKind)
        if (dot < 0 || masked.indexOf('.', dot + 1) >= 0) return Scope.Unparsed
        val database = unquote(original.substring(withoutKind, dot).trim())
        if (database.isEmpty()) return Scope.Unparsed
        return if (database == "*" || matchesSchema(database)) Scope.Covered else Scope.Other
    }

    /** MySQL의 DB 이름 패턴(`%`, `_`, `\` 이스케이프)이 설정 스키마에 걸리는지 */
    private fun matchesSchema(pattern: String): Boolean {
        val regex = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> regex.append(Regex.escape(pattern[++i].toString()))
                c == '%' -> regex.append(".*")
                c == '_' -> regex.append('.')
                else -> regex.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(regex.toString(), RegexOption.IGNORE_CASE).matches(schema)
    }

    private fun unquote(name: String): String =
        if (name.length >= 2 && name.first() == name.last() && name.first() in QUOTES) {
            name.substring(1, name.length - 1)
        } else {
            name
        }

    private enum class Scope { Covered, Other, Unparsed }

    private companion object {
        val READ_PRIVILEGES = setOf("SELECT", "SHOW VIEW", "USAGE")
        const val QUOTES = "`'\""
        val PRIVILEGE_LINE = Regex("""\s*GRANT\s+(.+?)\s+ON\s+(.+?)\s+TO\s+.+""", RegexOption.IGNORE_CASE)
        val ROLE_LINE = Regex("""\s*GRANT\s+(.+?)\s+TO\s+.+""", RegexOption.IGNORE_CASE)
        val OBJECT_KIND = Regex("""^\s*(TABLE|FUNCTION|PROCEDURE)\s+""", RegexOption.IGNORE_CASE)
        val COLUMN_LIST = Regex("""\([^)]*\)""")
        val WHITESPACE = Regex("""\s+""")

        /** 따옴표 안의 글자를 `x`로 바꾼다. 길이와 위치는 그대로라 원문을 같은 범위로 자를 수 있다 */
        fun maskQuoted(text: String): String {
            val out = StringBuilder(text.length)
            var quote: Char? = null
            for (c in text) {
                when {
                    quote == null && c in QUOTES -> {
                        quote = c
                        out.append(c)
                    }
                    quote != null && c == quote -> {
                        quote = null
                        out.append(c)
                    }
                    quote != null -> out.append('x')
                    else -> out.append(c)
                }
            }
            return out.toString()
        }
    }
}

internal data class GrantCheck(
    /** 조회가 아닌 권한 이름. 중복 없이 처음 나온 순서대로 */
    val writePrivileges: List<String>,
    /** 역할 줄, 해석하지 못한 줄처럼 권한을 끝까지 확인하지 못한 경우 */
    val warnings: List<String>,
)
