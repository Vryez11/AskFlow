package io.askflow.pipeline.datasource.guard

import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.AllTableColumns
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.WithItem
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * 해석 트리에서 읽는 테이블과 부르는 함수를 모은다 (design 3장).
 *
 * JSqlParser의 visitor(`TablesNamesFinder`, 어댑터들)는 비교식 안 서브쿼리 같은 곳을 건너뛴다.
 * 놓친 곳이 곧 검사를 피해 가는 길이 되므로, 노드의 필드를 리플렉션으로 모두 따라가 빠짐없이 모은다.
 * CTE 이름은 그 이름이 보이는 범위 안에서만 테이블에서 뺀다.
 */
internal object ReferenceCollector {
    data class TableRef(
        /** 쓴 그대로의 이름. 오류 원인에 넣는다 */
        val written: String,
        val name: String,
        val schema: String?,
        /** 스키마 앞에 이름이 더 붙었다(`db.schema.table`) */
        val hasCatalog: Boolean,
    )

    data class References(
        val tables: List<TableRef>,
        val functions: List<String>,
        /** SELECT 안에 들어 있는 SELECT가 아닌 문(예: CTE 본문의 DELETE). 있으면 그 클래스 이름 */
        val nestedNonSelect: String?,
    )

    fun collect(statement: Statement): References = Walker().apply { walk(statement, emptySet()) }.result()

    private class Walker {
        private val tables = mutableListOf<TableRef>()
        private val functions = mutableListOf<String>()
        private var nestedNonSelect: String? = null
        private val visited = IdentityHashMap<Any, Unit>()

        fun result() = References(tables, functions, nestedNonSelect)

        /** [cteNames]: 지금 위치에서 보이는 CTE 이름 */
        fun walk(
            node: Any?,
            cteNames: Set<String>,
        ) {
            if (node == null || isLeaf(node) || visited.put(node, Unit) != null) return
            when (node) {
                is Iterable<*> -> node.forEach { walk(it, cteNames) }
                is Map<*, *> -> node.values.forEach { walk(it, cteNames) }
                is Array<*> -> node.forEach { walk(it, cteNames) }
                is Select -> walkSelect(node, cteNames)
                is Statement -> nestedNonSelect = nestedNonSelect ?: node.javaClass.simpleName
                is Table -> {
                    addTable(node, cteNames)
                    walkFields(node, cteNames)
                }
                // 컬럼과 `t.*` 앞의 이름은 테이블 별칭일 수 있어 테이블로 보지 않는다
                is Column, is AllTableColumns -> walkFields(node, cteNames, skip = "table")
                is Function -> {
                    functions += node.multipartName.joinToString(".") { unquote(it) }
                    walkFields(node, cteNames)
                }
                else -> if (node.javaClass.name.startsWith(JSQLPARSER_PACKAGE)) walkFields(node, cteNames)
            }
        }

        // MySQL에서 CTE는 앞에 정의한 CTE만 볼 수 있고, RECURSIVE면 자기 자신도 본다
        private fun walkSelect(
            select: Select,
            cteNames: Set<String>,
        ) {
            val withItems = select.withItemsList.orEmpty()
            val recursive = withItems.any { it.isRecursive }
            var visible = cteNames
            withItems.forEach { item ->
                val name = unquote(item.alias.name)
                walkWithItem(item, if (recursive) visible + name else visible)
                visible = visible + name
            }
            walkFields(select, visible, skip = "withItemsList")
        }

        private fun walkWithItem(
            item: WithItem<*>,
            cteNames: Set<String>,
        ) {
            visited[item] = Unit
            // 본문이 DELETE 같은 문이어도 해석은 되므로 여기서 걸러 낸다
            walk(item.parenthesedStatement, cteNames)
            walk(item.withItemList, cteNames)
        }

        private fun addTable(
            table: Table,
            cteNames: Set<String>,
        ) {
            val name = unquote(table.name)
            val schema = table.schemaName?.let(::unquote)
            if (schema == null && name in cteNames) return
            tables += TableRef(table.fullyQualifiedName, name, schema, table.databaseName != null)
        }

        private fun walkFields(
            node: Any,
            cteNames: Set<String>,
            skip: String? = null,
        ) {
            fieldsOf(node.javaClass).forEach { field ->
                if (field.name != skip) walk(field.get(node), cteNames)
            }
        }
    }

    private const val JSQLPARSER_PACKAGE = "net.sf.jsqlparser."

    private val fieldCache = HashMap<Class<*>, List<Field>>()

    /** 클래스 계층의 인스턴스 필드. 파서 내부 노드(`transient`)는 뺀다 */
    private fun fieldsOf(type: Class<*>): List<Field> =
        synchronized(fieldCache) {
            fieldCache.getOrPut(type) {
                generateSequence(type) { it.superclass }
                    .takeWhile { it.name.startsWith(JSQLPARSER_PACKAGE) }
                    .flatMap { it.declaredFields.asSequence() }
                    .filterNot { Modifier.isStatic(it.modifiers) || Modifier.isTransient(it.modifiers) }
                    .onEach { it.isAccessible = true }
                    .toList()
            }
        }

    private fun isLeaf(node: Any): Boolean =
        node is CharSequence || node is Number || node is Boolean || node is Char || node is Enum<*> || node is Class<*>

    private fun unquote(name: String): String = name.removeSurrounding("`").removeSurrounding("\"")
}
