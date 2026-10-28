package io.askflow.pipeline.datasource.guard

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * JSqlParser 해석 트리를 리플렉션으로 빠짐없이 훑을 때 쓰는 도구.
 * visitor는 일부 노드를 건너뛰어, 검사([ReferenceCollector])와 개인정보 추적([PiiLineage]) 모두 이것으로 훑는다.
 */
internal object JsqlTree {
    private const val JSQLPARSER_PACKAGE = "net.sf.jsqlparser."

    private val fieldCache = HashMap<Class<*>, List<Field>>()

    fun isNode(node: Any): Boolean = node.javaClass.name.startsWith(JSQLPARSER_PACKAGE)

    /** 더 내려갈 것이 없는 값(문자열, 숫자, enum 등) */
    fun isLeaf(node: Any): Boolean =
        node is CharSequence || node is Number || node is Boolean || node is Char || node is Enum<*> || node is Class<*>

    /** 클래스 계층의 인스턴스 필드. 파서 내부 노드(`transient`)는 뺀다 */
    fun fieldsOf(type: Class<*>): List<Field> =
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

    fun unquote(name: String): String = name.removeSurrounding("`").removeSurrounding("\"")
}
