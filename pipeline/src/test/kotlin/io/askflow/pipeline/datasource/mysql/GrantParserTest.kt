package io.askflow.pipeline.datasource.mysql

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** data-connection "쓰기 권한 경고"의 SHOW GRANTS 줄 해석 (F-003) */
class GrantParserTest {
    private val parser = GrantParser(schema = "olist")

    private val usage = "GRANT USAGE ON *.* TO `askflow_reader`@`%`"

    @Test
    fun `F-003 읽기 전용 계정 - SELECT와 SHOW VIEW만 있으면 경고가 없다`() {
        val check = parser.parse(listOf(usage, "GRANT SELECT, SHOW VIEW ON `olist`.* TO `askflow_reader`@`%`"))

        assertEquals(emptyList(), check.writePrivileges)
        assertEquals(emptyList(), check.warnings)
    }

    @Test
    fun `F-003 쓰기 권한이 있는 계정 - 스키마의 INSERT가 경고에 들어간다`() {
        val check = parser.parse(listOf(usage, "GRANT SELECT, INSERT ON `olist`.* TO `askflow_writer`@`%`"))

        assertEquals(listOf("INSERT"), check.writePrivileges)
    }

    @Test
    fun `F-003 쓰기 권한이 있는 계정 - 테이블 하나에만 준 권한도 본다`() {
        val check =
            parser.parse(
                listOf(
                    usage,
                    "GRANT SELECT ON `olist`.* TO `askflow_writer`@`%`",
                    "GRANT UPDATE, DELETE ON `olist`.`orders` TO `askflow_writer`@`%`",
                ),
            )

        assertEquals(listOf("UPDATE", "DELETE"), check.writePrivileges)
    }

    @Test
    fun `F-003 쓰기 권한이 있는 계정 - 컬럼 권한과 대소문자, 따옴표 없는 스키마도 본다`() {
        val check =
            parser.parse(
                listOf("grant select (`order_id`), insert (`order_status`) on OLIST.orders to 'askflow_writer'@'%'"),
            )

        assertEquals(listOf("INSERT"), check.writePrivileges)
    }

    @Test
    fun `F-003 쓰기 권한이 있는 계정 - 여러 줄에 나온 권한은 한 번만 적는다`() {
        val check =
            parser.parse(
                listOf(
                    "GRANT INSERT ON *.* TO `askflow_writer`@`%`",
                    "GRANT SELECT, INSERT ON `olist`.* TO `askflow_writer`@`%`",
                ),
            )

        assertEquals(listOf("INSERT"), check.writePrivileges)
    }

    @Test
    fun `F-003 쓰기 권한이 있는 계정 - 다른 스키마의 권한은 보지 않는다`() {
        val check =
            parser.parse(
                listOf(
                    usage,
                    "GRANT SELECT ON `olist`.* TO `askflow_reader`@`%`",
                    "GRANT ALL PRIVILEGES ON `askflow`.* TO `askflow_reader`@`%`",
                    "GRANT INSERT ON `olist_backup`.* TO `askflow_reader`@`%`",
                ),
            )

        assertEquals(emptyList(), check.writePrivileges)
    }

    @Test
    fun `F-003 전체 권한 계정 - 모든 DB에 ALL PRIVILEGES가 있으면 경고가 있다`() {
        val check = parser.parse(listOf("GRANT ALL PRIVILEGES ON *.* TO `root`@`%` WITH GRANT OPTION"))

        assertContains(check.writePrivileges, "ALL PRIVILEGES")
    }

    @Test
    fun `F-003 전체 권한 계정 - MySQL 8이 펼쳐 보여 주는 정적·동적 권한 줄도 경고한다`() {
        val check =
            parser.parse(
                listOf(
                    "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, SHOW VIEW ON *.* TO `root`@`%` WITH GRANT OPTION",
                    "GRANT BACKUP_ADMIN,SYSTEM_VARIABLES_ADMIN ON *.* TO `root`@`%` WITH GRANT OPTION",
                ),
            )

        for (privilege in listOf("INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "BACKUP_ADMIN", "SYSTEM_VARIABLES_ADMIN")) {
            assertContains(check.writePrivileges, privilege)
        }
        assertTrue("SELECT" !in check.writePrivileges && "SHOW VIEW" !in check.writePrivileges)
    }

    @Test
    fun `F-003 역할 줄 - 역할의 권한은 보지 않고 확인하지 못했다는 경고를 남긴다`() {
        val check =
            parser.parse(
                listOf(
                    usage,
                    "GRANT SELECT ON `olist`.* TO `askflow_reader`@`%`",
                    "GRANT `analyst`@`%`,`reporter`@`%` TO `askflow_reader`@`%`",
                ),
            )

        assertEquals(emptyList(), check.writePrivileges)
        assertEquals(1, check.warnings.size, "경고: ${check.warnings}")
        assertContains(check.warnings.single(), "analyst")
    }

    @Test
    fun `F-003 권한을 확인하지 못함 - 해석하지 못한 줄은 경고로 남긴다`() {
        val check = parser.parse(listOf(usage, "GRANT SELECT ON `olist`.* TO `askflow_reader`@`%`", "?"))

        assertTrue(check.warnings.isNotEmpty(), "해석하지 못한 줄은 경고가 있어야 한다")
    }

    @Test
    fun `F-003 권한을 확인하지 못함 - SHOW GRANTS가 빈 결과면 경고를 남긴다`() {
        val check = parser.parse(emptyList())

        assertTrue(check.warnings.isNotEmpty(), "빈 결과는 경고가 있어야 한다")
    }
}
