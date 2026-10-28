package io.askflow.pipeline.datasource.guard

import io.askflow.pipeline.datasource.guard.JsqlTree.fieldsOf
import io.askflow.pipeline.datasource.guard.JsqlTree.isLeaf
import io.askflow.pipeline.datasource.guard.JsqlTree.unquote
import net.sf.jsqlparser.expression.Alias
import net.sf.jsqlparser.expression.AnalyticExpression
import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.expression.WindowDefinition
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.AllColumns
import net.sf.jsqlparser.statement.select.AllTableColumns
import net.sf.jsqlparser.statement.select.FromItem
import net.sf.jsqlparser.statement.select.LateralSubSelect
import net.sf.jsqlparser.statement.select.ParenthesedFromItem
import net.sf.jsqlparser.statement.select.ParenthesedSelect
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.SelectItem
import net.sf.jsqlparser.statement.select.SetOperationList
import java.util.IdentityHashMap

/**
 * 해석 트리로 최상위 SELECT 항목마다 안전이 확인된 열인지 추적한다 (design 7장 "판정 방향").
 * 안전하려면 모든 잎이 리터럴이거나 확인된 일반 컬럼이고, 모든 함수와 노드가 [BuiltinAllowList]에 있어야 한다.
 * 별칭으로 테이블까지 풀고, 서브쿼리·CTE는 그 안의 SELECT 항목으로 이어 간다. COUNT 안의 재료는 따지지 않는다.
 * 뷰의 모든 열, 생성 컬럼, 읽은 컬럼 목록에 없는 이름, 저장 함수나 스키마를 붙인 함수를 부른 항목은 개인정보로 본다.
 *
 * 돌려주는 항목 수는 결과 열 수와 다를 수 있다(`*`, UNION 가지에 `*`가 있을 때, VALUES 같은 문).
 * 결과 열에 맞추지 못하면 모두 가린다.
 */
