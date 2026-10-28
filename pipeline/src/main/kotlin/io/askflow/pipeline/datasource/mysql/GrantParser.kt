package io.askflow.pipeline.datasource.mysql

/**
 * `SHOW GRANTS FOR CURRENT_USER()` 줄을 해석해 조회가 아닌 권한을 찾는다 (design 5장).
 * 범위가 `*.*`이거나 설정 스키마(`olist`.*, `olist`.`table`)인 줄만 본다.
 * {SELECT, SHOW VIEW, USAGE} 밖의 권한은 모두 경고 대상이다(허용 목록 방식).
 */
internal class GrantParser(
    /** 대소문자를 무시하고 비교한다 */
    private val schema: String,
) {
    fun parse(grantLines: List<String>): GrantCheck = TODO()
}

internal data class GrantCheck(
    /** 조회가 아닌 권한 이름. 중복 없이 처음 나온 순서대로 */
    val writePrivileges: List<String>,
    /** 역할 줄, 해석하지 못한 줄처럼 권한을 끝까지 확인하지 못한 경우 */
    val warnings: List<String>,
)
