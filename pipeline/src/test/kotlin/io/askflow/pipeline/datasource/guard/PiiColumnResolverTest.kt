package io.askflow.pipeline.datasource.guard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 뷰와 생성 컬럼의 정의를 따라간 실제 개인정보 컬럼 (F-003, 7.1 재검토 막음) */
class PiiColumnResolverTest {
    private val pii = listOf("customers.customer_zip_code_prefix", "customers.customer_unique_id")

    @Test
    fun `F-003 뷰를 거친 열 - 개인정보 컬럼을 다른 이름으로 내보낸 뷰 열이 개인정보 컬럼이 된다`() {
        val view =
            ViewDefinition(
                name = "customer_regions",
                definition =
                    "select `olist`.`customers`.`customer_id` AS `customer_id`," +
                        "`olist`.`customers`.`customer_zip_code_prefix` AS `region_code` from `olist`.`customers`",
                columns = listOf("customer_id", "region_code"),
            )

        val resolved = PiiColumnResolver.resolve(pii, listOf(view), emptyList())

        assertTrue("customer_regions.region_code" in resolved, "결과: $resolved")
        assertTrue("customer_regions.customer_id" !in resolved, "결과: $resolved")
        assertTrue(pii.all { it in resolved }, "설정의 개인정보 컬럼이 남아야 한다: $resolved")
    }

    @Test
    fun `F-003 뷰를 거친 열 - COUNT 안에서만 쓴 뷰 열은 개인정보 컬럼이 아니다`() {
        val view =
            ViewDefinition(
                name = "state_counts",
                definition =
                    "select `customers`.`customer_state` AS `customer_state`," +
                        "count(distinct `customers`.`customer_unique_id`) AS `buyers` " +
                        "from `customers` group by `customers`.`customer_state`",
                columns = listOf("customer_state", "buyers"),
            )

        val resolved = PiiColumnResolver.resolve(pii, listOf(view), emptyList())

        assertEquals(pii.toSet(), resolved)
    }

    @Test
    fun `F-003 뷰를 거친 열 - 뷰가 뷰를 쓰면 끝까지 따라간다`() {
        // 앞의 뷰가 뒤의 뷰를 쓰도록 순서를 뒤집어 반복이 필요하게 한다
        val outer =
            ViewDefinition(
                name = "regions_upper",
                definition = "select upper(`r`.`code`) AS `region` from `regions` `r`",
                columns = listOf("region"),
            )
        val inner =
            ViewDefinition(
                name = "regions",
                definition = "select `customers`.`customer_zip_code_prefix` AS `code` from `customers`",
                columns = listOf("code"),
            )

        val resolved = PiiColumnResolver.resolve(pii, listOf(outer, inner), emptyList())

        assertTrue("regions.code" in resolved, "결과: $resolved")
        assertTrue("regions_upper.region" in resolved, "결과: $resolved")
    }

    @Test
    fun `F-003 정의를 읽을 수 없는 뷰 - 정의가 비면 그 뷰의 모든 열이 개인정보 컬럼이 된다`() {
        val view = ViewDefinition(name = "hidden", definition = "", columns = listOf("order_id", "amount"))

        val resolved = PiiColumnResolver.resolve(pii, listOf(view), emptyList())

        assertTrue("hidden.order_id" in resolved && "hidden.amount" in resolved, "결과: $resolved")
    }

    @Test
    fun `F-003 정의를 읽을 수 없는 뷰 - 정의를 해석하지 못하면 그 뷰의 모든 열이 개인정보 컬럼이 된다`() {
        val view = ViewDefinition(name = "broken", definition = "select ((( from", columns = listOf("a", "b"))

        val resolved = PiiColumnResolver.resolve(pii, listOf(view), emptyList())

        assertTrue("broken.a" in resolved && "broken.b" in resolved, "결과: $resolved")
    }

    @Test
    fun `F-003 정의를 읽을 수 없는 뷰 - 정의의 항목을 뷰 열에 맞출 수 없으면 모든 열이 개인정보 컬럼이 된다`() {
        val view =
            ViewDefinition(
                name = "mismatch",
                definition = "select `orders`.`order_id` AS `order_id` from `orders`",
                columns = listOf("order_id", "extra"),
            )

        val resolved = PiiColumnResolver.resolve(pii, listOf(view), emptyList())

        assertTrue("mismatch.order_id" in resolved && "mismatch.extra" in resolved, "결과: $resolved")
    }

    @Test
    fun `F-003 개인정보 컬럼으로 만든 생성 컬럼 - 개인정보 컬럼을 잘라 계산하면 개인정보 컬럼이 된다`() {
        val generated =
            listOf(
                GeneratedColumn("customers", "zip_area", "left(`customer_zip_code_prefix`,3)"),
                GeneratedColumn("customers", "state_lower", "lower(`customer_state`)"),
            )

        val resolved = PiiColumnResolver.resolve(pii, emptyList(), generated)

        assertTrue("customers.zip_area" in resolved, "결과: $resolved")
        assertTrue("customers.state_lower" !in resolved, "결과: $resolved")
    }

    @Test
    fun `F-003 개인정보 컬럼으로 만든 생성 컬럼 - 생성식을 해석하지 못하면 개인정보 컬럼이 된다`() {
        val generated = listOf(GeneratedColumn("orders", "weird", "((( not sql"))

        val resolved = PiiColumnResolver.resolve(pii, emptyList(), generated)

        assertTrue("orders.weird" in resolved, "결과: $resolved")
    }

    @Test
    fun `F-003 개인정보 컬럼으로 만든 생성 컬럼 - 생성 컬럼을 거친 뷰 열과 생성 컬럼을 쓰는 생성 컬럼도 따라간다`() {
        val generated =
            listOf(
                GeneratedColumn("customers", "zip_area_upper", "upper(`zip_area`)"),
                GeneratedColumn("customers", "zip_area", "left(`customer_zip_code_prefix`,3)"),
            )
        val view =
            ViewDefinition(
                name = "areas",
                definition = "select `customers`.`zip_area` AS `area` from `customers`",
                columns = listOf("area"),
            )

        val resolved = PiiColumnResolver.resolve(pii, listOf(view), generated)

        assertTrue("customers.zip_area_upper" in resolved, "결과: $resolved")
        assertTrue("areas.area" in resolved, "결과: $resolved")
    }
}
