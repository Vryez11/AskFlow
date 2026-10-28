package io.askflow.pipeline.datasource.mysql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 시나리오 밖이지만 design 5장 해석에서 빠지기 쉬운 줄 (F-003) */
class GrantParserEdgeTest {
    private val parser = GrantParser(schema = "olist")

    @Test
    fun `F-003 쓰기 권한 경고 - 설정 스키마에 걸리는 와일드카드 스키마 권한도 본다`() {
        val check =
            parser.parse(
                listOf(
                    "GRANT INSERT ON `oli%`.* TO `askflow_reader`@`%`",
                    "GRANT UPDATE ON `o_ist`.* TO `askflow_reader`@`%`",
                    "GRANT DELETE ON `o\\_ist`.* TO `askflow_reader`@`%`",
                ),
            )

        assertEquals(listOf("INSERT", "UPDATE"), check.writePrivileges)
    }

    @Test
    fun `F-003 쓰기 권한 경고 - 계정 범위의 PROXY 줄은 스키마 권한이 아니다`() {
        val check = parser.parse(listOf("GRANT PROXY ON ``@`` TO `root`@`%` WITH GRANT OPTION"))

        assertEquals(emptyList(), check.writePrivileges)
        assertEquals(emptyList(), check.warnings)
    }

    @Test
    fun `F-003 쓰기 권한 경고 - 루틴 권한과 따옴표 안의 ON·TO에 속지 않는다`() {
        val check =
            parser.parse(
                listOf(
                    "GRANT EXECUTE ON PROCEDURE `olist`.`refresh` TO `a on b`@`%`",
                    "GRANT `role to x`@`%` TO `askflow_reader`@`%`",
                ),
            )

        assertEquals(listOf("EXECUTE"), check.writePrivileges)
        assertEquals(1, check.warnings.size)
        assertTrue(check.warnings.single().contains("role to x"))
    }
}