class PiiLineage(
    /** "table.column" 형식. 대소문자를 무시하고 비교한다 */
    piiColumns: Collection<String>,
    private val objects: PiiSchemaObjects = PiiSchemaObjects.NONE,
) {
    /** (테이블, 컬럼). 모두 소문자 */
    private val piiColumns: Set<Pair<String, String>> = piiColumnPairs(piiColumns)

    /** [SqlGuard]를 통과한 SQL을 받아, 최상위 SELECT 항목 순서대로 판정을 돌려준다 */
    fun trace(sql: String): List<ItemPii> {
        val select = CCJSqlParserUtil.parse(sql) as? Select ?: return listOf(ItemPii.PII)
        return outputs(select, null, emptyMap()).map { output ->
            when (output) {
                is Output.Named -> if (output.pii) ItemPii.PII else ItemPii.SAFE
                // 서브쿼리·CTE·뷰를 펼친 `*`는 메타데이터의 원래 테이블을 믿을 수 없어 가린다
                is Output.Star -> if (output.sources.all { it is Source.BaseTable && !it.isView }) ItemPii.STAR else ItemPii.PII
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

        /** 테이블이나 뷰. 뷰면 모든 열이 개인정보다. [columnPii]는 읽은 컬럼 목록에 없는 이름이면 null */
        class BaseTable(
            override val name: String,
            val isView: Boolean,
            private val columnPii: (String) -> Boolean?,
        ) : Source {
            override fun pii(column: String): Boolean? = if (isView) true else columnPii(column)
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
                else -> Output.Named(itemName(item), ExpressionWalker(scope, ctes, select.windowDefinitions.orEmpty()).isPii(expression))
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
                val caseOnlyCte = ctes.keys.any { it != name && it.equals(name, ignoreCase = true) }
                if (item.schemaName == null && cte != null) {
                    listOf(Source.Derived(sourceName, renamed(cte, aliasColumns(item.alias))))
                } else if ((item.schemaName == null && caseOnlyCte) || !objects.isExactTable(name)) {
                    // 대소문자만 다르게 맞으면 서버 설정에 따라 다른 테이블이나 CTE로 읽힐 수 있다 (design 7장 "출처 이름")
                    listOf(Source.Unknown(sourceName))
                } else {
                    listOf(
                        Source.BaseTable(sourceName, objects.isView(name)) { column ->
                            if (objects.isKnownColumn(name, column)) {
                                (name.lowercase() to column.lowercase()) in piiColumns || objects.isGenerated(name, column)
                            } else {
                                null
                            }
                        },
                    )
                }
            }
            // LATERAL 안의 이름은 같은 FROM의 앞쪽 출처를 먼저 찾는다. 그 규칙을 따라가지 않고 모든 열을 가린다
            is LateralSubSelect -> listOf(Source.Unknown(item.alias?.let { unquote(it.name) }.orEmpty()))
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
     * SELECT 항목 식 안을 모두 훑어, 안전이 확인되지 않은 것이 하나라도 있으면 개인정보로 본다.
     * visitor는 노드를 놓칠 수 있어 [ReferenceCollector]처럼 필드를 리플렉션으로 따라간다.
     * [windows]는 이 SELECT의 `WINDOW` 절이다. `OVER w`의 정의는 창 함수 노드에 붙지 않아 따로 찾는다.
     */
    private inner class ExpressionWalker(
        private val scope: Scope,
        private val ctes: Map<String, List<Output>>,
        private val windows: List<WindowDefinition>,
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
                is Function -> walkFunction(node)
                is AnalyticExpression -> walkAnalytic(node)
                is Select -> pii = outputs(node, scope, ctes).any { it !is Output.Named || it.pii }
                else ->
                    when {
                        BuiltinAllowList.isLiteral(node) -> Unit
                        BuiltinAllowList.isCombiner(node) -> walkFields(node)
                        // 처음 보는 노드(변수, `*`, 모르는 문법)는 무엇을 돌려줄지 모르니 안전하지 않다
                        else -> pii = true
                    }
            }
        }

        private fun walkFields(
            node: Any,
            skip: String? = null,
        ) {
            fieldsOf(node.javaClass).forEach { if (it.name != skip) walk(it.get(node)) }
        }

        // 저장 함수는 인자와 상관없이 어떤 값이든 읽어 돌려줄 수 있다. 스키마를 붙인 함수는 어느 스키마 것인지 따지지 않는다
        private fun walkFunction(function: Function) {
            val parts = function.multipartName.map(::unquote)
            val name = parts.singleOrNull()
            when {
                name == null || objects.isStoredFunction(name) || !BuiltinAllowList.isSafeFunction(name) -> pii = true
                // COUNT는 개수만 돌려주니 안에 무엇을 넣어도 값이 나가지 않는다
                name.equals("COUNT", ignoreCase = true) -> Unit
                else -> {
                    val first = function.parameters?.firstOrNull()
                    if (BuiltinAllowList.isUnitArgument(name, first)) visited[first] = Unit
                    walkFields(function)
                }
            }
        }

        // 창 함수 COUNT도 센 재료는 따지지 않지만, 창을 나누고 정렬하는 식은 훑는다
        private fun walkAnalytic(expression: AnalyticExpression) {
            if (!BuiltinAllowList.isSafeFunction(expression.name)) {
                pii = true
                return
            }
            walkFields(expression, skip = if (expression.name.equals("COUNT", ignoreCase = true)) "expression" else null)
            val windowName = expression.windowName ?: return
            val window = windows.firstOrNull { it.windowName.equals(windowName, ignoreCase = true) }
            if (window == null) pii = true else walk(window)
        }

        /**
         * 테이블을 적었으면 바깥 범위까지 그 이름의 출처 모두에서, 안 적었으면 보이는 모든 출처에서 찾는다.
         * MySQL은 안쪽 테이블에 없는 컬럼을 바깥의 같은 별칭에서 찾으니, MySQL이 어느 쪽을 고를지 따지지 않고
         * 그 이름을 가진 출처 중 하나라도 개인정보면 가린다. 어느 출처에서도 확인하지 못하면 개인정보로 본다.
         */
        private fun isPiiColumn(column: Column): Boolean {
            val name = unquote(column.columnName)
            val table = column.table?.name?.let(::unquote)
            val candidates = scope.chain.flatMap { it.sources }
            val sources = if (table == null) candidates else candidates.filter { it.name.equals(table, ignoreCase = true) }
            return sources.toList().piiOf(name) ?: true
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
