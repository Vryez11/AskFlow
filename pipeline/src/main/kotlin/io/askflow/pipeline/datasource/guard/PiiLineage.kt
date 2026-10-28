package io.askflow.pipeline.datasource.guard

import io.askflow.pipeline.datasource.guard.JsqlTree.fieldsOf
import io.askflow.pipeline.datasource.guard.JsqlTree.isLeaf
import io.askflow.pipeline.datasource.guard.JsqlTree.isNode
import io.askflow.pipeline.datasource.guard.JsqlTree.unquote
import net.sf.jsqlparser.expression.Alias
import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.AllColumns
import net.sf.jsqlparser.statement.select.AllTableColumns
import net.sf.jsqlparser.statement.select.FromItem
import net.sf.jsqlparser.statement.select.ParenthesedFromItem
import net.sf.jsqlparser.statement.select.ParenthesedSelect
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.SelectItem
import net.sf.jsqlparser.statement.select.SetOperationList
import java.util.IdentityHashMap

/**
 * 해석 트리로 최상위 SELECT 항목마다 개인정보 컬럼에서 나온 열인지 추적한다 (design 7장).
 * 별칭으로 테이블까지 풀고, 서브쿼리·CTE는 그 안의 SELECT 항목으로 이어 간다.
 * COUNT 안에서만 쓴 경우만 예외이고, 풀 수 없으면 개인정보로 본다(fail-closed).
 *
 * 돌려주는 항목 수는 결과 열 수와 다를 수 있다(`*`, UNION 가지에 `*`가 있을 때, VALUES 같은 문).
 * 결과 열에 맞추지 못하면 모두 가린다.
 */
