package io.askflow.api.connection

import org.springframework.stereotype.Component

/** 용어 사전의 용어 수 (design 8장). F-002가 용어 사전을 읽는 구현으로 바꾼다 */
fun interface TermCountProvider {
    fun termCount(): Int
}

/** 용어 사전이 아직 없을 때의 기본 구현. 늘 0이다 */
@Component
class ZeroTermCountProvider : TermCountProvider {
    override fun termCount(): Int = TODO()
}