class PiiLineage(
    /** "table.column" 형식. 대소문자를 무시하고 비교한다 */
    piiColumns: Collection<String>,
) {
    /** 테이블 이름 → 개인정보 컬럼 이름. 모두 소문자 */
    private val piiByTable: Map<String, Set<String>> =
        piiColumns
            .map { it.lowercase() }
            .groupBy({ it.substringBeforeLast('.').substringAfterLast('.') }, { it.substringAfterLast('.') })
            .mapValues { it.value.toSet() }

    /** [SqlGuard]를 통과한 SQL을 받아, 최상위 SELECT 항목 순서대로 판정을 돌려준다 */
    fun trace(sql: String): List<ItemPii> {
        val select = CCJSqlParserUtil.parse(sql) as? Select ?: return listOf(ItemPii.PII)
        return outputs(select, null, emptyMap()).map { output ->
            when (output) {
                is Output.Named -> if (output.pii) ItemPii.PII else ItemPii.SAFE
                // 서브쿼리·CTE를 펼친 `*`는 메타데이터의 원래 테이블을 믿을 수 없어 가린다
                is Output.Star -> if (output.sources.all { it is Source.BaseTable }) ItemPii.STAR else ItemPii.PII
                Output.Opaque -> ItemPii.PII
            }
        }
    }

    /** SELECT 항목 하나가 내놓는 열 */
    private sealed interface Output {
        data class Named(
            val name: String,
            val pii: Boolean,
        ) : Output

        /** `*`, `t.*`. 펼쳐질 열은 [sources]에서 이름으로 찾는다 */
        class Star(
            val sources: List<Source>,
        ) : Output

        /** 어떤 열이 나오는지 알 수 없다. 모두 개인정보로 본다 */
        data object Opaque : Output
    }

    /** FROM에 놓인 것. [name]은 별칭, 없으면 테이블 이름 */
    private sealed interface Source {
        val name: String

        /** [column]이 개인정보면 true, 아니면 false, 이 출처에 없는 이름이면 null */
        fun pii(column: String): Boolean?

        class BaseTable(
            override val name: String,
            private val piiColumns: Set<String>,
        ) : Source {
            // 테이블의 컬럼 목록은 모르니 어떤 이름이든 있을 수 있다고 본다
            override fun pii(column: String): Boolean = column.lowercase() in piiColumns
        }

        class Derived(
            override val name: String,
            private val outputs: List<Output>,
        ) : Source {
            override fun pii(column: String): Boolean? {
                if (Output.Opaque in outputs) return true
                val found =
                    outputs.mapNotNull { output ->
                        when (output) {
                            is Output.Named -> output.pii.takeIf { output.name.equals(column, ignoreCase = true) }
                            is Output.Star -> output.sources.piiOf(column)
                            Output.Opaque -> true
                        }
                    }
                return if (found.isEmpty()) null else found.any { it }
            }
        }

        class Unknown(
            override val name: String,
        ) : Source {
            override fun pii(column: String): Boolean = true
        }
    }

    private class Scope(
        val sources: List<Source>,
        val parent: Scope?,
    ) {
        val chain: Sequence<Scope> get() = generateSequence(this) { it.parent }
    }

    /** [ctes]: 지금 위치에서 보이는 CTE 이름(쓴 그대로)과 그 열 */
    private fun outputs(
        select: Select,
        scope: Scope?,
        ctes: Map<String, List<Output>>,
    ): List<Output> {
        val visible = withCtes(select, scope, ctes)
        return when (select) {
            is PlainSelect -> plainOutputs(select, scope, visible)
            is SetOperationList -> merge(select.selects.map { outputs(it, scope, visible) })
            is ParenthesedSelect -> outputs(select.select, scope, visible)
            else -> listOf(Output.Opaque)
        }
    }

    // MySQL에서 CTE는 앞에 정의한 CTE만 볼 수 있다. 재귀 CTE의 자기 참조는 풀지 않고 가린다
    private fun withCtes(
        select: Select,
        scope: Scope?,
        ctes: Map<String, List<Output>>,
    ): Map<String, List<Output>> {
        val items = select.withItemsList.orEmpty()
        val recursive = items.any { it.isRecursive }
        var visible = ctes
        items.forEach { item ->
            val name = unquote(item.alias.name)
            val bodyCtes = if (recursive) visible + (name to listOf(Output.Opaque)) else visible
            val body = item.parenthesedStatement
            val out = if (body is Select) outputs(body, scope, bodyCtes) else listOf(Output.Opaque)
            visible = visible + (name to renamed(out, item.withItemList?.map { itemName(it) }))
        }
        return visible
    }

    private fun plainOutputs(
        select: PlainSelect,
        outer: Scope?,
        ctes: Map<String, List<Output>>,
    ): List<Output> {
        val sources =
            sourcesOf(select.fromItem, outer, ctes) +
                select.joins.orEmpty().flatMap { sourcesOf(it.fromItem, outer, ctes) }
        val scope = Scope(sources, outer)
        return select.selectItems.map { item ->
            when (val expression = item.expression) {
                is AllTableColumns -> {
                    val source = findSource(scope, unquote(expression.table.name))
                    if (source == null) Output.Opaque else Output.Star(listOf(source))
                }
                is AllColumns ->
                    if (expression.javaClass == AllColumns::class.java) Output.Star(sources) else Output.Opaque
                else -> Output.Named(itemName(item), ExpressionWalker(scope, ctes).isPii(expression))
            }
        }
    }

    private fun sourcesOf(
        item: FromItem?,
        outer: Scope?,
        ctes: Map<String, List<Output>>,
    ): List<Source> =
        when (item) {
            null -> emptyList()
            is Table -> {
                val name = unquote(item.name)
                val sourceName = item.alias?.let { unquote(it.name) } ?: name
                val cte = ctes[name]
                if (item.schemaName == null && cte != null) {
                    listOf(Source.Derived(sourceName, renamed(cte, aliasColumns(item.alias))))
                } else {
                    listOf(Source.BaseTable(sourceName, piiByTable[name.lowercase()].orEmpty()))
                }
            }
            // LATERAL이 앞의 FROM을 참조해도 그 이름은 바깥 범위에서 못 찾아 가려진다
            is ParenthesedSelect ->
                listOf(
                    Source.Derived(
                        item.alias?.let { unquote(it.name) }.orEmpty(),
                        renamed(outputs(item, outer, ctes), aliasColumns(item.alias)),
                    ),
                )
            is ParenthesedFromItem ->
                sourcesOf(item.fromItem, outer, ctes) + item.joins.orEmpty().flatMap { sourcesOf(it.fromItem, outer, ctes) }
            else -> listOf(Source.Unknown(item.alias?.let { unquote(it.name) }.orEmpty()))
        }

    private fun aliasColumns(alias: Alias?): List<String>? = alias?.aliasColumns?.map { unquote(it.name) }

    /** CTE·파생 테이블에 열 이름 목록을 붙였으면 그 이름으로 바꾼다. 자리를 맞출 수 없으면 모두 가린다 */
    private fun renamed(
        outputs: List<Output>,
        names: List<String>?,
    ): List<Output> {
        if (names.isNullOrEmpty()) return outputs
        if (outputs.size != names.size || outputs.any { it !is Output.Named }) {
            return names.map { Output.Named(it, pii = true) }
        }
        return names.zip(outputs) { name, output -> Output.Named(name, (output as Output.Named).pii) }
    }

    /** UNION 등은 자리마다 어느 가지든 개인정보면 개인정보다. 자리를 맞출 수 없으면 모두 가린다 */
    private fun merge(branches: List<List<Output>>): List<Output> {
        val first = branches.first()
        val aligned = branches.all { it.size == first.size } && branches.flatten().all { it is Output.Named }
        if (!aligned) return first.map { Output.Opaque }
        return first.indices.map { i ->
            Output.Named((first[i] as Output.Named).name, branches.any { (it[i] as Output.Named).pii })
        }
    }

    /** 바깥 쿼리에서 보이는 열 이름. MySQL처럼 별칭, 컬럼 이름, 식 원문 순으로 정한다 */
    private fun itemName(item: SelectItem<*>): String =
        item.alias?.let { unquote(it.name) }
            ?: (item.expression as? Column)?.let { unquote(it.columnName) }
            ?: item.expression.toString()

    private fun findSource(
        scope: Scope,
        name: String,
    ): Source? = scope.chain.firstNotNullOfOrNull { s -> s.sources.firstOrNull { it.name.equals(name, ignoreCase = true) } }

    /**
     * SELECT 항목 식 안을 모두 훑어, COUNT 밖에서 개인정보 컬럼을 참조하는지 본다.
     * visitor는 노드를 놓칠 수 있어 [ReferenceCollector]처럼 필드를 리플렉션으로 따라간다.
     */
    private inner class ExpressionWalker(
        private val scope: Scope,
        private val ctes: Map<String, List<Output>>,
    ) {
        private var pii = false
        private val visited = IdentityHashMap<Any, Unit>()

        fun isPii(expression: Any): Boolean {
            walk(expression)
            return pii
        }

        private fun walk(node: Any?) {
            if (pii || node == null || isLeaf(node) || visited.put(node, Unit) != null) return
            when (node) {
                is Iterable<*> -> node.forEach(::walk)
                is Map<*, *> -> node.values.forEach(::walk)
                is Array<*> -> node.forEach(::walk)
                is Column -> pii = isPiiColumn(node)
                is AllColumns -> pii = true
                is Function -> if (!isCount(node)) walkFields(node)
                is Select -> pii = outputs(node, scope, ctes).any { it !is Output.Named || it.pii }
                else -> if (isNode(node)) walkFields(node)
            }
        }

        private fun walkFields(node: Any) {
            fieldsOf(node.javaClass).forEach { walk(it.get(node)) }
        }

        // COUNT는 개수만 돌려주니 안에 무엇을 넣어도 값이 나가지 않는다. 창 함수 COUNT는 따로 보지 않고 훑는다
        private fun isCount(function: Function): Boolean =
            function.multipartName.size == 1 && unquote(function.name).equals("COUNT", ignoreCase = true)

        /** 테이블을 적었으면 그 출처에서, 안 적었으면 보이는 모든 출처에서 찾는다. 못 찾으면 개인정보로 본다 */
        private fun isPiiColumn(column: Column): Boolean {
            val name = unquote(column.columnName)
            val table = column.table?.name?.let(::unquote)
            if (table != null) return findSource(scope, table)?.pii(name) ?: true
            val found =
                scope.chain
                    .flatMap { it.sources }
                    .toList()
                    .piiOf(name)
            return found ?: true
        }
    }

    private companion object {
        fun List<Source>.piiOf(column: String): Boolean? {
            val found = mapNotNull { it.pii(column) }
            return if (found.isEmpty()) null else found.any { it }
        }
    }
}

enum class ItemPii {
    /** 개인정보 컬럼에서 나왔거나 어디서 왔는지 알 수 없다 */
    PII,

    /** 개인정보 컬럼 값이 담기지 않는다 */
    SAFE,

    /** `*`, `t.*`. 펼친 열은 JDBC 메타데이터로 판정하고, 메타데이터도 없으면 가린다 */
    STAR,
}
